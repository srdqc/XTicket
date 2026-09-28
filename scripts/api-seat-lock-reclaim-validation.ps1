param([string]$BaseUrl = 'http://localhost')

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$benchmark = Join-Path $PSScriptRoot 'benchmark'
$tokensPath = Join-Path $benchmark 'results\raw\tokens.json'
if (-not (Test-Path -LiteralPath $tokensPath)) { throw 'Run benchmark prepare.ps1 first' }
$parsedUsers = Get-Content -Raw -LiteralPath $tokensPath | ConvertFrom-Json
$users = @($parsedUsers)
if ($users.Count -lt 25) { throw 'At least 25 benchmark users are required' }

function Invoke-Api($User, $Body) {
    $headers = @{ Authorization = "Bearer $($User.token)"; 'X-Trace-Id' = "reclaim-$([guid]::NewGuid().ToString('N'))" }
    $json = $Body | ConvertTo-Json -Depth 6 -Compress
    try {
        return Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/seat/lock" -Headers $headers -Body $json -ContentType 'application/json'
    } catch {
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { return ($_.ErrorDetails.Message | ConvertFrom-Json) }
        throw
    }
}

function Invoke-ConcurrentLocks([int]$Count, [long]$SessionId) {
    $startAt = [DateTime]::UtcNow.AddSeconds(2)
    $jobs = @()
    foreach ($i in 0..($Count - 1)) {
        $row = [math]::Floor($i / 12) + 90
        $col = ($i % 12) + 1
        $body = @{ scheduleId = $SessionId; seats = @(@{ row = $row; col = $col }) } | ConvertTo-Json -Depth 6 -Compress
        $jobs += Start-Job -ScriptBlock {
            param($url, $token, $json, $start)
            while ([DateTime]::UtcNow -lt $start) { Start-Sleep -Milliseconds 10 }
            $headers = @{ Authorization = "Bearer $token"; 'X-Trace-Id' = "reclaim-$([guid]::NewGuid().ToString('N'))" }
            try {
                Invoke-RestMethod -Method Post -Uri "$url/api/seat/lock" -Headers $headers -Body $json -ContentType 'application/json'
            } catch {
                if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $_.ErrorDetails.Message | ConvertFrom-Json } else { throw }
            }
        } -ArgumentList $BaseUrl, $users[$i].token, $body, $startAt
    }
    $done = Wait-Job -Job $jobs -Timeout 45
    if (@($done).Count -ne $Count) { $jobs | Stop-Job; $jobs | Remove-Job -Force; throw "$Count concurrent locks timed out" }
    $responses = @($jobs | Receive-Job)
    $jobs | Remove-Job -Force
    return $responses
}

function Db-Scalar([string]$Sql) {
    $command = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw --skip-column-names'
    $output = @($Sql | docker compose exec -T mysql sh -lc $command 2>&1)
    if ($LASTEXITCODE -ne 0) { throw (($output | Out-String).Trim()) }
    $rows = @($output | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -ne '' })
    if ($rows.Count -ne 1) { throw "Expected one scalar row, got $($rows.Count)" }
    return [string]$rows[0]
}

function Reset-Fixture {
    $output = & (Join-Path $benchmark 'reset.ps1')
    if ($LASTEXITCODE -ne 0) { throw (($output | Out-String).Trim()) }
}

function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Seat([int]$Row, [int]$Col) { return @{ row = $Row; col = $Col } }

$results = [ordered]@{}
$startedAt = [DateTimeOffset]::UtcNow.ToString('o')
Push-Location $root
try {
    $session = 910001L
    Reset-Fixture
    $singleRow = 100
    $singleCol = 200
    while ((Db-Scalar "SELECT (SELECT COUNT(*) FROM seat_lock WHERE schedule_id=$session AND row_num=$singleRow AND col_num=$singleCol) + (SELECT COUNT(*) FROM order_seat WHERE schedule_id=$session AND row_num=$singleRow AND col_num=$singleCol)") -ne '0') {
        $singleCol--
        if ($singleCol -lt 1) { $singleRow--; $singleCol = 200 }
        if ($singleRow -lt 1) { throw 'No free benchmark seat for reclaim validation' }
    }
    $missing = Invoke-Api $users[0] @{ scheduleId = $session; seats = @(Seat $singleRow $singleCol) }
    Assert-True ($missing.code -eq 200) 'missing seat insert failed'
    $rowId = Db-Scalar "SELECT id FROM seat_lock WHERE schedule_id=$session AND row_num=$singleRow AND col_num=$singleCol"
    $active = Invoke-Api $users[1] @{ scheduleId = $session; seats = @(Seat $singleRow $singleCol) }
    Assert-True ($active.code -ne 200 -and (Db-Scalar "SELECT COUNT(*) FROM seat_lock WHERE id=$rowId") -eq '1') 'active seat was reclaimed'
    [void](Db-Scalar "UPDATE seat_lock SET lock_until=DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id=$rowId; SELECT ROW_COUNT()")
    $reclaimed = Invoke-Api $users[1] @{ scheduleId = $session; seats = @(Seat $singleRow $singleCol) }
    Assert-True ($reclaimed.code -eq 200 -and (Db-Scalar "SELECT id FROM seat_lock WHERE schedule_id=$session AND row_num=$singleRow AND col_num=$singleCol") -eq $rowId -and (Db-Scalar "SELECT COUNT(*) FROM seat_lock WHERE schedule_id=$session AND row_num=$singleRow AND col_num=$singleCol") -eq '1') 'expired seat was not reclaimed in place'
    [void](Db-Scalar "UPDATE seat_lock SET order_no='BOUND-TEST', lock_until=DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id=$rowId; SELECT ROW_COUNT()")
    $bound = Invoke-Api $users[2] @{ scheduleId = $session; seats = @(Seat $singleRow $singleCol) }
    Assert-True ($bound.code -ne 200 -and (Db-Scalar "SELECT order_no FROM seat_lock WHERE id=$rowId") -eq 'BOUND-TEST') 'bound seat was reclaimed'
    $results.singleState = 'PASS'

    foreach ($count in @(8, 16, 25, 25, 25)) {
        Reset-Fixture
        $responses = @(Invoke-ConcurrentLocks $count $session)
        $success = @($responses | Where-Object { $_.code -eq 200 }).Count
        $rows = [int](Db-Scalar "SELECT COUNT(*) FROM seat_lock WHERE schedule_id=$session AND status=1")
        Assert-True ($success -eq $count -and $rows -eq $count) "$count different-seat locks failed: success=$success rows=$rows"
    }
    $deadlocks = @(& docker compose logs --since $startedAt backend 2>&1 | Select-String 'Deadlock found when trying to get lock').Count
    Assert-True ($deadlocks -eq 0) "new deadlocks found: $deadlocks"
    $results.differentSeat = 'PASS:8,16,25'
    $results.deadlockRepro25 = 'PASS:3/3'
    $results.deadlockCount = 0
    $results | ConvertTo-Json -Compress
} finally {
    Reset-Fixture
    Pop-Location
}
