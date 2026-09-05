# 한 회차의 산출물(results.csv / metrics.csv / resources.csv)을 읽어 요약과 시계열을 만든다.
#
# 왜 집계를 run.ps1 에서 떼어 냈는가 — 두 가지 이유다.
#   1. 동시 500 · 10분이면 results.csv 가 약 60만 행이다. Import-Csv 로는 감당이 안 되고
#      JMeter 의 HTML 리포트 생성은 측정보다 오래 걸린다. StreamReader 로 직접 훑는다.
#   2. 회차가 끝난 뒤에도 집계만 다시 돌릴 수 있어야 한다. 3시간짜리 배치에서 집계 로직 하나
#      틀렸다고 측정을 다시 하는 일은 없어야 한다.
#
# 집계 구간 — 세 CSV 를 모두 "JMeter 첫 샘플 + ramp-up" 이라는 하나의 절대 시각으로 자른다.
# 샘플러는 JMeter 보다 먼저 뜨므로(JVM 기동 시간) 각 파일의 첫 줄을 기준으로 자르면 파일마다
# 구간이 어긋난다.
#
#   .\summarize.ps1 -OutDir results\reactive-u500-20260905-101500 -Scenario reactive -Users 500 -Rampup 150

param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [Parameter(Mandatory = $true)][string]$Scenario,
    [Parameter(Mandatory = $true)][int]$Users,
    [Parameter(Mandatory = $true)][int]$Rampup,
    [int]$SteadySeconds = 600
)

$ErrorActionPreference = "Stop"

$resultsCsv = Join-Path $OutDir "results.csv"
$metricsCsv = Join-Path $OutDir "metrics.csv"
$resourcesCsv = Join-Path $OutDir "resources.csv"

foreach ($f in @($resultsCsv, $metricsCsv)) {
    if (-not (Test-Path $f)) { throw "필요한 파일이 없습니다: $f" }
}

# ---------------------------------------------------------------- results.csv (JMeter)
# 라벨이나 실패 메시지에 쉼표가 들어가면 JMeter 가 큰따옴표로 감싼다. 그 줄만 따로 풀고
# 나머지(대다수)는 Split 으로 빠르게 간다.
function Split-CsvLine([string]$line) {
    if ($line.IndexOf([char]34) -lt 0) { return $line.Split(',') }
    $out = New-Object System.Collections.Generic.List[string]
    $sb = New-Object System.Text.StringBuilder
    $quote = [char]34
    $inQuotes = $false
    for ($i = 0; $i -lt $line.Length; $i++) {
        $ch = $line[$i]
        if ($inQuotes) {
            if ($ch -eq $quote) {
                if (($i + 1) -lt $line.Length -and $line[$i + 1] -eq $quote) { [void]$sb.Append($quote); $i++ }
                else { $inQuotes = $false }
            } else { [void]$sb.Append($ch) }
        } else {
            if ($ch -eq $quote) { $inQuotes = $true }
            elseif ($ch -eq ',') { [void]$out.Add($sb.ToString()); [void]$sb.Clear() }
            else { [void]$sb.Append($ch) }
        }
    }
    [void]$out.Add($sb.ToString())
    return $out.ToArray()
}

function Get-Percentile($sortedArr, [double]$p) {
    $n = @($sortedArr).Count
    if ($n -eq 0) { return $null }
    $idx = [int][Math]::Floor($p * ($n - 1))
    return @($sortedArr)[$idx]
}

$reader = [System.IO.File]::OpenText($resultsCsv)
try {
    $headerLine = $reader.ReadLine()
    if (-not $headerLine) { throw "results.csv 가 비어 있습니다: $resultsCsv" }
    $cols = $headerLine.Split(',')
    $iTs = [Array]::IndexOf($cols, "timeStamp")
    $iElapsed = [Array]::IndexOf($cols, "elapsed")
    $iSuccess = [Array]::IndexOf($cols, "success")
    if ($iTs -lt 0 -or $iElapsed -lt 0 -or $iSuccess -lt 0) {
        throw "results.csv 헤더에서 timeStamp/elapsed/success 를 찾지 못했습니다: $headerLine"
    }

    $tsList = New-Object System.Collections.Generic.List[long]
    $elapsedList = New-Object System.Collections.Generic.List[int]
    $okList = New-Object System.Collections.Generic.List[bool]

    while ($null -ne ($line = $reader.ReadLine())) {
        if ($line.Length -eq 0) { continue }
        $f = Split-CsvLine $line
        if (@($f).Count -le $iSuccess) { continue }
        $tsList.Add([long]$f[$iTs])
        $elapsedList.Add([int]$f[$iElapsed])
        $okList.Add($f[$iSuccess] -eq "true")
    }
} finally {
    $reader.Dispose()
}

