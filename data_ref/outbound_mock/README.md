# 아웃바운드 마스터 데이터 (mock)

이 폴더 전체가 가상 데이터다. `backend/data/ERP_data/spring-csv`(인바운드: 공급사→원자재→LG엔솔)와
달리, LG엔솔→배터리제품→완성차고객사 방향은 ERP에 대응하는 테이블이 아예 없다
(고객사/제품 개념 자체가 백엔드에 없음). 생성 스크립트는 `data_prep/generate_outbound_master_data.py`.

## 파일

- `products.csv` — 배터리/구동모터 제품 16종 (`product_id, name_en, name_kr, product_line`)
- `product_composition.csv` — 제품이 쓰는 원자재 카테고리 (`product_id, material_category`,
  다대다). **실제 배터리/모터 화학 지식 기반 추정치이지 ERP/RAG 어디에도 없는 값이다 —
  조원 검증 전까지는 추정치로 취급할 것.** (구 `build_ontology_graph.py`의
  `PRODUCT_COMPOSITION` dict를 대체함)
- `customers.csv` — 완성차/ESS 고객사 22개 (`customer_id, name_en, name_kr, country_code`)
- `outbound_contracts.csv` — 계약 27건. 물량(`quantity_gwh`)/단가(`unit_price_usd_kwh`)/
  배상금(`penalty_pct`, `line_stop_charge_usd`/`line_stop_charge_krw`)/리드타임은
  `contract_id`로 시드 고정한 난수라 재실행해도 값이 같다 — 실제 계약 조건이 아니다.

## 왜 이렇게 설계했는가

여러 고객사가 같은 제품을 공유하도록 일부러 배정했다(예: NCM 9-Series를
GM/기아/Nissan/NIO 4개사가 같이 씀, High-Nickel Pouch를 Ford/현대차/Lucid 3개사가
같이 씀) — "복합 리스크 탐지"(서로 다른 리스크 이벤트가 그래프상 같은 아웃바운드
계약/고객사를 건드리는 경우) 기능이 실제로 발생하는 사례를 만들기 위해서다.

RARE_EARTH 카테고리는 구동모터 부품(`PROD-015`/`PROD-016`, 영구자석 원료인
네오디뮴 등 희토류 기반) 전용으로 신설했다 — 기존엔 이 카테고리를 쓰는 제품이
하나도 없어서 COPPER/ALUMINUM과 함께 아웃바운드 그래프에 도달 자체가 불가능했다.

기존 `rag_dataset/outbound_sales/*.txt`(제품 5/고객사 8/계약 10, 자유텍스트 정규식
파싱)는 이 CSV들로 대체됐다 — `build_ontology_graph.py`가 더 이상 그 폴더를 읽지
않는다. 삭제는 안 했으니 RAG 데모용 자유텍스트가 다시 필요해지면 참고할 수 있다.
