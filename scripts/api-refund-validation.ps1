param([string]$BackendUrl = "http://localhost")

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ts = Get-Date -Format "yyyyMMddHHmmss"
$password = "Phase4C_Refund_123456"

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

function RedisGet([long]$scheduleId) {
    $out = & $script:docker compose exec -T redis redis-cli GET "schedule:stock:$scheduleId" 2>&1
    if ($LASTEXITCODE -ne 0) { throw (($out | Out-String).Trim()) }
    return [int](($out | Out-String).Trim())
}

function Api([string]$method, [string]$path, $body, [string]$token) {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    try {
        if ($null -ne $body) {
            $json = $body | ConvertTo-Json -Depth 12 -Compress
            return Invoke-RestMethod -Method $method -Uri "$BackendUrl$path" -Headers $headers -Body $json -ContentType "application/json"
        }
        return Invoke-RestMethod -Method $method -Uri "$BackendUrl$path" -Headers $headers
    } catch {
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { return ($_.ErrorDetails.Message | ConvertFrom-Json) }
        throw
    }
}

function NewUser([string]$suffix) {
    $account = "p4c_${ts}_$suffix"
    Api POST "/api/auth/register" @{ account=$account; password=$password; userNick=$account; inviteCode="lpf" } $null | Out-Null
    $login = Api POST "/api/auth/login" @{ account=$account; password=$password } $null
    if ($login.code -ne 200) { throw "Login failed: $($login.message)" }
    return [pscustomobject]@{ id=[long]$login.data.id; account=$account; token=[string]$login.data.token }
}

function FreeSeat($user, [long]$scheduleId, $excluded) {
    $layout = Api GET "/api/seat/layout?scheduleId=$scheduleId" $null $user.token
    foreach ($row in $layout.data.seats) {
        foreach ($seat in $row) {
            $isExcluded = $excluded -and [int]$seat.row -eq [int]$excluded.row -and [int]$seat.col -eq [int]$excluded.col
            if ([int]$seat.status -eq 0 -and -not $isExcluded) { return [pscustomobject]@{ row=[int]$seat.row; col=[int]$seat.col } }
        }
    }
    throw "No free seat"
}

function CreatePaid($user, [long]$scheduleId, $seat) {
    $seats = @(@{ row=[int]$seat.row; col=[int]$seat.col })
    $lock = Api POST "/api/seat/lock" @{ scheduleId=$scheduleId; seats=$seats } $user.token
    if ($lock.code -ne 200) { throw "Lock failed: $($lock.message)" }
    $create = Api POST "/api/order/create" @{ scheduleId=$scheduleId; lockToken=$lock.data.lockToken; seats=$seats; seatCount=1; seatsInfo="$($seat.row),$($seat.col)" } $user.token
    if ($create.code -ne 200) { throw "Create failed: $($create.message)" }
    $pay = Api POST "/api/payment/pay?orderNo=$($create.data.orderNo)" $null $user.token
    if ($pay.code -ne 200) { throw "Pay failed: $($pay.message)" }
    $tickets = Api GET "/api/tickets?orderNo=$($create.data.orderNo)" $null $user.token
    return [pscustomobject]@{ orderNo=[string]$create.data.orderNo; ticketNo=[string]$tickets.data[0].ticketNo; seat=$seat }
}

function Points([long]$userId) { return [int](DbScalar "SELECT points FROM sys_user WHERE id=$userId") }
function DbStock([long]$scheduleId) { return [int](DbScalar "SELECT available_seats FROM activity_session WHERE id=$scheduleId") }

$uri = [Uri]$BackendUrl
if (@("localhost", "127.0.0.1", "::1") -notcontains $uri.Host) { throw "Refusing non-local backend" }
$scheduleId = [long](DbScalar "SELECT id FROM activity_session WHERE status=1 AND deleted=0 AND available_seats>5 AND price<=200 AND TIMESTAMP(show_date,STR_TO_DATE(show_time,'%H:%i'))>NOW() ORDER BY available_seats DESC,id LIMIT 1")
$buyer = NewUser "buyer"
$usedBuyer = NewUser "used"
$staff = NewUser "staff"
DbExec "UPDATE sys_user SET role='CHECKIN_STAFF' WHERE id=$($staff.id)"

$points0 = Points $buyer.id
$db0 = DbStock $scheduleId
$redis0 = RedisGet $scheduleId
$seat = FreeSeat $buyer $scheduleId $null
$order = CreatePaid $buyer $scheduleId $seat
$pointsPaid = Points $buyer.id
$dbPaid = DbStock $scheduleId
$redisPaid = RedisGet $scheduleId
$refund1 = Api POST "/api/order/refund/$($order.orderNo)" $null $buyer.token
$refundTime1 = DbScalar "SELECT DATE_FORMAT(invalidated_at,'%Y-%m-%d %H:%i:%s.%f') FROM electronic_ticket WHERE ticket_no='$($order.ticketNo)'"
$refund2 = Api POST "/api/order/refund/$($order.orderNo)" $null $buyer.token
$refundTime2 = DbScalar "SELECT DATE_FORMAT(invalidated_at,'%Y-%m-%d %H:%i:%s.%f') FROM electronic_ticket WHERE ticket_no='$($order.ticketNo)'"

