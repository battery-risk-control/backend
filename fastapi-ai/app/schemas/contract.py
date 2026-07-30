from datetime import datetime

from pydantic import (
    Field,
    field_validator,
    model_validator,
)

from app.schemas.erp import (
    ApiModel,
    ContractQuestionCode,
    ExposureLevel,
)


class ContractAgentQuestion(ApiModel):
    """
    Contract Agent가 계약 문서에서 확인할 질문 하나.
    """

    questionId: str = Field(
        min_length=1,
        examples=[
            "ERP-REQ-004-Q01",
        ],
    )

    questionCode: ContractQuestionCode = Field(
        description="계약 검토 질문 유형",
        examples=[
            "DELIVERY_PENALTY",
        ],
    )

    contractId: str = Field(
        min_length=1,
        description="검색할 계약 ID",
        examples=[
            "CTR-010",
        ],
    )

    question: str = Field(
        min_length=1,
        description="Contract Agent가 확인할 질문",
        examples=[
            "납품 지연 시 위약금 조항이 있는가?",
        ],
    )

class ContractErpContext(ApiModel):
    """
    Contract Agent가 계약 조항을 해석할 때 참고하는 ERP 위험 정보.
    """

    erpExposureScore: float | None = Field(
        default=None,
        ge=0,
        le=100,
    )

    exposureLevel: ExposureLevel

    inventoryDays: float | None = Field(
        default=None,
        ge=0,
    )

    expectedSupplyGapDays: float | None = Field(
        default=None,
        ge=0,
    )

    projectedSupplyGapDays: float | None = Field(
        default=None,
        ge=0,
    )

    selectedPurchaseOrderId: str | None = None

    selectedPurchaseOrderItemId: str | None = None

class ContractAgentRequest(ApiModel):
    """
    ERP Exposure Agent가 Contract Agent에 전달하는 요청.
    """

    requestId: str = Field(
        min_length=1,
        description="Contract Agent 요청 ID",
        examples=[
            "CONTRACT-ERP-REQ-004",
        ],
    )

    eventId: str = Field(
        min_length=1,
        description="뉴스 사건 클러스터 ID",
        examples=[
            "EVT-20260722-004",
        ],
    )

    erpRequestId: str = Field(
        min_length=1,
        description="이 요청을 발생시킨 ERP 요청 ID",
        examples=[
            "ERP-REQ-004",
        ],
    )

    asOf: datetime = Field(
        description="계약 검토 기준시각",
    )

    affectedMaterialIds: list[str] = Field(
        default_factory=list,
    )

    affectedSupplierIds: list[str] = Field(
        default_factory=list,
    )

    contractIds: list[str] = Field(
        min_length=1,
        description="검색해야 하는 계약 ID 목록",
    )

    questions: list[
        ContractAgentQuestion
    ] = Field(
        min_length=1,
        description="계약서에서 확인할 질문 목록",
    )

    erpContext: ContractErpContext

    @field_validator("asOf")
    @classmethod
    def validateAsOfTimezone(
        cls,
        value: datetime,
    ) -> datetime:
        """
        계약 검토 기준시각에는 반드시
        타임존 정보가 있어야 한다.
        """

        if value.tzinfo is None:
            raise ValueError(
                "CONTRACT_AS_OF_TIMEZONE_MISSING"
            )

        return value

    @model_validator(mode="after")
    def validateContractReferences(
        self,
    ) -> "ContractAgentRequest":
        """
        계약 ID와 질문의 참조 관계를 검증한다.
        """

        if len(self.contractIds) != len(
            set(self.contractIds)
        ):
            raise ValueError(
                "DUPLICATE_CONTRACT_ID"
            )

        questionIds = [
            question.questionId
            for question in self.questions
        ]

        if len(questionIds) != len(
            set(questionIds)
        ):
            raise ValueError(
                "DUPLICATE_CONTRACT_QUESTION_ID"
            )

        registeredContractIds = set(
            self.contractIds
        )

        for question in self.questions:
            if (
                question.contractId
                not in registeredContractIds
            ):
                raise ValueError(
                    "QUESTION_CONTRACT_NOT_REGISTERED: "
                    f"{question.contractId}"
                )

        return self