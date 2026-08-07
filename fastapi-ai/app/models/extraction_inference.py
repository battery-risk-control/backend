import os
from dataclasses import dataclass
from typing import Literal, Protocol, get_args

from pydantic import BaseModel, Field

from app.schemas.analyze import EventInput, ExtractionRequest

# 자재 대분류 단일 소스.
# DB의 materials.material_category와 값이 같아야 F9(대체 공급사 추천)가 매칭된다 —
# 값이 어긋나면 SupplierQualificationService의 WHERE material_category = ? 가 0건을 반환해
# 추천이 조용히 비어버린다(예: LLM이 "lithium carbonate"를 뱉던 문제).
# 자재를 추가·변경할 때는 여기만 고치면 mock/OpenAI 양쪽이 함께 따라간다.
MaterialCategory = Literal[
    "LITHIUM", "COBALT", "NICKEL", "GRAPHITE",
    "MANGANESE", "COPPER", "ALUMINUM", "RARE_EARTH",
]
MATERIAL_CATEGORIES: tuple[str, ...] = get_args(MaterialCategory)

# mock 추출용 키워드. 키는 위 MaterialCategory 값만 사용한다(타입체커가 오타를 잡는다).
# 키워드 매핑 자체는 언어별 표현이라 자동 파생이 불가능해 수동 유지한다.
#
# ⚠️ 순서 주의: 한 기사에서 여러 자재가 매칭되면 여기 등록 순서대로 리스트에 담기고,
# Spring AnalysisService가 그중 첫 번째만(get(0)) ERP 노출도·F9 추천에 사용한다.
# 그래서 기존 3종(NICKEL·COBALT·LITHIUM)의 순서를 바꾸면 같은 기사라도 분석 대상 자재가
# 달라진다 — 기존 동작 보존을 위해 순서를 유지하고, 신규 자재는 뒤에 추가한다.
_MATERIAL_KEYWORDS: dict[MaterialCategory, list[str]] = {
    "NICKEL": ["nickel", "니켈"],
    "COBALT": ["cobalt", "코발트"],
    "LITHIUM": ["lithium", "리튬"],
    "GRAPHITE": ["graphite", "흑연"],
    "MANGANESE": ["manganese", "망간"],
    "COPPER": ["copper", "구리"],
    "ALUMINUM": ["aluminum", "aluminium", "알루미늄"],
    "RARE_EARTH": ["rare earth", "rare-earth", "희토류"],
}

_EVENT_TYPE_RULES = [
    (["strike", "파업"], "STRIKE", "LOGISTICS"),
    (["export ban", "export restriction", "수출 제한", "수출 금지"], "EXPORT_RESTRICTION", "POLICY"),
    (["price volatility", "가격 변동성"], "MARKET_VOLATILITY", "MARKET"),
    (["flood", "rain", "폭우", "홍수"], "PRODUCTION_DISRUPTION", "PRODUCTION"),
]


@dataclass(frozen=True)
class ExtractionPrediction:
    affected_materials: list[str]
    event_type: str
    impact_domain_draft: str
    tone_score: float
    summary_kr: str
    model_version: str
    mock: bool
    is_supply_chain_relevant: bool = True


class ExtractionInference(Protocol):
    def extract(self, event: EventInput | ExtractionRequest) -> ExtractionPrediction: ...


class MockExtractionInference:
    """실제 LLM 연동 전까지 키워드 기반으로 뉴스 원문을 구조화 정보로 흉내 내는 Mock 구현체입니다."""

    MODEL_VERSION = "llm-extraction-v0.1-mock"

    def extract(self, event: EventInput | ExtractionRequest) -> ExtractionPrediction:
        text = f"{event.title} {event.content}".lower()

        affected_materials = [
            material for material, keywords in _MATERIAL_KEYWORDS.items()
            if any(keyword in text for keyword in keywords)
        ]

        event_type, impact_domain_draft = "UNCLASSIFIED", "IRRELEVANT"
        for keywords, candidate_event_type, candidate_domain in _EVENT_TYPE_RULES:
            if any(keyword in text for keyword in keywords):
                event_type, impact_domain_draft = candidate_event_type, candidate_domain
                break

        tone_score = -0.85 if event_type != "UNCLASSIFIED" else 0.0

        return ExtractionPrediction(
            affected_materials=affected_materials,
            event_type=event_type,
            impact_domain_draft=impact_domain_draft,
            tone_score=tone_score,
            summary_kr=event.content[:200],
            model_version=self.MODEL_VERSION,
            mock=True,
            is_supply_chain_relevant=event_type != "UNCLASSIFIED",
        )


