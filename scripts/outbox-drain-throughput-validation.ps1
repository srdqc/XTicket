param(
    [Parameter(Mandatory = $true)][ValidateSet(100, 500, 1000, 3000)][int]$Count,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9]{1,12}$')][string]$RunLabel,
    [ValidateSet('PENDING', 'PROCESSING')][string]$InitialStatus = 'PENDING',
    [int]$TimeoutSeconds = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$mysql = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw --skip-column-names'
$prefix = "obx-$RunLabel-"
$consumerGroup = 'maoyan_order_consumer_group'
$metricNames = @(
    'xticket.outbox.publisher.poll.duration',
    'xticket.outbox.publisher.claim.duration',
    'xticket.outbox.publisher.send.duration',
    'xticket.outbox.publisher.mark.published.duration',
    'xticket.outbox.publisher.batch.duration',
    'xticket.outbox.publisher.selected.count',
    'xticket.outbox.publisher.published.per.batch',
    'xticket.outbox.publisher.claim.success',
    'xticket.outbox.publisher.claim.conflict',
    'xticket.outbox.publish.failure'
)

function Invoke-Db([string]$Sql) {
    $rows = @($Sql | docker compose exec -T mysql sh -lc $script:mysql 2>&1)
    if ($LASTEXITCODE -ne 0) { throw (($rows | Out-String).Trim()) }
    return @($rows | Where-Object { $_ -and -not $_.StartsWith('mysql: [Warning]') })
}

