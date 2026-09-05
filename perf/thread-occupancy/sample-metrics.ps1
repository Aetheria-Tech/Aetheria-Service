# 부하가 도는 동안 톰캣 워커 점유를 일정 간격으로 긁어 CSV 로 남긴다.
#
# 왜 액추에이터인가 — 톰캣 워커 점유는 부하 도구가 볼 수 없다. JMeter 가 아는 것은 응답 시간과
# 처리량뿐이고, "요청 하나가 워커를 몇 초 붙잡고 있었나"는 서버 안에서만 보인다.
# tomcat.threads.busy 는 Actuator 가 노출하지만, 그러려면 Tomcat 의 MBean 레지스트리가 켜져 있어야
# 한다(server.tomcat.mbeanregistry.enabled). 꺼져 있으면 tomcat.sessions.* 만 바인딩되어 워커 점유를
# 볼 수 없다. docker-compose.bench.yml 이 벤치 스택에서만 이 값을 켠다.
#
# 왜 지표를 하나만 긁는가 — 이 샘플러의 요청도 톰캣 워커를 하나 쓴다. 01번 측정에서 동기 경로의
# busy 가 100 이 아니라 이따금 101 로 찍힌 것이 그 흔적이다. 틱마다 지표 4개를 부르면 초당 16개의
# 요청이 측정 대상 위에 얹힌다. 그래서 매 틱 부르는 것은 tomcat.threads.busy 하나뿐이고(초당 4개),
# 부하 중 변하지 않는 tomcat.threads.config.max 는 시작 시 한 번만 읽어 그 값을 그대로 적는다.
# 힙·커넥션 풀·Hikari 처럼 초 단위로 충분한 지표는 sample-resources.ps1 이 5초 간격으로 따로 맡는다.
#
# 출력: timestampMs,tomcatBusy,tomcatMax
#
# 멈추는 방법: -StopFile 로 지정한 경로에 파일이 생기면 스스로 종료한다.
# (run.ps1 이 JMeter 종료 후 그 파일을 만든다)

param(
    [string]$BaseUrl = "http://localhost:8080",
    [Parameter(Mandatory = $true)][string]$OutFile,
    [Parameter(Mandatory = $true)][string]$StopFile,
    [int]$IntervalMs = 250
)

$ErrorActionPreference = "Stop"

function Get-MetricValue([string]$name) {
    try {
        $r = Invoke-RestMethod -Uri "$BaseUrl/actuator/metrics/$name" -TimeoutSec 3
        return [double]$r.measurements[0].value
    } catch {
        # 부하 중 액추에이터 응답이 늦어 한두 샘플을 놓치는 것은 정상이다.
        # 빈칸으로 남기고 계속 간다 — 여기서 죽으면 측정 전체를 다시 돌려야 한다.
        return $null
    }
}

# 워커 풀의 상한은 부하 중 변하지 않는다. 한 번만 읽고 매 행에 같은 값을 적는다.
$tomcatMax = Get-MetricValue "tomcat.threads.config.max"

"timestampMs,tomcatBusy,tomcatMax" | Out-File -FilePath $OutFile -Encoding utf8

while (-not (Test-Path $StopFile)) {
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $busy = Get-MetricValue "tomcat.threads.busy"

    "$ts,$busy,$tomcatMax" | Out-File -FilePath $OutFile -Encoding utf8 -Append
    Start-Sleep -Milliseconds $IntervalMs
}
