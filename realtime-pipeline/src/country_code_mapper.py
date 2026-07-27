"""
뉴스/이벤트의 자유텍스트 country 필드를 ERP country_code(ISO alpha-2)로 매핑.

## 왜 필요한가 (2026-07-24 밤)

`build_ontology_graph.py`의 `assess_risk(country=...)`는 ERP 공급사 데이터
(`backend/data/ERP_data/spring-csv/02_suppliers.csv`)의 `country_code`(ISO alpha-2,
예: 칠레=CL) 기준으로 국가를 매칭한다. 반면 GDELT/LLM이 채우는 뉴스 이벤트의
`country` 필드는 자유텍스트라 같은 나라도 표기가 제각각이다 — 실측 확인 결과
295개의 고유값이 있었고, 그중 DRC 하나만도 "Democratic Republic of the Congo"/
"Democratic Republic of Congo"/"Congo"/"DRC"/"콩고" 등으로 쪼개져 있었다.
이 매핑이 없으면 심각도 스코어링 결과(country=자유텍스트)를 그래프 탐색
(country=ISO코드)으로 자동으로 넘길 수 없다.

## 매핑 전략

1. `_ALIAS_OVERRIDES`: 실측 데이터에서 자주 나오는 한국어/스페인어/프랑스어 등
   다국어 표기, 약어(DRC/USA/UK 등), GDELT 특유 표기를 수동으로 정리한 사전.
   ERP가 실제로 다루는 14개국(AU/CL/ID/CD/CN/KR/CA/FI/JP/DE/MY/ZA/BR/US)의
   변형은 최대한 빠짐없이 포함, 그 외 국가도 실측 상위 빈도 기준으로 포함.
2. 1번에 없으면 `pycountry.countries.lookup()`(공식 영어 국가명 정확매칭)으로 폴백.
   `search_fuzzy()`는 시도하지 않음 — "Zaire"(DRC 옛 이름)를 Angola로 잘못
   매칭하는 등 오탐 위험이 확인돼서, 틀린 매핑보다는 매핑 안 하는(None) 쪽이 안전.
3. "미국, 중국"처럼 콤마로 여러 국가가 나열된 경우 첫 번째만 사용 (다국가 이벤트는
   어차피 그래프 쪽에서 국가 하나로 단일 매칭하는 게 원래 부정확하므로 최선 근사치).
4. "기타/무관", "없음", "Global", "Europe" 같은 국가가 아닌 값은 명시적으로 None
   처리 — 억지로 매핑하지 않는다.

## 커버리지 한계

295개 고유값 중 최상위 빈도 국가들과 ERP 14개국은 확실히 커버하지만, 1~2회만
등장하는 희귀 표기(예: "Servië", "Ungaria")까지 전부 손으로 정리하진 않았다.
매핑 안 되는 값은 `to_iso_code()`가 `None`을 반환하므로 호출 쪽에서 반드시
None 체크할 것 — 무리하게 추정하지 않는 게 이 모듈의 설계 원칙이다.
"""
from __future__ import annotations

import pycountry

# ERP가 실제로 다루는 국가 (참고용 — 매핑 자체는 이 목록 밖 국가도 지원함)
ERP_COUNTRY_CODES = {"AU", "CL", "ID", "CD", "CN", "KR", "CA", "FI", "JP", "DE", "MY", "ZA", "BR", "US"}

