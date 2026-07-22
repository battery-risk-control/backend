from hashlib import sha256
from uuid import uuid4

import pytest

from app.core.config import Settings
from app.core.exceptions import RagFilterRequired, VectorStoreFailed
from app.services.embedding_service import MockEmbedding
from app.services.vector_store_service import ChromaVectorStore


def make_store() -> ChromaVectorStore:
    return ChromaVectorStore(
        MockEmbedding(dimension=64),
        Settings(
            embedding_dimension=64,
            chroma_mode="ephemeral",
            chroma_collection_prefix=f"test_contracts_{uuid4().hex[:8]}",
        ),
    )


def chunk(
    document_id: str,
    chunk_index: int,
    content: str,
    *,
    contract_id: int = 501,
    supplier_id: int = 11,
    material_id: int = 1,
) -> dict[str, object]:
    return {
        "document_id": document_id,
        "chunk_index": chunk_index,
        "page_number": 1,
        "content": content,
        "contract_id": contract_id,
        "supplier_id": supplier_id,
        "material_id": material_id,
        "document_type": "LTA",
        "content_hash": sha256(content.encode()).hexdigest(),
    }


def test_clean_upsert_removes_stale_chunks_and_keeps_embedding_metadata() -> None:
    store = make_store()
    store.upsert_chunks([
        chunk("DOC-1", 0, "리튬 가격 조정 조항"),
        chunk("DOC-1", 1, "기존 청크"),
    ])

    result = store.upsert_chunks([chunk("DOC-1", 0, "리튬 단가 조정 기준")])
    stored = store.get_document("DOC-1")

    assert result.chunk_count == 1
    assert result.embedding_type == "MOCK_TOKEN_HASH"
    assert result.embedding_version == "mock-v1"
    assert result.mock_embedding is True
    assert [item["id"] for item in stored] == ["DOC-1:0"]
    assert stored[0]["metadata"]["mock_embedding"] is True


def test_search_applies_metadata_filters_before_vector_query_and_returns_score() -> None:
    store = make_store()
    store.upsert_chunks([
        chunk("DOC-11", 0, "리튬 가격 조정 조항", contract_id=501, supplier_id=11),
        chunk("DOC-12", 0, "리튬 가격 조정 조항", contract_id=502, supplier_id=12),
        chunk(
            "DOC-M2", 0, "리튬 가격 조정 조항",
            contract_id=503, supplier_id=11, material_id=2,
        ),
    ])

    results = store.search(
        "리튬 단가 조정",
        contract_id=None,
        supplier_id=11,
        material_id=1,
        top_k=5,
    )

    assert [item.metadata["document_id"] for item in results] == ["DOC-11"]
    assert 0.0 <= results[0].similarity_score <= 1.0
    assert results[0].similarity_score != 0.92


def test_search_requires_contract_or_supplier_filter() -> None:
    store = make_store()
    with pytest.raises(RagFilterRequired):
        store.search("리튬", contract_id=None, supplier_id=None)


def test_all_nine_chunk_fields_are_required() -> None:
    store = make_store()
    invalid = chunk("DOC-1", 0, "리튬")
    del invalid["content_hash"]

    with pytest.raises(VectorStoreFailed) as exception:
        store.upsert_chunks([invalid])

    assert exception.value.code == "VECTOR_STORE_FAILED"
    assert "content_hash" in exception.value.message
