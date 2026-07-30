from pydantic import BaseModel, Field


class MarketPriceRequest(BaseModel):
    """Spring MarketPriceService가 주기적으로 호출한다.

    days는 "오늘부터 며칠 전까지"를 뜻한다. Spring은 매번 넉넉한 구간을 요청하고 upsert로
    수렴시키므로(스케줄러가 며칠 쉬어도 빈 구간이 메워진다), 증분 계산은 여기서 하지 않는다.
    """

    days: int = Field(default=180, ge=1, le=1825, description="오늘 기준 조회 일수")


class MarketPricePoint(BaseModel):
    material_category: str = Field(description="자재 대분류(LITHIUM 등). DB material_category와 동일")
    ticker: str
    price_date: str = Field(description="거래일 ISO(YYYY-MM-DD)")
    close_price: float
    stock_vol_20d: float | None = Field(
        default=None, description="20일 종가 수익률 표준편차. 데이터가 20일 미만인 앞부분은 null")


class MarketPriceResult(BaseModel):
    points: list[MarketPricePoint]
    # 티커별 조회 실패는 전체 실패로 만들지 않고 여기에 모아 보고한다 — 한 종목이 상장폐지·
    # 티커 변경 등으로 막혀도 나머지 자재의 가격 갱신은 계속되어야 하기 때문.
    failed_tickers: list[str] = Field(default_factory=list)
    mock: bool = False
