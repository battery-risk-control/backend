# severity-rule-v1 회귀 기준선 (ERP 엔진 교체 전 스냅샷)

- 측정일: 2026-07-29
- 대상: `SeverityService.evaluate()` — `POST /api/v1/internal/severity/score`
- `rule_version`: **`severity-rule-v1`**
- 목적: ERP 노출도 계산을 **soojung `erp_calculator`(erp-exposure-v0.1)로 단일화**할 때,
  등급·점수가 얼마나 달라지는지 비교할 **before 기준선**을 남긴다.
  (F5 브리핑 / F7 근거계보 / F11 대시보드가 이 값에 의존하므로 변화량 설명이 필요)

## 결과 (2026-07-29 기준)

| # | 시나리오 | 등급 | 점수 | 근거 코드 |
|---|---|---|---|---|
| S1 | 정상(재고 충분) | `NORMAL` | 0.0 | `NO_RISK_RULE_TRIGGERED` |
| S2 | 재고 < 안전재고 | `WARNING` | 30.0 | `INVENTORY_BELOW_SAFETY` |
| S3 | 재고 < 안전재고 절반 | `WARNING` | 35.0 | `INVENTORY_BELOW_HALF_SAFETY` |
| S4 | 복합위험(재고+공백+의존+가격+물류) | `CRITICAL` | 100.0 | `INVENTORY_BELOW_HALF_SAFETY`, `LONG_SUPPLY_GAP`, `VERY_HIGH_SUPPLIER_DEPENDENCY` … |
| S5 | **FEOC=YES (F8 하드게이트)** | `CRITICAL` | **100.0** | **`FEOC_HARD_GATE`** |
| S6 | 데이터 없음 | `UNKNOWN` | 0.0 | `INSUFFICIENT_DATA` |
| S7 | 품질 INVALID | `UNKNOWN` | 0.0 | `INVALID_DATA_QUALITY` |
| S8 | GDACS Red만(재고 정상) | `WARNING` | 40.0 | `GDACS_RED_ALERT` |

## 관찰 포인트 (교체 시 반드시 확인할 것)

1. **S5 — F8의 고유 가치**
   재고 40일·의존도 0.1·공백 0으로 **다른 모든 지표가 정상**인데도 FEOC 하나로 `CRITICAL 100`이 됩니다.
   현재 `erp_calculator`에는 FEOC 규칙이 **없으므로**, 이관하지 않으면 이 시나리오가 `NORMAL`로 떨어집니다.
   → `erp_rules.yaml`의 `forcedCriticalRules`에 FEOC 추가 필요.

2. **S8 — GDACS 인코딩 확인**
   `gdacs_alert_level=2` → `GDACS_RED_ALERT`. 즉 **v1도 `2 = Red`**로 해석합니다.
   (Spring `HistoricalFeatureJoinService`의 `Red→2 / Orange→1`과 일치.
   `severity_engine.py:16` 주석의 "2(Orange)"만 어긋남 — 별건 확인 필요)

3. **S2/S3 — 임계값 차이**
   v1은 `WARNING ≥ 30 / CRITICAL ≥ 70` 합산 방식.
   `erp_calculator`는 `weights`(supplyGap 0.35 / safetyStock 0.20 / dependency 0.20 / poDelay 0.15 / altSupplier 0.10) 가중합 + `exposureLevelThresholds`.
   → **같은 상황에 다른 점수**가 나오므로 대시보드 집계 분포가 바뀝니다.

4. **S6/S7 — UNKNOWN 처리**
   `erp_calculator`도 `ExposureLevel.UNKNOWN` + `evaluateDataQuality`가 있어 대응 가능.

## 재현 방법

스택이 떠 있는 상태에서(비용 발생 없음, OpenAI 미사용):

```bash
docker exec battery-risk-fastapi curl -s -X POST \
  http://localhost:8000/api/v1/internal/severity/score \
  -H "Content-Type: application/json" \
  -d '{"inventory_days":40,"safety_stock_days":15,"expected_supply_gap_days":0,
       "supplier_dependency_ratio":0.1,"price_change_rate":0.0,"logistics_delay_days":0,
       "gdacs_alert_level":0,"feoc_status":"YES","data_quality_status":"VALID"}'
```

전체 8종은 `feoc_status`/`data_quality_status`/재고 수치를 위 표대로 바꿔 반복.

### 시나리오 입력값
| # | 입력 |
|---|---|
| S1 | inv 40 / safety 15 / gap 0 / dep 0.3 / price 1.0 / delay 0 / gdacs 0 / FEOC NO / VALID |
| S2 | inv 12 / safety 15 / 나머지 S1과 동일 |
| S3 | inv 5 / safety 15 / 나머지 S1과 동일 |
| S4 | inv 5 / safety 15 / gap 14 / dep 0.85 / price 12.0 / delay 8 / gdacs 1 / FEOC NO / VALID |
| S5 | inv 40 / safety 15 / gap 0 / dep 0.1 / price 0 / delay 0 / gdacs 0 / **FEOC YES** / VALID |
| S6 | (수치 전부 생략) / FEOC NO / **UNKNOWN** |
| S7 | inv 5 / safety 15 / FEOC NO / **INVALID** |
| S8 | inv 40 / safety 15 / gap 0 / dep 0.1 / price 0 / delay 0 / **gdacs 2** / FEOC NO / VALID |

## 교체 후 할 일

1. 동일 8종을 **ERP Exposure Agent**(`POST /api/v1/internal/erp/exposure`)로 실행
   — 입력 형태가 `ErpExposureRequest`라 매핑 필요
2. 아래 표를 채워 **변화량**을 팀에 공유

| # | v1 등급/점수 | erp-exposure-v0.1 등급/점수 | 변화 | 설명 |
|---|---|---|---|---|
| S1~S8 | (위 표) | (측정 후 기입) | | |

3. 특히 **S5(FEOC)**가 `CRITICAL`을 유지하는지 확인 → 유지 못 하면 F8 이관 누락

## 관련 문서
- [surin-merge-plan.md](surin-merge-plan.md) — 병합 경위
- 통합 개발 계획 S11 — `evaluate()`의 설계 근거
- 멀티 에이전트 설계안 §3·§6 — ERP Exposure Agent / 최종 구매 리스크 계산
