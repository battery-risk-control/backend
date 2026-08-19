import json
import os
from pathlib import Path

import pandas as pd

from app.models.triage_filter import should_crawl
from app.services.realtime_gdelt_service import TRIAGE_COLS

DEFAULT_MANIFEST = Path("/app/data/demo/gdelt_demo_manifest.json")


def load_demo_candidates(limit: int = 100) -> list[dict]:
    path = Path(os.getenv("DEMO_GDELT_MANIFEST_PATH", str(DEFAULT_MANIFEST)))
    if not path.exists():
        return []
    records = json.loads(path.read_text(encoding="utf-8"))
    if records:
        raw_frame = pd.DataFrame([record.get("raw") or {} for record in records])
        if not set(TRIAGE_COLS).issubset(raw_frame.columns):
            return []
        records = [record for record, passed in zip(records, should_crawl(raw_frame[TRIAGE_COLS])) if passed]
    result = []
    for record in records:
        raw = record.get("raw") or {}
        summary = (record.get("summary_kr") or "").strip()
        if not summary:
            continue
        material = record.get("material") or record.get("affected_material") or "원자재"
        event_type = record.get("event_type") or "공급망 사건"
        result.append({
            "global_event_id": str(record["global_event_id"]),
            "title": record.get("title") or f"[과거 사건 재현] {material} {event_type}",
            "content": record.get("content") or summary,
            "source_url": record.get("source_url") or raw.get("SOURCEURL") or "https://www.gdeltproject.org/",
            "action_geo_country_code": raw.get("ActionGeo_CountryCode"),
            "goldstein_scale": raw.get("GoldsteinScale"),
            "num_articles": raw.get("NumArticles"),
            "avg_tone": raw.get("AvgTone"),
            "original_event_date": record.get("original_date"),
            "demo_day": int(record.get("demo_day") or 0),
            # (C) 추출 오버라이드용 — Spring이 이 값으로 material_category·관련성을 강제한다.
            "material_enum": record.get("material_enum"),
            "event_type": event_type,
            "tone_score": record.get("tone_score"),
            "impact_domain": record.get("impact_domain") or "PRODUCTION",
        })
        # 상한: 시간압축(3일×~63건 ≈ 190건)을 담을 수 있게 300으로 둔다(과거 100 고정이면 잘렸음).
        if len(result) >= max(0, min(limit, 300)):
            break
    return result
