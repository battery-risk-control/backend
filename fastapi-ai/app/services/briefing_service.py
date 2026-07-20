from app.schemas.briefing import BriefingGenerationRequest, BriefingGenerationResult
from app.schemas.common import ProcessingStatus


class BriefingService:
    """Mock boundary for a future LLM briefing generator."""

    def generate(self, request: BriefingGenerationRequest) -> BriefingGenerationResult:
        return BriefingGenerationResult(
            briefing_id=7001,
            risk_id=request.risk_id,
            status=ProcessingStatus.COMPLETED,
            headline=f"리스크 {request.risk_id} 내부 브리핑",
            event_summary=request.event_summary,
            inventory_perspective=request.inventory_summary,
            contract_perspective=request.contract_summary,
            recommended_actions=[
                "입고 예정일과 실제 ETA를 확인한다.",
                "가격 조정 조항 적용 가능 여부를 검토한다.",
            ],
            mock=True,
        )


def generate_briefing(request: BriefingGenerationRequest) -> BriefingGenerationResult:
    return BriefingService().generate(request)