_ALIAS_OVERRIDES: dict[str, str | None] = {
    # --- 한국어 ---
    "미국": "US", "중국": "CN", "러시아": "RU", "영국": "GB", "일본": "JP",
    "필리핀": "PH", "브라질": "BR", "말레이시아": "MY", "캐나다": "CA", "독일": "DE",
    "프랑스": "FR", "인도네시아": "ID", "인도": "IN", "칠레": "CL", "호주": "AU",
    "핀란드": "FI", "스웨덴": "SE", "스위스": "CH", "스페인": "ES", "싱가포르": "SG",
    "덴마크": "DK", "네덜란드": "NL", "그리스": "GR", "미얀마": "MM", "자메이카": "JM",
    "뉴질랜드": "NZ", "대한민국": "KR", "홍콩": "HK", "알래스카": "US",
    "아프리카": None, "유럽": None, "유럽연합": None, "다수 국가": None,
    "기타/무관": None, "기타": None, "없음": None, "해당 없음": None,

    # --- 영어 약어/변형 ---
    "USA": "US", "US": "US", "us": "US", "U.S.": "US", "United States": "US",
    "United Kingdom": "GB", "UK": "GB",
    "South Korea": "KR", "Korea": "KR", "North Korea": "KP",
    "Burma": "MM", "Birmania": "MM", "Birmanie": "MM",
    "DRC": "CD", "DR Congo": "CD", "Kongo": "CD", "Zaire": "CD",
    "Democratic Republic of the Congo": "CD", "Democratic Republic of Congo": "CD",
    "República Democrática del Congo": "CD", "République Démocratique du Congo": "CD",
    "Demokratische Republik Kongo": "CD",
    "Congo": "CD",  # 기사 맥락상 대부분 코발트=DRC. Congo-Brazzaville(CG)과 텍스트만으론 구분 불가 — 다수결로 CD
    "Ivory Coast": "CI", "Costa de Marfil": "CI",
    "Sudáfrica": "ZA", "Sudafrica": "ZA",
    "Estados Unidos": "US", "EE.UU.": "US", "Amerika Serikat": "US",
    "México": "MX", "Mexique": "MX",
    "Perú": "PE", "Pérou": "PE",
    "Rusia": "RU", "Russie": "RU",
    "Canadá": "CA", "Kanada": "CA",
    "Chine": "CN", "Cina": "CN",
    "Indonésia": "ID", "Indonésie": "ID", "Indonezia": "ID", "Inggris": "GB",
    "Australien": "AU", "Australie": "AU", "Austrália": "AU",
    "Ucrania": "UA", "Ucraina": "UA", "Ucrânia": "UA", "Ukraina": "UA",
    "Chili": "CL", "Grecia": "GR", "Groenlandia": "GL", "Groenland": "GL",
    "Filipinler": "PH", "Simbabwe": "ZW", "Afganistán": "AF", "Níger": "NE",
    "Gabón": "GA", "Servië": "RS", "Georgien": "GE", "Géorgie": "GE",
    "Dinamarca": "DK", "Ungaria": "HU", "España": "ES", "Suiza": "CH",
    "Russia": "RU",  # pycountry 공식명은 "Russian Federation"이라 lookup("Russia") 자체가 실패함
    "Panamá": "PA", "Panama": "PA",  # 악센트 있는 표기는 pycountry가 못 잡음
    "Australia, US, UK, Canada": None,
    "Latin America": None, "Latinoamérica": None, "South America": None,
    "West Africa": None, "GCC": None, "EU": None, "European Union": None,
    "Unión Europea": None, "Europe": None, "Europa": None, "Africa": None,
    "Global": None, "Unknown": None, "No country specified": None,
    "No applicable": None, "No aplica": None, "No relevant country": None,
    "No specific country mentioned": None, "Tibet": "CN",  # 뉴스 맥락상 중국 자원정책과 묶임
}


def to_iso_code(raw: str | None) -> str | None:
    """자유텍스트 국가명 -> ISO alpha-2. 매핑 실패 시 None (추정하지 않음).

    ⚠️ 나미비아(Namibia)의 ISO alpha-2 코드는 실제로 "NA"다. 이 함수가 반환한
    "NA"를 CSV에 썼다가 `pandas.read_csv()`로 다시 읽으면 pandas 기본 결측값
    목록에 "NA"가 포함돼 있어서 **문자열 "NA"가 아니라 NaN으로 조용히 바뀐다**
    (실제로 이 함수 검증 중 발견함 — Namibia 2건이 "매핑 실패"로 잘못 집계됨).
    이 컬럼을 다시 읽는 코드는 반드시 `pd.read_csv(..., keep_default_na=False)`
    또는 `na_values=[]`를 쓸 것. 나미비아만을 위해 표준 ISO 코드를 임의로
    바꾸진 않음 — ERP `country_code`와의 계약을 깨는 게 더 위험한 선택이라서.
    """
    if not raw or not isinstance(raw, str):
        return None

    name = raw.strip()
    if not name:
        return None

    if "," in name:
        name = name.split(",")[0].strip()

    if name in _ALIAS_OVERRIDES:
        return _ALIAS_OVERRIDES[name]

    try:
        return pycountry.countries.lookup(name).alpha_2
    except LookupError:
        return None