if ($tsList.Count -eq 0) { throw "results.csv 에 샘플이 없습니다: $resultsCsv" }

$firstSampleMs = [long]($tsList | Measure-Object -Minimum).Minimum
$lastSampleMs = [long]($tsList | Measure-Object -Maximum).Maximum
$steadyStartMs = $firstSampleMs + ([long]$Rampup * 1000L)

# 정상 상태 구간만 남긴다.
$steadyElapsed = New-Object System.Collections.Generic.List[int]
$steadyErrors = 0
for ($i = 0; $i -lt $tsList.Count; $i++) {
    if ($tsList[$i] -lt $steadyStartMs) { continue }
    $steadyElapsed.Add($elapsedList[$i])
    if (-not $okList[$i]) { $steadyErrors++ }
}
$steadyCount = $steadyElapsed.Count
if ($steadyCount -eq 0) { throw "정상 상태 구간의 샘플이 없습니다. ramp-up($Rampup 초)을 채우기 전에 끝났을 수 있습니다." }

$windowSeconds = [Math]::Round(($lastSampleMs - $steadyStartMs) / 1000.0, 1)
if ($windowSeconds -le 0) { throw "정상 상태 구간의 길이가 0 입니다." }

$sortedElapsed = $steadyElapsed.ToArray()
[Array]::Sort($sortedElapsed)

$tps = [Math]::Round($steadyCount / $windowSeconds, 1)
$latP50 = Get-Percentile $sortedElapsed 0.50
$latP95 = Get-Percentile $sortedElapsed 0.95
$latP99 = Get-Percentile $sortedElapsed 0.99
$latMean = [Math]::Round(($steadyElapsed | Measure-Object -Average).Average, 1)
$errorRate = [Math]::Round(100.0 * $steadyErrors / $steadyCount, 3)

# ---------------------------------------------------------------- metrics.csv (워커 점유)
$metricRows = @(Import-Csv $metricsCsv | Where-Object { $_.tomcatBusy -ne "" -and $_.timestampMs -ne "" })
$steadyMetrics = @($metricRows | Where-Object { [long]$_.timestampMs -ge $steadyStartMs -and [long]$_.timestampMs -le $lastSampleMs })
if ($steadyMetrics.Count -eq 0) { throw "정상 상태 구간의 워커 점유 샘플이 없습니다. Actuator 노출과 tomcat MBean 레지스트리를 확인하세요." }

$busy = @(@($steadyMetrics | ForEach-Object { [double]$_.tomcatBusy }) | Sort-Object)
$tomcatMax = [double]($steadyMetrics[0].tomcatMax)
$busyMean = [Math]::Round((($busy | Measure-Object -Average).Average), 2)
$busyP95 = Get-Percentile $busy 0.95
$busyPeak = $busy[$busy.Count - 1]
$utilP95 = if ($tomcatMax -gt 0) { [Math]::Round(100.0 * $busyP95 / $tomcatMax, 2) } else { $null }
$utilMean = if ($tomcatMax -gt 0) { [Math]::Round(100.0 * $busyMean / $tomcatMax, 2) } else { $null }

# ---------------------------------------------------------------- resources.csv (힙 / 커넥션 풀)
$resRows = @()
if (Test-Path $resourcesCsv) {
    $resRows = @(Import-Csv $resourcesCsv | Where-Object { $_.timestampMs -ne "" })
}
$steadyRes = @($resRows | Where-Object { [long]$_.timestampMs -ge $steadyStartMs -and [long]$_.timestampMs -le $lastSampleMs })

function Get-FirstOf($rows, [string]$col) {
    foreach ($r in @($rows)) { if ($null -ne $r.$col -and $r.$col -ne "") { return $r.$col } }
    return $null
}
function Get-LastOf($rows, [string]$col) {
    $arr = @($rows)
    for ($i = $arr.Count - 1; $i -ge 0; $i--) { if ($null -ne $arr[$i].$col -and $arr[$i].$col -ne "") { return $arr[$i].$col } }
    return $null
}
function Get-MeanOf($rows, [string]$col) {
    $vals = @(@($rows) | Where-Object { $null -ne $_.$col -and $_.$col -ne "" } | ForEach-Object { [double]$_.$col })
    if ($vals.Count -eq 0) { return $null }
    return [Math]::Round((($vals | Measure-Object -Average).Average), 1)
}
function Get-MaxOf($rows, [string]$col) {
    $vals = @(@($rows) | Where-Object { $null -ne $_.$col -and $_.$col -ne "" } | ForEach-Object { [double]$_.$col })
    if ($vals.Count -eq 0) { return $null }
    return ($vals | Measure-Object -Maximum).Maximum
}

