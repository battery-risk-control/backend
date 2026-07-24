from app.agents.erp_agent import (
    analyze_erp_node,
    recheck_erp_node,
)
from decimal import Decimal


def test_erp_node_detects_inventory_shortage():
    state = {
        "erp_context": {
            "inventory_days": 18,
            "safety_stock_days": 20,
            "open_orders": 2,
            "supplier_dependency": 0.72
        }
    }

    result = analyze_erp_node(state)
    erp_findings = result["erp_findings"]

    assert erp_findings["inventory_status"] == "warning"
    assert "현재 재고가 안전재고 기준보다 2일 부족합니다." in erp_findings["findings"]
    assert "미입고 발주가 2건 존재합니다." in erp_findings["findings"]
    assert "해당 공급사 의존도가 72%로 높습니다." in erp_findings["findings"]


def test_erp_node_handles_missing_data():
    state = {
        "erp_context": {}
    }

    result = analyze_erp_node(state)
    erp_findings = result["erp_findings"]

    assert erp_findings["inventory_status"] == "unknown"
    assert "재고 또는 안전재고 데이터가 없습니다." in erp_findings["findings"]


def test_erp_node_creates_agent_assessment():
    state = {
        "erp_context": {
            "inventory_days": 18,
            "safety_stock_days": 20,
            "open_orders": 2,
            "supplier_dependency": 0.80,
            "next_inbound_eta_days": 25,
            "has_alternative_supplier": False,
            "contract_ids": [
                "CONTRACT-001",
                "CONTRACT-002",
            ],
        }
    }

    result = analyze_erp_node(state)
    assessment = result["erp_assessment"]

    assert assessment["erp_exposure_score"] == 100
    assert assessment["inventory_status"] == "warning"
    assert assessment["stockout_before_eta"] is True
    assert assessment["has_alternative_supplier"] is False

    assert result["affected_contract_ids"] == [
        "CONTRACT-001",
        "CONTRACT-002",
    ]

    assert len(result["questions_for_contract_agent"]) == 2


def test_erp_agent_rechecks_contract_question():
    state = {
        "erp_context": {
            "inventory_days": 18,
            "contract_adjusted_eta_days": 25,
        },
        "erp_assessment": {
            "erp_exposure_score": 40,
            "stockout_before_eta": False,
            "has_alternative_supplier": False,
            "findings": [
                "현재 재고가 안전재고 기준보다 부족합니다."
            ],
        },
        "questions_for_erp_agent": [
            "변경된 납기를 반영해도 재고 소진 전에 입고 가능한가?"
        ],
    }

    result = recheck_erp_node(state)

    reassessment = result["erp_reassessment"]

    assert result["erp_reassessment_done"] is True

    assert reassessment["score_before"] == 40
    assert reassessment["score_after"] == 50

    assert (
        reassessment["requires_human_confirmation"]
        is False
    )

    assert (
        result["erp_assessment"]["stockout_before_eta"]
        is True
    )

    assert len(
        reassessment["checked_questions"]
    ) == 1

    assert any(
        "조정된 입고 예정일보다 재고 소진일이 빠릅니다"
        in finding
        for finding in reassessment["findings"]
    )


def test_erp_recheck_does_not_duplicate_findings():
    initial_state = {
        "erp_context": {
            "inventory_days": 18,
            "contract_adjusted_eta_days": 25,
        },

        "erp_assessment": {
            "erp_exposure_score": 100,
            "stockout_before_eta": True,
            "has_alternative_supplier": False,
            "findings": [
                "현재 재고가 안전재고 기준보다 부족합니다."
            ],
        },

        "questions_for_erp_agent": [
            "변경된 납기를 반영해 재고를 확인합니다."
        ],
    }

    first_result = recheck_erp_node(
        initial_state
    )

    second_state = {
        **initial_state,
        **first_result,
    }

    second_result = recheck_erp_node(
        second_state
    )

    findings = second_result[
        "erp_assessment"
    ]["findings"]

    assert findings.count(
        "계약상 조정된 입고 예정일보다 "
        "재고 소진일이 빠릅니다."
    ) == 1

    assert findings.count(
        "계약 분석 결과를 반영해 재확인했으나 "
        "등록된 대체 공급사가 없습니다."
    ) == 1

