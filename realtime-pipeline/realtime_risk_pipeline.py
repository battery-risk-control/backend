"""
GDELT 실시간 15분 주기 공급망 리스크 감지 파이프라인.

흐름: GDELT Events 2.0 raw export(lastupdate.txt -> export.CSV.zip, 15분마다 갱신)
다운로드 -> XGBoost 사전 필터(triage)로 크롤링 대상만 선별 -> 동시 크롤링 ->
크롤링 성공분만 LLM(GPT-4o-mini)로 동시 정보추출 -> 결과를 배치로 CSV 2개에 append
(data_core/cleaned_labeled_articles.csv, data_ref/gdelt_event_metadata.csv).

마지막 성공 처리 시각(processed_events.db의 last_run 테이블)부터 GDELT 최신 시각까지
15분 단위로 밀린 구간을 전부 순차 처리(백필)한다 — 컴퓨터가 몇 시간 꺼져있었어도
그 사이 기사를 놓치지 않기 위함. 단, 72시간(288구간) 상한을 넘는 과거분은 스킵하고
경고만 남긴다(주말 이상 밀리는 극단적 케이스에서 비용/시간 폭주 방지).

실행: cron/작업 스케줄러로 15분마다 실행 (예: */15 * * * * python3 realtime_risk_pipeline.py)
주의: 이 샌드박스 환경은 GDELT/OpenAI 서버 네트워크 접근이 막혀있어 여기서는 실행이 안 됩니다.
"""
import io
import json
import os
import sqlite3
import sys
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta
from typing import List, Literal, Optional

import pandas as pd
import requests
from dotenv import load_dotenv
from openai import OpenAI
from pydantic import BaseModel, Field

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
# override=True: 시스템/사용자 환경변수(예: 로컬 LM Studio 테스트용으로 설정해둔
# OPENAI_API_KEY=lm-studio)가 있으면 python-dotenv 기본값(override=False)에서는
# .env의 실제 키보다 우선시되어 조용히 무시된다. 이 프로젝트의 .env 값이 항상
# 우선하도록 명시.
load_dotenv(os.path.join(BASE_DIR, ".env"), override=True)

sys.path.insert(0, os.path.join(BASE_DIR, "src"))
sys.path.insert(0, os.path.join(BASE_DIR, "data_prep"))
from triage_filter import should_crawl  # noqa: E402
from build_features import main as build_features_main  # noqa: E402
import severity_scoring  # noqa: E402

DB_PATH = os.path.join(BASE_DIR, "processed_events.db")
LABELED_CSV = os.path.join(BASE_DIR, "data_core", "cleaned_labeled_articles.csv")
# 구 bq_metadata_extracted.csv + bq_precrawl_extra_fields.csv를 통합한 파일.
# "bq_" 접두어를 뗀 이유: 이 실시간 파이프라인은 BigQuery를 거치지 않고 GDELT raw export를
# 직접 파싱하므로, 이름이 출처를 잘못 암시하지 않도록 함.
GDELT_METADATA_CSV = os.path.join(BASE_DIR, "data_ref", "gdelt_event_metadata.csv")

LASTUPDATE_URL = "http://data.gdeltproject.org/gdeltv2/lastupdate.txt"
GDELT_EXPORT_BASE = "http://data.gdeltproject.org/gdeltv2/"
CRAWL_WORKERS = 20
LLM_WORKERS = 5
CRAWL_TIMEOUT_SEC = 10
MIN_ARTICLE_LEN = 200
MAX_CHARS_FOR_LLM = 6000  # 토큰/비용 절감 — 본문 앞부분만으로도 판정 가능
BACKFILL_CAP_HOURS = 72  # 이보다 오래 밀렸으면 최신 72시간만 백필하고 그 이전은 스킵
BACKFILL_CAP_SLOTS = BACKFILL_CAP_HOURS * 4  # 15분 단위 288구간

TRIAGE_COLS = [
    "GlobalEventID", "GoldsteinScale", "NumArticles", "AvgTone",
    "Actor1Type1Code", "Actor2Type1Code", "EventCode", "ActionGeo_CountryCode",
]
LABEL_COLUMNS = [
    "GlobalEventID", "is_supply_chain_relevant", "impact_domain_draft",
    "country", "summary_kr", "error_msg", "affected_material",
    "tone_score", "event_type",
]
METADATA_COLUMNS = [
    "GlobalEventID", "SQLDATE", "GoldsteinScale", "NumArticles", "AvgTone",
    "Actor1Type1Code", "Actor2Type1Code", "EventCode", "ActionGeo_CountryCode",
]

# GDELT Events 2.0 전체 61개 컬럼
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


