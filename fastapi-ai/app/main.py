from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.v1.analyze import router as analyze_router
from app.api.v1.rag import router as rag_router
from app.api.v1.internal import router as internal_router
from app.api.v1.documents import router as documents_router
from app.api.v1.supplier import router as supplier_router
from app.api.v1.realtime_pipeline import router as realtime_pipeline_router
from app.api.v1.market import router as market_router
from app.api.routes.erp import (
    erpRouter,
)
from app.schemas.common import ApiErrorResponse, ErrorBody
from app.core.config import get_settings
from app.core.exceptions import AppException
from app.api.dependencies import get_vector_store
from app.services.vector_store_service import ChromaVectorStore
from app.api.v1.multi_agent import (
    router as multi_agent_router,
)

settings = get_settings()

app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
)

app.include_router(analyze_router)
app.include_router(rag_router)
app.include_router(internal_router)

app.include_router(documents_router)
app.include_router(multi_agent_router)
app.include_router(erpRouter)
# [surin] F9 적격 공급사 추천 · F4 실시간 파이프라인 라우터
app.include_router(supplier_router)
app.include_router(realtime_pipeline_router)
# 원자재 가격 프록시 수집(yfinance). Spring 스케줄러가 호출해 결과를 DB에 저장한다.
app.include_router(market_router)



def error_response(status_code: int, code: str, message: str, details=None) -> JSONResponse:
    body = ApiErrorResponse(error=ErrorBody(code=code, message=message, details=details))
    return JSONResponse(status_code=status_code, content=body.model_dump(mode="json"))

def serialize_validation_errors(
    exception: RequestValidationError,
) -> list[dict]:
    """
    FastAPI validation 오류 안에 포함된
    ValueError 등의 객체를 JSON 문자열로 변환한다.
    """

    serialized_errors: list[dict] = []

    for validation_error in exception.errors():
        serialized_error = dict(
            validation_error
        )

        context = serialized_error.get("ctx")

        if isinstance(context, dict):
            serialized_error["ctx"] = {
                key: str(value)
                for key, value in context.items()
            }

        serialized_errors.append(
            serialized_error
        )

    return serialized_errors

@app.exception_handler(HTTPException)
async def http_exception_handler(_request: Request, exception: HTTPException) -> JSONResponse:
    code = "UNPROCESSABLE_ENTITY" if exception.status_code == 422 else "HTTP_ERROR"
    return error_response(exception.status_code, code, str(exception.detail))


@app.exception_handler(AppException)
async def app_exception_handler(_request: Request, exception: AppException) -> JSONResponse:
    return error_response(exception.status_code, exception.code, exception.message, exception.details)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(_request: Request, exception: RequestValidationError) -> JSONResponse:
    details = serialize_validation_errors(exception)

    return error_response(422, "VALIDATION_ERROR", "요청 스키마 검증에 실패했습니다.", details,)


@app.exception_handler(Exception)
async def unexpected_exception_handler(_request: Request, exception: Exception) -> JSONResponse:
    return error_response(500, "INTERNAL_SERVER_ERROR", "예상하지 못한 서버 오류가 발생했습니다.")


@app.get("/health")
def health(
    vector_store: ChromaVectorStore = Depends(get_vector_store),
) -> dict[str, object]:
    return {"status": "UP", "vector_store": vector_store.health_check()}
