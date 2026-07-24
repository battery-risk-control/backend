# 프로젝트명
백엔드 코드입니다.

# 기술 스택
- Spring Boot
- FastAPI
- Python
- Java

## 프로젝트 구조
React
  ↓
Spring Boot
  ├─ Dashboard / Risk / Contract / Briefing 조회 API
  ├─ ERP·계약 업무 데이터 관리
  ├─ 사용자·스케줄러 분석 요청 오케스트레이션
  ├─ FastAPI 분석 호출 및 결과 검증
  └─ PostgreSQL 전체 업무·이벤트·AI 결과 읽기·쓰기

사용자 요청 기반 분석

React
  ↓
Spring Boot
  ↓
FastAPI /api/v1/analyze

자동 데이터 수집 및 분석

MVP:
Spring Scheduler
  ↓
Spring 분석 Orchestration Service
  ↓
FastAPI /api/v1/analyze

고도화:
별도 Worker / Message Queue
  ↓
Spring 내부 분석 접수 API
  ↓
FastAPI /api/v1/analyze

FastAPI 분석 Service
  ├─ 뉴스 이벤트 분석
  ├─ LLM 정보 추출
  ├─ 피처 생성
  ├─ XGBoost Impact Domain 분류
  ├─ Severity Rule Engine
  ├─ Spring이 전달한 ERP Context 활용
  ├─ 계약서 RAG 검색
  ├─ 브리핑 생성
  └─ 분석 결과를 Spring Boot에 반환

FastAPI 데이터 접근
  ├─ PostgreSQL 직접 접근 없음
  └─ ChromaDB 계약서 임베딩 저장·검색

Spring Boot는 기업 업무 시스템과 API의 안정적인 중심을 맡고, FastAPI는 AI·ML·RAG 처리에 집중하도록 분리하는 구조이다.

## 코딩 규칙
특별한 이유가 없다면 아래의 규칙을 적용함
Spring Boot → 관련 폴더마다 기능 파일 최대 1개
FastAPI → API, Schema, Service 각각 기능 파일 1개

## 주의사항
