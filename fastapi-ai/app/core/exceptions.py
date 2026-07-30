class AppException(Exception):
    def __init__(self, code: str, message: str, status_code: int = 500, details=None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.details = details


class UnsupportedDocumentType(AppException):
    def __init__(self, file_name: str) -> None:
        super().__init__(
            "UNSUPPORTED_DOCUMENT_TYPE",
            f"지원하지 않는 문서 형식입니다: {file_name}",
            422,
        )


class EmptyDocument(AppException):
    def __init__(self) -> None:
        super().__init__("EMPTY_DOCUMENT", "문서에서 처리할 텍스트를 찾을 수 없습니다.", 422)


class InvalidPdf(AppException):
    def __init__(self) -> None:
        super().__init__("INVALID_PDF", "올바른 PDF 파일이 아니거나 파일이 손상되었습니다.", 422)


class TextExtractionFailed(AppException):
    def __init__(self, message: str = "문서 텍스트 추출에 실패했습니다.") -> None:
        super().__init__("TEXT_EXTRACTION_FAILED", message, 422)


class ChunkingFailed(AppException):
    def __init__(self, message: str = "문서 청크 생성에 실패했습니다.") -> None:
        super().__init__("CHUNKING_FAILED", message, 500)


class RagFilterRequired(AppException):
    def __init__(self) -> None:
        super().__init__("RAG_FILTER_REQUIRED", "contract_id 또는 supplier_id가 필요합니다.", 422)


class DocumentIdentifierRequired(AppException):
    def __init__(self) -> None:
        super().__init__(
            "DOCUMENT_IDENTIFIER_REQUIRED",
            "supplier_id+material_id 또는 product_id+customer_id 중 하나는 필요합니다.",
            422,
        )


class ModelUnavailable(AppException):
    def __init__(self) -> None:
        super().__init__("MODEL_UNAVAILABLE", "모델을 사용할 수 없습니다.", 503)


class VectorStoreUnavailable(AppException):
    def __init__(self) -> None:
        super().__init__(
            "VECTOR_STORE_UNAVAILABLE",
            "ChromaDB에 연결할 수 없습니다.",
            503,
        )


class VectorStoreFailed(AppException):
    def __init__(self, message: str = "ChromaDB 처리에 실패했습니다.") -> None:
        super().__init__("VECTOR_STORE_FAILED", message, 500)


class VectorStoreDeleteFailed(AppException):
    def __init__(self) -> None:
        super().__init__(
            "VECTOR_STORE_DELETE_FAILED",
            "ChromaDB의 기존 문서 청크 삭제에 실패했습니다.",
            500,
        )
