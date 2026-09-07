# perf — 측정 하네스

부하를 걸고 지표를 긁는 도구들입니다. **왜 그 조건으로 재는지**는 여기가 아니라
[`docs/benchmark/`](../docs/benchmark/)에 있습니다. 이 디렉터리는 **어떻게 돌리는지**만 맡습니다.

```
perf/
├── stub/mappings/geocode.json     WireMock 매핑. 고정 지연 500ms 로 카카오 지오코딩 응답을 흉내 낸다
└── thread-occupancy/
    ├── thread-occupancy.jmx       JMeter 플랜. 시나리오는 -J 프로퍼티로 받는다
    ├── sample-metrics.ps1         워커 점유(tomcat.threads.busy)만 250ms 간격으로 긁는다
    ├── sample-resources.ps1       힙·커넥션 풀·Hikari·호스트 CPU 를 5초 간격으로 긁는다
    ├── summarize.ps1              results/metrics/resources CSV → summary.csv · timeseries.csv
    ├── run.ps1                    워밍업 → 샘플러 → JMeter → 집계까지 한 회차
    ├── run-matrix.ps1             동시성 × 지속시간 매트릭스를 회차마다 앱을 재시작하며 전부
    └── results/                   회차별 산출물 (.gitignore 대상)
```

**샘플러가 둘인 이유** — 샘플러의 요청도 톰캣 워커를 하나 쓴다. 한 루프에서 지표를 열 개 가까이 부르면 그 요청들이 정작 재려는 워커 점유를 부풀린다 (01번 측정에서 busy 가 100 이 아니라 101
로 찍힌 것이 그 흔적이다). 그래서 빠른 루프는 `tomcat.threads.busy` 하나만 보고, 초 단위면 충분한 힙·커넥션 풀은 느린 루프가 따로 맡는다.

## 스레드 점유 측정

```powershell
# 1. 벤치 스택 (기본 스택 + WireMock 스텁 오버레이)
docker compose -f docker-compose.yml -f docker-compose.bench.yml up -d --build

# 2. JMeter 위치
$env:JMETER_HOME = "C:\Users\usr\tools\apache-jmeter-5.6.3"

# 3-a. 한 회차만 (01번 문서의 짧은 버스트: ramp-up 30 + 정상 상태 60)
.\perf\thread-occupancy\run.ps1 -Scenario blocking -Users 100   # 동기 (RestClient)
.\perf\thread-occupancy\run.ps1 -Scenario reactive -Users 100   # 논블로킹 (WebClient)
.\perf\thread-occupancy\run.ps1 -Scenario geocode  -Users 100   # 상용 지오코딩 경로

# 3-b. 10분 지속 부하 한 회차 (01b 문서)
.\perf\thread-occupancy\run.ps1 -Scenario reactive -Users 500 -Rampup 150 -Duration 750

# 3-c. 매트릭스 전체 (3경로 × 100/300/500 × 10분 + 500 구간 2회차 = 12회차, 약 3시간 40분)
.\perf\thread-occupancy\run-matrix.ps1
```

결과는 `results/<시나리오>-u<사용자수>-<타임스탬프>/` 에 남습니다.

| 파일             | 내용                                                                                               |
|------------------|----------------------------------------------------------------------------------------------------|
| `summary.csv`    | 회차 요약. TPS · 지연 p50/p95/p99 · 오류율 · `busyP95` · `utilP95Percent` · 힙/커넥션 풀 시작→종료 |
| `timeseries.csv` | 1분 간격 스냅샷(각 분 표시 ±15초). **추이를 보는 표는 이쪽이다** — 끝 값만으로는 누수를 못 본다    |
| `metrics.csv`    | 250ms 간격 워커 점유 원본                                                                          |
| `resources.csv`  | 5초 간격 힙·커넥션 풀·Hikari·호스트 CPU 원본                                                       |
| `results.csv`    | JMeter 원본 샘플                                                                                   |

`run-matrix.ps1` 은 진행 상황을 `results/matrix-log.csv` 에 회차마다 append 합니다. 중간에 끊겼다면 그 파일에서 마지막 index 를 보고
`-StartAt <다음 index>` 로 이어서 돌리면 됩니다.

**HTML 리포트는 기본으로 만들지 않습니다.** 동시 500 · 10분이면 샘플이 60만 개라 리포트 생성이 측정보다 오래 걸립니다. 필요하면 `-Report` 를 붙이십시오. 표에 넣을 수치는
`summarize.ps1` 이
`results.csv` 에서 직접 뽑으므로 리포트 없이도 전부 나옵니다.

**집계만 다시 돌릴 수 있습니다.** 집계 로직을 고쳤다고 10분짜리 측정을 다시 할 이유는 없습니다.

```powershell
.\perf\thread-occupancy\summarize.ps1 -OutDir perf\thread-occupancy\results\reactive-u500-... `
    -Scenario reactive -Users 500 -Rampup 150
