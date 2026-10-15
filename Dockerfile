# 우리 모듈 다섯(수집기 · 적재 처리기 · API 서버 · 탐지 · 알림)의 이미지를 이 파일 하나로 만든다 (#170, monimo-shop #42 와 같은 방식).
# 레포 루트에서 하나만 빌드할 때 :  docker build --target collector -t monimo/collector .
# --target 을 빼면 맨 마지막 단계(notifier)가 만들어진다. 늘 --target 을 준다
# compose · CI 는 서비스마다 target 만 골라 쓴다
#
# 단계 연결 :  build ─┬─▶ runtime ──▶ collector · ingester · api-server · detector · notifier
#             agent ─┘
# Gradle 은 build 단계에서 한 번만 돈다. 서비스마다 Dockerfile 을 두던 때처럼 Gradle 다섯이 동시에 CPU 를 다 쓰지 않는다.
# 서비스를 이미지로 더 만들 때는 BOOT_JARS 기본값에 :<모듈>:bootJar 를 더하고 맨 아래에 target 단계를 하나 더한다

# 1단계: Gradle 로 실행용 jar 만들기 (테스트는 CI build 잡 몫이라 bootJar 만)
# 캐시 마운트 : Gradle 이 받은 배포판 · 라이브러리(/root/.gradle)를 이미지 층 밖에 보관한다.
# 소스가 바뀌어 이 RUN 이 다시 돌아도 이미 받은 것은 다시 받지 않는다. 이 컴퓨터의 BuildKit 에만 남는
# 로컬 · compose 용 이득이고, CI(gha 캐시)는 레이어 캐시만 옮기고 이 마운트는 옮기지 않는다.
# sharing=locked : 같은 마운트를 쓰는 빌드가 동시에 돌면 차례로 쓰게 한다
# BOOT_JARS : 기본은 다섯 다(compose 가 한 번에 쓰게). CI 매트릭스처럼 러너마다 하나만 만들 때는
# --build-arg BOOT_JARS=:collector:bootJar 로 그 모듈만 빌드한다 (다른 target 은 그 빌드에서 못 만든다)
FROM eclipse-temurin:17-jdk AS build
ARG BOOT_JARS=":collector:bootJar :ingester:bootJar :api-server:bootJar :detector:bootJar :notifier:bootJar"
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew ${BOOT_JARS} --no-daemon

# 2단계: OTel Java Agent 내려받기 (B평면 #213). 버전은 otel/AGENT_VERSION 한 곳에서만 정한다 (쇼핑몰과 같은 값).
# 이 파일만 입력이라 소스가 바뀌어도 다시 받지 않는다
FROM eclipse-temurin:17-jdk AS agent
WORKDIR /otel
COPY otel/AGENT_VERSION .
RUN VERSION="$(tr -d '[:space:]' < AGENT_VERSION)" \
 && curl -fsSL -o opentelemetry-javaagent.jar \
      "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${VERSION}/opentelemetry-javaagent.jar"

# 3단계: JRE + 에이전트 + 에이전트 설정 (다섯 공통)
# 에이전트를 앱보다 먼저 JVM 에 올린다. -javaagent 는 반드시 -jar 앞 (뒤에 쓰면 앱 인자로 넘어가 조용히 무시된다).
# 앱 코드는 에이전트의 존재를 모른다 (ADR #33 비침습). 모듈별 차이(서비스 이름 · 트레이스 끄기)는 target 단계의 ENV 가 준다 :
# OTel 설정 우선순위가 시스템 속성 > 환경변수 > 설정 파일이라 ENV 가 agent.properties 를 덮고, compose · K8s env 는 그 위에 또 덮는다
FROM eclipse-temurin:17-jre AS runtime
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=agent /otel/opentelemetry-javaagent.jar otel/opentelemetry-javaagent.jar
COPY otel/agent.properties otel/agent.properties
USER app
ENTRYPOINT ["java", \
  "-javaagent:/app/otel/opentelemetry-javaagent.jar", \
  "-Dotel.javaagent.configuration-file=/app/otel/agent.properties", \
  "-jar", "/app/app.jar"]

# 4단계: 모듈별 이미지. 서비스 이름(OTEL_SERVICE_NAME)은 applications.name · CH service_name 과 같은 글자다. monimo- 접두로 쇼핑몰(shop-)과 가른다

# 수집기. 자기 스팬("받았다")이 자기 입력으로 돌아오므로 트레이스만 끈다 (지표 · 로그는 보낸다)
FROM runtime AS collector
COPY --from=build /workspace/collector/build/libs/*.jar app.jar
ENV OTEL_SERVICE_NAME=monimo-collector \
    OTEL_TRACES_EXPORTER=none
# 8081 업무 HTTP(probe /livez · /readyz 포함) · 18081 관리(/actuator/**, 업무 포트 + 10000) · 4317 OTLP gRPC(에이전트 수신)
EXPOSE 8081 18081 4317

# 적재 처리기. 자기 스팬("꺼냈다" · "썼다")이 Kafka 를 거쳐 다시 돌아오므로 트레이스만 끈다
FROM runtime AS ingester
COPY --from=build /workspace/ingester/build/libs/*.jar app.jar
ENV OTEL_SERVICE_NAME=monimo-ingester \
    OTEL_TRACES_EXPORTER=none
# 8082 업무 HTTP(probe /livez · /readyz 포함) · 18082 관리(/actuator/**, 업무 포트 + 10000)
EXPOSE 8082 18082

# API 서버
FROM runtime AS api-server
COPY --from=build /workspace/api-server/build/libs/*.jar app.jar
ENV OTEL_SERVICE_NAME=monimo-api-server
# 8080 업무 HTTP(probe /livez · /readyz 포함) · 18080 관리
EXPOSE 8080 18080

# 탐지
FROM runtime AS detector
COPY --from=build /workspace/detector/build/libs/*.jar app.jar
ENV OTEL_SERVICE_NAME=monimo-detector
# 8083 업무 HTTP(probe /livez · /readyz 포함) · 18083 관리
EXPOSE 8083 18083

# 알림
FROM runtime AS notifier
COPY --from=build /workspace/notifier/build/libs/*.jar app.jar
ENV OTEL_SERVICE_NAME=monimo-notifier
# 8084 업무 HTTP(probe /livez · /readyz 포함) · 18084 관리
EXPOSE 8084 18084
