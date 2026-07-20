from app.schemas.analyze import EventInput
from app.schemas.extraction import ExtractionResult
from app.schemas.common import ImpactDomain


class ExtractionService:
    """Mock LLM boundary; preserves the production extraction schema."""

    def extract(self, event: EventInput) -> ExtractionResult:
        text = f"{event.title} {event.content}".lower()
        materials = [
            material for material, keywords in {
                "LITHIUM": ("lithium", "리튬"),
                "NICKEL": ("nickel", "니켈"),
                "COBALT": ("cobalt", "코발트"),
                "GRAPHITE": ("graphite", "흑연"),
                "MANGANESE": ("manganese", "망간"),
            }.items() if any(keyword in text for keyword in keywords)
        ] or ["UNKNOWN"]
        if any(keyword in text for keyword in ("rain", "flood", "폭우", "홍수")):
            event_type, draft = "FLOODING", ImpactDomain.PRODUCTION
        elif any(keyword in text for keyword in ("strike", "파업", "port closure", "항만 폐쇄")):
            event_type, draft = "STRIKE", ImpactDomain.LOGISTICS
        elif any(keyword in text for keyword in ("export ban", "export restriction", "수출 제한", "규제")):
            event_type, draft = "EXPORT_RESTRICTION", ImpactDomain.POLICY
        elif any(keyword in text for keyword in ("sanction", "conflict", "제재", "분쟁")):
            event_type, draft = "GEOPOLITICAL_CONFLICT", ImpactDomain.GEOPOLITICS
        elif any(keyword in text for keyword in ("price", "volatility", "가격", "변동성")):
            event_type, draft = "MARKET_VOLATILITY", ImpactDomain.MARKET
        else:
            event_type, draft = "UNKNOWN", ImpactDomain.IRRELEVANT
        return ExtractionResult(
            country_code=event.country_code or "CL",
            affected_materials=materials,
            event_type=event_type,
            tone_score=-0.68,
            impact_domain_draft=draft,
            summary_kr=f"{event.title}에서 공급망 관련 정보를 추출한 Mock 결과입니다.",
        )
