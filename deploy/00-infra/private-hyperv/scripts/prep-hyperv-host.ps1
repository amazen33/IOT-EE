#Requires -RunAsAdministrator
#Requires -Version 5.1
<#
.SYNOPSIS
  Prepares a Windows Hyper-V host using this self-contained Layer 0 directory.

.DESCRIPTION
  Idempotent: safe to re-run; each step checks the current state first.
  Every change goes through ShouldProcess, so -WhatIf previews it and
  -Confirm:$false runs it without prompts.

    1. Capacity     - checks the chosen storage volumes before any mutation.
    2. Hyper-V      - checks the feature (enabling it needs a reboot).
    3. Switch + NAT - Internal vSwitch, the host's gateway address on it, and a
                      Windows NAT for the VM subnet. Optional port forward to
                      the Kubernetes API.
    4. Directories  - VM and template folders.
    5. qemu-img     - downloads QEMU for Windows (Stefan Weil's builds, linked
                      from qemu.org/download; no checksum is published for
                      them, so this is HTTPS-only, not hash-verified -- see
                      the .PARAMETER QemuImgPath notes) and installs it
                      silently to -QemuInstallRoot. Skipped if qemu-img.exe is
                      already on PATH or already at -QemuInstallRoot.
    6. Template     - downloads the Ubuntu cloud image, verifies it against
                      Canonical's SHA256SUMS, converts it to VHDX with
                      qemu-img, grows it with Hyper-V's Resize-VHD (qemu-img
                      can convert into vhdx but not resize one), and marks it
                      read-only. VMs use it as the parent of their
                      differencing OS disks. A file left behind by a build
                      that failed partway through isn't read-only, so the
                      next run detects and replaces it rather than treating
                      it as done.
  OpenTofu uses the local Hyper-V provider; WinRM is not required or enabled.
  Supply storage paths explicitly and use the same values in terraform.tfvars.

.PARAMETER ApiServerForwardTo
  Optional. The control-plane node's address (e.g. 10.20.0.10). When set,
  TCP 6443 on every host address is forwarded to it, so kubectl on the LAN can
  reach the API. Without it, the API is reachable from this host only.

.PARAMETER QemuImgPath
  qemu-img.exe from QEMU for Windows. Needed only to build the template. If
  this parameter is not explicitly passed, it is resolved automatically: PATH,
  then -QemuInstallRoot, installing there if neither has it (see
  -SkipQemuInstall). That installer (qemu.weilnetz.de, linked from
  qemu.org/download) publishes no checksum, so the download is HTTPS-only:
  origin and transport integrity from TLS, no hash to verify the file's
  contents against. Its SHA-256 and Authenticode signature status (if any)
  are printed so you can compare them yourself. If you'd rather not trust
  that, install QEMU yourself from a source you trust and pass -QemuImgPath.

.PARAMETER QemuInstallRoot
  Where to silently install QEMU for Windows if qemu-img.exe isn't already on
  PATH. Defaults next to this script, not Program Files, so it needs no extra
  admin consent beyond what the script already requires.

.PARAMETER SkipQemuInstall
  Don't auto-install QEMU. Fails the template step if qemu-img still can't be
  found, with instructions to install it or pass -QemuImgPath.

.EXAMPLE
  .\prep-hyperv-host.ps1 -VmRoot 'E:\HyperV\lab\vms' -TemplateRoot 'E:\HyperV\lab\templates' -WhatIf
  .\prep-hyperv-host.ps1 -VmRoot 'E:\HyperV\lab\vms' -TemplateRoot 'E:\HyperV\lab\templates'
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string] $SwitchName = 'iotee-nat',

    [string] $NatName = 'iotee-nat',

    # VM subnet and the host's address on it (the VMs' default gateway).
    [string] $NatPrefix = '10.20.0.0/24',
    [string] $GatewayAddress = '10.20.0.1',

    [Parameter(Mandatory = $true)][string] $VmRoot,
    [Parameter(Mandatory = $true)][string] $TemplateRoot,
    [string] $TemplateName = 'ubuntu-noble-base.vhdx',

    # Defaults reserve room for the example's 160 GB of data disks, three
    # 30 GB OS disks, a 30 GB template, downloads, and growth headroom.
    [ValidateRange(1, 100000)][int] $VmFreeGB = 280,
    [ValidateRange(1, 100000)][int] $TemplateFreeGB = 40,

    # Ubuntu release codename. The standard (not "minimal") cloud image ships
    # the generic virtual kernel with Hyper-V drivers.
    [string] $UbuntuRelease = 'noble',

    [ValidateRange(20, 512)]
    [int] $TemplateSizeGB = 30,

    [string] $QemuImgPath = 'qemu-img.exe',

    # Local, not Program Files: this script already requires admin, but an
    # install under its own folder needs no separate consent and is easy to
    # remove (delete the folder) without touching anything system-wide.
    [string] $QemuInstallRoot = (Join-Path $PSScriptRoot 'tools\qemu'),

    # Pinned; bump with -QemuInstallerVersion rather than editing the script.
    # Checked against https://qemu.weilnetz.de/w64/ on 2026-09-25.
    [string] $QemuInstallerVersion = '20260811',

    [string] $ApiServerForwardTo = '',

    [switch] $SkipTemplate,
    [switch] $SkipQemuInstall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'StoragePreflight.ps1')

