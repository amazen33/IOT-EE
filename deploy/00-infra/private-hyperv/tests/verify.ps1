#Requires -Version 5.1
[CmdletBinding()]
param([switch] $RunTofu)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$layer = Split-Path -Parent $PSScriptRoot
$preflight = Join-Path $layer 'scripts/StoragePreflight.ps1'
. $preflight

foreach ($path in @($preflight, (Join-Path $layer 'scripts/prep-hyperv-host.ps1'), $PSCommandPath)) {
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$null, [ref]$errors) | Out-Null
    if ($errors) { throw "PowerShell parse failed: $path`n$($errors -join "`n")" }
}

$gb = [int64]1GB
Assert-Layer0StorageCapacity -VmRoot 'E:\lab\vms' -TemplateRoot 'E:\lab\templates' -FreeBytesByDrive @{ E = 320 * $gb }
Assert-Layer0StorageCapacity -VmRoot 'E:\lab\vms' -TemplateRoot 'E:\lab\templates' -VmFreeGB 170 -TemplateFreeGB 40 -FreeBytesByDrive @{ E = 210 * $gb }
Assert-Layer0StorageCapacity -VmRoot 'E:\lab\vms' -TemplateRoot 'F:\lab\templates' -VmFreeGB 170 -TemplateFreeGB 40 -FreeBytesByDrive @{ E = 170 * $gb; F = 40 * $gb }
foreach ($case in @(
    @{ VmRoot = 'E:\lab\vms'; TemplateRoot = 'E:\lab\templates'; FreeBytesByDrive = @{ E = 319 * $gb } },
    @{ VmRoot = 'E:\lab\vms'; TemplateRoot = 'E:\lab\templates'; VmFreeGB = 170; TemplateFreeGB = 40; FreeBytesByDrive = @{ E = 209 * $gb } },
    @{ VmRoot = 'E:\lab\vms'; TemplateRoot = 'F:\lab\templates'; VmFreeGB = 170; TemplateFreeGB = 40; FreeBytesByDrive = @{ E = 169 * $gb; F = 40 * $gb } },
    @{ VmRoot = 'relative\vms'; TemplateRoot = 'F:\lab\templates'; FreeBytesByDrive = @{ F = 40 * $gb } }
)) {
    $rejected = $false
    try { Assert-Layer0StorageCapacity @case } catch { $rejected = $true }
    if (-not $rejected) { throw "Storage preflight accepted an invalid capacity or path case." }
}

$hcl = @(Get-ChildItem -LiteralPath $layer -Filter '*.tf')
if ($hcl.Count -lt 3) { throw 'Expected main.tf, variables.tf, and outputs.tf in this directory.' }
$source = ($hcl | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
if ($source -match '(?i)\.\.[\\/]01-k8s-engine|\bdeploy[\\/]01-k8s-engine') {
    throw 'Layer 0 source depends on a sibling Layer 1 directory.'
}
if (-not (Test-Path -LiteralPath (Join-Path $layer 'terraform.tfvars.example'))) {
    throw 'The standalone configuration example is missing.'
}
if ($RunTofu) {
    $tofu = Get-Command tofu -ErrorAction Stop
    Push-Location -LiteralPath $layer
    try {
        & $tofu.Source fmt -check
        if ($LASTEXITCODE -ne 0) { throw 'tofu fmt failed' }
        & $tofu.Source init -backend=false -input=false
        if ($LASTEXITCODE -ne 0) { throw 'tofu init failed' }
        & $tofu.Source validate
        if ($LASTEXITCODE -ne 0) { throw 'tofu validate failed' }
    }
    finally { Pop-Location }
}
Write-Host 'Layer 0 standalone checks passed.'
