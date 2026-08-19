"""Build a local, zero-cost demo manifest from GDELT daily archives.

The script reads selected historical rows from data_core, downloads each required
daily archive once, restores the original event row by GlobalEventID, and writes
an auditable JSON manifest.  It does not call BigQuery or any paid API.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from collections import defaultdict
from datetime import date
from pathlib import Path

import pandas as pd
import requests


ARCHIVE_BASE_URL = "http://data.gdeltproject.org/events"
GDELT_COLUMNS = [
    "GlobalEventID", "SQLDATE", "MonthYear", "Year", "FractionDate", "Actor1Code", "Actor1Name",
    "Actor1CountryCode", "Actor1KnownGroupCode", "Actor1EthnicCode", "Actor1Religion1Code",
    "Actor1Religion2Code", "Actor1Type1Code", "Actor1Type2Code", "Actor1Type3Code", "Actor2Code",
    "Actor2Name", "Actor2CountryCode", "Actor2KnownGroupCode", "Actor2EthnicCode",
    "Actor2Religion1Code", "Actor2Religion2Code", "Actor2Type1Code", "Actor2Type2Code",
    "Actor2Type3Code", "IsRootEvent", "EventCode", "EventBaseCode", "EventRootCode", "QuadClass",
    "GoldsteinScale", "NumMentions", "NumSources", "NumArticles", "AvgTone", "Actor1Geo_Type",
    "Actor1Geo_FullName", "Actor1Geo_CountryCode", "Actor1Geo_ADM1Code", "Actor1Geo_Lat",
    "Actor1Geo_Long", "Actor1Geo_FeatureID", "Actor2Geo_Type", "Actor2Geo_FullName",
    "Actor2Geo_CountryCode", "Actor2Geo_ADM1Code", "Actor2Geo_Lat", "Actor2Geo_Long",
    "Actor2Geo_FeatureID", "ActionGeo_Type", "ActionGeo_FullName", "ActionGeo_CountryCode",
    "ActionGeo_ADM1Code", "ActionGeo_Lat", "ActionGeo_Long", "ActionGeo_FeatureID", "DATEADDED",
    "SOURCEURL",
]
RESTORED_FIELDS = [
    "GlobalEventID", "SQLDATE", "EventCode", "EventBaseCode", "EventRootCode", "QuadClass",
    "GoldsteinScale", "NumMentions", "NumSources", "NumArticles", "AvgTone", "Actor1Type1Code",
    "Actor2Type1Code", "ActionGeo_CountryCode", "ActionGeo_FullName", "DATEADDED", "SOURCEURL",
]
MATERIAL_ALIASES = {
    "니켈": ("nickel", "니켈"), "코발트": ("cobalt", "코발트"),
    "구리": ("copper", "구리"), "리튬": ("lithium", "리튬"),
    "망간": ("manganese", "망간"), "흑연": ("graphite", "흑연"),
    "알루미늄": ("aluminium", "aluminum", "알루미늄"),
}
TARGETS = {"심각": 30, "주의": 50, "정상": 20}
MANIFEST_TARGET = sum(TARGETS.values())

# 현재 KG에서 실제 공급사 경로가 있고 재고가 부족한 국가/원자재 조합입니다.
# KG 게이트를 우회하지 않으면서도 데모 사건 일부가 멀티에이전트까지 도달하도록,
# 같은 위험등급 안에서는 이 조합을 먼저 선별합니다.
KG_SHORTAGE_COUNTRIES = {
    "니켈": {"FI", "ID", "PH"},
    "코발트": {"CA", "CD"},
    "흑연": {"CA", "CN", "JP", "KR"},
    "망간": {"BR", "KR", "ZA"},
    "구리": {"DE", "JP", "KR"},
}
# 심각/주의 데모 이벤트의 배달 국가를 그 자재의 공급사(재고부족) 국가로 **강제**할 때 쓰는
# FIPS 코드. KG가 보는 country = fipsToIso2(ActionGeo_CountryCode)이므로 여기 FIPS를 덮으면
# (country, material)이 KG 공급망 경로에 정렬돼 멀티에이전트 브리핑까지 도달한다.
#
# 값은 반드시 GdeltEventArchiveService.ISO2_TO_FIPS에 존재하는 FIPS여야 한다 — 없으면
# fipsToIso2가 null을 돌려 국가가 유실되고 KG 매칭이 깨진다. 위 KG_SHORTAGE_COUNTRIES에서
# 매핑 없는 국가는 제외했다: 핀란드(FI)·브라질(BR)은 그 맵에 없어 뺀다. 남아공은 FIPS 'SF'
# (FIPS 'ZA'는 잠비아라 절대 쓰면 안 됨). 실제 KG /resolve로 아래 조합의 재고부족을 확인함.
SHORTAGE_FIPS = {
    "니켈": ["ID", "RP"],              # ID=인니, RP=필리핀
    "코발트": ["CG", "CA"],             # CG=콩고민주공화국, CA=캐나다
    "흑연": ["CH", "CA", "JA", "KS"],   # CH=중국, CA=캐나다, JA=일본, KS=한국
    "망간": ["SF", "KS"],               # SF=남아공, KS=한국
    "구리": ["GM", "JA", "KS"],         # GM=독일, JA=일본, KS=한국
}
# KG 게이트를 통과할 수 있는(공급사+재고부족) 5개 자재. 심각/주의는 이 자재로만 채운다 —
# 리튬(재고 충분)·알루미늄(공급사 없음)은 아무리 넣어도 브리핑까지 못 가고 KG에서 종료된다.
SHORTAGE_MATERIALS = frozenset(SHORTAGE_FIPS)
# 데모 소스 창(가용 데이터 최신일 기준 최근 N일). 시간압축 설계상 6년치를 뽑아 사이트 3일로
# 압축하므로 넉넉히 잡는다. 실측(6년 풀): 통과자재 1750건(구리694·니켈669·코발트267·흑연78·망간42).
RECENT_WINDOW_DAYS = 2190

# 시간압축(quota 롤오버): 최신순으로 소비하며 하루 quota를 채우면 그 날은 마감하고 전날로 넘어간다.
# 각 사이트 하루(demo_day 0=오늘, 1=어제, 2=그제)에 5종 통과자재 주의가 모두 담기고, 구리는 ERP
# 노출이 높아 composite 심각이 되므로 조금 더 뽑는다. 3일 모두 뉴스피드 만료창(정상3·주의5·심각10일)
# 안이라 전부 노출된다. 망간·흑연은 6년 풀에서도 적어(42·78) 부족하면 build_manifest가 경고한다.
DEMO_DAYS = 3
PER_DAY_QUOTA = {"니켈": 12, "코발트": 12, "흑연": 12, "망간": 12, "구리": 15}

# (C) 추출 오버라이드용: 데모는 진짜 헤드라인을 표시하므로 제목에 자재명이 없다. 매니페스트가 아는
# 자재·관련성을 extraction_override로 강제해 LLM 재추출 없이 material_category를 채우고 "공급망 무관"
# 판정을 피한다. 자재는 FastAPI ImpactDomain·affected_material enum(영문 대문자)로 매핑한다.
MATERIAL_ENUM = {
    "니켈": "NICKEL", "코발트": "COBALT", "흑연": "GRAPHITE", "망간": "MANGANESE", "구리": "COPPER",
}
# CSV impact_domain_draft(한글) → FastAPI ImpactDomain enum(영문). 매핑 없는 값(기타·기타/무관)은
# PRODUCTION으로 기본 처리한다 — is_supply_chain_relevant=true를 강제하므로 관련 도메인으로 둔다.
IMPACT_DOMAIN_ENUM = {
    "정책": "POLICY", "물류": "LOGISTICS", "생산": "PRODUCTION",
    "지정학": "GEOPOLITICS", "시장": "MARKET",
}
# 이미 배포/로컬 DB에 들어간 최초 16건은 계속 포함해, manifest 확대 후에도
# 별도 삭제 없이 DEMO_GDELT 총합이 정확히 100건(기존 16 + 신규 84)이 되게 한다.
PINNED_EVENT_IDS = {
    941312649, 1080094196, 1103426471, 1126371437,
    1126486358, 1126775200, 1144009302, 1162059318,
    1192934820, 1199261761, 1206252173, 1212998269,
    1228502243, 1230097170, 1268707747, 1275570633,
}
# data_core에는 있으나 해당 날짜의 공식 daily export에서 확인되지 않은 행. 복원 리포트로
# 검증된 값이며, 다음 순위 후보를 선택해 목표 분포가 무너지지 않게 한다.
UNAVAILABLE_EVENT_IDS = {
    1028347980,
    1162651682,
    1182582241,
    1223275118,
    1223537898,
    1230127445,
    1250614812,
    1262780126,
    1274498126,
    1279517466,
}


def _json_value(value):
    if pd.isna(value):
        return None
    return value.item() if hasattr(value, "item") else value


def _download_once(url: str, destination: Path) -> tuple[Path, str]:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        partial = destination.with_suffix(destination.suffix + ".part")
        try:
            with requests.get(url, stream=True, timeout=(10, 45)) as response:
                response.raise_for_status()
                with partial.open("wb") as output:
                    for chunk in response.iter_content(1024 * 1024):
                        if chunk:
                            output.write(chunk)
            partial.replace(destination)
        except Exception:
            partial.unlink(missing_ok=True)
            raise
    digest = hashlib.md5(destination.read_bytes()).hexdigest()  # GDELT publishes MD5 alongside archives.
    return destination, digest


def _crawl_headline(url) -> str | None:
    """SOURCEURL을 크롤링해 원문 헤드라인을 돌려준다. 실패(죽은 링크·차단·타임아웃)면 None →
    호출부가 그 이벤트를 매니페스트에서 제외한다(폴백 제목 없음). 실시간 수집과 같은 newspaper를 쓴다."""
    url = str(url or "")
    if not url.startswith("http"):
        return None
    try:
        from newspaper import Article, Config
        cfg = Config()
        cfg.request_timeout = 8
        cfg.browser_user_agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
        article = Article(url, config=cfg)
        article.download()
        article.parse()
        title = (article.title or "").strip()
        return title or None
    except Exception:
        return None


def _read_archive(path: Path) -> pd.DataFrame:
    with zipfile.ZipFile(path) as archive:
        members = [name for name in archive.namelist() if not name.endswith("/")]
        if len(members) != 1:
            raise ValueError(f"expected one data file in {path}, found {len(members)}")
        with archive.open(members[0]) as source:
            frame = pd.read_csv(source, sep="\t", header=None, low_memory=False)
    if frame.shape[1] != len(GDELT_COLUMNS):
        raise ValueError(
            f"unexpected GDELT column count in {path}: {frame.shape[1]} (expected {len(GDELT_COLUMNS)})"
        )
    frame.columns = GDELT_COLUMNS
    return frame


def _material_of(value) -> str | None:
    text = str(value or "").lower()
    return next((name for name, aliases in MATERIAL_ALIASES.items() if any(a in text for a in aliases)), None)


def _select_demo_rows(source: pd.DataFrame) -> pd.DataFrame:
    """시간압축 quota 롤오버 선별.

    최신 original_date 우선으로 소비하며, demo_day(0=오늘,1=어제,2=그제)마다 5종 통과자재를
    PER_DAY_QUOTA만큼 채우고 다음 날로 넘어간다. 반환 df에 demo_day 컬럼이 붙는다. 전부 KG 통과
    5개 자재(니켈·코발트·흑연·망간·구리)라 뒤에서 배달 국가를 공급사 국가로 강제하면 브리핑까지 간다.
    """
    source = source.copy()
    source = source[~source["GlobalEventID"].isin(UNAVAILABLE_EVENT_IDS)]
    source["demo_material"] = source["affected_material"].map(_material_of)
    # 통과 5개 자재만 — 전부 KG 통과(공급사+재고부족) 대상. 리튬·알루미늄·희토류 등은 제외.
    source = source[source["demo_material"].isin(SHORTAGE_MATERIALS)]
    source = source.drop_duplicates(subset=["GlobalEventID", "summary_kr"])
    # 최신 원본 날짜 우선(newest-first)으로 소비한다.
    source["_d"] = pd.to_datetime(source["Date"], errors="coerce")
    source = source.dropna(subset=["_d"]).sort_values("_d", ascending=False)

    selected: list[tuple] = []   # (index, demo_day)
    used: set = set()
    for demo_day in range(DEMO_DAYS):
        for material, quota in PER_DAY_QUOTA.items():
            pool = source[(source["demo_material"] == material) & (~source.index.isin(used))]
            take = pool.head(quota)
            got = len(take)
            if got < quota:
                print(
                    f"[demo][warn] day{demo_day} '{material}' 목표 {quota}건 중 {got}건 확보 "
                    f"— 6년 통과자재 풀 소진(망간·흑연이 적음).",
                    file=sys.stderr,
                )
            for idx in take.index:
                selected.append((idx, demo_day))
                used.add(idx)

    if not selected:
        return source.iloc[0:0].assign(demo_day=[])
    result = source.loc[[i for i, _ in selected]].copy()
    result["demo_day"] = [d for _, d in selected]
    return result


def build_manifest(source_csv: Path, cache_dir: Path) -> tuple[list[dict], dict]:
    source = pd.read_csv(source_csv, low_memory=False)
    required = {"GlobalEventID", "Date"}
    missing_columns = required.difference(source.columns)
    if missing_columns:
        raise ValueError(f"missing source columns: {sorted(missing_columns)}")

    source = source[source["is_supply_chain_relevant"] == True].copy()  # noqa: E712

    # 최근 1년치로 제한 — 가용 데이터 최신일 기준 RECENT_WINDOW_DAYS.
    parsed_dates = pd.to_datetime(source["Date"], errors="coerce")
    max_date = parsed_dates.max()
    if pd.notna(max_date):
        cutoff = max_date - pd.Timedelta(days=RECENT_WINDOW_DAYS)
        kept = parsed_dates >= cutoff
        print(
            f"[demo] 최근 {RECENT_WINDOW_DAYS}일 필터: {cutoff.date()} ~ {max_date.date()} "
            f"— {len(source)} → {int(kept.sum())}건",
            file=sys.stderr,
        )
        source = source[kept].copy()

    source = _select_demo_rows(source)

    # 선별 요약(조용한 미달 방지 — 세부 부족은 _select_demo_rows가 이미 경고).
    print(
        f"[demo] 선별 {len(source)}건 (목표 {DEMO_DAYS}일 × {sum(PER_DAY_QUOTA.values())}건/일)",
        file=sys.stderr,
    )
    for d in range(DEMO_DAYS):
        day_rows = source[source["demo_day"] == d]
        print(
            f"[demo]  day{d}: {len(day_rows)}건 {dict(day_rows['demo_material'].value_counts())}",
            file=sys.stderr,
        )
    source["GlobalEventID"] = pd.to_numeric(source["GlobalEventID"], errors="coerce").astype("Int64")
    source["Date"] = pd.to_datetime(source["Date"], errors="coerce").dt.date
    source = source.dropna(subset=["GlobalEventID", "Date"])

    by_date: dict[date, list[dict]] = defaultdict(list)
    for record in source.to_dict(orient="records"):
        by_date[record["Date"]].append(record)

    restored: list[dict] = []
    # 자재별로 공급사 국가를 돌아가며 배정해(로테이션) 국가 다양성을 유지한다.
    shortage_rotation: dict[str, int] = defaultdict(int)
    report = {"source": str(source_csv), "dates": {}, "missing_event_ids": [], "errors": []}
    for event_date, records in sorted(by_date.items()):
        day = event_date.strftime("%Y%m%d")
        filename = f"{day}.export.CSV.zip"
        url = f"{ARCHIVE_BASE_URL}/{filename}"
        try:
            archive_path, md5 = _download_once(url, cache_dir / filename)
            frame = _read_archive(archive_path)
        except (OSError, ValueError, zipfile.BadZipFile, requests.RequestException) as exc:
            report["errors"].append({"date": day, "url": url, "error": str(exc)})
            continue

        requested_ids = {int(record["GlobalEventID"]) for record in records}
        matches = frame[frame["GlobalEventID"].isin(requested_ids)].copy()
        rows_by_id = {int(row.GlobalEventID): row for row in matches.itertuples(index=False)}
        report["dates"][day] = {
            "url": url, "cache_path": str(archive_path), "md5": md5,
            "requested": len(requested_ids), "restored": len(rows_by_id),
        }

        for record in records:
            event_id = int(record["GlobalEventID"])
            raw = rows_by_id.get(event_id)
            if raw is None:
                report["missing_event_ids"].append(event_id)
                continue
            restored_fields = {field: _json_value(getattr(raw, field)) for field in RESTORED_FIELDS}
            # 선별된 이벤트는 전부 통과 5개 자재다. 배달 국가를 그 자재의 공급사(재고부족) 국가 FIPS로
            # 강제한다 — Spring 어댑터의 fipsToIso2 → raw_events.country_code → KG가 보는 country가
            # 공급망 경로와 정렬돼, KG 게이트를 통과해 멀티에이전트 브리핑까지 도달한다.
            # (자재별 공급사 국가를 로테이션해 국가 다양성을 유지한다.)
            demo_material = record.get("demo_material")
            if demo_material in SHORTAGE_FIPS:
                fips_pool = SHORTAGE_FIPS[demo_material]
                restored_fields["ActionGeo_CountryCode"] = fips_pool[
                    shortage_rotation[demo_material] % len(fips_pool)
                ]
                shortage_rotation[demo_material] += 1
            # 진짜 기사 헤드라인 크롤링 — 실패(죽은 링크·차단·타임아웃)면 이 이벤트는 제외한다
            # (폴백 제목 없음, 건수만 줄임). 성공 시 title에 실어 [과거 사건 재현] 폴백을 대체한다.
            headline = _crawl_headline(restored_fields.get("SOURCEURL"))
            if not headline:
                report.setdefault("dead_links", []).append(event_id)
                continue
            demo_mat = record.get("demo_material")
            restored.append({
                "global_event_id": str(event_id),
                "original_date": event_date.isoformat(),
                "demo_day": int(record.get("demo_day", 0)),
                "title": headline,
                "summary_kr": _json_value(record.get("summary_kr")),
                "affected_material": _json_value(record.get("affected_material")),
                "material": _json_value(demo_mat),
                "event_type": _json_value(record.get("event_type")),
                "expected_severity": _json_value(record.get("severity_tier")),
                "expected_severity_score": _json_value(record.get("severity_score")),
                # (C) 추출 오버라이드용 — LLM 재추출 대신 이 값들을 강제해 material_category를 채우고
                # 관련성=true로 "공급망 무관" 판정을 피한다.
                "material_enum": MATERIAL_ENUM.get(demo_mat),
                "tone_score": _json_value(record.get("tone_score")),
                "impact_domain": IMPACT_DOMAIN_ENUM.get(record.get("impact_domain_draft"), "PRODUCTION"),
                "raw": restored_fields,
            })

    report["restored_count"] = len(restored)
    report["missing_event_ids"] = sorted(set(report["missing_event_ids"]))
    report["dead_links"] = sorted(set(report.get("dead_links", [])))
    print(
        f"[demo] 크롤 제외(죽은 링크): {len(report['dead_links'])}건 → 최종 {len(restored)}건",
        file=sys.stderr,
    )
    return restored, report


def main() -> int:
    repository = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        type=Path,
        default=repository / "data_core" / "event_features_normalized.csv",
    )
    parser.add_argument("--cache-dir", type=Path, default=repository / "data" / "demo" / "cache" / "gdelt")
    parser.add_argument("--output", type=Path, default=repository / "data" / "demo" / "gdelt_recovered_events.json")
    parser.add_argument("--report", type=Path, default=repository / "data" / "demo" / "gdelt_recovery_report.json")
    args = parser.parse_args()

    manifest, report = build_manifest(args.source, args.cache_dir)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(args.output), **report}, ensure_ascii=False, indent=2))
    return 0 if not report["errors"] else 1


if __name__ == "__main__":
    sys.exit(main())
