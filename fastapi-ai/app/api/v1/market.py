from fastapi import APIRouter

from app.schemas.common import ApiResponse
from app.schemas.market import MarketPriceRequest, MarketPriceResult
from app.services.market_price_service import fetch_intraday, fetch_prices

router = APIRouter(prefix="/api/v1/internal/market", tags=["market"])


@router.post("/prices", response_model=ApiResponse[MarketPriceResult])
def prices_route(request: MarketPriceRequest) -> ApiResponse[MarketPriceResult]:
    """Spring MarketPriceService가 주기적으로 호출합니다.

    yfinance에서 자재별 대표 종목의 일봉을 받아 (자재, 거래일) 단위로 평탄화해 반환하고,
    DB 저장·지수 변환은 Spring이 처리합니다(FastAPI는 PostgreSQL에 접근하지 않습니다).
    """
    return ApiResponse(data=fetch_prices(request.days))


@router.post("/intraday", response_model=ApiResponse[MarketPriceResult])
def intraday_route() -> ApiResponse[MarketPriceResult]:
    """공개 대시보드 "1일" 탭 장중 흐름용. 최근 1거래일의 시간별(1시간 봉) 시세를 반환합니다.

    일봉과 달리 저장하지 않고 조회 때마다 받습니다(요청 본문 없음). price_date에 시각까지 담기며,
    거래소가 섞여 있어 KST 환산·정렬·지수화는 Spring이 처리합니다.
    """
    return ApiResponse(data=fetch_intraday())
