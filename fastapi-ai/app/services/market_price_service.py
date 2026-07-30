"""원자재 가격 프록시 수집(yfinance). Spring이 호출해 결과를 DB에 저장한다.

배터리 핵심광물은 공개 현물 시세 API가 없거나 유료라, 학습 데이터 수집 단계부터 대표
채굴/생산 기업의 주가를 대리 지표로 써왔다. 이 모듈은 그 매핑을 그대로 이어받는다
(data/yfinance/collect_yfinance_data.py의 MATERIAL_TICKER_MAP).

비용: yfinance는 API 키가 필요 없는 공개 데이터라 호출 비용이 0이다.
"""
from __future__ import annotations

import logging
from datetime import date, timedelta

import pandas as pd

from app.schemas.market import MarketPricePoint, MarketPriceResult

logger = logging.getLogger(__name__)

# 자재 대분류 → 대표 종목. 키는 DB material_category / FastAPI MaterialCategory와 같은 값이다.
# RARE_EARTH는 단일 대표 종목을 특정하기 어려워(희토류 전업 상장사가 시장별로 제각각) 제외한다.
MATERIAL_TICKERS: dict[str, str] = {
    "LITHIUM": "ALB",       # Albemarle (NYSE)
    "NICKEL": "BHP",        # BHP Group (ADR)
    "COBALT": "GLNCY",      # Glencore (ADR)
    "MANGANESE": "S32.AX",  # South32 (ASX) — 문서상 'S32'는 미조회, .AX 접미사 필요
    "ALUMINUM": "AA",       # Alcoa (NYSE)
    "COPPER": "FCX",        # Freeport-McMoRan (NYSE)
    "GRAPHITE": "SYR.AX",   # Syrah Resources (ASX) — 마찬가지로 .AX 필요
}

VOLATILITY_WINDOW = 20  # 20거래일 — yfinance CSV의 stock_vol_20d와 같은 정의를 유지한다


def fetch_prices(days: int) -> MarketPriceResult:
    """종목별 일봉을 받아 (자재, 날짜) 단위 종가·변동성 목록으로 평탄화한다.

    한 종목이 실패해도 나머지는 계속 처리하고 failed_tickers로 보고한다 — 티커 하나가
    막혔다고 전체 가격 갱신이 멈추면 화면이 통째로 낡은 값으로 굳는다.
    """
    import yfinance as yf

    start = date.today() - timedelta(days=days)
    points: list[MarketPricePoint] = []
    failed: list[str] = []

    for material, ticker in MATERIAL_TICKERS.items():
        try:
            history = yf.Ticker(ticker).history(start=start.isoformat(), interval="1d", auto_adjust=True)
        except Exception as exception:  # noqa: BLE001 — 네트워크/파싱 등 어떤 실패든 종목 단위로 격리
            logger.warning("가격 조회 실패 (%s/%s): %s", material, ticker, exception)
            failed.append(ticker)
            continue

        if history is None or history.empty or "Close" not in history.columns:
            logger.warning("가격 데이터 없음 (%s/%s)", material, ticker)
            failed.append(ticker)
            continue

        closes = history["Close"].dropna()
        # 일간 수익률의 20일 이동 표준편차. 앞 19개는 NaN이라 그대로 None으로 내보낸다.
        volatility = closes.pct_change().rolling(VOLATILITY_WINDOW).std()

        for timestamp, close in closes.items():
            vol = volatility.get(timestamp)
            points.append(MarketPricePoint(
                material_category=material,
                ticker=ticker,
                # 거래소 현지 tz-aware 인덱스라 날짜만 취해 거래일로 쓴다(CSV의 Date_local과 동일 취지).
                price_date=timestamp.date().isoformat(),
                close_price=float(close),
                stock_vol_20d=None if vol is None or pd.isna(vol) else float(vol),
            ))

    logger.info("가격 수집 완료: %d건, 실패 종목 %s", len(points), failed or "없음")
    return MarketPriceResult(points=points, failed_tickers=failed, mock=False)
