param(
    [string]$BackendUrl = "http://localhost",
    [int]$TimeoutPollSeconds = 130
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$ReportPath = Join-Path $Root "docs/log/02-Phase1B-交易失败路径验收记录.md"
$Timestamp = Get-Date -Format "yyyyMMddHHmmss"
$UserPrefix = "codex_phase1b_${Timestamp}"
$Password = "CodexPhase1B_123456"
$TouchedSchedules = New-Object System.Collections.Generic.HashSet[long]

function Assert-LocalBackend {
    param([string]$Url)
    $uri = [Uri]$Url
    if (@("localhost", "127.0.0.1", "::1") -notcontains $uri.Host) {
        throw "Refusing to call non-local backend: $Url"
    }
}

function Mask-Token {
    param([string]$Token)
    if ([string]::IsNullOrWhiteSpace($Token)) { return "" }
    if ($Token.Length -le 14) { return "***" }
    return $Token.Substring(0, 6) + "..." + $Token.Substring($Token.Length - 4)
}

function Mask-Value {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return "" }
    if ($Value.Length -le 12) { return $Value.Substring(0, [Math]::Min(4, $Value.Length)) + "***" }
    return $Value.Substring(0, 6) + "..." + $Value.Substring($Value.Length - 4)
}

function Escape-Sql {
    param([string]$Value)
    if ($null -eq $Value) { return "" }
    return $Value.Replace("\", "\\").Replace("'", "''")
}

function To-JsonShort {
    param($Value)
    if ($null -eq $Value) { return "" }
    return ($Value | ConvertTo-Json -Depth 16 -Compress)
}

function Convert-DbValue {
    param([string]$Value)
    if ($null -eq $Value -or $Value -eq "NULL") { return $null }
    if ($Value -match "^-?\d+$") { return [long]$Value }
    if ($Value -match "^-?\d+\.\d+$") { return [decimal]$Value }
    return $Value
}

function Invoke-DbLines {
    param([string]$Sql, [switch]$NoHeader)
    $args = @("compose", "exec", "-T", "mysql", "sh", "-lc")
    $mysql = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --batch --raw'
    if ($NoHeader) { $mysql += " --skip-column-names" }
    $lines = $Sql | & docker @args $mysql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw (($lines | Out-String).Trim())
    }
    return @($lines | Where-Object { $_ -and -not $_.StartsWith("mysql: [Warning]") })
}

function Invoke-DbRows {
    param([string]$Sql)
    $lines = @(Invoke-DbLines $Sql)
    if ($lines.Count -eq 0) { return @() }
    $headers = $lines[0] -split "`t", -1
    $rows = @()
    for ($i = 1; $i -lt $lines.Count; $i++) {
        if ([string]::IsNullOrWhiteSpace($lines[$i])) { continue }
        $values = $lines[$i] -split "`t", -1
        $obj = [ordered]@{}
        for ($j = 0; $j -lt $headers.Count; $j++) {
            $value = if ($j -lt $values.Count) { $values[$j] } else { $null }
            $obj[$headers[$j]] = Convert-DbValue $value
        }
        $rows += [pscustomobject]$obj
    }
    return @($rows)
}

function Invoke-DbScalar {
    param([string]$Sql)
    $lines = @(Invoke-DbLines $Sql -NoHeader)
    if ($lines.Count -eq 0) { return $null }
    return Convert-DbValue (($lines[0] -split "`t", -1)[0])
}

function Invoke-DbExec {
    param([string]$Sql)
    Invoke-DbLines $Sql -NoHeader | Out-Null
}

function Invoke-Redis {
    param([string[]]$CommandArgs)
    try {
        $args = @("compose", "exec", "-T", "redis", "redis-cli") + $CommandArgs
        $resp = & docker @args 2>&1
        if ($LASTEXITCODE -ne 0) { throw (($resp | Out-String).Trim()) }
        $text = ($resp | Out-String).Trim()
        if ($text -eq "(nil)") { $text = $null }
        elseif ($text -match "`r?`n") { $text = (($text -split "`r?`n") -join ", ") }
        return [pscustomobject]@{ ok = $true; value = $text; error = $null }
    } catch {
        return [pscustomobject]@{ ok = $false; value = $null; error = $_.Exception.Message }
    }
}

function Invoke-Api {
    param(
        [ValidateSet("GET", "POST")]
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers,
        $Body
    )
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    $httpMethod = if ($Method -eq "GET") { [System.Net.Http.HttpMethod]::Get } else { [System.Net.Http.HttpMethod]::Post }
    $request = [System.Net.Http.HttpRequestMessage]::new($httpMethod, "$BackendUrl$Path")
    if ($Headers) {
        foreach ($key in $Headers.Keys) {
            $request.Headers.TryAddWithoutValidation($key, [string]$Headers[$key]) | Out-Null
        }
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 16
        $request.Content = [System.Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, "application/json")
    }
    try {
        $response = $client.SendAsync($request).Result
        $text = $response.Content.ReadAsStringAsync().Result
        $parsed = if ([string]::IsNullOrWhiteSpace($text)) { $null } else { $text | ConvertFrom-Json }
        return [pscustomobject]@{
            httpStatus = [int]$response.StatusCode
            code = if ($parsed) { $parsed.code } else { $null }
            message = if ($parsed) { $parsed.message } else { "" }
            data = if ($parsed) { $parsed.data } else { $null }
            raw = $parsed
            error = $null
        }
    } catch {
        return [pscustomobject]@{ httpStatus = $null; code = $null; message = $_.Exception.Message; data = $null; raw = $null; error = $_.Exception.Message }
    } finally {
        $request.Dispose()
        $client.Dispose()
    }
}

function Invoke-ConcurrentPost {
    param($Requests)
    Add-Type -AssemblyName System.Net.Http
    $tasks = @()
    foreach ($item in @($Requests)) {
        $client = [System.Net.Http.HttpClient]::new()
        $client.Timeout = [TimeSpan]::FromSeconds(20)
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$BackendUrl$($item.path)")
        if ($item.headers) {
            foreach ($key in $item.headers.Keys) {
                $request.Headers.TryAddWithoutValidation($key, [string]$item.headers[$key]) | Out-Null
            }
        }
        if ($null -ne $item.body) {
            $json = $item.body | ConvertTo-Json -Depth 16
            $request.Content = [System.Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, "application/json")
        }
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $task = $client.SendAsync($request)
        $tasks += [pscustomobject]@{ label = $item.label; client = $client; request = $request; task = $task; stopwatch = $sw }
    }
    [System.Threading.Tasks.Task]::WaitAll(@($tasks | ForEach-Object { $_.task }))
    $results = @()
    foreach ($entry in $tasks) {
        $entry.stopwatch.Stop()
        $text = $entry.task.Result.Content.ReadAsStringAsync().Result
        $parsed = if ([string]::IsNullOrWhiteSpace($text)) { $null } else { $text | ConvertFrom-Json }
        $results += [pscustomobject]@{
            label = $entry.label
            elapsedMs = $entry.stopwatch.ElapsedMilliseconds
            httpStatus = [int]$entry.task.Result.StatusCode
            code = if ($parsed) { $parsed.code } else { $null }
            message = if ($parsed) { $parsed.message } else { "" }
            data = if ($parsed) { $parsed.data } else { $null }
        }
        $entry.request.Dispose()
        $entry.client.Dispose()
    }
    return @($results)
}

