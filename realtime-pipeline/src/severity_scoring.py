"""
심각도 스코어링: 정규화 + 가중합 + 등급 산출 (2026-07-24 밤 article_signal 구조로 개정).

## 이번 개정에서 바뀐 것 (곱셈 상호작용항 도입 + stock_volatility 제외)

이전 버전은 goldstein/tone/news_count/stock_volatility 4개를 전부 따로 가중치
곱해서 더했다. 두 가지 문제가 실측으로 확인돼서 구조를 바꿨다.

1. **tone과 news_count는 곱해야 의미가 생긴다.** 참고논문
   "Interpretable Machine Learning for Macro Alpha: A News Sentiment Case Study"의
   SHAP 분석에서 최상위 예측 피처가 `article_impact = 평균감정 × log(뉴스량)`이라는
   결합 피처였다(둘을 따로 넣은 게 아니라 곱한 것). 톤이 부정적이어도 기사 1건뿐이면
   약하게, 기사가 많아도 톤이 중립이면 약하게 — 덧셈으로 각각 가중치를 주면 한쪽만
   극단이어도 점수가 부풀지만, 곱셈은 둘 다 있어야 점수가 붙는다. 실제로 지난번
   relevance 게이트 도입 전 문제 사례(허리케인 음모론 반박 기사가 톤만 극단적으로
   부정적이라 심각도 1위를 찍은 것)도 이 덧셈 구조 때문이었다.
2. **stock_volatility_20d는 15분 단위 관제에 안 맞는다.** 일봉 기준 20일 이동
   표준편차라 해상도가 하루 단위보다 촘촘해질 수 없고, 실측 확인 결과 같은 날짜 안
   이벤트들의 고유값 개수가 평균 1.77개인데 이벤트 수는 평균 4.77건 — 즉 같은 날 터진
   서로 다른 사건들이 거의 같은 값을 공유해서 개별 이벤트를 구분하는 힘이 없다.
   4차 XGBoost 도메인분류기에서도 이 피처(및 bdi_pct_change/bdi_zscore_20d)가
   mutual information은 높게 나왔지만 date_ord(날짜) 하나로 예측한 MI가 0.82로
   나와서 "사건 내용"이 아니라 "날짜 대리변수"에 가깝다는 게 확인됐고, 실제로 빼고
   재학습해도 macro F1이 안 바뀌었다(0.429→0.431, 오차범위). base_score에서 빼고
   9단계 Agent 브리핑에 "요즘 시장 국면" 참고정보로만 남기기로 함(재구현은 별도).

## 새 구조

```
article_signal = norm_tone × norm_news_count                      # 곱셈 상호작용항
base_score = WEIGHT_GOLDSTEIN × norm_goldstein + WEIGHT_ARTICLE_SIGNAL × article_signal
```

gdacs_alert_level 하드 게이트, is_supply_chain_relevant 하드 게이트는 유지.
rainfall 소프트 게이트는 2026-07-24 밤 검증 중 제거함 — 아래 "게이트 검증" 절 참고.

## 게이트 검증 (2026-07-24 밤)

호우(rainfall) 소프트 게이트로 '정상→주의' 승격된 49건 중 47건을 샘플링했더니
전부 인도네시아 수출금지·해운지수 같은 강수와 무관한 정책기사였다. 원인은
`rainfall_24h_mm`이 사건이 실제로 벌어진 위치가 아니라 "국가+날짜" 단위로 조인돼
있어서(인도네시아 기준 날짜당 고유값 1.05개, 이벤트 수는 1.43개 — 사실상 나라
단위 대리변수) — stock_volatility_20d를 뺀 것과 같은 이유로 제거함.

GDACS 하드 게이트는 relevant==True 중 해당 건이 1건뿐이라 이번엔 통계적으로
검증 불가능했음(그 1건도 게이트 없이 base_score만으로 이미 '심각'이었어서 게이트가
실제로 등급을 바꾼 사례가 없었음) — 구조는 유지하되 "검증된 적 없다"는 점은
남겨둘 것. 표본이 쌓이면 재검증 필요.

## 가중치 (합 100, relevant==True 4,081건 실측 분포로 재보정)

  - goldstein 70 (GDELT 자체가 정의하는 사건 강도 지표, 항상 존재하는 가장 직접적인 신호)
  - article_signal 30 (톤×보도량 결합 신호, 위 논문 근거)

relevant==True 기준 base_score 분포: mean 54.0, std 15.1, min 35.0, max 87.1.
(article_signal은 39.3%가 정확히 0 — 톤이 중립/긍정인 관련 기사는 goldstein만으로 점수를 받음)

## 정규화 방식

원본 피처들은 단위가 제각각이라(goldstein -10~+10, news_count는 왜곡된
분포 등) 가중치를 곱하기 전에 반드시 0~1로 맞춰야 한다 — 안 그러면 가중치가
아니라 원래 값의 크기가 결과를 지배한다.

기준(caps)은 두 가지 방식을 섞어 씀:
  - 이론적으로 정해진 범위가 있는 값(goldstein, gdacs)은 이론값 기준
  - 그렇지 않은 값(news_count)은 data_core/event_features.csv 실측 분포의
    95~99 percentile을 캡으로 사용 (극단치 하나가 정규화 전체를 찌그러뜨리지 않게)

gdacs_alert_level/rainfall_24h_mm은 2026-07-24에 build_features.py의 조인 버그를
고친 뒤 재생성한 값 기준. 그 전 데이터로 이 캡을 다시 잡으면 안 됨(그때는 거의 0이었음).

## 임계치 (base_score 기준 percentile 재캘리브레이션, relevant==True 4,081건 실측)

90th pct ≈ 75.0 → CRITICAL_MIN=75, 70th pct ≈ 66.5 → WARNING_MIN=67로 반올림 확정.
게이트가 이 위에 얹히므로 실제 '심각'/'주의' 비중은 10%/20%보다 약간 높아짐
(의도된 동작 — GDACS/호우 게이트가 percentile 밖 이벤트를 끌어올리기 때문).
재보정하려면 이 파일 상단 상수만 바꾸고 `python src/severity_scoring.py`를
재실행해서 분포를 다시 확인할 것.
"""
import os

