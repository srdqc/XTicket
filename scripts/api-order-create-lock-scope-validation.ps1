param([string]$BaseUrl = 'http://localhost')

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$sameTokenOnly = $env:XTICKET_SAME_TOKEN_ONLY -eq 'true'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$benchmark = Join-Path $PSScriptRoot 'benchmark'
$tokensPath = Join-Path $benchmark 'results\raw\tokens.json'
if (-not (Test-Path -LiteralPath $tokensPath)) { throw 'Run benchmark prepare.ps1 first' }
$users = @(Get-Content -Raw -LiteralPath $tokensPath | ConvertFrom-Json)
if ($users.Count -lt 8) { throw 'At least 8 benchmark users are required' }

function Invoke-Api([string]$Path, $User, $Body) {
    $headers = @{ Authorization = "Bearer $($User.token)"; 'X-Trace-Id' = "scope-$([guid]::NewGuid().ToString('N'))" }
    $json = $Body | ConvertTo-Json -Depth 6 -Compress
    try {
        return Invoke-RestMethod -Method Post -Uri "$BaseUrl$Path" -Headers $headers -Body $json -ContentType 'application/json'
    } catch {
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { return ($_.ErrorDetails.Message | ConvertFrom-Json) }
        throw
    }
}

function Invoke-Concurrent($Requests) {
    $startAt = [DateTime]::UtcNow.AddSeconds(2)
    $jobs = @()
    foreach ($request in @($Requests)) {
        $jobs += Start-Job -ScriptBlock {
            param($url, $token, $body, $start)
            while ([DateTime]::UtcNow -lt $start) { Start-Sleep -Milliseconds 10 }
            $headers = @{ Authorization = "Bearer $token"; 'X-Trace-Id' = "scope-$([guid]::NewGuid().ToString('N'))" }
            try {
                Invoke-RestMethod -Method Post -Uri $url -Headers $headers -Body $body -ContentType 'application/json'
            } catch {
                if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $_.ErrorDetails.Message | ConvertFrom-Json } else { throw }
            }
        } -ArgumentList "$BaseUrl$($request.path)", $request.user.token, $request.json, $startAt
    }
    $done = Wait-Job -Job $jobs -Timeout 30
    if (@($done).Count -ne @($jobs).Count) { $jobs | Stop-Job; $jobs | Remove-Job -Force; throw 'Concurrent requests timed out' }
    $results = @($jobs | Receive-Job)
    $jobs | Remove-Job -Force
    return $results
}

function Db-Scalar([string]$Sql) {
    $command = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw --skip-column-names'
    $output = @($Sql | docker compose exec -T mysql sh -lc $command 2>&1)
    if ($LASTEXITCODE -ne 0) { throw (($output | Out-String).Trim()) }
    $rows = @($output | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -ne '' })
    if ($rows.Count -ne 1) { throw "Expected one scalar row, got $($rows.Count)" }
    return [long]$rows[0]
}

function Redis-Stock([long]$SessionId) {
    return [long]((& docker compose exec -T redis redis-cli GET "schedule:stock:$SessionId") | Select-Object -Last 1)
}

function Reset-Fixture {
    $output = & (Join-Path $benchmark 'reset.ps1')
    if ($LASTEXITCODE -ne 0) { throw (($output | Out-String).Trim()) }
}

function New-Seat([int]$Row, [int]$Col) { return @{ row = $Row; col = $Col } }
function New-CreateBody([long]$SessionId, $Seat, [string]$LockToken) {
    $body = @{ scheduleId = $SessionId; seats = @($Seat); seatCount = 1; seatsInfo = "$($Seat.row),$($Seat.col)" }
    if ($LockToken) { $body.lockToken = $LockToken }
    return $body
}
function New-Request($User, $Body) {
    return [pscustomobject]@{ path = '/api/order/create'; user = $User; json = ($Body | ConvertTo-Json -Depth 6 -Compress) }
}
function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }

