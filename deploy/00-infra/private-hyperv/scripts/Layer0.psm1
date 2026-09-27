# Shared helpers for the Layer 0 scripts (deploy-layer0.ps1, check-layer0.ps1).
# Pure functions first (unit-tested by tests/Layer0.Tests.ps1 on any OS);
# the functions that call tofu or read a volume are at the end.

Set-StrictMode -Version Latest

# The one expression both scripts evaluate with `tofu console`, so every value
# comes from terraform.tfvars through OpenTofu's own parser and variable
# validations, never from a second copy of the settings.
$script:SettingsExpression = 'jsonencode({vm_root = var.vm_root, template_vhdx_path = var.template_vhdx_path, switch_name = var.switch_name, cidr = var.network.cidr, gateway = var.network.gateway, admin_user = var.admin_user, nodes = {for name, n in var.nodes : name => {role = n.role, ipv4 = cidrhost(var.network.cidr, n.ip_host), data_disk_gb = n.data_disk_gb}}})'

function Get-Layer0SettingsExpression { $script:SettingsExpression }

function ConvertFrom-TofuConsoleString {
    # `tofu console` prints a string result as a quoted HCL string literal. For
    # the output of jsonencode() its escapes (\" and \\) are JSON escapes, so
    # the literal is itself a JSON string: decode it once to get the JSON text,
    # then once more to get the object. Other stdout lines (a warning block,
    # blank lines) are ignored; the result is the last quoted line.
    param([Parameter(Mandatory = $true)][AllowEmptyString()][AllowEmptyCollection()][string[]] $Lines)
    $literal = @($Lines | ForEach-Object { $_.Trim() } |
            Where-Object { $_.Length -ge 2 -and $_.StartsWith('"') -and $_.EndsWith('"') }) | Select-Object -Last 1
    if (-not $literal) {
        throw "Unexpected tofu console output (no quoted string): $($Lines -join ' ')"
    }
    $json = $literal | ConvertFrom-Json
    return $json | ConvertFrom-Json
}

function Test-TofuStderrFailed {
    # `tofu console` exits 0 even when a variable validation fails; the
    # failure is visible only as an "Error:" block on stderr.
    param([AllowEmptyString()][AllowEmptyCollection()][string[]] $StderrLines)
    return [bool](@(@($StderrLines) | Where-Object { $_ -match '^\s*(\S+\s+)?Error:' }).Count)
}

function Get-PlanActionSummary {
    # Counts resource actions in `tofu show -json <plan>` output. A replace
    # (delete + create, either order) counts as a create and a delete, and its
    # address is also listed under Replace.
    param([Parameter(Mandatory = $true)] $Plan)
    $create = 0; $update = 0; $delete = 0; $replace = @(); $deleted = @()
    $changes = @()
    if ($Plan.PSObject.Properties.Name -contains 'resource_changes' -and $Plan.resource_changes) {
        $changes = @($Plan.resource_changes)
    }
    foreach ($rc in $changes) {
        $actions = @($rc.change.actions)
        if ($actions -contains 'create') { $create++ }
        if ($actions -contains 'update') { $update++ }
        if ($actions -contains 'delete') {
            $delete++
            $deleted += $rc.address
            if ($actions -contains 'create') { $replace += $rc.address }
        }
    }
    return [pscustomobject]@{ Create = $create; Update = $update; Delete = $delete; Replace = $replace; DeleteAddresses = $deleted }
}

function Get-WorstCaseGB {
    # Every OS disk grown to the template size plus every data disk grown to
    # its declared size.
    param([int] $NodeCount, [int] $TemplateSizeGB, [int] $DataDiskGB)
    return $NodeCount * $TemplateSizeGB + $DataDiskGB
}

function Get-RequiredVmFreeGB {
    # Free space the VM volume must still have so that every planned disk can
    # grow to its maximum: the worst case minus what the existing Layer 0
    # disks already occupy, and never less than the hard minimum. On a fresh
    # host that is the full worst case; on a re-run it shrinks by what the
    # running lab has already written.
    param([int] $WorstCaseGB, [double] $ExistingDiskGB, [int] $MinFreeGB)
    $remaining = [math]::Ceiling($WorstCaseGB - $ExistingDiskGB)
    return [int][math]::Max($MinFreeGB, $remaining)
}

