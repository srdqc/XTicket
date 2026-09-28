param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Quick', 'Formal')]
    [string]$Mode
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$raw = Join-Path $PSScriptRoot 'results\raw'
$summaryPath = Join-Path $raw ("phase6c-7c-{0}-summary.json" -f $Mode.ToLowerInvariant())
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$docker = if ($dockerCommand) { $dockerCommand.Source } else {
    Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
}
$k6 = (Get-Command k6 -ErrorAction Stop).Source
$utf8NoBom = New-Object Text.UTF8Encoding($false)
$previousProfile = $env:MAOYAN_PROFILING_PAYMENT_ENABLED
$script:lastDiagnostic = ''
New-Item -ItemType Directory -Force $raw | Out-Null

function Save-Text([string]$Path, $Lines) {
    [IO.File]::WriteAllText($Path, (($Lines | Out-String).Trim()), $script:utf8NoBom)
}

function Invoke-Script([string]$Name, [hashtable]$Arguments, [string]$LogName) {
    $path = Join-Path $PSScriptRoot $Name
    $log = Join-Path $script:raw $LogName
    $script:lastDiagnostic = $log
    $output = & $path @Arguments 2>&1
    $code = $LASTEXITCODE
    Save-Text $log $output
    if ($null -ne $code -and $code -ne 0) { throw "$Name failed; diagnostic=$log" }
    $jsonLine = @($output | Where-Object { $_ -and ([string]$_).Trim().StartsWith('{') }) | Select-Object -Last 1
    if (-not $jsonLine) { throw "$Name returned no JSON; diagnostic=$log" }
    return ($jsonLine | ConvertFrom-Json)
}

