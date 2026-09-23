param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Quick', 'Formal')]
    [string]$Mode
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runId = 'phase6c-8-{0}-{1}' -f $Mode.ToLowerInvariant(), (Get-Date -Format 'yyyyMMdd-HHmmss')
$runDir = Join-Path $PSScriptRoot "results\$runId"
$summaryPath = Join-Path $runDir 'summary.json'
$utf8NoBom = New-Object Text.UTF8Encoding($false)
$previousConcurrency = $env:MAOYAN_OUTBOX_PUBLISHER_CONCURRENCY
$script:lastDiagnostic = ''
New-Item -ItemType Directory -Force $runDir | Out-Null

function Save-Json([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 12 -Compress), $script:utf8NoBom)
}

function Json-Line($Output, [string]$Diagnostic) {
    $line = @($Output | Where-Object { $_ -and ([string]$_).Trim().StartsWith('{') }) | Select-Object -Last 1
    if (-not $line) { throw "Command returned no JSON; diagnostic=$Diagnostic" }
    return ($line | ConvertFrom-Json)
}

function Restart-Backend([int]$Concurrency, [string]$Label) {
    $env:MAOYAN_OUTBOX_PUBLISHER_CONCURRENCY = [string]$Concurrency
    $log = Join-Path $script:runDir "$Label-docker.log"
    $script:lastDiagnostic = $log
    $saved = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & docker compose -f (Join-Path $script:root 'docker-compose.yml') up -d --force-recreate backend *> $log
    $code = $LASTEXITCODE; $ErrorActionPreference = $saved
    if ($code -ne 0) { throw "Backend recreate failed; diagnostic=$log" }
    $deadline = (Get-Date).AddSeconds(90)
    $saved = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    do {
        $health = & docker exec maoyan-backend wget -qO- http://127.0.0.1:8080/actuator/health 2>$null
        if ($LASTEXITCODE -eq 0 -and $health -match '"status":"UP"') { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    $ErrorActionPreference = $saved
    if (-not ($health -match '"status":"UP"')) { throw "Backend health timeout; diagnostic=$log" }
}

function Start-Sampler([string]$Label) {
    $output = Join-Path $script:runDir "$Label-metrics.jsonl"
    $summary = Join-Path $script:runDir "$Label-metrics-summary.json"
    $stop = Join-Path $script:runDir "$Label-metrics.stop"
    if (Test-Path -LiteralPath $stop) { Remove-Item -LiteralPath $stop -Force }
    $job = Start-Job -FilePath (Join-Path $PSScriptRoot 'metrics-sampler.ps1') `
        -ArgumentList @($output, $summary, $stop, 2, $script:root)
    return [pscustomobject]@{ job=$job; output=$output; summary=$summary; stop=$stop }
}

function Stop-Sampler($Sampler) {
    New-Item -ItemType File -Path $Sampler.stop -Force | Out-Null
    $completed = Wait-Job -Job $Sampler.job -Timeout 20
    if ($null -eq $completed) { Stop-Job $Sampler.job; throw 'Metrics sampler stop timeout' }
    $output = @(Receive-Job $Sampler.job -ErrorAction SilentlyContinue)
    $state = $Sampler.job.State; Remove-Job $Sampler.job -Force
    Remove-Item -LiteralPath $Sampler.stop -Force -ErrorAction SilentlyContinue
    if ($state -ne 'Completed') { throw "Metrics sampler failed: $state $($output -join ' ')" }
    return (Get-Content -Raw -LiteralPath $Sampler.summary | ConvertFrom-Json)
}

function Invoke-Drain([int]$Concurrency) {
    $label = "c$Concurrency"
    Restart-Backend $Concurrency $label
    $sampler = Start-Sampler $label
    $log = Join-Path $script:runDir "$label-drain.log"
    $script:lastDiagnostic = $log
    try {
        $unique = 'P8{0}{1}' -f $Concurrency, (Get-Date -Format 'HHmmss')
        $output = & (Join-Path $script:root 'scripts\outbox-drain-throughput-validation.ps1') `
            -Count 3000 -RunLabel $unique 2>&1
        $code = $LASTEXITCODE
        [IO.File]::WriteAllText($log, (($output | Out-String).Trim()), $script:utf8NoBom)
        if ($null -ne $code -and $code -ne 0) { throw "Drain workload failed; diagnostic=$log" }
        $drain = Json-Line $output $log
    } finally {
        $metrics = Stop-Sampler $sampler
    }
    return [pscustomobject]@{
        concurrency=$Concurrency; eventsPerSecond=[double]$drain.eventsPerSecond
        drainSeconds=[double]$drain.drainSeconds; sendErrors=[long]$drain.sendErrors
        retries=[long]$drain.retries; hikariActive=[long]$metrics.hikariActivePeak
        hikariPending=[long]$metrics.hikariPendingPeak; cpuPeak=[double]$metrics.processCpuPeakPercent
        cpuMedian=[double]$metrics.processCpuMedianPercent; final=$drain.final
        diagnostics=@($log, $sampler.summary)
    }
}

function Count-Metric($Summary, [string]$Name) {
    $property = $Summary.metrics.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value.count) { return 0L }
    return [long]$property.Value.count
}

