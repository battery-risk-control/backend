import csv
from pathlib import Path

import pytest

from app.schemas.erp import (
    ErpExposureRequest,
)

from app.services.erp_exposure_service import (
    calculateErpExposure,
)


FIXTURE_DIR = (
    Path(__file__).resolve().parent
    / "fixtures"
    / "agent-csv"
)


def readCsv(
    fileName: str,
) -> list[dict[str, str]]:
    """
    CSV 파일을 dict 목록으로 읽는다.

    utf-8-sig를 사용해 CSV 앞의 BOM을 자동 제거한다.
    """

    filePath = FIXTURE_DIR / fileName

    if not filePath.exists():
        raise FileNotFoundError(
            f"테스트 CSV를 찾을 수 없습니다: "
            f"{filePath}"
        )

    with filePath.open(
        "r",
        encoding="utf-8-sig",
        newline="",
    ) as file:
        return list(
            csv.DictReader(file)
        )

def noneIfBlank(
    value: str | None,
) -> str | None:
    """빈 문자열을 None으로 변환한다."""

    if value is None:
        return None

    strippedValue = value.strip()

    if strippedValue == "":
        return None

    return strippedValue

def toFloatOrNone(
    value: str | None,
) -> float | None:
    """CSV 문자열을 float 또는 None으로 변환한다."""

    normalizedValue = noneIfBlank(value)

    if normalizedValue is None:
        return None

    return float(normalizedValue)

def toIntOrNone(
    value: str | None,
) -> int | None:
    """CSV 문자열을 int 또는 None으로 변환한다."""

    normalizedValue = noneIfBlank(value)

    if normalizedValue is None:
        return None

    return int(float(normalizedValue))

def toBool(
    value: str | None,
) -> bool:
    """CSV의 Boolean 문자열을 bool로 변환한다."""

    normalizedValue = (
        noneIfBlank(value) or ""
    ).lower()

    if normalizedValue in {
        "true",
        "1",
        "yes",
    }:
        return True

    if normalizedValue in {
        "false",
        "0",
        "no",
        "",
    }:
        return False

    raise ValueError(
        f"Boolean으로 변환할 수 없습니다: "
        f"{value}"
    )


REQUEST_ROWS = readCsv(
    "01_erp_agent_requests.csv"
)

MATERIAL_ROWS = readCsv(
    "02_erp_material_context.csv"
)

PURCHASE_ORDER_ROWS = readCsv(
    "03_erp_purchase_order_context.csv"
)

EXPECTED_ROWS = readCsv(
    "04_erp_exposure_expected.csv"
)

CONTRACT_REVIEW_ROWS = readCsv(
    "05_contract_review_requests.csv"
)


MATERIAL_BY_REQUEST_ID = {
    row["requestId"]: row
    for row in MATERIAL_ROWS
}

EXPECTED_BY_REQUEST_ID = {
    row["requestId"]: row
    for row in EXPECTED_ROWS
}

CONTRACT_REVIEW_REQUEST_IDS = {
    row["requestId"]
    for row in CONTRACT_REVIEW_ROWS
}

CONTRACT_REVIEW_BY_REQUEST_ID = {
    row["requestId"]: row
    for row in CONTRACT_REVIEW_ROWS
}


def buildMaterialContext(
    materialRow: dict[str, str],
) -> dict:
    """
    Agent CSV 자재 행을 ErpMaterialContext 입력으로 변환한다.

    다음 계산 결과는 CSV에서 받지 않고 FastAPI가 계산한다.

    제외 필드:
    - availableQuantity
    - inventoryDays
    - safetyStockDays
    - nextEtaDays
    - dataQualityStatus
    """

    return {
        "materialId": (
            materialRow["materialId"]
        ),
        "materialName": (
            materialRow["materialName"]
        ),
        "unit": materialRow["unit"],
        "onHandQuantity": float(
            materialRow["onHandQuantity"]
        ),
        "reservedQuantity": float(
            materialRow["reservedQuantity"]
        ),
        "blockedQuantity": float(
            materialRow["blockedQuantity"]
        ),
        "qualityHoldQuantity": float(
            materialRow[
                "qualityHoldQuantity"
            ]
        ),
        "averageDailyUsage": toFloatOrNone(
            materialRow[
                "averageDailyUsage"
            ]
        ),
        "safetyStockQuantity": toFloatOrNone(
            materialRow[
                "safetyStockQuantity"
            ]
        ),
        "supplierDependencyRatio": (
            toFloatOrNone(
                materialRow[
                    "supplierDependencyRatio"
                ]
            )
        ),
        "alternativeSupplierStatus": (
            noneIfBlank(
                materialRow[
                    "alternativeSupplierStatus"
                ]
            )
        ),
        "supplierStatus": noneIfBlank(
            materialRow["supplierStatus"]
        ),
        "primarySupplierId": noneIfBlank(
            materialRow[
                "primarySupplierId"
            ]
        ),
        "primaryContractId": noneIfBlank(
            materialRow[
                "primaryContractId"
            ]
        ),

        "inventorySnapshotAt": noneIfBlank(
            materialRow[
                "inventorySnapshotAt"
            ]
        ),
    }

