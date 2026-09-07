# 동시성 × 지속시간 매트릭스를 처음부터 끝까지 돌린다 (docs/benchmark/01b-thread-occupancy-scale.md).
#
#   $env:JMETER_HOME = "C:\Users\usr\tools\apache-jmeter-5.6.3"
#   docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --build
#   .\perf\thread-occupancy\run-matrix.ps1
#
# 왜 회차마다 앱을 재시작하는가 — 세 경로가 JVM 전역 커넥션 풀(Reactor Netty 의 HttpResources)
# 하나를 공유한다. 경로 B 를 돌린 뒤 남은 웜 커넥션을 경로 C 가 물려받으면 "커넥션 풀 시작→종료"
# 비교가 무의미해지고, 힙도 앞 회차의 잔여물을 안고 시작한다. 쿨다운만으로는 풀이 비워지지 않는다.
#
# 한 회차가 실패해도 멈추지 않는다. 세 시간짜리 배치를 회차 하나 때문에 처음부터 다시 도는 일은
# 없어야 한다. 실패는 matrix-log.csv 에 남고 문서에는 "(미측정)" 으로 간다.

param(
    [int]$SteadySeconds = 600,
    [int]$SettleSeconds = 30,
    [int]$CooldownSeconds = 60,
    [int]$WarmupSeconds = 20,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$AppContainer = "aetheria-backend",
    [string]$StubContainer = "aetheria-bench-stub",
    [int]$HealthTimeoutSeconds = 300,
    # 중간에 끊겼을 때 N 번째 회차부터 다시 시작한다 (1-based, matrix-log.csv 의 index).
    [int]$StartAt = 1,
    [string]$OutRoot = "$PSScriptRoot\results"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path "$PSScriptRoot\..\..").Path

# ramp-up 은 유입 속도를 3.33 users/s 로 고정해 스케일한다. 동시성마다 유입 속도가 달라지면
# 세 구간을 나란히 놓을 수 없다. 100 → 30s 는 01번 문서와 같은 값이다.
$rampupFor = @{ 100 = 30; 300 = 90; 500 = 150 }

# 1회차 9구간을 먼저 전부 돌고, 500 구간 2회차를 뒤에 몰아 돌린다.
# 재현성 확인은 "같은 조건을 연달아 두 번" 이 아니라 "한 바퀴 돈 뒤 다시" 여야 의미가 있다.
$plan = @(
    @{ scenario = "blocking"; users = 100; rep = 1 }
    @{ scenario = "blocking"; users = 300; rep = 1 }
    @{ scenario = "blocking"; users = 500; rep = 1 }
    @{ scenario = "reactive"; users = 100; rep = 1 }
    @{ scenario = "reactive"; users = 300; rep = 1 }
    @{ scenario = "reactive"; users = 500; rep = 1 }
    @{ scenario = "geocode";  users = 100; rep = 1 }
    @{ scenario = "geocode";  users = 300; rep = 1 }
    @{ scenario = "geocode";  users = 500; rep = 1 }
    @{ scenario = "blocking"; users = 500; rep = 2 }
    @{ scenario = "reactive"; users = 500; rep = 2 }
    @{ scenario = "geocode";  users = 500; rep = 2 }
)

$logCsv = Join-Path $OutRoot "matrix-log.csv"
New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
if (-not (Test-Path $logCsv)) {
    "index,scenario,users,rep,startedAt,finishedAt,status,outDir,note" | Out-File -FilePath $logCsv -Encoding utf8
}

function Wait-AppHealthy {
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $h = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 5
            if ($h.status -eq "UP") { return $true }
        } catch { }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Write-Log($index, $spec, $started, $finished, $status, $outDir, $note) {
    $safeNote = ($note -replace '[",\r\n]', ' ')
    "$index,$($spec.scenario),$($spec.users),$($spec.rep),$started,$finished,$status,$outDir,$safeNote" |
        Out-File -FilePath $logCsv -Encoding utf8 -Append
}

