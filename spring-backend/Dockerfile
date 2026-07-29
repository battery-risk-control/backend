# 1단계: 빌드 — gradle wrapper로 실행 가능한 jar를 만든다.
# JDK(개발 도구 포함)가 필요하므로 무거운 jdk 이미지를 쓴다.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

# 의존성 정의 파일을 먼저 복사하면, 소스만 바뀌었을 때 의존성 다운로드 레이어를 캐시로 재사용한다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# Windows 체크아웃(CRLF)이면 shebang이 깨져 리눅스에서 실행되지 않으므로 CR을 제거한다.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

COPY src ./src
# 테스트는 CI/로컬에서 이미 돌리므로 이미지 빌드 시에는 제외해 시간을 아낀다.
RUN ./gradlew bootJar --no-daemon -x test

# 2단계: 실행 — 빌드 결과 jar만 가벼운 JRE 이미지에 담아 실행한다.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# compose 헬스체크(curl)를 위해 curl만 설치한다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