# ── 실제 LLM 추출 (realtime-pipeline 브랜치에서 포팅) ─────────────
# OPENAI_API_KEY 없이도 임포트/생성은 가능하며, 실제 extract() 호출 시점에만 실패합니다
# (openai 클라이언트는 키를 생성 시점이 아니라 호출 시점에 검증하기 때문).

class _ArticleRiskExtraction(BaseModel):
    is_supply_chain_relevant: bool = Field(
        # 나열 목록은 아래 affected_material의 enum(MaterialCategory 8종)과 일치시킨다 —
        # 예전에는 철/주석/갈륨/게르마늄/안티모니/텅스텐까지 14종을 안내했지만 enum이 8종으로
        # 제한해 그 항목들은 모델이 골라도 조용히 []로 접혔다(설명과 출력이 어긋남).
        description="핵심광물(리튬/코발트/니켈/흑연/망간/구리/알루미늄/희토류)의 글로벌 공급망 "
                    "리스크(공급 부족, 가격 변동, 정책 제재 등)와 직결된 새로운 리스크 이벤트인지 여부."
    )
    country: str = Field(description="리스크 이벤트가 발생한 국가. 불분명하면 '알 수 없음'")
    # Structured Outputs가 이 Literal을 JSON 스키마 enum으로 변환해, 모델이 목록 밖 값을
    # 생성하지 못하도록 강제한다. 덕분에 DB material_category와 항상 일치한다.
    affected_material: list[MaterialCategory] = Field(
        description="영향받은 원자재 대분류. 기사 본문에 해당 원자재(또는 명백한 동의어·제품명)가 "
                    "실제로 언급되거나 직접적으로 연관된 경우에만 선택할 것 — 목록에 없거나 "
                    "확신할 수 없으면 반드시 빈 배열 []. '공급망 리스크 기사인 것 같다'는 "
                    "막연한 인상만으로 아무 자재나 추측해서 채우지 말 것.")
    tone_score: float = Field(description="-1.0(극도로 부정적/위기) ~ +1.0(극도로 긍정적/기회)")
    event_type: str = Field(description="파업, 관세 부과, 수출 금지 등 구체적 사건 키워드. 없으면 '알 수 없음'")
    impact_domain_draft: Literal["생산", "지정학", "정책", "물류", "시장", "기타/무관"] = Field(
        description="is_supply_chain_relevant가 False면 무조건 '기타/무관'. "
                    "True면 생산/지정학/정책/물류/시장 중 가장 핵심적인 리스크 원인 하나."
    )
    summary_kr: str = Field(description="핵심 내용 2문장 이내 한국어 요약. 무관이면 '해당 없음'")


_SYSTEM_PROMPT = """
You are an expert supply chain risk analyst for a battery manufacturing company.
Read the news article and extract key supply chain risk indicators into structured JSON.

[CRITICAL RULE - 2단계 필터링]
1단계: 이 기사가 핵심광물 공급망 리스크와 진정으로 관련 있는지 엄격히 판별. 전쟁/선거/정치
기사라도 광물 이야기가 핵심 주제가 아니면 False. 군사/안보/드론/테러/스파이/공항 보안 사건,
해협·항로의 지정학적 긴장 등은 그 자체로는 False다 — 기사 안에서 핵심광물의 채굴·정제·수출·
가격·물류가 구체적으로 언급되거나 논리적으로 직접 이어질 때만 True로 판정한다. "전략적
요충지라서 원자재에 영향을 줄 수도 있다" 같은 추론만으로는 True로 판정하지 말 것.
2단계: False면 impact_domain_draft는 무조건 "기타/무관", affected_material은 반드시 빈 배열([]).
True인 경우에도 어떤 구체적 자재가 영향받는지 본문에서 확인되지 않으면 affected_material은
빈 배열로 둔다 — 추측으로 채우는 것보다 비워두는 것이 낫다.

[HARD CONSTRAINT - 광물 명시 앵커]
본문에 핵심광물 이름(리튬/코발트/니켈/흑연/망간/구리/알루미늄/희토류 또는 그 영문명이
문자 그대로) 등장하지 않으면, 전쟁/제재/화물/물류/해협/유가 등 다른 신호가 아무리 강해도
반드시 False로 판정한다. 기사에 "공급망(supply chain)"이라는 단어가 등장한다는 사실만으로
관련 있다고 판단하지 말 것 — 그 단어가 핵심광물과 무관한 문맥(일반 물류, 식품, 반도체,
군수품 등)에서 쓰였을 수 있다.

[NEGATIVE EXAMPLES - False로 판정해야 하는 경우]
- "독일 공항에서 우크라이나 화물기 근처 폭발물 드론 발견, 여러 항공편 우회"
  → False (드론/보안/화물기 사건일 뿐 광물 언급 없음)
- "이란-오만 호르무즈 해협 통항 협정 타결 임박, 유가 소폭 하락"
  → False (해협/유가/지정학 이슈지만 광물 언급 없음)

[POSITIVE EXAMPLE - True로 판정해야 하는 경우]
- "콩고민주공화국 코발트 광산 파업 발생, 배터리 제조사向 선적 중단"
  → True, affected_material=["COBALT"], impact_domain_draft="생산"

[Rules for impact_domain_draft]
- 생산: 광산 파업, 자연재해로 인한 조업 중단, 설비 고장 등 직접적 생산 차질
- 물류: 항만 파업, 운하 봉쇄, 해운 운임 폭등 등 운송망 물리적 차질
- 정책: 수출 금지, 관세, 환경 규제, 광산 허가 취소, 국유화 등 정부 조치
- 시장: 가격 폭등/폭락, 수요 급감, 기업 파산 (단, 원인이 명시되면 그 원인의 도메인으로 분류)
- 지정학: 전쟁, 반군 공격, 쿠데타, 국가 간 외교 분쟁으로 인한 공급망 위협
  (전쟁/외교 분쟁 결과로 발생한 조업 중단/제재/수출 금지는 생산/정책이 아니라 지정학 우선)
- 기타/무관: 배터리 공급망 위기와 무관
"""