function Get-Metric([string]$Name) {
    $raw = @(& docker compose exec -T backend wget -qO- "http://127.0.0.1:8080/actuator/metrics/$Name" 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Metric unavailable: $Name - $($raw -join ' ')" }
    $json = (($raw | Out-String).Trim() | ConvertFrom-Json)
    $values = @{}
    foreach ($measurement in @($json.measurements)) {
        $values[[string]$measurement.statistic] = [double]$measurement.value
    }
    return $values
}

function Get-Metrics {
    $snapshot = @{}
    foreach ($name in $script:metricNames) { $snapshot[$name] = Get-Metric $name }
    return $snapshot
}

function Metric-Delta($Before, $After, [string]$Name, [string]$Statistic) {
    $left = if ($Before[$Name].ContainsKey($Statistic)) { [double]$Before[$Name][$Statistic] } else { 0.0 }
    $right = if ($After[$Name].ContainsKey($Statistic)) { [double]$After[$Name][$Statistic] } else { 0.0 }
    return $right - $left
}

function Timer-Result($Before, $After, [string]$Name) {
    $countDelta = Metric-Delta $Before $After $Name 'COUNT'
    $totalDelta = Metric-Delta $Before $After $Name 'TOTAL_TIME'
    return [ordered]@{
        count = [long]$countDelta
        totalMs = [Math]::Round($totalDelta * 1000.0, 3)
        meanMs = if ($countDelta -gt 0) { [Math]::Round($totalDelta * 1000.0 / $countDelta, 3) } else { 0 }
    }
}

function Get-State {
    $row = @(Invoke-Db @"
SELECT
  SUM(status='PENDING'), SUM(status='PROCESSING'), SUM(status='FAILED'), SUM(status='PUBLISHED'),
  (SELECT COUNT(*) FROM consumed_event WHERE consumer_group='$consumerGroup' AND event_id LIKE '$prefix%'),
  COALESCE(SUM(retry_count),0)
FROM outbox_event WHERE event_id LIKE '$prefix%';
"@)
    if ($row.Count -ne 1) { throw "Unexpected state rows: $($row.Count)" }
    $v = @($row[0] -split "`t")
    if ($v.Count -ne 6) { throw "Unexpected state: $($row[0])" }
    return [pscustomobject]@{ pending=[long]$v[0]; processing=[long]$v[1]; failed=[long]$v[2]; published=[long]$v[3]; consumed=[long]$v[4]; retries=[long]$v[5] }
}

Push-Location $repoRoot
try {
    $existing = @(Invoke-Db "SELECT COUNT(*) FROM outbox_event WHERE event_id LIKE '$prefix%';")
    if ([long]$existing[0] -ne 0) { throw "Run label already exists: $RunLabel" }
    $before = Get-Metrics
    $types = @('CREATED', 'PAID', 'CANCELLED', 'REFUNDED')
    $tags = @('ORDER_CREATED', 'ORDER_PAID', 'ORDER_CANCELLED', 'ORDER_REFUNDED')
    $values = @()
    $occurredAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    for ($i = 1; $i -le $Count; $i++) {
        $eventId = '{0}{1:D6}' -f $prefix, $i
        $orderNo = 'OBX-{0}-{1:D6}' -f $RunLabel, $i
        $slot = ($i - 1) % 4
        $payload = [ordered]@{ eventId=$eventId; version=1; type=$types[$slot]; orderNo=$orderNo; userId=1; scheduleId=1; movieId=$null; movieName=$null; seatCount=1; totalPrice=1.00; occurredAt=$occurredAt; traceId="obx-$RunLabel" }
        $json = ($payload | ConvertTo-Json -Compress).Replace("'", "''")
        $values += "('$eventId','ORDER','$orderNo','$($types[$slot])','maoyan_order_event','$($tags[$slot])','$json','PENDING',0,NOW(),NOW(),NOW())"
    }
    $started = [DateTimeOffset]::UtcNow
    $initialUpdate = if ($InitialStatus -eq 'PROCESSING') { 'DATE_SUB(NOW(), INTERVAL 60 SECOND))' } else { 'NOW())' }
    $insertValues = ($values -join ',').Replace("'PENDING',0,NOW(),NOW(),NOW())", "'$InitialStatus',0,NOW(),NOW(),$initialUpdate")
    Invoke-Db ("START TRANSACTION; INSERT INTO outbox_event (event_id,aggregate_type,aggregate_id,event_type,topic,tag,payload,status,retry_count,next_retry_time,create_time,update_time) VALUES " + $insertValues + '; COMMIT;') | Out-Null
    $at30 = $null; $at60 = $null; $at120 = $null; $state = $null
    do {
        $state = Get-State
        $elapsed = ([DateTimeOffset]::UtcNow - $started).TotalSeconds
        if ($null -eq $at30 -and $elapsed -ge 30) { $at30 = $state.pending + $state.processing + $state.failed }
        if ($null -eq $at60 -and $elapsed -ge 60) { $at60 = $state.pending + $state.processing + $state.failed }
        if ($null -eq $at120 -and $elapsed -ge 120) { $at120 = $state.pending + $state.processing + $state.failed }
        $done = $state.published -eq $Count -and $state.consumed -eq $Count -and $state.pending -eq 0 -and $state.processing -eq 0 -and $state.failed -eq 0
        if (-not $done) { Start-Sleep -Milliseconds 200 }
    } while (-not $done -and $elapsed -lt $TimeoutSeconds)
    if (-not $done) { throw "Drain timeout: count=$Count pending=$($state.pending) processing=$($state.processing) failed=$($state.failed) published=$($state.published) consumed=$($state.consumed)" }
    $drainSeconds = [Math]::Round(([DateTimeOffset]::UtcNow - $started).TotalSeconds, 3)
    $after = Get-Metrics
    $result = [ordered]@{
        status='OUTBOX_DRAIN_OK'; count=$Count; runLabel=$RunLabel; initialStatus=$InitialStatus; drainSeconds=$drainSeconds
        eventsPerSecond=[Math]::Round($Count / $drainSeconds, 3)
        backlog30=if ($null -eq $at30) { 0 } else { $at30 }
        backlog60=if ($null -eq $at60) { 0 } else { $at60 }
        backlog120=if ($null -eq $at120) { 0 } else { $at120 }
        poll=Timer-Result $before $after 'xticket.outbox.publisher.poll.duration'
        claim=Timer-Result $before $after 'xticket.outbox.publisher.claim.duration'
        send=Timer-Result $before $after 'xticket.outbox.publisher.send.duration'
        mark=Timer-Result $before $after 'xticket.outbox.publisher.mark.published.duration'
        batch=Timer-Result $before $after 'xticket.outbox.publisher.batch.duration'
        selected=[long](Metric-Delta $before $after 'xticket.outbox.publisher.selected.count' 'TOTAL')
        publishedPerBatch=[long](Metric-Delta $before $after 'xticket.outbox.publisher.published.per.batch' 'TOTAL')
        claimSuccess=[long](Metric-Delta $before $after 'xticket.outbox.publisher.claim.success' 'COUNT')
        claimConflict=[long](Metric-Delta $before $after 'xticket.outbox.publisher.claim.conflict' 'COUNT')
        sendErrors=[long](Metric-Delta $before $after 'xticket.outbox.publish.failure' 'COUNT')
        retries=[long]$state.retries
        final=$state
    }
    Invoke-Db "DELETE FROM consumed_event WHERE consumer_group='$consumerGroup' AND event_id LIKE '$prefix%'; DELETE FROM outbox_event WHERE event_id LIKE '$prefix%';" | Out-Null
    $result | ConvertTo-Json -Depth 6 -Compress
} finally {
    Pop-Location
}
