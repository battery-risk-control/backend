from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field

T = TypeVar("T")


class ApiModel(BaseModel):
    model_config = ConfigDict(
        use_enum_values=True,
        protected_namespaces=(),
    )


class Severity(str, Enum):
    NORMAL = "NORMAL"
    WARNING = "WARNING"
    CRITICAL = "CRITICAL"


class ImpactDomain(str, Enum):
    PRODUCTION = "PRODUCTION"
    LOGISTICS = "LOGISTICS"
    POLICY = "POLICY"
    MARKET = "MARKET"
    GEOPOLITICS = "GEOPOLITICS"
    IRRELEVANT = "IRRELEVANT"


class ProcessingStatus(str, Enum):
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class ApiResponse(ApiModel, Generic[T]):
    success: bool = True
    data: T
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class ErrorBody(ApiModel):
    code: str
    message: str
    details: Any = None


class ApiErrorResponse(ApiModel):
    success: bool = False
    error: ErrorBody
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
