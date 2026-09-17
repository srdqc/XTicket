param(
    [Parameter(Mandatory = $true)][string]$Scenario,
    [Parameter(Mandatory = $true)][int]$VUs,
    [Parameter(Mandatory = $true)][int]$Run,
    [string]$Duration = '60s',
    [string]$WarmupDuration = '30s',
    [switch]$SkipWarmup,
    [switch]$WriteWorkload
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$benchmarkRoot = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $benchmarkRoot '..\..')).Path
$rawDir = Join-Path $benchmarkRoot 'results\raw'
$k6Exe = (Get-Command k6 -ErrorAction Stop).Source
$commit = (git -C $repoRoot rev-parse --short HEAD).Trim()
$name = '{0}-vu{1}-run{2}-{3}' -f $Scenario, $VUs, $Run, $commit
$scenarioPath = Join-Path $benchmarkRoot "scenarios\$Scenario.js"
$trendStats = '--summary-trend-stats=avg,min,med,p(90),p(95),p(99),max'

if ($VUs -lt 1) { throw 'VUs must be greater than zero' }
if (-not (Test-Path -LiteralPath $scenarioPath -PathType Leaf)) {
    throw "Scenario not found: $scenarioPath"
}
New-Item -ItemType Directory -Force $rawDir | Out-Null

function Assert-Summary(
    [string]$SummaryPath,
    [string[]]$RequiredMetrics,
    [int]$ExpectedVUs
) {
    if (-not (Test-Path -LiteralPath $SummaryPath -PathType Leaf)) {
        throw "Summary missing: $SummaryPath"
    }
    $summaryFile = Get-Item -LiteralPath $SummaryPath
    if ($summaryFile.Length -le 0) { throw "Summary is empty: $SummaryPath" }
    try {
        $summary = Get-Content -Raw -LiteralPath $SummaryPath | ConvertFrom-Json
    } catch {
        throw "Summary JSON parse failed: $SummaryPath - $($_.Exception.Message)"
    }
    if (-not $summary.metrics) { throw "Summary metrics missing: $SummaryPath" }
    $metricNames = @($summary.metrics.PSObject.Properties.Name)
    foreach ($metric in $RequiredMetrics) {
        if ($metricNames -notcontains $metric) {
            throw "Required metric missing: $metric"
        }
    }
    if ([long]$summary.metrics.http_reqs.count -le 0) {
        throw 'Measurement contains no HTTP requests'
    }
    if ([int]$summary.metrics.vus_max.max -ne $ExpectedVUs) {
        throw "VU metadata mismatch: expected=$ExpectedVUs actual=$($summary.metrics.vus_max.max)"
    }
}

function Invoke-K6Native(
    [string[]]$K6Args,
    [string]$LogPath,
    [string]$SummaryPath,
    [string[]]$RequiredMetrics,
    [int]$ExpectedVUs
) {
    if (Test-Path -LiteralPath $SummaryPath) {
        Remove-Item -LiteralPath $SummaryPath -Force
    }
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $script:k6Exe @K6Args *> $LogPath
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $savedPreference
    if ($null -eq $exitCode) {
        throw 'Runner infrastructure failure: k6 exit code is null'
    }
    if ($exitCode -ne 0) {
        throw "k6 run failed with exit code $exitCode; log=$LogPath"
    }
    Assert-Summary $SummaryPath $RequiredMetrics $ExpectedVUs
    return [int]$exitCode
}

function Invoke-Reset {
    $result = & (Join-Path $benchmarkRoot 'reset.ps1')
    if ($LASTEXITCODE -ne 0) { throw "Reset failed: $result" }
    return ($result | Out-String).Trim()
}

Push-Location $benchmarkRoot
try {
    if (-not $SkipWarmup) {
        $warmupSummary = Join-Path $rawDir "$name-warmup.json"
        $warmupLog = Join-Path $rawDir "$name-warmup.log"
        $warmupArgs = @(
            'run', '--quiet', $trendStats, "--summary-export=$warmupSummary",
            '-e', 'PHASE=warmup', '-e', "VUS=$VUs",
            '-e', "WARMUP_DURATION=$WarmupDuration", $scenarioPath
        )
        Invoke-K6Native $warmupArgs $warmupLog $warmupSummary `
            @('http_reqs', 'http_req_duration', 'vus_max') $VUs | Out-Null
        if ($WriteWorkload) { Invoke-Reset | Out-Null }
    }

    $summaryPath = Join-Path $rawDir "$name.json"
    $logPath = Join-Path $rawDir "$name.log"
    $measureArgs = @(
        'run', '--quiet', $trendStats, "--summary-export=$summaryPath",
        '-e', 'PHASE=measurement', '-e', "VUS=$VUs",
        '-e', "DURATION=$Duration", $scenarioPath
    )
    $required = @('http_reqs', 'http_req_duration', 'vus_max', 'app_success', 'system_error')
    if ($Scenario -eq 'transaction-flow') {
        $required += @('transaction_success', 'transaction_duration')
    }
    $exitCode = Invoke-K6Native $measureArgs $logPath $summaryPath $required $VUs
    if ($WriteWorkload) { Invoke-Reset | Out-Null }

    [pscustomobject]@{
        status = 'RUN_OK'
        scenario = $Scenario
        vus = $VUs
        duration = $Duration
        exitCode = $exitCode
        summary = $summaryPath
        log = $logPath
    } | ConvertTo-Json -Compress
} finally {
    Pop-Location
}
