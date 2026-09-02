param(
    [string]$BackendUrl = "http://localhost",
    [int]$TimeoutPollSeconds = 130
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$ReportPath = Join-Path $Root "docs/log/03-Phase2A-订单快照验收记录.md"
$Timestamp = Get-Date -Format "yyyyMMddHHmmss"
$UserPrefix = "codex_phase2a_${Timestamp}"
$Password = "CodexPhase2A_123456"
$Results = New-Object System.Collections.Generic.List[object]
$Findings = New-Object System.Collections.Generic.List[object]

function Assert-LocalBackend {
    param([string]$Url)
    $uri = [Uri]$Url
    if (@("localhost", "127.0.0.1", "::1") -notcontains $uri.Host) {
        throw "Refusing to call non-local backend: $Url"
    }
}

function Resolve-Docker {
    $cmd = Get-Command docker -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        "$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin\docker.exe",
        "C:\Program Files\Docker\Docker\resources\bin\docker.exe"
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }
    throw "docker executable not found"
}

$DockerExe = Resolve-Docker

function Escape-Sql {
    param([string]$Value)
    if ($null -eq $Value) { return "" }
    return $Value.Replace("\", "\\").Replace("'", "''")
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
    $mysql = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw'
    if ($NoHeader) { $mysql += " --skip-column-names" }
    $lines = $Sql | & $script:DockerExe @args $mysql 2>&1
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
        $resp = & $script:DockerExe @args 2>&1
        if ($LASTEXITCODE -ne 0) { throw (($resp | Out-String).Trim()) }
        $text = ($resp | Out-String).Trim()
        if ($text -eq "(nil)") { $text = $null }
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

function To-JsonShort {
    param($Value)
    if ($null -eq $Value) { return "" }
    return ($Value | ConvertTo-Json -Depth 20 -Compress)
}

function Get-ResponseLockToken {
    param($Response)
    if ($null -eq $Response -or $null -eq $Response.data) { return "" }
    $prop = $Response.data.PSObject.Properties["lockToken"]
    if ($null -eq $prop -or $null -eq $prop.Value) { return "" }
    return [string]$prop.Value
}

function New-TestUser {
    param([string]$Suffix)
    $account = "${UserPrefix}_$Suffix"
    Invoke-Api -Method POST -Path "/api/auth/register" -Body @{
        account = $account
        password = $Password
        userNick = "Phase2A $Suffix"
        inviteCode = "lpf"
    } | Out-Null
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
    param([int]$MinStock = 20)
    $sql = @"
SELECT ms.id AS schedule_id, ms.movie_id, m.nm AS movie_name, ms.cinema_id, c.nm AS cinema_name,
       ms.hall_name, ms.show_date, ms.show_time, ms.price AS unit_price,
       ms.available_seats, ms.version, COUNT(sl.id) AS lock_rows
FROM movie_schedule ms
JOIN movie m ON ms.movie_id = m.id AND m.deleted = 0
JOIN cinema c ON ms.cinema_id = c.id AND c.deleted = 0
LEFT JOIN seat_lock sl ON sl.schedule_id = ms.id
WHERE ms.status = 1 AND ms.deleted = 0 AND ms.available_seats >= $MinStock
GROUP BY ms.id, ms.movie_id, m.nm, ms.cinema_id, c.nm, ms.hall_name, ms.show_date, ms.show_time,
         ms.price, ms.available_seats, ms.version
ORDER BY lock_rows ASC, ms.available_seats DESC, ms.show_date, ms.show_time, ms.id
LIMIT 1
"@
    $rows = @(Invoke-DbRows $sql)
    if ($rows.Count -eq 0) { throw "No salable schedule found" }
    return $rows[0]
}

function Get-ScheduleSource {
    param([long]$ScheduleId)
    $rows = @(Invoke-DbRows @"
SELECT ms.id AS schedule_id, ms.movie_id, m.nm AS movie_name, ms.cinema_id, c.nm AS cinema_name,
       ms.hall_name, ms.show_date, ms.show_time, ms.price AS unit_price,
       ms.available_seats, ms.version
FROM movie_schedule ms
LEFT JOIN movie m ON ms.movie_id = m.id AND m.deleted = 0
LEFT JOIN cinema c ON ms.cinema_id = c.id AND c.deleted = 0
WHERE ms.id = $ScheduleId AND ms.deleted = 0
LIMIT 1
"@)
    if ($rows.Count -eq 0) { return $null }
    return $rows[0]
}

function Get-OrderSnapshot {
    param([string]$OrderNo)
    $safe = Escape-Sql $OrderNo
    $rows = @(Invoke-DbRows "SELECT id, order_no, user_id, schedule_id, lock_token, movie_name, cinema_name, hall_name, show_time, seat_count, seats_info, unit_price, total_price, status, expire_time, pay_time, cancel_time, create_time, update_time FROM ticket_order WHERE order_no = '$safe' AND deleted = 0 LIMIT 1")
    if ($rows.Count -eq 0) { return $null }
    return $rows[0]
}

function Get-OrderCountByLockToken {
    param([string]$LockToken)
    $safe = Escape-Sql $LockToken
    return [int](Invoke-DbScalar "SELECT COUNT(*) FROM ticket_order WHERE lock_token = '$safe' AND deleted = 0")
}

function Get-Stock {
    param([long]$ScheduleId)
    $db = Get-ScheduleSource $ScheduleId
    $redis = Invoke-Redis -CommandArgs @("GET", "schedule:stock:$ScheduleId")
    return [pscustomobject]@{
        dbAvailableSeats = if ($db) { [int]$db.available_seats } else { $null }
        dbVersion = if ($db) { [int]$db.version } else { $null }
        redisStock = if ($redis.ok) { $redis.value } else { $null }
    }
}

function Get-AvailableSeat {
    param([long]$ScheduleId)
    $layout = Invoke-Api -Method GET -Path "/api/seat/layout?scheduleId=$ScheduleId" -Headers $script:UserA.headers
    if ($layout.code -ne 200) { throw "Seat layout failed: $($layout.message)" }
    $blocked = @{}
    $blockedRows = @(Invoke-DbRows "SELECT CONCAT(row_num, ',', col_num) AS seat_key FROM seat_lock WHERE schedule_id = $ScheduleId UNION SELECT CONCAT(row_num, ',', col_num) AS seat_key FROM order_seat WHERE schedule_id = $ScheduleId")
    foreach ($row in $blockedRows) {
        if ($row.seat_key) { $blocked[[string]$row.seat_key] = $true }
    }
    foreach ($row in $layout.data.seats) {
        foreach ($seat in $row) {
            $key = "$($seat.row),$($seat.col)"
            if (-not $blocked.ContainsKey($key)) {
                return [pscustomobject]@{ row = [int]$seat.row; col = [int]$seat.col; label = "$($seat.row)排$($seat.col)座" }
            }
        }
    }
    throw "No free seat found through seat layout API"
}

function Get-AvailableSeats {
    param([long]$ScheduleId, [int]$Count)
    $seats = @()
    for ($i = 0; $i -lt $Count; $i++) {
        $seat = Get-AvailableSeat $ScheduleId
        $seats += $seat
        Invoke-DbExec "INSERT INTO seat_lock(schedule_id, row_num, col_num, user_id, lock_token, lock_until, status) VALUES ($ScheduleId, $($seat.row), $($seat.col), -1, 'PHASE2A_RESERVE_${Timestamp}_$i', NOW(), 0)"
    }
    Invoke-DbExec "DELETE FROM seat_lock WHERE user_id = -1 AND lock_token LIKE 'PHASE2A_RESERVE_${Timestamp}_%'"
    return @($seats)
}

function Get-SeatsBody {
    param($Seats)
    $items = New-Object System.Collections.Generic.List[object]
    foreach ($seat in @($Seats)) {
        $items.Add(@{ row = $seat.row; col = $seat.col }) | Out-Null
    }
    return ,$items
}

function Get-SeatsInfo {
    param($Seats)
    return (@($Seats) | Sort-Object row, col | ForEach-Object { "$($_.row)排$($_.col)座" }) -join ","
}

function Lock-And-CreateOrder {
    param($User, [long]$ScheduleId, $Seats, [string]$ClientSeatsInfo = "CLIENT_TEXT_SHOULD_NOT_WIN")
    $seatBody = Get-SeatsBody $Seats
    $lock = Invoke-Api -Method POST -Path "/api/seat/lock" -Headers $User.headers -Body @{
        scheduleId = $ScheduleId
        seats = $seatBody
    }
    if ($lock.code -ne 200) { throw "Lock failed: code=$($lock.code), message=$($lock.message)" }
    $lockToken = Get-ResponseLockToken $lock
    $createBody = @{
        scheduleId = $ScheduleId
        lockToken = $lockToken
        seats = $seatBody
        seatCount = @($Seats).Count
        seatsInfo = $ClientSeatsInfo
    }
    $stockBeforeCreate = Get-Stock $ScheduleId
    $create = Invoke-Api -Method POST -Path "/api/order/create" -Headers $User.headers -Body $createBody
    if ($create.code -ne 200) { throw "Create failed: code=$($create.code), message=$($create.message)" }
    $orderNo = [string]$create.data.orderNo
    return [pscustomobject]@{
        lockResponse = $lock
        createResponse = $create
        createBody = $createBody
        lockToken = $lockToken
        lockTokenMasked = Mask-Value $lockToken
        orderNo = $orderNo
        snapshot = Get-OrderSnapshot $orderNo
        stockBeforeCreate = $stockBeforeCreate
        stockAfterCreate = Get-Stock $ScheduleId
    }
}

function Snapshot-Comparable {
    param($Snapshot)
    if ($null -eq $Snapshot) { return $null }
    return [pscustomobject]@{
        movieName = $Snapshot.movie_name
        cinemaName = $Snapshot.cinema_name
        hallName = $Snapshot.hall_name
        showTime = $Snapshot.show_time
        seatsInfo = $Snapshot.seats_info
        seatCount = [int]$Snapshot.seat_count
        unitPrice = [decimal]$Snapshot.unit_price
        totalPrice = [decimal]$Snapshot.total_price
    }
}

function Same-Snapshot {
    param($Left, $Right)
    if ($null -eq $Left -or $null -eq $Right) { return $false }
    return (To-JsonShort (Snapshot-Comparable $Left)) -eq (To-JsonShort (Snapshot-Comparable $Right))
}

function Add-Result {
    param([string]$Name, [string]$Result, [string]$Conclusion, $Evidence)
    $script:Results.Add([pscustomobject]@{
        name = $Name
        result = $Result
        conclusion = $Conclusion
        evidence = $Evidence
    }) | Out-Null
}

function Add-Finding {
    param([string]$Problem, [string]$Severity, [string]$Evidence, [string]$Suggestion)
    $script:Findings.Add([pscustomobject]@{
        problem = $Problem
        severity = $Severity
        evidence = $Evidence
        suggestion = $Suggestion
    }) | Out-Null
}

function Find-OrderInList {
    param($User, [string]$OrderNo)
    $list = Invoke-Api -Method GET -Path "/api/order/list?page=1&size=100" -Headers $User.headers
    if ($list.code -ne 200) { throw "Order list failed: $($list.message)" }
    foreach ($order in @($list.data)) {
        if ([string]$order.orderNo -eq $OrderNo) { return $order }
    }
    return $null
}

function Get-OrderDetail {
    param($User, [string]$OrderNo)
    $detail = Invoke-Api -Method GET -Path "/api/payment/orderDetail?orderNo=$OrderNo" -Headers $User.headers
    if ($detail.code -ne 200) { throw "Order detail failed: $($detail.message)" }
    return $detail.data
}

function Set-BaseNames {
    param($Source, [string]$Suffix)
    $movieName = "Phase2A Movie $Suffix"
    $cinemaName = "Phase2A Cinema $Suffix"
    $hallName = "Phase2A Hall $Suffix"
    Invoke-DbExec "UPDATE movie SET nm = '$(Escape-Sql $movieName)', update_time = CURRENT_TIMESTAMP WHERE id = $($Source.movie_id)"
    Invoke-DbExec "UPDATE cinema SET nm = '$(Escape-Sql $cinemaName)', update_time = CURRENT_TIMESTAMP WHERE id = $($Source.cinema_id)"
    Invoke-DbExec "UPDATE movie_schedule SET hall_name = '$(Escape-Sql $hallName)', update_time = CURRENT_TIMESTAMP WHERE id = $($Source.schedule_id)"
    return [pscustomobject]@{ movieName = $movieName; cinemaName = $cinemaName; hallName = $hallName }
}

function Restore-BaseNames {
    param($Source)
    if ($null -eq $Source) { return }
    Invoke-DbExec "UPDATE movie SET nm = '$(Escape-Sql ([string]$Source.movie_name))', update_time = CURRENT_TIMESTAMP WHERE id = $($Source.movie_id)"
    Invoke-DbExec "UPDATE cinema SET nm = '$(Escape-Sql ([string]$Source.cinema_name))', update_time = CURRENT_TIMESTAMP WHERE id = $($Source.cinema_id)"
    Invoke-DbExec "UPDATE movie_schedule SET hall_name = '$(Escape-Sql ([string]$Source.hall_name))', update_time = CURRENT_TIMESTAMP WHERE id = $($Source.schedule_id)"
}

function Test-SnapshotComplete {
    param($Snapshot, $Source, $Seats)
    $expectedSeatsInfo = Get-SeatsInfo $Seats
    $expectedShowTime = "$($Source.show_date) $($Source.show_time)"
    $expectedTotal = [decimal]$Source.unit_price * @($Seats).Count
    return (
        -not [string]::IsNullOrWhiteSpace([string]$Snapshot.movie_name) -and
        -not [string]::IsNullOrWhiteSpace([string]$Snapshot.cinema_name) -and
        [string]$Snapshot.movie_name -eq [string]$Source.movie_name -and
        [string]$Snapshot.cinema_name -eq [string]$Source.cinema_name -and
        [string]$Snapshot.hall_name -eq [string]$Source.hall_name -and
        [string]$Snapshot.show_time -eq $expectedShowTime -and
        [int]$Snapshot.seat_count -eq @($Seats).Count -and
        [string]$Snapshot.seats_info -eq $expectedSeatsInfo -and
        [decimal]$Snapshot.unit_price -eq [decimal]$Source.unit_price -and
        [decimal]$Snapshot.total_price -eq $expectedTotal
    )
}

function Write-Report {
    param($Payload)
    $summaryRows = @($Payload.results | ForEach-Object {
        "| $($_.name) | $($_.result) | $($_.conclusion) | $(To-JsonShort $_.evidence) |"
    })
    if ($summaryRows.Count -eq 0) { $summaryRows = @("| - | - | - | - |") }

    $findingRows = @($Payload.findings | ForEach-Object {
        "| $($_.problem) | $($_.severity) | $($_.evidence) | $($_.suggestion) |"
    })
    if ($findingRows.Count -eq 0) { $findingRows = @("| 无 | - | - | - |") }

    $details = @($Payload.results | ForEach-Object {
        @(
            "### $($_.name)",
            "",
            "- 结果：$($_.result)",
            "- 结论：$($_.conclusion)",
            "",
            "JSON 证据：",
            ($_.evidence | ConvertTo-Json -Depth 20),
            ""
        ) -join "`n"
    })

    $report = @"
# Phase 2A 订单快照验收记录

## 1. 执行环境

- 测试时间：$($Payload.testTime)
- 后端地址：$BackendUrl
- Docker：$($Payload.dockerSummary)
- MySQL：$($Payload.mysqlVersion)
- Redis：$($Payload.redisPing)
- 测试账号：$($Payload.users)
- Token/密码/数据库密码：未写入报告
- 测试场次：$($Payload.scheduleId)

## 2. 修改前审计结论

- ticket_order 已存在 movie_name、cinema_name、hall_name、show_time、seats_info、unit_price、total_price。
- MySQL 与 H2 schema 的订单快照字段一致，本阶段未修改表结构。
- 修改前创建订单显式写入 movieName=null、cinemaName=null，seatsInfo 来源于客户端请求文本。
- 订单列表和订单详情均读取 ticket_order 快照字段，不使用实时基础表覆盖非空快照。
- 修改前旧订单空快照数量：$($Payload.incompleteBefore)

## 3. 测试汇总

| 场景 | 结果 | 结论 | 证据 |
|---|---|---|---|
$($summaryRows -join "`n")

## 4. 场景明细

$($details -join "`n")

## 5. 发现的问题

| 问题 | 严重程度 | 证据 | 建议 |
|---|---|---|---|
$($findingRows -join "`n")

## 6. 边界说明

- 本阶段未实现支付流水、电子票、核销、退款、Outbox、Waiting Room 或活动领域重命名。
- 本阶段未修改锁座算法、Redisson 锁粒度、库存扣减逻辑、支付事务、过期订单关闭逻辑或正式 DDL。
- 基础数据名称修改仅用于专项验证，并已恢复原值。
"@
    $report | Set-Content -Path $ReportPath -Encoding UTF8
}

Assert-LocalBackend $BackendUrl
$dockerPs = & $DockerExe compose ps -a 2>&1
if ($LASTEXITCODE -ne 0) { throw (($dockerPs | Out-String).Trim()) }
$runningServices = @(& $DockerExe compose ps --services --filter "status=running" 2>&1)
foreach ($service in @("backend", "frontend", "nginx", "mysql", "redis", "rocketmq-namesrv", "rocketmq-broker")) {
    if ($runningServices -notcontains $service) { throw "Docker service is not running: $service" }
}

$health = Invoke-Api -Method GET -Path "/api/seat/layout?scheduleId=1"
if ($health.code -ne 200) { throw "Backend health check failed: $($health.message)" }

$mysqlVersion = Invoke-DbScalar "SELECT VERSION()"
$redisPing = (Invoke-Redis -CommandArgs @("PING")).value
$incompleteBefore = Invoke-DbScalar "SELECT COUNT(*) FROM ticket_order WHERE movie_name IS NULL OR cinema_name IS NULL"

$UserA = New-TestUser "a"
$script:UserA = $UserA
$schedule = Select-Schedule 20
$scheduleId = [long]$schedule.schedule_id

try {
    $sourceA = Get-ScheduleSource $scheduleId
    $seatsA = Get-AvailableSeats $scheduleId 2
    $orderA = Lock-And-CreateOrder -User $UserA -ScheduleId $scheduleId -Seats $seatsA
    $completeA = Test-SnapshotComplete $orderA.snapshot $sourceA $seatsA
    if (-not $completeA) { Add-Finding "新订单快照不完整或与基础数据不一致" "P0" $orderA.orderNo "检查 OrderService 快照组装" }
    Add-Result "新订单快照完整" $(if ($completeA) { "PASS" } else { "FAIL" }) "movie/cinema/hall/showTime/seats/price 快照来自后端可信数据" ([ordered]@{
        orderNo = $orderA.orderNo
        lockToken = $orderA.lockTokenMasked
        source = $sourceA
        snapshot = $orderA.snapshot
        expectedSeatsInfo = Get-SeatsInfo $seatsA
        clientSeatsInfoIgnored = ([string]$orderA.snapshot.seats_info -ne "CLIENT_TEXT_SHOULD_NOT_WIN")
    })
} catch {
    Add-Result "新订单快照完整" "BLOCKED" $_.Exception.Message @{}
    Add-Finding "新订单快照完整场景阻塞" "P2" $_.Exception.Message "检查本地测试数据和服务状态"
}

try {
    $sourceB = Get-ScheduleSource $scheduleId
    $seatsB = Get-AvailableSeats $scheduleId 1
    $orderB = Lock-And-CreateOrder -User $UserA -ScheduleId $scheduleId -Seats $seatsB
    $beforeSnapshot = Get-OrderSnapshot $orderB.orderNo
    $changedNames = $null
    try {
        $changedNames = Set-BaseNames $sourceB "B_$Timestamp"
        $listOrder = Find-OrderInList $UserA $orderB.orderNo
        $detailOrder = Get-OrderDetail $UserA $orderB.orderNo
        $afterSnapshot = Get-OrderSnapshot $orderB.orderNo
        $pass = (
            (Same-Snapshot $beforeSnapshot $afterSnapshot) -and
            [string]$listOrder.movieName -eq [string]$beforeSnapshot.movie_name -and
            [string]$listOrder.cinemaName -eq [string]$beforeSnapshot.cinema_name -and
            [string]$listOrder.hallName -eq [string]$beforeSnapshot.hall_name -and
            [string]$detailOrder.movieName -eq [string]$beforeSnapshot.movie_name -and
            [string]$detailOrder.cinemaName -eq [string]$beforeSnapshot.cinema_name -and
            [string]$detailOrder.hallName -eq [string]$beforeSnapshot.hall_name
        )
        if (-not $pass) { Add-Finding "基础数据修改后订单展示被实时基础表覆盖" "P0" $orderB.orderNo "订单列表/详情应优先使用 ticket_order 快照" }
        Add-Result "基础数据修改不影响历史订单" $(if ($pass) { "PASS" } else { "FAIL" }) "订单列表、详情和数据库快照均保持下单时名称" ([ordered]@{
            orderNo = $orderB.orderNo
            changedNames = $changedNames
            dbBefore = $beforeSnapshot
            dbAfter = $afterSnapshot
            listOrder = $listOrder
            detailOrder = $detailOrder
        })
    } finally {
        Restore-BaseNames $sourceB
    }
} catch {
    Add-Result "基础数据修改不影响历史订单" "BLOCKED" $_.Exception.Message @{}
    Add-Finding "基础数据修改场景阻塞" "P2" $_.Exception.Message "检查本地测试数据和接口响应"
}

try {
    $sourceC = Get-ScheduleSource $scheduleId
    $seatsC = Get-AvailableSeats $scheduleId 1
    $orderC = Lock-And-CreateOrder -User $UserA -ScheduleId $scheduleId -Seats $seatsC
    $beforeSnapshot = Get-OrderSnapshot $orderC.orderNo
    $stockBeforeRetry = Get-Stock $scheduleId
    try {
        Set-BaseNames $sourceC "C_$Timestamp" | Out-Null
        $retry = Invoke-Api -Method POST -Path "/api/order/create" -Headers $UserA.headers -Body $orderC.createBody
        $afterSnapshot = Get-OrderSnapshot $orderC.orderNo
        $stockAfterRetry = Get-Stock $scheduleId
        $orderCount = Get-OrderCountByLockToken $orderC.lockToken
        $pass = (
            $retry.code -eq 200 -and
            [string]$retry.data.orderNo -eq [string]$orderC.orderNo -and
            $orderCount -eq 1 -and
            (Same-Snapshot $beforeSnapshot $afterSnapshot) -and
            [int]$stockBeforeRetry.dbAvailableSeats -eq [int]$stockAfterRetry.dbAvailableSeats -and
            [string]$stockBeforeRetry.redisStock -eq [string]$stockAfterRetry.redisStock
        )
        if (-not $pass) { Add-Finding "lockToken 幂等重试重写快照或重复扣库存" "P0" $orderC.orderNo "幂等命中必须直接返回已有订单" }
        Add-Result "lockToken 幂等不重写快照" $(if ($pass) { "PASS" } else { "FAIL" }) "相同 lockToken 返回原订单，快照和库存均不变化" ([ordered]@{
            orderNo = $orderC.orderNo
            retryResponse = @{ code = $retry.code; message = $retry.message; orderNo = $retry.data.orderNo }
            orderCount = $orderCount
            dbBefore = $beforeSnapshot
            dbAfter = $afterSnapshot
            stockBeforeRetry = $stockBeforeRetry
            stockAfterRetry = $stockAfterRetry
        })
    } finally {
        Restore-BaseNames $sourceC
    }
} catch {
    Add-Result "lockToken 幂等不重写快照" "BLOCKED" $_.Exception.Message @{}
    Add-Finding "lockToken 幂等快照场景阻塞" "P2" $_.Exception.Message "检查订单幂等和测试数据"
}

try {
    $sourceD1 = Get-ScheduleSource $scheduleId
    $payOrder = Lock-And-CreateOrder -User $UserA -ScheduleId $scheduleId -Seats (Get-AvailableSeats $scheduleId 1)
    $payBefore = Get-OrderSnapshot $payOrder.orderNo
    $payResp = Invoke-Api -Method POST -Path "/api/payment/pay?orderNo=$($payOrder.orderNo)" -Headers $UserA.headers
    $payAfter = Get-OrderSnapshot $payOrder.orderNo

    $cancelOrder = Lock-And-CreateOrder -User $UserA -ScheduleId $scheduleId -Seats (Get-AvailableSeats $scheduleId 1)
    $cancelBefore = Get-OrderSnapshot $cancelOrder.orderNo
    $cancelResp = Invoke-Api -Method POST -Path "/api/order/cancel/$($cancelOrder.orderNo)" -Headers $UserA.headers
    $cancelAfter = Get-OrderSnapshot $cancelOrder.orderNo

    $timeoutOrder = Lock-And-CreateOrder -User $UserA -ScheduleId $scheduleId -Seats (Get-AvailableSeats $scheduleId 1)
    $timeoutBefore = Get-OrderSnapshot $timeoutOrder.orderNo
    Invoke-DbExec "UPDATE ticket_order SET expire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE), update_time = NOW() WHERE order_no = '$(Escape-Sql $timeoutOrder.orderNo)' AND status = 0"
    $closedAt = $null
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutPollSeconds) {
        Start-Sleep -Seconds 5
        $current = Get-OrderSnapshot $timeoutOrder.orderNo
        if ($current -and [int]$current.status -eq 2) {
            $closedAt = Get-Date
            break
        }
    }
    $stopwatch.Stop()
    $timeoutAfter = Get-OrderSnapshot $timeoutOrder.orderNo

    $pass = (
        $payResp.code -eq 200 -and [int]$payAfter.status -eq 1 -and (Same-Snapshot $payBefore $payAfter) -and
        $cancelResp.code -eq 200 -and [int]$cancelAfter.status -eq 2 -and (Same-Snapshot $cancelBefore $cancelAfter) -and
        $closedAt -ne $null -and [int]$timeoutAfter.status -eq 2 -and (Same-Snapshot $timeoutBefore $timeoutAfter)
    )
    if (-not $pass) { Add-Finding "支付、取消或超时关闭修改了订单快照" "P0" "pay=$($payOrder.orderNo), cancel=$($cancelOrder.orderNo), timeout=$($timeoutOrder.orderNo)" "状态变更不能覆盖历史快照字段" }
    Add-Result "支付取消超时不修改快照" $(if ($pass) { "PASS" } else { "FAIL" }) "订单状态变化后核心快照字段保持不变" ([ordered]@{
        pay = @{ orderNo = $payOrder.orderNo; response = @{ code = $payResp.code; message = $payResp.message }; before = $payBefore; after = $payAfter }
        cancel = @{ orderNo = $cancelOrder.orderNo; response = @{ code = $cancelResp.code; message = $cancelResp.message }; before = $cancelBefore; after = $cancelAfter }
        timeout = @{ orderNo = $timeoutOrder.orderNo; elapsedSeconds = [math]::Round($stopwatch.Elapsed.TotalSeconds, 1); closedAt = if ($closedAt) { $closedAt.ToString("yyyy-MM-dd HH:mm:ss") } else { $null }; before = $timeoutBefore; after = $timeoutAfter }
        sourceAtStart = $sourceD1
    })
} catch {
    Add-Result "支付取消超时不修改快照" "BLOCKED" $_.Exception.Message @{}
    Add-Finding "状态变更快照稳定性场景阻塞" "P2" $_.Exception.Message "检查支付、取消、定时关单链路"
}

try {
    $stockBeforeMissing = Get-Stock $scheduleId
    $ordersBeforeMissing = Invoke-DbScalar "SELECT COUNT(*) FROM ticket_order"
    $missing = Invoke-Api -Method POST -Path "/api/order/create" -Headers $UserA.headers -Body @{
        scheduleId = 999999999
        seats = Get-SeatsBody @([pscustomobject]@{ row = 1; col = 1 })
        seatCount = 1
        seatsInfo = "1排1座"
    }
    $ordersAfterMissing = Invoke-DbScalar "SELECT COUNT(*) FROM ticket_order"
    $stockAfterMissing = Get-Stock $scheduleId
    $pass = (
        $missing.code -ne 200 -and
        [int]$ordersBeforeMissing -eq [int]$ordersAfterMissing -and
        [int]$stockBeforeMissing.dbAvailableSeats -eq [int]$stockAfterMissing.dbAvailableSeats -and
        [string]$stockBeforeMissing.redisStock -eq [string]$stockAfterMissing.redisStock
    )
    Add-Result "来源信息异常" $(if ($pass) { "PASS" } else { "FAIL" }) "不存在场次在库存预扣前失败，不生成订单、不影响已选场次库存" ([ordered]@{
        response = @{ code = $missing.code; message = $missing.message }
        ordersBefore = $ordersBeforeMissing
        ordersAfter = $ordersAfterMissing
        stockBefore = $stockBeforeMissing
        stockAfter = $stockAfterMissing
        incompleteAssociation = "未删除正式基础数据；关联缺失以代码审查验证 loadOrderSnapshotSource 在 preDeduct 前执行"
    })
} catch {
    Add-Result "来源信息异常" "BLOCKED" $_.Exception.Message @{}
    Add-Finding "来源信息异常场景阻塞" "P2" $_.Exception.Message "检查异常路径测试"
}

$userSummary = ([string]$UserA.account) + " / token=" + ([string]$UserA.tokenMasked)
$resultArray = @($Results.ToArray())
$findingArray = @($Findings.ToArray())

$payload = [pscustomobject]@{
    testTime = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
    dockerSummary = (($dockerPs | Select-Object -First 8) -join "; ")
    mysqlVersion = $mysqlVersion
    redisPing = $redisPing
    users = $userSummary
    scheduleId = $scheduleId
    incompleteBefore = $incompleteBefore
    results = $resultArray
    findings = $findingArray
}

Write-Report $payload

$overall = if (@($Results | Where-Object { $_.result -in @("FAIL", "BLOCKED") }).Count -eq 0) { "PASS" } else { "FAIL" }
Write-Output "overall=$overall"
Write-Output "reportPath=$ReportPath"
foreach ($item in $resultArray) {
    Write-Output "$($item.name)=$($item.result)"
}
Write-Output "findings=$($findingArray.Count)"
if ($overall -ne "PASS") {
    throw "Order snapshot validation failed"
}