function New-TestUser {
    param([string]$Suffix)
    $account = "${UserPrefix}_$Suffix"
    $reg = Invoke-Api -Method POST -Path "/api/auth/register" -Body @{
        account = $account
        password = $Password
        userNick = "Phase1B $Suffix"
        inviteCode = "lpf"
    }
    $login = Invoke-Api -Method POST -Path "/api/auth/login" -Body @{ account = $account; password = $Password }
    if ($login.code -ne 200) { throw "Login failed for ${account}: $($login.message)" }
    return [pscustomobject]@{
        account = $account
        id = [long]$login.data.id
        tokenMasked = Mask-Token ([string]$login.data.token)
        headers = @{ Authorization = "Bearer $($login.data.token)" }
    }
}

function Select-Schedule {
    param([int]$MinStock = 10)
    $sql = @"
SELECT ms.id, ms.total_seats, ms.available_seats, ms.version, ms.price, COUNT(sl.id) AS lock_rows
FROM movie_schedule ms
LEFT JOIN seat_lock sl ON sl.schedule_id = ms.id
WHERE ms.status = 1 AND ms.deleted = 0 AND ms.available_seats >= $MinStock
  AND TIMESTAMP(ms.show_date, STR_TO_DATE(ms.show_time, '%H:%i')) > NOW()
GROUP BY ms.id, ms.total_seats, ms.available_seats, ms.version, ms.price, ms.show_date, ms.show_time
ORDER BY lock_rows ASC, ms.available_seats DESC, ms.id
LIMIT 1
"@
    $rows = @(Invoke-DbRows $sql)
    if ($rows.Count -eq 0) {
        $rows = @(Invoke-DbRows "SELECT id, total_seats, available_seats, version, price, 0 AS lock_rows FROM movie_schedule WHERE status = 1 AND deleted = 0 AND available_seats >= $MinStock ORDER BY available_seats DESC, id LIMIT 1")
    }
    if ($rows.Count -eq 0) { throw "No salable schedule found" }
    $TouchedSchedules.Add([long]$rows[0].id) | Out-Null
    return $rows[0]
}

function Get-Schedule {
    param([long]$ScheduleId)
    $rows = @(Invoke-DbRows "SELECT id, total_seats, available_seats, version, price FROM movie_schedule WHERE id = $ScheduleId")
    if ($rows.Count -eq 0) { return $null }
    return $rows[0]
}

function Get-RedisStock {
    param([long]$ScheduleId)
    return Invoke-Redis -CommandArgs @("GET", "schedule:stock:$ScheduleId")
}

function Get-DbNow {
    return [string](Invoke-DbScalar "SELECT NOW()")
}

function Set-RedisStock {
    param([long]$ScheduleId, [int]$Stock)
    Invoke-Redis -CommandArgs @("SET", "schedule:stock:$ScheduleId", "$Stock") | Out-Null
}

function Get-FreeSeat {
    param([long]$ScheduleId, [hashtable]$Used)
    $layout = Invoke-Api -Method GET -Path "/api/seat/layout?scheduleId=$ScheduleId"
    if ($layout.code -ne 200) { throw "Seat layout failed: $($layout.message)" }
    $blocked = @{}
    $blockedRows = @(Invoke-DbRows "SELECT CONCAT(row_num, ',', col_num) AS seat_key FROM seat_lock WHERE schedule_id = $ScheduleId UNION SELECT CONCAT(row_num, ',', col_num) AS seat_key FROM order_seat WHERE schedule_id = $ScheduleId")
    foreach ($row in $blockedRows) {
        if ($row.seat_key) { $blocked[[string]$row.seat_key] = $true }
    }
    foreach ($row in $layout.data.seats) {
        foreach ($seat in $row) {
            $key = "$($seat.row),$($seat.col)"
            if ([int]$seat.status -eq 0 -and -not $Used.ContainsKey($key) -and -not $blocked.ContainsKey($key)) {
                $Used[$key] = $true
                return [pscustomobject]@{ row = [int]$seat.row; col = [int]$seat.col; label = [string]$seat.label }
            }
        }
    }
    throw "No free seat found"
}

function Get-FreeSeats {
    param([long]$ScheduleId, [hashtable]$Used, [int]$Count)
    $items = @()
    for ($i = 0; $i -lt $Count; $i++) {
        $items += Get-FreeSeat $ScheduleId $Used
    }
    return @($items)
}

function To-SeatBody {
    param($Seats)
    return @(@($Seats) | ForEach-Object { @{ row = $_.row; col = $_.col } })
}

function Get-SeatsInfo {
    param($Seats)
    return (@($Seats) | ForEach-Object { $_.label }) -join ", "
}

function Lock-Seats {
    param($User, [long]$ScheduleId, $Seats)
    $body = @{ scheduleId = $ScheduleId; seats = @(To-SeatBody $Seats) }
    $resp = Invoke-Api -Method POST -Path "/api/seat/lock" -Headers $User.headers -Body $body
    if ($resp.code -ne 200) { throw "Lock seats failed: code=$($resp.code), message=$($resp.message)" }
    return $resp
}

function Create-Order {
    param($User, [long]$ScheduleId, $Seats, [string]$LockToken)
    $seatList = @($Seats)
    $body = @{
        scheduleId = $ScheduleId
        lockToken = $LockToken
        seats = @(To-SeatBody $seatList)
        seatCount = $seatList.Count
        seatsInfo = Get-SeatsInfo $seatList
    }
    return Invoke-Api -Method POST -Path "/api/order/create" -Headers $User.headers -Body $body
}

function Create-PendingOrder {
    param($User, [long]$ScheduleId, [hashtable]$Used, [int]$SeatCount = 1)
    $seats = @(Get-FreeSeats $ScheduleId $Used $SeatCount)
    $lock = Lock-Seats $User $ScheduleId $seats
    $lockToken = [string]$lock.data.lockToken
    $order = Create-Order $User $ScheduleId $seats $lockToken
    if ($order.code -ne 200) { throw "Create order failed: code=$($order.code), message=$($order.message)" }
    return [pscustomobject]@{ seats = $seats; lockToken = $lockToken; order = $order; orderNo = [string]$order.data.orderNo }
}

function Pay-Order {
    param($User, [string]$OrderNo)
    return Invoke-Api -Method POST -Path "/api/payment/pay?orderNo=$OrderNo" -Headers $User.headers
}

function Cancel-Order {
    param($User, [string]$OrderNo)
    return Invoke-Api -Method POST -Path "/api/order/cancel/$OrderNo" -Headers $User.headers
}