function Join-WindowsPath {
    # tfvars paths are Windows paths; join them the same way on any OS (the
    # unit tests run on Linux too).
    param([string] $Parent, [string] $Child)
    return $Parent.TrimEnd('\', '/') + '\' + $Child
}

function Split-WindowsPath {
    # Parent folder and leaf name of a Windows path, on any OS.
    param([string] $Path)
    $i = $Path.LastIndexOfAny([char[]]@('\', '/'))
    if ($i -lt 1) { throw "Not an absolute file path: $Path" }
    return [pscustomobject]@{ Parent = $Path.Substring(0, $i); Leaf = $Path.Substring($i + 1) }
}

function Test-PathUnderRoot {
    # True when $Path is inside $Root (case-insensitive, either separator).
    param([string] $Path, [string] $Root)
    $p = (($Path -replace '/', '\').TrimEnd('\')).ToLowerInvariant()
    $r = (($Root -replace '/', '\').TrimEnd('\')).ToLowerInvariant()
    return $p.StartsWith($r + '\')
}

function Get-DiskFindings {
    # Problems with one VM's disks: none attached, a path outside vm_root, or
    # an .avhdx (the active disk of a checkpoint, so a checkpoint was taken).
    # Returns messages (wrap the call in @() to count them); none means OK.
    param([string] $VmName, [AllowEmptyCollection()][string[]] $DiskPaths, [string] $VmRoot)
    $findings = @()
    if (-not $DiskPaths -or $DiskPaths.Count -eq 0) { return "${VmName}: no disks attached" }
    foreach ($d in $DiskPaths) {
        if ($d -match '\.avhdx$') { $findings += "${VmName}: disk $d is a checkpoint disk (.avhdx)" }
        if (-not (Test-PathUnderRoot -Path $d -Root $VmRoot)) { $findings += "${VmName}: disk $d is outside vm_root $VmRoot" }
    }
    return $findings
}

function ConvertTo-Utf8NoBomFile {
    # Windows PowerShell 5.1's Set-Content -Encoding utf8 writes a BOM; the
    # handover files are read by Ansible and other Linux tools, so write
    # plain UTF-8 with LF line endings.
    param([string] $Path, [string] $Text)
    $normalized = ($Text -replace "`r`n", "`n")
    if (-not $normalized.EndsWith("`n")) { $normalized += "`n" }
    [System.IO.File]::WriteAllText($Path, $normalized, [System.Text.UTF8Encoding]::new($false))
}

# ----- Functions that call tofu or read a volume ---------------------------------

function Invoke-Tofu {
    # Runs tofu in the current folder with stdout and stderr kept apart.
    # Throws on an exit code not in -AllowedExitCodes, and with
    # -FailOnStderrError also on an "Error:" block when the exit code is 0.
    # -Interactive leaves stdin/stdout on the console.
    param([string[]] $Arguments, [int[]] $AllowedExitCodes = @(0), [string] $InputText,
        [switch] $FailOnStderrError, [switch] $Interactive)
    if ($Interactive) {
        & tofu @Arguments | Out-Host
        $code = $LASTEXITCODE
        if ($AllowedExitCodes -notcontains $code) { throw "tofu $($Arguments -join ' ') failed (exit code $code)." }
        return [pscustomobject]@{ ExitCode = $code; Output = @(); Stderr = @() }
    }
    $errFile = [System.IO.Path]::GetTempFileName()
    try {
        if ($PSBoundParameters.ContainsKey('InputText')) { $out = @($InputText | & tofu @Arguments 2> $errFile) }
        else { $out = @(& tofu @Arguments 2> $errFile) }
        $code = $LASTEXITCODE
        $err = @(Get-Content -LiteralPath $errFile -ErrorAction SilentlyContinue)
    }
    finally { Remove-Item -LiteralPath $errFile -Force -ErrorAction SilentlyContinue }
    if (($AllowedExitCodes -notcontains $code) -or ($FailOnStderrError -and (Test-TofuStderrFailed -StderrLines $err))) {
        $err | ForEach-Object { Write-Host $_ }
        throw "tofu $($Arguments -join ' ') failed (exit code $code)."
    }
    return [pscustomobject]@{ ExitCode = $code; Output = @($out | ForEach-Object { "$_" }); Stderr = $err }
}

function Get-Layer0Settings {
    # terraform.tfvars as OpenTofu sees it (every variable validation applies).
    # Needs `tofu init` first.
    $r = Invoke-Tofu -Arguments @('console', '-no-color') -InputText (Get-Layer0SettingsExpression) -FailOnStderrError
    return ConvertFrom-TofuConsoleString -Lines $r.Output
}

function Get-ExistingLayer0DiskGB {
    # Size on disk of the Layer 0 VHDX files that already exist for the
    # configured nodes (<name>-os.vhdx and <name>-data.vhdx under vm_root).
    param([string] $VmRoot, [string[]] $NodeNames)
    $bytes = 0L
    foreach ($n in $NodeNames) {
        foreach ($suffix in @('-os.vhdx', '-data.vhdx')) {
            $p = Join-WindowsPath $VmRoot ($n + $suffix)
            if (Test-Path -LiteralPath $p) { $bytes += (Get-Item -LiteralPath $p).Length }
        }
    }
    return [math]::Round($bytes / 1GB, 1)
}

function Get-FreeSpaceGB {
    param([string] $Path)
    $root = [System.IO.Path]::GetPathRoot([System.IO.Path]::GetFullPath($Path))
    if (-not $root) { throw "Cannot determine the volume of '$Path'." }
    $drive = [System.IO.DriveInfo]::new($root)
    if (-not $drive.IsReady) { throw "Volume $root for '$Path' is not ready." }
    return [pscustomobject]@{ Root = $root; FreeGB = [math]::Round($drive.AvailableFreeSpace / 1GB, 1) }
}

Export-ModuleMember -Function *
