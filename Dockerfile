# 수집기 · 적재 처리기 이미지를 이 파일 하나로 만든다 (#170, monimo-shop #42 와 같은 방식).
# 레포 루트에서 하나만 빌드할 때 :  docker build --target collector -t monimo/collector .
# --target 을 빼면 맨 마지막 단계(ingester)가 만들어진다. 늘 --target 을 준다
# compose · CI 는 서비스마다 target 만 골라 쓴다
#
# 단계 연결 :  build ──▶ runtime ──▶ collector · ingester
# Gradle 은 build 단계에서 한 번만 돈다. 서비스마다 Dockerfile 을 두던 때처럼 Gradle 둘이 동시에 CPU 를 다 쓰지 않는다.
# 서비스를 이미지로 더 만들 때는 build 의 gradlew 줄에 :<모듈>:bootJar 를 더하고 맨 아래에 target 단계를 하나 더한다

# 1단계: Gradle 로 실행용 jar 만들기 (테스트는 CI build 잡 몫이라 bootJar 만)
# 캐시 마운트 : Gradle 이 받은 배포판 · 라이브러리(/root/.gradle)를 이미지 층 밖에 보관한다.
# 소스가 바뀌어 이 RUN 이 다시 돌아도 이미 받은 것은 다시 받지 않는다. 이 컴퓨터의 BuildKit 에만 남는
# 로컬 · compose 용 이득이고, CI(gha 캐시)는 레이어 캐시만 옮기고 이 마운트는 옮기지 않는다.
# sharing=locked : 같은 마운트를 쓰는 빌드가 동시에 돌면 차례로 쓰게 한다
# BOOT_JARS : 기본은 둘 다(compose 가 한 번에 쓰게). CI 매트릭스처럼 러너마다 하나만 만들 때는
# --build-arg BOOT_JARS=:collector:bootJar 로 그 모듈만 빌드한다 (다른 target 은 그 빌드에서 못 만든다)
FROM eclipse-temurin:17-jdk AS build
ARG BOOT_JARS=":collector:bootJar :ingester:bootJar"
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew ${BOOT_JARS} --no-daemon

# 2단계: JRE 만 있는 가벼운 실행 이미지 (공통)
FROM eclipse-temurin:17-jre AS runtime
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# 수집기
FROM runtime AS collector
COPY --from=build /workspace/collector/build/libs/*.jar app.jar
# 8081 업무 HTTP(probe /livez · /readyz 포함) · 18081 관리(/actuator/**, 업무 포트 + 10000) · 4317 OTLP gRPC(에이전트 수신)
EXPOSE 8081 18081 4317

# 적재 처리기
FROM runtime AS ingester
COPY --from=build /workspace/ingester/build/libs/*.jar app.jar
# 8082 업무 HTTP(probe /livez · /readyz 포함) · 18082 관리(/actuator/**, 업무 포트 + 10000)
EXPOSE 8082 18082
