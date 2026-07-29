import math

from app.schemas.analyze import FeatureVector, SeverityResult
from app.schemas.common import Severity

RULE_VERSION = "severity-rule-v0.2-realtime"

# realtime-pipeline 브랜치(src/severity_scoring.py)에서 포팅.
# relevant==True 4,081건 실측 base_score 퍼센타일로 캘리브레이션됨(90th≈75.0, 70th≈66.5).
CRITICAL_MIN = 75.0
WARNING_MIN = 67.0

GOLDSTEIN_MAX_ABS = 10.0           # GDELT Goldstein Scale 공식 정의 범위: -10 ~ +10
TONE_MAX_ABS = 1.0                 # tone_score(LLM 추출)는 이미 대략 -1~1 스케일
NEWS_COUNT_LOG_CAP = math.log1p(16)  # NumArticles 99th percentile(16건) 기준 log1p 캡
GDACS_HARD_GATE_LEVEL = 2          # gdacs_alert_level >= 2(Orange) → 무조건 '심각'

WEIGHT_GOLDSTEIN = 70.0             # GDELT 자체가 정의하는 사건 강도 지표
WEIGHT_ARTICLE_SIGNAL = 30.0        # article_signal = norm_tone × norm_news_count (곱셈 상호작용항)


def score_severity(features: FeatureVector) -> SeverityResult:
    """F3 심각도는 외부 데이터(뉴스·기상·시장)만으로 계산합니다. ERP 기반 노출도 평가는
    ERP Exposure Agent/F9가 각자 담당하므로 여기서는 다루지 않습니다.

    realtime-pipeline 브랜치가 4,081건 실측으로 검증한 공식으로 교체.
    tone과 news_count는 곱해야 의미가 생긴다는 실측 근거(SHAP 분석: article_impact =
    평균감정 × log(뉴스량))로 덧셈 대신 곱셈 상호작용항(article_signal)을 쓰고,
    rainfall_24h_mm/stock_volatility_20d/bdi_index/gdacs_historical_event_count는
    "사건 내용이 아니라 국가+날짜 단위 대리변수일 뿐"이라는 실측 검증 결과로 제거됨
    (근거: src/severity_scoring.py 상단 docstring).
    """
    if not features.is_supply_chain_relevant:
        return SeverityResult(
            severity=Severity.NORMAL, score=0.0, reason_codes=["NOT_RELEVANT"],
            calculation_details={"is_supply_chain_relevant": False}, rule_version=RULE_VERSION,
        )

    norm_goldstein = min(max(-features.goldstein_scale, 0.0), GOLDSTEIN_MAX_ABS) / GOLDSTEIN_MAX_ABS
    norm_tone = min(max(-features.tone_score, 0.0), TONE_MAX_ABS) / TONE_MAX_ABS
    norm_news_count = min(math.log1p(max(features.news_count, 0)) / NEWS_COUNT_LOG_CAP, 1.0)
    article_signal = norm_tone * norm_news_count

    base_score = WEIGHT_GOLDSTEIN * norm_goldstein + WEIGHT_ARTICLE_SIGNAL * article_signal

    calculation_details = {
        "norm_goldstein": round(norm_goldstein, 4),
        "article_signal": round(article_signal, 4),
        "base_score": round(base_score, 1),
    }

    reasons = []
    gdacs_gate = features.gdacs_alert_level >= GDACS_HARD_GATE_LEVEL
    if gdacs_gate:
        score = max(base_score, CRITICAL_MIN)
        reasons.append("GDACS_HARD_GATE")
        # 이 게이트는 기사 내용이 아니라 국가 단위 GDACS 경보 수준만 보고 걸린다(판정 로직은 안 바꿈 —
        # 왜 강제 상향됐는지만 사람이 읽을 수 있게 설명 추가). 쉼표는 넣지 않는다 — Spring이
        # analyses.reason_codes를 다시 읽을 때 ","로 split하므로 쉼표가 있으면 그 파싱이 깨진다.
        reasons.append(
            f"국가 단위 GDACS 재난경보(레벨 {features.gdacs_alert_level})로 인해 기사 내용과 무관하게 "
            f"강제 상향됨(원 계산 점수 {round(base_score, 1)}점)"
        )
    else:
        score = base_score
        reasons.append("BASE_SCORE_ONLY")

    score = round(min(score, 100.0), 1)
    calculation_details["final_score"] = score

    severity = (
        Severity.CRITICAL if score >= CRITICAL_MIN
        else Severity.WARNING if score >= WARNING_MIN
        else Severity.NORMAL
    )
    return SeverityResult(
        severity=severity, score=score, reason_codes=reasons,
        calculation_details=calculation_details, rule_version=RULE_VERSION,
    )
