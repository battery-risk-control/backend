from app.schemas.analyze import EventInput, ExtractionResult
from app.schemas.common import ImpactDomain


class ExtractionService:
    """Mock LLM boundary; preserves the production extraction schema."""

    def extract(self, event: EventInput) -> ExtractionResult:
        return ExtractionResult(
            country_code=event.country_code or "CL",
            affected_materials=["LITHIUM"],
            event_type="FLOODING",
            tone_score=-0.68,
            impact_domain_draft=ImpactDomain.PRODUCTION,
            summary_kr="칠레 리튬 생산 지역에 폭우가 발생해 생산 시설 운영에 차질이 발생했다.",
        )
