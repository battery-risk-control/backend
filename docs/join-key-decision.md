# 분석 ↔ 브리핑 연결 — 선행 결정 정리

작성 2026-07-29 · 상태: **결정 대기(IA 확정 후)**
개정 2026-07-29 — 초판의 "데이터 손실 진행 중" 서술은 사실이 아니어서 정정했습니다(§1-3 참고).

로그인 이후 대시보드에서 "리스크 이벤트 + 그 근거(재고·계약)"를 함께 보여주려면 필요한 결정입니다.
**급하지 않습니다.** IA(계층별 정보구조)가 확정되면 필요 여부부터 자동으로 갈립니다.

---

## 1. 현재 상태

### 1-1. 두 테이블이 서로 다른 기준으로 대상을 지목한다

| 테이블 | 지목 방식 | 실제 값(2026-07-29) |
|---|---|---|
| `analyses` (뉴스 분석) | `analysis_id`(UUID) + `material_category`(문자열) | `"lithium mine halt in Chile"` / `LITHIUM` |
| `briefings` (브리핑) | `material_id` + `supplier_id` (BIGINT FK) | `4`(Nickel Sulfate) + `5` |
| `severity_assessments` | `material_id` + `supplier_id` | 〃 |

`analyses`에 `material_id`·`supplier_id` 컬럼이 있긴 하나 **38건 전부 NULL**입니다.
뉴스 수집 경로(`CollectionService.triggerAnalysis`)가 `Analysis.pending(null, null, ...)`로 생성하기
때문이며, 뉴스에서 특정 공급사를 지목할 근거가 없으므로 버그가 아닙니다.

### 1-2. 애초에 이어진 적이 없다

`BriefingService.generate()` 호출자는 **`BriefingController` 하나뿐**이고, 요청에 분석을 가리키는
필드가 없습니다.

```java
GenerateRequest(erpMaterialId, erpSupplierId, asOf, priceChangeRate, ...)
//              ↑ 자재 ERP ID   ↑ 공급사 ERP ID     — analysis_id 자리 없음
```

즉 **분석이 브리핑을 만드는 경로가 존재하지 않습니다.** 브리핑은 담당자가 자재+공급사를 지정해
직접 만드는 기능입니다. 두 기능이 서로 다른 진입점에서 독립적으로 설계된 결과입니다.

### 1-3. 따라서 "데이터 손실"은 없다 (초판 정정)

초판에서 *"브리핑 생성 시 어느 분석이 촉발했는지를 버리고 있어 복구 불가"* 라고 적었으나
**사실이 아닙니다.** 호출자가 애초에 분석 정보를 넘기지 않으므로 버려지는 연결이 없습니다.
결정을 미뤄도 잃는 것이 없습니다.

### 1-4. 다만 별개 사항 — 브리핑 개념이 두 개다

| | Spring `briefings` (F5) | FastAPI 브리핑 |
|---|---|---|
| 생성 주체 | 사람이 `POST /api/v1/briefings` 호출 | 분석 중 자동 (`generate_briefing: True`가 기본값) |
| 기준 | 자재 + 공급사 | 뉴스 이벤트 |
| DB 저장 | ✅ `briefings` 테이블 | ❌ 저장 안 됨 (FastAPI는 DB 미접근) |
| 결과 | 계약 근거·재고 요약 등 실데이터 | `briefing_id`만 반환 → Spring이 버림 |

`analyses`에 `briefing_id` 컬럼이 없어 FastAPI가 반환한 id는 즉시 사라집니다. 그 브리핑은
어디에도 저장되지 않으므로 **잃는 것은 없지만**, 매 분석마다 쓰이지 않을 브리핑을 만들고 있습니다.

> **별도 검토 사항**: `/analyze` 호출 시 `generate_briefing`을 `false`로 보내 불필요한 생성을
> 없앨지 판단이 필요합니다. 이 문서의 결정과는 독립적입니다.

---

## 2. 결정해야 할 것 — 순서가 있습니다

### 🔴 1순위 (IA 논의에서 답이 나옴)

> ### 뉴스 분석이 브리핑을 자동으로 만들어야 하는가?

리스크 이벤트 화면에서 뉴스를 클릭했을 때 **재고 일수·계약 조항 같은 근거가 자동으로 따라 나와야
하는가**와 같은 질문입니다.

| 답 | 결과 |
|---|---|
| **아니요** — 브리핑은 담당자가 필요할 때 직접 만드는 기능 | **연결 불필요.** 아래 2순위는 논의 대상에서 빠지고, 리스크 화면에는 뉴스·등급·대체 공급사만 표시 |
| **네** — 분석이 근거까지 자동 생성 | 연결 필요 → 2순위로 |

### 🟡 2순위 (1순위가 "네"일 때만, 기술 선택)

> 브리핑은 "뉴스 1건당 1개"인가, "자재+공급사 조합당 1개"인가?

