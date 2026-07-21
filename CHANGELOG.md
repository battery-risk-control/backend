# Changelog

## 2026-07-21 — 백엔드 책임 구조 v0.2 계획 확정

- Spring Boot를 시스템 분석 오케스트레이터 및 PostgreSQL 단일 읽기·쓰기 주체로 재정의
- FastAPI를 LLM·XGBoost·Severity·RAG·브리핑 분석 전용 서비스로 재정의
- FastAPI의 PostgreSQL 직접 접근 계획 제거, ChromaDB 소유권만 유지
- Spring–FastAPI 연동을 2.5순위로 추가하고 분석 상태·실패·저장 흐름 정의
- 자동 분석 시작점을 Spring Scheduler로 변경
- 수정된 전체 To Do List를 `docs/backend-data-modeling-todo-v0.2.md`에 추가

## 2026-07-20 — FastAPI Mock 인터페이스 완성

- extraction, classification, severity, RAG, briefing 스키마를 도메인별 파일로 분리
- 내부 API의 `dict` 응답을 구체적인 Swagger response model로 교체
- 입력 기반 Extraction과 다중 Impact Domain 분류 Mock 구현
- NORMAL/WARNING/CRITICAL Severity 시나리오 및 계산 상세 추가
- `/analyze`의 네 가지 option이 실제 파이프라인 단계를 제어하도록 개선
- TXT/PDF Mock 업로드, chunking, embedding, metadata filter 검색 E2E 구현
- 의존성 주입과 테스트별 In-memory store 격리 추가
- 애플리케이션·validation·예상하지 못한 오류의 공통 envelope 처리
- 7개 API의 OpenAPI request/response schema 자동 검증 추가
- 실제 Uvicorn `/docs` 및 `/openapi.json` HTTP 200 확인, Swagger 화면 캡처 추가

## 2026-07-20 — 프로젝트 루트 통합

- 중첩된 프로젝트 두 벌을 바깥 프로젝트 루트로 평탄화
- 바깥 전용 ML/RAG 구현과 안쪽 서비스·repository 구조를 병합
- 문서, 테스트, Gradle Wrapper 및 설정 파일을 루트 기준으로 통일
- 중첩 원본과 병합 전 바깥 원본을 Git 제외 백업으로 보존
- 평탄화 후 Spring 및 FastAPI 전체 테스트 통과
- 바깥 프로젝트 루트에 `main` 브랜치 Git 저장소 초기화

## 2026-07-20 — FastAPI 교체 가능 구조 추가

- 환경 변수 기반 `core/config.py`와 명시적 Mock mode 추가
- extraction, classification, severity, ERP context 서비스를 분리
- XGBoost 교체 지점인 `ml/inference.py` 추가
- loader, chunker, in-memory vector store와 RAG 서비스 추가
- ERP/Risk repository Protocol 및 in-memory 구현 추가
- `/analyze`와 내부 API를 서비스 계층 기반으로 리팩터링
- 실제 LLM, XGBoost, PostgreSQL, ChromaDB 연동은 보류

## 2026-07-20 — Interface v0.1 보완

- Spring Risk Detail 응답을 명세의 중첩 DTO 구조로 변경
- 리스크·계약·브리핑별 404 오류 코드 추가
- Spring 잘못된 요청 공통 오류 응답과 CORS 설정 추가
- Spring API 통합 테스트 추가
- FastAPI 공통 validation/HTTP 오류 envelope 추가
- FastAPI 내부 개발용 Mock API와 테스트 추가
- 저장소 내 Interface Specification v0.1 문서 추가
