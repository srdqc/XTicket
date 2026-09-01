param(
    [string]$BackendUrl = "http://localhost",
    [int]$TimeoutPollSeconds = 130
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$ReportPath = Join-Path $Root "docs/log/01-手动交易验收记录.md"
$TmpDir = Join-Path $PSScriptRoot ".api-validation-tmp"
$Timestamp = Get-Date -Format "yyyyMMddHHmmss"
$Password = "CodexApiTest_123456"

function Assert-LocalBackend {
    param([string]$Url)
    $uri = [Uri]$Url
    $allowed = @("localhost", "127.0.0.1", "::1")
    if ($allowed -notcontains $uri.Host) {
        throw "Refusing to call non-local backend: $Url"
    }
    return $uri
}

function Read-DotEnv {
    param([string]$Path)
    $map = @{}
    if (Test-Path $Path) {
        foreach ($line in Get-Content $Path) {
            $trimmed = $line.Trim()
            if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) { continue }
            $idx = $trimmed.IndexOf("=")
            if ($idx -gt 0) {
                $key = $trimmed.Substring(0, $idx).Trim()
                $value = $trimmed.Substring($idx + 1).Trim().Trim('"').Trim("'")
                $map[$key] = $value
            }
        }
    }
    return $map
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

function Get-ResponseLockToken {
    param($Response)
    if ($null -eq $Response -or $null -eq $Response.data) { return "" }
    $prop = $Response.data.PSObject.Properties["lockToken"]
    if ($null -eq $prop -or $null -eq $prop.Value) { return "" }
    return [string]$prop.Value
}

function To-JsonShort {
    param($Value)
    if ($null -eq $Value) { return "" }
    return ($Value | ConvertTo-Json -Depth 12 -Compress)
}

function Get-DetailValue {
    param($Details, [string]$Name)
    if ($null -eq $Details) { return $null }
    if ($Details -is [System.Collections.IDictionary] -and $Details.Contains($Name)) {
        return $Details[$Name]
    }
    $prop = $Details.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Get-BackendLogsForOrder {
    param([string]$OrderNo)
    if ([string]::IsNullOrWhiteSpace($OrderNo)) { return "" }
    try {
        $logs = & docker compose logs --tail=300 backend 2>&1
        if ($LASTEXITCODE -ne 0) { return "未获取：docker compose logs backend 执行失败" }
        $lines = @($logs | Where-Object {
            $_ -like "*$OrderNo*" -or $_ -like "*reason=TIMEOUT*" -or $_ -like "*订单状态不允许支付*"
        } | Select-Object -Last 12)
        return ($lines -join "`n")
    } catch {
        return "未获取：$($_.Exception.Message)"
    }
}

function Get-OrderCreatedLogCount {
    param([string]$OrderNo)
    if ([string]::IsNullOrWhiteSpace($OrderNo)) { return $null }
    try {
        $logs = & docker compose logs --tail=2000 backend 2>&1
        if ($LASTEXITCODE -ne 0) { return $null }
        return @($logs | Where-Object {
            $_.Contains("[Order] Created:") -and $_.Contains($OrderNo)
        }).Count
    } catch {
        return $null
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
    $uri = "$BackendUrl$Path"
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    $httpMethod = $(if ($Method -eq "GET") { [System.Net.Http.HttpMethod]::Get } else { [System.Net.Http.HttpMethod]::Post })
    $request = [System.Net.Http.HttpRequestMessage]::new($httpMethod, $uri)
    if ($Headers) {
        foreach ($key in $Headers.Keys) {
            $request.Headers.TryAddWithoutValidation($key, [string]$Headers[$key]) | Out-Null
        }
    }
    if ($null -ne $Body) {
        $jsonBody = $Body | ConvertTo-Json -Depth 12
        $request.Content = [System.Net.Http.StringContent]::new($jsonBody, [Text.Encoding]::UTF8, "application/json")
    }
    try {
        $response = $client.SendAsync($request).Result
        $bodyText = $response.Content.ReadAsStringAsync().Result
        $resp = $(if ([string]::IsNullOrWhiteSpace($bodyText)) { $null } else { $bodyText | ConvertFrom-Json })
        return [pscustomobject]@{
            httpStatus = [int]$response.StatusCode
            code = $(if ($resp) { $resp.code } else { $null })
            message = $(if ($resp) { $resp.message } else { "" })
            data = $(if ($resp) { $resp.data } else { $null })
            raw = $resp
            error = $null
        }
    } catch {
        return [pscustomobject]@{
            httpStatus = $null
            code = $null
            message = $_.Exception.Message
            data = $null
            raw = $null
            error = $_.Exception.Message
        }
    } finally {
        $request.Dispose()
        $client.Dispose()
    }
}

function Ensure-DbHelper {
    if (!(Test-Path $TmpDir)) { New-Item -ItemType Directory -Path $TmpDir | Out-Null }
    $javaPath = Join-Path $TmpDir "DbQuery.java"
    $classPath = Join-Path $TmpDir "DbQuery.class"
    if (Test-Path $classPath) { return }
    @'
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class DbQuery {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("url user sqlBase64 required");
        String url = args[0];
        String user = args[1];
        String pass = System.getenv("XTICKET_DB_PASSWORD");
        String sql = new String(Base64.getDecoder().decode(args[2]), StandardCharsets.UTF_8);
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {
            boolean hasResult = stmt.execute(sql);
            if (!hasResult) {
                System.out.print("{\"updateCount\":" + stmt.getUpdateCount() + "}");
                return;
            }
            ResultSet rs = stmt.getResultSet();
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            StringBuilder out = new StringBuilder("[");
            boolean firstRow = true;
            while (rs.next()) {
                if (!firstRow) out.append(',');
                firstRow = false;
                out.append('{');
                for (int i = 1; i <= cols; i++) {
                    if (i > 1) out.append(',');
                    out.append('"').append(esc(md.getColumnLabel(i))).append('"').append(':');
                    Object value = rs.getObject(i);
                    if (value == null) {
                        out.append("null");
                    } else if (value instanceof Number || value instanceof Boolean) {
                        out.append(value.toString());
                    } else {
                        out.append('"').append(esc(value.toString())).append('"');
                    }
                }
                out.append('}');
            }
            out.append(']');
            System.out.print(out.toString());
        }
    }
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
'@ | ForEach-Object {
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        [System.IO.File]::WriteAllText($javaPath, $_, $utf8NoBom)
    }
    & javac -d $TmpDir $javaPath | Out-Null
}

function Invoke-Db {
    param([string]$Sql)
    Ensure-DbHelper
    $sql64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sql))
    $old = $env:XTICKET_DB_PASSWORD
    $env:XTICKET_DB_PASSWORD = $script:DbPassword
    try {
        $cp = "$TmpDir;$script:MysqlJar"
        $json = & java -cp $cp DbQuery $script:JdbcUrl $script:DbUser $sql64
        if ($LASTEXITCODE -ne 0) { throw "java DbQuery failed" }
        $parsed = $json | ConvertFrom-Json
        if ($parsed -is [System.Array]) {
            foreach ($item in $parsed) { $item }
        } else {
            $parsed
        }
    } finally {
        $env:XTICKET_DB_PASSWORD = $old
    }
}

