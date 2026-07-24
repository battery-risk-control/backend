from datetime import date, datetime
from decimal import Decimal
from enum import StrEnum

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
)
from pydantic.alias_generators import to_camel


class CamelCaseModel(BaseModel):
    """
    외부 JSON은 camelCase로 받고,
    Python 내부에서는 snake_case로 사용한다.
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class UnitType(StrEnum):
    KG = "KG"


class PurchaseOrderStatus(StrEnum):
    OPEN = "OPEN"
    CONFIRMED = "CONFIRMED"
    PARTIALLY_RECEIVED = "PARTIALLY_RECEIVED"
    DELAYED = "DELAYED"
    CANCELLED = "CANCELLED"
    CLOSED = "CLOSED"


class AlternativeSupplierStatus(StrEnum):
    APPROVED = "APPROVED"
    CONDITIONAL = "CONDITIONAL"
    PENDING = "PENDING"
    NONE = "NONE"


class SupplierStatus(StrEnum):
    ACTIVE = "ACTIVE"
    UNDER_REVIEW = "UNDER_REVIEW"
    SUSPENDED = "SUSPENDED"
    TERMINATED = "TERMINATED"


class MaterialContext(CamelCaseModel):
    """Spring Boot가 전달하는 자재별 ERP 원천 데이터."""

    material_id: str = Field(
        min_length=1,
        pattern=r"^MAT-[A-Z0-9-]+$",
    )
    material_name: str = Field(
        min_length=1,
    )
    unit: UnitType

    on_hand_quantity: Decimal = Field(
        ge=0,
    )
    reserved_quantity: Decimal = Field(
        ge=0,
    )
    blocked_quantity: Decimal = Field(
        ge=0,
    )
    quality_hold_quantity: Decimal = Field(
        ge=0,
    )

    average_daily_usage: Decimal = Field(
        ge=0,
    )
    safety_stock_quantity: Decimal = Field(
        ge=0,
    )

    supplier_dependency_ratio: Decimal = Field(
        ge=0,
        le=1,
    )

    purchase_order_status: (
        PurchaseOrderStatus | None
    ) = None

    alternative_supplier_status: (
        AlternativeSupplierStatus
    )

    supplier_status: SupplierStatus

    primary_supplier_id: str | None = Field(
        default=None,
        pattern=r"^SUP-[A-Z0-9-]+$",
    )

    primary_contract_id: str | None = Field(
        default=None,
        pattern=r"^CTR-[0-9]{3,}$",
    )

    inventory_snapshot_at: datetime | None = None

class PurchaseOrderItem(CamelCaseModel):
    purchase_order_item_id: str = Field(
        min_length=1,
        pattern=r"^POI-[A-Z0-9-]+$",
    )
    purchase_order_id: str = Field(
        min_length=1,
        pattern=r"^PO-[A-Z0-9-]+$",
    )
    material_id: str = Field(
        min_length=1,
        pattern=r"^MAT-[A-Z0-9-]+$",
    )
    supplier_id: str = Field(
        min_length=1,
        pattern=r"^SUP-[A-Z0-9-]+$",
    )
    contract_id: str | None = Field(
        default=None,
        pattern=r"^CTR-[0-9]{3,}$",
    )

    remaining_quantity: Decimal = Field(ge=0)
    order_status: PurchaseOrderStatus
    effective_arrival_date: date | None = None
    eligible_for_eta: bool

class AlternativeSupplierItem(CamelCaseModel):
    supplier_id: str = Field(
        min_length=1,
        pattern=r"^SUP-[A-Z0-9-]+$",
    )
    material_id: str = Field(
        min_length=1,
        pattern=r"^MAT-[A-Z0-9-]+$",
    )
    contract_id: str | None = Field(
        default=None,
        pattern=r"^CTR-[0-9]{3,}$",
    )

    supplier_status: SupplierStatus
    available_capacity_quantity: Decimal | None = Field(
        default=None,
        ge=0,
    )
    lead_time_days: int | None = Field(
        default=None,
        ge=0,
    )
    qualified: bool

class ErpContext(CamelCaseModel):
    material_context: MaterialContext | None = None
    purchase_orders: list[PurchaseOrderItem] = Field(
        default_factory=list,
    )
    alternative_suppliers: list[AlternativeSupplierItem] = Field(
        default_factory=list,
    )
