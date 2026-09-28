param(
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [Parameter(Mandatory = $true)][string]$SummaryPath,
    [Parameter(Mandatory = $true)][string]$StopPath,
    [int]$IntervalSeconds = 2,
    [string]$RepoRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
} else {
    (Resolve-Path $RepoRoot).Path
}
$dockerExe = (Get-Command docker -ErrorAction Stop).Source
$utf8NoBom = New-Object Text.UTF8Encoding($false)

if ($IntervalSeconds -lt 1) { throw 'IntervalSeconds must be positive' }

function Get-Measurement($Metric, [string]$Statistic) {
    $measurement = @($Metric.measurements | Where-Object { $_.statistic -eq $Statistic } | Select-Object -First 1)
    if ($measurement.Count -ne 1) { throw "Metric $($Metric.name) lacks $Statistic" }
    return [double]$measurement[0].value
}

function Get-Median([double[]]$Values) {
    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $middle = [Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return [double]$sorted[$middle] }
    return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2
}

function Get-Snapshot {
    $metricUrls = @(
        'process.cpu.usage',
        'jvm.memory.used?tag=area:heap',
        'jvm.gc.pause',
        'jvm.threads.live',
        'hikaricp.connections.active',
        'hikaricp.connections.idle',
        'hikaricp.connections.pending',
        'xticket.outbox.pending',
        'xticket.outbox.processing',
        'xticket.outbox.failed',
        'xticket.outbox.publish.success'
    )
    $parts = @($metricUrls | ForEach-Object {
        "wget -qO- 'http://127.0.0.1:8080/actuator/metrics/$_'; printf '\n'"
    })
    $lines = @(& $script:dockerExe compose exec -T backend sh -lc ($parts -join '; ') 2>&1)
    if ($LASTEXITCODE -ne 0) { throw (($lines | Out-String).Trim()) }
    $metrics = @($lines | Where-Object { $_.TrimStart().StartsWith('{') } | ForEach-Object {
        $_ | ConvertFrom-Json
    })
    if ($metrics.Count -ne $metricUrls.Count) {
        throw "Expected $($metricUrls.Count) Actuator metrics, received $($metrics.Count)"
    }
    [pscustomobject]@{
        timestampUtc = [DateTimeOffset]::UtcNow.ToString('o')
        processCpuPercent = (Get-Measurement $metrics[0] 'VALUE') * 100
        heapUsedBytes = Get-Measurement $metrics[1] 'VALUE'
        gcCount = Get-Measurement $metrics[2] 'COUNT'
        gcTimeSeconds = Get-Measurement $metrics[2] 'TOTAL_TIME'
        liveThreads = Get-Measurement $metrics[3] 'VALUE'
        hikariActive = Get-Measurement $metrics[4] 'VALUE'
        hikariIdle = Get-Measurement $metrics[5] 'VALUE'
        hikariPending = Get-Measurement $metrics[6] 'VALUE'
        outboxPending = Get-Measurement $metrics[7] 'VALUE'
        outboxProcessing = Get-Measurement $metrics[8] 'VALUE'
        outboxFailed = Get-Measurement $metrics[9] 'VALUE'
        outboxPublishedCount = Get-Measurement $metrics[10] 'COUNT'
    }
}

Push-Location $repoRoot
try {
    $outputDirectory = Split-Path -Parent $OutputPath
    New-Item -ItemType Directory -Force $outputDirectory | Out-Null
    foreach ($path in @($OutputPath, $SummaryPath)) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    }
    $samples = @()
    do {
        $sample = Get-Snapshot
        $samples += $sample
        [IO.File]::AppendAllText($OutputPath, (($sample | ConvertTo-Json -Compress) + [Environment]::NewLine), $utf8NoBom)
        for ($slice = 0; $slice -lt ($IntervalSeconds * 5); $slice++) {
            if (Test-Path -LiteralPath $StopPath) { break }
            Start-Sleep -Milliseconds 200
        }
    } while (-not (Test-Path -LiteralPath $StopPath))

    $cpuValues = [double[]]@($samples | ForEach-Object { $_.processCpuPercent })
    $first = $samples[0]
    $last = $samples[$samples.Count - 1]
    $sampledSeconds = ([DateTimeOffset]::Parse($last.timestampUtc) - [DateTimeOffset]::Parse($first.timestampUtc)).TotalSeconds
    $publishedDelta = [long]($last.outboxPublishedCount - $first.outboxPublishedCount)
    $summary = [pscustomobject]@{
        status = 'SAMPLER_OK'
        intervalSeconds = $IntervalSeconds
        sampleCount = $samples.Count
        processCpuPeakPercent = [Math]::Round(($cpuValues | Measure-Object -Maximum).Maximum, 3)
        processCpuMedianPercent = [Math]::Round((Get-Median $cpuValues), 3)
        heapPeakBytes = [long](($samples.heapUsedBytes | Measure-Object -Maximum).Maximum)
        gcCountDelta = [long]($last.gcCount - $first.gcCount)
        gcTimeDeltaSeconds = [Math]::Round(($last.gcTimeSeconds - $first.gcTimeSeconds), 6)
        threadPeak = [long](($samples.liveThreads | Measure-Object -Maximum).Maximum)
        hikariActivePeak = [long](($samples.hikariActive | Measure-Object -Maximum).Maximum)
        hikariIdlePeak = [long](($samples.hikariIdle | Measure-Object -Maximum).Maximum)
        hikariPendingPeak = [long](($samples.hikariPending | Measure-Object -Maximum).Maximum)
        outboxPendingPeak = [long](($samples.outboxPending | Measure-Object -Maximum).Maximum)
        outboxProcessingPeak = [long](($samples.outboxProcessing | Measure-Object -Maximum).Maximum)
        outboxFailedPeak = [long](($samples.outboxFailed | Measure-Object -Maximum).Maximum)
        outboxPublishedDelta = $publishedDelta
        outboxPublishedPerSecond = if ($sampledSeconds -gt 0) {
            [Math]::Round($publishedDelta / $sampledSeconds, 3)
        } else { 0 }
    }
    [IO.File]::WriteAllText($SummaryPath, ($summary | ConvertTo-Json -Compress), $utf8NoBom)
    $summary | ConvertTo-Json -Compress
} finally {
    Pop-Location
}
