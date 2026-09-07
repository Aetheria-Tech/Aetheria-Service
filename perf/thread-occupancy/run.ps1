# 시나리오 하나를 측정한다. 워밍업 → 샘플러 시작 → JMeter → 샘플러 정지 → 집계.
#
#   $env:JMETER_HOME = "C:\Users\usr\tools\apache-jmeter-5.6.3"
#   .\perf\thread-occupancy\run.ps1 -Scenario blocking -Users 100                    # 짧은 버스트(01번 문서)
#   .\perf\thread-occupancy\run.ps1 -Scenario reactive -Users 500 -Rampup 150 -Duration 750   # 10분 지속(01b)
#
# 여러 조합을 순서대로 도는 것은 run-matrix.ps1 이 맡는다.
#
# 앞서 벤치 스택이 떠 있어야 한다:
#   docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --build
#
# 지표 정의와 결과 해석은 docs/benchmark/01-thread-occupancy.md (짧은 버스트) 와
# docs/benchmark/01b-thread-occupancy-scale.md (동시성 × 지속시간) 를 보라.

param(
    [Parameter(Mandatory = $true)][ValidateSet("blocking", "reactive", "geocode")][string]$Scenario,
    [int]$Users = 100,
    [int]$Rampup = 30,
    [int]$Duration = 90,
    [int]$WarmupSeconds = 20,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$OutRoot = "$PSScriptRoot\results",
    # JMeter 의 HTML 리포트를 만들지 여부. 기본은 만들지 않는다 — 동시 500 · 10분이면 샘플이
    # 60만 개라 리포트 생성이 측정 자체보다 오래 걸리고 수백 MB 를 쓴다. 필요한 수치는
    # summarize.ps1 이 results.csv 에서 직접 뽑는다.
    [switch]$Report,
    [string]$AppContainer = "aetheria-backend",
    [string]$StubContainer = "aetheria-bench-stub"
)

$ErrorActionPreference = "Stop"

if (-not $env:JMETER_HOME) { throw "JMETER_HOME 을 설정하세요 (apache-jmeter-5.6.3 압축 푼 경로)." }

# bin\jmeter.bat 이 아니라 jar 를 직접 부른다. 이유가 둘이다.
#   1. bat 이 인자를 다시 파싱하면서 -Jhost=... 같은 값을 쪼갠다 ("Unknown arg: localhost").
#   2. bat 은 실패하면 `pause` 로 키 입력을 기다려, 비대화형 실행에서 그대로 멈춘다.
$jmeterJar = Join-Path $env:JMETER_HOME "bin\ApacheJMeter.jar"
if (-not (Test-Path $jmeterJar)) { throw "JMeter 를 찾을 수 없습니다: $jmeterJar" }

if ($Duration -le $Rampup) { throw "Duration($Duration) 은 Rampup($Rampup) 보다 커야 합니다. 정상 상태 구간이 없습니다." }
$steadySeconds = $Duration - $Rampup

# 시나리오 → 경로. geocode 는 상용 엔드포인트라 주소 파라미터가 필요하다.
$address = [uri]::EscapeDataString("서울특별시 강남구 테헤란로 427")
$path = switch ($Scenario) {
    "blocking" { "/bench/blocking" }
    "reactive" { "/bench/reactive" }
    "geocode"  { "/api/v1/geocode?address=$address" }
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outDir = Join-Path $OutRoot "$Scenario-u$Users-$stamp"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$stopFile = Join-Path $outDir "STOP"
$metricsCsv = Join-Path $outDir "metrics.csv"
$resourcesCsv = Join-Path $outDir "resources.csv"
$resultsCsv = Join-Path $outDir "results.csv"
$reportDir = Join-Path $outDir "report"

Write-Host "== $Scenario / users=$Users / rampup=${Rampup}s / steady=${steadySeconds}s / path=$path"
Write-Host "== 출력: $outDir"

# 워밍업. JIT 와 커넥션 풀이 데워지기 전 구간을 측정에 섞으면 첫 시나리오만 불리해진다.
Write-Host "-- 워밍업 ${WarmupSeconds}s"
$warmEnd = (Get-Date).AddSeconds($WarmupSeconds)
while ((Get-Date) -lt $warmEnd) {
    try { Invoke-WebRequest -Uri "$BaseUrl$path" -TimeoutSec 10 -UseBasicParsing | Out-Null } catch {}
}

# 샘플러를 별도 프로세스로 띄운다. JMeter 와 같은 프로세스에 두면 부하가 샘플링 간격을 흔든다.
# 빠른 샘플러(250ms, 워커 점유)와 느린 샘플러(5s, 힙·커넥션 풀)도 서로 분리한다 —
# 한 루프에 넣으면 액추에이터 호출이 틱마다 열 번 가까이 되어 재려는 워커 점유를 스스로 부풀린다.
Write-Host "-- 지표 샘플러 시작 (워커 점유 250ms / 자원 5s)"
$sampler = Start-Process -FilePath "powershell.exe" -PassThru -WindowStyle Hidden -ArgumentList @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", "$PSScriptRoot\sample-metrics.ps1",
    "-BaseUrl", $BaseUrl, "-OutFile", $metricsCsv, "-StopFile", $stopFile
)
$resSampler = Start-Process -FilePath "powershell.exe" -PassThru -WindowStyle Hidden -ArgumentList @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", "$PSScriptRoot\sample-resources.ps1",
    "-BaseUrl", $BaseUrl, "-OutFile", $resourcesCsv, "-StopFile", $stopFile,
    "-AppContainer", $AppContainer, "-StubContainer", $StubContainer
)

try {
    Write-Host "-- JMeter 실행 (ramp-up ${Rampup}s + 총 ${Duration}s)"
    # 인자 안에서 $(...).Host 처럼 쓰면 PowerShell 이 프로퍼티 접근을 리터럴로 붙인다. 미리 뽑아 둔다.
    $uri = [uri]$BaseUrl
    # 동시 500 · 10분이면 샘플이 60만 개다. 기본 힙으로는 결과 수집 단계에서 흔들린다.
    $jmeterArgs = @(
        "-Xmx2g", "-jar", $jmeterJar, "-n", "-t", "$PSScriptRoot\thread-occupancy.jmx",
        "-Jhost=$($uri.Host)", "-Jport=$($uri.Port)",
        "-Jpath=$path", "-Jusers=$Users", "-Jrampup=$Rampup", "-Jduration=$Duration",
        "-l", $resultsCsv
    )
    if ($Report) { $jmeterArgs += @("-e", "-o", $reportDir) }
    & java @jmeterArgs
    if ($LASTEXITCODE -ne 0) { throw "JMeter 가 비정상 종료했습니다 (exit=$LASTEXITCODE)." }
} finally {
    New-Item -ItemType File -Force -Path $stopFile | Out-Null
    Start-Sleep -Seconds 6
    foreach ($p in @($sampler, $resSampler)) {
        if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    }
}

# 집계는 별도 스크립트가 맡는다. 회차가 끝난 뒤에도 집계만 다시 돌릴 수 있어야 하기 때문이다.
& "$PSScriptRoot\summarize.ps1" -OutDir $outDir -Scenario $Scenario -Users $Users -Rampup $Rampup -SteadySeconds $steadySeconds

if ($Report) { Write-Host "-- JMeter HTML 리포트: $reportDir\index.html" }
