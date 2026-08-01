"""XGBoost 트리아지 필터 스모크 테스트.

트리아지는 F4 수집의 첫 관문이다(Spring GdeltRealtimeTriageAdapter →
POST /internal/realtime-pipeline/fetch-and-triage → realtime_gdelt_service.fetch_and_triage →
should_crawl). 그런데 이 경로는 지금까지 테스트가 한 건도 없었다.

모델 파일이 빠지거나, meta의 categories 사전이 학습 시점과 어긋나거나,
realtime_gdelt_service.TRIAGE_COLS가 모델이 기대하는 피처와 갈라지면
should_crawl()이 처음 불리는 순간에야 터진다 — 그때는 수집 전체가 FAILED로 떨어지고
원인이 스택 깊은 곳에 있어 찾기 어렵다.

여기서는 "모델이 로드되는가 / 계약된 컬럼으로 예측이 나오는가 / 화이트리스트 안전장치가
살아 있는가"만 빠르게 확인한다. 모델의 예측 품질(정확도·재현율)은 대상이 아니다 —
그건 학습 쪽에서 볼 일이고, 여기서 특정 확률값에 기대면 재학습할 때마다 테스트가 깨진다.
"""
import json
import os

import pandas as pd
import pytest

from app.models import triage_filter
from app.models.triage_filter import (
    CORE_PRODUCER_WHITELIST,
    META_PATH,
    MODEL_PATH,
    should_crawl,
)
from app.services.realtime_gdelt_service import TRIAGE_COLS


@pytest.fixture(scope="module")
def meta() -> dict:
    with open(META_PATH, "r", encoding="utf-8") as file:
        return json.load(file)


def build_events(meta: dict, country_codes: list[str]) -> pd.DataFrame:
    """학습에 실제로 쓰인 범주값으로 GDELT 이벤트 DataFrame을 만든다.

    범주값을 하드코딩하지 않고 meta에서 꺼내 쓴다 — 재학습으로 사전이 바뀌어도
    이 헬퍼는 따라간다. 사전에 없는 값을 넣고 싶은 테스트는 직접 지정한다.
    """
    categories = meta["categories"]

    return pd.DataFrame(
        {
            "GlobalEventID": [1_000_000 + index for index in range(len(country_codes))],
            "GoldsteinScale": [-5.0] * len(country_codes),
            "NumArticles": [12] * len(country_codes),
            "AvgTone": [-6.5] * len(country_codes),
            "Actor1Type1Code": [categories["Actor1Type1Code"][0]] * len(country_codes),
            "Actor2Type1Code": [categories["Actor2Type1Code"][0]] * len(country_codes),
            "EventCode": [int(categories["EventCode"][0])] * len(country_codes),
            "ActionGeo_CountryCode": country_codes,
        }
    )


def test_model_artifacts_exist() -> None:
    """부스터와 meta 두 파일이 모두 있어야 한다. 하나만 빠져도 로드가 실패한다.

    Dockerfile이 `COPY app ./app`으로 통째 복사하므로 두 파일이 app/models 안에
    있는 한 이미지에 들어간다 — 위치를 옮기면 이 테스트가 먼저 걸린다.
    """
    for path in (MODEL_PATH, META_PATH):
        assert os.path.isfile(path), f"트리아지 산출물이 없습니다: {path}"
        assert os.path.getsize(path) > 0, f"트리아지 산출물이 비어 있습니다: {path}"


def test_meta_declares_every_feature_column(meta: dict) -> None:
    """meta가 피처 계약을 온전히 선언하는지 본다.

    _build_features()는 categories[col]을 무조건 참조하므로, cat_cols에 있는데
    categories에 없으면 KeyError로 죽는다.
    """
    for key in ("threshold", "num_cols", "cat_cols", "categories"):
        assert key in meta, f"meta에 {key}가 없습니다."

    assert meta["num_cols"], "수치 피처가 하나도 선언되지 않았습니다."
    assert meta["cat_cols"], "범주 피처가 하나도 선언되지 않았습니다."

    missing = set(meta["cat_cols"]) - set(meta["categories"])
    assert not missing, f"categories 사전에 없는 범주 피처가 있습니다: {sorted(missing)}"

    for column, values in meta["categories"].items():
        assert values, f"{column}의 범주 목록이 비어 있습니다."


def test_threshold_is_a_probability(meta: dict) -> None:
    """임계값이 확률 범위를 벗어나면 전량 통과 또는 전량 차단이 된다."""
    threshold = meta["threshold"]
    assert 0.0 < threshold <= 1.0, f"threshold가 확률 범위를 벗어났습니다: {threshold}"


def test_pipeline_column_contract_matches_model_features(meta: dict) -> None:
    """realtime_gdelt_service.TRIAGE_COLS가 모델 입력을 모두 담고 있는지 본다.

    TRIAGE_COLS는 meta와 별개로 하드코딩돼 있어서, 재학습으로 피처가 늘면
    fetch_and_triage()가 df[TRIAGE_COLS]로 잘라 넘길 때 그 컬럼이 빠진 채 들어가
    _build_features()에서 KeyError가 난다. ActionGeo_CountryCode는 피처이자
    화이트리스트 판정에도 쓰이므로 반드시 포함돼야 한다.
    """
    required = set(meta["num_cols"]) | set(meta["cat_cols"]) | {"ActionGeo_CountryCode"}
    missing = required - set(TRIAGE_COLS)
    assert not missing, (
        "TRIAGE_COLS에서 모델 입력 컬럼이 빠졌습니다: "
        f"{sorted(missing)} — realtime_gdelt_service.TRIAGE_COLS를 맞춰야 합니다."
    )


