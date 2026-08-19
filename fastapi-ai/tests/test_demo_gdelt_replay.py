import json

from app.services.demo_gdelt_replay_service import load_demo_candidates


def test_missing_manifest_returns_empty(monkeypatch, tmp_path):
    monkeypatch.setenv("DEMO_GDELT_MANIFEST_PATH", str(tmp_path / "missing.json"))
    assert load_demo_candidates() == []


def test_manifest_maps_to_realtime_candidate(monkeypatch, tmp_path):
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps([{
        "global_event_id": "123", "material": "니켈", "event_type": "생산 중단",
        "summary_kr": "니켈 생산이 중단되었습니다.",
        "raw": {"SOURCEURL": "https://example.com/news", "ActionGeo_CountryCode": "ID",
                "GoldsteinScale": -9.2, "GlobalEventID": 123, "NumArticles": 42,
                "AvgTone": -8.1, "Actor1Type1Code": "BUS", "Actor2Type1Code": "GOV",
                "EventCode": "190"},
    }]), encoding="utf-8")
    monkeypatch.setenv("DEMO_GDELT_MANIFEST_PATH", str(path))

    assert load_demo_candidates(1) == [{
        "global_event_id": "123",
        "title": "[과거 사건 재현] 니켈 생산 중단",
        "content": "니켈 생산이 중단되었습니다.",
        "source_url": "https://example.com/news",
        "action_geo_country_code": "ID",
        "goldstein_scale": -9.2,
        "num_articles": 42,
        "avg_tone": -8.1,
        "original_event_date": None,
    }]