$summary = [PSCustomObject]@{
    scenario         = $Scenario
    users            = $Users
    rampupSeconds    = $Rampup
    steadySeconds    = $windowSeconds
    requests         = $steadyCount
    tps              = $tps
    latMeanMs        = $latMean
    latP50Ms         = $latP50
    latP95Ms         = $latP95
    latP99Ms         = $latP99
    errorRatePercent = $errorRate
    tomcatMax        = $tomcatMax
    busyMean         = $busyMean
    busyP95          = $busyP95
    busyPeak         = $busyPeak
    utilMeanPercent  = $utilMean
    utilP95Percent   = $utilP95
    heapStartMb      = Get-FirstOf $steadyRes "heapUsedMb"
    heapEndMb        = Get-LastOf  $steadyRes "heapUsedMb"
    heapPeakMb       = Get-MaxOf   $steadyRes "heapUsedMb"
    liveStartMb      = Get-FirstOf $steadyRes "liveDataSizeMb"
    liveEndMb        = Get-LastOf  $steadyRes "liveDataSizeMb"
    poolStart        = Get-FirstOf $steadyRes "stubConnections"
    poolEnd          = Get-LastOf  $steadyRes "stubConnections"
    poolPeak         = Get-MaxOf   $steadyRes "stubConnections"
    hikariActiveEnd  = Get-LastOf  $steadyRes "hikariActive"
    hikariMax        = Get-LastOf  $steadyRes "hikariMax"
    jvmThreadsStart  = Get-FirstOf $steadyRes "jvmThreadsLive"
    jvmThreadsEnd    = Get-LastOf  $steadyRes "jvmThreadsLive"
    hostCpuMean      = Get-MeanOf  $steadyRes "hostCpuPercent"
    appCpuMean       = Get-MeanOf  $steadyRes "appCpuPercent"
    stubCpuMean      = Get-MeanOf  $steadyRes "stubCpuPercent"
    steadyStartMs    = $steadyStartMs
}

$summary | Format-List
$summary | Export-Csv -Path (Join-Path $OutDir "summary.csv") -NoTypeInformation -Encoding utf8

# ---------------------------------------------------------------- 1분 간격 스냅샷
# 각 분 표시(0,1,...,10)를 중심으로 ±15초 창의 값을 쓴다. 0분은 정상 상태의 첫 15초,
# 10분은 마지막 15초다. "끝 값만 말고 추이" 를 보려면 이 표가 본체다.
$series = New-Object System.Collections.Generic.List[object]
$maxMinute = [int][Math]::Floor($SteadySeconds / 60)
$steadyEndMs = $steadyStartMs + ([long]$SteadySeconds * 1000L)
for ($m = 0; $m -le $maxMinute; $m++) {
    $centre = $steadyStartMs + ([long]$m * 60000L)
    $lo = [Math]::Max($steadyStartMs, $centre - 15000L)
    $hi = [Math]::Min($steadyEndMs, $centre + 15000L)
    if ($m -eq 0) { $hi = $steadyStartMs + 15000L }
    if ($m -eq $maxMinute) { $lo = $steadyEndMs - 15000L; $hi = $steadyEndMs }

    $mSlice = @($steadyMetrics | Where-Object { [long]$_.timestampMs -ge $lo -and [long]$_.timestampMs -lt $hi })
    $rSlice = @($steadyRes     | Where-Object { [long]$_.timestampMs -ge $lo -and [long]$_.timestampMs -lt $hi })

    $sliceBusy = @(@($mSlice | ForEach-Object { [double]$_.tomcatBusy }) | Sort-Object)
    $series.Add([PSCustomObject]@{
        minute          = $m
        samples         = $mSlice.Count
        busyMean        = if ($sliceBusy.Count) { [Math]::Round((($sliceBusy | Measure-Object -Average).Average), 2) } else { $null }
        busyP95         = if ($sliceBusy.Count) { Get-Percentile $sliceBusy 0.95 } else { $null }
        heapUsedMb      = Get-MeanOf $rSlice "heapUsedMb"
        liveDataSizeMb  = Get-MeanOf $rSlice "liveDataSizeMb"
        stubConnections = Get-MeanOf $rSlice "stubConnections"
        hikariActive    = Get-MeanOf $rSlice "hikariActive"
        jvmThreadsLive  = Get-MeanOf $rSlice "jvmThreadsLive"
        hostCpuPercent  = Get-MeanOf $rSlice "hostCpuPercent"
    })
}

$series | Format-Table -AutoSize
$series | Export-Csv -Path (Join-Path $OutDir "timeseries.csv") -NoTypeInformation -Encoding utf8

Write-Host "-- 집계 완료: $OutDir\summary.csv, $OutDir\timeseries.csv"
