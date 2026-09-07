# 부하가 도는 동안 "느린" 자원 지표를 5초 간격으로 긁어 CSV 로 남긴다.
#
# 왜 샘플러를 둘로 나누는가 — 워커 점유(sample-metrics.ps1)는 250ms 간격이 필요하지만, 힙과
# 커넥션 풀은 초 단위면 충분하다. 두 가지를 한 루프에 넣으면 틱마다 열 번 가까이 액추에이터를
# 부르게 되고, 그 요청들이 톰캣 워커를 점유해 정작 재려는 값을 부풀린다. 그래서 빠른 루프는
# 지표 하나만 보고, 나머지는 이 스크립트가 별도 프로세스로 맡는다.
#
# 커넥션 풀을 왜 /proc 에서 세는가 — 경로 A 는 JDK HttpClient, 경로 B·C 는 Reactor Netty 를 쓴다.
# Micrometer 풀 게이지는 Reactor Netty 쪽에만 있고 그나마 기본으로 꺼져 있어, 세 경로를 같은
# 기준으로 놓을 수 없다. 앱 컨테이너의 /proc/net/tcp 에서 "스텁(8080)으로 나가는 ESTABLISHED
# 소켓 수"를 직접 세면 세 경로가 완전히 같은 잣대 위에 선다. 프로덕션 코드도 건드리지 않는다.
#   필터: state=01(ESTABLISHED) AND 원격 포트=1F90(8080) AND 로컬 포트<>1F90
#   (로컬 포트 8080 은 JMeter 가 들어온 인바운드 연결이므로 빼야 한다)
#
# 출력: timestampMs,heapUsedMb,heapCommittedMb,liveDataSizeMb,stubConnections,
#       hikariActive,hikariIdle,hikariPending,hikariMax,jvmThreadsLive,hostCpuPercent,
#       appCpuPercent,stubCpuPercent
# (컨테이너 CPU 는 docker stats 호출이 비싸 30초마다만 찍는다. 나머지 행은 빈칸이다.)

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$AppContainer = "aetheria-backend",
    [string]$StubContainer = "aetheria-bench-stub",
    [Parameter(Mandatory = $true)][string]$OutFile,
    [Parameter(Mandatory = $true)][string]$StopFile,
    [int]$IntervalMs = 5000,
    [int]$DockerStatsEveryTicks = 6
)

$ErrorActionPreference = "Stop"

function Get-MetricValue([string]$name, [string]$tag) {
    try {
        $uri = "$BaseUrl/actuator/metrics/$name"
        if ($tag) { $uri = "$uri`?tag=$tag" }
        $r = Invoke-RestMethod -Uri $uri -TimeoutSec 5
        return [double]$r.measurements[0].value
    } catch {
        # 지표가 없거나(예: 풀 GC 전의 live data size) 부하 중 응답이 늦으면 빈칸으로 남기고 간다.
        return $null
    }
}

function ConvertTo-Mb($bytes) {
    if ($null -eq $bytes) { return $null }
    return [Math]::Round($bytes / 1MB, 1)
}

# 앱 컨테이너 안에서 스텁으로 나가는 ESTABLISHED 소켓 수를 센다.
# awk 가 이미지에 있다고 가정하지 않으려고 원본을 그대로 받아 PowerShell 에서 판다.
function Get-StubConnectionCount {
    try {
        # 네이티브 명령이 stderr 로 한 줄만 내도 EAP=Stop 에서는 종료성 오류가 된다. 여기서만 낮춘다.
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $raw = & docker exec $AppContainer sh -c "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null"
        $ErrorActionPreference = $prevEap
        if ($LASTEXITCODE -ne 0) { return $null }
        $n = 0
        foreach ($line in $raw) {
            $f = ($line.Trim() -split '\s+')
            # sl local_address rem_address st ...  (헤더 줄은 $f[1] 가 'local_address' 라 파싱에서 걸러진다)
            if ($f.Count -lt 4) { continue }
            $local = $f[1]; $rem = $f[2]; $state = $f[3]
            if ($state -ne "01") { continue }
            if (-not $rem.Contains(":")) { continue }
            $remPort = $rem.Split(":")[1]
            $localPort = $local.Split(":")[1]
            if ($remPort -eq "1F90" -and $localPort -ne "1F90") { $n++ }
        }
        return $n
    } catch {
        return $null
    }
}

