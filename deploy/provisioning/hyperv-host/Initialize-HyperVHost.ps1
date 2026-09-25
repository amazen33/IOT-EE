#Requires -RunAsAdministrator
<#
.SYNOPSIS
  Prepares one Windows machine as an IOT-EE private-cloud Hyper-V host.

.DESCRIPTION
  Idempotent. Run once per host (OptiPlex, laptop) BEFORE the first
  `tofu apply`. Every change asks for confirmation (-Confirm:$false to skip)
  and -WhatIf shows what would change without changing anything.

    1. Checks the Hyper-V feature (enabling it needs a reboot).
    2. Creates the external virtual switch the VMs attach to. Creating an
       external switch briefly drops the host's network connection.
    3. Creates the VM and image directories Terraform writes into.
    4. Enables a WinRM HTTPS listener (port 5986) with a self-signed
       certificate for the Terraform Hyper-V provider, and opens the firewall
       for it from -AllowedRemoteAddress only.

.PARAMETER NetAdapterName
  Physical adapter to bind the external switch to (see Get-NetAdapter).

.EXAMPLE
  .\Initialize-HyperVHost.ps1 -NetAdapterName 'Ethernet' -VmRoot 'D:\HyperV\iotee' -ImageRoot 'D:\HyperV\images'
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory = $true)]
    [string] $NetAdapterName,

    [string] $SwitchName = 'iotee-external',

    [Parameter(Mandatory = $true)]
    [string] $VmRoot,

    [Parameter(Mandatory = $true)]
    [string] $ImageRoot,

    # Who may reach WinRM: the operator workstation / CI runner, not the world.
    [string[]] $AllowedRemoteAddress = @('LocalSubnet'),

    [string] $CertificateDnsName = $env:COMPUTERNAME
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 1. Hyper-V feature ---------------------------------------------------------
$feature = Get-WindowsOptionalFeature -Online -FeatureName 'Microsoft-Hyper-V-All'
if ($feature.State -ne 'Enabled') {
    if ($PSCmdlet.ShouldProcess('Microsoft-Hyper-V-All', 'Enable Windows feature (reboot required)')) {
        Enable-WindowsOptionalFeature -Online -FeatureName 'Microsoft-Hyper-V-All' -All -NoRestart | Out-Null
        Write-Warning 'Hyper-V was enabled. Reboot, then run this script again.'
        return
    }
}
else {
    Write-Host 'Hyper-V: enabled'
}

# 2. External switch ---------------------------------------------------------
$adapter = Get-NetAdapter -Name $NetAdapterName
if ($adapter.PhysicalMediaType -match '802\.11') {
    Write-Warning (("'{0}' is a Wi-Fi adapter. Hyper-V bridges Wi-Fi by rewriting MAC addresses, " +
            'so a floating VIP (kube-vip ARP mode) is unreliable behind it. Keep RKE2 server nodes ' +
            'on a wired host; see deploy/README.md "Topology limits".') -f $NetAdapterName)
}
if (-not (Get-VMSwitch -Name $SwitchName -ErrorAction SilentlyContinue)) {
    if ($PSCmdlet.ShouldProcess($SwitchName, "Create external vSwitch on '$NetAdapterName' (host network drops briefly)")) {
        New-VMSwitch -Name $SwitchName -NetAdapterName $NetAdapterName -AllowManagementOS $true | Out-Null
        Write-Host "vSwitch: created $SwitchName"
    }
}
else {
    Write-Host "vSwitch: $SwitchName exists"
}

# 3. Directories -------------------------------------------------------------
foreach ($dir in @($VmRoot, $ImageRoot)) {
    if (-not (Test-Path -LiteralPath $dir)) {
        if ($PSCmdlet.ShouldProcess($dir, 'Create directory')) {
            New-Item -ItemType Directory -Path $dir | Out-Null
        }
    }
}

# 4. WinRM over HTTPS for the Terraform provider -----------------------------
$httpsListener = Get-ChildItem -Path 'WSMan:\localhost\Listener' |
    Where-Object { $_.Keys -contains 'Transport=HTTPS' }
if (-not $httpsListener) {
    if ($PSCmdlet.ShouldProcess('WinRM', 'Enable HTTPS listener on 5986 with a self-signed certificate')) {
        Enable-PSRemoting -SkipNetworkProfileCheck -Force | Out-Null
        $cert = New-SelfSignedCertificate -DnsName $CertificateDnsName `
            -CertStoreLocation 'Cert:\LocalMachine\My' -NotAfter (Get-Date).AddYears(2)
        New-Item -Path 'WSMan:\localhost\Listener' -Transport HTTPS -Address * `
            -CertificateThumbPrint $cert.Thumbprint -Force | Out-Null
        Write-Host "WinRM HTTPS: listener created (certificate $($cert.Thumbprint))"
    }
}
else {
    Write-Host 'WinRM HTTPS: listener exists'
}

$ruleName = 'IOT-EE WinRM HTTPS (Terraform)'
if (-not (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue)) {
    if ($PSCmdlet.ShouldProcess($ruleName, "Allow TCP 5986 from $($AllowedRemoteAddress -join ', ')")) {
        New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort 5986 `
            -RemoteAddress $AllowedRemoteAddress -Action Allow | Out-Null
    }
}

Write-Host ''
Write-Host 'Next: build the golden image with .\New-BaseImage.ps1, then set hosts.<name> in terraform.tfvars:'
Write-Host "  switch_name = `"$SwitchName`""
Write-Host "  vm_root     = `"$VmRoot`""