def test_should_crawl_returns_aligned_boolean_series(meta: dict) -> None:
    """모델이 실제로 로드되고 예측이 나오는지 — 스모크의 핵심."""
    events = build_events(meta, ["US", "FR", "JA"])
    events.index = [10, 20, 30]  # 인덱스를 보존하는지 확인하려고 일부러 어긋나게 둔다

    result = should_crawl(events[TRIAGE_COLS])

    assert isinstance(result, pd.Series)
    assert list(result.index) == [10, 20, 30], "입력 인덱스를 보존해야 df[mask]가 성립한다."
    assert result.dtype == bool
    assert len(result) == len(events)


def test_core_producer_countries_always_pass(meta: dict) -> None:
    """핵심 생산국 화이트리스트는 모델 점수와 무관하게 통과해야 한다.

    배터리 5대 원자재 생산국 이벤트를 모델이 놓쳐 영구 누락시키는 리스크가
    크롤링 비용보다 비싸다는 게 이 안전장치의 근거다(triage_filter 모듈 docstring).
    OR 조건이 실수로 AND가 되거나 화이트리스트가 비면 조용히 사라지는 종류의 보호라
    회귀 방지 장치를 둔다.
    """
    countries = sorted(CORE_PRODUCER_WHITELIST)
    assert countries, "핵심 생산국 화이트리스트가 비어 있습니다."

    # 모델이 가장 낮은 점수를 줄 법한 밋밋한 이벤트로 채워도 통과해야 한다.
    events = build_events(meta, countries)
    events["GoldsteinScale"] = 10.0
    events["NumArticles"] = 1
    events["AvgTone"] = 10.0

    result = should_crawl(events[TRIAGE_COLS])

    blocked = [
        country for country, passed in zip(countries, result) if not passed
    ]
    assert not blocked, f"핵심 생산국이 트리아지에서 걸러졌습니다: {blocked}"


def test_whitelist_only_adds_never_removes(meta: dict) -> None:
    """화이트리스트는 OR이지 AND가 아니다 — 모델 통과분을 빼앗으면 안 된다.

    위의 화이트리스트 테스트는 "7개국이 통과하는가"만 보는데, 재학습으로 7개국이
    전부 자력 통과하게 되면 OR를 AND로 바꿔도 그 테스트는 계속 초록으로 남는다.
    (작성 시점 기준으로는 AR·AS·SF가 모델 단독으로는 탈락해 화이트리스트가 실제로 구제 중이다.)
    여기서는 모델 확률과 직접 비교해 OR 의미론 자체를 고정한다.
    """
    countries = sorted(CORE_PRODUCER_WHITELIST) + ["US", "FR", "JA", "UK", "GM"]
    events = build_events(meta, countries)
    columns = events[TRIAGE_COLS]

    triage_filter._load()
    features = triage_filter._build_features(columns)
    model_pass = triage_filter._model.predict_proba(features)[:, 1] >= meta["threshold"]

    result = should_crawl(columns)

    rejected = [
        country
        for country, passed_model, passed_triage in zip(countries, model_pass, result)
        if passed_model and not passed_triage
    ]
    assert not rejected, (
        "모델이 통과시킨 이벤트를 트리아지가 걸러냈습니다 — OR가 AND로 바뀌었는지 "
        f"확인하십시오: {rejected}"
    )


def test_non_whitelist_rows_follow_the_model_threshold(meta: dict) -> None:
    """화이트리스트 밖에서는 판정이 실제 모델 확률에서 나오는지 확인한다.

    특정 확률값을 기대하지 않는다 — 모델이 매긴 확률과 threshold 비교 결과가
    should_crawl()의 출력과 일치하는지만 본다. 재학습해도 깨지지 않으면서
    "화이트리스트가 전부를 통과시켜 모델이 사실상 무력화된" 상태는 잡아낸다.
    """
    non_whitelist = ["US", "FR", "JA", "UK", "GM"]
    assert not (set(non_whitelist) & set(CORE_PRODUCER_WHITELIST))

    events = build_events(meta, non_whitelist)
    columns = events[TRIAGE_COLS]

    triage_filter._load()
    features = triage_filter._build_features(columns)
    probability = triage_filter._model.predict_proba(features)[:, 1]
    expected = probability >= meta["threshold"]

    result = should_crawl(columns)

    assert list(result) == list(expected)


def test_unseen_category_values_do_not_crash(meta: dict) -> None:
    """학습 사전에 없는 범주값과 결측이 들어와도 죽지 않아야 한다.

    GDELT는 새 EventCode·행위자 코드를 계속 만들어낸다. pandas Categorical은
    사전에 없는 값을 NaN으로 만들고 XGBoost가 결측으로 처리하는 구조인데,
    이게 깨지면 수집 도중 특정 구간에서만 터져 재현이 어렵다.
    """
    events = build_events(meta, ["US", "FR"])
    events["Actor1Type1Code"] = ["ZZZ_NOT_TRAINED", None]
    events["Actor2Type1Code"] = [None, "ZZZ_NOT_TRAINED"]
    events["EventCode"] = [999_999, None]

    result = should_crawl(events[TRIAGE_COLS])

    assert len(result) == 2
    assert result.dtype == bool


def test_should_crawl_is_deterministic(meta: dict) -> None:
    """같은 입력은 항상 같은 판정이어야 한다 — 수집 재실행의 전제."""
    events = build_events(meta, ["US", "ID", "FR", "CG"])
    columns = events[TRIAGE_COLS]

    assert list(should_crawl(columns)) == list(should_crawl(columns))
