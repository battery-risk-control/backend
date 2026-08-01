# 멀티에이전트 구매 리스크 점수 — 인계 문서

**작성일** 2026-07-31 (PR #11 머지 후 갱신)
**대상** 멀티에이전트 담당자
**목적** 비로그인 대시보드가 쓸 리스크 점수를 저장·조회 가능하게 만들기

---

## 0. 배경

비로그인 대시보드에 **자재별 리스크 점수 카드**와 **KPI 요약**을 붙여야 합니다.
페이지를 열 때마다 LangGraph를 돌릴 수는 없으니(느리고, 비싸고, 값이 매번 달라짐)
**미리 채워둘 저장소**가 필요합니다.

---

## 1. 현재 상태 — 절반은 이미 main에 있습니다

### ✅ 완료 (PR #11, `ba20972`)

| 항목 | 내용 |
|---|---|
| 외부신호 연결 | `analysis_id` → `analyses.severity_score` → `external_signal_score` |
| `NOT_RELEVANT` 차단 | 무관 기사는 LangGraph 진입 전 거부 |
| 저장 테이블 | `procurement_risk_assessments` (V18) |
| 세부 점수 3개 | 전용 컬럼 (`external_signal_score`·`erp_exposure_score`·`contract_gap_score`) |
| `weight_version`·`mock`·`stockout_gate_applied` | 함께 저장 |
| 조회 API | `GET /api/v1/multi-agent/assessments/{id}` |
| **`country` 전송** | ✅ **팀이 이미 수정** — `GenerateRequest`·`Request` 양쪽에 필드 존재 |

> `country` 누락은 KG 게이트를 무력화시키던 문제였는데, 2026-08-01 Docker 실증 중 발견돼
> 이미 고쳐졌습니다. 코드 주석에 *"이 필드가 없어서 Chain B가 실제로는 한 번도 본 파이프라인을
> 안 타고 있었음"*이라고 남아 있습니다.

### ❌ 남은 작업

| # | 항목 | 난이도 |
|---|---|---|
| **A** | 브리핑 산출물 저장 (컬럼 4개) | 소 |
| **B** | `material_category` 등급 조건 해제 | 중 (지도 쿼리 동반 수정) |
| **C** | 스케줄러 | 중 |
| **D** | 중복 사건 제거 | 소 |

**A~D는 서로 의존합니다** — B가 안 되면 C가 정상 등급 뉴스를 못 잡고, D가 없으면 C의 비용이 샙니다.

---

## 2. A — 브리핑 산출물이 생성되고 버려집니다

### 문제

**`generate_briefing_node`는 `use_llm`과 무관하게 항상 브리핑을 만듭니다.**
`use_llm`은 "만들까 말까"가 아니라 **"LLM으로 쓸까, 템플릿으로 조립할까"**의 선택입니다.

```python
def generate_briefing_node(state):
    if state.get("use_llm", False):
        return {"briefing": llm_result["briefing"], "llm_used": True}
    ...
    return {"briefing": briefing, "llm_used": False}   # ← 템플릿 조립본
```

`procurement_risk_assessments`에 저장할 컬럼이 없어 **지금도 버려지고 있습니다.**
점수에서 고쳤던 "응답으로만 나가고 사라진다"가 브리핑 쪽에 그대로 남아 있습니다.

### 해결

V18에 컬럼 4개 추가 (또는 V19 신규).

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `briefing` | TEXT | 본문 |
| `recommended_actions` | JSONB | 권고 조치 |
| `contract_findings` | JSONB | **인용한 계약 조항 — 감사추적의 핵심** |
| `warnings` | JSONB | reviewer 경고 |

`use_llm=false`인 지금도 템플릿 조립본이 저장되고, 나중에 `true`로 켜면 LLM 생성본이
같은 자리에 들어갑니다. **스키마 변경 없이 전환됩니다.**

### 참고 — 템플릿 조립본의 형태

```
[뉴스 ID] / [제목] / [영향 원자재] / [영향 영역]
[위험 단계] / [위험 점수] / [위험도 근거]
[KG 근거] / [ERP 분석] / [계약 근거]
[권고 조치]
```

각 항목이 에이전트 하나의 산출물입니다.

---

## 3. B — `material_category`가 정상 등급 뉴스를 막고 있습니다

### 문제

`analyses.material_category`가 **CRITICAL/WARNING일 때만** 채워집니다.
`AnalysisService`(main 기준 68행)에서 F9 공급사 추천의 부산물로 저장되기 때문입니다.

```java
if (RISKY_SEVERITIES.contains(data.severity().severity())
        && data.affectedMaterials() != null && !data.affectedMaterials().isEmpty()) {
    String materialCategory = data.affectedMaterials().get(0);
    ...
    analysis.attachSupplierRecommendation(materialCategory, ...);  // 여기서만 저장
}
```

```
실측: COMPLETED 중 NORMAL 31건 → material_category 전부 NULL
```

**사실(자재)이 조치(추천)에 묶여 있어, 조치가 없으면 사실도 사라집니다.**
LLM은 모든 기사에서 자재를 뽑아주는데 정상 등급이면 그 값을 버립니다.

### 해결

사실과 조치를 분리합니다. 추가 LLM 비용은 없습니다 — 이미 추출된 값입니다.

```java
// 사실: 등급과 무관하게 저장
if (materialCategory != null) {
    analysis.attachMaterialCategory(materialCategory);
}

// 조치: 위험 등급일 때만 (기존 조건 유지)
if (RISKY_SEVERITIES.contains(...) && materialCategory != null) { ... }
```

### ⚠️ 반드시 함께 고칠 것 — 지도 쿼리

`AnalysisRepository.findRiskBoardCandidates`가
`materialCategory IS NOT NULL`을 **우연히 등급 필터로** 쓰고 있습니다.

```sql
WHERE a.status = 'COMPLETED'
  AND a.severity IS NOT NULL
  AND a.countryCode IS NOT NULL
  AND a.materialCategory IS NOT NULL   -- ← 지금은 이게 등급 필터 역할을 겸함
```

자재 분류를 항상 저장하면 **정상 이벤트까지 지도에 올라옵니다.**
마커 상한(`RISK_BOARD_MAX_MARKERS`)을 정상 이벤트가 먼저 채워
심각 이벤트를 밀어낼 수 있으므로, 등급 조건을 명시해야 합니다.

```sql
AND a.severity IN ('CRITICAL', 'WARNING')
```

---

## 4. C — 스케줄러

### 대상 선정 — 함정 3가지

**① 자재 펼치기**

`analyses.material_id`는 **실데이터에서 전부 NULL**입니다.
버그가 아니라 GDELT 기사에 우리 자재 코드가 없기 때문입니다 —
LLM이 "리튬"까지는 뽑아도 탄산리튬인지 수산화리튬인지는 기사에 없습니다.

그런데 ERP 노드는 구체적 자재가 있어야 재고·계약·대체공급사를 조회합니다.
**`material_category` → ERP 자재로 펼쳐야 합니다.**

```
LITHIUM  → MAT-LI-CARB, MAT-LI-OH   (2개)
GRAPHITE → MAT-GR-NAT, MAT-GR-SYN   (2개)
나머지 6종 → 각 1개
```

**② 공급사는 펼칠 필요 없음**

`ErpRepository.findSupply`가 supplier를 `null`로 받으면
`priority_rank` 순으로 주 공급사를 자동 선택합니다.

**③ 중복 사건 제거 (= D)**

GDELT는 같은 기사를 여러 `GlobalEventID`로 보고합니다.

```
실측: 공급망 관련 분석 6건 → 고유 기사 2건 (67% 중복)
```

`(제목, 자재)`로 묶어 최신 1건만 처리하면 비용이 그만큼 줄고,
KPI의 "전체 리스크 건수"도 같은 사건이 여러 번 세어지는 걸 막습니다.

### 반드시 지킬 것

| | 내용 |
|---|---|
| **멱등성** | `NOT EXISTS`로 이미 점수 낸 분석 제외. append-only라 유니크 제약이 없어 **이게 유일한 방어선** |
| **기본 off** | `@Value` · `application.yml` · `docker-compose` **3곳 일치**. F4에서 이 3곳이 어긋나 자동 분석이 의도치 않게 돈 적 있음 |
| **부분 실패 격리** | 자재 단위 try/catch. ERP Context 부재나 FastAPI 일시 장애로 한 건이 막혀도 배치는 계속 |
| **비용 통제** | batch-size 상한, 주기는 수집(15분)보다 길게, `use_llm=false` |
| **관측성** | 대상 0건일 때도 **`info`로 남길 것**. `debug`면 "스케줄러가 죽었나, 대상이 없나"를 구분할 수 없음 (실제로 겪음) |

---

## 5. 남은 계약 어긋남 — 응답 `kg_*` 필드

요청 `country`는 이미 고쳐졌습니다(1절 참고). 응답 쪽만 남아 있습니다.

| | 상태 |
|---|---|
| 응답 `kg_matched`·`kg_shortage_detected`·`kg_affected_suppliers`·`kg_affected_contract_ids`·`kg_evidence_paths` | Spring `MultiAgentDto.Response`에 필드가 없어 **조용히 버려짐** |

파싱이 깨지지는 않습니다 — 수동 생성한 `RestClient`가 Spring Jackson 기본값을 쓰므로
모르는 필드는 무시됩니다. 다만 **KG 판정 근거가 저장되지 않습니다.**

브리핑 산출물(A)을 저장할 때 함께 처리하면 자연스럽습니다.

---

## 6. ⚠️ 호출자가 둘입니다 — flush 순서 주의

PR #10(Chain A → Chain B 자동 트리거) 이후 `AnalysisService`가 멀티에이전트를 **내부 호출**합니다.
그런데 그 시점엔 분석이 아직 `COMPLETED`로 flush되지 않아 `analysisId` 경로를 못 씁니다.

머지 과정에서 이렇게 처리돼 있습니다.

```java
// analysisId는 null로 둔다: 이 시점엔 analysis가 아직 COMPLETED로 flush되지 않아
// (saveAndFlush는 create()의 이 호출 이후에 일어난다) PR#11의 analysisId 경로를 타면
// ANALYSIS_NOT_SCORED로 막힌다. 기존처럼 값을 직접 실어 보낸다.
new MultiAgentDto.GenerateRequest(
        analysis.getAnalysisId().toString(), null, ...)
//                                          ↑ analysisId
```

**두 경로가 공존합니다.**

| 호출자 | 외부신호 |
|---|---|
| `AnalysisService` (Chain A→B, 실시간) | 값을 직접 전달 |
| 스케줄러 (배치) | `analysis_id`로 조회 |

스케줄러를 만들 때 이 차이를 인지하고 계셔야 합니다.
**flush 순서를 바꿔 통일할지, 두 경로를 유지할지**는 판단이 필요합니다.

---

## 7. 화면이 최종적으로 필요로 하는 계약

나중에 대시보드를 붙일 때 맞춰야 하는 부분입니다.

| | 내용 |
|---|---|
| 자재 | **8종** — `LITHIUM COBALT NICKEL GRAPHITE MANGANESE COPPER ALUMINUM RARE_EARTH` |
| 창 | **Rolling 24시간** (자정 리셋 아님 — 새벽에 화면이 비지 않도록) |
| 자재 카드 | 자재별 등급 + **전일 대비** (어제 같은 창과 비교) |
| KPI 요약 | **뉴스 건수 모수**(중복 제거 후 고유 기사), 등급은 **종합 점수** 기준 |
| 등급 | **3단계** (`NORMAL/WARNING/CRITICAL`). `risk_node.score_to_level`이 이 셋만 반환 — `UNKNOWN` 없음 |
| 공개 subset | `erp_assessment`·`contract_assessment`는 ERP 내부값이라 **제외**. `/public/risk-board`가 이미 쓰는 방침 |

### ⚠️ 저장 입자 — 뉴스 단위로 저장하고, 화면에서 접을 것

**자재별로 접은 값은 저장하지 마세요.** 접는 규칙(24h 최댓값 / 혼합 등)이 아직 확정 전입니다.

- 원본(뉴스 단위)만 저장 → 규칙을 바꿔도 **과거까지 새 규칙으로 다시 접을 수 있음**
- 접은 값까지 저장 → 규칙 변경 시 **과거는 옛 규칙 그대로 남아** 그래프가 튐

성능은 지금 규모(24h 내 수십~수백 행 + 인덱스)에서 문제되지 않습니다.
느려지면 캐시나 스냅샷을 얹으면 되고, 원본이 남아 있어 아무것도 잃지 않습니다.

---

## 8. 참고 — 스케줄러 구현이 브랜치에 있습니다

```
feat/procurement-risk-scheduler   (origin에 push됨)
  39aed92  구매 리스크 점수 자동 축적 스케줄러
  19b58e9  (= PR #11로 main에 머지된 내용)
```

`39aed92`가 위 **C·D**에 해당합니다. 실 PostgreSQL로 검증까지 마쳤습니다.

```
대상 분석 6건 → 저장 12건, 실패 0건 (LITHIUM은 자재 2개)
재시작 후 12행 유지 → 멱등성 확인
가중합 검증: 85×0.35 + 19×0.45 + 30×0.20 = 44.3 → 44 ✅
```

그대로 쓰셔도 되고, 참고만 하셔도 됩니다.
**A·B는 이 브랜치에도 없습니다** (로컬 미커밋 상태였음).

---

## 9. 검증 체크리스트

| 항목 | 확인 방법 |
|---|---|
| 직렬화 필드명 | 실제 `RestClient`를 태워 **직렬화된 본문**을 검사. mock으로는 `@JsonProperty` 오타를 못 잡음 (PR #9에서 `Kg*Fields`가 같은 문제를 밟았음) |
| `NOT_RELEVANT` 차단 | 거부만 확인하면 부족 — **FastAPI 호출 자체가 안 일어나는지** 확인 |
| 멱등성 | 스케줄러 2회 실행 후 행 수 불변 |
| 세부 점수 | 응답 Map의 키는 **snake_case**. `alias_generator=to_camel`은 필드 이름만 바꾸고 dict 내용은 안 건드림 — camelCase로 꺼내면 전부 null인데 에러가 안 남 |
| 마이그레이션 | 실제 PostgreSQL에서 적용되는지. 단위 테스트는 JSONB 캐스팅·CHECK 제약을 검증하지 않음 |
| 브리핑 저장 | `use_llm=false`에서도 `briefing`이 NULL이 아닌지 |

---

## 부록 — 실측 데이터 (2026-07-31 기준)

| 항목 | 값 |
|---|---|
| `analyses` COMPLETED | 37건 |
| 그중 `NOT_RELEVANT` | **30건 (81%)** |
| 공급망 관련 분석 | 7건 |
| 그중 고유 기사 | **2~3건 (62~67% 중복)** |
| NORMAL 중 `material_category` 있음 | **0건** |
| ERP 자재 대분류 | 8종 / 활성 자재 10개 |