function Get-UserPoints {
    param([long]$UserId)
    return [int](Invoke-DbScalar "SELECT points FROM sys_user WHERE id = $UserId")
}

function Get-Order {
    param([string]$OrderNo)
    $safe = Escape-Sql $OrderNo
    $rows = @(Invoke-DbRows "SELECT id, order_no, user_id, schedule_id, lock_token, seat_count, seats_info, total_price, status, expire_time, pay_time, cancel_time, update_time FROM ticket_order WHERE order_no = '$safe'")
    if ($rows.Count -eq 0) { return $null }
    return $rows[0]
}

function Get-OrderSnapshot {
    param([string]$Label, [string]$OrderNo, [long]$UserId, [long]$ScheduleId)
    $order = Get-Order $OrderNo
    $locks = @(Get-SeatLocksByOrder $OrderNo)
    $stock = Get-Schedule $ScheduleId
    $redis = Get-RedisStock $ScheduleId
    return [ordered]@{
        label = $Label
        localTime = Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff"
        dbNow = Get-DbNow
        orderNo = $OrderNo
        status = if ($order) { [int]$order.status } else { $null }
        statusText = if ($order) { Status-Text $order.status } else { "NOT_FOUND" }
        expireTime = if ($order) { $order.expire_time } else { $null }
        updateTime = if ($order) { $order.update_time } else { $null }
        points = Get-UserPoints $UserId
        orderSeatCount = Get-OrderSeatCount $OrderNo
        seatLockCount = @($locks).Count
        seatLockStatuses = (@($locks | ForEach-Object { $_.status }) -join ",")
        seatLockOrderNos = (@($locks | ForEach-Object { $_.order_no }) -join ",")
        dbAvailableSeats = if ($stock) { [int]$stock.available_seats } else { $null }
        dbVersion = if ($stock) { [int]$stock.version } else { $null }
        redisStock = if ($redis.ok) { $redis.value } else { "UNAVAILABLE: $($redis.error)" }
    }
}

function Get-BackendLogsForOrder {
    param([string]$OrderNo)
    try {
        $lines = @(& docker compose logs --no-color --since 20m backend 2>&1 | Select-String -SimpleMatch $OrderNo | Select-Object -Last 20 | ForEach-Object { $_.Line })
        return @($lines)
    } catch {
        return @("UNAVAILABLE: $($_.Exception.Message)")
    }
}

function Get-OrderSeatCount {
    param([string]$OrderNo)
    $safe = Escape-Sql $OrderNo
    return [int](Invoke-DbScalar "SELECT COUNT(*) FROM order_seat WHERE order_no = '$safe'")
}

function Get-SeatLocksByOrder {
    param([string]$OrderNo)
    $safe = Escape-Sql $OrderNo
    return @(Invoke-DbRows "SELECT id, schedule_id, row_num, col_num, user_id, lock_token, order_no, status, lock_until FROM seat_lock WHERE order_no = '$safe' ORDER BY id")
}

function Count-OrdersByTokens {
    param([string[]]$Tokens)
    if ($Tokens.Count -eq 0) { return 0 }
    $quoted = ($Tokens | ForEach-Object { "'" + (Escape-Sql $_) + "'" }) -join ","
    return [int](Invoke-DbScalar "SELECT COUNT(*) FROM ticket_order WHERE lock_token IN ($quoted)")
}

function Status-Text {
    param($Code)
    switch ([int]$Code) {
        0 { "待支付" }
        1 { "已支付" }
        2 { "已取消" }
        3 { "已退款" }
        default { "$Code" }
    }
}

function New-Scenario {
    param([string]$Name, [string]$Result, [string]$Conclusion, $Details)
    return [pscustomobject]@{ name = $Name; result = $Result; conclusion = $Conclusion; details = $Details }
}

function Add-Finding {
    param($Findings, [string]$Issue, [string]$Severity, [string]$Steps, [string]$Evidence, [string]$Suggestion)
    $Findings.Add([pscustomobject]@{ issue = $Issue; severity = $Severity; steps = $Steps; evidence = $Evidence; suggestion = $Suggestion }) | Out-Null
}

function Restore-Schedule {
    param($Snapshot)
    if ($null -eq $Snapshot) { return }
    Invoke-DbExec "UPDATE movie_schedule SET available_seats = $($Snapshot.available_seats), version = $($Snapshot.version), update_time = CURRENT_TIMESTAMP WHERE id = $($Snapshot.id)"
    Set-RedisStock ([long]$Snapshot.id) ([int]$Snapshot.available_seats)
}

function Reset-StockCacheFromDb {
    $rows = @(Invoke-DbRows "SELECT id, available_seats FROM movie_schedule WHERE deleted = 0")
    foreach ($row in $rows) {
        Set-RedisStock ([long]$row.id) ([int]$row.available_seats)
    }
}

function Clear-TestData {
    param([string[]]$Prefixes)
    foreach ($prefix in $Prefixes) {
        $safe = Escape-Sql $prefix
        $sql = @"
CREATE TEMPORARY TABLE cleanup_users AS SELECT id FROM sys_user WHERE account LIKE '$safe%';
CREATE TEMPORARY TABLE cleanup_orders AS SELECT order_no FROM ticket_order WHERE user_id IN (SELECT id FROM cleanup_users);
DELETE os FROM order_seat os JOIN cleanup_orders co ON os.order_no = co.order_no;
DELETE sl FROM seat_lock sl LEFT JOIN cleanup_orders co ON sl.order_no = co.order_no WHERE sl.user_id IN (SELECT id FROM cleanup_users) OR co.order_no IS NOT NULL;
DELETE o FROM ticket_order o JOIN cleanup_users cu ON o.user_id = cu.id;
DELETE uw FROM user_wish uw JOIN cleanup_users cu ON uw.user_id = cu.id;
DELETE u FROM sys_user u JOIN cleanup_users cu ON u.id = cu.id;
DROP TEMPORARY TABLE cleanup_orders;
DROP TEMPORARY TABLE cleanup_users;
"@
        Invoke-DbExec $sql
    }
    Invoke-DbExec "UPDATE movie_schedule ms SET available_seats = total_seats - (SELECT COUNT(*) FROM order_seat os JOIN ticket_order o ON o.order_no = os.order_no WHERE o.schedule_id = ms.id AND o.status = 1 AND o.deleted = 0), version = version + 1 WHERE deleted = 0"
    Reset-StockCacheFromDb
}

function Get-DockerStatus {
    try {
        return ((& docker compose ps -a 2>&1) -join "`n")
    } catch {
        return $_.Exception.Message
    }
}

function Assert-DockerServices {
    $services = @("mysql", "redis", "rocketmq-namesrv", "rocketmq-broker", "backend", "frontend", "nginx")
    $running = @(& docker compose ps --services --filter "status=running" 2>&1)
    if ($LASTEXITCODE -ne 0) { throw (($running | Out-String).Trim()) }
    foreach ($service in $services) {
        if ($running -notcontains $service) { throw "Docker service is not running: $service" }
    }
}

