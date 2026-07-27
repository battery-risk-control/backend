from typing import Protocol


class RagSearchResultItem(Protocol):
    document_id: str
    contract_id: int
    supplier_id: int
    material_id: int
    page_number: int
    content: str
    similarity_score: float


class RagSearchService(Protocol):
    def search(
        self,
        query: str,
        contract_id: int | None,
        supplier_id: int | None,
        top_k: int,
        material_id: int | None = None,
    ) -> list[RagSearchResultItem]:
        ...


def search_contract_evidence(
    service: RagSearchService,
    query: str,
    contract_id: int | None = None,
    supplier_id: int | None = None,
    material_id: int | None = None,
    top_k: int = 5,
) -> list[dict]:
    results = service.search(
        query=query,
        contract_id=contract_id,
        supplier_id=supplier_id,
        material_id=material_id,
        top_k=top_k,
    )

    return [
        {
            "document_id": item.document_id,
            "contract_id": item.contract_id,
            "supplier_id": item.supplier_id,
            "material_id": item.material_id,
            "page": item.page_number,
            "evidence_text": item.content,
            "similarity_score": item.similarity_score,
            "source_type": "chroma",
        }
        for item in results
    ]