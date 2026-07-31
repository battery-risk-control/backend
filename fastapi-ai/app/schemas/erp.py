from __future__ import annotations

from datetime import date, datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


# =========================================================
# 공통 Enum
# =========================================================

class ExposureLevel(str, Enum):
    """ERP 기준 자사 노출도 등급."""

    NORMAL = "NORMAL"
    WARNING = "WARNING"
    CRITICAL = "CRITICAL"
    UNKNOWN = "UNKNOWN"

class ExternalSignalLevel(str, Enum):
    """뉴스·외부 데이터 기준 위험 신호 등급."""

    NORMAL = "NORMAL"
    WARNING = "WARNING"
    CRITICAL = "CRITICAL"

class DataQualityStatus(str, Enum):
    """ERP 계산에 사용한 데이터의 품질 상태."""

    VALID = "VALID"
    INCOMPLETE = "INCOMPLETE"
    STALE = "STALE"
    INVALID = "INVALID"

class ImpactDomain(str, Enum):
    """XGBoost가 분류한 공급망 사건 유형."""

    PRODUCTION = "PRODUCTION"
    LOGISTICS = "LOGISTICS"
    POLICY = "POLICY"
    MARKET = "MARKET"
    GEOPOLITICS = "GEOPOLITICS"
    OTHER_IRRELEVANT = "OTHER_IRRELEVANT"

class PurchaseOrderStatus(str, Enum):
    """ERP 발주 상태."""

    OPEN = "OPEN"
    CONFIRMED = "CONFIRMED"
    PARTIALLY_RECEIVED = "PARTIALLY_RECEIVED"
    DELAYED = "DELAYED"
    CANCELLED = "CANCELLED"
    CLOSED = "CLOSED"

class AlternativeSupplierStatus(str, Enum):
    """대체 공급사 사용 가능 상태."""

    APPROVED = "APPROVED"
    CONDITIONAL = "CONDITIONAL"
    PENDING = "PENDING"
    NONE = "NONE"

class SupplierStatus(str, Enum):
    """공급사 거래 상태."""

    ACTIVE = "ACTIVE"
    UNDER_REVIEW = "UNDER_REVIEW"
    SUSPENDED = "SUSPENDED"
    TERMINATED = "TERMINATED"

class SupplierQualificationStatus(
    str,
    Enum,
):
    """대체 공급사의 자재 공급 승인 상태."""

    APPROVED = "APPROVED"
    CONDITIONAL = "CONDITIONAL"
    PENDING = "PENDING"
    REJECTED = "REJECTED"

class SupplierCapacityStatus(
    str,
    Enum,
):
    """대체 공급사의 필요수량 대비 가용 capacity 상태."""

    SUFFICIENT = "SUFFICIENT"
    PARTIAL = "PARTIAL"
    UNAVAILABLE = "UNAVAILABLE"
    UNKNOWN = "UNKNOWN"

class ContractQuestionCode(str, Enum):
    """ERP Agent가 Contract Agent에 전달하는 질문 유형."""

    DELIVERY_DELAY_NOTICE = "DELIVERY_DELAY_NOTICE"
    DELIVERY_PENALTY = "DELIVERY_PENALTY"
    FORCE_MAJEURE = "FORCE_MAJEURE"
    ALTERNATIVE_SUPPLIER_RESTRICTION = (
        "ALTERNATIVE_SUPPLIER_RESTRICTION"
    )
    EMERGENCY_PURCHASE = "EMERGENCY_PURCHASE"
    MINIMUM_PURCHASE_OBLIGATION = (
        "MINIMUM_PURCHASE_OBLIGATION"
    )


# =========================================================
# 공통 Base Model
# =========================================================

class ApiModel(BaseModel):
    """
    프로젝트의 모든 API 모델이 공통으로 사용하는 설정.

    extra="forbid":
        정의하지 않은 필드가 들어오면 422 오류를 반환한다.

    str_strip_whitespace=True:
        문자열 앞뒤 공백을 자동 제거한다.
    """

    model_config = ConfigDict(
        extra="forbid",
        str_strip_whitespace=True,
    )