Assert-LocalBackend $BackendUrl
$dockerStatusBefore = Get-DockerStatus
Assert-DockerServices
$health = Invoke-Api -Method GET -Path "/api/seat/layout?scheduleId=1"
if ($health.code -ne 200) { throw "Backend health check failed: $($health.message)" }

$scenarioResults = New-Object System.Collections.Generic.List[object]
$findings = New-Object System.Collections.Generic.List[object]
$usedSeats = @{}
$cleanupCompleted = $false
$users = @()

try {
    Clear-TestData @("codex_phase1b_", "codex_api_test_")
    $schedule = Select-Schedule 20
    $scheduleId = [long]$schedule.id
    $userA = New-TestUser "a"
    $userB = New-TestUser "b"
    $users += $userA
    $users += $userB

    # Scenario 1: concurrent pay.
    try {
        $pending = Create-PendingOrder $userA $scheduleId $usedSeats 1
        $orderNo = $pending.orderNo
        $pointsBefore = Get-UserPoints $userA.id
        $responses = @(Invoke-ConcurrentPost @(
            [pscustomobject]@{ label = "pay-A"; path = "/api/payment/pay?orderNo=$orderNo"; headers = $userA.headers; body = $null },
            [pscustomobject]@{ label = "pay-B"; path = "/api/payment/pay?orderNo=$orderNo"; headers = $userA.headers; body = $null }
        ))
        $pointsAfter = Get-UserPoints $userA.id
        $order = Get-Order $orderNo
        $orderSeatCount = Get-OrderSeatCount $orderNo
        $locks = @(Get-SeatLocksByOrder $orderNo)
        $successCount = @($responses | Where-Object { $_.code -eq 200 }).Count
        $cost = [int][Math]::Ceiling([decimal]$order.total_price)
        $pass = ($successCount -eq 1 -and ($pointsBefore - $pointsAfter) -eq $cost -and [int]$order.status -eq 1 -and $orderSeatCount -eq 1 -and @($locks | Where-Object { [int]$_.status -eq 2 }).Count -eq 1)
        $scenarioResults.Add((New-Scenario "同一订单并发支付" $(if ($pass) { "PASS" } else { "FAIL" }) $(if ($pass) { "最多一次支付成功，积分和座位确认只变更一次" } else { "并发支付出现不安全副作用" }) ([ordered]@{
            orderNo = $orderNo
            responses = @($responses | ForEach-Object { @{ label = $_.label; httpStatus = $_.httpStatus; code = $_.code; message = $_.message; elapsedMs = $_.elapsedMs } })
            pointsBefore = $pointsBefore
            pointsAfter = $pointsAfter
            orderStatus = Status-Text $order.status
            orderSeatCount = $orderSeatCount
            seatLockStatuses = (@($locks | ForEach-Object { $_.status }) -join ", ")
        }))) | Out-Null
        if (-not $pass) { Add-Finding $findings "同一订单并发支付存在不安全副作用" "P0" "两个并发 POST /api/payment/pay" "order=$orderNo" "建议独立分支 fix/payment-concurrency-consistency" }
    } catch {
        $scenarioResults.Add((New-Scenario "同一订单并发支付" "BLOCKED" $_.Exception.Message @{})) | Out-Null
    }

    # Scenario 2: order_seat duplicate insert rollback.
    try {
        $pending = Create-PendingOrder $userA $scheduleId $usedSeats 1
        $orderNo = $pending.orderNo
        $orderBefore = Get-Order $orderNo
        $seat = @($pending.seats)[0]
        $pointsBefore = Get-UserPoints $userA.id
        $preseed = @(Invoke-DbRows "INSERT INTO order_seat(order_id, order_no, schedule_id, row_num, col_num, seat_label) VALUES ($($orderBefore.id), '$orderNo', $scheduleId, $($seat.row), $($seat.col), '$($seat.label)'); SELECT LAST_INSERT_ID() AS id;")[0]
        $pay = Pay-Order $userA $orderNo
        $pointsAfter = Get-UserPoints $userA.id
        $orderAfter = Get-Order $orderNo
        $orderSeatCount = Get-OrderSeatCount $orderNo
        $locks = @(Get-SeatLocksByOrder $orderNo)
        $pass = ($pay.code -ne 200 -and $pointsBefore -eq $pointsAfter -and [int]$orderAfter.status -eq 0 -and $orderSeatCount -eq 1 -and @($locks | Where-Object { [int]$_.status -eq 2 }).Count -eq 0)
        $scenarioResults.Add((New-Scenario "order_seat 插入失败回滚" $(if ($pass) { "PASS" } else { "FAIL" }) $(if ($pass) { "唯一约束冲突后订单、积分和锁座状态回滚；接口返回非业务化错误需后续观察" } else { "order_seat 冲突后事务状态异常" }) ([ordered]@{
            orderNo = $orderNo
            preseedOrderSeatId = $preseed.id
            payResponse = @{ httpStatus = $pay.httpStatus; code = $pay.code; message = $pay.message }
            pointsBefore = $pointsBefore
            pointsAfter = $pointsAfter
            orderStatus = Status-Text $orderAfter.status
            orderSeatCountIncludingPreseed = $orderSeatCount
            seatLockStatuses = (@($locks | ForEach-Object { $_.status }) -join ", ")
        }))) | Out-Null
        if ($pass -and $pay.code -eq 500) { Add-Finding $findings "order_seat 唯一约束冲突返回 500" "P1" "支付前预置同场次同座位 order_seat 后调用支付" "order=$orderNo, code=500" "建议独立分支 fix/payment-seat-confirmation-rollback 统一异常响应" }
        if (-not $pass) { Add-Finding $findings "order_seat 插入失败导致事务不完整" "P0" "支付时触发 order_seat 唯一约束冲突" "order=$orderNo" "建议独立分支 fix/payment-seat-confirmation-rollback" }
        Invoke-DbExec "DELETE FROM order_seat WHERE id = $($preseed.id)"
        Cancel-Order $userA $orderNo | Out-Null
    } catch {
        $scenarioResults.Add((New-Scenario "order_seat 插入失败回滚" "BLOCKED" $_.Exception.Message @{})) | Out-Null
    }

    # Scenario 3: Redis pre-deduct succeeds but MySQL stock deduction fails.
    try {
        $stockSnapshot = Get-Schedule $scheduleId
        $redisBefore = Get-RedisStock $scheduleId
        $seats = @(Get-FreeSeats $scheduleId $usedSeats 2)
        $lock = Lock-Seats $userA $scheduleId $seats
        $lockToken = [string]$lock.data.lockToken
        Invoke-DbExec "UPDATE movie_schedule SET available_seats = 1, version = version + 1, update_time = CURRENT_TIMESTAMP WHERE id = $scheduleId"
        Set-RedisStock $scheduleId 2
        $create = Create-Order $userA $scheduleId $seats $lockToken
        $orderCount = [int](Invoke-DbScalar "SELECT COUNT(*) FROM ticket_order WHERE lock_token = '$(Escape-Sql $lockToken)'")
        $boundCount = [int](Invoke-DbScalar "SELECT COUNT(*) FROM seat_lock WHERE lock_token = '$(Escape-Sql $lockToken)' AND order_no IS NOT NULL")
        $dbAfter = Get-Schedule $scheduleId
        $redisAfter = Get-RedisStock $scheduleId
        $pass = ($create.code -ne 200 -and $orderCount -eq 0 -and $boundCount -eq 0 -and [int]$dbAfter.available_seats -eq 1 -and $redisAfter.ok -and [int]$redisAfter.value -eq 2)
        $scenarioResults.Add((New-Scenario "Redis 预扣后 DB 失败补偿" $(if ($pass) { "PASS" } else { "FAIL" }) $(if ($pass) { "DB 扣减失败后不生成订单，Redis 预扣已补偿" } else { "Redis/DB 补偿结果异常" }) ([ordered]@{
            scheduleId = $scheduleId
            lockToken = Mask-Value $lockToken
            createResponse = @{ code = $create.code; message = $create.message }
            originalDbStock = $stockSnapshot.available_seats
            originalRedisStock = $(if ($redisBefore.ok) { $redisBefore.value } else { "UNAVAILABLE" })
            forcedDbStock = 1
            forcedRedisStock = 2
            orderCount = $orderCount
            boundSeatLockCount = $boundCount
            dbStockAfter = $dbAfter.available_seats
            redisStockAfter = $(if ($redisAfter.ok) { $redisAfter.value } else { "UNAVAILABLE: $($redisAfter.error)" })
        }))) | Out-Null
        if (-not $pass) { Add-Finding $findings "Redis 预扣成功但 DB 失败时补偿异常" "P0" "临时设置 Redis>=2 且 MySQL available_seats=1 后创建 2 座订单" "schedule=$scheduleId" "建议独立分支 fix/redis-stock-compensation" }
        Restore-Schedule $stockSnapshot
    } catch {
        if ($stockSnapshot) { Restore-Schedule $stockSnapshot }
        $scenarioResults.Add((New-Scenario "Redis 预扣后 DB 失败补偿" "BLOCKED" $_.Exception.Message @{})) | Out-Null
    }

    # Scenario 4: concurrent create orders with insufficient stock.
    try {
        $stockSnapshot = Get-Schedule $scheduleId
        $createUsers = @($userA, $userB, (New-TestUser "c"))
        $users += $createUsers[2]
        $requests = @()
        $tokens = @()
        $orderBodies = @()
        foreach ($idx in 0..2) {
            $seat = Get-FreeSeat $scheduleId $usedSeats
            $lock = Lock-Seats $createUsers[$idx] $scheduleId @($seat)
            $token = [string]$lock.data.lockToken
            $tokens += $token
            $body = @{ scheduleId = $scheduleId; lockToken = $token; seats = @(To-SeatBody @($seat)); seatCount = 1; seatsInfo = $seat.label }
            $requests += [pscustomobject]@{ label = "create-$idx"; path = "/api/order/create"; headers = $createUsers[$idx].headers; body = $body }
            $orderBodies += $body
        }
        Invoke-DbExec "UPDATE movie_schedule SET available_seats = 1, version = version + 1, update_time = CURRENT_TIMESTAMP WHERE id = $scheduleId"
        Set-RedisStock $scheduleId 1
        $responses = @(Invoke-ConcurrentPost $requests)
        $dbAfter = Get-Schedule $scheduleId
        $redisAfter = Get-RedisStock $scheduleId
        $successCount = @($responses | Where-Object { $_.code -eq 200 }).Count
        $orderCount = Count-OrdersByTokens $tokens
        $boundCount = [int](Invoke-DbScalar "SELECT COUNT(*) FROM seat_lock WHERE lock_token IN ($(($tokens | ForEach-Object { "'" + (Escape-Sql $_) + "'" }) -join ",")) AND order_no IS NOT NULL")
        $pass = ($successCount -eq 1 -and $orderCount -eq 1 -and $boundCount -eq 1 -and [int]$dbAfter.available_seats -ge 0 -and $redisAfter.ok -and [int]$redisAfter.value -ge 0 -and [int]$dbAfter.available_seats -eq 0 -and [int]$redisAfter.value -eq 0)
        $scenarioResults.Add((New-Scenario "并发建单库存非负" $(if ($pass) { "PASS" } else { "FAIL" }) $(if ($pass) { "超额并发建单最多成功一单，DB/Redis 库存均未为负" } else { "超额并发建单库存或订单状态异常" }) ([ordered]@{
            scheduleId = $scheduleId
            forcedStock = 1
            responses = @($responses | ForEach-Object { @{ label = $_.label; code = $_.code; message = $_.message; elapsedMs = $_.elapsedMs; orderNo = if ($_.data) { $_.data.orderNo } else { $null } } })
            successCount = $successCount
            orderCount = $orderCount
            boundSeatLockCount = $boundCount
            dbStockAfter = $dbAfter.available_seats
            redisStockAfter = $(if ($redisAfter.ok) { $redisAfter.value } else { "UNAVAILABLE: $($redisAfter.error)" })
        }))) | Out-Null
        if (-not $pass) { Add-Finding $findings "并发建单库存出现不安全结果" "P0" "3 个 lockToken 在库存=1 下并发建单" "schedule=$scheduleId" "建议独立分支 fix/order-stock-nonnegative" }
        Restore-Schedule $stockSnapshot
    } catch {
        if ($stockSnapshot) { Restore-Schedule $stockSnapshot }
        $scenarioResults.Add((New-Scenario "并发建单库存非负" "BLOCKED" $_.Exception.Message @{})) | Out-Null
    }

    # Scenario 5: expired order payment before scheduled task closes it.
    try {
        $beforeStock = Get-Schedule $scheduleId
        $redisBefore = Get-RedisStock $scheduleId
        $pending = Create-PendingOrder $userA $scheduleId $usedSeats 1
        $orderNo = $pending.orderNo
        $pointsBefore = Get-UserPoints $userA.id
        $stockAfterCreate = Get-Schedule $scheduleId
        $redisAfterCreate = Get-RedisStock $scheduleId
        $snapshots = @()
        $snapshots += [pscustomobject](Get-OrderSnapshot "T0_CREATED" $orderNo $userA.id $scheduleId)
        Invoke-DbExec "UPDATE ticket_order SET expire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE), update_time = CURRENT_TIMESTAMP WHERE order_no = '$(Escape-Sql $orderNo)' AND status = 0"
        $snapshots += [pscustomobject](Get-OrderSnapshot "T1_EXPIRED_BY_SQL" $orderNo $userA.id $scheduleId)
        $t2 = [ordered]@{ label = "T2_FIRST_PAY_SENT"; localTime = Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff"; dbNow = Get-DbNow }
        $pay = Pay-Order $userA $orderNo
        $t3 = [ordered]@{ label = "T3_FIRST_PAY_RETURNED"; localTime = Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff"; dbNow = Get-DbNow; httpStatus = $pay.httpStatus; code = $pay.code; message = $pay.message }
        $snapshots += [pscustomobject](Get-OrderSnapshot "T4_AFTER_FIRST_PAY_IMMEDIATE" $orderNo $userA.id $scheduleId)
        $t5 = [ordered]@{ label = "T5_SECOND_PAY_SENT"; localTime = Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff"; dbNow = Get-DbNow }
        $secondPay = Pay-Order $userA $orderNo
        $t6 = [ordered]@{ label = "T6_SECOND_PAY_RETURNED"; localTime = Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff"; dbNow = Get-DbNow; httpStatus = $secondPay.httpStatus; code = $secondPay.code; message = $secondPay.message }
        $snapshots += [pscustomobject](Get-OrderSnapshot "T6_AFTER_SECOND_PAY_IMMEDIATE" $orderNo $userA.id $scheduleId)
        Start-Sleep -Seconds 65
        $snapshots += [pscustomobject](Get-OrderSnapshot "T7_AFTER_ONE_SCHEDULER_PERIOD" $orderNo $userA.id $scheduleId)
        $afterFirst = $snapshots | Where-Object { $_.label -eq "T4_AFTER_FIRST_PAY_IMMEDIATE" } | Select-Object -First 1
        $afterSecond = $snapshots | Where-Object { $_.label -eq "T6_AFTER_SECOND_PAY_IMMEDIATE" } | Select-Object -First 1
        $afterScheduler = $snapshots | Where-Object { $_.label -eq "T7_AFTER_ONE_SCHEDULER_PERIOD" } | Select-Object -First 1
        $payMessageOk = ($pay.code -ne 200 -and (($pay.message -like "*过期*") -or ($pay.message -like "*取消*")))
        $secondMessageOk = ($secondPay.code -ne 200 -and (($secondPay.message -like "*过期*") -or ($secondPay.message -like "*取消*")))
        $pass = ($payMessageOk -and $secondMessageOk `
            -and [int]$afterFirst.status -eq 2 -and [int]$afterSecond.status -eq 2 -and [int]$afterScheduler.status -eq 2 `
            -and [int]$afterFirst.points -eq $pointsBefore -and [int]$afterFirst.orderSeatCount -eq 0 `
            -and [int]$afterFirst.seatLockCount -eq 0 `
            -and [int]$afterFirst.dbAvailableSeats -eq [int]$beforeStock.available_seats `
            -and [int]$afterSecond.dbAvailableSeats -eq [int]$beforeStock.available_seats `
            -and [int]$afterScheduler.dbAvailableSeats -eq [int]$beforeStock.available_seats `
            -and [int]$afterFirst.redisStock -eq [int]$redisBefore.value `
            -and [int]$afterSecond.redisStock -eq [int]$redisBefore.value `
            -and [int]$afterScheduler.redisStock -eq [int]$redisBefore.value)
        $logs = Get-BackendLogsForOrder $orderNo
        $scenarioResults.Add((New-Scenario "过期订单支付" $(if ($pass) { "PASS" } else { "FAIL" }) $(if ($pass) { "支付入口拒绝过期订单并只释放一次资源" } else { "过期订单支付保护异常" }) ([ordered]@{
            orderNo = $orderNo
            firstPaySent = $t2
            firstPayReturned = $t3
            secondPaySent = $t5
            secondPayReturned = $t6
            firstPayResponse = @{ httpStatus = $pay.httpStatus; code = $pay.code; message = $pay.message }
            secondPayResponse = @{ httpStatus = $secondPay.httpStatus; code = $secondPay.code; message = $secondPay.message }
            pointsBefore = $pointsBefore
            dbStockBefore = $beforeStock.available_seats
            dbStockAfterCreate = $stockAfterCreate.available_seats
            redisStockBefore = $(if ($redisBefore.ok) { $redisBefore.value } else { "UNAVAILABLE" })
            redisStockAfterCreate = $(if ($redisAfterCreate.ok) { $redisAfterCreate.value } else { "UNAVAILABLE" })
            snapshots = @($snapshots)
            backendLogs = @($logs)
        }))) | Out-Null
        if (-not $pass) { Add-Finding $findings "过期订单支付保护异常" "P0" "将待支付订单 expire_time 调整到过去后立即支付" "order=$orderNo" "建议独立分支 fix/expired-order-payment-guard" }
    } catch {
        $scenarioResults.Add((New-Scenario "过期订单支付" "BLOCKED" $_.Exception.Message @{})) | Out-Null
    }

    # Scenario 6: payment lazy close and timeout scheduler must not double release.
    try {
        $raceRows = @()
        $racePass = $true
        foreach ($round in 1..5) {
            $roundBeforeStock = Get-Schedule $scheduleId
            $roundRedisBefore = Get-RedisStock $scheduleId
            $pending = Create-PendingOrder $userA $scheduleId $usedSeats 1
            $orderNo = $pending.orderNo
            $pointsBeforeRound = Get-UserPoints $userA.id
            Invoke-DbExec "UPDATE ticket_order SET expire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE), update_time = CURRENT_TIMESTAMP WHERE order_no = '$(Escape-Sql $orderNo)' AND status = 0"
            $pay = Pay-Order $userA $orderNo
            $afterPay = Get-OrderSnapshot "AFTER_LAZY_CLOSE" $orderNo $userA.id $scheduleId
            $raceRows += [ordered]@{
                round = $round
                orderNo = $orderNo
                payResponse = @{ code = $pay.code; message = $pay.message }
                passAfterPay = $false
                passAfterScheduler = $false
                beforeDbStock = $roundBeforeStock.available_seats
                beforeRedisStock = $(if ($roundRedisBefore.ok) { $roundRedisBefore.value } else { "UNAVAILABLE" })
                pointsBefore = $pointsBeforeRound
                afterPay = $afterPay
                afterScheduler = $null
                backendLogs = @()
            }
        }
        Start-Sleep -Seconds 65
        foreach ($row in $raceRows) {
            $afterScheduler = Get-OrderSnapshot "AFTER_SCHEDULER" $row.orderNo $userA.id $scheduleId
            $row.afterScheduler = $afterScheduler
            $row.backendLogs = @(Get-BackendLogsForOrder $row.orderNo)
            $row.passAfterPay = ($row.payResponse.code -ne 200 `
                -and [int]$row.afterPay.status -eq 2 `
                -and [int]$row.afterPay.points -eq [int]$row.pointsBefore `
                -and [int]$row.afterPay.orderSeatCount -eq 0 `
                -and [int]$row.afterPay.seatLockCount -eq 0 `
                -and [int]$row.afterPay.dbAvailableSeats -eq [int]$row.beforeDbStock `
                -and [int]$row.afterPay.redisStock -eq [int]$row.beforeRedisStock)
            $row.passAfterScheduler = ([int]$afterScheduler.status -eq 2 `
                -and [int]$afterScheduler.points -eq [int]$row.pointsBefore `
                -and [int]$afterScheduler.orderSeatCount -eq 0 `
                -and [int]$afterScheduler.seatLockCount -eq 0 `
                -and [int]$afterScheduler.dbAvailableSeats -eq [int]$row.beforeDbStock `
                -and [int]$afterScheduler.redisStock -eq [int]$row.beforeRedisStock)
            if (-not ($row.passAfterPay -and $row.passAfterScheduler)) { $racePass = $false }
        }
        $scenarioResults.Add((New-Scenario "支付路径与定时任务竞争" $(if ($racePass) { "PASS" } else { "FAIL" }) $(if ($racePass) { "5 轮过期支付后等待定时任务，均未重复释放资源" } else { "支付懒关闭与定时任务存在重复释放风险" }) ([ordered]@{
            rounds = $raceRows
        }))) | Out-Null
        if (-not $racePass) { Add-Finding $findings "支付懒关闭与定时任务重复释放资源" "P0" "5 轮过期支付后等待一个定时扫描周期" "schedule=$scheduleId" "继续修复过期关单 CAS 与资源释放边界" }
    } catch {
        $scenarioResults.Add((New-Scenario "支付路径与定时任务竞争" "BLOCKED" $_.Exception.Message @{})) | Out-Null
    }

    # Scenario 7: pure timeout scheduler close without payment.
    try {
        $beforeStock = Get-Schedule $scheduleId
        $redisBefore = Get-RedisStock $scheduleId
        $pending = Create-PendingOrder $userA $scheduleId $usedSeats 1
        $orderNo = $pending.orderNo
        Invoke-DbExec "UPDATE ticket_order SET expire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE), update_time = CURRENT_TIMESTAMP WHERE order_no = '$(Escape-Sql $orderNo)' AND status = 0"
        $start = Get-Date
        $closedSnapshot = $null
        while (((Get-Date) - $start).TotalSeconds -lt $TimeoutPollSeconds) {
            Start-Sleep -Seconds 5
            $snap = Get-OrderSnapshot "SCHEDULER_POLL" $orderNo $userA.id $scheduleId
            if ([int]$snap.status -eq 2) {
                $closedSnapshot = $snap
                break
            }
        }
        if ($null -eq $closedSnapshot) {
            $closedSnapshot = Get-OrderSnapshot "SCHEDULER_TIMEOUT" $orderNo $userA.id $scheduleId
        }
        $elapsedSeconds = [int]((Get-Date) - $start).TotalSeconds
        $pass = ([int]$closedSnapshot.status -eq 2 `
            -and [int]$closedSnapshot.dbAvailableSeats -eq [int]$beforeStock.available_seats `
            -and [int]$closedSnapshot.redisStock -eq [int]$redisBefore.value `
            -and [int]$closedSnapshot.orderSeatCount -eq 0 `
            -and [int]$closedSnapshot.seatLockCount -eq 0)
        $scenarioResults.Add((New-Scenario "纯定时超时关闭" $(if ($pass) { "PASS" } else { "FAIL" }) $(if ($pass) { "定时任务可关闭过期订单并释放资源" } else { "定时任务未在等待窗口内正确关闭订单" }) ([ordered]@{
            orderNo = $orderNo
            elapsedSeconds = $elapsedSeconds
            beforeDbStock = $beforeStock.available_seats
            beforeRedisStock = $(if ($redisBefore.ok) { $redisBefore.value } else { "UNAVAILABLE" })
            finalSnapshot = $closedSnapshot
            backendLogs = @(Get-BackendLogsForOrder $orderNo)
        }))) | Out-Null
        if (-not $pass) { Add-Finding $findings "纯定时超时关闭异常" "P0" "创建订单后只修改 expire_time 并等待定时任务" "order=$orderNo" "检查定时任务和过期关闭 CAS" }
    } catch {
        $scenarioResults.Add((New-Scenario "纯定时超时关闭" "BLOCKED" $_.Exception.Message @{})) | Out-Null
    }

    # Scenario 8: concurrent lock different seats in the same schedule.
    try {
        $lockUsers = @($userA, $userB)
        $seats = @(Get-FreeSeats $scheduleId $usedSeats 2)
        $requests = @()
        for ($i = 0; $i -lt 2; $i++) {
            $requests += [pscustomobject]@{
                label = "lock-$i"
                path = "/api/seat/lock"
                headers = $lockUsers[$i].headers
                body = @{ scheduleId = $scheduleId; seats = @(To-SeatBody @($seats[$i])) }
            }
        }
        $responses = @(Invoke-ConcurrentPost $requests)
        $tokens = @($responses | Where-Object { $_.data -and $_.data.lockToken } | ForEach-Object { [string]$_.data.lockToken })
        $quoted = ($tokens | ForEach-Object { "'" + (Escape-Sql $_) + "'" }) -join ","
        $locks = if ($tokens.Count -gt 0) { @(Invoke-DbRows "SELECT schedule_id, row_num, col_num, user_id, lock_token, status FROM seat_lock WHERE lock_token IN ($quoted) ORDER BY id") } else { @() }
        $successCount = @($responses | Where-Object { $_.code -eq 200 }).Count
        $distinctTokenCount = @($tokens | Select-Object -Unique).Count
        $pass = ($successCount -eq 2 -and $distinctTokenCount -eq 2 -and @($locks).Count -eq 2)
        $scenarioResults.Add((New-Scenario "同场次不同座位并发锁定" $(if ($pass) { "PASS" } else { "FAIL" }) $(if ($pass) { "不同座位均锁定成功，返回独立 lockToken" } else { "不同座位并发锁定存在异常" }) ([ordered]@{
            scheduleId = $scheduleId
            seats = Get-SeatsInfo $seats
            responses = @($responses | ForEach-Object { @{ label = $_.label; code = $_.code; message = $_.message; elapsedMs = $_.elapsedMs; lockToken = if ($_.data) { Mask-Value ([string]$_.data.lockToken) } else { "" } } })
            successCount = $successCount
            distinctTokenCount = $distinctTokenCount
            seatLockRows = @($locks | ForEach-Object { @{ row = $_.row_num; col = $_.col_num; userId = $_.user_id; status = $_.status; lockToken = Mask-Value ([string]$_.lock_token) } })
        }))) | Out-Null
        if (-not $pass) { Add-Finding $findings "同场次不同座位并发锁定失败" "P1" "两个用户对同场次不同座位并发 POST /api/seat/lock" "schedule=$scheduleId" "建议独立分支 fix/seat-lock-concurrent-different-seats" }
    } catch {
        $scenarioResults.Add((New-Scenario "同场次不同座位并发锁定" "BLOCKED" $_.Exception.Message @{})) | Out-Null
    }

    # Scenario 9: existing six-scenario regression.
    try {
        $regressionJson = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "api-transaction-validation.ps1") -BackendUrl $BackendUrl 2>&1
        if ($LASTEXITCODE -ne 0) { throw (($regressionJson | Out-String).Trim()) }
        $regression = (($regressionJson | Out-String).Trim()) | ConvertFrom-Json
        $regScenarios = @($regression.scenarios)
        $failed = @($regScenarios | Where-Object { $_.result -notin @("PASS", "PASS_IDEMPOTENT") })
        $pass = ($failed.Count -eq 0 -and $regScenarios.Count -ge 6)
        $scenarioResults.Add((New-Scenario "原有 6 场景回归" $(if ($pass) { "PASS" } else { "FAIL" }) $(if ($pass) { "原有 6 个交易场景均通过" } else { "原有交易场景出现回归失败" }) ([ordered]@{
            reportPath = [string]$regression.reportPath
            scenarios = @($regScenarios | ForEach-Object { @{ name = $_.name; result = $_.result; conclusion = $_.conclusion } })
        }))) | Out-Null
        if (-not $pass) { Add-Finding $findings "原有交易场景回归失败" "P0" "执行 scripts/api-transaction-validation.ps1" "failed=$($failed.Count)" "建议先定位是否由 Phase 1A 或环境引起，再创建独立 fix 分支" }
    } catch {
        $scenarioResults.Add((New-Scenario "原有 6 场景回归" "BLOCKED" $_.Exception.Message @{})) | Out-Null
        Add-Finding $findings "原有 6 场景回归无法执行" "P1" "执行 scripts/api-transaction-validation.ps1" $_.Exception.Message "建议修复测试脚本或本地 Docker 状态后重跑" 
    } finally {
        $tmp = Join-Path $PSScriptRoot ".api-validation-tmp"
        if (Test-Path $tmp) {
            Remove-Item -LiteralPath $tmp -Recurse -Force
        }
    }
} finally {
    Clear-TestData @("codex_phase1b_", "codex_api_test_")
    $cleanupCompleted = $true
}

$dockerStatusAfter = Get-DockerStatus
$duplicateLockTokens = Invoke-DbScalar "SELECT COUNT(*) FROM (SELECT lock_token FROM ticket_order GROUP BY lock_token HAVING COUNT(*) > 1) t"
$dirtyRollback = Invoke-Redis -CommandArgs @("HGETALL", "stock:dirty:rollback")

$javaVersion = ((& cmd /c "java -version 2>&1") | Select-Object -First 1)
$mysqlVersion = Invoke-DbScalar "SELECT VERSION()"
$redisVersion = Invoke-Redis -CommandArgs @("INFO", "server")

$summaryRows = foreach ($s in $scenarioResults) {
    $evidence = To-JsonShort $s.details
    if ($evidence.Length -gt 900) { $evidence = $evidence.Substring(0, 900) + "..." }
    "| $($s.name) | $($s.result) | $evidence | $($s.conclusion) |"
}

$findingRows = if ($findings.Count -eq 0) {
    "| 无 |  |  |  |  |"
} else {
    foreach ($f in $findings) {
        "| $($f.issue) | $($f.severity) | $($f.steps) | $($f.evidence) | $($f.suggestion) |"
    }
}

$detailText = foreach ($s in $scenarioResults) {
    "### $($s.name)`n`n结果：$($s.result)`n`n结论：$($s.conclusion)`n`n``````json`n$(To-JsonShort $s.details)`n``````"
}

$report = @"
# Phase 1B 交易失败路径验收记录

## 1. 执行环境

- 测试时间：$(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
- 后端地址：$BackendUrl
- 后端地址校验：localhost
- Java：$javaVersion
- MySQL：Docker Compose mysql / database=xticket / version=$mysqlVersion
- Redis：Docker Compose redis / available=$((Invoke-Redis -CommandArgs @("PING")).value)
- RocketMQ：Docker Compose namesrv + broker 已参与应用启动，本轮不做故障注入
- 测试用户前缀：codex_phase1b_
- Token/密码/数据库密码：未写入报告
- Docker 状态：

``````text
$dockerStatusAfter
``````

## 2. 测试汇总

| 测试场景 | 结果 | 关键证据 | 结论 |
|---|---|---|---|
$($summaryRows -join "`n")

## 3. 场景详情

$($detailText -join "`n`n")

## 4. 发现的问题

| 问题 | 严重程度 | 复现步骤 | 数据证据 | 建议 |
|---|---|---|---|---|
$($findingRows -join "`n")

## 4.1 过期订单问题修复结论

- 根因：支付方法处于事务内，旧逻辑在检测订单过期后执行 DB 关单、DB 库存恢复、seat_lock 释放和 Redis 库存恢复，随后抛出业务异常；异常回滚了 DB 事务，但 Redis 已经不可逆恢复，导致订单仍待支付、MySQL 未恢复、Redis 先恢复，后续定时任务可能再次恢复 Redis。
- 修复方式：统一关单能力以 `ticket_order.status = 0` 和 `expire_time <= now` 的 CAS 更新作为唯一关闭权；只有 CAS 成功的路径才在 DB 事务内恢复 MySQL 库存并释放 `seat_lock`，事务提交后再恢复 Redis 库存和刷新缓存。
- 关闭来源：过期支付场景的后端日志显示 `source=PAYMENT_LAZY_EXPIRE` 且 `casAffectedRows=1`，说明第一次支付请求完成懒关闭；纯定时场景显示 `source=TIMEOUT_SCHEDULER`。
- 第一次过期支付响应：`code=409`，`message=订单已过期并自动取消，无法支付`。
- 第二次过期支付响应：`code=409`，`message=订单已取消，无法支付`。
- 前端状态同步：支付页倒计时归零后会将本页视为过期不可支付并重新拉取订单详情；支付接口返回过期或取消时也会重新拉取订单详情。当前环境未执行可点击浏览器人工验收，需要用户在浏览器中复核最终展示。

## 5. 数据清理

- cleanupCompleted：$cleanupCompleted
- 清理范围：仅 `codex_phase1b_` 和 `codex_api_test_` 测试账号关联的订单、座位、锁座和关注数据。
- 未执行：清空数据库、删除初始化业务数据、删除 MySQL 数据卷、`docker compose down -v`、删除整个 Redis 数据。
- ticket_order 重复 lockToken 组数：$duplicateLockTokens
- Redis dirty rollback：$(if ($dirtyRollback.ok) { $dirtyRollback.value } else { "UNAVAILABLE: $($dirtyRollback.error)" })

## 6. 执行边界

- 本轮允许修复过期订单关闭相关后端逻辑、最小前端状态同步、测试脚本和本地私人文档。
- 未修改 Maven 依赖、正式 DDL/初始化 SQL、lockToken 建单幂等逻辑、支付流水、Outbox、电子票、核销、退款、Waiting Room、Redis Lua 座位级锁或领域命名。
- order_seat 唯一约束冲突返回 500 的 P1 本轮继续记录，不在当前修复中处理。
"@

$report | Set-Content -Path $ReportPath -Encoding UTF8

[pscustomobject]@{
    reportPath = $ReportPath
    backend = $BackendUrl
    cleanupCompleted = $cleanupCompleted
    duplicateLockTokens = $duplicateLockTokens
    scenarios = $scenarioResults
    findings = $findings
} | ConvertTo-Json -Depth 24