import pandas as pd
import numpy as np

from country_code_mapper import to_iso_code

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FEATURES_PATH = os.path.join(BASE_DIR, "data_core", "event_features.csv")

# --- 정규화 기준값 ---
GOLDSTEIN_MAX_ABS = 10.0          # GDELT Goldstein Scale 공식 정의 범위: -10 ~ +10
TONE_MAX_ABS = 1.0                # tone_score(LLM 추출)는 이미 대략 -1~1 스케일
NEWS_COUNT_LOG_CAP = np.log1p(16)  # NumArticles 99th percentile(16건) 기준 log1p 캡
GDACS_MAX = 2.0                   # 실측 데이터 상 관측된 최댓값(Green=1/Orange=2, Red=3은 미관측)
RAINFALL_HEAVY_MM = 20.0          # "호우" 기준 20mm/24h (기상청 주의보 기준과 유사한 수준) 이상이면 1
STOCK_VOL_CAP = 36.7              # stock_volatility_20d 95th percentile 기준

# --- base_score 가중치 (합 100) ---
WEIGHT_GOLDSTEIN = 70.0
WEIGHT_ARTICLE_SIGNAL = 30.0        # article_signal = norm_tone × norm_news_count (곱셈)

# --- 물리적 재해 게이트 (희소하지만 확실한 신호, base_score와 별도) ---
GDACS_HARD_GATE_LEVEL = 2          # gdacs_alert_level >= 2(Orange) → 무조건 '심각'
# RAINFALL_SOFT_GATE: 2026-07-24 밤 검증 후 제거함.
# rainfall_24h_mm이 사건 위치가 아니라 "국가+날짜" 단위로 조인돼 있어서(인도네시아 기준
# 날짜당 고유값 1.05개, 이벤트 수는 1.43개 — 사실상 나라 단위 대리변수), 실제로 승격된
# 49건을 샘플링해보니 47건이 수출금지·해운지수 같은 강수와 무관한 정책기사였다.
# stock_volatility와 같은 "날짜 대리변수" 문제라 게이트로 쓰기엔 근거가 약함 — 제거.

# --- 등급 임계치 (base_score 90th/70th percentile 캘리브레이션, relevant==True 4,081건 실측) ---
CRITICAL_MIN = 75.0
WARNING_MIN = 67.0


def normalize_features(df: pd.DataFrame) -> pd.DataFrame:
    """
    goldstein/tone은 절댓값이 아니라 "부정적인 쪽만" 정규화한다.
    리스크 심각도를 재는 것이지 화제성/변화폭을 재는 게 아니므로, 긍정적인
    사건(수출금지 해제, 협정 체결 등)은 절댓값이 커도 심각도가 0에 가까워야 한다.
    (실제로 |tone_score|=0.7짜리 "노천광산 금지 해제" 긍정 뉴스가 절댓값 방식에서는
    부정적 뉴스와 동일하게 0.7로 나오는 걸 확인하고 수정함.)
    """
    df = df.copy()

    df["norm_goldstein"] = (-df["GoldsteinScale"]).clip(lower=0) / GOLDSTEIN_MAX_ABS
    df["norm_goldstein"] = df["norm_goldstein"].clip(0, 1)
    df["norm_tone"] = (-df["tone_score"]).clip(lower=0) / TONE_MAX_ABS
    df["norm_tone"] = df["norm_tone"].clip(0, 1)
    df["norm_news_count"] = (np.log1p(df["NumArticles"]) / NEWS_COUNT_LOG_CAP).clip(0, 1)
    df["norm_gdacs"] = (df["gdacs_alert_level"] / GDACS_MAX).clip(0, 1)
    df["rainfall_threshold_exceeded"] = (df["rainfall_24h_mm"] >= RAINFALL_HEAVY_MM).astype(int)
    df["norm_stock_volatility"] = (df["stock_volatility_20d"] / STOCK_VOL_CAP).clip(0, 1)

    return df


