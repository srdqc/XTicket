param(
    [int]$DrainTimeoutSeconds = 30
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$mysqlCommand = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw'

function Invoke-Db([string]$Sql) {
    $output = $Sql | docker compose exec -T mysql sh -lc $script:mysqlCommand 2>&1
    if ($LASTEXITCODE -ne 0) { throw (($output | Out-String).Trim()) }
    return @($output | Where-Object { $_ -and -not $_.StartsWith('mysql: [Warning]') })
}

function Get-Scalar([string]$Sql) {
    $rows = @(Invoke-Db $Sql)
    if ($rows.Count -lt 2) { return 0 }
    return [long](($rows[1] -split "`t")[0])
}

Push-Location $repoRoot
try {
    $drainSql = @'
SELECT COUNT(*)
FROM outbox_event e
JOIN ticket_order o
  ON CONVERT(o.order_no USING utf8mb4) COLLATE utf8mb4_unicode_ci
   = CONVERT(e.aggregate_id USING utf8mb4) COLLATE utf8mb4_unicode_ci
JOIN sys_user u ON u.id = o.user_id
WHERE LEFT(u.account, 11) = 'BENCH_USER_'
  AND e.status IN ('PENDING', 'PROCESSING', 'FAILED');
'@
    $deadline = (Get-Date).AddSeconds($DrainTimeoutSeconds)
    do {
        $remaining = Get-Scalar $drainSql
        if ($remaining -eq 0) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if ($remaining -ne 0) {
        throw "Outbox drain timed out with $remaining benchmark events; benchmark is invalid"
    }

    $resetSql = Get-Content -Raw (Join-Path $PSScriptRoot 'fixtures\reset.sql')
    Invoke-Db $resetSql | Out-Null

    $userRows = @(Invoke-Db "SELECT id FROM sys_user WHERE LEFT(account, 11) = 'BENCH_USER_' ORDER BY id;")
    $userIds = @($userRows | Select-Object -Skip 1 | ForEach-Object { [long](($_ -split "`t")[0]) })
    foreach ($sessionId in 910001..910008) {
        docker compose exec -T redis redis-cli DEL "schedule:stock:$sessionId" "session:detail:$sessionId" | Out-Null
        docker compose exec -T redis redis-cli SET "schedule:stock:$sessionId" 20000 EX 86400 | Out-Null
    }
    $userIdSet = @{}
    foreach ($userId in $userIds) { $userIdSet[[string]$userId] = $true }
    $rateLimitKeys = @(docker compose exec -T redis redis-cli --scan --pattern 'rate_limit:*:user:*')
    $ownedRateLimitKeys = @($rateLimitKeys | Where-Object {
        $_ -match ':user:(\d+):' -and $userIdSet.ContainsKey($Matches[1])
    })
    if ($ownedRateLimitKeys.Count -gt 0) {
        docker compose exec -T redis redis-cli DEL $ownedRateLimitKeys | Out-Null
    }

    [pscustomobject]@{
        status = 'RESET_OK'
        users = $userIds.Count
        sessions = 8
        stockPerSession = 20000
        outboxRemaining = 0
    } | ConvertTo-Json -Compress
} finally {
    Pop-Location
}
