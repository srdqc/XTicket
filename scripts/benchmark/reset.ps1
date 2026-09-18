param(
    [int]$ObservationCheckpointSeconds = 30,
    [int]$DrainTimeoutSeconds = 120,
    [int]$PollIntervalMilliseconds = 500,
    [string]$DrainStartedAtUtc = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$mysqlCommand = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw'

if ($ObservationCheckpointSeconds -lt 1) { throw 'ObservationCheckpointSeconds must be positive' }
if ($DrainTimeoutSeconds -le $ObservationCheckpointSeconds) {
    throw 'DrainTimeoutSeconds must be greater than ObservationCheckpointSeconds'
}
if ($PollIntervalMilliseconds -lt 100) { throw 'PollIntervalMilliseconds must be at least 100' }

function Invoke-Db([string]$Sql) {
    $output = $Sql | docker compose exec -T mysql sh -lc $script:mysqlCommand 2>&1
    if ($LASTEXITCODE -ne 0) { throw (($output | Out-String).Trim()) }
    return @($output | Where-Object { $_ -and -not $_.StartsWith('mysql: [Warning]') })
}

function Get-Scalar([string]$Sql) {
    $rows = @(Invoke-Db $Sql)
    if ($rows.Count -lt 2) { return 0 }
    return [long](($rows[1] -split "`t")[0])
}

function Get-OutboxState {
    $rows = @(Invoke-Db @'
SELECT
  COALESCE(SUM(e.status = 'PENDING'), 0) AS pending_count,
  COALESCE(SUM(e.status = 'PROCESSING'), 0) AS processing_count,
  COALESCE(SUM(e.status = 'FAILED'), 0) AS failed_count,
  COALESCE(SUM(e.status = 'PUBLISHED' AND c.id IS NULL), 0) AS missing_consumed_count
FROM outbox_event e
JOIN ticket_order o
  ON CONVERT(o.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci
   = CONVERT(e.aggregate_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
JOIN sys_user u ON u.id = o.user_id
LEFT JOIN consumed_event c
  ON CONVERT(c.event_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
   = CONVERT(e.event_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
 AND c.consumer_group = 'maoyan_order_consumer_group'
WHERE LEFT(u.account, 11) = 'BENCH_USER_';
'@)
    if ($rows.Count -lt 2) { throw 'Outbox state query returned no data row' }
    $values = @($rows[1] -split "`t")
    if ($values.Count -ne 4) { throw "Unexpected Outbox state row: $($rows[1])" }
    $pending = [long]$values[0]
    $processing = [long]$values[1]
    $failed = [long]$values[2]
    [pscustomobject]@{
        pending = $pending
        processing = $processing
        failed = $failed
        missingConsumed = [long]$values[3]
        open = $pending + $processing + $failed
    }
}

function Copy-State($State) {
    return [pscustomobject]@{
        pending = [long]$State.pending
        processing = [long]$State.processing
        failed = [long]$State.failed
        missingConsumed = [long]$State.missingConsumed
        open = [long]$State.open
    }
}

Push-Location $repoRoot
try {
    $drainStartedAt = if ([string]::IsNullOrWhiteSpace($DrainStartedAtUtc)) {
        [DateTimeOffset]::UtcNow
    } else {
        [DateTimeOffset]::Parse($DrainStartedAtUtc).ToUniversalTime()
    }
    $initial = Get-OutboxState
    $peakPending = $initial.pending
    $peakProcessing = $initial.processing
    $peakFailed = $initial.failed
    $peakOpen = $initial.open
    $at10Seconds = $null
    $at30Seconds = $null
    $timeToZeroSeconds = $null
    $finalState = $initial

    do {
        $elapsed = ([DateTimeOffset]::UtcNow - $drainStartedAt).TotalSeconds
        $state = Get-OutboxState
        $finalState = $state
        $peakPending = [Math]::Max($peakPending, $state.pending)
        $peakProcessing = [Math]::Max($peakProcessing, $state.processing)
        $peakFailed = [Math]::Max($peakFailed, $state.failed)
        $peakOpen = [Math]::Max($peakOpen, $state.open)
        if ($null -eq $at10Seconds -and $elapsed -ge 10) { $at10Seconds = Copy-State $state }
        if ($null -eq $at30Seconds -and $elapsed -ge $ObservationCheckpointSeconds) {
            $at30Seconds = Copy-State $state
        }
        if ($null -eq $timeToZeroSeconds -and $state.pending -eq 0 -and $state.processing -eq 0) {
            $timeToZeroSeconds = [Math]::Round($elapsed, 3)
        }
        $drained = $state.open -eq 0 -and $state.failed -eq 0 -and $state.missingConsumed -eq 0
        if ($drained) { break }
        Start-Sleep -Milliseconds $PollIntervalMilliseconds
    } while ($elapsed -lt $DrainTimeoutSeconds)

    if ($null -eq $at10Seconds) { $at10Seconds = Copy-State $finalState }
    if ($null -eq $at30Seconds) { $at30Seconds = Copy-State $finalState }
    if (-not $drained) {
        throw "Outbox cleanup safety timeout after ${DrainTimeoutSeconds}s: pending=$($finalState.pending), processing=$($finalState.processing), failed=$($finalState.failed), missingConsumed=$($finalState.missingConsumed); cleanup not started"
    }

    $expectedEvents = Get-Scalar @'
SELECT COALESCE(SUM(CASE o.status
  WHEN 0 THEN 1
  WHEN 1 THEN 2
  WHEN 2 THEN 2
  WHEN 3 THEN 3
  ELSE 1 END), 0)
FROM ticket_order o
JOIN sys_user u ON u.id = o.user_id
WHERE LEFT(u.account, 11) = 'BENCH_USER_';
'@
    $actualEvents = Get-Scalar @'
SELECT COUNT(*)
FROM outbox_event e
JOIN ticket_order o
  ON CONVERT(o.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci
   = CONVERT(e.aggregate_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
JOIN sys_user u ON u.id = o.user_id
WHERE LEFT(u.account, 11) = 'BENCH_USER_';
'@
    $orphanEvents = Get-Scalar @'
SELECT COUNT(*)
FROM outbox_event e
JOIN sys_user u
  ON u.id = CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.userId')) AS UNSIGNED)
LEFT JOIN ticket_order o
  ON CONVERT(o.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci
   = CONVERT(e.aggregate_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
WHERE LEFT(u.account, 11) = 'BENCH_USER_'
  AND o.id IS NULL;
'@
    if ($actualEvents -ne $expectedEvents) {
        throw "Outbox event count mismatch: expected=$expectedEvents actual=$actualEvents; cleanup not started"
    }
    if ($orphanEvents -ne 0) {
        throw "Outbox orphan events detected: $orphanEvents; cleanup not started"
    }

    $resetSql = Get-Content -Raw (Join-Path $PSScriptRoot 'fixtures\reset.sql')
    Invoke-Db $resetSql | Out-Null

    $userRows = @(Invoke-Db "SELECT id FROM sys_user WHERE LEFT(account, 11) = 'BENCH_USER_' ORDER BY id;")
    $userIds = @($userRows | Select-Object -Skip 1 | ForEach-Object { [long](($_ -split "`t")[0]) })
    foreach ($sessionId in 910001..910008) {
        docker compose exec -T redis redis-cli DEL "schedule:stock:$sessionId" "session:detail:$sessionId" | Out-Null
        docker compose exec -T redis redis-cli SET "schedule:stock:$sessionId" 20000 EX 86400 | Out-Null
    }
    $userIdSet = @{}
    foreach ($userId in $userIds) { $userIdSet[[string]$userId] = $true }
    $rateLimitKeys = @(docker compose exec -T redis redis-cli --scan --pattern 'rate_limit:*:user:*')
    $ownedRateLimitKeys = @($rateLimitKeys | Where-Object {
        $_ -match ':user:(\d+):' -and $userIdSet.ContainsKey($Matches[1])
    })
    if ($ownedRateLimitKeys.Count -gt 0) {
        docker compose exec -T redis redis-cli DEL $ownedRateLimitKeys | Out-Null
    }

    [pscustomobject]@{
        status = 'RESET_OK'
        users = $userIds.Count
        sessions = 8
        stockPerSession = 20000
        outbox = [pscustomobject]@{
            observationCheckpointSeconds = $ObservationCheckpointSeconds
            cleanupSafetyTimeoutSeconds = $DrainTimeoutSeconds
            openAtMeasurementEnd = [long]$initial.open
            openAt10Seconds = [long]$at10Seconds.open
            openAt30Seconds = [long]$at30Seconds.open
            timeToZeroSeconds = $timeToZeroSeconds
            peakOpen = [long]$peakOpen
            peakPending = [long]$peakPending
            peakProcessing = [long]$peakProcessing
            peakFailed = [long]$peakFailed
            finalPending = [long]$finalState.pending
            finalProcessing = [long]$finalState.processing
            finalFailed = [long]$finalState.failed
            finalMissingConsumed = [long]$finalState.missingConsumed
            expectedEvents = [long]$expectedEvents
            actualEvents = [long]$actualEvents
            orphanEvents = [long]$orphanEvents
            asyncBacklogPresent = [long]$at30Seconds.open -gt 0
        }
    } | ConvertTo-Json -Depth 5 -Compress
} finally {
    Pop-Location
}