# =========================================================
# Spring → FastAPI 입력 모델
# =========================================================

class ErpMaterialContext(ApiModel):
    """
    Spring이 ERP DB에서 조회해 FastAPI에 전달하는 자재 정보.

    가용재고, 재고일수, 공급공백 등은 FastAPI가 계산하므로
    이 모델에는 가능한 한 원천 사실을 넣는다.
    """

    materialId: str = Field(
        min_length=1,
        description="ERP 자재 ID",
        examples=["MAT-CO-SULF"],
    )

    materialName: str = Field(
        min_length=1,
        description="자재명",
        examples=["Cobalt Sulfate"],
    )

    unit: str = Field(
        min_length=1,
        description="수량 기준 단위",
        examples=["KG"],
    )

    onHandQuantity: float = Field(
        ge=0,
        description="장부상 현재고",
        examples=[7280],
    )

    reservedQuantity: float = Field(
        ge=0,
        description="생산 또는 출고에 예약된 재고",
        examples=[429],
    )

    blockedQuantity: float = Field(
        ge=0,
        description="업무상 사용이 차단된 재고",
        examples=[195],
    )

    qualityHoldQuantity: float = Field(
        ge=0,
        description="품질검사로 사용이 보류된 재고",
        examples=[156],
    )

    averageDailyUsage: float | None = Field(
        default=None,
        ge=0,
        description=(
            "일평균 사용량. 누락되거나 0이면 "
            "재고일수를 계산할 수 없다."
        ),
        examples=[1000],
    )

    safetyStockQuantity: float | None = Field(
        default=None,
        ge=0,
        description="ERP 안전재고 수량",
        examples=[18000],
    )

    supplierDependencyRatio: float | None = Field(
        default=None,
        ge=0,
        le=1,
        description="주 공급사 의존도. 0~1 범위",
        examples=[0.84],
    )

    alternativeSupplierStatus: (
        AlternativeSupplierStatus | None
    ) = Field(
        default=None,
        description="대체 공급사 승인 상태",
        examples=["NONE"],
    )

    supplierStatus: SupplierStatus | None = Field(
        default=None,
        description="주 공급사 거래 상태",
        examples=["ACTIVE"],
    )

    primarySupplierId: str | None = Field(
        default=None,
        description="주 공급사 ID",
        examples=["SUP-COD-01"],
    )

    primaryContractId: str | None = Field(
        default=None,
        description="주 공급계약 ID",
        examples=["CTR-010"],
    )

    inventorySnapshotAt: datetime | None = Field(
        default=None,
        description="재고 데이터 기준 시각",
        examples=["2026-07-22T09:00:00+09:00"],
    )

class ErpPurchaseOrderContext(ApiModel):
    """Spring이 전달하는 미입고 발주 품목 정보."""

    purchaseOrderItemId: str = Field(
        min_length=1,
        description="발주 품목 ID",
        examples=["POI-0004"],
    )

    purchaseOrderId: str = Field(
        min_length=1,
        description="발주 ID",
        examples=["PO-0004"],
    )

    materialId: str = Field(
        min_length=1,
        description="발주 대상 자재 ID",
        examples=["MAT-CO-SULF"],
    )

    supplierId: str = Field(
        min_length=1,
        description="발주 공급사 ID",
        examples=["SUP-COD-01"],
    )

    contractId: str | None = Field(
        default=None,
        description="발주에 적용된 계약 ID",
        examples=["CTR-010"],
    )

    remainingQuantity: float = Field(
        ge=0,
        description="아직 입고되지 않은 잔여 발주 수량",
        examples=[15000],
    )

    orderStatus: PurchaseOrderStatus = Field(
        description="발주 상태",
        examples=["DELAYED"],
    )

    effectiveArrivalDate: date | None = Field(
        default=None,
        description=(
            "분석에 사용할 최종 ETA. 변경 ETA가 있으면 "
            "변경 ETA를 사용한다."
        ),
        examples=["2026-08-06"],
    )

    eligibleForEta: bool = Field(
        default=True,
        description="다음 입고일 계산에 사용할 수 있는 발주 여부",
    )

