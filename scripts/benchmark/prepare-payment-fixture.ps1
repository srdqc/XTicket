param(
    [Parameter(Mandatory = $true)][ValidateSet('same-user', 'different-users')][string]$Mode,
    [Parameter(Mandatory = $true)][ValidateSet(1, 3, 6)][int]$SeatCount,
    [Parameter(Mandatory = $true)][ValidateRange(1, 6000)][int]$OrderCount,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_-]+$')][string]$RunLabel,
    [string]$TokenFile = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$rawDir = Join-Path $PSScriptRoot 'results\raw'
$tokenPath = if ([string]::IsNullOrWhiteSpace($TokenFile)) {
    Join-Path $rawDir 'tokens.json'
} else { (Resolve-Path $TokenFile).Path }
$tokens = @(Get-Content -Raw -LiteralPath $tokenPath | ConvertFrom-Json)
if ($tokens.Count -lt 1) { throw 'No benchmark tokens available' }
$utf8NoBom = New-Object Text.UTF8Encoding($false)
$orders = @()
$orderValues = @()
$lockValues = @()

for ($i = 0; $i -lt $OrderCount; $i++) {
    $user = if ($Mode -eq 'same-user') { $tokens[0] } else { $tokens[$i % $tokens.Count] }
    $orderNo = 'BP{0}{1:D6}' -f $RunLabel, ($i + 1)
    $lockToken = 'BPL{0}{1:D6}' -f $RunLabel, ($i + 1)
    $absoluteSeat = $i * $SeatCount
    $sessionOffset = [Math]::Floor($absoluteSeat / 20000)
    if ($sessionOffset -gt 7) { throw 'Payment fixture exceeds reserved session capacity' }
    $sessionId = 910001 + $sessionOffset
    $seatStart = $absoluteSeat % 20000
    $labels = @()
    for ($seatOffset = 0; $seatOffset -lt $SeatCount; $seatOffset++) {
        $ordinal = $seatStart + $seatOffset
        $row = [Math]::Floor($ordinal / 200) + 1
        $col = ($ordinal % 200) + 1
        $labels += "${row},${col}"
        $lockValues += "($sessionId,$row,$col,$([long]$user.id),'$lockToken','$orderNo',DATE_ADD(NOW(),INTERVAL 30 MINUTE),1,NOW(),NOW())"
    }
    $seatsInfo = $labels -join ';'
    $orderValues += "('$orderNo',$([long]$user.id),$sessionId,'$lockToken','BENCH_ACTIVITY_001','BENCH_VENUE_001','BENCH_HALL','2099-01-01 10:00',$SeatCount,'$seatsInfo',1.00,$SeatCount,0,DATE_ADD(NOW(),INTERVAL 30 MINUTE),NOW(),NOW(),0)"
    $orders += [pscustomobject]@{ orderNo = $orderNo; id = [long]$user.id; token = [string]$user.token }
}

$sql = @"
SET NAMES utf8mb4;
INSERT INTO ticket_order (order_no,user_id,schedule_id,lock_token,movie_name,cinema_name,hall_name,show_time,seat_count,seats_info,unit_price,total_price,status,expire_time,create_time,update_time,deleted) VALUES
$($orderValues -join ",`n");
INSERT INTO seat_lock (schedule_id,row_num,col_num,user_id,lock_token,order_no,lock_until,status,create_time,update_time) VALUES
$($lockValues -join ",`n");
INSERT INTO outbox_event (event_id,aggregate_type,aggregate_id,event_type,topic,tag,payload,status,retry_count,next_retry_time,published_at,create_time,update_time)
SELECT UUID(),'ORDER',o.order_no,'CREATED','ORDER_TOPIC','ORDER_CREATED',JSON_OBJECT('orderNo',o.order_no,'userId',o.user_id),'PUBLISHED',0,NOW(),NOW(),NOW(),NOW()
FROM ticket_order o WHERE o.order_no LIKE 'BP$RunLabel%';
INSERT INTO consumed_event (consumer_group,event_id,consumed_at)
SELECT 'maoyan_order_consumer_group',event_id,NOW() FROM outbox_event WHERE aggregate_id LIKE 'BP$RunLabel%';
"@

New-Item -ItemType Directory -Force $rawDir | Out-Null
$orderFile = Join-Path $rawDir "payment-orders-$RunLabel.json"
[IO.File]::WriteAllText($orderFile, ($orders | ConvertTo-Json -Depth 3), $utf8NoBom)
Push-Location $repoRoot
try {
    $mysql = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw'
    $output = $sql | docker compose exec -T mysql sh -lc $mysql 2>&1
    if ($LASTEXITCODE -ne 0) { throw (($output | Out-String).Trim()) }
} finally { Pop-Location }
[pscustomobject]@{ status='PAYMENT_FIXTURE_OK'; mode=$Mode; seats=$SeatCount; orders=$OrderCount; orderFile=$orderFile } | ConvertTo-Json -Compress
