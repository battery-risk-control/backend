from typing import Literal

from pydantic import BaseModel, Field
from app.erp.schemas import ErpContext


RiskLevel = Literal["normal", "warning", "critical"]


class BriefingRequest(BaseModel):
    """Spring Boot가 멀티에이전트 실행을 요청할 때 보내는 데이터."""

    news_id: str
    title: str
    article_text: str = ""
    summary_kr: str = ""
    impact_domain_draft: str = "unknown"
    impact_domain_final: str
    external_signal_level: RiskLevel
    external_signal_score: int = Field(ge=0, le=100)
    affected_materials: list[str] = Field(default_factory=list)
    use_llm: bool = False
    erp_context: ErpContext = Field(
    default_factory=ErpContext,
    )


class BriefingResponse(BaseModel):
    """멀티에이전트 실행 후 Spring Boot에 반환하는 결과."""

    news_id: str
    impact_domain_final: str
    procurement_risk_level: RiskLevel
    procurement_risk_score: int
    risk_reasons: list[str]
    erp_assessment: dict
    erp_reassessment: dict
    contract_assessment: dict
    contract_findings: list[dict]
    recommended_actions: list[str]
    briefing: str
    llm_used: bool
    llm_error: str | None
    review_passed: bool
    warnings: list[str]