class ErpAlternativeSupplierContext(
    ApiModel
):
    """Spring이 전달하는 대체 공급사 원본 정보."""

    supplierId: str = Field(
        min_length=1,
        description="대체 공급사 ID",
        examples=["SUP-COD-02"],
    )

    contractId: str | None = Field(
        default=None,
        description="대체 공급계약 ID",
        examples=["CTR-011"],
    )

    supplierStatus: SupplierStatus = Field(
        description="대체 공급사의 거래 상태",
        examples=["ACTIVE"],
    )

    availableCapacityQuantity: (
        float | None
    ) = Field(
        default=None,
        ge=0,
        description=(
            "현재 추가 공급 가능한 수량. "
            "아직 확인되지 않았으면 null"
        ),
        examples=[7000],
    )

    leadTimeDays: int | None = Field(
        default=None,
        ge=0,
        description="대체 공급 리드타임",
        examples=[25],
    )

    qualificationStatus: (
        SupplierQualificationStatus
    ) = Field(
        description="해당 자재의 공급 승인 상태",
        examples=["CONDITIONAL"],
    )

class ErpExposureRequest(ApiModel):
    """
    ERP Exposure Agent 실행 요청.

    Spring이 /api/v1/internal/erp/exposure 또는
    /api/v1/briefings 호출 시 전달한다.
    """

    requestId: str = Field(
        min_length=1,
        examples=["ERP-REQ-004"],
    )

    eventId: str = Field(
        min_length=1,
        examples=["EVT-20260722-004"],
    )

    asOf: datetime = Field(
        description="ERP 분석 기준 시각",
        examples=["2026-07-22T09:00:00+09:00"],
    )

    impactDomain: ImpactDomain = Field(
        description="XGBoost Impact Domain 분류 결과",
        examples=["LOGISTICS"],
    )

    externalSignalScore: float = Field(
        ge=0,
        le=100,
        description="외부 신호 위험점수",
        examples=[82],
    )

    externalSignalLevel: ExternalSignalLevel = Field(
        description="외부 신호 위험등급",
        examples=["CRITICAL"],
    )

    affectedMaterialId: str | None = Field(
        default=None,
        min_length=1,
        description="뉴스 사건의 영향 자재 ID",
        examples=["MAT-CO-SULF"],
    )

    affectedSupplierId: str | None = Field(
        default=None,
        description="뉴스 사건의 영향 공급사 ID",
        examples=["SUP-COD-01"],
    )

    affectedCountryCode: str | None = Field(
        default=None,
        min_length=2,
        max_length=2,
        description="영향 국가 ISO 2자리 코드",
        examples=["CD"],
    )

    primaryContractId: str | None = Field(
        default=None,
        description="우선 검토할 계약 ID",
        examples=["CTR-010"],
    )

    eventSummary: str = Field(
        min_length=1,
        description="뉴스 사건 요약",
        examples=[
            "코발트 공급사의 선적 지연이 발생했습니다."
        ],
    )

    erpAnalysisRequired: bool = Field(
        default=True,
        description="ERP 분석 실행 여부",
    )

    materialContext: ErpMaterialContext | None = Field(
        default=None,
        description=(
            "Spring이 조회한 ERP 자재 Context. "
            "ERP 분석이 필요한 경우 필수이며, "
            "OTHER_IRRELEVANT이면 없을 수 있다."
        ),
    )

    purchaseOrders: list[ErpPurchaseOrderContext] = Field(
        default_factory=list,
        description="분석 대상 자재와 관련된 미입고 발주",
    )

    requiredQuantity: float | None = Field(
        default=None,
        ge=0,
        description=(
            "대체 공급사에게 필요한 조달 수량. "
            "공급사별 capacity 충족률 계산에 사용한다."
        ),
        examples=[8000],
    )

    alternativeSuppliers: list[ErpAlternativeSupplierContext] = Field(
        default_factory=list,
        description="분석 대상 자재의 대체 공급사 목록",
    )

    @field_validator("asOf")
    @classmethod
    def validateAsOfTimezone(
        cls,
        value: datetime,
    ) -> datetime:
        """
        분석 기준 시각에는 반드시 UTC 또는 +09:00 등의
        시간대 정보가 포함되어야 한다.
        """

        if value.tzinfo is None:
            raise ValueError(
                "asOf에는 시간대 정보가 필요합니다."
            )

        return value

    @model_validator(mode="after")
    def validateErpAnalysisContext(
        self,
    ) -> "ErpExposureRequest":
        """
        ERP 분석 실행 여부와 ERP Context 사이의 관계를 검사한다.

        규칙:
        1. OTHER_IRRELEVANT이면 ERP 분석을 실행하지 않는다.
        2. ERP 분석이 필요하면 영향 자재 ID가 반드시 있어야 한다.
        3. ERP 분석이 필요하면 materialContext가 반드시 있어야 한다.
        """

        if self.impactDomain is ImpactDomain.OTHER_IRRELEVANT and self.erpAnalysisRequired:
            raise ValueError(
                "OTHER_IRRELEVANT 사건은 "
                "erpAnalysisRequired=false여야 합니다."
            )

        if self.erpAnalysisRequired:
            if self.affectedMaterialId is None:
                raise ValueError(
                    "ERP 분석이 필요한 경우 "
                    "affectedMaterialId가 필요합니다."
                )

            if self.materialContext is None:
                raise ValueError(
                    "ERP 분석이 필요한 경우 "
                    "materialContext가 필요합니다."
                )

            if self.materialContext.materialId != self.affectedMaterialId:
                raise ValueError(
                    "affectedMaterialId와 "
                    "materialContext.materialId가 "
                    "일치하지 않습니다."
                )

        return self