function Restart-Backend([string]$Label) {
    $log = Join-Path $script:raw "phase6c-7c-$Label-docker.log"
    $script:lastDiagnostic = $log
    $savedPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & $script:docker compose -f (Join-Path $script:root 'docker-compose.yml') restart backend *> $log
    $code = $LASTEXITCODE; $ErrorActionPreference = $savedPreference
    if ($code -ne 0) { throw "backend restart failed; diagnostic=$log" }
    $deadline = (Get-Date).AddSeconds(90)
    $ErrorActionPreference = 'Continue'
    do {
        $health = & $script:docker exec maoyan-backend wget -qO- http://127.0.0.1:8080/actuator/health 2>$null
        if ($LASTEXITCODE -eq 0 -and $health -match '"status":"UP"') { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    $ErrorActionPreference = $savedPreference
    if ($health -match '"status":"UP"') { return }
    throw "backend health timeout; diagnostic=$log"
}

function Reset-Fixture([string]$Label) {
    [void](Invoke-Script 'reset.ps1' @{} "phase6c-7c-$Label-reset.log")
}

function Prepare-Payment([int]$Count, [string]$Label, [int]$Seats = 1) {
    return Invoke-Script 'prepare-payment-fixture.ps1' @{
        Mode='different-users'; SeatCount=$Seats; OrderCount=$Count; RunLabel=$Label
    } "phase6c-7c-$Label-fixture.log"
}

function Get-Count($Summary, [string]$Name) {
    $property = $Summary.metrics.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value.count) { return 0L }
    return [long]$property.Value.count
}

function Convert-Run($Run) {
    $summary = Get-Content -Raw -LiteralPath $Run.summary | ConvertFrom-Json
    $duration = if ($Run.scenario -eq 'payment-only') {
        $summary.metrics.payment_only_duration
    } else { $summary.metrics.transaction_duration }
    $success = if ($Run.scenario -eq 'payment-only') {
        [long]$summary.metrics.payment_only_success.count
    } else { [long]$summary.metrics.transaction_success.count }
    return [pscustomobject]@{
        run = [int]$Run.runNumber; scenario = [string]$Run.scenario
        throughput = [math]::Round($success / [double]$Run.measurementSeconds, 3)
        p50Ms = [double]$duration.med; p95Ms = [double]$duration.'p(95)'; p99Ms = [double]$duration.'p(99)'
        updateP95Ms = [double]$Run.paymentProfileSnapshot.payment_seat_lock_update.p95Ms
        rowLockWaits = [long]$Run.mysqlLockSummary.rowLockWaitsDelta
        rowLockTimeMs = [long]$Run.mysqlLockSummary.rowLockTimeDeltaMs
        deadlock = [bool]$Run.mysqlLockSummary.deadlockChanged
        systemError = Get-Count $summary 'system_error'; timeout = Get-Count $summary 'timeout_error'
        networkError = Get-Count $summary 'network_error'; parseError = Get-Count $summary 'parse_error'
        businessConflict = Get-Count $summary 'business_conflict'
        hikariActive = [int]$Run.metricsSummary.hikariActivePeak
        hikariPending = [int]$Run.metricsSummary.hikariPendingPeak
        cpuPeak = [double]$Run.metricsSummary.processCpuPeakPercent
        outbox = $Run.cleanup.outbox
        diagnostics = @($Run.log, $Run.metrics, $Run.mysqlLocks, $Run.paymentProfile)
    }
}

function Run-One([string]$Scenario, [int]$RunNumber, [int]$Seconds, [string]$OrderFile) {
    Restart-Backend "run$RunNumber"
    $args = @{ Scenario=$Scenario; VUs=25; Run=$RunNumber; Duration="${Seconds}s";
        SkipWarmup=$true; WriteWorkload=$true; CapturePaymentProfile=$true; CaptureMysqlLocks=$true }
    if ($OrderFile) { $args.PaymentOrderFile = $OrderFile }
    $run = Invoke-Script 'run-one.ps1' $args "phase6c-7c-run$RunNumber.log"
    $run | Add-Member -NotePropertyName runNumber -NotePropertyValue $RunNumber
    $run | Add-Member -NotePropertyName measurementSeconds -NotePropertyValue $Seconds
    return Convert-Run $run
}

function Warmup([string]$Scenario, [int]$RunNumber) {
    Restart-Backend "warmup$RunNumber"
    $log = Join-Path $script:raw "phase6c-7c-warmup$RunNumber-k6.log"
    $script:lastDiagnostic = $log
    $args = @('run','--quiet','-e','PHASE=warmup','-e','VUS=25','-e','WARMUP_DURATION=30s')
    if ($Scenario -eq 'payment-only') {
        $fixture = Prepare-Payment 2200 "W7C$RunNumber"
        $args += @('-e',"PAYMENT_ORDER_FILE=$($fixture.orderFile)")
    }
    $args += (Join-Path $PSScriptRoot "scenarios\$Scenario.js")
    $savedPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & $script:k6 @args *> $log
    $code = $LASTEXITCODE; $ErrorActionPreference = $savedPreference
    if ($code -ne 0) { throw "warmup failed; diagnostic=$log" }
    Reset-Fixture "warmup$RunNumber"
}

function Median([double[]]$Values) { return @($Values | Sort-Object)[1] }
function Gate-Run($Run) {
    return -not $Run.deadlock -and $Run.systemError -eq 0 -and $Run.timeout -eq 0 -and
        $Run.networkError -eq 0 -and $Run.parseError -eq 0
}

try {
    $env:MAOYAN_PROFILING_PAYMENT_ENABLED = 'true'
    $enableLog = Join-Path $raw 'phase6c-7c-enable-profile.log'
    $savedPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & $docker compose -f (Join-Path $root 'docker-compose.yml') up -d backend *> $enableLog
    $code = $LASTEXITCODE; $ErrorActionPreference = $savedPreference
    if ($code -ne 0) { throw "profile enable failed; diagnostic=$enableLog" }
    $deadline = (Get-Date).AddSeconds(90)
    $ErrorActionPreference = 'Continue'
    do {
        $health = & $docker exec maoyan-backend wget -qO- http://127.0.0.1:8080/actuator/health 2>$null
        if ($LASTEXITCODE -eq 0 -and $health -match '"status":"UP"') { break }
        Start-Sleep -Seconds 2 } while ((Get-Date) -lt $deadline)
    $ErrorActionPreference = $savedPreference
    if (-not ($health -match '"status":"UP"')) { throw "profile backend health timeout; diagnostic=$enableLog" }
    if ($Mode -eq 'Quick') {
        [void](Invoke-Script 'prepare.ps1' @{} 'phase6c-7c-prepare.log')
        $fixture3 = Prepare-Payment 500 'Q7C3' 3
        $seat3 = Run-One 'payment-only' 753 5 $fixture3.orderFile
        $fixture6 = Prepare-Payment 500 'Q7C6' 6
        $seat6 = Run-One 'payment-only' 756 5 $fixture6.orderFile
        $fixture = Prepare-Payment 2200 'Q7CPAY'
        $payment = Run-One 'payment-only' 751 25 $fixture.orderFile
        $full = Run-One 'transaction-flow' 752 25 ''
        $pass = (Gate-Run $seat3) -and (Gate-Run $seat6) -and (Gate-Run $payment) -and (Gate-Run $full)
        $result = [pscustomobject]@{
            status = if ($pass) { 'QUICK_PASS' } else { 'QUICK_FAIL' }
            mode = 'Quick'; seat3 = $seat3; seat6 = $seat6; payment = $payment; fullTransaction = $full
            diagnostics = @($seat3.diagnostics + $seat6.diagnostics + $payment.diagnostics + $full.diagnostics)
        }
    } else {
        $quickPath = Join-Path $raw 'phase6c-7c-quick-summary.json'
        if (-not (Test-Path -LiteralPath $quickPath)) { throw 'Quick summary missing' }
        $quick = Get-Content -Raw -LiteralPath $quickPath | ConvertFrom-Json
        if ($quick.status -ne 'QUICK_PASS') { throw 'Quick gate is not PASS' }
        $payments = @(); $fullRuns = @()
        foreach ($runNumber in @(761,762,763)) {
            Warmup 'payment-only' $runNumber
            $fixture = Prepare-Payment 4500 "F7C$runNumber"
            $payments += Run-One 'payment-only' $runNumber 60 $fixture.orderFile
        }
        foreach ($runNumber in @(771,772,773)) {
            Warmup 'transaction-flow' $runNumber
            $fullRuns += Run-One 'transaction-flow' $runNumber 60 ''
        }
        $allGate = @($payments + $fullRuns | Where-Object { -not (Gate-Run $_) }).Count -eq 0
        $outboxGate = @($fullRuns | Where-Object {
            $_.outbox.timeToZeroSeconds -gt 120 -or $_.outbox.finalPending -ne 0 -or
            $_.outbox.finalProcessing -ne 0 -or $_.outbox.finalFailed -ne 0 -or
            $_.outbox.finalMissingConsumed -ne 0 -or $_.outbox.orphanEvents -ne 0
        }).Count -eq 0
        $result = [pscustomobject]@{
            status = if ($allGate -and $outboxGate) { 'FORMAL_PASS' } else { 'FORMAL_FAIL' }
            mode = 'Formal'; paymentRuns = $payments; fullTransactionRuns = $fullRuns
            paymentMedian = [pscustomobject]@{ throughput=Median @($payments.throughput); p95Ms=Median @($payments.p95Ms); updateP95Ms=Median @($payments.updateP95Ms); rowLockWaits=Median @($payments.rowLockWaits); rowLockTimeMs=Median @($payments.rowLockTimeMs) }
            fullMedian = [pscustomobject]@{ throughput=Median @($fullRuns.throughput); p95Ms=Median @($fullRuns.p95Ms); p99Ms=Median @($fullRuns.p99Ms) }
            outboxGate = $outboxGate
            diagnostics = @($payments.diagnostics + $fullRuns.diagnostics)
        }
    }
    [IO.File]::WriteAllText($summaryPath, ($result | ConvertTo-Json -Depth 12 -Compress), $utf8NoBom)
    $result | ConvertTo-Json -Depth 12 -Compress
    if ($result.status -match 'FAIL$') { exit 1 }
} catch {
    $failureStatus = $Mode.ToUpperInvariant() + '_ERROR'
    $failure = [pscustomobject]@{ status=$failureStatus; mode=$Mode; error=$_.Exception.Message; diagnostic=$script:lastDiagnostic }
    [IO.File]::WriteAllText($summaryPath, ($failure | ConvertTo-Json -Compress), $utf8NoBom)
    $failure | ConvertTo-Json -Compress
    exit 1
} finally {
    if ($null -eq $previousProfile) { Remove-Item Env:MAOYAN_PROFILING_PAYMENT_ENABLED -ErrorAction SilentlyContinue }
    else { $env:MAOYAN_PROFILING_PAYMENT_ENABLED = $previousProfile }
    $ErrorActionPreference = 'Continue'
    & $docker compose -f (Join-Path $root 'docker-compose.yml') up -d backend *> (Join-Path $raw 'phase6c-7c-restore-profile.log')
}
