param(
    [Parameter(Mandatory = $true)][string]$Scenario,
    [Parameter(Mandatory = $true)][int]$VUs,
    [Parameter(Mandatory = $true)][int]$Run,
    [string]$Duration = '60s',
    [string]$WarmupDuration = '30s',
    [int]$SamplerIntervalSeconds = 2,
    [switch]$SkipWarmup,
    [switch]$WriteWorkload,
    [switch]$CaptureOrderCreateProfile,
    [switch]$CapturePaymentProfile,
    [string]$PaymentOrderFile = ''
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

function Invoke-Reset([string]$DrainStartedAtUtc) {
    $result = & (Join-Path $benchmarkRoot 'reset.ps1') -DrainStartedAtUtc $DrainStartedAtUtc
    if ($LASTEXITCODE -ne 0) { throw "Reset failed: $result" }
    return (($result | Out-String).Trim() | ConvertFrom-Json)
}

function Start-MetricsSampler(
    [string]$OutputPath,
    [string]$SummaryPath,
    [string]$StopPath
) {
    if (Test-Path -LiteralPath $StopPath) { Remove-Item -LiteralPath $StopPath -Force }
    return Start-Job -FilePath (Join-Path $benchmarkRoot 'metrics-sampler.ps1') -ArgumentList @(
        $OutputPath, $SummaryPath, $StopPath, $SamplerIntervalSeconds, $repoRoot
    )
}

function Stop-MetricsSampler($Job, [string]$StopPath, [string]$SummaryPath) {
    New-Item -ItemType File -Path $StopPath -Force | Out-Null
    $completed = Wait-Job -Job $Job -Timeout 20
    if ($null -eq $completed) {
        Stop-Job -Job $Job
        Remove-Job -Job $Job -Force
        throw 'Metrics sampler did not stop within 20 seconds'
    }
    $jobOutput = @(Receive-Job -Job $Job -ErrorAction SilentlyContinue)
    $jobState = $Job.State
    $jobReason = $Job.ChildJobs[0].JobStateInfo.Reason
    Remove-Job -Job $Job -Force
    Remove-Item -LiteralPath $StopPath -Force -ErrorAction SilentlyContinue
    if ($jobState -ne 'Completed') {
        throw "Metrics sampler failed: $jobState $jobReason $($jobOutput -join ' ')"
    }
    if (-not (Test-Path -LiteralPath $SummaryPath -PathType Leaf)) {
        throw "Metrics sampler summary missing: $SummaryPath"
    }
    return (Get-Content -Raw -LiteralPath $SummaryPath | ConvertFrom-Json)
}

function Save-OrderCreateProfile([string]$OutputPath) {
    $composePath = Join-Path $repoRoot 'docker-compose.yml'
    $profileOutput = & docker compose -f $composePath exec -T backend `
        wget -qO- http://127.0.0.1:8080/actuator/ordercreateprofile 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Order Create profile capture failed: $($profileOutput -join ' ')"
    }
    $profileJson = ($profileOutput | Out-String).Trim()
    try {
        $profile = $profileJson | ConvertFrom-Json
    } catch {
        throw "Order Create profile JSON parse failed: $($_.Exception.Message)"
    }
    $criticalStages = @(
        'request_validation', 'redisson_wait', 'load_session',
        'idempotency_check', 'verify_locks', 'load_snapshot', 'redis_stock',
        'pre_stock', 'db_stock_update', 'post_stock_to_tx_end',
        'order_insert', 'bind_locks', 'refresh_cache',
        'outbox_insert', 'response_mapping', 'tx_completion', 'response_enrichment'
    )
    foreach ($stage in $criticalStages) {
        $property = $profile.PSObject.Properties[$stage]
        if ($null -eq $property -or [long]$property.Value.count -le 0) {
            throw "Order Create profile stage count is zero or missing: $stage"
        }
    }
    [IO.File]::WriteAllText($OutputPath, $profileJson, [Text.UTF8Encoding]::new($false))
    return $profile
}

function Save-PaymentProfile([string]$OutputPath) {
    $composePath = Join-Path $repoRoot 'docker-compose.yml'
    $profileOutput = & docker compose -f $composePath exec -T backend `
        wget -qO- http://127.0.0.1:8080/actuator/paymentprofile 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Payment profile capture failed: $($profileOutput -join ' ')" }
    $profileJson = ($profileOutput | Out-String).Trim()
    try { $profile = $profileJson | ConvertFrom-Json }
    catch { throw "Payment profile JSON parse failed: $($_.Exception.Message)" }
    $criticalStages = @(
        'payment_idempotency', 'payment_order_load', 'payment_seat_lock_load',
        'payment_points_debit', 'payment_order_transition', 'payment_order_seat',
        'payment_record_write', 'payment_ticket_issue', 'payment_outbox_insert',
        'payment_response_mapping', 'payment_tx_completion'
    )
    foreach ($stage in $criticalStages) {
        $property = $profile.PSObject.Properties[$stage]
        if ($null -eq $property -or [long]$property.Value.count -le 0) {
            throw "Payment profile stage count is zero or missing: $stage"
        }
    }
    [IO.File]::WriteAllText($OutputPath, $profileJson, [Text.UTF8Encoding]::new($false))
    return $profile
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
        if ($WriteWorkload) {
            Invoke-Reset ([DateTimeOffset]::UtcNow.ToString('o')) | Out-Null
        }
    }

    $summaryPath = Join-Path $rawDir "$name.json"
    $logPath = Join-Path $rawDir "$name.log"
    $measureArgs = @(
        'run', '--quiet', $trendStats, "--summary-export=$summaryPath",
        '-e', 'PHASE=measurement', '-e', "VUS=$VUs",
        '-e', "DURATION=$Duration", $scenarioPath
    )
    if (-not [string]::IsNullOrWhiteSpace($PaymentOrderFile)) {
        $measureArgs = $measureArgs[0..($measureArgs.Count - 2)] + @('-e', "PAYMENT_ORDER_FILE=$PaymentOrderFile") + $measureArgs[-1]
    }
    $required = @('http_reqs', 'http_req_duration', 'vus_max', 'app_success', 'system_error')
    if ($Scenario -eq 'transaction-flow') {
        $required += @('transaction_success', 'transaction_duration')
    }
    if ($Scenario -eq 'payment-only') {
        $required += @('payment_only_success', 'payment_only_duration')
    }
    $metricsPath = Join-Path $rawDir "$name-metrics.jsonl"
    $metricsSummaryPath = Join-Path $rawDir "$name-metrics-summary.json"
    $samplerStopPath = Join-Path $rawDir "$name-metrics.stop"
    $samplerJob = Start-MetricsSampler $metricsPath $metricsSummaryPath $samplerStopPath
    try {
        $exitCode = Invoke-K6Native $measureArgs $logPath $summaryPath $required $VUs
        $measurementEndedAt = [DateTimeOffset]::UtcNow.ToString('o')
    } finally {
        $metricsSummary = Stop-MetricsSampler $samplerJob $samplerStopPath $metricsSummaryPath
    }
    $profilePath = $null
    $profileSnapshot = $null
    if ($CaptureOrderCreateProfile) {
        $profilePath = Join-Path $rawDir "$name-order-create-profile.json"
        $profileSnapshot = Save-OrderCreateProfile $profilePath
    }
    $paymentProfilePath = $null
    $paymentProfileSnapshot = $null
    if ($CapturePaymentProfile) {
        $paymentProfilePath = Join-Path $rawDir "$name-payment-profile.json"
        $paymentProfileSnapshot = Save-PaymentProfile $paymentProfilePath
    }
    $cleanup = $null
    if ($WriteWorkload) { $cleanup = Invoke-Reset $measurementEndedAt }

    [pscustomobject]@{
        status = 'RUN_OK'
        scenario = $Scenario
        vus = $VUs
        duration = $Duration
        exitCode = $exitCode
        summary = $summaryPath
        log = $logPath
        metrics = $metricsSummaryPath
        metricsSummary = $metricsSummary
        orderCreateProfile = $profilePath
        orderCreateProfileSnapshot = $profileSnapshot
        paymentProfile = $paymentProfilePath
        paymentProfileSnapshot = $paymentProfileSnapshot
        cleanup = $cleanup
    } | ConvertTo-Json -Compress
} finally {
    Pop-Location
}