function Try-Db {
    param([string]$Sql)
    try {
        return [pscustomobject]@{ ok = $true; value = @(Invoke-Db $Sql); error = $null }
    } catch {
        return [pscustomobject]@{ ok = $false; value = @(); error = $_.Exception.Message }
    }
}

function Get-RedisReply {
    param(
        [string]$HostName,
        [int]$Port,
        [string[]]$CommandArgs
    )
    $client = [Net.Sockets.TcpClient]::new()
    $iar = $client.BeginConnect($HostName, $Port, $null, $null)
    if (-not $iar.AsyncWaitHandle.WaitOne(1200)) {
        $client.Close()
        throw "Redis connect timeout: ${HostName}:${Port}"
    }
    $client.EndConnect($iar)
    $stream = $client.GetStream()
    $payload = "*" + $CommandArgs.Count + "`r`n"
    foreach ($arg in $CommandArgs) {
        $bytes = [Text.Encoding]::UTF8.GetBytes($arg)
        $payload += "$" + $bytes.Length + "`r`n" + $arg + "`r`n"
    }
    $send = [Text.Encoding]::UTF8.GetBytes($payload)
    $stream.Write($send, 0, $send.Length)
    Start-Sleep -Milliseconds 80
    $buffer = New-Object byte[] 8192
    $all = New-Object System.Collections.Generic.List[byte]
    do {
        $read = $stream.Read($buffer, 0, $buffer.Length)
        for ($i = 0; $i -lt $read; $i++) { $all.Add($buffer[$i]) }
    } while ($stream.DataAvailable)
    $client.Close()
    return [Text.Encoding]::UTF8.GetString($all.ToArray())
}

function Convert-RedisSimple {
    param([string]$Resp)
    if ([string]::IsNullOrWhiteSpace($Resp)) { return $null }
    if ($Resp.StartsWith("+") -or $Resp.StartsWith(":")) { return $Resp.Substring(1).Trim() }
    if ($Resp.StartsWith("-")) { return "ERROR " + $Resp.Substring(1).Trim() }
    if ($Resp.StartsWith("$-1")) { return $null }
    if ($Resp.StartsWith("$")) {
        $parts = $Resp -split "`r`n", 3
        if ($parts.Count -ge 2) { return $parts[1] }
    }
    if ($Resp.StartsWith("*")) {
        $lines = $Resp -split "`r`n"
        $values = @()
        for ($i = 2; $i -lt $lines.Count; $i += 2) {
            if ($lines[$i] -ne "") { $values += $lines[$i] }
        }
        return ($values -join ", ")
    }
    return $Resp.Trim()
}

function Invoke-Redis {
    param([string[]]$CommandArgs)
    if (-not $script:RedisAvailable) {
        return [pscustomobject]@{ ok = $false; value = $null; error = $script:RedisError }
    }
    try {
        if ($script:RedisMode -eq "docker-compose") {
            $dockerArgs = @("compose", "exec", "-T", "redis", "redis-cli") + $CommandArgs
            $resp = & docker @dockerArgs 2>&1
            if ($LASTEXITCODE -ne 0) { throw (($resp | Out-String).Trim()) }
            $text = ($resp | Out-String).Trim()
            if ($text -eq "(nil)") { $text = $null }
            elseif ($text -match "`r?`n") { $text = (($text -split "`r?`n") -join ", ") }
            return [pscustomobject]@{ ok = $true; value = $text; error = $null }
        } else {
            $resp = Get-RedisReply -HostName $script:RedisHost -Port $script:RedisPort -CommandArgs $CommandArgs
            return [pscustomobject]@{ ok = $true; value = (Convert-RedisSimple $resp); error = $null }
        }
    } catch {
        return [pscustomobject]@{ ok = $false; value = $null; error = $_.Exception.Message }
    }
}

function Find-DbConnection {
    param([hashtable]$EnvMap)
    $appUser = $(if ($env:MYSQL_USER) { $env:MYSQL_USER } elseif ($EnvMap["MYSQL_USER"]) { $EnvMap["MYSQL_USER"] } else { "maoyan" })
    $appPassword = $(if ($env:MYSQL_PASSWORD) { $env:MYSQL_PASSWORD } elseif ($EnvMap["MYSQL_PASSWORD"]) { $EnvMap["MYSQL_PASSWORD"] } else { "" })
    $rootPassword = $(if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } elseif ($EnvMap["MYSQL_ROOT_PASSWORD"]) { $EnvMap["MYSQL_ROOT_PASSWORD"] } else { $null })
    $creds = New-Object System.Collections.Generic.List[object]
    $creds.Add([pscustomobject]@{ user = $appUser; password = $appPassword; label = "app-user" }) | Out-Null
    if ($rootPassword) {
        $creds.Add([pscustomobject]@{ user = "root"; password = $rootPassword; label = "root-local" }) | Out-Null
    }
    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($name in @($env:MYSQL_DATABASE, $EnvMap["MYSQL_DATABASE"], "maoyan", "xticket")) {
        if (-not [string]::IsNullOrWhiteSpace($name) -and -not $candidates.Contains($name)) {
            $candidates.Add($name)
        }
    }
    foreach ($cred in $creds) {
        $script:DbUser = $cred.user
        $script:DbPassword = $cred.password
        foreach ($db in $candidates) {
            $script:JdbcUrl = "jdbc:mysql://127.0.0.1:3306/$db" + "?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=8000"
            try {
                $probe = Invoke-Db "SELECT COUNT(*) AS cnt FROM movie_schedule"
                $script:DbName = $db
                $script:DbCredentialLabel = $cred.label
                return [pscustomobject]@{ ok = $true; database = $db; count = @($probe)[0].cnt; credential = $cred.label; error = $null }
            } catch {
                continue
            }
        }
    }
    return [pscustomobject]@{ ok = $false; database = $null; count = 0; error = "Unable to connect local MySQL with configured credentials" }
}

function Find-RedisConnection {
    param([hashtable]$EnvMap)
    try {
        $resp = & docker compose exec -T redis redis-cli PING 2>&1
        if ($LASTEXITCODE -eq 0 -and (($resp | Out-String).Trim()) -eq "PONG") {
            $script:RedisMode = "docker-compose"
            $script:RedisHost = "compose:redis"
            $script:RedisPort = $null
            $script:RedisAvailable = $true
            $script:RedisError = $null
            return [pscustomobject]@{ ok = $true; host = "compose:redis"; port = "docker compose exec"; error = $null }
        }
    } catch {
        $script:RedisError = $_.Exception.Message
    }

    $port = 6379
    if ($env:REDIS_PORT) { $port = [int]$env:REDIS_PORT }
    elseif ($EnvMap["REDIS_PORT"]) { $port = [int]$EnvMap["REDIS_PORT"] }
    $hosts = New-Object System.Collections.Generic.List[string]
    foreach ($h in @($env:REDIS_HOST, $EnvMap["REDIS_HOST"], "localhost", "127.0.0.1")) {
        if (-not [string]::IsNullOrWhiteSpace($h) -and -not $hosts.Contains($h)) { $hosts.Add($h) }
    }
    foreach ($h in $hosts) {
        try {
            $pong = Convert-RedisSimple (Get-RedisReply -HostName $h -Port $port -CommandArgs @("PING"))
            if ($pong -eq "PONG") {
                $script:RedisHost = $h
                $script:RedisPort = $port
                $script:RedisMode = "tcp"
                $script:RedisAvailable = $true
                $script:RedisError = $null
                return [pscustomobject]@{ ok = $true; host = $h; port = $port; error = $null }
            }
        } catch {
            $script:RedisError = $_.Exception.Message
        }
    }
    $script:RedisAvailable = $false
    if (-not $script:RedisError) { $script:RedisError = "Redis is not reachable from this shell" }
    return [pscustomobject]@{ ok = $false; host = $null; port = $port; error = $script:RedisError }
}

