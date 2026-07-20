from pydantic import Field

from app.schemas.common import ApiModel, ProcessingStatus


class BriefingGenerationRequest(ApiModel):
    risk_id: int
    event_summary: str = "Mock event summary"
    inventory_summary: str = "Mock inventory summary"
    contract_summary: str = "Mock contract summary"


class BriefingGenerationResult(ApiModel):
    briefing_id: int
    risk_id: int
    status: ProcessingStatus
    headline: str
    event_summary: str
    inventory_perspective: str
    contract_perspective: str
    recommended_actions: list[str]
    warnings: list[str] = Field(default_factory=list)
    mock: bool = True