def buildPurchaseOrders(
    materialId: str,
) -> list[dict]:
    """자재 ID에 해당하는 발주 Context를 구성한다."""

    matchingRows = [
        row
        for row in PURCHASE_ORDER_ROWS
        if row["materialId"] == materialId
    ]

    purchaseOrders: list[dict] = []

    for row in matchingRows:
        purchaseOrders.append(
            {
                "purchaseOrderItemId": (
                    row["purchaseOrderItemId"]
                ),
                "purchaseOrderId": (
                    row["purchaseOrderId"]
                ),
                "materialId": (
                    row["materialId"]
                ),
                "supplierId": (
                    row["supplierId"]
                ),
                "contractId": noneIfBlank(
                    row["contractId"]
                ),
                "remainingQuantity": float(
                    row["remainingQuantity"]
                ),
                "orderStatus": (
                    row["orderStatus"]
                ),
                "effectiveArrivalDate": (
                    noneIfBlank(
                        row[
                            "effectiveArrivalDate"
                        ]
                    )
                ),

                # etaDays는 FastAPI가 다시 계산하므로
                # 입력에 포함하지 않는다.
                "eligibleForEta": toBool(
                    row["eligibleForEta"]
                ),
            }
        )

    return purchaseOrders

def buildErpRequest(
    requestRow: dict[str, str],
) -> ErpExposureRequest:
    """세 CSV를 조합해 ERP 분석 요청을 생성한다."""

    requestId = requestRow["requestId"]

    materialRow = (
        MATERIAL_BY_REQUEST_ID[requestId]
    )

    affectedMaterialId = requestRow[
        "affectedMaterialId"
    ]

    requestData = {
        "requestId": requestId,
        "eventId": requestRow["eventId"],
        "asOf": requestRow["asOf"],
        "impactDomain": (
            requestRow["impactDomain"]
        ),
        "externalSignalScore": float(
            requestRow[
                "externalSignalScore"
            ]
        ),
        "externalSignalLevel": (
            requestRow[
                "externalSignalLevel"
            ]
        ),
        "affectedMaterialId": (
            affectedMaterialId
        ),
        "affectedSupplierId": noneIfBlank(
            requestRow[
                "affectedSupplierId"
            ]
        ),
        "affectedCountryCode": noneIfBlank(
            requestRow[
                "affectedCountryCode"
            ]
        ),
        "primaryContractId": noneIfBlank(
            requestRow[
                "primaryContractId"
            ]
        ),
        "eventSummary": (
            requestRow["eventSummary"]
        ),
        "erpAnalysisRequired": toBool(
            requestRow[
                "erpAnalysisRequired"
            ]
        ),
        "materialContext": (
            buildMaterialContext(
                materialRow
            )
        ),
        "purchaseOrders": (
            buildPurchaseOrders(
                affectedMaterialId
            )
        ),
    }

    return ErpExposureRequest.model_validate(
        requestData
    )

def assertOptionalFloat(
    actualValue: float | None,
    expectedText: str | None,
) -> None:
    """선택형 숫자를 안전하게 비교한다."""

    expectedValue = toFloatOrNone(
        expectedText
    )

    if expectedValue is None:
        assert actualValue is None
        return

    assert actualValue == pytest.approx(
        expectedValue,
        abs=0.01,
    )


