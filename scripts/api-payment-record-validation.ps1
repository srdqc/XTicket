param([string]$BackendUrl = "http://localhost")

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ts = Get-Date -Format "yyyyMMddHHmmss"
$password = "CodexPayRecord_123456"
$results = @()

function Assert-Local {
    $u = [Uri]$BackendUrl
    if (@("localhost", "127.0.0.1", "::1") -notcontains $u.Host) {
        throw "Refusing non-local backend: $BackendUrl"
    }
}

function DockerExe {
    $cmd = Get-Command docker -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return "$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin\docker.exe"
}

$docker = DockerExe
$mysql = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw'

function DbLines([string]$sql) {
    $out = $sql | & $script:docker compose exec -T mysql sh -lc $script:mysql 2>&1
    if ($LASTEXITCODE -ne 0) { throw (($out | Out-String).Trim()) }
    return @($out | Where-Object { $_ -and -not $_.StartsWith("mysql: [Warning]") })
}

function DbScalar([string]$sql) {
    $lines = @(DbLines $sql)
    if ($lines.Count -lt 2) { return $null }
    return ($lines[1] -split "`t")[0]
}

function DbExec([string]$sql) { DbLines $sql | Out-Null }

function DbRows([string]$sql) {
    $lines = @(DbLines $sql)
    if ($lines.Count -lt 2) { return @() }
    $headers = $lines[0] -split "`t"
    $rows = @()
    for ($i = 1; $i -lt $lines.Count; $i++) {
        $values = $lines[$i] -split "`t"
        $obj = [ordered]@{}
        for ($j = 0; $j -lt $headers.Count; $j++) { $obj[$headers[$j]] = if ($j -lt $values.Count) { $values[$j] } else { $null } }
        $rows += [pscustomobject]$obj
    }
    return $rows
}

function Api([string]$method, [string]$path, $body, [string]$token) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    $json = $null
    if ($null -ne $body) { $json = $body | ConvertTo-Json -Depth 12 -Compress }
    try {
        if ($json) {
            return Invoke-RestMethod -Method $method -Uri "$BackendUrl$path" -Headers $headers -Body $json -ContentType "application/json"
        }
        return Invoke-RestMethod -Method $method -Uri "$BackendUrl$path" -Headers $headers
    } catch {
        $message = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.PSObject.Properties["Message"]) {
            $message = $_.ErrorDetails.Message
        }
        if (-not $message -and $_.Exception.Response) {
            $stream = $_.Exception.Response.GetResponseStream()
            if ($stream) {
                $reader = New-Object System.IO.StreamReader($stream)
                $message = $reader.ReadToEnd()
            }
        }
        if ($message) { return ($message | ConvertFrom-Json) }
        throw
    }
}

function NewUser([string]$suffix) {
    $account = "cpr_$($ts.Substring(6))_$suffix"
    Api POST "/api/auth/register" @{ account=$account; password=$password; userNick=$account; inviteCode="lpf" } $null | Out-Null
    $login = Api POST "/api/auth/login" @{ account=$account; password=$password } $null
    return [pscustomobject]@{ id=[long]$login.data.id; account=$account; token=[string]$login.data.token }
}

function SeatList($seats) {
    $list = New-Object System.Collections.Generic.List[object]
    foreach ($s in @($seats)) { $list.Add(([pscustomobject]@{ row=[int]$s.row; col=[int]$s.col })) }
    return ,$list
}

function FreeSeat($user, [long]$scheduleId) {
    $layout = Api GET "/api/seat/layout?scheduleId=$scheduleId" $null $user.token
    foreach ($row in $layout.data.seats) {
        foreach ($seat in $row) {
            if ($seat.status -eq 0) { return [pscustomobject]@{ row=[int]$seat.row; col=[int]$seat.col } }
        }
    }
    throw "No free seat for schedule $scheduleId"
}

function CreatePending($user, [long]$scheduleId) {
    $seat = FreeSeat $user $scheduleId
    $seats = SeatList @($seat)
    $lock = Api POST "/api/seat/lock" @{ scheduleId=$scheduleId; seats=$seats } $user.token
    if ($lock.code -ne 200) { throw "Lock failed: $($lock.message)" }
    $lockToken = [string]$lock.data.lockToken
    $create = Api POST "/api/order/create" @{ scheduleId=$scheduleId; lockToken=$lockToken; seats=$seats; seatCount=1; seatsInfo="CLIENT_TEXT" } $user.token
    if ($create.code -ne 200) { throw "Create failed: $($create.message)" }
    return [pscustomobject]@{ orderNo=[string]$create.data.orderNo; seat=$seat; lockToken=$lockToken }
}

