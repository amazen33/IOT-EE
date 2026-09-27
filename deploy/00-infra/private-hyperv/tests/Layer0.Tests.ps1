<#
.SYNOPSIS
  Tests for scripts/Layer0.psm1. Plain PowerShell (no Pester needed); runs on
  Windows PowerShell 5.1 and PowerShell 7 on any OS.

    pwsh -NoProfile -File tests/Layer0.Tests.ps1
    pwsh -NoProfile -File tests/Layer0.Tests.ps1 -WithTofu

  Normally run through tests/verify.ps1, the directory's one test command.

  -WithTofu also evaluates the settings expression with the real `tofu
  console` against terraform.tfvars.example in a throwaway copy of the
  directory (needs `tofu` on PATH and provider downloads or a mirror). It
  uses a throwaway public key and never touches Hyper-V.
#>
param([switch] $WithTofu)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$layerRoot = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $layerRoot 'scripts/Layer0.psm1') -Force

$script:failed = 0
$script:passed = 0
function Assert([bool] $Condition, [string] $Name) {
    if ($Condition) { $script:passed++; Write-Host "ok   $Name" }
    else { $script:failed++; Write-Host "FAIL $Name" -ForegroundColor Red }
}
function Assert-Throws([scriptblock] $Block, [string] $Name) {
    try { & $Block; $script:failed++; Write-Host "FAIL $Name (no exception)" -ForegroundColor Red }
    catch { $script:passed++; Write-Host "ok   $Name" }
}

# --- ConvertFrom-TofuConsoleString -------------------------------------------------------
# Real `tofu console -no-color` output for the settings expression.
$literal = '"{\"admin_user\":\"iotee\",\"cidr\":\"10.20.0.0/24\",\"gateway\":\"10.20.0.1\",\"nodes\":{\"rke2-master-01\":{\"data_disk_gb\":40,\"ipv4\":\"10.20.0.10\",\"role\":\"control-plane\"},\"rke2-worker-01\":{\"data_disk_gb\":60,\"ipv4\":\"10.20.0.21\",\"role\":\"worker\"}},\"switch_name\":\"iotee-nat\",\"template_vhdx_path\":\"E:\\\\HyperV\\\\iotee\\\\templates\\\\ubuntu-noble-base.vhdx\",\"vm_root\":\"E:\\\\HyperV\\\\iotee\\\\vms\"}"'
$s = ConvertFrom-TofuConsoleString -Lines @($literal)
Assert ($s.vm_root -eq 'E:\HyperV\iotee\vms') 'console: Windows path with backslashes decoded'
Assert ($s.template_vhdx_path -eq 'E:\HyperV\iotee\templates\ubuntu-noble-base.vhdx') 'console: template path decoded'
Assert ($s.nodes.'rke2-worker-01'.ipv4 -eq '10.20.0.21') 'console: node address decoded'
Assert ([int]$s.nodes.'rke2-master-01'.data_disk_gb -eq 40) 'console: data disk size decoded'
# stdout also carries a warning block when a validation fails (the error goes to stderr).
$withWarning = @('', 'Warning: Due to the problems above, some expressions may produce unexpected results.', '', '', $literal)
Assert ((ConvertFrom-TofuConsoleString -Lines $withWarning).gateway -eq '10.20.0.1') 'console: warning lines before the value are ignored'
Assert-Throws { ConvertFrom-TofuConsoleString -Lines @('', 'no value here') } 'console: output without a quoted value is rejected'

# --- Test-TofuStderrFailed ------------------------------------------------------------------
$stderr = @('', 'Error: Invalid value for variable', '', '  on variables.tf line 88:', 'No SSH public key at ssh_public_key_path.')
Assert (Test-TofuStderrFailed -StderrLines $stderr) 'stderr: an Error: block is a failure even with exit code 0'
Assert (Test-TofuStderrFailed -StderrLines @([char]0x2502 + ' Error: Invalid value for variable')) 'stderr: boxed (colour-free) Error: line is a failure'
Assert (-not (Test-TofuStderrFailed -StderrLines @())) 'stderr: empty is not a failure'
Assert (-not (Test-TofuStderrFailed -StderrLines @('Warning: something'))) 'stderr: a warning alone is not a failure'

