# Battery Supply Chain Multi-Agent Server

ERP 노출도와 계약서 RAG 결과를 결합해 구매팀용 공급망 리스크 브리핑을 생성하는 FastAPI·LangGraph 서버입니다.

뉴스 수집, Impact Domain 분류와 외부 신호 산정은 다른 담당 서비스가 수행합니다. 이 서버는 해당 결과와 Spring Boot가 조회한 ERP 데이터를 입력으로 받으며 업무 DB에 직접 접근하지 않습니다.

## 실행

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python -m uvicorn app.main:app --reload
```

## 주소

- Swagger: http://127.0.0.1:8000/docs
- 상태 확인: http://127.0.0.1:8000/health
- 멀티에이전트 브리핑: `POST /api/v1/briefings`

## 처리 흐름

```text
Spring Boot 요청
→ Supervisor
→ ERP Agent
→ Contract RAG Agent
→ 최종 구매 리스크 규칙
→ Response Agent
→ Reviewer
→ Spring Boot 응답
```

현재 ERP와 계약 검색 내부 구현은 팀원 연동 전 Stub/Mock입니다. 실제 구현이 준비되면 합의된 입출력 형식을 유지한 채 내부 함수만 교체합니다.

## 테스트

```powershell
python -m pytest -v
```
