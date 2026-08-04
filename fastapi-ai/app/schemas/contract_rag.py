from pydantic import Field

from app.schemas.common import ApiModel


class ContractRagSearchRequest(ApiModel):
    """1계층 구매팀 계약·RAG 화면의 조항 검색 요청.

    기존 `RagSearchRequest`와 달리 **필터가 전부 선택**이다 — 화면은 검색창에 단어만 넣고
    전체 계약을 훑는 흐름이라, contract_id 없이도 검색이 되어야 한다.
    (멀티에이전트가 쓰는 `/api/v1/rag/search`는 필터를 강제하는 기존 규칙을 그대로 둔다.)
    """

    query: str = Field(min_length=1, max_length=2000)
    contract_id: int | None = Field(default=None, gt=0)
    supplier_id: int | None = Field(default=None, gt=0)
    material_id: int | None = Field(default=None, gt=0)
    top_k: int = Field(default=5, ge=1, le=50)


class ContractRagSearchItem(ApiModel):
    document_id: str
    contract_id: int
    document_type: str
    chunk_index: int
    page_number: int
    content: str
    content_hash: str
    similarity_score: float
    embedding_type: str
    embedding_version: str
    mock_embedding: bool
    supplier_id: int | None = None
    material_id: int | None = None


class ContractRagSearchResult(ApiModel):
    results: list[ContractRagSearchItem]
    #: 전체 계약을 훑었는지("all") 특정 계약으로 좁혔는지("filtered"). 화면 헤더 문구에 쓴다.
    scope: str
    #: 검색에 쓰인 임베딩이 mock인지. true면 유사도 점수에 의미가 없다.
    mock: bool
    embedding_type: str
    embedding_version: str
    collection_name: str
