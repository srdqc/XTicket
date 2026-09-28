param([long]$SessionId = 910001L)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$benchmark = Join-Path $PSScriptRoot 'benchmark'

function Invoke-Scalar([string]$Sql) {
    $command = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw --skip-column-names'
    $output = @($Sql | docker compose exec -T mysql sh -lc $command 2>&1)
    if ($LASTEXITCODE -ne 0) { throw (($output | Out-String).Trim()) }
    $rows = @($output | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -ne '' })
    if ($rows.Count -ne 1) { throw "Expected one scalar row, got $($rows.Count)" }
    return [long]$rows[0]
}

function Set-Stock([long]$Value) {
    $affected = Invoke-Scalar "UPDATE activity_session SET available_seats=$Value, version=0 WHERE id=$SessionId AND deleted=0; SELECT ROW_COUNT()"
    if ($affected -ne 1) { throw "Fixture session $SessionId unavailable" }
}

function Invoke-Decrement([long]$TargetId, [int]$Count) {
    return Invoke-Scalar "UPDATE activity_session SET available_seats=available_seats-$Count, version=version+1, update_time=CURRENT_TIMESTAMP WHERE id=$TargetId AND available_seats >= $Count AND deleted=0; SELECT ROW_COUNT()"
}

function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) { throw "$Message actual=$Actual expected=$Expected" }
}

function Test-Concurrent([int]$Workers) {
    Set-Stock 100
    $startAt = [DateTime]::UtcNow.AddSeconds(2)
    $jobs = @()
    foreach ($worker in 1..$Workers) {
        $jobs += Start-Job -ScriptBlock {
            param($repo, $session, $start)
            Set-Location $repo
            while ([DateTime]::UtcNow -lt $start) { Start-Sleep -Milliseconds 10 }
            $sql = "UPDATE activity_session SET available_seats=available_seats-1, version=version+1, update_time=CURRENT_TIMESTAMP WHERE id=$session AND available_seats >= 1 AND deleted=0; SELECT ROW_COUNT();"
            $command = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw --skip-column-names'
            $output = @($sql | docker compose exec -T mysql sh -lc $command 2>&1)
            if ($LASTEXITCODE -ne 0) { throw (($output | Out-String).Trim()) }
            @($output | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -ne '' })
        } -ArgumentList $root, $SessionId, $startAt
    }
    $done = Wait-Job -Job $jobs -Timeout 60
    if (@($done).Count -ne $Workers) { $jobs | Stop-Job; $jobs | Remove-Job -Force; throw "$Workers concurrent decrements timed out" }
    $rows = @($jobs | Receive-Job)
    $jobs | Remove-Job -Force
    Assert-Equal (@($rows | Where-Object { $_ -eq '1' }).Count) $Workers "$Workers affected-row results"
    Assert-Equal (Invoke-Scalar "SELECT available_seats FROM activity_session WHERE id=$SessionId") (100 - $Workers) "$Workers final stock"
    Assert-Equal (Invoke-Scalar "SELECT version FROM activity_session WHERE id=$SessionId") $Workers "$Workers final version"
}

Push-Location $root
try {
    $reset = & (Join-Path $benchmark 'reset.ps1')
    if ($LASTEXITCODE -ne 0) { throw (($reset | Out-String).Trim()) }

    Set-Stock 10
    Assert-Equal (Invoke-Decrement $SessionId 3) 1 'sufficient decrement affected rows'
    Assert-Equal (Invoke-Scalar "SELECT available_seats FROM activity_session WHERE id=$SessionId") 7 'sufficient final stock'

    Set-Stock 3
    Assert-Equal (Invoke-Decrement $SessionId 3) 1 'exact decrement affected rows'
    Assert-Equal (Invoke-Scalar "SELECT available_seats FROM activity_session WHERE id=$SessionId") 0 'exact final stock'

    Set-Stock 2
    Assert-Equal (Invoke-Decrement $SessionId 3) 0 'insufficient decrement affected rows'
    Assert-Equal (Invoke-Scalar "SELECT available_seats FROM activity_session WHERE id=$SessionId") 2 'insufficient final stock'

    Assert-Equal (Invoke-Decrement 999999999 1) 0 'missing session affected rows'
    Test-Concurrent 8
    Test-Concurrent 25
    [ordered]@{ sufficient = 'PASS'; exact = 'PASS'; insufficient = 'PASS'; missing = 'PASS'; concurrent8 = 'PASS:100->92'; concurrent25 = 'PASS:100->75' } | ConvertTo-Json -Compress
} finally {
    $reset = & (Join-Path $benchmark 'reset.ps1')
    if ($LASTEXITCODE -ne 0) { Write-Error (($reset | Out-String).Trim()) }
    Pop-Location
}
