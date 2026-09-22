# 레포 루트에서 빌드한다:  docker build -f collector/Dockerfile -t monimo/collector .

# 1단계: Gradle로 실행용 jar 만들기
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :collector:bootJar --no-daemon

# 2단계: JRE만 있는 가벼운 실행 이미지
FROM eclipse-temurin:17-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /workspace/collector/build/libs/*.jar app.jar
USER app
# 8081 HTTP(상태 확인) · 4317 OTLP gRPC(에이전트 수신)
EXPOSE 8081 4317
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