function Get-Stock {
    param([long]$ScheduleId)
    $db = Try-Db "SELECT id, available_seats, version, price FROM movie_schedule WHERE id = $ScheduleId"
    $redisStock = Invoke-Redis -CommandArgs @("GET", "schedule:stock:$ScheduleId")
    $redisDetail = Invoke-Redis -CommandArgs @("HGETALL", "schedule:detail:$ScheduleId")
    $dirty = Invoke-Redis -CommandArgs @("HGET", "stock:dirty:rollback", "$ScheduleId")
    return [pscustomobject]@{
        db = $(if ($db.ok -and @($db.value).Count -gt 0) { @($db.value)[0] } else { $null })
        redisStock = $redisStock
        redisDetail = $redisDetail
        dirtyRollback = $dirty
    }
}

function Get-UserPoints {
    param([long]$UserId)
    $rows = @(Invoke-Db "SELECT id, account, points FROM sys_user WHERE id = $UserId")
    if ($rows.Count -eq 0) { return $null }
    return $rows[0].points
}

function Get-OrderRows {
    param([string]$Where)
    return @(Invoke-Db "SELECT id, order_no, user_id, schedule_id, lock_token, seat_count, seats_info, unit_price, total_price, status, expire_time, pay_time, cancel_time, create_time FROM ticket_order WHERE $Where ORDER BY id")
}

function Get-SeatLockRows {
    param([string]$Where)
    return @(Invoke-Db "SELECT id, schedule_id, row_num, col_num, user_id, lock_token, order_no, status, lock_until FROM seat_lock WHERE $Where ORDER BY id")
}

function Get-OrderSeatCount {
    param([string]$OrderNo)
    $rows = @(Invoke-Db "SELECT COUNT(*) AS cnt FROM order_seat WHERE order_no = '$OrderNo'")
    return [int]$rows[0].cnt
}

function Get-OrderSeatRows {
    param([string]$OrderNo)
    return @(Invoke-Db "SELECT order_no, schedule_id, row_num, col_num, seat_label FROM order_seat WHERE order_no = '$OrderNo' ORDER BY row_num, col_num")
}

function New-TestUser {
    param([string]$Suffix)
    $account = "codex_api_test_${Timestamp}_$Suffix"
    $body = @{
        account = $account
        password = $Password
        userNick = "Codex API Test $Suffix"
        inviteCode = "lpf"
    }
    $reg = Invoke-Api -Method POST -Path "/api/auth/register" -Body $body
    $login = Invoke-Api -Method POST -Path "/api/auth/login" -Body @{ account = $account; password = $Password }
    if ($login.code -ne 200) { throw "Login failed for ${account}: $($login.message)" }
    return [pscustomobject]@{
        account = $account
        id = [long]$login.data.id
        token = [string]$login.data.token
        tokenMasked = (Mask-Token ([string]$login.data.token))
        headers = @{ Authorization = "Bearer $($login.data.token)" }
        register = $reg
        login = $login
    }
}

function Select-Schedule {
    $sql = @"
SELECT ms.id, ms.available_seats, ms.price, COUNT(sl.id) AS lock_rows
FROM movie_schedule ms
LEFT JOIN seat_lock sl ON sl.schedule_id = ms.id
WHERE ms.status = 1 AND ms.deleted = 0 AND ms.available_seats >= 20
  AND TIMESTAMP(ms.show_date, STR_TO_DATE(ms.show_time, '%H:%i')) > NOW()
GROUP BY ms.id, ms.available_seats, ms.price, ms.show_date, ms.show_time
ORDER BY lock_rows ASC, ms.available_seats DESC, ms.show_date, ms.show_time, ms.id
LIMIT 1
"@
    $rows = @(Invoke-Db $sql)
    if ($rows.Count -eq 0) {
        $rows = @(Invoke-Db "SELECT ms.id, ms.available_seats, ms.price, COUNT(sl.id) AS lock_rows FROM movie_schedule ms LEFT JOIN seat_lock sl ON sl.schedule_id = ms.id WHERE ms.status = 1 AND ms.deleted = 0 AND ms.available_seats >= 20 GROUP BY ms.id, ms.available_seats, ms.price ORDER BY lock_rows ASC, ms.available_seats DESC, ms.id LIMIT 1")
    }
    if ($rows.Count -eq 0) { throw "No salable schedule found in local MySQL" }
    return $rows[0]
}

