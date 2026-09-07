# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
COPY src ./src

# gradlew 는 Windows 에서 커밋되어 git index 의 모드가 100644 다.
# 실행 비트가 없으므로 리눅스에서 그대로는 "Permission denied" 로 죽는다.
RUN chmod +x gradlew

# 테스트는 GitHub Actions 가 이미지 빌드 이전 단계에서 이미 돌린다.
# 여기서 또 돌리면 배포 시간만 두 배가 되고, 실패 지점도 두 곳으로 흩어진다.
RUN ./gradlew --no-daemon bootJar -x test

# 레이어드 jar 를 풀어 의존성과 애플리케이션 코드를 분리한다.
# extract 결과의 application/app.jar 는 매니페스트 Class-Path 가 lib/ 를 가리키는 얇은 jar 라,
# 런타임 이미지에서도 app.jar 와 lib/ 의 상대 위치를 그대로 유지해야 한다.
RUN java -Djarmode=tools -jar build/libs/app.jar extract --layers --destination extracted

# SNAPSHOT 의존성이 생기면 별도 레이어로 떨어지지만 Class-Path 는 여전히 lib/ 하나만 가리킨다.
# 지금은 비어 있어도, 나중에 의존성이 추가됐을 때 조용히 클래스가 사라지지 않도록 미리 합쳐 둔다.
RUN mkdir -p extracted/dependencies/lib extracted/snapshot-dependencies/lib \
    && cp -r extracted/snapshot-dependencies/lib/. extracted/dependencies/lib/

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# 애플리케이션을 root 로 돌릴 이유가 없다.
RUN groupadd --system spring && useradd --system --gid spring spring

# 자주 바뀌지 않는 의존성을 먼저 넣는다. 소스만 바뀐 커밋에서 이 레이어(수십 MB)가 재사용된다.
COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/lib ./lib
COPY --from=build --chown=spring:spring /workspace/extracted/application/app.jar ./app.jar

USER spring
EXPOSE 8080

# 힙 크기는 ECS 태스크 정의의 JAVA_TOOL_OPTIONS(-XX:MaxRAMPercentage) 가 결정한다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
