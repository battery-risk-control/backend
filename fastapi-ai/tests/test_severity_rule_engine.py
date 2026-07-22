from fastapi.testclient import TestClient

from app.main import app
from app.schemas.severity import SeverityInput
from app.services.severity_service import SeverityService


client = TestClient(app)


def _input(**changes) -> SeverityInput:
    values = {
        "inventory_days": 30,
        "safety_stock_days": 20,
        "expected_supply_gap_days": 0,
        "supplier_dependency_ratio": 0.30,
        "price_change_rate": 2.0,
        "logistics_delay_days": 0,
        "gdacs_alert_level": 0,
        "feoc_status": "NO",
        "data_quality_status": "VALID",
    }
    values.update(changes)
    return SeverityInput(**values)


def test_normal_scenario_has_zero_score() -> None:
    result = SeverityService().evaluate(_input())
    assert result.severity == "NORMAL"
    assert result.score == 0.0
    assert result.reason_codes == ["NO_RISK_RULE_TRIGGERED"]


def test_warning_scenario_explains_low_inventory() -> None:
    result = SeverityService().evaluate(_input(inventory_days=15))
    assert result.severity == "WARNING"
    assert result.score == 30.0
    assert result.reason_codes == ["INVENTORY_BELOW_SAFETY"]
    assert result.calculation_details["component_scores"]["inventory"] == 30.0


def test_critical_scenario_combines_inventory_and_external_alert() -> None:
    result = SeverityService().evaluate(
        _input(inventory_days=9, gdacs_alert_level=2)
    )
    assert result.severity == "CRITICAL"
    assert result.score == 75.0
    assert result.reason_codes == [
        "INVENTORY_BELOW_HALF_SAFETY",
        "GDACS_RED_ALERT",
    ]


def test_unknown_scenario_does_not_invent_missing_values() -> None:
    result = SeverityService().evaluate(SeverityInput())
    assert result.severity == "UNKNOWN"
    assert result.score == 0.0
    assert result.reason_codes == ["INSUFFICIENT_DATA"]
    assert len(result.calculation_details["missing_inputs"]) == 7


def test_invalid_data_quality_returns_unknown() -> None:
    result = SeverityService().evaluate(_input(data_quality_status="INVALID"))
    assert result.severity == "UNKNOWN"
    assert result.reason_codes == ["INVALID_DATA_QUALITY"]


def test_feoc_hard_gate_forces_critical_even_without_numeric_inputs() -> None:
    result = SeverityService().evaluate(SeverityInput(feoc_status="YES"))
    assert result.severity == "CRITICAL"
    assert result.score == 100.0
    assert result.reason_codes == ["FEOC_HARD_GATE"]
    assert result.calculation_details["forced_critical"] is True


def test_same_input_always_returns_same_result() -> None:
    service = SeverityService()
    inputs = _input(
        inventory_days=12,
        expected_supply_gap_days=8,
        supplier_dependency_ratio=0.72,
        price_change_rate=11.5,
        logistics_delay_days=7,
        gdacs_alert_level=1,
    )
    assert service.evaluate(inputs) == service.evaluate(inputs)


def test_internal_api_exposes_stage_11_request_and_response() -> None:
    payload = _input(inventory_days=15).model_dump(mode="json")
    response = client.post("/api/v1/internal/severity/score", json=payload)
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["severity"] == "WARNING"
    assert data["rule_version"] == "severity-rule-v1"
    assert data["mock"] is True
    assert "calculation_details" in data


def test_internal_api_validates_ranges() -> None:
    response = client.post(
        "/api/v1/internal/severity/score",
        json={"supplier_dependency_ratio": 1.1, "gdacs_alert_level": 3},
    )
    assert response.status_code == 422


def test_openapi_contains_all_stage_11_fields() -> None:
    schema = client.get("/openapi.json").json()
    operation = schema["paths"]["/api/v1/internal/severity/score"]["post"]
    request_ref = operation["requestBody"]["content"]["application/json"]["schema"]["$ref"]
    request_name = request_ref.rsplit("/", 1)[-1]
    properties = schema["components"]["schemas"][request_name]["properties"]
    assert {
        "inventory_days",
        "safety_stock_days",
        "expected_supply_gap_days",
        "supplier_dependency_ratio",
        "price_change_rate",
        "logistics_delay_days",
        "gdacs_alert_level",
        "feoc_status",
        "data_quality_status",
    } <= properties.keys()