# --- Get-PlanActionSummary -------------------------------------------------------------------
function New-Plan([object[]] $Changes) { [pscustomobject]@{ resource_changes = $Changes } }
function New-Change([string] $Address, [string[]] $Actions) { [pscustomobject]@{ address = $Address; change = [pscustomobject]@{ actions = $Actions } } }
$first = New-Plan (@(1..15 | ForEach-Object { New-Change "r$_" @('create') }))
$sum = Get-PlanActionSummary -Plan $first
Assert ($sum.Create -eq 15 -and $sum.Delete -eq 0 -and $sum.Update -eq 0) 'plan: first apply is 15 creates, nothing deleted'
$rebuild = New-Plan @(
    (New-Change 'hyperv_vm.vm["rke2-worker-01"]' @('delete', 'create')),
    (New-Change 'hyperv_vhd.os["rke2-worker-01"]' @('create', 'delete')),
    (New-Change 'hyperv_image_file.seed["rke2-worker-01"]' @('no-op')),
    (New-Change 'hyperv_vm.vm["rke2-worker-02"]' @('update')))
$sum = Get-PlanActionSummary -Plan $rebuild
Assert ($sum.Create -eq 2 -and $sum.Delete -eq 2 -and $sum.Update -eq 1) 'plan: replaces count as create + delete'
Assert ($sum.Replace.Count -eq 2 -and $sum.Replace -contains 'hyperv_vm.vm["rke2-worker-01"]') 'plan: replaced addresses are listed'
$destroy = New-Plan @((New-Change 'hyperv_vhd.data["rke2-master-01"]' @('delete')))
$sum = Get-PlanActionSummary -Plan $destroy
Assert ($sum.Delete -eq 1 -and $sum.Replace.Count -eq 0 -and $sum.DeleteAddresses -contains 'hyperv_vhd.data["rke2-master-01"]') 'plan: a plain delete is a delete, not a replace'
$sum = Get-PlanActionSummary -Plan ([pscustomobject]@{ format_version = '1.2' })
Assert ($sum.Create -eq 0 -and $sum.Delete -eq 0) 'plan: no resource_changes means no actions'

