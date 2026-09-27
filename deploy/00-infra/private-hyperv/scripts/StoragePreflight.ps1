Set-StrictMode -Version Latest

function Assert-Layer0StorageCapacity {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string] $VmRoot,
        [Parameter(Mandatory = $true)][string] $TemplateRoot,
        [ValidateRange(1, 100000)][int] $VmFreeGB = 280,
        [ValidateRange(1, 100000)][int] $TemplateFreeGB = 40,
        [hashtable] $FreeBytesByDrive
    )

    $required = @{}
    foreach ($item in @(@{ Path = $VmRoot; GB = $VmFreeGB }, @{ Path = $TemplateRoot; GB = $TemplateFreeGB })) {
        if ($item.Path -notmatch '^(?<drive>[A-Za-z]):\\') {
            throw "Storage path must be an absolute local drive path: $($item.Path)"
        }
        $drive = $Matches.drive.ToUpperInvariant()
        if (-not $required.ContainsKey($drive)) { $required[$drive] = 0L }
        $required[$drive] += [int64]$item.GB * 1GB
    }

    foreach ($drive in $required.Keys) {
        if ($PSBoundParameters.ContainsKey('FreeBytesByDrive')) {
            if (-not $FreeBytesByDrive.ContainsKey($drive)) { throw "No free-space reading for drive ${drive}:" }
            $free = [int64]$FreeBytesByDrive[$drive]
        }
        else {
            $volume = Get-PSDrive -Name $drive -PSProvider FileSystem -ErrorAction Stop
            $free = [int64]$volume.Free
        }
        if ($free -lt $required[$drive]) {
            $needGB = [math]::Ceiling($required[$drive] / 1GB)
            $freeGB = [math]::Floor($free / 1GB)
            throw "Drive ${drive}: has ${freeGB} GB free; Layer 0 requires at least ${needGB} GB free. Choose another volume or adjust the explicit capacity threshold after sizing the lab."
        }
    }
}
