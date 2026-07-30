from enum import Enum
from typing import Any

from pydantic import Field, field_validator

from app.schemas.common import ApiModel, Severity


class FeocStatus(str, Enum):
    YES = "YES"
    NO = "NO"
    UNKNOWN = "UNKNOWN"


class DataQualityStatus(str, Enum):
    VALID = "VALID"
    STALE = "STALE"
    INCOMPLETE = "INCOMPLETE"
    INVALID = "INVALID"
    UNKNOWN = "UNKNOWN"


class SeverityInput(ApiModel):
    inventory_days: float | None = Field(default=None, ge=0)
    safety_stock_days: float | None = Field(default=None, ge=0)
    expected_supply_gap_days: float | None = Field(default=None, ge=0)
    supplier_dependency_ratio: float | None = Field(default=None, ge=0, le=1)
    price_change_rate: float | None = None
    logistics_delay_days: float | None = Field(default=None, ge=0)
    gdacs_alert_level: int | None = Field(default=None, ge=0, le=2)
    feoc_status: FeocStatus = FeocStatus.UNKNOWN
    data_quality_status: DataQualityStatus = DataQualityStatus.UNKNOWN

    @field_validator("feoc_status", mode="before")
    @classmethod
    def normalize_feoc_status(cls, value: Any) -> Any:
        if value is True:
            return FeocStatus.YES
        if value is False:
            return FeocStatus.NO
        return value


class SeverityResult(ApiModel):
    severity: Severity
    score: float = Field(ge=0.0, le=100.0)
    reason_codes: list[str]
    calculation_details: dict[str, Any] = Field(default_factory=dict)
    rule_version: str
    mock: bool = True
