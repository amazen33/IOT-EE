#Requires -RunAsAdministrator
#Requires -Version 5.1
<#
.SYNOPSIS
  Read-only check that the VMs this Layer 0 directory created are healthy.

.DESCRIPTION
  Changes nothing on the host, the VMs or the OpenTofu state. Reads the
  settings from terraform.tfvars through `tofu console` (so the same values
  and validations as `tofu plan`), then checks, per node:

    - the VM exists and is Running;
    - checkpoints are off (CheckpointType Disabled, Automatic Checkpoints
      off, no snapshots, no .avhdx disk);
    - every disk is under vm_root;
    - the Heartbeat integration service reports OK (the guest OS is up);
    - TCP 22 answers on the node's address (the guest finished enough of
      cloud-init to start SSH);
    - with -CheckCloudInit, `cloud-init status` over SSH is "done". This
      uses your SSH key and an already trusted host key (BatchMode); it
      never accepts a host key for you.

  And for the host: free space on the vm_root volume against -MinFreeGB
  (failure) and against the fully grown disks (warning). Hyper-V pauses and
  then turns off a VM whose disk file cannot grow.

  Writes a JSON report to out\check-<UTC timestamp>.json (git-ignored) and
  exits 1 when any check fails.

.PARAMETER WaitMinutes
  Keep retrying the heartbeat and SSH-port checks for up to this many
  minutes (for a run right after `tofu apply`, while cloud-init works).

.EXAMPLE
  .\scripts\check-layer0.ps1
  .\scripts\check-layer0.ps1 -WaitMinutes 5 -CheckCloudInit