현재 구현은 요청 1건당 브리핑 1개이며 요청 단위가 자재+공급사입니다. 즉 **지금은 자재+공급사 단위**입니다.

---

## 3. 2순위 선택지 (참고)

1순위가 "네"로 결정된 뒤에 보시면 됩니다.

### 안 A — `briefings`에 `analysis_id` 컬럼 추가

```sql
ALTER TABLE briefings ADD COLUMN analysis_id UUID;
ALTER TABLE briefings ADD CONSTRAINT fk_briefing_analysis
    FOREIGN KEY (analysis_id) REFERENCES analyses(analysis_id);
CREATE INDEX idx_briefings_analysis ON briefings (analysis_id);
-- 근거 계보를 판정까지 잇는다면
ALTER TABLE severity_assessments ADD COLUMN analysis_id UUID;
```

`NOT NULL`로 두지 않는 이유: 기존 브리핑 1건과, 분석 없이 수동 생성되는 브리핑을 계속 허용해야 합니다.

**영향 파일**

| 파일 | 변경 |
|---|---|
| `dto/BriefingDto.GenerateRequest` | `analysisId`(optional) 추가 |
| `service/BriefingService.generate()` | 받은 `analysisId`를 브리핑·판정에 저장 |
| `service/AnalysisService.create()` | 브리핑 자동 생성을 붙인다면 자기 `analysisId` 전달 |
| `repository/BriefingRepository` | `findByAnalysisId` 추가 |
| `service/RiskEventService.list()` | 분석 → 브리핑 조회로 `erp_view`/`rag_view`/`quality_check` 채움 |

- ✅ 가장 단순. 조회가 FK 한 번
- ✅ `analysis_supplier_recommendations`가 이미 쓰는 패턴과 동일
- ⚠️ 한 분석이 여러 브리핑을 낳으면 같은 `analysis_id` 행이 여러 개 (조회를 `List`로 받으면 실무상 무리 없음)

### 안 B — 매핑 테이블

```sql
CREATE TABLE analysis_briefing_links (
    analysis_id UUID NOT NULL REFERENCES analyses(analysis_id),
    briefing_id UUID NOT NULL REFERENCES briefings(briefing_id),
    linked_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_analysis_briefing_links PRIMARY KEY (analysis_id, briefing_id)
);
```

- ✅ N:M을 정직하게 표현. 브리핑 재사용도 담김
- ⚠️ 조회 한 단계 증가, 링크 생성 책임 주체가 애매해지기 쉬움
- ⚠️ 현재 1요청 1브리핑이라 **N:M을 쓸 상황이 아직 없음**(과설계 위험)

### 안 C — 느슨한 조인 (권장하지 않음)

`material_category` + 시간 근접으로 짝 추정. 마이그레이션은 0이지만, 같은 날 같은 자재 뉴스가 2건이면
규칙이 없어 **엉뚱한 근거가 조용히 화면에 뜹니다.** 근거 계보(F7)의 신뢰성이 무너지므로 기록용으로만 남깁니다.

### 비교

| 기준 | 안 A | 안 B | 안 C |
|---|---|---|---|
| 마이그레이션 | 1개 (ALTER) | 1개 (CREATE) | 없음 |
| 조회 복잡도 | 낮음 | 중간 | 낮음 |
| 정확성 | 정확 | 정확 | **부정확 가능** |
| 기존 패턴 일관성 | 높음 | 신규 패턴 | — |
| 과설계 위험 | 낮음 | **있음** | — |

**권고: 안 A.** N:M이 실제로 필요해지면 A→B 이전은 데이터 손실 없이 가능하지만 반대는 어렵습니다.

---

## 4. 기존 데이터

`briefings` 1건 / `severity_assessments` 1건뿐이라 마이그레이션 부담이 없습니다.
기존 행은 `analysis_id = NULL`로 둡니다 — `BriefingService.buildLineage()`가 이미
`assessment_id == null`인 옛 브리핑을 "계보 없음"으로 처리하고 있으므로 같은 방식이면 됩니다.

---

## 5. 결정 기록

| 항목 | 내용 |
|---|---|
| 1순위 — 분석이 브리핑을 자동 생성하는가 | (미정) |
| 2순위 — 연결 방식 (A/B) | (1순위가 "네"일 때만) |
| 결정일 | |
| 결정자 | |

결정 후 할 일(1순위가 "네"인 경우):
1. 마이그레이션 V15 작성
2. `BriefingService.generate()`가 `analysis_id`를 저장하도록 수정
3. `AnalysisService.create()`에 브리핑 자동 생성 연결
4. `RiskEventService.list()` 실데이터 전환

## 6. 관련 문서

- `docs/api-inventory-for-ia.md` — 계층별 IA 확립용 API 인벤토리 (이 결정의 상위 입력)
