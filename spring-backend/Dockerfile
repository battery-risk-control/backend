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
COPY --from=erpseed . /app/erp-seed
COPY --from=ragseed . /app/rag-seed
# 납품(outbound) 계약 시드 — ERP CSV(계약·고객사·제품)와 계약서 문서(.txt).
# 이미지에 함께 굽는 이유: 배포(ECS)에는 볼륨 마운트가 없어, 여기 없으면 시드가 읽을 파일이
# 없다. 실제로 배포 화면의 "제품 납품 계약"이 텅 비어 있던 원인이 이 두 COPY의 부재였다
# (2026-08-16 — 인바운드 erpseed/ragseed만 굽고 outbound는 빠져 있었다).
COPY --from=outbounderpseed . /app/outbound-erp-seed
COPY --from=outboundragseed . /app/outbound-rag-seed

# ECS 태스크 정의에 별도 변수가 없어도 이미지에 포함된 계약 원본을 부팅 때 확인한다.
# DB metadata는 남고 /app/uploads만 비워진 재배포에서도 RAG 중복 감지가 누락 원본을 복구한다.
# 로컬 compose는 env로 false를 명시해 덮으므로 로컬 기본 동작은 바뀌지 않는다.
ENV RAG_SEED_ENABLED=true \
    RAG_SEED_DIRECTORY=/app/rag-seed \
    OUTBOUND_ERP_SEED_ENABLED=true \
    OUTBOUND_ERP_SEED_DIRECTORY=/app/outbound-erp-seed \
    OUTBOUND_RAG_SEED_ENABLED=true \
    OUTBOUND_RAG_SEED_DIRECTORY=/app/outbound-rag-seed

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