def test_erp_agent_accepts_new_spring_erp_context():
    state = {
        "erp_context": {
            "materialContext": {
                "materialId": "MAT-CO-SULF",
                "materialName": "Cobalt Sulfate",
                "unit": "KG",
                "onHandQuantity": 7280,
                "reservedQuantity": 429,
                "blockedQuantity": 195,
                "qualityHoldQuantity": 156,
                "averageDailyUsage": 1000,
                "safetyStockQuantity": 18000,
                "supplierDependencyRatio": 0.84,
                "purchaseOrderStatus": "DELAYED",
                "alternativeSupplierStatus": "NONE",
                "supplierStatus": "ACTIVE",
                "primarySupplierId": "SUP-COD-01",
                "primaryContractId": "CTR-010",
                "inventorySnapshotAt": (
                    "2026-07-22T08:00:00+09:00"
                ),
            },
            "purchaseOrders": [
                {
                    "purchaseOrderItemId": "POI-001",
                    "purchaseOrderId": "PO-001",
                    "materialId": "MAT-CO-SULF",
                    "supplierId": "SUP-COD-01",
                    "contractId": "CTR-010",
                    "remainingQuantity": 5000,
                    "orderStatus": "DELAYED",
                    "effectiveArrivalDate": "2026-07-30",
                    "eligibleForEta": True,
                }
            ],
            "alternativeSuppliers": [],
        }
    }

    result = analyze_erp_node(state)
    assessment = result["erp_assessment"]

    assert assessment["erp_exposure_score"] == 100
    assert assessment["inventory_status"] == "warning"
    assert assessment["stockout_before_eta"] is True
    assert assessment["has_alternative_supplier"] is False
    assert (
    assessment["expected_supply_gap_days"]
    == Decimal("1.5")
    )
    assert assessment["manual_review_required"] is False
    assert assessment["calculation_warnings"] == []
    assert result["affected_contract_ids"] == ["CTR-010"]

    assert (
        "납기 지연 또는 공급 중단 시 적용할 수 있는 "
        "계약 조항이 있는가?"
        in result["questions_for_contract_agent"]
    )
    assert (
        "기존 계약에서 대체 조달을 제한하는 "
        "조항이 있는가?"
        in result["questions_for_contract_agent"]
    )

def test_erp_recheck_accepts_new_spring_erp_context():
    state = {
        "erp_context": {
            "materialContext": {
                "materialId": "MAT-CO-SULF",
                "materialName": "Cobalt Sulfate",
                "unit": "KG",
                "onHandQuantity": 7280,
                "reservedQuantity": 429,
                "blockedQuantity": 195,
                "qualityHoldQuantity": 156,
                "averageDailyUsage": 1000,
                "safetyStockQuantity": 18000,
                "supplierDependencyRatio": 0.84,
                "purchaseOrderStatus": "DELAYED",
                "alternativeSupplierStatus": "NONE",
                "supplierStatus": "ACTIVE",
                "primarySupplierId": "SUP-COD-01",
                "primaryContractId": "CTR-010",
                "inventorySnapshotAt": (
                    "2026-07-22T08:00:00+09:00"
                ),
            },
            "purchaseOrders": [],
            "alternativeSuppliers": [],
        },
        "erp_assessment": {
            "erp_exposure_score": 75,
            "stockout_before_eta": False,
            "has_alternative_supplier": False,
            "findings": [],
        },
        "questions_for_erp_agent": [
            "계약상 납기 변경 일정을 반영했을 때 "
            "재고 소진 전에 입고가 가능한가?"
        ],
    }

    result = recheck_erp_node(state)
    reassessment = result["erp_reassessment"]

    assert result["erp_reassessment_done"] is True
    assert reassessment["requires_human_confirmation"] is True
    assert (
        "계약 분석 질문은 존재하지만 변경된 입고 일정이 "
        "ERP 데이터에 없어 담당자 확인이 필요합니다."
        in reassessment["findings"]
    )