$refundFacts = [pscustomobject]@{
    firstCode=$refund1.code; secondCode=$refund2.code
    orderStatus=[int](DbScalar "SELECT status FROM ticket_order WHERE order_no='$($order.orderNo)'")
    refundTime=(DbScalar "SELECT refund_time FROM ticket_order WHERE order_no='$($order.orderNo)'")
    refundRecords=[int](DbScalar "SELECT COUNT(*) FROM refund_record WHERE order_no='$($order.orderNo)'")
    ticketStatus=[int](DbScalar "SELECT status FROM electronic_ticket WHERE ticket_no='$($order.ticketNo)'")
    invalidatedAtStable=($refundTime1 -eq $refundTime2)
    activeSeats=[int](DbScalar "SELECT COUNT(*) FROM order_seat WHERE order_no='$($order.orderNo)' AND active_sale_marker=1")
    historicalSeats=[int](DbScalar "SELECT COUNT(*) FROM order_seat WHERE order_no='$($order.orderNo)' AND active_sale_marker IS NULL")
    purchasedLocks=[int](DbScalar "SELECT COUNT(*) FROM seat_lock WHERE order_no='$($order.orderNo)' AND status=2")
    points0=$points0; pointsPaid=$pointsPaid; pointsAfter=(Points $buyer.id)
    db0=$db0; dbPaid=$dbPaid; dbAfter=(DbStock $scheduleId)
    redis0=$redis0; redisPaid=$redisPaid; redisAfter=(RedisGet $scheduleId)
}

$invalidatedCheckIn = Api POST "/api/checkin/tickets/$($order.ticketNo)" @{ sessionId=$scheduleId } $staff.token

$usedSeat = FreeSeat $usedBuyer $scheduleId $seat
$usedOrder = CreatePaid $usedBuyer $scheduleId $usedSeat
$usedPointsPaid = Points $usedBuyer.id
$checkIn = Api POST "/api/checkin/tickets/$($usedOrder.ticketNo)" @{ sessionId=$scheduleId } $staff.token
$usedRefund = Api POST "/api/order/refund/$($usedOrder.orderNo)" $null $usedBuyer.token
$usedFacts = [pscustomobject]@{
    checkInCode=$checkIn.code; refundCode=$usedRefund.code; refundMessage=$usedRefund.message
    orderStatus=[int](DbScalar "SELECT status FROM ticket_order WHERE order_no='$($usedOrder.orderNo)'")
    ticketStatus=[int](DbScalar "SELECT status FROM electronic_ticket WHERE ticket_no='$($usedOrder.ticketNo)'")
    pointsPaid=$usedPointsPaid; pointsAfter=(Points $usedBuyer.id)
    refundRecords=[int](DbScalar "SELECT COUNT(*) FROM refund_record WHERE order_no='$($usedOrder.orderNo)'")
}

$resale = CreatePaid $buyer $scheduleId $seat
$resaleFacts = [pscustomobject]@{
    orderNo=$resale.orderNo
    oldHistorical=[int](DbScalar "SELECT COUNT(*) FROM order_seat WHERE order_no='$($order.orderNo)' AND active_sale_marker IS NULL")
    newActive=[int](DbScalar "SELECT COUNT(*) FROM order_seat WHERE order_no='$($resale.orderNo)' AND active_sale_marker=1")
    sameSeatHistory=[int](DbScalar "SELECT COUNT(*) FROM order_seat WHERE schedule_id=$scheduleId AND row_num=$($seat.row) AND col_num=$($seat.col)")
}

$refundPass = $refundFacts.firstCode -eq 200 -and $refundFacts.secondCode -eq 200 -and $refundFacts.orderStatus -eq 3 -and $refundFacts.refundRecords -eq 1 -and $refundFacts.ticketStatus -eq 2 -and $refundFacts.invalidatedAtStable -and $refundFacts.activeSeats -eq 0 -and $refundFacts.historicalSeats -eq 1 -and $refundFacts.purchasedLocks -eq 0 -and $refundFacts.pointsAfter -eq $points0 -and $refundFacts.dbAfter -eq $db0 -and $refundFacts.redisAfter -eq $redis0
$invalidatedPass = $invalidatedCheckIn.code -eq 409 -and $refundFacts.ticketStatus -eq 2
$usedPass = $checkIn.code -eq 200 -and $usedRefund.code -eq 409 -and $usedFacts.orderStatus -eq 1 -and $usedFacts.ticketStatus -eq 1 -and $usedFacts.pointsAfter -eq $usedPointsPaid -and $usedFacts.refundRecords -eq 0
$resalePass = $resaleFacts.oldHistorical -eq 1 -and $resaleFacts.newActive -eq 1 -and $resaleFacts.sameSeatHistory -eq 2
$overall = if ($refundPass -and $invalidatedPass -and $usedPass -and $resalePass) { "PASS" } else { "FAIL" }

[pscustomobject]@{
    overall=$overall; scheduleId=$scheduleId; seat="$($seat.row),$($seat.col)"
    users=@($buyer.account,$usedBuyer.account,$staff.account)
    refund=$refundFacts; invalidatedCheckInCode=$invalidatedCheckIn.code; usedRefund=$usedFacts; resale=$resaleFacts
} | ConvertTo-Json -Depth 8
if ($overall -ne "PASS") { exit 1 }
