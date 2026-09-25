<#
.SYNOPSIS
  Builds the immutable golden VHDX every node's differencing disk sits on.

.DESCRIPTION
  Downloads the official Ubuntu Server cloud image, verifies it against
  Canonical's SHA256SUMS, converts it to a dynamic VHDX with qemu-img and
  grows it to -SizeGB.

  The output file name is dated and the script never overwrites an existing
  file: VMs hold differencing disks whose parent is this file, so changing it
  in place would corrupt every VM built on it. To roll a new image, build a
  new file, point base_image_path at it, and rebuild nodes one at a time.

  Requires qemu-img for Windows (https://qemu.weilnetz.de/ or the QEMU
  installer); pass its path with -QemuImgPath if it is not on PATH.

.EXAMPLE
  .\New-BaseImage.ps1 -ImageRoot 'D:\HyperV\images'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ImageRoot,

    # Ubuntu release codename. The standard (not "minimal") cloud image is
    # used on purpose: it ships the generic virtual kernel with Hyper-V drivers.
    [string] $Release = 'noble',

    [ValidateRange(20, 512)]
    [int] $SizeGB = 40,

    [string] $QemuImgPath = 'qemu-img.exe',

    [string] $OutputName = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Get-Command $QemuImgPath -ErrorAction SilentlyContinue)) {
    throw "qemu-img not found at '$QemuImgPath'. Install QEMU for Windows or pass -QemuImgPath."
}
if (-not $OutputName) {
    $OutputName = "ubuntu-$Release-base-$(Get-Date -Format 'yyyyMMdd').vhdx"
}
$output = Join-Path $ImageRoot $OutputName
if (Test-Path -LiteralPath $output) {
    throw "$output already exists. Golden images are immutable; pass a different -OutputName."
}

$baseUrl = "https://cloud-images.ubuntu.com/$Release/current"
$imageName = "$Release-server-cloudimg-amd64.img"
$work = Join-Path $ImageRoot '.download'
New-Item -ItemType Directory -Path $work -Force | Out-Null
$imagePath = Join-Path $work $imageName
$sumsPath = Join-Path $work 'SHA256SUMS'

Write-Host "Downloading $baseUrl/$imageName"
Invoke-WebRequest -Uri "$baseUrl/SHA256SUMS" -OutFile $sumsPath -UseBasicParsing
Invoke-WebRequest -Uri "$baseUrl/$imageName" -OutFile $imagePath -UseBasicParsing

$expected = (Get-Content -LiteralPath $sumsPath |
        Where-Object { $_ -match "\*?$([regex]::Escape($imageName))$" } |
        Select-Object -First 1) -split '\s+' | Select-Object -First 1
if (-not $expected) {
    throw "No checksum for $imageName in SHA256SUMS."
}
$actual = (Get-FileHash -LiteralPath $imagePath -Algorithm SHA256).Hash
if ($actual -ne $expected.ToUpperInvariant()) {
    throw "Checksum mismatch for ${imageName}: expected $expected, got $actual."
}
Write-Host 'Checksum OK'

& $QemuImgPath convert -p -f qcow2 -O vhdx -o subformat=dynamic $imagePath $output
if ($LASTEXITCODE -ne 0) { throw "qemu-img convert failed ($LASTEXITCODE)." }
& $QemuImgPath resize -f vhdx $output "${SizeGB}G"
if ($LASTEXITCODE -ne 0) { throw "qemu-img resize failed ($LASTEXITCODE)." }

# Guard against accidental in-place edits of the parent disk.
Set-ItemProperty -LiteralPath $output -Name IsReadOnly -Value $true

Write-Host ''
Write-Host "Golden image: $output"
Write-Host "Set hosts.<name>.base_image_path = `"$($output -replace '\\', '\\')`" in terraform.tfvars"