#>
[CmdletBinding()]
param(
    [ValidateRange(0, 60)]
    [int] $WaitMinutes = 0,
    [ValidateRange(1, 100000)]
    [int] $MinFreeGB = 40,
    [ValidateRange(20, 512)]
    [int] $TemplateSizeGB = 30,
    [switch] $CheckCloudInit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Layer0.psm1') -Force
$layerRoot = Split-Path -Parent $PSScriptRoot

Push-Location $layerRoot
try {
    if (-not (Test-Path -LiteralPath 'terraform.tfvars')) { throw 'terraform.tfvars not found in the Layer 0 folder.' }
    if (-not (Test-Path -LiteralPath '.terraform')) {
        Invoke-Tofu -Arguments @('init', '-input=false', '-lockfile=readonly', '-no-color') | Out-Null
    }
    $s = Get-Layer0Settings
    $nodeNames = @($s.nodes.PSObject.Properties.Name | Sort-Object)
    $failures = [System.Collections.Generic.List[string]]::new()
    $warnings = [System.Collections.Generic.List[string]]::new()
    $rows = [System.Collections.Generic.List[object]]::new()

    # --- host volume ---------------------------------------------------------------
    $vol = Get-FreeSpaceGB $s.vm_root
    $dataGB = 0
    foreach ($n in $nodeNames) { $dataGB += [int]$s.nodes.$n.data_disk_gb }
    $worst = Get-WorstCaseGB -NodeCount $nodeNames.Count -TemplateSizeGB $TemplateSizeGB -DataDiskGB $dataGB
    if ($vol.FreeGB -lt $MinFreeGB) {
        $failures.Add("Only $($vol.FreeGB) GB free on $($vol.Root) (minimum $MinFreeGB GB). Hyper-V stops VMs whose disks cannot grow.")
    }
    elseif ($vol.FreeGB -lt $worst) {
        $warnings.Add("$($vol.Root) has $($vol.FreeGB) GB free; the planned disks can grow to $worst GB.")
    }

    # --- per node, static checks ---------------------------------------------------------
    foreach ($name in $nodeNames) {
        $ip = $s.nodes.$name.ipv4
        $row = [ordered]@{ Name = $name; IPv4 = $ip; State = '-'; Checkpoints = '-'; Disks = '-'; Heartbeat = '-'; Ssh = '-'; CloudInit = '-' }
        $vm = Get-VM -Name $name -ErrorAction SilentlyContinue
        if (-not $vm) {
            $failures.Add("${name}: VM not found")
            $rows.Add([pscustomobject]$row); continue
        }
        $row.State = "$($vm.State)"
        if ($vm.State -ne 'Running') { $failures.Add("${name}: state is $($vm.State), not Running") }

        $snapshots = @(Get-VMSnapshot -VMName $name -ErrorAction SilentlyContinue)
        $cpOk = ("$($vm.CheckpointType)" -eq 'Disabled') -and (-not $vm.AutomaticCheckpointsEnabled) -and ($snapshots.Count -eq 0)
        $row.Checkpoints = if ($cpOk) { 'off' } else { "type=$($vm.CheckpointType) auto=$($vm.AutomaticCheckpointsEnabled) snapshots=$($snapshots.Count)" }
        if (-not $cpOk) { $failures.Add("${name}: checkpoints are not fully disabled ($($row.Checkpoints))") }

        $disks = @(Get-VMHardDiskDrive -VMName $name | ForEach-Object { $_.Path })
        $diskFindings = @(Get-DiskFindings -VmName $name -DiskPaths $disks -VmRoot $s.vm_root)
        $row.Disks = if ($diskFindings.Count -eq 0) { "$($disks.Count) under vm_root" } else { 'problem' }
        foreach ($f in $diskFindings) { $failures.Add($f) }
        $rows.Add([pscustomobject]$row)
    }

    # --- per node, guest checks (retried until -WaitMinutes) -----------------------------------
    $deadline = (Get-Date).AddMinutes($WaitMinutes)
    foreach ($row in $rows) {
        if ($row.State -ne 'Running') { continue }
        do {
            $hb = Get-VMIntegrationService -VMName $row.Name -Name 'Heartbeat' -ErrorAction SilentlyContinue
            $row.Heartbeat = if ($hb -and $hb.PrimaryStatusDescription) { "$($hb.PrimaryStatusDescription)" } else { 'no contact' }
            $portOpen = Test-NetConnection -ComputerName $row.IPv4 -Port 22 -InformationLevel Quiet -WarningAction SilentlyContinue
            $row.Ssh = if ($portOpen) { 'port 22 open' } else { 'port 22 closed' }
            $done = ($row.Heartbeat -eq 'OK') -and $portOpen
            if (-not $done -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 15 }
        } while (-not $done -and (Get-Date) -lt $deadline)
        if ($row.Heartbeat -ne 'OK') { $failures.Add("$($row.Name): heartbeat is '$($row.Heartbeat)' (guest OS not up or integration services missing)") }
        if (-not $portOpen) {
            $failures.Add("$($row.Name): TCP 22 on $($row.IPv4) is not answering (refused while the heartbeat is OK means SSH did not start: see the VM console and the Layer 0 README)")
        }

        if ($CheckCloudInit -and $portOpen) {
            $status = & ssh -o BatchMode=yes -o ConnectTimeout=10 "$($s.admin_user)@$($row.IPv4)" 'cloud-init status' 2>&1
            $text = ($status | Out-String).Trim()
            if ($LASTEXITCODE -eq 0 -and $text -match 'status:\s*done') { $row.CloudInit = 'done' }
            elseif ($text -match 'Host key verification failed|REMOTE HOST IDENTIFICATION HAS CHANGED') {
                $row.CloudInit = 'host key not trusted'
                $failures.Add("$($row.Name): SSH host key not trusted yet. Check its fingerprint, then add it (ssh-keyscan) and re-run.")
            }
            else {
                $row.CloudInit = 'not done'
                $failures.Add("$($row.Name): cloud-init status is not 'done': $text")
            }
        }
    }

    # --- report ------------------------------------------------------------------------------
    @($rows) | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
    foreach ($w in $warnings) { Write-Warning $w }
    $outDir = Join-Path $layerRoot 'out'
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ')
    $report = [ordered]@{
        checked_at_utc = (Get-Date).ToUniversalTime().ToString('o')
        host           = $env:COMPUTERNAME
        vm_root        = $s.vm_root
        volume         = @{ root = $vol.Root; free_gb = $vol.FreeGB; min_free_gb = $MinFreeGB; worst_case_gb = $worst }
        nodes          = $rows
        warnings       = @($warnings)
        failures       = @($failures)
        result         = if ($failures.Count -eq 0) { 'passed' } else { 'failed' }
    }
    $reportPath = Join-Path $outDir "check-$stamp.json"
    ConvertTo-Utf8NoBomFile -Path $reportPath -Text ($report | ConvertTo-Json -Depth 5)
    Write-Host "Report: $reportPath"

    if ($failures.Count -gt 0) {
        foreach ($f in $failures) { Write-Host "FAIL  $f" -ForegroundColor Red }
        exit 1
    }
    Write-Host 'Layer 0 check passed.' -ForegroundColor Green
}
finally { Pop-Location }
