from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api.v1.analyze import router as analyze_router
from app.api.v1.rag import router as rag_router
from app.api.v1.internal import router as internal_router
from app.schemas.common import ApiErrorResponse, ErrorBody
from app.core.config import get_settings
from app.core.exceptions import AppException

settings = get_settings()

app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
)

app.include_router(analyze_router)
app.include_router(rag_router)
app.include_router(internal_router)


def error_response(status_code: int, code: str, message: str, details=None) -> JSONResponse:
    body = ApiErrorResponse(error=ErrorBody(code=code, message=message, details=details))
    return JSONResponse(status_code=status_code, content=body.model_dump(mode="json", by_alias=True))


@app.exception_handler(HTTPException)
async def http_exception_handler(_request: Request, exception: HTTPException) -> JSONResponse:
    code = "UNPROCESSABLE_ENTITY" if exception.status_code == 422 else "HTTP_ERROR"
    return error_response(exception.status_code, code, str(exception.detail))


@app.exception_handler(AppException)
async def app_exception_handler(_request: Request, exception: AppException) -> JSONResponse:
    return error_response(exception.status_code, exception.code, exception.message, exception.details)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(_request: Request, exception: RequestValidationError) -> JSONResponse:
    return error_response(422, "VALIDATION_ERROR", "요청 스키마 검증에 실패했습니다.", exception.errors())


@app.exception_handler(Exception)
async def unexpected_exception_handler(_request: Request, exception: Exception) -> JSONResponse:
    return error_response(500, "INTERNAL_SERVER_ERROR", "예상하지 못한 서버 오류가 발생했습니다.")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