# 호스트 CPU. 두 가지를 피해야 한다.
#   1. Get-Counter 의 카운터 경로("\Processor(_Total)\% Processor Time")는 OS 언어에 따라
#      현지화되어 한국어 윈도우에서 실패한다.
#   2. Win32_PerfFormattedData 의 _Total 은 한 번 읽을 때마다 값이 크게 튄다(같은 초에 63% 와 1%
#      를 번갈아 낸다). 그 값으로는 "부하 도구가 병목이었는가" 를 판정할 수 없다.
# 그래서 원시 카운터의 유휴 시간 델타로 직접 계산한다. 언어와 무관하고 값이 안정적이다.
$script:prevCpuRaw = $null
function Get-HostCpuPercent {
    try {
        $r = Get-CimInstance Win32_PerfRawData_PerfOS_Processor -Filter "Name='_Total'" -ErrorAction Stop
        $result = $null
        if ($null -ne $script:prevCpuRaw) {
            $dIdle = [double]($r.PercentIdleTime - $script:prevCpuRaw.PercentIdleTime)
            $dTs = [double]($r.Timestamp_Sys100NS - $script:prevCpuRaw.Timestamp_Sys100NS)
            if ($dTs -gt 0) {
                $result = [Math]::Round([Math]::Max(0.0, 100.0 - (100.0 * $dIdle / $dTs)), 1)
            }
        }
        $script:prevCpuRaw = $r
        return $result
    } catch {
        return $null
    }
}

$header = "timestampMs,heapUsedMb,heapCommittedMb,liveDataSizeMb,stubConnections," +
          "hikariActive,hikariIdle,hikariPending,hikariMax,jvmThreadsLive,hostCpuPercent," +
          "appCpuPercent,stubCpuPercent"
$header | Out-File -FilePath $OutFile -Encoding utf8

$tick = 0
while (-not (Test-Path $StopFile)) {
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

    $heapUsed = ConvertTo-Mb (Get-MetricValue "jvm.memory.used" "area:heap")
    $heapCommitted = ConvertTo-Mb (Get-MetricValue "jvm.memory.committed" "area:heap")
    $liveData = ConvertTo-Mb (Get-MetricValue "jvm.gc.live.data.size" $null)
    $pool = Get-StubConnectionCount
    $hikariActive = Get-MetricValue "hikaricp.connections.active" $null
    $hikariIdle = Get-MetricValue "hikaricp.connections.idle" $null
    $hikariPending = Get-MetricValue "hikaricp.connections.pending" $null
    $hikariMax = Get-MetricValue "hikaricp.connections.max" $null
    $jvmThreads = Get-MetricValue "jvm.threads.live" $null
    $hostCpu = Get-HostCpuPercent

    $appCpu = ""
    $stubCpu = ""
    if (($tick % $DockerStatsEveryTicks) -eq 0) {
        try {
            $prevEap2 = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            $stats = & docker stats --no-stream --format "{{.Name}} {{.CPUPerc}}" $AppContainer $StubContainer
            $ErrorActionPreference = $prevEap2
            foreach ($row in $stats) {
                $parts = ($row.Trim() -split '\s+')
                if ($parts.Count -lt 2) { continue }
                $v = $parts[1].TrimEnd('%')
                if ($parts[0] -eq $AppContainer) { $appCpu = $v }
                elseif ($parts[0] -eq $StubContainer) { $stubCpu = $v }
            }
        } catch { }
    }
    $tick++

    "$ts,$heapUsed,$heapCommitted,$liveData,$pool,$hikariActive,$hikariIdle,$hikariPending,$hikariMax,$jvmThreads,$hostCpu,$appCpu,$stubCpu" |
        Out-File -FilePath $OutFile -Encoding utf8 -Append

    Start-Sleep -Milliseconds $IntervalMs
}
