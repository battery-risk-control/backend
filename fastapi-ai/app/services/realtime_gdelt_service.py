"""
GDELT 실시간 조회 → 트리아지(XGBoost) → 크롤링. (팀원 realtime-pipeline 브랜치 포팅, 2026-07-27)

Spring의 CollectionService.runSource("GDELT")가 이 서비스를 호출해서 크롤링된 후보 기사
목록을 받고, 그 뒤 dedup·저장·F3 분석 트리거·커서 갱신은 Spring이 기존 로직 그대로
처리한다 — 여기서는 "이 시점 이후 새로 크롤링할 가치가 있는 기사가 뭔지"만 책임진다.

LLM 추출/심각도 계산은 하지 않는다 — Spring이 크롤링된 기사를 F3(POST /api/v1/analyze)로
넘기면 그 안에서 (OPENAI_API_KEY가 설정된 경우) 실제 LLM 추출과 새 심각도 공식이 적용된다.

한 번 호출에 처리하는 시간 구간을 제한한다(PENDING_SLOTS_CAP) — Spring의 HTTP 타임아웃
안에서 끝나야 하고, 밀린 분량은 다음 주기 호출에서 이어서 처리된다(완전 백필은 하지 않음).
"""
import io
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta

import pandas as pd
import requests

from app.models.triage_filter import should_crawl

LASTUPDATE_URL = "http://data.gdeltproject.org/gdeltv2/lastupdate.txt"
GDELT_EXPORT_BASE = "http://data.gdeltproject.org/gdeltv2/"

PENDING_SLOTS_CAP = 4       # 15분 단위 4구간(1시간)만 한 번의 호출에서 처리 — 나머지는 다음 주기가 이어받음
MAX_CRAWL_PER_RUN = 30
CRAWL_WORKERS = 10
CRAWL_TIMEOUT_SEC = 8
MIN_ARTICLE_LEN = 200

GDELT_COLUMNS = [
    "GlobalEventID", "Day", "MonthYear", "Year", "FractionDate", "Actor1Code", "Actor1Name",
    "Actor1CountryCode", "Actor1KnownGroupCode", "Actor1EthnicCode", "Actor1Religion1Code",
    "Actor1Religion2Code", "Actor1Type1Code", "Actor1Type2Code", "Actor1Type3Code", "Actor2Code",
    "Actor2Name", "Actor2CountryCode", "Actor2KnownGroupCode", "Actor2EthnicCode",
    "Actor2Religion1Code", "Actor2Religion2Code", "Actor2Type1Code", "Actor2Type2Code",
    "Actor2Type3Code", "IsRootEvent", "EventCode", "EventBaseCode", "EventRootCode", "QuadClass",
    "GoldsteinScale", "NumMentions", "NumSources", "NumArticles", "AvgTone", "Actor1Geo_Type",
    "Actor1Geo_FullName", "Actor1Geo_CountryCode", "Actor1Geo_ADM1Code", "Actor1Geo_ADM2Code",
    "Actor1Geo_Lat", "Actor1Geo_Long", "Actor1Geo_FeatureID", "Actor2Geo_Type", "Actor2Geo_FullName",
    "Actor2Geo_CountryCode", "Actor2Geo_ADM1Code", "Actor2Geo_ADM2Code", "Actor2Geo_Lat",
    "Actor2Geo_Long", "Actor2Geo_FeatureID", "ActionGeo_Type", "ActionGeo_FullName",
    "ActionGeo_CountryCode", "ActionGeo_ADM1Code", "ActionGeo_ADM2Code", "ActionGeo_Lat",
    "ActionGeo_Long", "ActionGeo_FeatureID", "DATEADDED", "SOURCEURL",
]
TRIAGE_COLS = [
    "GlobalEventID", "GoldsteinScale", "NumArticles", "AvgTone",
    "Actor1Type1Code", "Actor2Type1Code", "EventCode", "ActionGeo_CountryCode",
]


class CrawledCandidate(dict):
    """{global_event_id, title, content, source_url, action_geo_country_code} 형태의 결과 1건."""