function Write-Step([string] $Text) { Write-Host "==> $Text" -ForegroundColor Cyan }

$prefixParts = $NatPrefix -split '/'
if ($prefixParts.Count -ne 2) { throw "NatPrefix must be CIDR, e.g. 10.20.0.0/24 (got '$NatPrefix')." }
[int] $prefixLength = $prefixParts[1]
if ($prefixLength -lt 8 -or $prefixLength -gt 30) { throw "NatPrefix length must be /8../30 (got /$prefixLength)." }

# Do this before changing the host. If both roots share a volume the two
# reservations are added, preventing a second check from double-counting space.
Assert-Layer0StorageCapacity -VmRoot $VmRoot -TemplateRoot $TemplateRoot -VmFreeGB $VmFreeGB -TemplateFreeGB $TemplateFreeGB

# --- 1. Hyper-V ----------------------------------------------------------------
Write-Step 'Hyper-V feature'
$isServer = (Get-CimInstance Win32_OperatingSystem).ProductType -ne 1
if ($isServer) {
    $hv = Get-WindowsFeature -Name Hyper-V
    if (-not $hv.Installed) {
        if ($PSCmdlet.ShouldProcess('Hyper-V', 'Install Windows Server role (reboot required)')) {
            Install-WindowsFeature -Name Hyper-V -IncludeManagementTools | Out-Null
            Write-Warning 'Hyper-V installed. Reboot, then run this script again.'
            return
        }
    }
}
else {
    $hv = Get-WindowsOptionalFeature -Online -FeatureName Microsoft-Hyper-V-All
    if ($hv.State -ne 'Enabled') {
        if ($PSCmdlet.ShouldProcess('Microsoft-Hyper-V-All', 'Enable Windows feature (reboot required)')) {
            Enable-WindowsOptionalFeature -Online -FeatureName Microsoft-Hyper-V-All -All -NoRestart | Out-Null
            Write-Warning 'Hyper-V enabled. Reboot, then run this script again.'
            return
        }
    }
}
Write-Host '    enabled'

# --- 2. Internal switch, gateway address, NAT ----------------------------------------
Write-Step "Internal switch '$SwitchName'"
$switch = Get-VMSwitch -Name $SwitchName -ErrorAction SilentlyContinue
if ($switch -and $switch.SwitchType -ne 'Internal') {
    throw "vSwitch '$SwitchName' exists but is $($switch.SwitchType), not Internal. Rename or remove it first."
}
if (-not $switch) {
    if ($PSCmdlet.ShouldProcess($SwitchName, 'Create Internal vSwitch')) {
        New-VMSwitch -Name $SwitchName -SwitchType Internal | Out-Null
        Write-Host '    created'
    }
}
else { Write-Host '    exists' }

Write-Step "Host gateway address $GatewayAddress/$prefixLength"
$hostAdapter = Get-NetAdapter -Name "vEthernet ($SwitchName)" -ErrorAction SilentlyContinue
if ($hostAdapter) {
    $existing = Get-NetIPAddress -InterfaceIndex $hostAdapter.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -eq $GatewayAddress }
    if (-not $existing) {
        if ($PSCmdlet.ShouldProcess("vEthernet ($SwitchName)", "Assign $GatewayAddress/$prefixLength")) {
            New-NetIPAddress -InterfaceIndex $hostAdapter.ifIndex -IPAddress $GatewayAddress -PrefixLength $prefixLength | Out-Null
            Write-Host '    assigned'
        }
    }
    else { Write-Host '    exists' }
}
elseif (-not $WhatIfPreference) {
    throw "Host adapter 'vEthernet ($SwitchName)' not found after creating the switch."
}

