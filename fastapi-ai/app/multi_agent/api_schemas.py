from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


RiskLevel = Literal[
    "normal",
    "warning",
    "critical",
]


def to_camel(field_name: str) -> str:
    """snake_case 필드명을 camelCase로 변환한다."""

    parts = field_name.split("_")

    return parts[0] + "".join(
        part.capitalize()
        for part in parts[1:]
    )


class MultiAgentApiModel(BaseModel):
    """
    Spring은 camelCase, Python 내부는 snake_case를 사용한다.

    populate_by_name=True이므로 테스트에서는
    snake_case 입력도 사용할 수 있다.
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class MultiAgentBriefingRequest(
    MultiAgentApiModel,
):
    # 뉴스 분석 결과
    news_id: str = Field(min_length=1)
    title: str = Field(min_length=1)
    article_text: str = ""
    summary_kr: str = ""

    impact_domain_draft: str = "unknown"
    impact_domain_final: str = Field(min_length=1)

    affected_materials: list[str] = Field(
        default_factory=list,
    )

    # 외부 뉴스·재난·시장 신호 점수
    external_signal_level: RiskLevel = "normal"
    external_signal_score: int = Field(
        ge=0,
        le=100,
    )

    # Spring이 ERP DB에서 조회해 전달하는 데이터
    erp_context: dict[str, Any]

    # Minji RAG DB 내부 숫자 ID
    rag_contract_id: int | None = None
    rag_supplier_id: int | None = None
    rag_material_id: int | None = None

    # Response Agent의 GPT-4o mini 사용 여부
    use_llm: bool = False


class MultiAgentBriefingResponse(
    MultiAgentApiModel,
):
    news_id: str
    impact_domain_final: str

    procurement_risk_level: RiskLevel
    procurement_risk_score: int
    risk_reasons: list[str]

    erp_assessment: dict[str, Any]
    erp_reassessment: dict[str, Any]

    contract_assessment: dict[str, Any]
    contract_findings: list[dict[str, Any]]

    recommended_actions: list[str]
    briefing: str

    llm_used: bool
    llm_error: str | None

    review_passed: bool
    warnings: list[str]