# --- disks and paths ------------------------------------------------------------------------------
Assert (Test-PathUnderRoot -Path 'E:\HyperV\iotee\vms\rke2-master-01-os.vhdx' -Root 'E:\HyperV\iotee\vms') 'path: disk under vm_root'
Assert (Test-PathUnderRoot -Path 'e:\hyperv\IOTEE\vms\x.vhdx' -Root 'E:\HyperV\iotee\vms\') 'path: case and trailing separator ignored'
Assert (-not (Test-PathUnderRoot -Path 'E:\HyperV\iotee\vms2\x.vhdx' -Root 'E:\HyperV\iotee\vms')) 'path: sibling folder with the same prefix is outside'
Assert (-not (Test-PathUnderRoot -Path 'D:\HyperV\iotee\vms\x.vhdx' -Root 'E:\HyperV\iotee\vms')) 'path: other drive is outside'
$ok = @(Get-DiskFindings -VmName 'n1' -DiskPaths @('E:\HyperV\iotee\vms\n1-os.vhdx', 'E:\HyperV\iotee\vms\n1-data.vhdx') -VmRoot 'E:\HyperV\iotee\vms')
Assert ($ok.Count -eq 0) 'disks: two disks under vm_root are fine'
$bad = @(Get-DiskFindings -VmName 'n1' -DiskPaths @('E:\HyperV\iotee\vms\n1-os_1A2B.avhdx', 'D:\old\n1-data.vhdx') -VmRoot 'E:\HyperV\iotee\vms')
Assert ($bad.Count -eq 2 -and ($bad -join ' ') -match 'avhdx' -and ($bad -join ' ') -match 'outside') 'disks: checkpoint disk and wrong volume are both reported'
$none = @(Get-DiskFindings -VmName 'n1' -DiskPaths @() -VmRoot 'E:\HyperV\iotee\vms')
Assert ($none.Count -eq 1 -and $none[0] -match 'no disks') 'disks: a VM without disks is reported'

# --- sizing ---------------------------------------------------------------------------------------------
Assert ((Get-WorstCaseGB -NodeCount 3 -TemplateSizeGB 30 -DataDiskGB 160) -eq 250) 'sizing: example nodes grow to 250 GB'
Assert ((Get-WorstCaseGB -NodeCount 3 -TemplateSizeGB 30 -DataDiskGB 60) -eq 150) 'sizing: the lab host (3 x 20 GB data) grows to 150 GB'

# --- Windows paths on any OS -------------------------------------------------------------------
$parts = Split-WindowsPath 'E:\HyperV\lab\templates\ubuntu-noble-base.vhdx'
Assert ($parts.Parent -eq 'E:\HyperV\lab\templates' -and $parts.Leaf -eq 'ubuntu-noble-base.vhdx') 'paths: template path split into folder and file'
Assert ((Join-WindowsPath 'E:\HyperV\lab\vms\' 'n1-os.vhdx') -eq 'E:\HyperV\lab\vms\n1-os.vhdx') 'paths: join keeps one separator'
Assert-Throws { Split-WindowsPath 'template.vhdx' } 'paths: a bare file name is rejected'

# --- capacity needed on re-runs ------------------------------------------------------------
Assert ((Get-RequiredVmFreeGB -WorstCaseGB 250 -ExistingDiskGB 0 -MinFreeGB 40) -eq 250) 'capacity: a fresh host needs the full worst case'
Assert ((Get-RequiredVmFreeGB -WorstCaseGB 150 -ExistingDiskGB 24.5 -MinFreeGB 40) -eq 126) 'capacity: existing disks reduce what must still be free'
Assert ((Get-RequiredVmFreeGB -WorstCaseGB 150 -ExistingDiskGB 140 -MinFreeGB 40) -eq 40) 'capacity: never below the hard minimum'

# --- hand-over file encoding ------------------------------------------------------------------------
$tmp = [System.IO.Path]::GetTempFileName()
try {
    ConvertTo-Utf8NoBomFile -Path $tmp -Text "[rke2_server]`r`nrke2-master-01 ansible_host=10.20.0.10"
    $bytes = [System.IO.File]::ReadAllBytes($tmp)
    Assert (-not ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) 'handover: no UTF-8 BOM'
    Assert (-not ($bytes -contains 13)) 'handover: LF line endings only'
    Assert ($bytes[$bytes.Length - 1] -eq 10) 'handover: ends with a newline'
}
finally { Remove-Item -LiteralPath $tmp -Force }

# --- optional: the settings expression against the real tofu console ---------------------------------------
if ($WithTofu) {
    $work = Join-Path ([System.IO.Path]::GetTempPath()) ("layer0-test-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $work | Out-Null
    try {
        Copy-Item -Path (Join-Path $layerRoot '*.tf'), (Join-Path $layerRoot '.terraform.lock.hcl') -Destination $work
        $pub = Join-Path $work 'test_key.pub'
        # A syntactically valid, throwaway public key (no private key exists).
        Set-Content -LiteralPath $pub -Value 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBtqVzKyyUC1f8v00oDpz8SK9Jiup8pUfToTvqxAtVxw layer0-test'
        $tfvars = (Get-Content -Raw -LiteralPath (Join-Path $layerRoot 'terraform.tfvars.example')) -replace '~/.ssh/id_ed25519.pub', ($pub -replace '\\', '/')
        Set-Content -LiteralPath (Join-Path $work 'terraform.tfvars') -Value $tfvars
        Push-Location $work
        try {
            # A full init (local backend, no remote state) in the throwaway
            # folder: `tofu console` needs an initialized backend.
            Invoke-Tofu -Arguments @('init', '-input=false', '-lockfile=readonly', '-no-color') | Out-Null
            $real = Get-Layer0Settings
            Assert ($real.vm_root -eq 'X:\HyperV\lab\vms') 'tofu: vm_root from terraform.tfvars.example'
            Assert (@($real.nodes.PSObject.Properties.Name).Count -eq 3) 'tofu: three nodes'
            Assert ($real.nodes.'rke2-master-01'.ipv4 -eq '10.20.0.10' -and $real.nodes.'rke2-master-01'.role -eq 'control-plane') 'tofu: node address from cidrhost()'
            # A failing variable validation must stop the scripts, although
            # `tofu console` exits 0 in that case.
            Set-Content -LiteralPath 'terraform.tfvars' -Value ($tfvars -replace [regex]::Escape(($pub -replace '\\', '/')), '/nonexistent/key.pub')
            Assert-Throws { Get-Layer0Settings } 'tofu: a failing variable validation is an error'
        }
        finally { Pop-Location }
    }
    finally { Remove-Item -Recurse -Force -LiteralPath $work }
}

Write-Host ''
Write-Host "$script:passed passed, $script:failed failed"
if ($script:failed -gt 0) { exit 1 }