# =========================================================
# FastAPI 내부 계산 결과 모델
# =========================================================

class ErpExposureFacts(ApiModel):

    """ERP 계산 도구가 산출한 사실값."""

    availableQuantity: float | None = Field(
        default=None,
        ge=0,
        description=(
            "실제 사용 가능한 재고."
            "원본 ERP 데이터가 유효하지 않으면 None"
        ),
    )

    inventoryDays: float | None = Field(
        default=None,
        ge=0,
        description="현재 재고로 버틸 수 있는 일수",
    )

    safetyStockDays: float | None = Field(
        default=None,
        ge=0,
        description="안전재고 수량을 일수로 변환한 값",
    )

    nextEtaDays: int | None = Field(
        default=None,
        ge=0,
        description="다음 유효 입고까지 남은 일수",
    )

    etaOverdueDays: int | None = Field(
        default=None,
        ge=0,
        description=(
            "선택된 발주의 ETA 초과 일수. "
            "ETA가 지나지 않았으면 0"
    ),
)

    expectedSupplyGapDays: float | None = Field(
        default=None,
        ge=0,
        description="재고 소진 후 입고까지 예상되는 공백",
    )

    projectedSupplyGapDays: float | None = Field(
        default=None,
        ge=0,
        description=(
            "발주별 입고일과 잔여수량을 "
            "누적 반영한 공급 공백"
        ),
)

    supplierDependencyRatio: float | None = Field(
        default=None,
        ge=0,
        le=1,
        description="주 공급사 의존도",
    )

    selectedPurchaseOrderId: str | None = Field(
        default=None,
        description="다음 ETA 계산에 선택된 발주 ID",
    )

    selectedPurchaseOrderItemId: str | None = Field(
        default=None,
        description="다음 ETA 계산에 선택된 발주 품목 ID",
    )

    selectedPurchaseOrderOriginalStatus: (
        PurchaseOrderStatus | None
    ) = Field(
        default=None,
        description=(
            "Spring 또는 ERP에서 전달된 선택 발주의 원본 상태"
        ),
    )

    selectedPurchaseOrderStatus: (
        PurchaseOrderStatus | None
    ) = Field(
        default=None,
        description=(
            "ETA 경과 여부를 반영해 ERP Agent가 정규화한 발주 상태"
        )
    )

