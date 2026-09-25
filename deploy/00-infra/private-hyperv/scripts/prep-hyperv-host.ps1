#Requires -RunAsAdministrator
#Requires -Version 5.1
<#
.SYNOPSIS
  Prepares a Windows Hyper-V host for Layer 0 (deploy/00-infra/private-hyperv).

.DESCRIPTION
  Idempotent: safe to re-run; each step checks the current state first.
  Every change goes through ShouldProcess, so -WhatIf previews it and
  -Confirm:$false runs it without prompts.

    1. Hyper-V      - checks the feature (enabling it needs a reboot).
    2. Switch + NAT - Internal vSwitch, the host's gateway address on it, and a
                      Windows NAT for the VM subnet. Optional port forward to
                      the Kubernetes API.
    3. Directories  - VM and template folders.
    4. Template     - downloads the Ubuntu cloud image, verifies it against
                      Canonical's SHA256SUMS, converts it to VHDX with qemu-img,
                      grows it, and marks it read-only. VMs use it as the parent
                      of their differencing OS disks.
    5. WinRM HTTPS  - listener on 5986 (self-signed certificate) and a firewall
                      rule, for the Terraform Hyper-V provider.

  The defaults match terraform.tfvars.example.

.PARAMETER ApiServerForwardTo
  Optional. The control-plane node's address (e.g. 10.20.0.10). When set,
  TCP 6443 on every host address is forwarded to it, so kubectl on the LAN can
  reach the API. Without it, the API is reachable from this host only.

.PARAMETER QemuImgPath
  qemu-img.exe from QEMU for Windows. Needed only to build the template.

.EXAMPLE
  .\prep-hyperv-host.ps1 -WhatIf
  .\prep-hyperv-host.ps1
  .\prep-hyperv-host.ps1 -ApiServerForwardTo 10.20.0.10 -WinRmAllowedRemoteAddress 192.168.1.50
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string] $SwitchName = 'iotee-nat',

    [string] $NatName = 'iotee-nat',

    # VM subnet and the host's address on it (the VMs' default gateway).
    [string] $NatPrefix = '10.20.0.0/24',
    [string] $GatewayAddress = '10.20.0.1',

    [string] $VmRoot = 'D:\HyperV\iotee\vms',
    [string] $TemplateRoot = 'D:\HyperV\iotee\templates',
    [string] $TemplateName = 'ubuntu-noble-base.vhdx',

    # Ubuntu release codename. The standard (not "minimal") cloud image ships
    # the generic virtual kernel with Hyper-V drivers.
    [string] $UbuntuRelease = 'noble',

    [ValidateRange(20, 512)]
    [int] $TemplateSizeGB = 30,

    [string] $QemuImgPath = 'qemu-img.exe',

    [string] $ApiServerForwardTo = '',

    [string[]] $WinRmAllowedRemoteAddress = @('LocalSubnet'),

    [switch] $SkipTemplate,
    [switch] $SkipWinRm
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-Step([string] $Text) { Write-Host "==> $Text" -ForegroundColor Cyan }

$prefixParts = $NatPrefix -split '/'
if ($prefixParts.Count -ne 2) { throw "NatPrefix must be CIDR, e.g. 10.20.0.0/24 (got '$NatPrefix')." }
[int] $prefixLength = $prefixParts[1]
if ($prefixLength -lt 8 -or $prefixLength -gt 30) { throw "NatPrefix length must be /8../30 (got /$prefixLength)." }

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
        $rule = 'IOT-EE Kubernetes API (NAT 6443)'
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

# --- 4. Golden template -----------------------------------------------------------
$templatePath = Join-Path $TemplateRoot $TemplateName
if ($SkipTemplate) {
    Write-Step 'Template (skipped)'
}
elseif (Test-Path -LiteralPath $templatePath) {
    Write-Step "Template $templatePath"
    Write-Host '    exists (templates are immutable; use -TemplateName for a new one)'
}
else {
    Write-Step "Template $templatePath"
    if (-not (Get-Command $QemuImgPath -ErrorAction SilentlyContinue)) {
        throw "qemu-img not found at '$QemuImgPath'. Install QEMU for Windows or pass -QemuImgPath."
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

        & $QemuImgPath convert -p -f qcow2 -O vhdx -o subformat=dynamic $imagePath $templatePath
        if ($LASTEXITCODE -ne 0) { throw "qemu-img convert failed ($LASTEXITCODE)." }
        & $QemuImgPath resize -f vhdx $templatePath "${TemplateSizeGB}G"
        if ($LASTEXITCODE -ne 0) { throw "qemu-img resize failed ($LASTEXITCODE)." }

        # Parent of every VM's differencing disk: must never change in place.
        Set-ItemProperty -LiteralPath $templatePath -Name IsReadOnly -Value $true
        Remove-Item -LiteralPath $imagePath -Force
        Write-Host '    built and marked read-only'
    }
}

# --- 5. WinRM over HTTPS for Terraform --------------------------------------------------
if ($SkipWinRm) {
    Write-Step 'WinRM (skipped)'
}
else {
    Write-Step 'WinRM HTTPS listener (5986)'
    $listener = Get-ChildItem -Path 'WSMan:\localhost\Listener' -ErrorAction SilentlyContinue |
        Where-Object { $_.Keys -contains 'Transport=HTTPS' }
    if (-not $listener) {
        if ($PSCmdlet.ShouldProcess('WinRM', 'Enable HTTPS listener with a self-signed certificate')) {
            Enable-PSRemoting -SkipNetworkProfileCheck -Force | Out-Null
            $cert = New-SelfSignedCertificate -DnsName $env:COMPUTERNAME, 'localhost' `
                -CertStoreLocation 'Cert:\LocalMachine\My' -NotAfter (Get-Date).AddYears(2)
            New-Item -Path 'WSMan:\localhost\Listener' -Transport HTTPS -Address * `
                -CertificateThumbPrint $cert.Thumbprint -Force | Out-Null
            Write-Host "    created (certificate $($cert.Thumbprint))"
        }
    }
    else { Write-Host '    exists' }

    $fwRule = 'IOT-EE WinRM HTTPS (Terraform)'
    if (-not (Get-NetFirewallRule -DisplayName $fwRule -ErrorAction SilentlyContinue)) {
        if ($PSCmdlet.ShouldProcess($fwRule, "Allow TCP 5986 from $($WinRmAllowedRemoteAddress -join ', ')")) {
            New-NetFirewallRule -DisplayName $fwRule -Direction Inbound -Protocol TCP -LocalPort 5986 `
                -RemoteAddress $WinRmAllowedRemoteAddress -Action Allow | Out-Null
        }
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