$results = [ordered]@{}
Push-Location $root
try {
    Reset-Fixture
    $session = 910001L
    $seat = New-Seat 1 1
    $lock = Invoke-Api '/api/seat/lock' $users[0] @{ scheduleId = $session; seats = @($seat) }
    Assert-True ($lock.code -eq 200) 'same-token setup lock failed'
    $body = New-CreateBody $session $seat ([string]$lock.data.lockToken)
    $requests = @(1..8 | ForEach-Object { New-Request $users[0] $body })
    $responses = @(Invoke-Concurrent $requests)
    $orderNos = @($responses | Where-Object { $_.code -eq 200 } | ForEach-Object { $_.data.orderNo } | Select-Object -Unique)
    $tokenSuccessCount = @($responses | Where-Object { $_.code -eq 200 }).Count
    $tokenCount = Db-Scalar "SELECT COUNT(*) FROM ticket_order WHERE lock_token = '$($lock.data.lockToken)'"
    $tokenOutbox = Db-Scalar "SELECT COUNT(*) FROM outbox_event e JOIN ticket_order o ON o.order_no=e.aggregate_id WHERE o.lock_token='$($lock.data.lockToken)' AND e.event_type='CREATED'"
    Assert-True ($tokenSuccessCount -eq 8 -and $orderNos.Count -eq 1 -and $tokenCount -eq 1 -and $tokenOutbox -eq 1) "8-way same-token failed: success=$tokenSuccessCount distinctOrders=$($orderNos.Count) dbOrders=$tokenCount outbox=$tokenOutbox"
    $results.sameToken = 'PASS'
    if ($sameTokenOnly) { $results | ConvertTo-Json -Compress; return }

    Reset-Fixture
    $body = New-CreateBody $session $seat $null
    $requests = @(0..7 | ForEach-Object { New-Request $users[$_] $body })
    $responses = @(Invoke-Concurrent $requests)
    $sameSeatOrders = Db-Scalar "SELECT COUNT(*) FROM ticket_order WHERE schedule_id=$session"
    Assert-True (@($responses | Where-Object { $_.code -eq 200 }).Count -eq 1 -and $sameSeatOrders -eq 1 -and (Redis-Stock $session) -eq 19999) '8-way same-seat failed'
    $results.sameSeat = 'PASS'

    Reset-Fixture
    $requests = @()
    foreach ($i in 0..7) {
        $currentSeat = New-Seat 1 ($i + 1)
        $currentLock = Invoke-Api '/api/seat/lock' $users[$i] @{ scheduleId = $session; seats = @($currentSeat) }
        Assert-True ($currentLock.code -eq 200) "same-session lock $i failed"
        $requests += New-Request $users[$i] (New-CreateBody $session $currentSeat ([string]$currentLock.data.lockToken))
    }
    $responses = @(Invoke-Concurrent $requests)
    Assert-True (@($responses | Where-Object { $_.code -eq 200 }).Count -eq 8 -and (Db-Scalar "SELECT COUNT(*) FROM ticket_order WHERE schedule_id=$session") -eq 8 -and (Redis-Stock $session) -eq 19992) 'same-session different-seat failed'
    $results.sameSessionDifferentSeat = 'PASS'

    Reset-Fixture
    $requests = @()
    foreach ($i in 0..7) {
        $currentSession = 910001L + $i
        $currentSeat = New-Seat 1 1
        $currentLock = Invoke-Api '/api/seat/lock' $users[$i] @{ scheduleId = $currentSession; seats = @($currentSeat) }
        Assert-True ($currentLock.code -eq 200) "different-session lock $i failed"
        $requests += New-Request $users[$i] (New-CreateBody $currentSession $currentSeat ([string]$currentLock.data.lockToken))
    }
    $responses = @(Invoke-Concurrent $requests)
    $sessionOrderCount = Db-Scalar 'SELECT COUNT(*) FROM ticket_order WHERE schedule_id BETWEEN 910001 AND 910008'
    $stockSum = Db-Scalar 'SELECT SUM(available_seats) FROM activity_session WHERE id BETWEEN 910001 AND 910008'
    Assert-True (@($responses | Where-Object { $_.code -eq 200 }).Count -eq 8 -and $sessionOrderCount -eq 8 -and $stockSum -eq 159992) 'different-session create failed'
    $results.differentSession = 'PASS'

    Reset-Fixture
    $expiredLock = Invoke-Api '/api/seat/lock' $users[0] @{ scheduleId = $session; seats = @($seat) }
    $expiredToken = [string]$expiredLock.data.lockToken
    [void](Db-Scalar "UPDATE seat_lock SET lock_until=DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE lock_token='$expiredToken'; SELECT ROW_COUNT()")
    $expiredCreate = Invoke-Api '/api/order/create' $users[0] (New-CreateBody $session $seat $expiredToken)
    Assert-True ($expiredCreate.code -ne 200 -and (Db-Scalar "SELECT COUNT(*) FROM ticket_order WHERE lock_token='$expiredToken'") -eq 0) 'expired lock accepted'
    $results.expiredLock = 'PASS'

    Reset-Fixture
    $stockLock = Invoke-Api '/api/seat/lock' $users[0] @{ scheduleId = $session; seats = @($seat) }
    [void](Db-Scalar "UPDATE activity_session SET available_seats=0, version=version+1 WHERE id=$session; SELECT ROW_COUNT()")
    [void](& docker compose exec -T redis redis-cli SET "schedule:stock:$session" 0)
    $stockToken = [string]$stockLock.data.lockToken
    $stockCreate = Invoke-Api '/api/order/create' $users[0] (New-CreateBody $session $seat $stockToken)
    Assert-True ($stockCreate.code -ne 200 -and (Db-Scalar "SELECT COUNT(*) FROM ticket_order WHERE lock_token='$stockToken'") -eq 0 -and (Redis-Stock $session) -eq 0) 'insufficient stock accepted'
    $results.insufficientStock = 'PASS'
    $results.oversellCount = 0
    $results.duplicateOrderCount = 0
    $results.inventoryConsistency = 'PASS'
    $results | ConvertTo-Json -Compress
} finally {
    Reset-Fixture
    Pop-Location
}