# ── LLM 정보추출 스키마 ──────────────────────────────────────────────
# cleaned_labeled_articles.csv의 기존 9,000여 건 라벨과 동일한 프롬프트/스키마를
# 써야 라벨 분포가 흔들리지 않으므로, data_prep/run_google_news_llm.py의
# 검증된 프롬프트를 그대로 가져와 이 스크립트 안에 자체 내장한다(경로 의존 제거).

class ArticleRiskExtraction(BaseModel):
    is_supply_chain_relevant: bool = Field(
        description="핵심광물(리튬/코발트/니켈/흑연/망간/구리/철/희토류/알루미늄/주석/갈륨/게르마늄/"
                    "안티모니/텅스텐)의 글로벌 공급망 리스크(공급 부족, 가격 변동, 정책 제재 등)와 "
                    "직결된 새로운 리스크 이벤트인지 여부."
    )
    country: str = Field(description="리스크 이벤트가 발생한 국가. 불분명하면 '알 수 없음'")
    affected_material: List[str] = Field(description="영향받은 원자재 목록. 없으면 빈 배열 []")
    tone_score: float = Field(description="-1.0(극도로 부정적/위기) ~ +1.0(극도로 긍정적/기회)")
    event_type: str = Field(description="파업, 관세 부과, 수출 금지 등 구체적 사건 키워드. 없으면 '알 수 없음'")
    impact_domain_draft: Literal["생산", "지정학", "정책", "물류", "시장", "기타/무관"] = Field(
        description="is_supply_chain_relevant가 False면 무조건 '기타/무관'. "
                    "True면 생산/지정학/정책/물류/시장 중 가장 핵심적인 리스크 원인 하나."
    )
    summary_kr: str = Field(description="핵심 내용 2문장 이내 한국어 요약. 무관이면 '해당 없음'")


SYSTEM_PROMPT = """
You are an expert supply chain risk analyst for a battery manufacturing company.
Read the news article and extract key supply chain risk indicators into structured JSON.

[CRITICAL RULE - 2단계 필터링]
1단계: 이 기사가 핵심광물 공급망 리스크와 진정으로 관련 있는지 엄격히 판별. 전쟁/선거/정치
기사라도 광물 이야기가 핵심 주제가 아니면 False.
2단계: False면 impact_domain_draft는 무조건 "기타/무관".

[Rules for impact_domain_draft]
- 생산: 광산 파업, 자연재해로 인한 조업 중단, 설비 고장 등 직접적 생산 차질
- 물류: 항만 파업, 운하 봉쇄, 해운 운임 폭등 등 운송망 물리적 차질
- 정책: 수출 금지, 관세, 환경 규제, 광산 허가 취소, 국유화 등 정부 조치
- 시장: 가격 폭등/폭락, 수요 급감, 기업 파산 (단, 원인이 명시되면 그 원인의 도메인으로 분류)
- 지정학: 전쟁, 반군 공격, 쿠데타, 국가 간 외교 분쟁으로 인한 공급망 위협
  (전쟁/외교 분쟁 결과로 발생한 조업 중단/제재/수출 금지는 생산/정책이 아니라 지정학 우선)
- 기타/무관: 배터리 공급망 위기와 무관
"""

_client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))


def init_db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.execute(
        """CREATE TABLE IF NOT EXISTS processed (
               GlobalEventID INTEGER PRIMARY KEY,
               processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
           )"""
    )
    conn.execute(
        """CREATE TABLE IF NOT EXISTS last_run (
               id INTEGER PRIMARY KEY CHECK (id = 1),
               ts TEXT NOT NULL
           )"""
    )
    conn.commit()
    return conn


def get_last_run_ts(conn: sqlite3.Connection) -> Optional[str]:
    row = conn.execute("SELECT ts FROM last_run WHERE id = 1").fetchone()
    return row[0] if row else None


def set_last_run_ts(conn: sqlite3.Connection, ts: str) -> None:
    conn.execute(
        "INSERT INTO last_run (id, ts) VALUES (1, ?) "
        "ON CONFLICT(id) DO UPDATE SET ts = excluded.ts",
        (ts,),
    )
    conn.commit()


def get_latest_gdelt_url() -> str:
    resp = requests.get(LASTUPDATE_URL, timeout=30)
    resp.raise_for_status()
    return resp.text.strip().split("\n")[0].split(" ")[2]


def extract_ts_from_url(url: str) -> str:
    """".../20260727091500.export.CSV.zip" -> "20260727091500" """
    fname = url.strip().rsplit("/", 1)[-1]
    return fname.split(".", 1)[0]