function PaymentCount([string]$orderNo) {
    return [int](DbScalar "SELECT COUNT(*) FROM payment_record WHERE order_no = '$orderNo' AND deleted = 0")
}

function PaymentRow([string]$orderNo) {
    $rows = @(DbRows "SELECT payment_no, order_no, user_id, amount, channel, status, paid_at FROM payment_record WHERE order_no = '$orderNo' AND deleted = 0")
    if ($rows.Count -eq 0) { return $null }
    return $rows[0]
}

function UserPoints([long]$userId) { return [int](DbScalar "SELECT points FROM sys_user WHERE id = $userId") }
function OrderSeatCount([string]$orderNo) { return [int](DbScalar "SELECT COUNT(*) FROM order_seat WHERE order_no = '$orderNo'") }
function OrderTotal([string]$orderNo) { return [decimal](DbScalar "SELECT total_price FROM ticket_order WHERE order_no = '$orderNo'") }

function AddResult([string]$name, [string]$result, $evidence) {
    $script:results += [pscustomobject]@{ name=$name; result=$result; evidence=$evidence }
}

Assert-Local
$scheduleId = [long](DbScalar "SELECT id FROM activity_session WHERE status = 1 AND deleted = 0 AND available_seats > 20 ORDER BY available_seats DESC, id LIMIT 1")

$u1 = NewUser "normal"
$o1 = CreatePending $u1 $scheduleId
$before = UserPoints $u1.id
$pay1 = Api POST "/api/payment/pay?orderNo=$($o1.orderNo)" $null $u1.token
$row1 = PaymentRow $o1.orderNo
AddResult "normal" ($(if ($pay1.code -eq 200 -and (PaymentCount $o1.orderNo) -eq 1 -and $row1.payment_no -and $row1.channel -eq "MOCK_POINTS" -and $row1.status -eq "SUCCESS" -and [decimal]$row1.amount -eq (OrderTotal $o1.orderNo)) { "PASS" } else { "FAIL" })) @{
    orderNo=$o1.orderNo; paymentCount=(PaymentCount $o1.orderNo); amount=$row1.amount; pointsBefore=$before; pointsAfter=(UserPoints $u1.id)
}

$u2 = NewUser "repeat"
$o2 = CreatePending $u2 $scheduleId
$p2Before = UserPoints $u2.id
$r21 = Api POST "/api/payment/pay?orderNo=$($o2.orderNo)" $null $u2.token
$r22 = Api POST "/api/payment/pay?orderNo=$($o2.orderNo)" $null $u2.token
AddResult "repeatPay" ($(if ($r21.code -eq 200 -and $r22.code -eq 409 -and (PaymentCount $o2.orderNo) -eq 1 -and ((UserPoints $u2.id) -eq ($p2Before - [int][Math]::Ceiling([double](OrderTotal $o2.orderNo)))) -and (OrderSeatCount $o2.orderNo) -eq 1) { "PASS" } else { "FAIL" })) @{
    orderNo=$o2.orderNo; first=$r21.code; second=$r22.code; paymentCount=(PaymentCount $o2.orderNo); pointsBefore=$p2Before; pointsAfter=(UserPoints $u2.id)
}

$u3 = NewUser "concurrent"
$o3 = CreatePending $u3 $scheduleId
$p3Before = UserPoints $u3.id
$jobScript = { param($url,$orderNo,$token) try { Invoke-RestMethod -Method POST -Uri "$url/api/payment/pay?orderNo=$orderNo" -Headers @{Authorization="Bearer $token"} } catch { if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message | ConvertFrom-Json } else { throw } } }
$jobs = @(
    Start-Job -ScriptBlock $jobScript -ArgumentList $BackendUrl,$o3.orderNo,$u3.token
    Start-Job -ScriptBlock $jobScript -ArgumentList $BackendUrl,$o3.orderNo,$u3.token
)
Wait-Job $jobs | Out-Null
$cr = @($jobs | Receive-Job)
Remove-Job $jobs
AddResult "concurrentPay" ($(if ((@($cr | Where-Object { $_.code -eq 200 }).Count) -eq 1 -and (PaymentCount $o3.orderNo) -eq 1 -and (OrderSeatCount $o3.orderNo) -eq 1 -and ((UserPoints $u3.id) -eq ($p3Before - [int][Math]::Ceiling([double](OrderTotal $o3.orderNo))))) { "PASS" } else { "FAIL" })) @{
    orderNo=$o3.orderNo; responses=@($cr | ForEach-Object { "$($_.code):$($_.message)" }); paymentCount=(PaymentCount $o3.orderNo); pointsBefore=$p3Before; pointsAfter=(UserPoints $u3.id)
}

