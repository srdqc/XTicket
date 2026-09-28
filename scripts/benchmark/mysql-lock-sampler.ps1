param(
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [Parameter(Mandatory = $true)][string]$SummaryPath,
    [Parameter(Mandatory = $true)][string]$StopPath,
    [int]$IntervalSeconds = 1,
    [string]$RepoRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
} else { (Resolve-Path $RepoRoot).Path }
$docker = (Get-Command docker -ErrorAction Stop).Source
$utf8NoBom = New-Object Text.UTF8Encoding($false)
$mysql = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw --skip-column-names'

function Invoke-ScalarRows([string]$Sql) {
    $output = $Sql | & $script:docker compose exec -T mysql sh -lc $script:mysql 2>&1
    if ($LASTEXITCODE -ne 0) { throw (($output | Out-String).Trim()) }
    return @($output | Where-Object { $_ -and -not $_.StartsWith('mysql: [Warning]') })
}

function Get-Snapshot {
    $rows = @(Invoke-ScalarRows @'
SHOW GLOBAL STATUS WHERE Variable_name IN
('Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_row_lock_waits');
SELECT 'waiting_trx',COUNT(*) FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT';
'@)
    $values = @{}
    foreach ($row in $rows) {
        $parts = @($row -split "`t")
        if ($parts.Count -eq 2) { $values[$parts[0].ToLowerInvariant()] = [long]$parts[1] }
    }
    foreach ($required in @('innodb_row_lock_current_waits','innodb_row_lock_time','innodb_row_lock_waits','waiting_trx')) {
        if (-not $values.ContainsKey($required)) { throw "Missing MySQL lock metric: $required" }
    }
    [pscustomobject]@{
        timestampUtc=[DateTimeOffset]::UtcNow.ToString('o')
        currentWaits=$values.innodb_row_lock_current_waits
        rowLockTimeMs=$values.innodb_row_lock_time
        rowLockWaits=$values.innodb_row_lock_waits
        waitingTransactions=$values.waiting_trx
    }
}

function Get-DeadlockMarker {
    $text = (Invoke-ScalarRows 'SHOW ENGINE INNODB STATUS;') -join "`n"
    if ($text -match 'LATEST DETECTED DEADLOCK\s*-+\s*(\d{4}-\d{2}-\d{2} [0-9:]+)') {
        return $Matches[1]
    }
    return ''
}

Push-Location $repoRoot
try {
    foreach ($path in @($OutputPath,$SummaryPath)) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    }
    $deadlockBefore = Get-DeadlockMarker
    $samples = @()
    do {
        $sample = Get-Snapshot
        $samples += $sample
        [IO.File]::AppendAllText($OutputPath,(($sample | ConvertTo-Json -Compress)+[Environment]::NewLine),$utf8NoBom)
        for ($slice=0;$slice -lt ($IntervalSeconds*5);$slice++) {
            if (Test-Path -LiteralPath $StopPath) { break }
            Start-Sleep -Milliseconds 200
        }
    } while (-not (Test-Path -LiteralPath $StopPath))
    $first=$samples[0];$last=$samples[$samples.Count-1]
    $summary=[pscustomobject]@{
        status='MYSQL_LOCK_SAMPLER_OK';sampleCount=$samples.Count
        rowLockWaitsDelta=[long]($last.rowLockWaits-$first.rowLockWaits)
        rowLockTimeDeltaMs=[long]($last.rowLockTimeMs-$first.rowLockTimeMs)
        currentWaitsPeak=[long](($samples.currentWaits|Measure-Object -Maximum).Maximum)
        waitingTransactionsPeak=[long](($samples.waitingTransactions|Measure-Object -Maximum).Maximum)
        deadlockMarkerBefore=$deadlockBefore
        deadlockMarkerAfter=(Get-DeadlockMarker)
        deadlockChanged=$false
    }
    $summary.deadlockChanged = $summary.deadlockMarkerBefore -ne $summary.deadlockMarkerAfter
    [IO.File]::WriteAllText($SummaryPath,($summary|ConvertTo-Json -Compress),$utf8NoBom)
    $summary|ConvertTo-Json -Compress
} finally { Pop-Location }