def compute_pending_timestamps(last_ts: Optional[str], latest_ts: str) -> List[str]:
    """last_ts(마지막 처리분) 이후부터 latest_ts까지 15분 간격 타임스탬프 목록.
    last_ts가 없으면(최초 실행) 최신 1건만. BACKFILL_CAP_SLOTS 초과분은 오래된 쪽부터 스킵."""
    latest_dt = datetime.strptime(latest_ts, "%Y%m%d%H%M%S")
    if last_ts is None:
        return [latest_ts]

    last_dt = datetime.strptime(last_ts, "%Y%m%d%H%M%S")
    if last_dt >= latest_dt:
        return []

    timestamps = []
    cur = last_dt + timedelta(minutes=15)
    while cur <= latest_dt:
        timestamps.append(cur.strftime("%Y%m%d%H%M%S"))
        cur += timedelta(minutes=15)

    if len(timestamps) > BACKFILL_CAP_SLOTS:
        skipped = len(timestamps) - BACKFILL_CAP_SLOTS
        skipped_hours = skipped * 15 / 60
        print(f"   ⚠️ {skipped_hours:.1f}시간치({skipped}구간) 백필 상한"
              f"({BACKFILL_CAP_HOURS}시간) 초과 — 가장 오래된 구간은 스킵하고 최신 "
              f"{BACKFILL_CAP_HOURS}시간만 백필")
        timestamps = timestamps[-BACKFILL_CAP_SLOTS:]

    return timestamps


def fetch_events_for_ts(ts: str) -> Optional[pd.DataFrame]:
    """특정 15분 구간의 GDELT export를 가져옴. 해당 시각 파일이 없으면(드묾) None."""
    url = f"{GDELT_EXPORT_BASE}{ts}.export.CSV.zip"
    r = requests.get(url, timeout=60)
    if r.status_code == 404:
        return None
    r.raise_for_status()
    z = zipfile.ZipFile(io.BytesIO(r.content))
    with z.open(z.namelist()[0]) as f:
        return pd.read_csv(f, sep="\t", header=None, names=GDELT_COLUMNS, low_memory=False)


def _crawl_one(event_id: int, url: str):
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
            return event_id, None, None
        return event_id, title, text
    except Exception:
        return event_id, None, None


def crawl_candidates(candidates: pd.DataFrame) -> dict:
    """{event_id: (title, text)} — 크롤링 성공분만 담김."""
    crawled = {}
    with ThreadPoolExecutor(max_workers=CRAWL_WORKERS) as pool:
        futures = [
            pool.submit(_crawl_one, int(row.GlobalEventID), str(row.SOURCEURL))
            for row in candidates.itertuples()
        ]
        for fut in as_completed(futures):
            event_id, title, text = fut.result()
            if text:
                crawled[event_id] = (title, text)
    return crawled


def _extract_one(event_id: int, title: str, text: str) -> dict:
    empty = {k: None for k in [
        "is_supply_chain_relevant", "impact_domain_draft", "country",
        "summary_kr", "affected_material", "tone_score", "event_type",
    ]}
    try:
        completion = _client.beta.chat.completions.parse(
            model="gpt-4o-mini",
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": f"Title: {title}\n\nArticle Text:\n{text[:MAX_CHARS_FOR_LLM]}"},
            ],
            response_format=ArticleRiskExtraction,
            temperature=0.1,
        )
        result = completion.choices[0].message.parsed.model_dump()
        result["affected_material"] = json.dumps(result["affected_material"], ensure_ascii=False)
        result["error_msg"] = None
    except Exception as e:
        result = {**empty, "error_msg": str(e)[:200]}
    result["GlobalEventID"] = event_id
    return result


def extract_batch(crawled: dict) -> list:
    results = []
    with ThreadPoolExecutor(max_workers=LLM_WORKERS) as pool:
        futures = [
            pool.submit(_extract_one, eid, title, text)
            for eid, (title, text) in crawled.items()
        ]
        for fut in as_completed(futures):
            results.append(fut.result())
    return results


def append_df(path: str, df: pd.DataFrame, columns: list):
    if df.empty:
        return
    header = not os.path.exists(path)
    df[columns].to_csv(path, mode="a", index=False, header=header, encoding="utf-8-sig")


def save_results(results: list, source_rows: dict):
    """results: extract_batch() 결과. source_rows: {event_id: GDELT row(namedtuple)}."""
    if not results:
        return

    append_df(LABELED_CSV, pd.DataFrame(results), LABEL_COLUMNS)

    meta_rows = []
    for r in results:
        row = source_rows[r["GlobalEventID"]]
        meta_rows.append({
            "GlobalEventID": row.GlobalEventID, "SQLDATE": row.Day,
            "GoldsteinScale": row.GoldsteinScale, "NumArticles": row.NumArticles,
            "AvgTone": row.AvgTone, "Actor1Type1Code": row.Actor1Type1Code,
            "Actor2Type1Code": row.Actor2Type1Code, "EventCode": row.EventCode,
            "ActionGeo_CountryCode": row.ActionGeo_CountryCode,
        })
    append_df(GDELT_METADATA_CSV, pd.DataFrame(meta_rows), METADATA_COLUMNS)


