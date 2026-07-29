from fastapi import APIRouter

from app.schemas.common import ApiResponse
from app.schemas.market import MarketPriceRequest, MarketPriceResult
from app.services.market_price_service import fetch_prices

router = APIRouter(prefix="/api/v1/internal/market", tags=["market"])


@router.post("/prices", response_model=ApiResponse[MarketPriceResult])
def prices_route(request: MarketPriceRequest) -> ApiResponse[MarketPriceResult]:
    """Spring MarketPriceService가 주기적으로 호출합니다.

    yfinance에서 자재별 대표 종목의 일봉을 받아 (자재, 거래일) 단위로 평탄화해 반환하고,
    DB 저장·지수 변환은 Spring이 처리합니다(FastAPI는 PostgreSQL에 접근하지 않습니다).
    """
    return ApiResponse(data=fetch_prices(request.days))
