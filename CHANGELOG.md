# Changelog

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
