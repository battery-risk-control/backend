from typing import Literal

from pydantic import Field

from app.schemas.common import ApiModel

#: 검색 범위 종류. ALL=매입·납품 전체, INBOUND=원자재 매입(공급사 계약),
#: OUTBOUND=제품 납품(고객사 계약). 인바운드 청크에는 supplier_id/material_id만,
#: 아웃바운드 청크에는 product_id/customer_id만 저장되므로 키 존재로 갈라낼 수 있다.
ContractKind = Literal["ALL", "INBOUND", "OUTBOUND"]


class ContractRagSearchRequest(ApiModel):
    """1계층 구매팀 계약·RAG 화면의 조항 검색 요청.

    기존 `RagSearchRequest`와 달리 **필터가 전부 선택**이다 — 화면은 검색창에 단어만 넣고
    전체 계약을 훑는 흐름이라, contract_id 없이도 검색이 되어야 한다.
    (멀티에이전트가 쓰는 `/api/v1/rag/search`는 필터를 강제하는 기존 규칙을 그대로 둔다.)

    `kind`로 검색 범위를 매입/납품으로 좁힐 수 있고, 특정 납품계약을 콕 집을 때는
    product_id+customer_id를 함께 넘긴다(아웃바운드는 contract_id가 인바운드와 번호가 겹쳐
    단독으로는 계약을 특정할 수 없다).
    """

    query: str = Field(min_length=1, max_length=2000)
    kind: ContractKind = "ALL"
    contract_id: int | None = Field(default=None, gt=0)
    supplier_id: int | None = Field(default=None, gt=0)
    material_id: int | None = Field(default=None, gt=0)
    product_id: int | None = Field(default=None, gt=0)
    customer_id: int | None = Field(default=None, gt=0)
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
    product_id: int | None = None
    customer_id: int | None = None


class ContractRagSearchResult(ApiModel):
    results: list[ContractRagSearchItem]
    #: 전체 계약을 훑었는지("all") 특정 계약으로 좁혔는지("filtered"). 화면 헤더 문구에 쓴다.
    scope: str
    #: 검색에 쓰인 임베딩이 mock인지. true면 유사도 점수에 의미가 없다.
    mock: bool
    embedding_type: str
    embedding_version: str
    collection_name: str
