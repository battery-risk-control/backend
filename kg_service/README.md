# kg_service

지식그래프(KG) 리졸버 서비스. `country + affected_materials`를 받아 `src/build_ontology_graph.py`의
`assess_risk()`를 호출해서 공급사/재고/인바운드 계약/제품/아웃바운드 계약까지 한 번에 전개해 돌려준다.
멀티에이전트(FastAPI, `fastapi-ai/app/services/kg_service_client.py`)와 Spring
(`KgServiceConfig.java`)이 `GET /resolve`로 이 서비스를 호출한다.

## 왜 Docker에 안 들어가 있나

의도된 설계다. `fastapi-ai`(멀티에이전트 본체) 자체 원칙("FastAPI는 ERP 데이터에 직접 접근하지
않는다, Spring이 조회해서 넘겨준 값만 쓴다")과 데이터 소스 경계를 지키기 위해 별도 프로세스로
분리했다. 호스트에서 직접 띄우고, Docker 컨테이너들은 `host.docker.internal:8100`으로 접근한다
(`docker-compose.yml`의 `KG_SERVICE_BASE_URL` 참고).

## 실행 방법

```bash
cd kg_service
pip install -r requirements.txt
PYTHONUTF8=1 python -m uvicorn main:app --host 0.0.0.0 --port 8100
```

Windows에서 `PYTHONUTF8=1`을 꼭 붙일 것 — 안 붙이면 콘솔 코드페이지(949) 영향으로 한글 문자열이
깨진다.

기동 확인:

```bash
curl http://localhost:8100/health
```

## 데이터 소스

`src/build_ontology_graph.py`가 다음 두 경로를 읽는다(기본값은 이 저장소 안의 경로, 환경변수로
덮어쓸 수 있음 — 예: Docker가 실제로 seed하는 로컬 clone과 같은 데이터를 보게 하고 싶을 때).

- `KG_ERP_DIR` (기본: `backend/data/ERP_data/spring-csv`) — 인바운드(공급사→원자재) 마스터 데이터
- `KG_OUTBOUND_DIR` (기본: `backend/data_ref/outbound_mock`) — 아웃바운드(제품→고객사) 목업 데이터

재고/자재소비량이 갱신되면 `kg_service`가 자체 CSV(`06_inventory_snapshots.csv`,
`07_material_consumptions.csv`)에도 반영해야 그래프가 최신 상태를 본다 —
`/admin/append_inventory_snapshot`, `/admin/append_material_consumption` 참고.