def compute_severity(df: pd.DataFrame) -> pd.DataFrame:
    """정규화된 df에 base_score/severity_score/severity_tier/reason_codes를 추가한다.

    normalize_features()가 먼저 실행돼 norm_* 컬럼이 있어야 한다.

    ## Relevance 하드 게이트 (2026-07-24 추가)

    `is_supply_chain_relevant == False`인 행은 LLM이 이미 "공급망 리스크와 무관"으로
    판정한 기사다. 이 게이트를 넣기 전에는 이 값을 전혀 안 보고 goldstein/tone만으로
    점수를 매겨서, '심각' 등급 674건 중 264건(39.2%)이 실제로는 무관 기사였다
    (예: 허리케인 음모론 반박 기사가 톤이 워낙 부정적이라 severity_score 1위를 찍음).
    그래서 무관 기사는 severity_tier를 아예 계산하지 않고 '해당없음'으로 고정한다
    — 관제 알림 대상 자체가 아니므로 등급을 매기는 것 자체가 무의미하다.

    CRITICAL_MIN/WARNING_MIN 임계치는 relevant==True 4,081건만의 base_score
    percentile로 계산했다(90th≈75.0, 70th≈66.5). article_signal 도입으로 base_score
    분포 자체가 바뀌어서 이전 버전(68/58)과 숫자가 다름 — 상단 docstring 참고.
    """
    df = df.copy()

    df["article_signal"] = df["norm_tone"].fillna(0.0) * df["norm_news_count"]
    df["base_score"] = (
        WEIGHT_GOLDSTEIN * df["norm_goldstein"]
        + WEIGHT_ARTICLE_SIGNAL * df["article_signal"]
    )

    is_relevant = df["is_supply_chain_relevant"] == True  # noqa: E712 (명시적 True 비교로 NaN/False 구분)

    gdacs_gate = df["gdacs_alert_level"].fillna(0) >= GDACS_HARD_GATE_LEVEL

    def _tier(row) -> str:
        if not row["_is_relevant"]:
            return "해당없음"
        if row["_gdacs_gate"]:
            return "심각"
        if row["base_score"] >= CRITICAL_MIN:
            return "심각"
        if row["base_score"] >= WARNING_MIN:
            return "주의"
        return "정상"

    df["_is_relevant"] = is_relevant
    df["_gdacs_gate"] = gdacs_gate
    df["severity_tier"] = df.apply(_tier, axis=1)

    # severity_score: 게이트가 걸린 건은 등급에 맞게 base_score를 바닥값으로 끌어올려
    # "게이트 때문에 승격됐는데 점수는 낮게 남아있는" 모순을 없앤다.
    # 무관 기사는 점수 자체가 오해를 부르므로 NaN 처리 (알림/랭킹 어디에도 노출 안 함).
    df["severity_score"] = df["base_score"]
    df.loc[gdacs_gate, "severity_score"] = df.loc[gdacs_gate, "base_score"].clip(lower=CRITICAL_MIN)
    df.loc[~is_relevant, "severity_score"] = np.nan
    df["severity_score"] = df["severity_score"].round(2)

    def _reasons(row) -> str:
        if not row["_is_relevant"]:
            return "NOT_RELEVANT"
        if row["_gdacs_gate"]:
            return "GDACS_HARD_GATE"
        return "BASE_SCORE_ONLY"

    df["severity_reason_codes"] = df.apply(_reasons, axis=1)
    df = df.drop(columns=["_is_relevant", "_gdacs_gate"])

    # country_iso: build_ontology_graph.assess_risk(country=...)가 ERP country_code
    # (ISO alpha-2)로 국가를 매칭하는데, 여기 country는 GDELT/LLM 자유텍스트라 그대로
    # 못 넘긴다. 매핑 실패는 None으로 남겨서(억지 추정 안 함) 그래프 연동 시 걸러내도록 함.
    df["country_iso"] = df["country"].apply(to_iso_code)

    return df


def main():
    df = pd.read_csv(FEATURES_PATH)
    df = normalize_features(df)
    df = compute_severity(df)

    norm_cols = [
        "norm_goldstein", "norm_tone", "norm_news_count",
        "norm_gdacs", "rainfall_threshold_exceeded", "norm_stock_volatility",
    ]
    print(f"[정규화 완료] {len(df)}건")
    print(df[norm_cols].describe().T)

    print(f"\n[심각도 분포] (해당없음 = is_supply_chain_relevant==False)")
    print(df["severity_tier"].value_counts())
    print(f"\nrelevant==True 중 base_score 분포")
    print(df.loc[df["is_supply_chain_relevant"] == True, "base_score"].describe())

    out_path = os.path.join(BASE_DIR, "data_core", "event_features_normalized.csv")
    df.to_csv(out_path, index=False, encoding="utf-8-sig")
    print(f"\n[저장 완료] {out_path}")


if __name__ == "__main__":
    main()
