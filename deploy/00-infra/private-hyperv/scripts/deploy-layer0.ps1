#Requires -RunAsAdministrator
#Requires -Version 5.1
<#
.SYNOPSIS
  Deploys this self-contained Layer 0 directory on its own: host
  preparation, OpenTofu plan, a typed confirmation, apply, a read-only check
  and the hand-over files for any configuration-management consumer.

.DESCRIPTION
  Needs nothing outside this directory. Runs end to end on the Hyper-V
  host, from any working directory, in an elevated PowerShell:

    1. Preflight   - Hyper-V module, OpenTofu >= 1.6 on PATH, terraform.tfvars.
    2. tofu init   - with -lockfile=readonly: only the committed, hashed
                     provider versions are accepted.
    3. Settings    - terraform.tfvars read through `tofu console`, so the
                     host preparation uses exactly the values the plan uses
                     (paths, switch, NAT prefix, gateway, node count, disks).
    4. Host prep   - scripts/prep-hyperv-host.ps1 with those values (it asks
                     before each change). Its capacity check is given the
                     space the planned disks still need to grow into: the
                     worst case (every OS disk at the template size, every
                     data disk at its size) minus what existing Layer 0 disks
                     already occupy, and at least -MinFreeGB.
                     Stops if the switch or the read-only template is missing
                     afterwards (for example after enabling Hyper-V, which
                     needs a reboot).
    5. Plan        - saved plan; a summary of creates/updates/deletes. A plan
                     that deletes or replaces anything is refused unless
                     -AllowDestroy is passed.
    6. Apply       - only after you type "apply". Nothing is applied with
                     -PlanOnly.
    7. Check       - scripts/check-layer0.ps1 (read-only), waiting for the
                     guests to boot.
    8. Hand-over   - out\ansible-inventory.ini, out\nodes.json and
                     out\network.json (git-ignored). A consumer copies them
                     into its own configuration; this directory never writes
                     anywhere else.

  Re-running is safe: host preparation is idempotent and an unchanged
  cluster plans "No changes", which skips straight to verification.

.PARAMETER PlanOnly
  Stop after showing the plan. Nothing is created or changed by OpenTofu.

.PARAMETER SkipHostPrep
  Don't run prep-hyperv-host.ps1 (the host is already prepared). The same
  capacity check still runs.

.PARAMETER AllowOvercommit
  Require only -MinFreeGB free instead of room for every disk to grow to
  its maximum. The check prints the shortfall; a full volume makes Hyper-V
  pause and then turn off the VMs, so watch free space afterwards.

.PARAMETER AllowDestroy
  Accept a plan that deletes or replaces resources (after review), for
  example a planned node rebuild.

.EXAMPLE
  .\scripts\deploy-layer0.ps1 -PlanOnly
  .\scripts\deploy-layer0.ps1
