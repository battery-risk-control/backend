import os
from typing import List, Dict, Any, Optional

from langchain_core.documents import Document
from langchain_chroma import Chroma


# 7) Vector Store 전용 예외 클래스
class VectorStoreError(Exception):
    """Vector Store 연동 및 처리 중 발생하는 예외"""
    pass

class RagService:
    def __init__(self, embedding_function, persist_directory: str = "./contract_db", collection_name: str = "default_collection"):
        """
        FastAPI 연동을 위한 RagService 초기화
        - 외부 Provider에서 주입받은 임베딩 객체 사용
        - 2) Mock/OpenAI Collection 분리 지원
        """
        self.persist_directory = persist_directory
        self.embeddings = embedding_function
        self.collection_name = collection_name
        
        try:
            self.vector_db = Chroma(
                collection_name=self.collection_name,
                embedding_function=self.embeddings,
                persist_directory=self.persist_directory
            )
        except Exception as e:
            raise VectorStoreError(f"ChromaDB 초기화 실패: {str(e)}")

    def health_check(self) -> Dict[str, Any]:
        """6) Health Check: DB 연결 및 컬렉션 상태 확인"""
        try:
            count = self.vector_db._collection.count()
            return {"status": "healthy", "collection": self.collection_name, "document_count": count}
        except Exception as e:
            raise VectorStoreError(f"Health Check 실패: {str(e)}")

    def _validate_chunk(self, chunk: Dict[str, Any]) -> None:
        """8) 필수 청크 필드 검증"""
        required_fields = ["document_id", "chunk_index", "content"]
        for field in required_fields:
            if field not in chunk:
                raise VectorStoreError(f"필수 필드 누락: '{field}'가 없습니다.")

    def delete_document(self, document_id: str) -> int:
        """6) 특정 document_id에 해당하는 모든 청크 삭제"""
        try:
            # 해당 document_id를 가진 데이터 조회
            existing = self.vector_db.get(where={"document_id": document_id})
            ids_to_delete = existing.get("ids", [])
            
            if ids_to_delete:
                self.vector_db.delete(ids=ids_to_delete)
            return len(ids_to_delete)
        except Exception as e:
            raise VectorStoreError(f"문서 삭제 실패 ({document_id}): {str(e)}")

    def get_document(self, document_id: str) -> List[Dict[str, Any]]:
        """6) 특정 document_id에 해당하는 모든 청크 조회"""
        try:
            result = self.vector_db.get(where={"document_id": document_id})
            docs = []
            for i in range(len(result["ids"])):
                docs.append({
                    "id": result["ids"][i],
                    "content": result["documents"][i],
                    "metadata": result["metadatas"][i]
                })
            return docs
        except Exception as e:
            raise VectorStoreError(f"문서 조회 실패 ({document_id}): {str(e)}")

    def add_chunks(self, chunk_list: List[Dict[str, Any]], embedding_type: str = "unknown", mock_embedding: bool = False, embedding_version: str = "1.0") -> str:
        """
        전달받은 청크 딕셔너리 리스트를 ChromaDB에 저장합니다.
        - 5) 재적재 전 같은 document_id의 기존 청크 전체 삭제 (Clean Upsert)
        - 1) embedding_type, embedding_version, mock_embedding Metadata 추가
        """
        try:
            # 1단계: 유입된 청크들의 고유 document_id 수집
            unique_doc_ids = set()
            for chunk in chunk_list:
                self._validate_chunk(chunk)
                unique_doc_ids.add(chunk["document_id"])
                
            # 2단계: 기존 데이터 깔끔하게 삭제 (Req 5)
            for doc_id in unique_doc_ids:
                deleted_count = self.delete_document(doc_id)
                if deleted_count > 0:
                    print(f"[RagService] 문서 덮어쓰기 위해 기존 청크 {deleted_count}개 삭제 (ID: {doc_id})")

            # 3단계: 새 데이터 삽입 준비
            documents = []
            ids = []
            
            for chunk in chunk_list:
                doc_id = f"{chunk['document_id']}:{chunk['chunk_index']}"
                ids.append(doc_id)
                
                # 메타데이터 병합 및 필수 정보(Req 1) 주입
                metadata = {
                    "document_id": chunk.get("document_id"),
                    "chunk_index": chunk.get("chunk_index"),
                    "page_number": chunk.get("page_number", 1),
                    "contract_id": chunk.get("contract_id", ""),
                    "supplier_id": chunk.get("supplier_id", ""),
                    "material_id": chunk.get("material_id", ""),
                    "document_type": chunk.get("document_type", ""),
                    "content_hash": chunk.get("content_hash", ""),
                    # 추가 메타데이터
                    "embedding_type": embedding_type,
                    "embedding_version": embedding_version,
                    "mock_embedding": mock_embedding
                }
                
                # None 값 빈 문자열 처리
                for k, v in metadata.items():
                    if v is None:
                        metadata[k] = ""
                        
                content = chunk["content"]
                documents.append(Document(page_content=content, metadata=metadata))
            
            # 4단계: ChromaDB 저장
            self.vector_db.add_documents(documents=documents, ids=ids)
            return "SUCCESS"
            
        except VectorStoreError:
            raise
        except Exception as e:
            # 7) "FAILED" 반환 대신 Exception Throw
            raise VectorStoreError(f"청크 저장 중 알 수 없는 오류 발생: {str(e)}")

    def search(self, query: str, k: int = 5, filters: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        """
        3, 4) Metadata Filter 검색 지원 및 similarity_score 반환
        """
        try:
            # ChromaDB는 거리가 가까울수록(낮을수록) 유사도가 높음
            results = self.vector_db.similarity_search_with_score(query, k=k, filter=filters)
            
            formatted_results = []
            for doc, score in results:
                formatted_results.append({
                    "content": doc.page_content,
                    "metadata": doc.metadata,
                    "similarity_score": float(score)  # score 반환 (Chroma의 L2 거리)
                })
            return formatted_results
        except Exception as e:
            raise VectorStoreError(f"검색 중 오류 발생: {str(e)}")

# =========================================================================
# 이하 테스트/사용 예시 (모듈 임포트 시에는 무시됨)
# =========================================================================
if __name__ == "__main__":
    # FastAPI 통합 환경을 가정한 테스트 로직
    from langchain_core.embeddings import FakeEmbeddings
    
    # 민지님의 Provider라고 가정한 FakeEmbeddings
    mock_provider = FakeEmbeddings(size=1536)
    
    # 2) 컬렉션 분리: 'mock_collection' 으로 생성
    service = RagService(embedding_function=mock_provider, collection_name="mock_collection")
    
    print("\n--- Health Check ---")
    print(service.health_check())
    
    dummy_data = [
        {
            "document_id": "LTA_2025_SK_01",
            "chunk_index": 0,
            "content": "공급 지연 위약금은 일일 0.5%로 산정한다.",
            "contract_id": "CNT_1001",
            "supplier_id": "SUP_SK",
            "material_id": "MAT_LITHIUM"
        }
    ]
    
    print("\n--- 데이터 삽입 (추가 메타데이터 주입) ---")
    service.add_chunks(
        chunk_list=dummy_data,
        embedding_type="FakeDeterministic",
        mock_embedding=True,
        embedding_version="1.0"
    )
    
    print("\n--- 기존 데이터 삭제 후 재적재(Upsert) 테스트 ---")
    service.add_chunks(chunk_list=dummy_data, embedding_type="FakeDeterministic", mock_embedding=True)
    
    print("\n--- 메타데이터 필터 검색 (contract_id = 'CNT_1001') ---")
    filter_dict = {"contract_id": "CNT_1001"}
    search_res = service.search("위약금은 얼마인가요?", k=1, filters=filter_dict)
    
    for res in search_res:
        print(f"점수(L2): {res['similarity_score']:.4f}")
        print(f"본문: {res['content']}")
        print(f"메타데이터: {res['metadata']}")
