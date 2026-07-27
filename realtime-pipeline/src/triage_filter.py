"""
사전 필터(triage) 추론 모듈.

train_triage_filter.py가 저장한 models/triage_filter.json(부스터) +
models/triage_filter_meta.json(임계값/카테고리 사전)을 로드해서,
"이 이벤트를 크롤링할 가치가 있는가"를 판단하는 should_crawl()만 제공한다.

결정 규칙: (모델 확률 >= 임계값) OR (핵심 생산국 화이트리스트) → 크롤링.
핵심 생산국은 모델 점수와 무관하게 항상 통과시킨다 — 배터리 5대 원자재의
실제 생산국에서 나는 이벤트를 XGBoost가 놓쳐서 영구 누락시키는 리스크가
크롤링 비용 절감보다 훨씬 비싸기 때문.
"""
import json
import os

import pandas as pd
import xgboost as xgb

BASE = os.path.join(os.path.dirname(__file__), "..")
MODEL_PATH = os.path.join(BASE, "models", "triage_filter.json")
META_PATH = os.path.join(BASE, "models", "triage_filter_meta.json")

# data_prep/bigquery_historical_pull.py의 MINING_COUNTRIES 중
# 배터리 5대 원자재 핵심 생산국만 재사용 (FIPS 10-4 코드)
CORE_PRODUCER_WHITELIST = {
    "ID": "니켈(인도네시아)",
    "CG": "코발트(DRC)",
    "CI": "리튬(칠레)",
    "AR": "리튬(아르헨티나)",
    "AS": "리튬/니켈(호주)",
    "CH": "흑연(중국)",
    "SF": "망간(남아프리카공화국)",
}

_model = None
_meta = None


def _load():
    global _model, _meta
    if _model is not None:
        return
    with open(META_PATH, "r", encoding="utf-8") as f:
        _meta = json.load(f)
    _model = xgb.XGBClassifier(enable_categorical=True, tree_method="hist")
    _model.load_model(MODEL_PATH)


def _build_features(df: pd.DataFrame) -> pd.DataFrame:
    _load()
    num_cols = _meta["num_cols"]
    cat_cols = _meta["cat_cols"]
    categories = _meta["categories"]

    X = pd.DataFrame(index=df.index)
    for col in num_cols:
        X[col] = pd.to_numeric(df[col], errors="coerce").fillna(0)
    for col in cat_cols:
        if col == "EventCode":
            # 학습 때와 동일하게 정수 문자열로 통일 (172.0 -> "172", 172 -> "172")
            numeric = pd.to_numeric(df[col], errors="coerce")
            vals = numeric.apply(lambda x: str(int(x)) if pd.notna(x) else "unknown")
        else:
            vals = df[col].fillna("unknown").astype(str)
        # 학습 시 카테고리 순서로 고정 — 처음 보는 값은 전부 NaN(=unknown 취급)으로 인코딩됨
        X[col] = pd.Categorical(vals, categories=categories[col])
    return X[num_cols + cat_cols]


def should_crawl(df: pd.DataFrame) -> pd.Series:
    """df는 최소한 GoldsteinScale, NumArticles, AvgTone, Actor1Type1Code,
    Actor2Type1Code, EventCode, ActionGeo_CountryCode 컬럼을 가져야 함.
    반환값: df와 같은 인덱스의 bool Series (True=크롤링 대상)."""
    _load()
    X = _build_features(df)
    proba = _model.predict_proba(X)[:, 1]
    model_pass = proba >= _meta["threshold"]
    whitelist_pass = df["ActionGeo_CountryCode"].isin(CORE_PRODUCER_WHITELIST)
    return pd.Series(model_pass, index=df.index) | whitelist_pass