def process_batch(df: pd.DataFrame) -> list:
    """한 15분 구간(df)에 대해 triage->크롤링->LLM추출->저장까지 수행하고 결과 리스트 반환."""
    print(f"   전체 이벤트: {len(df)}건")

    mask = should_crawl(df[TRIAGE_COLS])
    candidates = df[mask].reset_index(drop=True)
    print(f"   사전 필터(triage) 통과: {len(candidates)}/{len(df)}건")

    conn = init_db()
    seen_ids = {row[0] for row in conn.execute("SELECT GlobalEventID FROM processed")}
    new_candidates = candidates[~candidates["GlobalEventID"].isin(seen_ids)]
    skipped_dup = len(candidates) - len(new_candidates)

    print(f"   신규(중복 제외) {len(new_candidates)}건 크롤링 시작 (스레드 {CRAWL_WORKERS}개)...")
    crawled = crawl_candidates(new_candidates)
    print(f"   크롤링 성공: {len(crawled)}/{len(new_candidates)}건")

    conn.executemany(
        "INSERT OR IGNORE INTO processed (GlobalEventID) VALUES (?)",
        [(int(eid),) for eid in new_candidates["GlobalEventID"]],
    )
    conn.commit()
    conn.close()

    print(f"   LLM 정보추출 시작 (스레드 {LLM_WORKERS}개)...")
    results = extract_batch(crawled)

    source_rows = {int(r.GlobalEventID): r for r in new_candidates.itertuples()}
    save_results(results, source_rows)

    print(f"   배치 완료 — 라벨링 {len(results)}건 저장 / 중복 스킵 {skipped_dup}건")
    return results


def process_realtime():
    print(f"[{time.strftime('%X')}] 1. GDELT 최신 시점 확인 중...")
    latest_url = get_latest_gdelt_url()
    latest_ts = extract_ts_from_url(latest_url)

    conn = init_db()
    last_ts = get_last_run_ts(conn)
    conn.close()

    pending = compute_pending_timestamps(last_ts, latest_ts)
    if not pending:
        print("   처리할 신규 구간 없음 (이미 최신 상태)")
        return
    if last_ts is None:
        print(f"   최초 실행 — 최신 구간 1건만 처리 ({latest_ts})")
    else:
        print(f"   처리할 구간: {len(pending)}개 ({pending[0]} ~ {pending[-1]})")

    all_results = []
    for i, ts in enumerate(pending, 1):
        print(f"[{time.strftime('%X')}] 2. [{i}/{len(pending)}] {ts} 처리 중...")
        df = fetch_events_for_ts(ts)
        if df is None:
            print(f"   ⚠️ {ts} export 파일 없음(404) — 스킵")
        else:
            all_results.extend(process_batch(df))

        # 구간별로 즉시 갱신 — 중간에 실패해도 그 앞까지는 백필된 상태로 보존되고,
        # 재실행 시 이어서 진행됨(전체를 처음부터 다시 하지 않음).
        conn = init_db()
        set_last_run_ts(conn, ts)
        conn.close()

    print(f"3. 전체 완료 — {len(pending)}구간 / 총 라벨링 {len(all_results)}건")

    # 2026-07-25: ③(CSV 저장)에서 끊겨있던 걸 ④(build_features)→⑤(severity_scoring)까지
    # 자동으로 이어지도록 연결. build_features는 증분 처리라 신규 라벨링분만 계산하고,
    # 신규 결과가 없으면(이번 배치가 크롤링 실패/무관 판정뿐이었으면) 곧바로 종료하므로
    # 매 호출마다 비용이 크지 않다. 여러 구간을 백필했어도 build_features/severity_scoring은
    # 전체 백필이 끝난 뒤 한 번만 호출(구간마다 반복 호출하지 않음).
    if not all_results:
        print("4-5. 신규 라벨링 결과 없음 — build_features/severity_scoring 생략")
        return

    print("4. 피처 병합 자동 트리거 (build_features.py, 증분)...")
    try:
        build_features_main()
    except Exception as e:
        print(f"   ⚠️ build_features 실패: {e} — 이번 배치는 심각도 스코어링 건너뜀 "
              f"(다음 주기에 재시도됨, 이번 라벨링 결과는 CSV에 이미 저장돼 유실 없음)")
        return

    print("5. 심각도 스코어링 자동 트리거 (severity_scoring.py)...")
    try:
        severity_scoring.main()
    except Exception as e:
        print(f"   ⚠️ severity_scoring 실패: {e}")


if __name__ == "__main__":
    process_realtime()
