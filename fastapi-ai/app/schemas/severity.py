from pydantic import Field

from app.schemas.common import ApiModel, Severity


class SeverityResult(ApiModel):
    severity: Severity
    score: float = Field(ge=0.0, le=100.0)
    reason_codes: list[str]
    calculation_details: dict[str, float] = Field(default_factory=dict)
    rule_version: str
    mock: bool = True
