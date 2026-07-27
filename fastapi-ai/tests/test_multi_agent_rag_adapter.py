from dataclasses import dataclass
from app.services.rag_service import RagService
from app.multi_agent.rag.service_adapter import (
    search_contract_evidence,
)


@dataclass
class FakeSearchResult:
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    page_number: int
    content: str
    similarity_score: float


class FakeRagService:
    def search(
        self,
        query: str,
        contract_id: int | None,
        supplier_id: int | None,
        top_k: int,
        material_id: int | None = None,
    ) -> list[FakeSearchResult]:
        return [
            FakeSearchResult(
                document_id="DOC-001",
                contract_id=10,
                supplier_id=20,
                material_id=30,
                page_number=3,
                content=(
                    "공급자는 납기 지연 발생 시 "
                    "변경 일정을 통보해야 한다."
                ),
                similarity_score=0.91,
            )
        ]


def test_converts_rag_result_to_contract_evidence():
    service = FakeRagService()

    result = search_contract_evidence(
        service=service,
        query="납기 지연 조항",
        contract_id=10,
        supplier_id=20,
        material_id=30,
        top_k=5,
    )

    assert result == [
        {
            "document_id": "DOC-001",
            "contract_id": 10,
            "supplier_id": 20,
            "material_id": 30,
            "page": 3,
            "evidence_text": (
                "공급자는 납기 지연 발생 시 "
                "변경 일정을 통보해야 한다."
            ),
            "similarity_score": 0.91,
            "source_type": "chroma",
        }
    ]

def test_adapter_uses_real_minji_rag_service():
    service = RagService()

    service.upload(
        content=(
            "공급자는 납기 지연 발생 시 변경 일정을 "
            "구매자에게 통보해야 한다."
        ).encode("utf-8"),
        file_name="contract.txt",
        contract_id=10,
        supplier_id=20,
        material_id=30,
        document_type="LTA",
    )

    result = search_contract_evidence(
        service=service,
        query="납기 지연",
        contract_id=10,
        supplier_id=20,
        material_id=30,
        top_k=5,
    )

    assert len(result) == 1
    assert result[0]["contract_id"] == 10
    assert result[0]["supplier_id"] == 20
    assert result[0]["material_id"] == 30
    assert result[0]["page"] == 1
    assert "납기 지연" in result[0]["evidence_text"]
    assert result[0]["source_type"] == "chroma"