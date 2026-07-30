# 8단계 React 파일 업로드 연결

## 구현 범위

`frontend/`에 C1-B 문서 업로드 화면을 추가했다. 브라우저는 Spring Boot API만 호출하며 FastAPI 또는 ChromaDB 주소를 알지 못한다.

| 기능 | 구현 위치 | 동작 |
| --- | --- | --- |
| 문서 업로드 화면 | `frontend/src/App.jsx` | 파일과 계약·공급사·자재·문서 유형을 입력받는다. |
| Spring API 호출 | `frontend/src/documentApi.js` | Bearer 토큰을 포함해 `POST /api/v1/documents`와 `GET /api/v1/documents/{document_id}`만 호출한다. |
| 업로드 진행률 | `frontend/src/documentApi.js` | `XMLHttpRequest.upload.onprogress`로 전송률을 표시한다. |
| 파일 사전 검증 | `frontend/src/documentApi.js` | PDF/TXT 확장자, MIME, 빈 파일, 10MB 제한을 브라우저에서 먼저 검사한다. Spring에서도 동일 검증을 다시 수행한다. |
| 상태 표시 | `frontend/src/App.jsx` | `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, 화면 전용 `DUPLICATE`를 표시한다. |
| 상태 복원 | `frontend/src/App.jsx` | 마지막 `document_id`를 저장하고 새로고침 시 Spring 상태 API로 다시 조회한다. |
| 인증 연계 | `frontend/src/App.jsx` | C2 로그인 화면이 저장할 `localStorage.access_token`을 재사용한다. 현재 독립 검증을 위해 토큰 입력란도 제공한다. |

## 실행

Spring Boot가 `localhost:8080`에서 실행 중인 상태에서 다음 명령을 실행한다.

```powershell
cd C:\aivleschool\bigproject\battery-risk-mvp-starter\frontend
npm.cmd install
npm.cmd run dev
```

브라우저에서 `http://localhost:5173`을 연다. 로컬 개발 서버는 `/api` 요청만 Spring Boot `http://localhost:8080`으로 프록시한다.

배포 환경에서는 필요할 때만 `VITE_SPRING_API_BASE_URL`에 Spring API 주소를 설정한다. FastAPI 주소를 프론트 환경 변수에 넣지 않는다.

## 상태 처리 방식

현재 Spring `POST /api/v1/documents`는 FastAPI 처리가 끝날 때까지 기다리는 동기 API다. 따라서 화면은 파일 전송 중 `PENDING`, 전송 완료 후 응답 대기 중 `PROCESSING`을 표시하고, Spring 응답을 받으면 실제 `processing_status`를 표시한다.

응답이 `PENDING` 또는 `PROCESSING`인 경우에는 1.5초 간격으로 상태 API를 조회한다. `COMPLETED` 또는 `FAILED`가 되면 조회를 종료한다. 중복 응답의 `duplicate=true`는 DB 상태를 바꾸지 않고 화면에서만 `DUPLICATE`로 표시한다.

서버의 실제 상태 전이를 업로드 직후부터 폴링하려면 향후 Spring 업로드를 비동기 접수 방식으로 바꾸어 `document_id`를 먼저 반환해야 한다. 이는 8단계 React 범위에는 포함하지 않았다.

## 검증 명령

```powershell
npm.cmd run test
npm.cmd run build
npm.cmd audit
```

- 파일 검증 단위 테스트: 2개 통과
- Vite production build: 통과
- npm 취약점: 0개
- 소스 내 FastAPI URL: 없음

## 실제 화면 E2E 전제 조건

PostgreSQL, ChromaDB, FastAPI, Spring Boot, React를 실행하고 유효한 C2 access token과 존재하는 `contract_id`, `supplier_id`, `material_id`를 사용한다. 그 후 정상 PDF/TXT, 중복 파일, 잘못된 파일, 새로고침 복원을 브라우저에서 확인한다.