#>
[CmdletBinding()]
param(
    [switch] $PlanOnly,
    [switch] $SkipHostPrep,
    [switch] $AllowDestroy,
    [switch] $AllowOvercommit,
    [ValidateRange(1, 100000)]
    [int] $MinFreeGB = 40,
    [ValidateRange(20, 512)]
    [int] $TemplateSizeGB = 30,
    [ValidateRange(0, 60)]
    [int] $WaitMinutes = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Layer0.psm1') -Force
. (Join-Path $PSScriptRoot 'StoragePreflight.ps1')
$layerRoot = Split-Path -Parent $PSScriptRoot
$planFile = 'layer0.tfplan'

function Write-Step([string] $Text) { Write-Host "==> $Text" -ForegroundColor Cyan }

Push-Location $layerRoot
try {
    # --- 1. Preflight -----------------------------------------------------------------
    Write-Step 'Preflight'
    if (-not (Get-Module -ListAvailable -Name Hyper-V)) {
        throw 'The Hyper-V PowerShell module is not installed. Enable Hyper-V (with management tools) first.'
    }
    if (-not (Get-Command tofu -ErrorAction SilentlyContinue)) {
        throw 'tofu is not on PATH. Install OpenTofu (winget install --exact --id=OpenTofu.Tofu), open a new elevated PowerShell and re-run.'
    }
    $tofuVersion = [version](((& tofu version -json | Out-String | ConvertFrom-Json).terraform_version) -replace '[-+].*$', '')
    if ($tofuVersion -lt [version]'1.6.0') { throw "OpenTofu $tofuVersion is too old; Layer 0 needs >= 1.6." }
    Write-Host "    OpenTofu $tofuVersion"
    if (-not (Test-Path -LiteralPath 'terraform.tfvars')) {
        throw "terraform.tfvars not found in $layerRoot. Copy terraform.tfvars.example to terraform.tfvars and set vm_root, template_vhdx_path, ssh_public_key_path and nodes."
    }

    # --- 2. init ------------------------------------------------------------------------
    Write-Step 'tofu init (locked provider versions)'
    Invoke-Tofu -Arguments @('init', '-input=false', '-lockfile=readonly', '-no-color') | Out-Null
    Write-Host '    done'

    # --- 3. Settings from terraform.tfvars ----------------------------------------------------
    Write-Step 'Settings (terraform.tfvars, validated by OpenTofu)'
    $s = Get-Layer0Settings
    $nodeNames = @($s.nodes.PSObject.Properties.Name | Sort-Object)
    $dataGB = 0
    foreach ($n in $nodeNames) { $dataGB += [int]$s.nodes.$n.data_disk_gb }
    $templateParts = Split-WindowsPath $s.template_vhdx_path
    $templateRoot = $templateParts.Parent
    $templateName = $templateParts.Leaf
    Write-Host "    vm_root   $($s.vm_root)"
    Write-Host "    template  $($s.template_vhdx_path)"
    Write-Host "    network   $($s.cidr) via $($s.gateway) on switch $($s.switch_name)"
    foreach ($n in $nodeNames) { Write-Host ("    node      {0,-16} {1,-14} {2}" -f $n, $s.nodes.$n.ipv4, $s.nodes.$n.role) }

    # --- 4. Capacity and host preparation --------------------------------------------------
    $worstGB = Get-WorstCaseGB -NodeCount $nodeNames.Count -TemplateSizeGB $TemplateSizeGB -DataDiskGB $dataGB
    $existingGB = Get-ExistingLayer0DiskGB -VmRoot $s.vm_root -NodeNames $nodeNames
    $vmFreeGB = Get-RequiredVmFreeGB -WorstCaseGB $worstGB -ExistingDiskGB $existingGB -MinFreeGB $MinFreeGB
    if ($AllowOvercommit -and $vmFreeGB -gt $MinFreeGB) {
        Write-Warning ("-AllowOvercommit: requiring only {0} GB free although the planned disks may still need {1} GB. A full volume stops the VMs." -f $MinFreeGB, $vmFreeGB)
        $vmFreeGB = $MinFreeGB
    }
    $templateReady = (Test-Path -LiteralPath $s.template_vhdx_path) -and (Get-Item -LiteralPath $s.template_vhdx_path).IsReadOnly
    # About 40 GB covers the image download, conversion and the 30 GB template;
    # a finished template needs no more (the check requires at least 1).
    $templateFreeGB = if ($templateReady) { 1 } else { 40 }
    Write-Host ("    disks can grow to {0} GB, existing Layer 0 disks use {1} GB: {2} GB must be free for VMs (minimum {3})" -f $worstGB, $existingGB, $vmFreeGB, $MinFreeGB)
    if ($SkipHostPrep) {
        Write-Step 'Host preparation (skipped); capacity check only'
        Assert-Layer0StorageCapacity -VmRoot $s.vm_root -TemplateRoot $templateRoot -VmFreeGB $vmFreeGB -TemplateFreeGB $templateFreeGB
        Write-Host '    enough free space'
    }
    else {
        Write-Step 'Host preparation (scripts/prep-hyperv-host.ps1)'
        $prep = @{
            VmRoot         = $s.vm_root
            TemplateRoot   = $templateRoot
            TemplateName   = $templateName
            SwitchName     = $s.switch_name
            NatPrefix      = $s.cidr
            GatewayAddress = $s.gateway
            TemplateSizeGB = $TemplateSizeGB
            VmFreeGB       = $vmFreeGB
            TemplateFreeGB = $templateFreeGB
        }
        & (Join-Path $PSScriptRoot 'prep-hyperv-host.ps1') @prep
    }
    # Gate: the prep script returns early (without throwing) when it has just
    # enabled Hyper-V and a reboot is needed, and a declined prompt skips a
    # step. Don't plan against a host that is not ready.
    if (-not (Get-VMSwitch -Name $s.switch_name -ErrorAction SilentlyContinue)) {
        throw "vSwitch '$($s.switch_name)' does not exist. Complete host preparation (reboot first if Hyper-V was just enabled) and re-run."
    }
    if (-not ((Test-Path -LiteralPath $s.template_vhdx_path) -and (Get-Item -LiteralPath $s.template_vhdx_path).IsReadOnly)) {
        throw "Template $($s.template_vhdx_path) is missing or not marked read-only (an incomplete build). Re-run without -SkipHostPrep."
    }

    # --- 5. Plan ------------------------------------------------------------------------------
    Write-Step 'tofu plan'
    if (Test-Path -LiteralPath $planFile) { Remove-Item -LiteralPath $planFile -Force }
    $plan = Invoke-Tofu -Arguments @('plan', '-input=false', '-no-color', '-detailed-exitcode', "-out=$planFile") -AllowedExitCodes @(0, 2)
    $plan.Output | Write-Host
    $applied = $false
    if ($plan.ExitCode -eq 0) {
        Write-Host '    No changes: the VMs already match terraform.tfvars.'
        Remove-Item -LiteralPath $planFile -Force -ErrorAction SilentlyContinue
    }
    else {
        $planJson = (Invoke-Tofu -Arguments @('show', '-json', $planFile)).Output -join "`n" | ConvertFrom-Json
        $sum = Get-PlanActionSummary -Plan $planJson
        Write-Host ("    create {0}, update {1}, delete {2} (replace {3})" -f $sum.Create, $sum.Update, $sum.Delete, $sum.Replace.Count)
        if ($sum.Delete -gt 0 -and -not $AllowDestroy) {
            Remove-Item -LiteralPath $planFile -Force
            throw ("The plan deletes or replaces: {0}. Nothing was applied. Review it and re-run with -AllowDestroy if that is intended (a replaced VM loses everything on it)." -f ($sum.DeleteAddresses -join ', '))
        }
        if ($PlanOnly) {
            Remove-Item -LiteralPath $planFile -Force
            Write-Host 'Plan only: nothing was applied.' -ForegroundColor Yellow
            return
        }

        # --- 6. Apply ---------------------------------------------------------------------------
        $answer = Read-Host "Type 'apply' to apply exactly this plan (anything else cancels)"
        if ($answer -ne 'apply') {
            Remove-Item -LiteralPath $planFile -Force
            Write-Host 'Cancelled: nothing was applied.' -ForegroundColor Yellow
            return
        }
        Write-Step 'tofu apply'
        try { Invoke-Tofu -Arguments @('apply', '-input=false', $planFile) -Interactive | Out-Null }
        finally { Remove-Item -LiteralPath $planFile -Force -ErrorAction SilentlyContinue }
        $applied = $true
    }
    if ($PlanOnly) { return }

    # --- 7. Check -----------------------------------------------------------------------------
    Write-Step 'Check (read-only)'
    $wait = if ($applied) { $WaitMinutes } else { 0 }
    & (Join-Path $PSScriptRoot 'check-layer0.ps1') -WaitMinutes $wait -MinFreeGB $MinFreeGB -TemplateSizeGB $TemplateSizeGB
    if ($LASTEXITCODE -ne 0) { throw 'Layer 0 check failed; see the failures above. The hand-over files were not written.' }

    # --- 8. Hand-over ------------------------------------------------------------------------
    Write-Step 'Hand-over files (out\, git-ignored)'
    $outDir = Join-Path $layerRoot 'out'
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    $ini = (Invoke-Tofu -Arguments @('output', '-raw', 'ansible_inventory_ini')).Output -join "`n"
    ConvertTo-Utf8NoBomFile -Path (Join-Path $outDir 'ansible-inventory.ini') -Text $ini
    ConvertTo-Utf8NoBomFile -Path (Join-Path $outDir 'nodes.json') -Text ((Invoke-Tofu -Arguments @('output', '-json', 'nodes')).Output -join "`n")
    ConvertTo-Utf8NoBomFile -Path (Join-Path $outDir 'network.json') -Text ((Invoke-Tofu -Arguments @('output', '-json', 'network')).Output -join "`n")
    Write-Host "    $outDir\ansible-inventory.ini, nodes.json, network.json"
    Write-Host ''
    Write-Host 'Layer 0 is deployed and checked. Hand out\ansible-inventory.ini to your configuration-management layer.' -ForegroundColor Green
}
finally { Pop-Location }