class ErpRiskComponents(ApiModel):
    """ERP Exposure Score를 구성하는 세부 위험점수."""

    gapRiskScore: float | None = Field(
        default=None,
        ge=0,
        le=100,
    )

    safetyStockRiskScore: float | None = Field(
        default=None,
        ge=0,
        le=100,
    )

    dependencyRiskScore: float | None = Field(
        default=None,
        ge=0,
        le=100,
    )

    purchaseOrderDelayRiskScore: float | None = Field(
        default=None,
        ge=0,
        le=100,
    )

    alternativeSupplierRiskScore: float | None = Field(
        default=None,
        ge=0,
        le=100,
    )

    # Contract Agent가 아직 안 돌았거나(최초 계산) 협상 라운드에서
    # 계약 근거를 못 찾은 경우 None — calculateErpExposureScore가 None이면
    # 나머지 5개 가중치만으로(재정규화해서) 계산한다.
    contractProtectionRiskScore: float | None = Field(
        default=None,
        ge=0,
        le=100,
    )

class ContractQuestion(ApiModel):
    """ERP Agent가 Contract Agent에 전달하는 질문."""

    questionCode: ContractQuestionCode

    contractId: str | None = None

    question: str = Field(
        min_length=1,
        description="Contract Agent가 확인할 자연어 질문",
    )

class CalculationEvidence(ApiModel):
    """Reviewer가 ERP 계산을 재검증할 수 있는 근거."""

    metric: str = Field(
        min_length=1,
        examples=["inventoryDays"],
    )

    value: Any | None = None

    formula: str = Field(
        min_length=1,
        examples=[
            "availableQuantity / averageDailyUsage"
        ],
    )

    inputs: dict[
        str,
        float | int | str | bool | None,
    ] = Field(default_factory=dict)

    sourceRecordIds: list[str] = Field(
        default_factory=list,
        description="계산에 사용된 ERP 레코드 ID",
    )

class ErpSupplierAssessment(ApiModel):
    """ERP Agent의 공급사별 위험 평가 결과."""

    supplierId: str = Field(
        min_length=1,
    )

    supplierRiskScore: float = Field(
        ge=0,
        le=100,
    )

    qualificationRiskScore: float = Field(
        ge=0,
        le=100,
    )

    supplierStatusRiskScore: float = Field(
        ge=0,
        le=100,
    )

    capacityRiskScore: float = Field(
        ge=0,
        le=100,
    )

    capacityStatus: SupplierCapacityStatus

    capacityCoverageRatio: float | None = Field(
        default=None,
        ge=0,
    )

    availableCapacityQuantity: float | None = Field(
        default=None,
        ge=0,
    )

    reasonCodes: list[str] = Field(
        default_factory=list,
    )

    manualReviewRequired: bool = False


# =========================================================
# FastAPI → Spring 응답 모델
# =========================================================

class ErpExposureResponse(ApiModel):
    """ERP Exposure Agent의 최종 구조화 응답."""

    requestId: str

    affectedMaterialIds: list[str] = Field(
        default_factory=list,
    )

    affectedSupplierIds: list[str] = Field(
        default_factory=list,
    )

    affectedContractIds: list[str] = Field(
        default_factory=list,
    )

    facts: ErpExposureFacts

    riskComponents: ErpRiskComponents

    erpExposureScore: float | None = Field(
        default=None,
        ge=0,
        le=100,
    )

    supplierAssessments: list[
        ErpSupplierAssessment
    ] = Field(
        default_factory=list,
        description="공급사별 ERP 위험 평가 결과",
    )

    exposureLevel: ExposureLevel

    forcedCritical: bool = False

    contractReviewRequired: bool = False

    questionsForContractAgent: list[
        ContractQuestion
    ] = Field(default_factory=list)

    calculationEvidence: list[
        CalculationEvidence
    ] = Field(default_factory=list)

    dataQualityStatus: DataQualityStatus

    manualReviewRequired: bool = False

    warnings: list[str] = Field(
        default_factory=list,
    )

    ruleVersion: str = Field(
        default="erp-exposure-v0.1",
    )