function Invoke-Transaction([int]$RunNumber, [int]$Seconds, [switch]$Warmup) {
    $log = Join-Path $script:runDir "transaction-$RunNumber-runner.log"
    $script:lastDiagnostic = $log
    $arguments = @{
        Scenario='transaction-flow'; VUs=25; Run=$RunNumber; Duration="${Seconds}s"
        WriteWorkload=$true; CaptureMysqlLocks=$true; OutputDirectory=$script:runDir
        OutboxDrainTimeoutSeconds=180
    }
    if (-not $Warmup) { $arguments.SkipWarmup = $true }
    $output = & (Join-Path $PSScriptRoot 'run-one.ps1') @arguments 2>&1
    $code = $LASTEXITCODE
    [IO.File]::WriteAllText($log, (($output | Out-String).Trim()), $script:utf8NoBom)
    if ($null -ne $code -and $code -ne 0) { throw "Transaction run failed; diagnostic=$log" }
    $runResult = Json-Line $output $log
    $k6 = Get-Content -Raw -LiteralPath $runResult.summary | ConvertFrom-Json
    $duration = $k6.metrics.transaction_duration
    $success = [long]$k6.metrics.transaction_success.count
    $outbox = $runResult.cleanup.outbox
    return [pscustomobject]@{
        run=$RunNumber; tps=[math]::Round($success / [double]$Seconds, 3)
        p50Ms=[double]$duration.med; p95Ms=[double]$duration.'p(95)'; p99Ms=[double]$duration.'p(99)'
        producedPerSecond=[math]::Round([long]$outbox.expectedEvents / [double]$Seconds, 3)
        publishedPerSecond=[double]$runResult.metricsSummary.outboxPublishedPerSecond
        measurementEnd=[long]$outbox.openAtMeasurementEnd; backlog30=[long]$outbox.openAt30Seconds
        backlog60=[long]$outbox.openAt60Seconds; backlog120=[long]$outbox.openAt120Seconds
        timeToZeroSeconds=[double]$outbox.timeToZeroSeconds
        missing=[long]$outbox.finalMissingConsumed; orphan=[long]$outbox.orphanEvents
        failed=[long]$outbox.finalFailed; hikariActive=[long]$runResult.metricsSummary.hikariActivePeak
        hikariPending=[long]$runResult.metricsSummary.hikariPendingPeak
        cpuPeak=[double]$runResult.metricsSummary.processCpuPeakPercent
        cpuMedian=[double]$runResult.metricsSummary.processCpuMedianPercent
        systemError=Count-Metric $k6 'system_error'; timeout=Count-Metric $k6 'timeout_error'
        networkError=Count-Metric $k6 'network_error'; parseError=Count-Metric $k6 'parse_error'
        deadlock=[bool]$runResult.mysqlLockSummary.deadlockChanged
        diagnostics=@($log, $runResult.summary, $runResult.metrics, $runResult.mysqlLocks)
    }
}

function Transaction-Gate($Run, [bool]$LatencyGate) {
    $valid = -not $Run.deadlock -and $Run.systemError -eq 0 -and $Run.timeout -eq 0 -and
        $Run.networkError -eq 0 -and $Run.parseError -eq 0 -and $Run.failed -eq 0 -and
        $Run.missing -eq 0 -and $Run.orphan -eq 0 -and $Run.backlog120 -eq 0
    return $valid -and (-not $LatencyGate -or ($Run.p95Ms -lt 800 -and $Run.p99Ms -lt 1200))
}

function Median([double[]]$Values) { return @($Values | Sort-Object)[1] }

