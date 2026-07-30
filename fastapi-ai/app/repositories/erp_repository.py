from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class ErpContext:
    material_id: int
    supplier_ids: list[int]
    contract_ids: list[int]
    stock_days: int
    safety_stock_days: int
    expected_inbound_date: str


class ErpRepository(Protocol):
    def find_context(self, country_code: str, materials: list[str]) -> ErpContext: ...


class InMemoryErpRepository:
    def find_context(self, _country_code: str, _materials: list[str]) -> ErpContext:
        return ErpContext(
            material_id=1,
            supplier_ids=[11],
            contract_ids=[501],
            stock_days=12,
            safety_stock_days=20,
            expected_inbound_date="2026-08-04",
        )
