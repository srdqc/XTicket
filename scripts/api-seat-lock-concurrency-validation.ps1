param(
    [string]$BackendUrl = "http://localhost",
    [int]$Rounds = 50,
    [string]$Mode = "after"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$ReportPath = Join-Path $Root "docs/log/03-seat-lock-concurrency-validation.md"
$Timestamp = Get-Date -Format "yyyyMMddHHmmss"
$UserPrefix = "codex_seat_lock_${Timestamp}"
$Password = "CodexSeatLock_123456"

function Assert-LocalBackend {
    param([string]$Url)
    $uri = [Uri]$Url
    if (@("localhost", "127.0.0.1", "::1") -notcontains $uri.Host) {
        throw "Refusing to call non-local backend: $Url"
    }
}

function Mask-Value {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return "" }
    if ($Value.Length -le 12) { return $Value.Substring(0, [Math]::Min(4, $Value.Length)) + "***" }
    return $Value.Substring(0, 6) + "..." + $Value.Substring($Value.Length - 4)
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
    $headers = $lines[0] -split "`t"
    $rows = @()
    for ($i = 1; $i -lt $lines.Count; $i++) {
        if ([string]::IsNullOrWhiteSpace($lines[$i])) { continue }
        $values = $lines[$i] -split "`t"
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
    return Convert-DbValue (($lines[0] -split "`t")[0])
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
        return (($resp | Out-String).Trim())
    } catch {
        return "UNAVAILABLE: $($_.Exception.Message)"
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
            error = $null
        }
    } catch {
        return [pscustomobject]@{ httpStatus = $null; code = $null; message = $_.Exception.Message; data = $null; error = $_.Exception.Message }
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
        try {
            $text = $entry.task.Result.Content.ReadAsStringAsync().Result
            $parsed = if ([string]::IsNullOrWhiteSpace($text)) { $null } else { $text | ConvertFrom-Json }
            $results += [pscustomobject]@{
                label = $entry.label
                elapsedMs = $entry.stopwatch.ElapsedMilliseconds
                httpStatus = [int]$entry.task.Result.StatusCode
                code = if ($parsed) { $parsed.code } else { $null }
                message = if ($parsed) { $parsed.message } else { "" }
                lockToken = if ($parsed -and $parsed.data -and $parsed.data.PSObject.Properties["lockToken"]) { [string]$parsed.data.lockToken } else { "" }
            }
        } catch {
            $results += [pscustomobject]@{ label = $entry.label; elapsedMs = $entry.stopwatch.ElapsedMilliseconds; httpStatus = $null; code = $null; message = $_.Exception.Message; lockToken = "" }
        } finally {
            $entry.request.Dispose()
            $entry.client.Dispose()
        }
    }
    return @($results)
}

function New-TestUser {
    param([string]$Suffix)
    $account = "${UserPrefix}_$Suffix"
    $register = Invoke-Api -Method POST -Path "/api/auth/register" -Body @{
        account = $account
        password = $Password
        userNick = "Seat Lock $Suffix"
        inviteCode = "lpf"
    }
    $login = Invoke-Api -Method POST -Path "/api/auth/login" -Body @{ account = $account; password = $Password }
    if ($login.code -ne 200) { throw "Login failed for ${account}: $($login.message)" }
    return [pscustomobject]@{
        account = $account
        id = [long]$login.data.id
        tokenMasked = Mask-Value ([string]$login.data.token)
        headers = @{ Authorization = "Bearer $($login.data.token)" }
        registerCode = $register.code
    }
}

function Select-Schedule {
    $sql = @"
SELECT ms.id, ms.available_seats, ms.price, COUNT(sl.id) AS lock_rows
FROM activity_session ms
LEFT JOIN seat_lock sl ON sl.schedule_id = ms.id
WHERE ms.status = 1 AND ms.deleted = 0 AND ms.available_seats >= 10
  AND TIMESTAMP(ms.show_date, STR_TO_DATE(ms.show_time, '%H:%i')) > NOW()
GROUP BY ms.id, ms.available_seats, ms.price, ms.show_date, ms.show_time
ORDER BY lock_rows ASC, ms.available_seats DESC, ms.show_date, ms.show_time, ms.id
LIMIT 1
"@
    $rows = @(Invoke-DbRows $sql)
    if ($rows.Count -eq 0) { throw "No salable schedule found in local MySQL" }
    return $rows[0]
}