Write-Step "NAT '$NatName' for $NatPrefix"
$allNat = @(Get-NetNat -ErrorAction SilentlyContinue)
$nat = $allNat | Where-Object { $_.Name -eq $NatName }
if ($nat -and $nat.InternalIPInterfaceAddressPrefix -ne $NatPrefix) {
    throw "NAT '$NatName' exists with prefix $($nat.InternalIPInterfaceAddressPrefix), not $NatPrefix."
}
if (-not $nat) {
    $other = $allNat | Where-Object { $_.Name -ne $NatName }
    if ($other) {
        # WinNAT supports a single internal prefix per host.
        throw ("Another NAT already exists on this host ('{0}', {1}). Windows NAT supports only one; " +
            'remove it or reuse its prefix.') -f $other[0].Name, $other[0].InternalIPInterfaceAddressPrefix
    }
    if ($PSCmdlet.ShouldProcess($NatName, "Create NAT for $NatPrefix")) {
        New-NetNat -Name $NatName -InternalIPInterfaceAddressPrefix $NatPrefix | Out-Null
        Write-Host '    created'
    }
}
else { Write-Host '    exists' }

if ($ApiServerForwardTo) {
    Write-Step "Port forward host:6443 -> ${ApiServerForwardTo}:6443"
    $mapping = Get-NetNatStaticMapping -NatName $NatName -ErrorAction SilentlyContinue |
        Where-Object { $_.ExternalPort -eq 6443 -and $_.Protocol -eq 'TCP' }
    if ($mapping -and $mapping.InternalIPAddress -ne $ApiServerForwardTo) {
        throw "Port 6443 is already forwarded to $($mapping.InternalIPAddress)."
    }
    if (-not $mapping) {
        if ($PSCmdlet.ShouldProcess("${ApiServerForwardTo}:6443", 'Add NAT static mapping from host TCP 6443')) {
            Add-NetNatStaticMapping -NatName $NatName -Protocol TCP -ExternalIPAddress '0.0.0.0' -ExternalPort 6443 `
                -InternalIPAddress $ApiServerForwardTo -InternalPort 6443 | Out-Null
        }
        $rule = "$NatName Kubernetes API (NAT 6443)"
        if (-not (Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue)) {
            if ($PSCmdlet.ShouldProcess($rule, 'Allow inbound TCP 6443')) {
                New-NetFirewallRule -DisplayName $rule -Direction Inbound -Protocol TCP -LocalPort 6443 -Action Allow | Out-Null
            }
        }
        Write-Host '    forwarded'
    }
    else { Write-Host '    exists' }
}

# --- 3. Directories -------------------------------------------------------------
Write-Step 'Directories'
foreach ($dir in @($VmRoot, $TemplateRoot)) {
    if (-not (Test-Path -LiteralPath $dir)) {
        if ($PSCmdlet.ShouldProcess($dir, 'Create directory')) {
            New-Item -ItemType Directory -Path $dir | Out-Null
            Write-Host "    created $dir"
        }
    }
    else { Write-Host "    exists  $dir" }
}

# --- 4. qemu-img ----------------------------------------------------------------
# Resolution order: an explicitly passed -QemuImgPath (existing behaviour,
# unchanged: throws if that exact path doesn't work); else PATH; else
# -QemuInstallRoot, installing there if it's empty and -SkipQemuInstall wasn't
# passed. Skipped entirely with -SkipTemplate (qemu-img is only needed to
# build the template).
$resolvedQemuImgPath = $QemuImgPath
if ($SkipTemplate) {
    Write-Step 'qemu-img (skipped, -SkipTemplate)'
}
elseif ($PSBoundParameters.ContainsKey('QemuImgPath')) {
    Write-Step "qemu-img (explicit path)"
    Write-Host "    using $QemuImgPath"
}
else {
    Write-Step 'qemu-img'
    $onPath = Get-Command 'qemu-img.exe' -ErrorAction SilentlyContinue
    $localInstall = Join-Path $QemuInstallRoot 'qemu-img.exe'
    if ($onPath) {
        $resolvedQemuImgPath = $onPath.Source
        Write-Host "    found on PATH: $resolvedQemuImgPath"
    }
    elseif (Test-Path -LiteralPath $localInstall) {
        $resolvedQemuImgPath = $localInstall
        Write-Host "    found: $resolvedQemuImgPath"
    }
    elseif ($SkipQemuInstall) {
        throw "qemu-img not found on PATH or at '$localInstall'. Install QEMU for " +
            'Windows, or pass -QemuImgPath, or drop -SkipQemuInstall to auto-install.'
    }
    else {
        $setupName = "qemu-w64-setup-$QemuInstallerVersion.exe"
        $setupUrl = "https://qemu.weilnetz.de/w64/$setupName"
        if ($PSCmdlet.ShouldProcess($QemuInstallRoot, "Download and silently install QEMU for Windows $QemuInstallerVersion (qemu-img.exe)")) {
            $work = Join-Path $TemplateRoot '.download'
            New-Item -ItemType Directory -Path $work -Force | Out-Null
            $setupPath = Join-Path $work $setupName

            Write-Host "    downloading $setupUrl"
            Invoke-WebRequest -Uri $setupUrl -OutFile $setupPath -UseBasicParsing

            # Stefan Weil's third-party build, linked from qemu.org's own
            # download page, but qemu.org neither hosts nor signs it, and it
            # publishes no checksum file (checked .sha512/.sha256/.sha1/
            # SHA512SUMS/sha512sum.txt on 2026-09-25: all 404). Unlike the
            # Ubuntu image below, there is nothing to verify this download
            # against. Two informational-only checks instead of a real one:
            # the installer's Authenticode signature (if any) and its hash,
            # printed so you can compare against VirusTotal or a past run
            # yourself. HTTPS still guarantees you got this from
            # qemu.weilnetz.de unmodified in transit.
            $sha256 = (Get-FileHash -LiteralPath $setupPath -Algorithm SHA256).Hash
            Write-Host "    SHA-256: $sha256 (no vendor checksum exists to compare it against)"
            $sig = Get-AuthenticodeSignature -LiteralPath $setupPath
            if ($sig.Status -eq 'Valid') {
                Write-Host "    Authenticode: Valid, signed by $($sig.SignerCertificate.Subject)"
            }
            else {
                Write-Warning "Authenticode signature is $($sig.Status): $setupName is unsigned or its signature doesn't validate. Proceeding anyway (pass -SkipQemuInstall and install it yourself first if you'd rather not)."
            }

            New-Item -ItemType Directory -Path $QemuInstallRoot -Force | Out-Null
            # NSIS silent install: /S silent, /D=dir sets the install directory
            # (must be the last argument, no quotes, no trailing backslash).
            $proc = Start-Process -FilePath $setupPath -ArgumentList @('/S', "/D=$QemuInstallRoot") -Wait -PassThru
            if ($proc.ExitCode -ne 0) { throw "QEMU installer exited with code $($proc.ExitCode)." }
            Remove-Item -LiteralPath $setupPath -Force

            if (-not (Test-Path -LiteralPath $localInstall)) {
                throw "Installed QEMU to $QemuInstallRoot but qemu-img.exe isn't there. Installer layout may have changed; check $QemuInstallRoot manually."
            }
            $resolvedQemuImgPath = $localInstall
            Write-Host "    installed: $resolvedQemuImgPath"
        }
    }
}

# --- 5. Golden template -----------------------------------------------------------
$templatePath = Join-Path $TemplateRoot $TemplateName
# Completion is marked by IsReadOnly (set only after a full, successful
# build), not by the file merely existing: a run that fails partway through
# (network error, qemu-img error, etc.) can leave a partial file behind, and
# treating that as "already built" would silently ship a broken template.
$templateExists = Test-Path -LiteralPath $templatePath
$templateComplete = $templateExists -and (Get-Item -LiteralPath $templatePath).IsReadOnly
if ($SkipTemplate) {
    Write-Step 'Template (skipped)'
}
elseif ($templateComplete) {
    Write-Step "Template $templatePath"
    Write-Host '    exists (templates are immutable; use -TemplateName for a new one)'
}
else {
    Write-Step "Template $templatePath"
    if ($templateExists) {
        Write-Warning "    $templatePath exists but isn't marked read-only -- a previous build likely failed partway through. Rebuilding it."
        if ($PSCmdlet.ShouldProcess($templatePath, 'Remove incomplete template')) {
            Remove-Item -LiteralPath $templatePath -Force
        }
    }
    if (-not (Get-Command $resolvedQemuImgPath -ErrorAction SilentlyContinue)) {
        throw "qemu-img not found at '$resolvedQemuImgPath'."
    }
    if ($PSCmdlet.ShouldProcess($templatePath, "Build from Ubuntu '$UbuntuRelease' cloud image")) {
        $baseUrl = "https://cloud-images.ubuntu.com/$UbuntuRelease/current"
        $imageName = "$UbuntuRelease-server-cloudimg-amd64.img"
        $work = Join-Path $TemplateRoot '.download'
        New-Item -ItemType Directory -Path $work -Force | Out-Null
        $imagePath = Join-Path $work $imageName
        $sumsPath = Join-Path $work 'SHA256SUMS'

        Write-Host "    downloading $baseUrl/$imageName"
        Invoke-WebRequest -Uri "$baseUrl/SHA256SUMS" -OutFile $sumsPath -UseBasicParsing
        Invoke-WebRequest -Uri "$baseUrl/$imageName" -OutFile $imagePath -UseBasicParsing

        $line = Get-Content -LiteralPath $sumsPath |
            Where-Object { $_ -match "\*?$([regex]::Escape($imageName))$" } | Select-Object -First 1
        if (-not $line) { throw "No checksum for $imageName in SHA256SUMS." }
        $expected = ($line -split '\s+')[0].ToUpperInvariant()
        $actual = (Get-FileHash -LiteralPath $imagePath -Algorithm SHA256).Hash
        if ($actual -ne $expected) { throw "Checksum mismatch for ${imageName}: expected $expected, got $actual." }
        Write-Host '    checksum OK'

        & $resolvedQemuImgPath convert -p -f qcow2 -O vhdx -o subformat=dynamic $imagePath $templatePath
        if ($LASTEXITCODE -ne 0) { throw "qemu-img convert failed ($LASTEXITCODE)." }

        # Not qemu-img resize: QEMU's vhdx driver doesn't support resizing a
        # VHDX file ("Image format driver does not support resize"), only
        # converting into one. Hyper-V's own cmdlet does support it -- but
        # first, qemu-img's Windows VHDX writer marks the file NTFS-sparse,
        # and Resize-VHD refuses a VHDX that is sparse, compressed or
        # encrypted ("must be uncompressed and unencrypted and must not be
        # sparse"). Clear whichever of those NTFS attributes are actually
        # set before resizing; harmless (a no-op) when they aren't.
        $attrs = (Get-Item -LiteralPath $templatePath).Attributes
        $isSparse = [bool]($attrs -band [System.IO.FileAttributes]::SparseFile)
        $isCompressed = [bool]($attrs -band [System.IO.FileAttributes]::Compressed)
        $isEncrypted = [bool]($attrs -band [System.IO.FileAttributes]::Encrypted)
        if ($isSparse -or $isCompressed -or $isEncrypted) {
            Write-Host "    clearing NTFS attributes before resize (sparse=$isSparse compressed=$isCompressed encrypted=$isEncrypted)"
            if ($isSparse) { & fsutil.exe sparse setflag $templatePath 0 | Out-Null }
            if ($isCompressed) { & compact.exe /U $templatePath | Out-Null }
            if ($isEncrypted) { & cipher.exe /D $templatePath | Out-Null }
        }
        try {
            Resize-VHD -Path $templatePath -SizeBytes ([int64]$TemplateSizeGB * 1GB)
        }
        catch {
            throw "Resize-VHD failed even after clearing sparse/compressed/encrypted attributes. " +
                "If $TemplateRoot sits on a volume with NTFS compression or BitLocker/EFS " +
                "enabled at the folder or volume level (not just the file), turn that off for " +
                "$TemplateRoot and retry. Underlying error: $($_.Exception.Message)"
        }

        # Parent of every VM's differencing disk: must never change in place.
        Set-ItemProperty -LiteralPath $templatePath -Name IsReadOnly -Value $true
        Remove-Item -LiteralPath $imagePath -Force
        Write-Host '    built and marked read-only'
    }
}

# --- Summary --------------------------------------------------------------------------
Write-Host ''
Write-Host 'Host ready. Matching terraform.tfvars values:' -ForegroundColor Green
Write-Host "  vm_root            = `"$($VmRoot -replace '\\', '\\')`""
Write-Host "  template_vhdx_path = `"$($templatePath -replace '\\', '\\')`""
Write-Host "  switch_name        = `"$SwitchName`""
Write-Host "  network.cidr       = `"$NatPrefix`""
Write-Host "  network.gateway    = `"$GatewayAddress`""