# 새 LLM 스키마(한국어 Literal)를 우리 ImpactDomain enum(영문)으로 매핑.
_DOMAIN_KO_TO_EN = {
    "생산": "PRODUCTION",
    "지정학": "GEOPOLITICS",
    "정책": "POLICY",
    "물류": "LOGISTICS",
    "시장": "MARKET",
    "기타/무관": "IRRELEVANT",
}

MAX_CHARS_FOR_LLM = 6000  # 토큰/비용 절감 — 본문 앞부분만으로도 판정 가능


class OpenAIExtractionInference:
    """실제 GPT-4o-mini 호출로 뉴스 원문을 구조화 정보로 추출합니다.

    주의: openai 2.x부터는 클라이언트 생성 시점에 키 존재 여부를 검증하므로,
    OPENAI_API_KEY가 없으면 이 클래스의 __init__ 자체가 실패합니다. 그래서
    이 클래스를 실제로 생성하는 건 app/api/dependencies.py의
    get_extraction_service()뿐이고, 거기서 키가 있을 때만 생성을 시도하고
    실패하면 MockExtractionInference로 폴백합니다 — 키가 없을 땐 이 클래스
    생성 자체를 시도하지 않으므로 앱 실행에는 영향이 없습니다.
    """

    MODEL_VERSION = "gpt-4o-mini-v1"

    def __init__(self) -> None:
        from openai import OpenAI
        self._client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))

    def extract(self, event: EventInput | ExtractionRequest) -> ExtractionPrediction:
        completion = self._client.beta.chat.completions.parse(
            model="gpt-4o-mini",
            messages=[
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": f"Title: {event.title}\n\nArticle Text:\n{event.content[:MAX_CHARS_FOR_LLM]}"},
            ],
            response_format=_ArticleRiskExtraction,
            # tone_score 등 추출 결과의 재현성을 위해 0으로 낮춘다. 같은 기사를 반복 추출했을 때
            # tone_score 부호가 -0.5/+0.5로 뒤집혀 severity 등급(WARNING/NORMAL)까지 바뀌는 것을
            # 실증으로 확인했다(2026-07-31). temperature=0.0만으로는 부족해서(5회 중 2/3 비율로
            # 여전히 뒤집힘) seed도 함께 고정한다 — 두 값을 같이 줘야 OpenAI가 결정론적 실행을
            # 시도한다(그래도 100% 보장은 아님, system_fingerprint로만 확인 가능).
            temperature=0.0,
            seed=42,
        )
        parsed = completion.choices[0].message.parsed

        return ExtractionPrediction(
            affected_materials=list(parsed.affected_material),
            event_type=parsed.event_type,
            impact_domain_draft=_DOMAIN_KO_TO_EN.get(parsed.impact_domain_draft, "IRRELEVANT"),
            tone_score=parsed.tone_score,
            summary_kr=parsed.summary_kr,
            model_version=self.MODEL_VERSION,
            mock=False,
            is_supply_chain_relevant=parsed.is_supply_chain_relevant,
        )