function Get-FreeSeat {
    param([long]$ScheduleId, [hashtable]$Used)
    $layout = Invoke-Api -Method GET -Path "/api/seat/layout?scheduleId=$ScheduleId"
    if ($layout.code -ne 200) { throw "Seat layout failed: $($layout.message)" }
    $blocked = @{}
    $blockedRows = @(Invoke-DbRows "SELECT CONCAT(row_num, ',', col_num) AS seat_key FROM seat_lock WHERE schedule_id = $ScheduleId UNION SELECT CONCAT(row_num, ',', col_num) AS seat_key FROM order_seat WHERE schedule_id = $ScheduleId")
    foreach ($blockedRow in $blockedRows) {
        if ($blockedRow.seat_key) { $blocked[[string]$blockedRow.seat_key] = $true }
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
    throw "No free seat found through seat layout API"
}

function Lock-Request {
    param($User, [long]$ScheduleId, $Seats, [string]$Label)
    $seatBody = @(@($Seats) | ForEach-Object { @{ row = $_.row; col = $_.col } })
    return [pscustomobject]@{
        label = $Label
        path = "/api/seat/lock"
        headers = $User.headers
        body = @{ scheduleId = $ScheduleId; seats = $seatBody }
    }
}

function Unlock-Schedule {
    param($User, [long]$ScheduleId)
    Invoke-Api -Method POST -Path "/api/seat/unlock?scheduleId=$ScheduleId" -Headers $User.headers | Out-Null
}

function Get-SeatLocks {
    param([long]$ScheduleId, [int]$Row, [int]$Col)
    return @(Invoke-DbRows "SELECT id, schedule_id, row_num, col_num, user_id, lock_token, order_no, status FROM seat_lock WHERE schedule_id = $ScheduleId AND row_num = $Row AND col_num = $Col ORDER BY id")
}

function Classify-SameSeatRound {
    param($Responses, [int]$SeatLockCount, [int]$ActiveTokenCount)
    $codes = @($Responses | ForEach-Object { [int]$_.code })
    $success = @($Responses | Where-Object { [int]$_.code -eq 200 }).Count
    $locked = @($Responses | Where-Object { [int]$_.code -eq 462 }).Count
    $serverError = @($Responses | Where-Object { [int]$_.code -eq 500 -or [int]$_.httpStatus -ge 500 }).Count
    if ($success -eq 1 -and $locked -eq 1 -and $SeatLockCount -eq 1 -and $ActiveTokenCount -eq 1) { return "SUCCESS_462" }
    if ($success -eq 1 -and $serverError -eq 1 -and $SeatLockCount -eq 1 -and $ActiveTokenCount -eq 1) { return "SUCCESS_500" }
    if ($success -eq 2) { return "DOUBLE_SUCCESS" }
    if ($success -eq 0) { return "DOUBLE_FAIL" }
    return "OTHER"
}

function Get-BackendConflictLogSummary {
    try {
        $lines = & docker compose logs --tail=6000 backend 2>&1
        if ($LASTEXITCODE -ne 0) { return "docker compose logs failed" }
        $matched = @($lines | Where-Object {
            $_ -like "*idx_seat_lock_unique*" -or
            $_ -like "*SEAT_LOCK_UNIQUE*" -or
            $_ -like "*DuplicateKeyException*" -or
            $_ -like "*SQLIntegrityConstraintViolationException*"
        } | Select-Object -Last 30)
        return ($matched -join "`n")
    } catch {
        return "log read failed: $($_.Exception.Message)"
    }
}

function Write-Report {
    param($Payload)
    $summaryRows = @()
    foreach ($k in @("SUCCESS_462", "SUCCESS_500", "DOUBLE_SUCCESS", "DOUBLE_FAIL", "OTHER")) {
        $summaryRows += "| $k | $($Payload.summary.PSObject.Properties[$k].Value) |"
    }
    $detailJson = $Payload.rounds | ConvertTo-Json -Depth 18
    $reportLines = @(
        "# seat_lock 并发锁座验收记录",
        "",
        "## 1. 执行环境",
        "",
        "- 测试时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
        "- 模式：$Mode",
        "- 后端地址：$BackendUrl",
        "- 测试账号前缀：$UserPrefix",
        "- Token/密码/数据库密码：未写入报告",
        "- MySQL：$($Payload.mysqlVersion)",
        "- Redis：$($Payload.redisPing)",
        "- 场次：$($Payload.scheduleId)",
        "- 轮数：$($Payload.roundCount)",
        "- 总体结果：$($Payload.overallResult)",
        "",
        "## 2. seat_lock 唯一约束",
        "",
        "- 名称：$($Payload.indexName)",
        "- 字段：$($Payload.indexColumns)",
        "",
        "## 3. 同座并发统计",
        "",
        "| 分类 | 次数 |",
        "|---|---:|",
        ($summaryRows -join "`n"),
        "",
        "## 4. 专项场景",
        "",
        "| 场景 | 结果 | 证据 |",
        "|---|---|---|",
        "| 单用户正常锁座 | $($Payload.normal.result) | $($Payload.normal.evidence) |",
        "| 同场次不同座位并发锁定 | $($Payload.differentSeats.result) | $($Payload.differentSeats.evidence) |",
        "| 批量锁座包含冲突座位 | $($Payload.batchConflict.result) | $($Payload.batchConflict.evidence) |",
        "| 锁座释放后重新锁定 | $($Payload.relock.result) | $($Payload.relock.evidence) |",
        "",
        "## 5. 同座并发明细",
        "",
        '```json',
        $detailJson,
        '```',
        "",
        "## 6. 后端冲突日志摘要",
        "",
        '```text',
        $Payload.backendLogs,
        '```'
    )
    $report = $reportLines -join "`n"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
    $report | Set-Content -Path $ReportPath -Encoding UTF8
}

Assert-LocalBackend $BackendUrl
$health = Invoke-Api -Method GET -Path "/api/seat/layout?scheduleId=1"
if ($health.code -ne 200) { throw "Backend health check failed on localhost: $($health.message)" }

$mysqlVersion = Invoke-DbScalar "SELECT VERSION()"
$redisPing = Invoke-Redis -CommandArgs @("PING")
$indexRows = @(Invoke-DbRows "SHOW INDEX FROM seat_lock WHERE Key_name = 'idx_seat_lock_unique'")
$indexName = if ($indexRows.Count -gt 0) { [string]$indexRows[0].Key_name } else { "NOT_FOUND" }
$indexColumns = (@($indexRows | Sort-Object Seq_in_index | ForEach-Object { $_.Column_name }) -join ",")
$schedule = Select-Schedule
$scheduleId = [long]$schedule.id
$usedSeats = @{}
$roundDetails = New-Object System.Collections.Generic.List[object]
$summary = [ordered]@{
    SUCCESS_462 = 0
    SUCCESS_500 = 0
    DOUBLE_SUCCESS = 0
    DOUBLE_FAIL = 0
    OTHER = 0
}

$userA = New-TestUser "a"
$userB = New-TestUser "b"

try {
    for ($i = 1; $i -le $Rounds; $i++) {
        $seat = Get-FreeSeat $scheduleId $usedSeats
        $responses = @(Invoke-ConcurrentPost @(
            (Lock-Request $userA $scheduleId @($seat) "userA"),
            (Lock-Request $userB $scheduleId @($seat) "userB")
        ))
        $locks = @(Get-SeatLocks $scheduleId $seat.row $seat.col)
        $activeTokenCount = @($locks | Where-Object { [int]$_.status -eq 1 -and $_.lock_token } | Select-Object -ExpandProperty lock_token -Unique).Count
        $class = Classify-SameSeatRound $responses @($locks).Count $activeTokenCount
        $summary[$class]++
        $roundDetails.Add([pscustomobject]@{
            round = $i
            seat = $seat.label
            classification = $class
            responses = @($responses | ForEach-Object {
                [pscustomobject]@{
                    label = $_.label
                    httpStatus = $_.httpStatus
                    code = $_.code
                    message = $_.message
                    lockToken = Mask-Value $_.lockToken
                    elapsedMs = $_.elapsedMs
                }
            })
            seatLockCount = @($locks).Count
            activeTokenCount = $activeTokenCount
            lockUsers = (@($locks | ForEach-Object { $_.user_id }) -join ",")
        }) | Out-Null
        Unlock-Schedule $userA $scheduleId
        Unlock-Schedule $userB $scheduleId
    }

    $normalSeat = Get-FreeSeat $scheduleId $usedSeats
    $normal = Invoke-Api -Method POST -Path "/api/seat/lock" -Headers $userA.headers -Body @{ scheduleId = $scheduleId; seats = @(@{ row = $normalSeat.row; col = $normalSeat.col }) }
    $normalLocks = @(Get-SeatLocks $scheduleId $normalSeat.row $normalSeat.col)
    $normalResult = if ($normal.code -eq 200 -and @($normalLocks).Count -eq 1 -and [string]$normal.data.lockToken) { "PASS" } else { "FAIL" }
    Unlock-Schedule $userA $scheduleId

    $seat1 = Get-FreeSeat $scheduleId $usedSeats
    $seat2 = Get-FreeSeat $scheduleId $usedSeats
    $differentResponses = @(Invoke-ConcurrentPost @(
        (Lock-Request $userA $scheduleId @($seat1) "userA"),
        (Lock-Request $userB $scheduleId @($seat2) "userB")
    ))
    $differentLocks = @(Get-SeatLocks $scheduleId $seat1.row $seat1.col) + @(Get-SeatLocks $scheduleId $seat2.row $seat2.col)
    $differentSuccess = @($differentResponses | Where-Object { [int]$_.code -eq 200 }).Count
    $differentTokenCount = @($differentResponses | Where-Object { $_.lockToken } | Select-Object -ExpandProperty lockToken -Unique).Count
    $differentResult = if ($differentSuccess -eq 2 -and $differentTokenCount -eq 2 -and @($differentLocks).Count -eq 2) { "PASS" } else { "FAIL" }
    Unlock-Schedule $userA $scheduleId
    Unlock-Schedule $userB $scheduleId

    $conflictSeat = Get-FreeSeat $scheduleId $usedSeats
    $freeInBatch = Get-FreeSeat $scheduleId $usedSeats
    $preLock = Invoke-Api -Method POST -Path "/api/seat/lock" -Headers $userA.headers -Body @{ scheduleId = $scheduleId; seats = @(@{ row = $conflictSeat.row; col = $conflictSeat.col }) }
    $batch = Invoke-Api -Method POST -Path "/api/seat/lock" -Headers $userB.headers -Body @{ scheduleId = $scheduleId; seats = @(@{ row = $freeInBatch.row; col = $freeInBatch.col }, @{ row = $conflictSeat.row; col = $conflictSeat.col }) }
    $freeBatchLocks = @(Get-SeatLocks $scheduleId $freeInBatch.row $freeInBatch.col)
    $conflictLocks = @(Get-SeatLocks $scheduleId $conflictSeat.row $conflictSeat.col)
    $batchResult = if ($preLock.code -eq 200 -and $batch.code -eq 462 -and @($freeBatchLocks).Count -eq 0 -and @($conflictLocks).Count -eq 1) { "PASS" } else { "FAIL" }
    Unlock-Schedule $userA $scheduleId
    Unlock-Schedule $userB $scheduleId

    $relockSeat = Get-FreeSeat $scheduleId $usedSeats
    $firstLock = Invoke-Api -Method POST -Path "/api/seat/lock" -Headers $userA.headers -Body @{ scheduleId = $scheduleId; seats = @(@{ row = $relockSeat.row; col = $relockSeat.col }) }
    Unlock-Schedule $userA $scheduleId
    $secondLock = Invoke-Api -Method POST -Path "/api/seat/lock" -Headers $userB.headers -Body @{ scheduleId = $scheduleId; seats = @(@{ row = $relockSeat.row; col = $relockSeat.col }) }
    $relockRows = @(Get-SeatLocks $scheduleId $relockSeat.row $relockSeat.col)
    $relockResult = if ($firstLock.code -eq 200 -and $secondLock.code -eq 200 -and @($relockRows).Count -eq 1 -and [long]$relockRows[0].user_id -eq $userB.id) { "PASS" } else { "FAIL" }
    Unlock-Schedule $userB $scheduleId

    $normalToken = ""
    if ($normal.data -and $normal.data.PSObject.Properties["lockToken"]) {
        $normalToken = Mask-Value ([string]$normal.data.lockToken)
    }
    $summaryObject = [pscustomobject]@{
        SUCCESS_462 = [int]$summary["SUCCESS_462"]
        SUCCESS_500 = [int]$summary["SUCCESS_500"]
        DOUBLE_SUCCESS = [int]$summary["DOUBLE_SUCCESS"]
        DOUBLE_FAIL = [int]$summary["DOUBLE_FAIL"]
        OTHER = [int]$summary["OTHER"]
    }
    $sameSeatAfterOk = (
        [int]$summary["SUCCESS_462"] -eq $Rounds -and
        [int]$summary["SUCCESS_500"] -eq 0 -and
        [int]$summary["DOUBLE_SUCCESS"] -eq 0 -and
        [int]$summary["DOUBLE_FAIL"] -eq 0 -and
        [int]$summary["OTHER"] -eq 0
    )
    $specialScenariosOk = ($normalResult -eq "PASS" -and $differentResult -eq "PASS" -and $batchResult -eq "PASS" -and $relockResult -eq "PASS")
    $overallResult = if ($sameSeatAfterOk -and $specialScenariosOk) { "PASS" } else { "FAIL" }
    $payload = [pscustomobject]@{
        mode = $Mode
        scheduleId = $scheduleId
        roundCount = $Rounds
        overallResult = $overallResult
        mysqlVersion = $mysqlVersion
        redisPing = $redisPing
        indexName = $indexName
        indexColumns = $indexColumns
        summary = $summaryObject
        rounds = @($roundDetails.ToArray())
        normal = [pscustomobject]@{ result = $normalResult; evidence = "code=$($normal.code), lockRows=$(@($normalLocks).Count), lockToken=$normalToken" }
        differentSeats = [pscustomobject]@{ result = $differentResult; evidence = "codes=$(($differentResponses | ForEach-Object { $_.code }) -join '/'), lockRows=$(@($differentLocks).Count), tokens=$differentTokenCount" }
        batchConflict = [pscustomobject]@{ result = $batchResult; evidence = "preLock=$($preLock.code), batch=$($batch.code)/$($batch.message), freePartialRows=$(@($freeBatchLocks).Count), conflictRows=$(@($conflictLocks).Count)" }
        relock = [pscustomobject]@{ result = $relockResult; evidence = "first=$($firstLock.code), second=$($secondLock.code), finalRows=$(@($relockRows).Count)" }
        backendLogs = Get-BackendConflictLogSummary
    }
    Write-Report $payload
    if ($Mode -eq "after" -and $overallResult -ne "PASS") {
        throw "Seat lock concurrency validation failed in after mode: $($summaryObject | ConvertTo-Json -Compress)"
    }
    $payload | ConvertTo-Json -Depth 20
} finally {
    try {
        Unlock-Schedule $userA $scheduleId
        Unlock-Schedule $userB $scheduleId
        Invoke-DbExec "DELETE FROM seat_lock WHERE user_id IN (SELECT id FROM sys_user WHERE account LIKE '$UserPrefix%') AND order_no IS NULL"
        Invoke-DbExec "DELETE FROM sys_user WHERE account LIKE '$UserPrefix%'"
    } catch {
        Write-Warning "Cleanup failed: $($_.Exception.Message)"
    }
}
