import os
from typing import List, Dict, Any
from dotenv import load_dotenv

# 환경변수 로드 (.env 파일이 있으면 읽어옵니다)
load_dotenv()

from langchain_core.documents import Document
from langchain_openai import OpenAIEmbeddings
from langchain_core.embeddings import FakeEmbeddings
from langchain_chroma import Chroma

class ChromaDBManager:
    def __init__(self, embedding_function, persist_directory: str = "./contract_db"):
        """
        외부에서 전달받은 임베딩 객체(embedding_function)를 사용하여 ChromaDB를 초기화합니다.
        (Dependency Injection)
        """
        self.persist_directory = persist_directory
        self.embeddings = embedding_function
        
        # ChromaDB 클라이언트 세팅
        self.vector_db = Chroma(
            embedding_function=self.embeddings,
            persist_directory=self.persist_directory
        )

    def add_chunks(self, chunk_list: List[Dict[str, Any]]) -> str:
        """
        FastAPI에서 전달받은 청크 딕셔너리 리스트를 ChromaDB에 저장하거나 갱신(Upsert)합니다.
        """
        try:
            documents = []
            ids = []
            
            for chunk in chunk_list:
                # 1. 고유 ID 생성 (document_id:chunk_index)
                doc_id = f"{chunk.get('document_id')}:{chunk.get('chunk_index')}"
                ids.append(doc_id)
                
                # 2. 메타데이터 매핑 (본문 제외 모든 정보)
                metadata = {
                    "document_id": chunk.get("document_id"),
                    "chunk_index": chunk.get("chunk_index"),
                    "page_number": chunk.get("page_number", 1),
                    "contract_id": chunk.get("contract_id", ""),
                    "supplier_id": chunk.get("supplier_id", ""),
                    "material_id": chunk.get("material_id", ""),
                    "document_type": chunk.get("document_type", ""),
                    "content_hash": chunk.get("content_hash", "")
                }
                
                # None 값이 들어오면 ChromaDB가 거부할 수 있으므로 빈 문자열로 치환
                for k, v in metadata.items():
                    if v is None:
                        metadata[k] = ""
                
                # 3. 텍스트 본문 추출
                content = chunk.get("content", "")
                
                # Document 객체 생성
                doc = Document(page_content=content, metadata=metadata)
                documents.append(doc)
            
            # ChromaDB 저장 및 갱신 (이미 존재하는 ID는 덮어씁니다: Upsert)
            self.vector_db.add_documents(documents=documents, ids=ids)
            
            print(f"[ChromaDBManager] {len(documents)}개의 청크가 성공적으로 저장(갱신)되었습니다.")
            return "SUCCESS"
            
        except Exception as e:
            # OpenAI 통신 에러 등 모든 장애 상황 방어
            print(f"[ChromaDBManager] 장애 발생: {str(e)}")
            return "FAILED"


def get_embedding_function():
    """
    환경변수 EMBEDDING_PROVIDER 설정(openai 또는 mock)에 따라 임베딩 객체를 반환합니다.
    팀원이 구현할 6단계 Mock Embedding 팩토리 역할을 합니다.
    """
    provider = os.environ.get("EMBEDDING_PROVIDER", "mock").lower()
    
    if provider == "openai":
        api_key = os.environ.get("OPENAI_API_KEY")
        if api_key and not api_key.startswith("sk-your-real"):
            print("[EmbeddingFactory] OpenAI 임베딩 객체가 생성되었습니다.")
            return OpenAIEmbeddings(model="text-embedding-3-small")
        else:
            print("[EmbeddingFactory] 경고: OPENAI_API_KEY가 유효하지 않아 Mock으로 폴백합니다.")
            
    print("[EmbeddingFactory] Mock 임베딩 객체가 생성되었습니다.")
    return FakeEmbeddings(size=1536)


# 테스트용 더미 실행부 (팀원이 이렇게 호출하게 됩니다)
if __name__ == "__main__":
    # 팀원이 약속한 규격의 가짜(Dummy) 데이터
    dummy_chunks = [
        {
            "document_id": "DOC_SPRING_001",
            "chunk_index": 0,
            "page_number": 1,
            "content": "공급사는 약정된 납기일을 준수해야 하며, 지연 시 0.5% 배상.",
            "contract_id": 1,
            "supplier_id": 2,
            "material_id": 3,
            "document_type": "LTA",
            "content_hash": "abcd1234hash"
        },
        {
            "document_id": "DOC_SPRING_001",
            "chunk_index": 1,
            "page_number": 1,
            "content": "불가항력 발생 시 공급사는 지연 배상금을 면제받는다.",
            "contract_id": 1,
            "supplier_id": 2,
            "material_id": 3,
            "document_type": "LTA",
            "content_hash": "abcd1234hash"
        }
    ]
    
    print("\n================ [ 1차 삽입 테스트 ] ================")
    # 1. 외부에서 임베딩 객체를 생성하여 주입 (Dependency Injection)
    embed_func = get_embedding_function()
    manager = ChromaDBManager(embedding_function=embed_func)
    
    result = manager.add_chunks(dummy_chunks)
    print(f"반환값: {result}")
    
    print("\n================ [ 2차 중복(Upsert) 테스트 ] ================")
    result_dup = manager.add_chunks(dummy_chunks)
    print(f"반환값: {result_dup}")
    
    print("\n================ [ 3차 데이터 검증(검색) 테스트 ] ================")
    # 3. DB에 진짜 데이터와 메타데이터가 잘 들어갔는지 검색(Query)으로 확인해보기
    print("질문: '지연 시 배상 비율이 어떻게 되나요?'")
    
    # 유사도 기반 검색 수행 (가짜 임베딩이라 정확도는 무작위지만 동작 여부 확인)
    search_results = manager.vector_db.similarity_search("지연 시 배상 비율이 어떻게 되나요?", k=1)
    
    if search_results:
        best_match = search_results[0]
        print("\n[가장 유사한 청크 찾기 성공!]")
        print(f"-> 추출된 본문: {best_match.page_content}")
        print("-> 저장된 메타데이터(Metadata):")
        for key, value in best_match.metadata.items():
            print(f"   - {key}: {value}")
    else:
        print("결과를 찾을 수 없습니다.")