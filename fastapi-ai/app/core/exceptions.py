class AppException(Exception):
    def __init__(self, code: str, message: str, status_code: int = 500, details=None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.details = details


class UnsupportedDocumentType(AppException):
    def __init__(self, file_name: str) -> None:
        super().__init__("UNSUPPORTED_DOCUMENT_TYPE", f"지원하지 않는 문서 형식입니다: {file_name}", 422)


class RagFilterRequired(AppException):
    def __init__(self) -> None:
        super().__init__("RAG_FILTER_REQUIRED", "contract_id 또는 supplier_id가 필요합니다.", 422)


class ModelUnavailable(AppException):
    def __init__(self) -> None:
        super().__init__("MODEL_UNAVAILABLE", "모델을 사용할 수 없습니다.", 503)
