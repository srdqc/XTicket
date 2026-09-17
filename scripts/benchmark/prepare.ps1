param(
    [string]$BaseUrl = 'http://localhost',
    [int]$UserCount = 100,
    [string]$Password = 'Bench_Local_123456'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$uri = [Uri]$BaseUrl
if (@('localhost', '127.0.0.1', '::1') -notcontains $uri.Host) {
    throw 'Refusing to prepare benchmark data against a non-local host'
}
if ($UserCount -lt 1 -or $UserCount -gt 200) {
    throw 'UserCount must be between 1 and 200'
}

function Invoke-Api([string]$Method, [string]$Path, $Body) {
    $json = $Body | ConvertTo-Json -Depth 8 -Compress
    try {
        return Invoke-RestMethod -Method $Method -Uri "$BaseUrl$Path" -Body $json -ContentType 'application/json'
    } catch {
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            return ($_.ErrorDetails.Message | ConvertFrom-Json)
        }
        throw
    }
}

Push-Location $repoRoot
try {
    $mysqlCommand = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" --default-character-set=utf8mb4 --batch --raw'
    $setupSql = Get-Content -Raw (Join-Path $PSScriptRoot 'fixtures\setup.sql')
    $dbOutput = $setupSql | docker compose exec -T mysql sh -lc $mysqlCommand 2>&1
    if ($LASTEXITCODE -ne 0) { throw (($dbOutput | Out-String).Trim()) }

    $users = @()
    for ($i = 1; $i -le $UserCount; $i++) {
        $account = 'BENCH_USER_{0:D4}' -f $i
        $login = Invoke-Api POST '/api/auth/login' @{ account = $account; password = $Password }
        if ($login.code -ne 200) {
            $register = Invoke-Api POST '/api/auth/register' @{
                account = $account
                password = $Password
                userNick = $account
                inviteCode = 'lpf'
            }
            if ($register.code -ne 200 -and $register.code -ne 409) {
                throw "Register failed for ${account}: $($register.message)"
            }
            $login = Invoke-Api POST '/api/auth/login' @{ account = $account; password = $Password }
        }
        if ($login.code -ne 200) { throw "Login failed for ${account}: $($login.message)" }
        $users += [pscustomobject]@{
            id = [long]$login.data.id
            account = $account
            token = [string]$login.data.token
        }
    }

    $resetResult = & (Join-Path $PSScriptRoot 'reset.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark reset failed during prepare' }

    $rawDir = Join-Path $PSScriptRoot 'results\raw'
    New-Item -ItemType Directory -Force $rawDir | Out-Null
    $tokenPath = Join-Path $rawDir 'tokens.json'
    $users | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 $tokenPath

    [pscustomobject]@{
        status = 'PREPARE_OK'
        users = $users.Count
        sessions = 8
        seatsPerSession = 20000
        tokenFile = $tokenPath
        reset = ($resetResult | Out-String).Trim()
    } | ConvertTo-Json -Compress
} finally {
    Pop-Location
}