def get_latest_gdelt_url() -> str:
    resp = requests.get(LASTUPDATE_URL, timeout=30)
    resp.raise_for_status()
    return resp.text.strip().split("\n")[0].split(" ")[2]


def extract_ts_from_url(url: str) -> str:
    fname = url.strip().rsplit("/", 1)[-1]
    return fname.split(".", 1)[0]


def compute_pending_timestamps(last_ts: str | None, latest_ts: str) -> list[str]:
    """last_ts 이후부터 latest_ts까지 15분 간격 타임스탬프 목록. 최대 PENDING_SLOTS_CAP개만 반환
    (한 번의 HTTP 호출 안에서 끝나야 하므로 완전 백필은 하지 않고, 나머지는 다음 호출에서 이어감)."""
    latest_dt = datetime.strptime(latest_ts, "%Y%m%d%H%M%S")
    if last_ts is None:
        return [latest_ts]

    last_dt = datetime.strptime(last_ts, "%Y%m%d%H%M%S")
    if last_dt >= latest_dt:
        return []

    timestamps = []
    cur = last_dt + timedelta(minutes=15)
    while cur <= latest_dt and len(timestamps) < PENDING_SLOTS_CAP:
        timestamps.append(cur.strftime("%Y%m%d%H%M%S"))
        cur += timedelta(minutes=15)
    return timestamps


def fetch_events_for_ts(ts: str) -> pd.DataFrame | None:
    url = f"{GDELT_EXPORT_BASE}{ts}.export.CSV.zip"
    r = requests.get(url, timeout=60)
    if r.status_code == 404:
        return None
    r.raise_for_status()
    z = zipfile.ZipFile(io.BytesIO(r.content))
    with z.open(z.namelist()[0]) as f:
        return pd.read_csv(f, sep="\t", header=None, names=GDELT_COLUMNS, low_memory=False)


def _crawl_one(global_event_id: str, url: str, country_code: str) -> CrawledCandidate | None:
    from newspaper import Article, Config

    try:
        config = Config()
        config.request_timeout = CRAWL_TIMEOUT_SEC
        article = Article(url, config=config)
        article.download()
        article.parse()
        title = (article.title or "").strip()
        text = (article.text or "").strip()
        if len(text) < MIN_ARTICLE_LEN:
            return None
        return CrawledCandidate(
            global_event_id=global_event_id, title=title, content=text,
            source_url=url, action_geo_country_code=country_code,
        )
    except Exception:
        return None


def fetch_and_triage(cursor_value: str | None) -> tuple[list[CrawledCandidate], str | None]:
    """반환: (크롤링된 후보 목록, 새 커서 값). 처리할 신규 구간이 없으면 ([], None)."""
    latest_url = get_latest_gdelt_url()
    latest_ts = extract_ts_from_url(latest_url)
    pending = compute_pending_timestamps(cursor_value, latest_ts)
    if not pending:
        return [], None

    all_candidates: list[CrawledCandidate] = []
    last_processed_ts = None

    for ts in pending:
        if len(all_candidates) >= MAX_CRAWL_PER_RUN:
            break
        df = fetch_events_for_ts(ts)
        last_processed_ts = ts
        if df is None or df.empty:
            continue

        mask = should_crawl(df[TRIAGE_COLS])
        candidates = df[mask].reset_index(drop=True)
        candidates = candidates[candidates["SOURCEURL"].notna()]
        remaining_budget = MAX_CRAWL_PER_RUN - len(all_candidates)
        candidates = candidates.head(remaining_budget)

        with ThreadPoolExecutor(max_workers=CRAWL_WORKERS) as pool:
            futures = [
                pool.submit(
                    _crawl_one, str(int(row.GlobalEventID)), str(row.SOURCEURL),
                    str(row.ActionGeo_CountryCode) if pd.notna(row.ActionGeo_CountryCode) else None,
                )
                for row in candidates.itertuples()
            ]
            for fut in as_completed(futures):
                result = fut.result()
                if result is not None:
                    all_candidates.append(result)

    return all_candidates, last_processed_ts
