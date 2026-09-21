# 레포 루트에서 빌드한다:  docker build -f ingester/Dockerfile -t monimo/ingester .

# 1단계: Gradle로 실행용 jar 만들기
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :ingester:bootJar --no-daemon

# 2단계: JRE만 있는 가벼운 실행 이미지
FROM eclipse-temurin:17-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /workspace/ingester/build/libs/*.jar app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