@pytest.mark.parametrize(
    "requestRow",
    REQUEST_ROWS,
    ids=lambda row: row["requestId"],
)
def testErpCsvRegression(
    requestRow: dict[str, str],
) -> None:
    """
    CSV의 10개 시나리오에 대해
    실제 ERP 계산 결과와 예상 결과를 비교한다.
    """

    requestId = requestRow["requestId"]

    request = buildErpRequest(
        requestRow
    )

    result = calculateErpExposure(
        request
    )

    expected = (
        EXPECTED_BY_REQUEST_ID[requestId]
    )

    assert (
        result.requestId
        == requestId
    )

    assertOptionalFloat(
        actualValue=(
            result.facts.inventoryDays
        ),
        expectedText=(
            expected["inventoryDays"]
        ),
    )

    assertOptionalFloat(
        actualValue=(
            result.facts.safetyStockDays
        ),
        expectedText=(
            expected["safetyStockDays"]
        ),
    )

    expectedNextEtaDays = toIntOrNone(
        expected["nextEtaDays"]
    )

    assert (
        result.facts.nextEtaDays
        == expectedNextEtaDays
    )

    assertOptionalFloat(
        actualValue=(
            result.facts
            .expectedSupplyGapDays
        ),
        expectedText=(
            expected[
                "expectedSupplyGapDays"
            ]
        ),
    )

    assertOptionalFloat(
        actualValue=(
            result.facts
            .supplierDependencyRatio
        ),
        expectedText=(
            expected[
                "supplierDependencyRatio"
            ]
        ),
    )

    assertOptionalFloat(
        actualValue=(
            result.riskComponents
            .gapRiskScore
        ),
        expectedText=(
            expected["gapRiskScore"]
        ),
    )

    assertOptionalFloat(
        actualValue=(
            result.riskComponents
            .safetyStockRiskScore
        ),
        expectedText=(
            expected[
                "safetyStockRiskScore"
            ]
        ),
    )

    assertOptionalFloat(
        actualValue=(
            result.riskComponents
            .dependencyRiskScore
        ),
        expectedText=(
            expected[
                "dependencyRiskScore"
            ]
        ),
    )

    assertOptionalFloat(
        actualValue=(
            result.riskComponents
            .purchaseOrderDelayRiskScore
        ),
        expectedText=(
            expected[
                "purchaseOrderDelayRiskScore"
            ]
        ),
    )

    assertOptionalFloat(
        actualValue=(
            result.riskComponents
            .alternativeSupplierRiskScore
        ),
        expectedText=(
            expected[
                "alternativeSupplierRiskScore"
            ]
        ),
    )

    assertOptionalFloat(
        actualValue=(
            result.erpExposureScore
        ),
        expectedText=(
            expected["erpExposureScore"]
        ),
    )

    assert (
        result.forcedCritical
        is toBool(
            expected["forcedCritical"]
        )
    )

    assert (
        result.exposureLevel.value
        == expected["expectedLevel"]
    )

    assert (
        result.dataQualityStatus.value
        == expected["dataQualityStatus"]
    )

    expectedContractReview = (
        requestId
        in CONTRACT_REVIEW_REQUEST_IDS
    )

    assert (
        result.contractReviewRequired
        is expectedContractReview
    )

    # 계약 거토 예상 데이터 조회
    expectedContractReviewRow = (
        CONTRACT_REVIEW_BY_REQUEST_ID.get(
            requestId
        )
    )

    if expectedContractReviewRow is None:
        # 계약 검토 대상이 아니면 질문도 없어야 함.
        assert (
            result.questionsForContractAgent
            == []
        )
    else:
        # CSV의 예상 질문 코드
        expectedQuestionCodes = {
            questionCode.strip()
            for questionCode in (
                expectedContractReviewRow[
                    "questionCodes"
                ].split(";")
            )
            if questionCode.strip()
        }

        # 실제 ERP Agent가 생성한 질문 코드
        actualQuestionCodes = {
            question.questionCode.value
            for question
            in result.questionsForContractAgent
        }

        assert (
            actualQuestionCodes
            == expectedQuestionCodes
        )

        # CSV의 예상 계약 ID
        expectedContractId = (
            expectedContractReviewRow[
                "contractId"
            ]
        )

        # 실제 질문에 포함된 계약 ID
        actualContractIds = {
            question.contractId
            for question
            in result.questionsForContractAgent
        }

        assert actualContractIds == {
            expectedContractId
        }