$u4 = NewUser "conflict"
$o4 = CreatePending $u4 $scheduleId
$p4Before = UserPoints $u4.id
DbExec "INSERT INTO order_seat(order_id, order_no, schedule_id, row_num, col_num, seat_label, create_time) VALUES (999999, 'PRESEED_$($o4.orderNo)', $scheduleId, $($o4.seat.row), $($o4.seat.col), 'preseed', NOW())"
$r41 = Api POST "/api/payment/pay?orderNo=$($o4.orderNo)" $null $u4.token
$countAfterConflict = PaymentCount $o4.orderNo
$pointsAfterConflict = UserPoints $u4.id
DbExec "DELETE FROM order_seat WHERE order_no = 'PRESEED_$($o4.orderNo)'"
$r42 = Api POST "/api/payment/pay?orderNo=$($o4.orderNo)" $null $u4.token
AddResult "orderSeatConflict" ($(if ($r41.code -eq 409 -and $countAfterConflict -eq 0 -and $pointsAfterConflict -eq $p4Before -and $r42.code -eq 200 -and (PaymentCount $o4.orderNo) -eq 1) { "PASS" } else { "FAIL" })) @{
    orderNo=$o4.orderNo; conflictCode=$r41.code; countAfterConflict=$countAfterConflict; pointsBefore=$p4Before; pointsAfterConflict=$pointsAfterConflict; retryCode=$r42.code; finalCount=(PaymentCount $o4.orderNo)
}

$u5 = NewUser "expired"
$o5 = CreatePending $u5 $scheduleId
$p5Before = UserPoints $u5.id
DbExec "UPDATE ticket_order SET expire_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE), update_time = NOW() WHERE order_no = '$($o5.orderNo)' AND status = 0"
$r5 = Api POST "/api/payment/pay?orderNo=$($o5.orderNo)" $null $u5.token
AddResult "expiredPay" ($(if ($r5.code -eq 409 -and (PaymentCount $o5.orderNo) -eq 0 -and (UserPoints $u5.id) -eq $p5Before) { "PASS" } else { "FAIL" })) @{
    orderNo=$o5.orderNo; code=$r5.code; message=$r5.message; paymentCount=(PaymentCount $o5.orderNo); pointsBefore=$p5Before; pointsAfter=(UserPoints $u5.id)
}

$u6 = NewUser "insufficient"
$o6 = CreatePending $u6 $scheduleId
DbExec "UPDATE sys_user SET points = 0 WHERE id = $($u6.id)"
$r6 = Api POST "/api/payment/pay?orderNo=$($o6.orderNo)" $null $u6.token
AddResult "insufficientPoints" ($(if ($r6.code -eq 400 -and (PaymentCount $o6.orderNo) -eq 0 -and (UserPoints $u6.id) -eq 0) { "PASS" } else { "FAIL" })) @{
    orderNo=$o6.orderNo; code=$r6.code; message=$r6.message; paymentCount=(PaymentCount $o6.orderNo); pointsAfter=(UserPoints $u6.id)
}

$u7 = NewUser "amount"
$o7 = CreatePending $u7 $scheduleId
$oldPrice = DbScalar "SELECT price FROM activity_session WHERE id = $scheduleId"
$orderTotal = OrderTotal $o7.orderNo
try {
    DbExec "UPDATE activity_session SET price = price + 10, update_time = NOW() WHERE id = $scheduleId"
    $r7 = Api POST "/api/payment/pay?orderNo=$($o7.orderNo)" $null $u7.token
    $payAmount = [decimal](PaymentRow $o7.orderNo).amount
    AddResult "snapshotAmount" ($(if ($r7.code -eq 200 -and $payAmount -eq $orderTotal) { "PASS" } else { "FAIL" })) @{
        orderNo=$o7.orderNo; orderTotal=$orderTotal; paymentAmount=$payAmount; oldSchedulePrice=$oldPrice
    }
} finally {
    DbExec "UPDATE activity_session SET price = $oldPrice, update_time = NOW() WHERE id = $scheduleId"
}

$overall = if (@($results | Where-Object { $_.result -ne "PASS" }).Count -eq 0) { "PASS" } else { "FAIL" }
[pscustomobject]@{ overall=$overall; scheduleId=$scheduleId; results=$results } | ConvertTo-Json -Depth 8
if ($overall -ne "PASS") { exit 1 }
