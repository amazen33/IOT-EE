#Requires -Version 5.1
<#
.SYNOPSIS
  The one test command for this Layer 0 directory. Offline by default.

    .\tests\verify.ps1            # offline: parse, capacity rules, independence, unit tests
    .\tests\verify.ps1 -RunTofu   # also tofu fmt/init/validate and the real `tofu console`
                                  # (downloads the pinned providers, or uses a mirror)

  Works in Windows PowerShell 5.1 and PowerShell 7 on any OS. Touches no
  Hyper-V host, creates no VM, and writes only to temporary folders.
#>
[CmdletBinding()]
param([switch] $RunTofu)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$layer = Split-Path -Parent $PSScriptRoot
$preflight = Join-Path $layer 'scripts/StoragePreflight.ps1'
. $preflight
$excluded = '[\\/](\.terraform|out|tools)[\\/]'

# 1. Every script in this directory parses.
$scripts = @(Get-ChildItem -LiteralPath $layer -Recurse -File |
        Where-Object { $_.Extension -in @('.ps1', '.psm1') -and $_.FullName -notmatch $excluded })
foreach ($file in $scripts) {
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$null, [ref]$errors) | Out-Null
    if ($errors) { throw "PowerShell parse failed: $($file.FullName)`n$($errors -join "`n")" }
}
Write-Host "ok   $($scripts.Count) PowerShell files parse"

# 2. Capacity rules of the host-prep check (injected free space, no real volume).
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
    if (-not $rejected) { throw 'Storage preflight accepted an invalid capacity or path case.' }
}
Write-Host 'ok   capacity rules (3 accepted, 4 rejected)'

# 3. Independence: nothing here reads from or writes into another directory of
#    the repository, and the files a copy of this directory needs are present.
$sources = @(Get-ChildItem -LiteralPath $layer -Recurse -File |
        Where-Object { $_.Extension -in @('.tf', '.ps1', '.psm1') -and $_.FullName -notmatch $excluded -and $_.FullName -ne $PSCommandPath })
if (@($sources | Where-Object { $_.Extension -eq '.tf' }).Count -lt 3) { throw 'Expected main.tf, variables.tf, and outputs.tf in this directory.' }
foreach ($file in $sources) {
    $text = Get-Content -LiteralPath $file.FullName -Raw
    if ($text -match '(?i)01-k8s-engine|02-cluster-addons|rke2-ansible|deploy[\\/]profiles|deploy[\\/]catalog|\.\.[\\/]\.\.[\\/]') {
        throw "$($file.Name) refers to something outside this directory: $($Matches[0])"
    }
}
foreach ($required in @('terraform.tfvars.example', '.terraform.lock.hcl', '.gitignore')) {
    if (-not (Test-Path -LiteralPath (Join-Path $layer $required))) { throw "The standalone file $required is missing." }
}
$ignore = @(Get-Content -LiteralPath (Join-Path $layer '.gitignore'))
foreach ($pattern in @('terraform.tfvars', '*.tfstate*', '*.tfplan', 'out/', '*.vhdx', 'hosts.ini')) {
    if ($ignore -notcontains $pattern) { throw ".gitignore must ignore $pattern (real settings, state, hand-over files and disks stay out of Git)." }
}
Write-Host "ok   self-contained ($($sources.Count) sources checked; .gitignore covers settings, state, hand-over files, disks)"

# 4. Unit tests of the deploy/check helpers (with -RunTofu also the real tofu console).
$unit = Join-Path $PSScriptRoot 'Layer0.Tests.ps1'
$global:LASTEXITCODE = 0
if ($RunTofu) { & $unit -WithTofu } else { & $unit }
if ($LASTEXITCODE -ne 0) { throw 'Layer0.Tests.ps1 failed.' }

# 5. With -RunTofu: formatting, locked init and validation in a throwaway copy
#    (this directory's own .terraform and state are never touched).
if ($RunTofu) {
    $tofu = Get-Command tofu -ErrorAction Stop
    $work = Join-Path ([System.IO.Path]::GetTempPath()) ('layer0-verify-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $work | Out-Null
    try {
        Copy-Item -Path (Join-Path $layer '*.tf'), (Join-Path $layer '.terraform.lock.hcl') -Destination $work
        & $tofu.Source "-chdir=$work" fmt -check -diff
        if ($LASTEXITCODE -ne 0) { throw 'tofu fmt failed' }
        & $tofu.Source "-chdir=$work" init -backend=false -input=false -lockfile=readonly
        if ($LASTEXITCODE -ne 0) { throw 'tofu init failed (the lock file must cover this platform)' }
        & $tofu.Source "-chdir=$work" validate
        if ($LASTEXITCODE -ne 0) { throw 'tofu validate failed' }
        Write-Host 'ok   tofu fmt, locked init, validate'
    }
    finally { Remove-Item -Recurse -Force -LiteralPath $work -ErrorAction SilentlyContinue }
}
Write-Host 'Layer 0 standalone checks passed.'