$totalMinutes = 0.0
foreach ($spec in $plan) {
    $r = $rampupFor[$spec.users]
    $totalMinutes += ($SettleSeconds + $WarmupSeconds + 15 + $r + $SteadySeconds + 45 + $CooldownSeconds + 120) / 60.0
}
Write-Host "== 매트릭스 $($plan.Count) 회차 / 예상 소요 약 $([Math]::Round($totalMinutes, 0)) 분"
Write-Host "== 로그: $logCsv"

for ($i = 0; $i -lt $plan.Count; $i++) {
    $index = $i + 1
    if ($index -lt $StartAt) { Write-Host "== [$index/$($plan.Count)] 건너뜀 (StartAt=$StartAt)"; continue }

    $spec = $plan[$i]
    $rampup = $rampupFor[$spec.users]
    $duration = $rampup + $SteadySeconds
    $started = (Get-Date).ToString("s")

    Write-Host ""
    Write-Host "===== [$index/$($plan.Count)] $($spec.scenario) u$($spec.users) rep$($spec.rep) — $(Get-Date -Format 'HH:mm:ss')"

    # 앱 재시작. 워커·커넥션 풀·힙을 앞 회차와 분리하는 유일하게 확실한 방법이다.
    #
    # docker compose 는 진행 상황("Container ... Restarting")을 stderr 로 낸다. Windows PowerShell 5.1
    # 은 네이티브 명령의 stderr 한 줄 한 줄을 ErrorRecord 로 감싸는데, $ErrorActionPreference = "Stop"
    # 아래에서는 그것이 곧 종료성 오류가 되어 **정상 재시작인데도 스크립트가 죽는다.**
    # (실제로 이 배치가 1회차 시작 직후 여기서 멈췄다.) 그래서 이 구간에서만 Continue 로 낮춘다.
    Write-Host "-- 앱 재시작"
    $prevEap = $ErrorActionPreference
    Push-Location $repoRoot
    try {
        $ErrorActionPreference = "Continue"
        & docker compose -f docker-compose.yml -f docker-compose.bench.yml restart app | Out-Null
    } catch {
        Write-Warning "재시작 명령에서 예외: $($_.Exception.Message)"
    } finally {
        $ErrorActionPreference = $prevEap
        Pop-Location
    }

    if (-not (Wait-AppHealthy)) {
        Write-Warning "앱이 ${HealthTimeoutSeconds}초 안에 UP 이 되지 않았습니다. 이 회차를 건너뜁니다."
        Write-Log $index $spec $started (Get-Date).ToString("s") "SKIPPED" "" "health check timeout"
        continue
    }

    Write-Host "-- 정착 ${SettleSeconds}s"
    Start-Sleep -Seconds $SettleSeconds

    $before = @(Get-ChildItem -Path $OutRoot -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    $status = "OK"
    $note = ""
    try {
        & "$PSScriptRoot\run.ps1" -Scenario $spec.scenario -Users $spec.users `
            -Rampup $rampup -Duration $duration -WarmupSeconds $WarmupSeconds `
            -BaseUrl $BaseUrl -OutRoot $OutRoot -AppContainer $AppContainer -StubContainer $StubContainer
    } catch {
        $status = "FAILED"
        $note = $_.Exception.Message
        Write-Warning "회차 실패: $note"
    }

    $after = @(Get-ChildItem -Path $OutRoot -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
    $newDir = @($after | Where-Object { $before -notcontains $_ }) | Select-Object -Last 1
    Write-Log $index $spec $started (Get-Date).ToString("s") $status $newDir $note

    if ($index -lt $plan.Count) {
        Write-Host "-- 쿨다운 ${CooldownSeconds}s"
        Start-Sleep -Seconds $CooldownSeconds
    }
}

Write-Host ""
Write-Host "== 매트릭스 종료 $(Get-Date -Format 'HH:mm:ss')"
Import-Csv $logCsv | Format-Table -AutoSize