```

바꿀 수 있는 것: `-Users`, `-Rampup`, `-Duration`, `-WarmupSeconds`, `-BaseUrl`, `-Report`. **바꿨다면 문서의 측정 조건 표도 함께 고쳐야 합니다.**
조건을 밝히지 않은 스레드 점유 수치는 아무것도 말하지 않습니다.

### 자주 걸리는 곳

아래는 전부 이 하네스를 실제로 돌리다 걸린 것들이다.

| 증상                                            | 원인                                                                                                                                                                                                                         |
|-------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `JMETER_HOME 을 설정하세요`                     | 2번 단계를 건너뛰었다                                                                                                                                                                                                        |
| `샘플이 하나도 없습니다`                        | `tomcat.threads.*` 가 바인딩되지 않았다. Actuator 노출(`metrics`)뿐 아니라 **Tomcat MBean 레지스트리**(`server.tomcat.mbeanregistry.enabled`)가 켜져 있어야 한다. 벤치 오버레이가 켜 주므로, 오버레이 없이 띄웠는지 확인한다 |
| 세 시나리오의 TPS 가 모두 같은 낮은 값에 묶인다 | **스텁이 병목이다.** 앱이 아니라 WireMock 을 재고 있는 것이다. 스텁 단독 처리량을 먼저 확인한다(아래)                                                                                                                        |
| 오류율이 0% 가 아니다                           | 그 회차는 버린다. 타임아웃으로 끝난 요청이 섞이면 busy 도 응답 시간도 의미를 잃는다                                                                                                                                          |
| 한글이 `異쒕젰` 처럼 깨진다                     | `.ps1` 이 BOM 없이 저장됐다. Windows PowerShell 5.1 은 BOM 이 없으면 ANSI 로 읽는다. **UTF-8 with BOM 으로 저장할 것**                                                                                                       |
| `Unknown arg: localhost` / 키 입력 대기         | `jmeter.bat` 을 거쳤다. `run.ps1` 은 `ApacheJMeter.jar` 를 직접 부른다                                                                                                                                                       |
| 첫 시나리오만 유난히 나쁘다                     | 워밍업이 부족하다. 순서를 바꿔 한 번 더 돌려 확인한다                                                                                                                                                                        |
| 동시 500 인데 TPS 가 1000 근처에 못 간다        | 스텁 천장을 먼저 확인한다(아래). 스텁이 아니라면 앱 쪽 커넥션 풀이다 — Reactor Netty 공용 풀 상한이 **500** 이라(`ConnectionProvider.create("http", 500)`) 동시 500 · 지연 500ms 는 정확히 그 천장에 닿는다                  |
| `summary.csv` 의 힙·커넥션 풀 칸이 비어 있다    | 느린 샘플러가 죽었다. `resources.csv` 를 열어 본다. 커넥션 풀 칸만 비었다면 `docker exec <앱컨테이너> sh -c "cat /proc/net/tcp"` 가 되는지 확인한다                                                                          |
| 경로 C 만 오류율이 튄다                         | 서킷브레이커다. `slowCallDurationThreshold: 2500ms` 를 넘기면 열리고 fallback 이 5xx 를 던진다. 하네스 문제가 아니라 **측정 결과**이므로 그대로 기록한다                                                                     |

**스텁이 병목이 아닌지 먼저 확인하는 법** — 앱을 거치지 않고 스텁만 때린다. 동시 N · 지연 500ms 의 이론적 상한은 `N / 0.5` TPS 다. 동시 100 이면 200 TPS 이고 정상이라면 180
TPS 대가 오류 0% 로, 동시 500 이면 1000 TPS 이고 정상이라면 900 TPS 대가 오류 0% 로 나온다.

```powershell
# 동시 100
java -jar "$env:JMETER_HOME\bin\ApacheJMeter.jar" -n -t perf\thread-occupancy\thread-occupancy.jmx `
    "-Jhost=localhost" "-Jport=8090" "-Jpath=/v2/local/search/address.json" `
    "-Jusers=100" "-Jrampup=5" "-Jduration=35" -l "$env:TEMP\stubcheck.csv"

# 동시 500 — 매트릭스를 돌리기 전에 반드시 여기까지 확인한다.
java -jar "$env:JMETER_HOME\bin\ApacheJMeter.jar" -n -t perf\thread-occupancy\thread-occupancy.jmx `
    "-Jhost=localhost" "-Jport=8090" "-Jpath=/v2/local/search/address.json" `
    "-Jusers=500" "-Jrampup=15" "-Jduration=75" -l "$env:TEMP\stubcheck500.csv"
```

여기서 나온 TPS 가 **그 동시성 구간에서 측정의 천장**이다. 앱이 그보다 낮게 나왔다면 앱을 잰 것이고, 천장에 붙었다면 스텁을 잰 것이다. 그래서 `docker-compose.bench.yml` 은 스텁을
`--container-threads 1000` · `--async-response-threads 500` 으로 띄운다 (동시 100 만 재던 시절의 400/200 으로는 500 구간에서 스텁이 먼저 포화된다).

## SQS 멱등성 정합성 측정

이쪽은 별도 스크립트가 없습니다. 컨테이너 (MySQL·Redis·LocalStack)를 테스트가 직접 띄웁니다.

```powershell
.\gradlew integrationTest --tests "*SqsIdempotencyConsistencyTest" --info
```

조건과 결과 해석은 [`docs/benchmark/02-sqs-idempotency-consistency.md`](../docs/benchmark/02-sqs-idempotency-consistency.md).

## 원본 산출물을 커밋하지 않는 이유

`results/` 는 `.gitignore` 대상입니다. JMeter HTML 리포트 한 회차가 수십 MB이고, 저장소에 필요한 것은 **요약값과 재현 방법**이지 원본 CSV가 아닙니다. 회차를 남기고 싶다면
`summary.csv` 의 값을 `docs/benchmark/` 표에 옮기십시오.