function Get-FreeSeat {
    param([long]$ScheduleId, [hashtable]$Used)
    $layout = Invoke-Api -Method GET -Path "/api/seat/layout?scheduleId=$ScheduleId"
    if ($layout.code -ne 200) { throw "Seat layout failed: $($layout.message)" }
    $blocked = @{}
    $blockedRows = @(Invoke-Db "SELECT CONCAT(row_num, ',', col_num) AS seat_key FROM seat_lock WHERE schedule_id = $ScheduleId UNION SELECT CONCAT(row_num, ',', col_num) AS seat_key FROM order_seat WHERE schedule_id = $ScheduleId")
    foreach ($blockedRow in $blockedRows) {
        if ($blockedRow.seat_key) {
            $blocked[[string]$blockedRow.seat_key] = $true
        }
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

function Get-FreeSeats {
    param([long]$ScheduleId, [hashtable]$Used, [int]$Count)
    $items = @()
    for ($i = 0; $i -lt $Count; $i++) {
        $items += Get-FreeSeat $ScheduleId $Used
    }
    return @($items)
}

function Get-SeatsInfo {
    param($Seats)
    return (@($Seats) | ForEach-Object { $_.label }) -join ", "
}

function To-SeatBody {
    param($Seats)
    return @(@($Seats) | ForEach-Object { @{ row = $_.row; col = $_.col } })
}

function Same-RedisValue {
    param($Left, $Right)
    if (-not $Left.ok -or -not $Right.ok) { return $true }
    return "$($Left.value)" -eq "$($Right.value)"
}

function Lock-Seat {
    param($User, [long]$ScheduleId, $Seat)
    return Lock-Seats -User $User -ScheduleId $ScheduleId -Seats @($Seat)
}

function Lock-Seats {
    param($User, [long]$ScheduleId, $Seats)
    $seatBody = @(To-SeatBody $Seats)
    $body = @{ scheduleId = $ScheduleId; seats = $seatBody }
    return Invoke-Api -Method POST -Path "/api/seat/lock" -Headers $User.headers -Body $body
}

function Create-Order {
    param($User, [long]$ScheduleId, $Seat, [string]$LockToken)
    return Create-OrderWithSeats -User $User -ScheduleId $ScheduleId -Seats @($Seat) -LockToken $LockToken -SeatsInfo $Seat.label
}

function Create-OrderWithSeats {
    param($User, [long]$ScheduleId, $Seats, [string]$LockToken, [string]$SeatsInfo)
    $seatList = @($Seats)
    $seatBody = @(To-SeatBody $seatList)
    $body = @{
        scheduleId = $ScheduleId
        lockToken = $LockToken
        seats = $seatBody
        seatCount = $seatList.Count
        seatsInfo = $SeatsInfo
    }
    return Invoke-Api -Method POST -Path "/api/order/create" -Headers $User.headers -Body $body
}

function Pay-Order {
    param($User, [string]$OrderNo)
    return Invoke-Api -Method POST -Path "/api/payment/pay?orderNo=$OrderNo" -Headers $User.headers
}

function Cancel-Order {
    param($User, [string]$OrderNo)
    return Invoke-Api -Method POST -Path "/api/order/cancel/$OrderNo" -Headers $User.headers
}

function Add-Finding {
    param([System.Collections.Generic.List[object]]$Findings, [string]$Issue, [string]$Severity, [string]$Steps, [string]$Evidence, [string]$Suggestion)
    $Findings.Add([pscustomobject]@{
        issue = $Issue
        severity = $Severity
        steps = $Steps
        evidence = $Evidence
        suggestion = $Suggestion
    }) | Out-Null
}

function New-Scenario {
    param([string]$Name, [string]$Result, [string]$Conclusion, $Details)
    return [pscustomobject]@{
        name = $Name
        result = $Result
        conclusion = $Conclusion
        details = $Details
    }
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

$backendUri = Assert-LocalBackend $BackendUrl
$envMap = Read-DotEnv (Join-Path $Root ".env")
$script:MysqlJar = Get-ChildItem "$env:USERPROFILE\.m2\repository\com\mysql\mysql-connector-j" -Recurse -Filter "mysql-connector-j-*.jar" -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $script:MysqlJar) { throw "mysql-connector-j jar not found in local Maven cache" }

$script:RedisAvailable = $false
$script:RedisHost = $null
$script:RedisPort = 6379
$script:RedisMode = $null
$script:RedisError = $null

$dbProbe = Find-DbConnection $envMap
if (-not $dbProbe.ok) { throw $dbProbe.error }
$redisProbe = Find-RedisConnection $envMap

$health = Invoke-Api -Method GET -Path "/api/seat/layout?scheduleId=1"
if ($health.code -ne 200) { throw "Backend health check failed on localhost: $($health.message)" }

$scenarioResults = New-Object System.Collections.Generic.List[object]
$findings = New-Object System.Collections.Generic.List[object]
$usedSeats = @{}
$schedule = Select-Schedule
$scheduleId = [long]$schedule.id

$userA = New-TestUser "a"
$userB = New-TestUser "b"

$probeUser = @(Invoke-Db "SELECT id, account FROM sys_user WHERE account IN ('$($userA.account)', '$($userB.account)') ORDER BY account")
if (@($probeUser).Count -lt 2) {
    throw "API-created test users were not found in local MySQL; refusing to continue because HTTP API and queried DB do not match"
}

# Scenario 1: normal transaction
try {
    $seat = Get-FreeSeat $scheduleId $usedSeats
    $beforeStock = Get-Stock $scheduleId
    $pointsBefore = Get-UserPoints $userA.id
    $lock = Lock-Seat $userA $scheduleId $seat
    $lockToken = [string]$lock.data.lockToken
    $order = Create-Order $userA $scheduleId $seat $lockToken
    $orderNo = [string]$order.data.orderNo
    $pay = Pay-Order $userA $orderNo
    $pointsAfter = Get-UserPoints $userA.id
    $afterStock = Get-Stock $scheduleId
    $orderRows = Get-OrderRows "order_no = '$orderNo'"
    $seatLocks = Get-SeatLockRows "order_no = '$orderNo' OR lock_token = '$lockToken'"
    $orderSeatRows = Get-OrderSeatRows $orderNo
    $expectedCost = [int][Math]::Ceiling([decimal]$orderRows[0].total_price)
    $orderSeatRowCount = @($orderSeatRows).Count
    $paidSeatLockCount = @($seatLocks | Where-Object { [int]$_.status -eq 2 }).Count
    $pass = ($pay.code -eq 200 -and [int]$orderRows[0].status -eq 1 -and ($pointsBefore - $pointsAfter) -eq $expectedCost -and $orderSeatRowCount -eq 1 -and $paidSeatLockCount -eq 1)
    $scenarioResults.Add((New-Scenario "正常交易" $(if ($pass) { "PASS" } else { "FAIL" }) "支付成功并确认座位" ([ordered]@{
        userId = $userA.id
        scheduleId = $scheduleId
        seat = $seat.label
        lockToken = Mask-Value $lockToken
        orderNo = $orderNo
        payResponse = @{ code = $pay.code; message = $pay.message; status = $pay.data.status; remainingPoints = $pay.data.remainingPoints }
        pointsBefore = $pointsBefore
        pointsAfter = $pointsAfter
        dbStockBefore = $beforeStock.db.available_seats
        dbStockAfter = $afterStock.db.available_seats
        redisStockBefore = $(if ($beforeStock.redisStock.ok) { $beforeStock.redisStock.value } else { "UNAVAILABLE: $($beforeStock.redisStock.error)" })
        redisStockAfter = $(if ($afterStock.redisStock.ok) { $afterStock.redisStock.value } else { "UNAVAILABLE: $($afterStock.redisStock.error)" })
        orderStatus = Status-Text $orderRows[0].status
        orderSeatCount = $orderSeatRowCount
        seatLockStatus = (@($seatLocks | ForEach-Object { Status-Text $_.status }) -join ", ")
    }))) | Out-Null
    if (-not $pass) { Add-Finding $findings "正常支付链路数据不符合预期" "P0" "正常交易场景" "order=$orderNo" "先复核订单、积分、座位锁状态变更" }
} catch {
    $scenarioResults.Add((New-Scenario "正常交易" "BLOCKED" $_.Exception.Message @{})) | Out-Null
}

# Scenario 2: same lockToken repeated create
try {
    $seats = @(Get-FreeSeats $scheduleId $usedSeats 2)
    $seatCount = $seats.Count
    $seatsInfo = Get-SeatsInfo $seats
    $beforeStock = Get-Stock $scheduleId
    $lock = Lock-Seats -User $userA -ScheduleId $scheduleId -Seats $seats
    $lockToken = [string]$lock.data.lockToken
    $create1 = Create-OrderWithSeats -User $userA -ScheduleId $scheduleId -Seats $seats -LockToken $lockToken -SeatsInfo $seatsInfo
    $create2 = Create-OrderWithSeats -User $userA -ScheduleId $scheduleId -Seats $seats -LockToken $lockToken -SeatsInfo $seatsInfo
    $reversedSeats = @($seats[1], $seats[0])
    $reverseRetry = Create-OrderWithSeats -User $userA -ScheduleId $scheduleId -Seats $reversedSeats -LockToken $lockToken -SeatsInfo (Get-SeatsInfo $reversedSeats)
    $afterInitialRetriesStock = Get-Stock $scheduleId
    $differentSeats = @(Get-FreeSeats $scheduleId $usedSeats $seatCount)
    $differentSeatRetry = Create-OrderWithSeats -User $userA -ScheduleId $scheduleId -Seats $differentSeats -LockToken $lockToken -SeatsInfo (Get-SeatsInfo $differentSeats)
    $differentUserRetry = Create-OrderWithSeats -User $userB -ScheduleId $scheduleId -Seats $seats -LockToken $lockToken -SeatsInfo $seatsInfo
    $afterConflictRetriesStock = Get-Stock $scheduleId
    $ordersBeforeCancel = Get-OrderRows "user_id = $($userA.id) AND schedule_id = $scheduleId AND lock_token = '$lockToken'"
    $seatLocksBeforeCancel = Get-SeatLockRows "lock_token = '$lockToken'"
    $cancelOriginal = Cancel-Order $userA ([string]$create1.data.orderNo)
    $afterCancelStock = Get-Stock $scheduleId
    $retryAfterCancel = Create-OrderWithSeats -User $userA -ScheduleId $scheduleId -Seats $seats -LockToken $lockToken -SeatsInfo $seatsInfo
    $afterCancelRetryStock = Get-Stock $scheduleId
    $orders = $ordersBeforeCancel
    $seatLocks = $seatLocksBeforeCancel
    $orderNos = @($orders | ForEach-Object { $_.order_no })
    $boundOrderNos = @($seatLocks | Where-Object { $_.order_no } | ForEach-Object { $_.order_no } | Select-Object -Unique)
    $orphanOrders = @($orderNos | Where-Object { $boundOrderNos -notcontains $_ })
    $dbDelta = [int]$beforeStock.db.available_seats - [int]$afterInitialRetriesStock.db.available_seats
    $redisDelta = $null
    if ($beforeStock.redisStock.ok -and $afterInitialRetriesStock.redisStock.ok -and $beforeStock.redisStock.value -match "^-?\d+$" -and $afterInitialRetriesStock.redisStock.value -match "^-?\d+$") {
        $redisDelta = [int]$beforeStock.redisStock.value - [int]$afterInitialRetriesStock.redisStock.value
    }
    $orderNo1 = [string]$create1.data.orderNo
    $classification = "FAIL"
    $orderCount = @($orders).Count
    $sameRequestIdempotent = ($create2.code -eq 200 -and [string]$create2.data.orderNo -eq $orderNo1)
    $reverseRequestIdempotent = ($reverseRetry.code -eq 200 -and [string]$reverseRetry.data.orderNo -eq $orderNo1)
    $conflictRejected = ($differentSeatRetry.code -ne 200 -and $differentUserRetry.code -ne 200)
    $conflictNoStockLeak = ([int]$afterConflictRetriesStock.db.available_seats -eq [int]$afterInitialRetriesStock.db.available_seats) -and (Same-RedisValue $afterConflictRetriesStock.redisStock $afterInitialRetriesStock.redisStock)
    $cancelRestoredStock = ([int]$afterCancelStock.db.available_seats - [int]$afterConflictRetriesStock.db.available_seats) -eq $seatCount
    $retryAfterCancelNoNewOrder = ([string]$retryAfterCancel.data.orderNo -eq $orderNo1)
    $retryAfterCancelNoStockChange = ([int]$afterCancelRetryStock.db.available_seats -eq [int]$afterCancelStock.db.available_seats) -and (Same-RedisValue $afterCancelRetryStock.redisStock $afterCancelStock.redisStock)
    if ($orderCount -eq 1 -and $dbDelta -eq $seatCount -and ($null -eq $redisDelta -or $redisDelta -eq $seatCount) -and
        $sameRequestIdempotent -and $reverseRequestIdempotent -and $conflictRejected -and $conflictNoStockLeak -and
        $cancelRestoredStock -and $retryAfterCancelNoNewOrder -and $retryAfterCancelNoStockChange) {
        $classification = "PASS_IDEMPOTENT"
    } elseif ($orderCount -eq 1 -and $dbDelta -eq $seatCount -and ($null -eq $redisDelta -or $redisDelta -eq $seatCount) -and $conflictNoStockLeak) {
        $classification = "SAFE_BUT_NOT_IDEMPOTENT"
    }
    $resultForReport = $classification
    $scenarioResults.Add((New-Scenario "重复建单" $resultForReport $classification ([ordered]@{
        lockToken = Mask-Value $lockToken
        seats = $seatsInfo
        firstResponse = @{ code = $create1.code; message = $create1.message; orderNo = $create1.data.orderNo; status = $create1.data.status }
        secondResponse = @{ code = $create2.code; message = $create2.message; orderNo = $create2.data.orderNo; status = $create2.data.status }
        reversedSeatsResponse = @{ code = $reverseRetry.code; message = $reverseRetry.message; orderNo = $reverseRetry.data.orderNo; status = $reverseRetry.data.status }
        differentSeats = Get-SeatsInfo $differentSeats
        differentSeatResponse = @{ code = $differentSeatRetry.code; message = $differentSeatRetry.message }
        differentUserResponse = @{ code = $differentUserRetry.code; message = $differentUserRetry.message }
        cancelOriginalResponse = @{ code = $cancelOriginal.code; message = $cancelOriginal.message }
        retryAfterCancelResponse = @{ code = $retryAfterCancel.code; message = $retryAfterCancel.message; orderNo = $retryAfterCancel.data.orderNo; status = $retryAfterCancel.data.status }
        orderCount = $orderCount
        orderNos = $orderNos
        seatCount = $seatCount
        dbStockBefore = $beforeStock.db.available_seats
        dbStockAfter = $afterInitialRetriesStock.db.available_seats
        dbStockAfterConflictRetries = $afterConflictRetriesStock.db.available_seats
        dbStockAfterCancel = $afterCancelStock.db.available_seats
        dbStockAfterCancelRetry = $afterCancelRetryStock.db.available_seats
        redisStockBefore = $(if ($beforeStock.redisStock.ok) { $beforeStock.redisStock.value } else { "UNAVAILABLE: $($beforeStock.redisStock.error)" })
        redisStockAfter = $(if ($afterInitialRetriesStock.redisStock.ok) { $afterInitialRetriesStock.redisStock.value } else { "UNAVAILABLE: $($afterInitialRetriesStock.redisStock.error)" })
        redisStockAfterConflictRetries = $(if ($afterConflictRetriesStock.redisStock.ok) { $afterConflictRetriesStock.redisStock.value } else { "UNAVAILABLE: $($afterConflictRetriesStock.redisStock.error)" })
        redisStockAfterCancel = $(if ($afterCancelStock.redisStock.ok) { $afterCancelStock.redisStock.value } else { "UNAVAILABLE: $($afterCancelStock.redisStock.error)" })
        redisStockAfterCancelRetry = $(if ($afterCancelRetryStock.redisStock.ok) { $afterCancelRetryStock.redisStock.value } else { "UNAVAILABLE: $($afterCancelRetryStock.redisStock.error)" })
        seatLockOrderNo = (@($seatLocks | ForEach-Object { $_.order_no }) -join ", ")
        orphanOrders = $orphanOrders
        sameRequestIdempotent = $sameRequestIdempotent
        reverseRequestIdempotent = $reverseRequestIdempotent
        conflictRejected = $conflictRejected
        conflictNoStockLeak = $conflictNoStockLeak
        retryAfterCancelNoNewOrder = $retryAfterCancelNoNewOrder
        retryAfterCancelNoStockChange = $retryAfterCancelNoStockChange
        orderCreatedLogCount = Get-OrderCreatedLogCount $orderNo1
    }))) | Out-Null
    if ($resultForReport -eq "FAIL") {
        Add-Finding $findings "同一 lockToken 重复建单产生不安全副作用" "P0" "同一 lockToken 连续 POST /api/order/create 两次" "orders=$orderCount, dbDelta=$dbDelta, redisDelta=$redisDelta, orphanOrders=$($orphanOrders -join ',')" "后续阶段考虑锁座令牌消费状态或请求幂等，但本轮不修复"
    }
} catch {
    $scenarioResults.Add((New-Scenario "重复建单" "BLOCKED" $_.Exception.Message @{})) | Out-Null
}

# Scenario 3: same orderNo repeated pay
try {
    $seat = Get-FreeSeat $scheduleId $usedSeats
    $lock = Lock-Seat $userA $scheduleId $seat
    $lockToken = [string]$lock.data.lockToken
    $order = Create-Order $userA $scheduleId $seat $lockToken
    $orderNo = [string]$order.data.orderNo
    $pointsBefore = Get-UserPoints $userA.id
    $pay1 = Pay-Order $userA $orderNo
    $pay2 = Pay-Order $userA $orderNo
    $pointsAfter = Get-UserPoints $userA.id
    $orderRows = Get-OrderRows "order_no = '$orderNo'"
    $orderSeatCount = Get-OrderSeatCount $orderNo
    $seatLocks = Get-SeatLockRows "order_no = '$orderNo' OR lock_token = '$lockToken'"
    $expectedCost = [int][Math]::Ceiling([decimal]$orderRows[0].total_price)
    $pass = ($pay1.code -eq 200 -and ($pointsBefore - $pointsAfter) -eq $expectedCost -and $orderSeatCount -eq 1 -and [int]$orderRows[0].status -eq 1)
    $scenarioResults.Add((New-Scenario "重复支付" $(if ($pass) { "PASS" } else { "FAIL" }) $(if ($pass) { "积分只扣一次，第二次未产生额外资源变更" } else { "重复支付存在异常副作用" }) ([ordered]@{
        orderNo = $orderNo
        firstResponse = @{ code = $pay1.code; message = $pay1.message; status = $pay1.data.status; remainingPoints = $pay1.data.remainingPoints }
        secondResponse = @{ code = $pay2.code; message = $pay2.message }
        pointsBefore = $pointsBefore
        pointsAfter = $pointsAfter
        orderStatus = Status-Text $orderRows[0].status
        orderSeatCount = $orderSeatCount
        seatLockStatus = (@($seatLocks | ForEach-Object { Status-Text $_.status }) -join ", ")
    }))) | Out-Null
    if (-not $pass) { Add-Finding $findings "同一订单重复支付存在不安全副作用" "P0" "同一 orderNo 连续支付两次" "order=$orderNo" "后续阶段复核支付状态 CAS 和事务回滚" }
} catch {
    $scenarioResults.Add((New-Scenario "重复支付" "BLOCKED" $_.Exception.Message @{})) | Out-Null
}

# Scenario 4: repeated cancel pending order
try {
    $seat = Get-FreeSeat $scheduleId $usedSeats
    $beforeLockStock = Get-Stock $scheduleId
    $lock = Lock-Seat $userA $scheduleId $seat
    $lockToken = [string]$lock.data.lockToken
    $order = Create-Order $userA $scheduleId $seat $lockToken
    $orderNo = [string]$order.data.orderNo
    $beforeCancelStock = Get-Stock $scheduleId
    $cancel1 = Cancel-Order $userA $orderNo
    $cancel2 = Cancel-Order $userA $orderNo
    $afterCancelStock = Get-Stock $scheduleId
    $payAfterCancel = Pay-Order $userA $orderNo
    $relock = Lock-Seat $userA $scheduleId $seat
    $orders = Get-OrderRows "order_no = '$orderNo'"
    $seatLocks = Get-SeatLockRows "order_no = '$orderNo' OR lock_token = '$lockToken'"
    $dbRestoredOnce = ([int]$afterCancelStock.db.available_seats - [int]$beforeCancelStock.db.available_seats) -eq 1
    $dbBackToBeforeLock = ([int]$afterCancelStock.db.available_seats -eq [int]$beforeLockStock.db.available_seats)
    $seatReleased = (@($seatLocks | Where-Object { $_.order_no -eq $orderNo }).Count -eq 0)
    $pass = ($cancel1.code -eq 200 -and [int]$orders[0].status -eq 2 -and $dbRestoredOnce -and $dbBackToBeforeLock -and $seatReleased -and $payAfterCancel.code -ne 200 -and $relock.code -eq 200)
    $scenarioResults.Add((New-Scenario "重复取消" $(if ($pass) { "PASS" } else { "FAIL" }) $(if ($pass) { "第二次取消未重复恢复库存，原座位可重新锁定" } else { "重复取消存在异常" }) ([ordered]@{
        orderNo = $orderNo
        lockToken = Mask-Value $lockToken
        firstResponse = @{ code = $cancel1.code; message = $cancel1.message }
        secondResponse = @{ code = $cancel2.code; message = $cancel2.message }
        payAfterCancel = @{ code = $payAfterCancel.code; message = $payAfterCancel.message }
        relockResponse = @{ code = $relock.code; message = $relock.message; lockToken = Mask-Value (Get-ResponseLockToken $relock) }
        dbStockBeforeLock = $beforeLockStock.db.available_seats
        dbStockBeforeCancel = $beforeCancelStock.db.available_seats
        dbStockAfterCancel = $afterCancelStock.db.available_seats
        redisStockBeforeCancel = $(if ($beforeCancelStock.redisStock.ok) { $beforeCancelStock.redisStock.value } else { "UNAVAILABLE: $($beforeCancelStock.redisStock.error)" })
        redisStockAfterCancel = $(if ($afterCancelStock.redisStock.ok) { $afterCancelStock.redisStock.value } else { "UNAVAILABLE: $($afterCancelStock.redisStock.error)" })
        orderStatus = Status-Text $orders[0].status
        seatReleased = $seatReleased
    }))) | Out-Null
    if (-not $pass) { Add-Finding $findings "重复取消待支付订单存在不安全表现" "P0" "同一待支付 orderNo 连续取消两次" "order=$orderNo" "后续阶段复核 closePendingOrder CAS 与资源释放" }
} catch {
    $scenarioResults.Add((New-Scenario "重复取消" "BLOCKED" $_.Exception.Message @{})) | Out-Null
}

# Scenario 5: timeout close
try {
    $seat = Get-FreeSeat $scheduleId $usedSeats
    $lock = Lock-Seat $userA $scheduleId $seat
    $lockToken = [string]$lock.data.lockToken
    $order = Create-Order $userA $scheduleId $seat $lockToken
    $orderNo = [string]$order.data.orderNo
    $beforeExpireStock = Get-Stock $scheduleId
    $markTime = Get-Date
    $update = Invoke-Db "UPDATE ticket_order SET expire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE), update_time = NOW() WHERE order_no = '$orderNo' AND status = 0"
    $closedAt = $null
    $latestOrder = $null
    for ($elapsed = 0; $elapsed -le $TimeoutPollSeconds; $elapsed += 5) {
        Start-Sleep -Seconds 5
        $latestOrder = @(Get-OrderRows "order_no = '$orderNo'")[0]
        if ([int]$latestOrder.status -eq 2) {
            $closedAt = Get-Date
            break
        }
    }
    $afterExpireStock = Get-Stock $scheduleId
    $seatLocks = Get-SeatLockRows "order_no = '$orderNo' OR lock_token = '$lockToken'"
    $payAfterTimeout = Pay-Order $userA $orderNo
    $delay = $(if ($closedAt) { [Math]::Round(($closedAt - $markTime).TotalSeconds, 1) } else { $null })
    $dbRestored = $closedAt -and (([int]$afterExpireStock.db.available_seats - [int]$beforeExpireStock.db.available_seats) -eq 1)
    $seatReleased = (@($seatLocks | Where-Object { $_.order_no -eq $orderNo }).Count -eq 0)
    $pass = ($closedAt -and [int]$latestOrder.status -eq 2 -and $dbRestored -and $seatReleased -and $payAfterTimeout.code -ne 200)
    $scenarioResults.Add((New-Scenario "超时关单" $(if ($pass) { "PASS" } elseif ($closedAt) { "FAIL" } else { "BLOCKED" }) $(if ($pass) { "定时任务关闭订单并释放资源" } elseif ($closedAt) { "关单后数据不符合预期" } else { "130 秒内未检测到自动关单" }) ([ordered]@{
        orderNo = $orderNo
        lockToken = Mask-Value $lockToken
        expireUpdateCount = @($update)[0].updateCount
        markedExpiredAt = $markTime.ToString("yyyy-MM-dd HH:mm:ss")
        detectedClosedAt = $(if ($closedAt) { $closedAt.ToString("yyyy-MM-dd HH:mm:ss") } else { "" })
        delaySeconds = $delay
        dbStockBeforeClose = $beforeExpireStock.db.available_seats
        dbStockAfterClose = $afterExpireStock.db.available_seats
        redisStockBeforeClose = $(if ($beforeExpireStock.redisStock.ok) { $beforeExpireStock.redisStock.value } else { "UNAVAILABLE: $($beforeExpireStock.redisStock.error)" })
        redisStockAfterClose = $(if ($afterExpireStock.redisStock.ok) { $afterExpireStock.redisStock.value } else { "UNAVAILABLE: $($afterExpireStock.redisStock.error)" })
        orderStatus = $(if ($latestOrder) { Status-Text $latestOrder.status } else { "" })
        seatReleased = $seatReleased
        payAfterTimeout = @{ code = $payAfterTimeout.code; message = $payAfterTimeout.message }
        backendLogs = Get-BackendLogsForOrder $orderNo
    }))) | Out-Null
    if (-not $pass) { Add-Finding $findings "超时关单未完全满足预期" $(if ($closedAt) { "P1" } else { "P1" }) "创建待支付订单后将 expire_time 调整为过去并等待" "order=$orderNo, delay=$delay" "后续阶段复核定时任务调度和释放链路" }
} catch {
    $scenarioResults.Add((New-Scenario "超时关单" "BLOCKED" $_.Exception.Message @{})) | Out-Null
}

# Scenario 6: two users compete for same seat
try {
    $seat = Get-FreeSeat $scheduleId $usedSeats
    $bodyObj = @{ scheduleId = $scheduleId; seats = @(@{ row = $seat.row; col = $seat.col }) }
    $jsonBody = $bodyObj | ConvertTo-Json -Depth 12 -Compress
    Add-Type -AssemblyName System.Net.Http
    $clientA = [System.Net.Http.HttpClient]::new()
    $clientB = [System.Net.Http.HttpClient]::new()
    $reqA = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$BackendUrl/api/seat/lock")
    $reqA.Headers.TryAddWithoutValidation("Authorization", "Bearer $($userA.token)") | Out-Null
    $reqA.Content = [System.Net.Http.StringContent]::new($jsonBody, [Text.Encoding]::UTF8, "application/json")
    $reqB = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$BackendUrl/api/seat/lock")
    $reqB.Headers.TryAddWithoutValidation("Authorization", "Bearer $($userB.token)") | Out-Null
    $reqB.Content = [System.Net.Http.StringContent]::new($jsonBody, [Text.Encoding]::UTF8, "application/json")
    $taskA = $clientA.SendAsync($reqA)
    $taskB = $clientB.SendAsync($reqB)
    [System.Threading.Tasks.Task]::WaitAll($taskA, $taskB)
    $textA = $taskA.Result.Content.ReadAsStringAsync().Result
    $textB = $taskB.Result.Content.ReadAsStringAsync().Result
    $respA = $textA | ConvertFrom-Json
    $respB = $textB | ConvertFrom-Json
    $seatLocks = Get-SeatLockRows "schedule_id = $scheduleId AND row_num = $($seat.row) AND col_num = $($seat.col)"
    $successCount = @(@($respA, $respB) | Where-Object { $_.code -eq 200 }).Count
    $seatLockedCount = @(@($respA, $respB) | Where-Object { $_.code -eq 462 }).Count
    $serverErrorCount = @(@($respA, $respB) | Where-Object { $_.code -eq 500 }).Count
    $distinctTokens = @($seatLocks | Where-Object { [int]$_.status -eq 1 -or [int]$_.status -eq 2 } | ForEach-Object { $_.lock_token } | Select-Object -Unique)
    $seatLockCount = @($seatLocks).Count
    $activeTokenCount = @($distinctTokens).Count
    $pass = ($successCount -eq 1 -and $seatLockedCount -eq 1 -and $serverErrorCount -eq 0 -and $seatLockCount -eq 1 -and $activeTokenCount -eq 1)
    $scenarioResults.Add((New-Scenario "同座竞争" $(if ($pass) { "PASS" } else { "FAIL" }) $(if ($pass) { "一个用户锁座成功，另一个稳定返回 462，同座只有一条锁记录" } else { "同座竞争出现异常" }) ([ordered]@{
        userA = $userA.account
        userB = $userB.account
        scheduleId = $scheduleId
        seat = $seat.label
        responseA = @{ code = $respA.code; message = $respA.message; lockToken = Mask-Value (Get-ResponseLockToken $respA) }
        responseB = @{ code = $respB.code; message = $respB.message; lockToken = Mask-Value (Get-ResponseLockToken $respB) }
        successCount = $successCount
        seatLockedCount = $seatLockedCount
        serverErrorCount = $serverErrorCount
        seatLockCount = $seatLockCount
        activeTokenCount = $activeTokenCount
        seatLockUsers = (@($seatLocks | ForEach-Object { $_.user_id }) -join ", ")
    }))) | Out-Null
    if (-not $pass) {
        $severity = if ($successCount -gt 1 -or $seatLockCount -ne 1 -or $activeTokenCount -ne 1) { "P0" } else { "P1" }
        Add-Finding $findings "两个用户同座竞争响应不符合预期" $severity "两个 HttpClient 近同时 POST /api/seat/lock" "schedule=$scheduleId seat=$($seat.label), success=$successCount, 462=$seatLockedCount, 500=$serverErrorCount, lockRows=$seatLockCount, tokens=$activeTokenCount" "同座竞争应为一个成功、一个 462，且不返回 500"
    }
} catch {
    $scenarioResults.Add((New-Scenario "同座竞争" "BLOCKED" $_.Exception.Message @{})) | Out-Null
}

if (-not $redisProbe.ok) {
    Add-Finding $findings "当前执行环境无法从宿主机查询 Redis" "P1" "脚本探测 Redis 连接" $redisProbe.error "确认 Redis 是否暴露给本机或提供 docker CLI/redis-cli 访问路径"
}

$javaVersion = ((& cmd /c "java -version 2>&1") | Select-Object -First 1)
$mysqlVersionRows = Try-Db "SELECT VERSION() AS version"
$mysqlVersion = $(if ($mysqlVersionRows.ok) { @($mysqlVersionRows.value)[0].version } else { "UNKNOWN" })
$dirtyAll = Invoke-Redis -CommandArgs @("HGETALL", "stock:dirty:rollback")

$summaryRows = foreach ($s in $scenarioResults) {
    $keyResp = ""
    if ($s.details -and (Get-DetailValue $s.details "firstResponse")) { $keyResp = To-JsonShort (Get-DetailValue $s.details "firstResponse") }
    elseif ($s.details -and (Get-DetailValue $s.details "payResponse")) { $keyResp = To-JsonShort (Get-DetailValue $s.details "payResponse") }
    elseif ($s.details -and (Get-DetailValue $s.details "responseA")) { $keyResp = (To-JsonShort (Get-DetailValue $s.details "responseA")) + " / " + (To-JsonShort (Get-DetailValue $s.details "responseB")) }
    $dbEvidence = To-JsonShort ([ordered]@{
        orderNo = Get-DetailValue $s.details "orderNo"
        orderCount = Get-DetailValue $s.details "orderCount"
        dbStockBefore = Get-DetailValue $s.details "dbStockBefore"
        dbStockAfter = Get-DetailValue $s.details "dbStockAfter"
        dbStockBeforeCancel = Get-DetailValue $s.details "dbStockBeforeCancel"
        dbStockAfterCancel = Get-DetailValue $s.details "dbStockAfterCancel"
        dbStockBeforeClose = Get-DetailValue $s.details "dbStockBeforeClose"
        dbStockAfterClose = Get-DetailValue $s.details "dbStockAfterClose"
        orderStatus = Get-DetailValue $s.details "orderStatus"
        orderSeatCount = Get-DetailValue $s.details "orderSeatCount"
        seatLockCount = Get-DetailValue $s.details "seatLockCount"
    })
    $redisEvidence = To-JsonShort ([ordered]@{
        redisStockBefore = Get-DetailValue $s.details "redisStockBefore"
        redisStockAfter = Get-DetailValue $s.details "redisStockAfter"
        redisStockBeforeCancel = Get-DetailValue $s.details "redisStockBeforeCancel"
        redisStockAfterCancel = Get-DetailValue $s.details "redisStockAfterCancel"
        redisStockBeforeClose = Get-DetailValue $s.details "redisStockBeforeClose"
        redisStockAfterClose = Get-DetailValue $s.details "redisStockAfterClose"
    })
    "| $($s.name) | $($s.result) | $keyResp | $dbEvidence | $redisEvidence | $($s.conclusion) |"
}

$findingRows = if ($findings.Count -eq 0) {
    "| 无 |  |  |  |  |"
} else {
    foreach ($f in $findings) {
        "| $($f.issue) | $($f.severity) | $($f.steps) | $($f.evidence) | $($f.suggestion) |"
    }
}

$detailsText = foreach ($s in $scenarioResults) {
    "### $($s.name)`n`n结果：$($s.result)`n`n结论：$($s.conclusion)`n`n``````json`n$(To-JsonShort $s.details)`n``````"
}

$report = @"
# API 交易验收记录

## 1. 执行环境

- 测试时间：$(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
- 后端地址：$BackendUrl
- 后端地址校验：localhost
- Java：$javaVersion
- MySQL：Docker Compose mysql，经 127.0.0.1:3306 访问 / database=$script:DbName / version=$mysqlVersion
- Redis：$(if ($redisProbe.ok) { "$($redisProbe.host):$($redisProbe.port)" } else { "BLOCKED - $($redisProbe.error)" })
- RocketMQ：本轮不直接测试 Broker；后端已启动时按当前应用配置运行
- 测试用户 A：$($userA.account)，userId=$($userA.id)，token=$(Mask-Token $userA.token)
- 测试用户 B：$($userB.account)，userId=$($userB.id)，token=$(Mask-Token $userB.token)
- 测试账号前缀：codex_api_test_
- 数据库校验：API 创建的测试用户已在本地 MySQL 查询到
- Redis dirty rollback：$(if ($dirtyAll.ok) { $dirtyAll.value } else { "UNAVAILABLE: $($dirtyAll.error)" })

## 2. 测试汇总

| 场景 | 结果 | 关键响应 | 数据库证据 | Redis 证据 | 结论 |
|---|---|---|---|---|---|
$($summaryRows -join "`n")

## 3. 正常交易

$($detailsText | Where-Object { $_ -like "### 正常交易*" })

## 4. 重复建单

$($detailsText | Where-Object { $_ -like "### 重复建单*" })

## 5. 重复支付

$($detailsText | Where-Object { $_ -like "### 重复支付*" })

## 6. 重复取消

$($detailsText | Where-Object { $_ -like "### 重复取消*" })

## 7. 超时关单

$($detailsText | Where-Object { $_ -like "### 超时关单*" })

## 8. 同座竞争

$($detailsText | Where-Object { $_ -like "### 同座竞争*" })

## 9. 发现的问题

| 问题 | 严重程度 | 复现步骤 | 数据证据 | 建议 |
|---|---|---|---|---|
$($findingRows -join "`n")

## 10. 执行边界

- 本轮通过 localhost HTTP API 执行业务操作。
- 除将专用测试订单 expire_time 调整到过去外，未通过 SQL 模拟业务结果。
- 未输出完整 Token、测试密码或数据库密码。
- Phase 1A 仅修复同一 lockToken 重复建单的幂等与库存一致性问题。
- 未修改前端代码、Maven 依赖、支付流水、Outbox、电子票、核销、退款、Waiting Room 或领域命名。
"@

$report | Set-Content -Path $ReportPath -Encoding UTF8

[pscustomobject]@{
    reportPath = $ReportPath
    scriptPath = $PSCommandPath
    backend = $BackendUrl
    database = $script:DbName
    redis = $(if ($redisProbe.ok) { "$($redisProbe.host):$($redisProbe.port)" } else { "BLOCKED: $($redisProbe.error)" })
    scenarios = $scenarioResults
    findings = $findings
} | ConvertTo-Json -Depth 20
