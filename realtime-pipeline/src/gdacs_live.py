"""
GDACS(Global Disaster Alert and Coordination System) 실시간 재난경보 조회.

## 왜 필요한가 (2026-07-27)

기존 `데이터셋/gdacs_historical_filtered_500km.json`은 특정 시점(7/21)에 한 번 받아둔
정적 스냅샷이다. severity_scoring.py의 GDACS 하드 게이트(gdacs_alert_level>=2 → 무조건
'심각')는 지금도 살아있는데, 정적 파일이라 그 이후 실제로 발생한 실시간 GDELT 이벤트에는
매칭될 수 없어 gdacs_alert_level이 항상 0으로 채워지고 있었다 — "실시간 파이프라인"이라는
이름이 무색하게 이 게이트만 조용히 죽어있던 상태. 이 모듈이 그 자리를 대체한다.

## 국가 코드 매핑 주의

GDACS API는 ISO3 코드를 쓰고, 이 프로젝트의 `ActionGeo_CountryCode`는 GDELT 원시 필드라
FIPS 10-4 코드다. 둘이 알파벳이 겹치면서 다른 나라를 가리키는 경우가 많다(예:
FIPS SF=남아공/ISO ZAF, FIPS ZA=잠비아이고 ISO ZA는 남아공, FIPS RS=러시아인데 ISO RS는
세르비아, FIPS GB=가봉인데 ISO GB는 영국). 실제로 기존 정적 파일 로딩 코드도 칠레를
ISO2 'CL'로 매핑해놨었는데 이 프로젝트 전역에서 칠레는 FIPS 'CI'를 쓰고 있어서 애초에
매칭이 안 되고 있었다(이번에 같이 고침).

아래 FIPS_TO_ISO3는 이 프로젝트가 실제로 다루는 38개국(triage_filter.py의 핵심 생산국 +
_archive/data_prep/bigquery_historical_pull.py의 MINING_COUNTRIES 32개 + 물류 허브국 6개,
data_prep/triage_hub_candidates.csv 실측으로 EG/SN/NL/AE/GR/PM 확인)만 pycountry 공식
영문 국가명 조회로 검증해서 만들었다. 이 목록 밖 국가는 어차피 이 프로젝트의 triage
화이트리스트 밖이라 매핑 안 해도 된다.
"""
import requests
import pandas as pd

GDACS_SEARCH_URL = "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH"

# FIPS 10-4 -> ISO alpha-3 (pycountry 공식명 조회로 검증됨, 2026-07-27)
FIPS_TO_ISO3 = {
    "ID": "IDN", "CG": "COD", "CI": "CHL", "AR": "ARG", "AS": "AUS", "CH": "CHN",
    "SF": "ZAF", "GB": "GAB", "RP": "PHL", "BR": "BRA", "BL": "BOL", "PE": "PER",
    "ZA": "ZMB", "RS": "RUS", "CA": "CAN", "MX": "MEX", "UP": "UKR", "GV": "GIN",
    "JM": "JAM", "US": "USA", "BM": "MMR", "IN": "IND", "MA": "MDG", "TH": "THA",
    "RW": "RWA", "ZI": "ZWE", "KZ": "KAZ", "WA": "NAM", "UZ": "UZB", "NG": "NER",
    "TI": "TJK", "VM": "VNM",
    # 물류 허브국 (실측 ActionGeo_CountryCode 기준, data_prep/triage_hub_candidates.csv)
    "EG": "EGY", "SN": "SGP", "NL": "NLD", "AE": "ARE", "GR": "GRC", "PM": "PAN",
}
ISO3_TO_FIPS = {v: k for k, v in FIPS_TO_ISO3.items()}


def fetch_current_alerts(timeout: int = 20) -> pd.DataFrame:
    """현재 진행 중인 GDACS Orange/Red 경보를 (ActionGeo_CountryCode, Date, gdacs_alert_level)
    형태로 반환한다.

    Green은 안 가져온다 — severity_scoring.py의 하드 게이트 임계치가 2(Orange)라서
    Green(1)은 애초에 스코어링에 영향을 주지 않는다.

    API 호출 실패 시(네트워크 문제 등) 빈 DataFrame을 반환한다 — 호출 쪽(build_features.py)의
    기존 fallback(gdacs_alert_level을 0으로 채움)이 그대로 동작해서 파이프라인 전체가
    이 때문에 죽지 않는다.
    """
    try:
        resp = requests.get(GDACS_SEARCH_URL, params={"alertlevel": "Orange;Red"}, timeout=timeout)
        resp.raise_for_status()
        features = resp.json().get("features", [])
    except Exception as e:
        print(f"  ⚠️ GDACS 실시간 조회 실패({e}) — 이번 배치는 gdacs_alert_level=0으로 처리됨")
        return pd.DataFrame(columns=["ActionGeo_CountryCode", "Date", "gdacs_alert_level"])

    rows = []
    for feat in features:
        p = feat.get("properties", {})
        score = p.get("alertscore")
        if score is None:
            continue

        from_dt = pd.to_datetime(p.get("fromdate"), errors="coerce")
        to_dt = pd.to_datetime(p.get("todate"), errors="coerce")
        if pd.isna(from_dt) or pd.isna(to_dt):
            continue
        from_dt, to_dt = from_dt.normalize(), to_dt.normalize()
        if to_dt < from_dt:
            to_dt = from_dt

        iso3_list = {c.get("iso3") for c in p.get("affectedcountries", []) if c.get("iso3")}
        if p.get("iso3"):
            iso3_list.add(p["iso3"])

        fips_codes = {ISO3_TO_FIPS[i] for i in iso3_list if i in ISO3_TO_FIPS}
        if not fips_codes:
            continue

        for date in pd.date_range(from_dt, to_dt, freq="D"):
            for fips in fips_codes:
                rows.append({
                    "ActionGeo_CountryCode": fips,
                    "Date": date,
                    "gdacs_alert_level": score,
                })

    if not rows:
        return pd.DataFrame(columns=["ActionGeo_CountryCode", "Date", "gdacs_alert_level"])

    df = pd.DataFrame(rows)
    # 같은 국가+날짜에 여러 경보가 겹치면 더 위험한(높은) 등급을 취함
    df = df.groupby(["ActionGeo_CountryCode", "Date"], as_index=False)["gdacs_alert_level"].max()
    return df
