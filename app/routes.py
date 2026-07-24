from fastapi import APIRouter

from app.graph.briefing_graph import briefing_graph
from app.schemas import BriefingRequest, BriefingResponse


router = APIRouter()


@router.post("/briefings", response_model=BriefingResponse)
def create_briefing(data: BriefingRequest):
    """Spring Boot 요청으로 멀티에이전트 구매 브리핑을 생성한다."""
    initial_state = {
        **data.model_dump(),
        "retry_count": 0,
        "warnings": [],
    }
    result = briefing_graph.invoke(initial_state)

    return {
        "news_id": result["news_id"],
        "impact_domain_final": result["impact_domain_final"],
        "procurement_risk_level": result["procurement_risk_level"],
        "procurement_risk_score": result["procurement_risk_score"],
        "risk_reasons": result["risk_reasons"],
        "erp_assessment": result["erp_assessment"],
        "erp_reassessment": result["erp_reassessment"],
        "contract_assessment": result["contract_assessment"],
        "contract_findings": result["contract_findings"],
        "recommended_actions": result["recommended_actions"],
        "briefing": result["briefing"],
        "llm_used": result.get("llm_used", False),
        "llm_error": result.get("llm_error"),
        "review_passed": result["review_passed"],
        "warnings": result["warnings"],
    }