try {
    $prepareLog = Join-Path $runDir 'prepare.log'; $script:lastDiagnostic = $prepareLog
    $prepareOutput = & (Join-Path $PSScriptRoot 'prepare.ps1') 2>&1
    [IO.File]::WriteAllText($prepareLog, (($prepareOutput | Out-String).Trim()), $utf8NoBom)
    if ($LASTEXITCODE -ne 0) { throw "Prepare failed; diagnostic=$prepareLog" }
    if ($Mode -eq 'Quick') {
        $drains = @(Invoke-Drain 1; Invoke-Drain 2; Invoke-Drain 4)
        $baseline = $drains[0]
        $eligible2 = $drains[1].eventsPerSecond -ge $baseline.eventsPerSecond * 1.15 -and
            $drains[1].sendErrors -eq 0 -and $drains[1].retries -eq 0 -and
            $drains[1].hikariPending -le ([math]::Max(1, $baseline.hikariPending + 1))
        $eligible4 = $drains[2].eventsPerSecond -ge $baseline.eventsPerSecond * 1.15 -and
            $drains[2].sendErrors -eq 0 -and $drains[2].retries -eq 0 -and
            $drains[2].hikariPending -le ([math]::Max(1, $baseline.hikariPending + 1))
        $selected = if ($eligible2) { 2 } elseif ($eligible4) { 4 } else { 1 }
        Restart-Backend $selected 'selected'
        $transaction = Invoke-Transaction 781 25
        $decreasing = $transaction.measurementEnd -ge $transaction.backlog30 -and
            $transaction.backlog30 -ge $transaction.backlog60 -and
            $transaction.backlog60 -ge $transaction.backlog120
        $pass = $selected -gt 1 -and (Transaction-Gate $transaction $false) -and $decreasing
        $result = [pscustomobject]@{
            status=if ($pass) { 'QUICK_PASS' } else { 'QUICK_FAIL' }; mode='Quick'; runId=$runId
            selectionThresholdPercent=15; drainRuns=$drains; selectedConcurrency=$selected
            transaction=$transaction; backlogContinuouslyDecreased=$decreasing
            diagnostics=@($drains.diagnostics + $transaction.diagnostics)
        }
    } else {
        $quickSummary = Get-ChildItem (Join-Path $PSScriptRoot 'results\phase6c-8-quick-*\summary.json') |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($null -eq $quickSummary) { throw 'Quick summary missing' }
        $quick = Get-Content -Raw -LiteralPath $quickSummary.FullName | ConvertFrom-Json
        if ($quick.status -ne 'QUICK_PASS') { throw 'Quick gate is not PASS' }
        $selected = [int]$quick.selectedConcurrency
        Restart-Backend $selected 'selected'
        $runs = @(Invoke-Transaction 7811 60 -Warmup; Invoke-Transaction 7812 60 -Warmup; Invoke-Transaction 7813 60 -Warmup)
        $pass = @($runs | Where-Object { -not (Transaction-Gate $_ $true) }).Count -eq 0
        $result = [pscustomobject]@{
            status=if ($pass) { 'FORMAL_PASS' } else { 'FORMAL_FAIL' }; mode='Formal'; runId=$runId
            selectedConcurrency=$selected; runs=$runs
            median=[pscustomobject]@{ tps=Median @($runs.tps); p95Ms=Median @($runs.p95Ms); p99Ms=Median @($runs.p99Ms); producedPerSecond=Median @($runs.producedPerSecond); publishedPerSecond=Median @($runs.publishedPerSecond) }
            diagnostics=@($runs.diagnostics)
        }
    }
    Save-Json $summaryPath $result
    $result | ConvertTo-Json -Depth 12 -Compress
    if ($result.status -match 'FAIL$') { exit 1 }
} catch {
    $failure = [pscustomobject]@{ status=($Mode.ToUpperInvariant() + '_ERROR'); mode=$Mode; runId=$runId; error=$_.Exception.Message; diagnostic=$script:lastDiagnostic }
    Save-Json $summaryPath $failure
    $failure | ConvertTo-Json -Compress
    exit 1
} finally {
    if ($null -eq $previousConcurrency) { Remove-Item Env:MAOYAN_OUTBOX_PUBLISHER_CONCURRENCY -ErrorAction SilentlyContinue }
    else { $env:MAOYAN_OUTBOX_PUBLISHER_CONCURRENCY = $previousConcurrency }
    $ErrorActionPreference = 'Continue'
    & docker compose -f (Join-Path $root 'docker-compose.yml') up -d --force-recreate backend *> (Join-Path $runDir 'restore-default.log')
}
