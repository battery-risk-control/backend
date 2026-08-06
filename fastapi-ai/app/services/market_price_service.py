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
# MaterialCategory(extraction_inference.py) 8종과 1:1로 맞춘다 — 대시보드가 멀티에이전트 표준과
# 같은 자재 집합을 보여주도록(2026-08-06). RARE_EARTH는 희토류 전업 상장사가 시장별로 제각각이라
# 단일 대표 종목이 없어, 희토류·전략금속 ETF인 REMX(VanEck)를 프록시로 쓴다 — 다른 항목도 채굴/생산
# 기업 주가를 대리 지표로 쓰는 것과 같은 성격이다.
MATERIAL_TICKERS: dict[str, str] = {
    "LITHIUM": "ALB",       # Albemarle (NYSE)
    "NICKEL": "BHP",        # BHP Group (ADR)
    "COBALT": "GLNCY",      # Glencore (ADR)
    "MANGANESE": "S32.AX",  # South32 (ASX) — 문서상 'S32'는 미조회, .AX 접미사 필요
    "ALUMINUM": "AA",       # Alcoa (NYSE)
    "COPPER": "FCX",        # Freeport-McMoRan (NYSE)
    "GRAPHITE": "SYR.AX",   # Syrah Resources (ASX) — 마찬가지로 .AX 필요
    "RARE_EARTH": "REMX",   # VanEck Rare Earth/Strategic Metals ETF — 희토류 프록시(단일 상장사 부재)
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


def fetch_intraday() -> MarketPriceResult:
    """최근 1거래일의 시간별(1시간 봉) 시세. 공개 대시보드 "1일" 탭 장중 흐름용이다.

    일봉(fetch_prices)과 달리 저장하지 않고 조회 때마다 받는다 — 시간별 데이터는 "1일" 탭에서만
    쓰고 양도 적어, DB 스키마(자재·거래일 복합키)를 시간 단위로 넓히는 대신 실시간 조회로 둔다.

    price_date에는 날짜가 아니라 tz-aware ISO 타임스탬프(예: 2026-08-07T09:00:00-04:00)를 담는다.
    거래소가 미국·호주로 섞여 있어 Spring이 KST로 환산해 한 축에 정렬한다. 20일 변동성은 시간
    단위로 정의가 없어 항상 None으로 두고, 리스크 지수는 Spring이 저장된 일간 변동성으로 채운다.
    """
    import yfinance as yf

    points: list[MarketPricePoint] = []
    failed: list[str] = []

    for material, ticker in MATERIAL_TICKERS.items():
        try:
            history = yf.Ticker(ticker).history(period="1d", interval="60m", auto_adjust=True)
        except Exception as exception:  # noqa: BLE001 — 종목 단위로 격리(fetch_prices와 동일 방침)
            logger.warning("장중 가격 조회 실패 (%s/%s): %s", material, ticker, exception)
            failed.append(ticker)
            continue

        if history is None or history.empty or "Close" not in history.columns:
            logger.warning("장중 가격 데이터 없음 (%s/%s)", material, ticker)
            failed.append(ticker)
            continue

        closes = history["Close"].dropna()
        for timestamp, close in closes.items():
            points.append(MarketPricePoint(
                material_category=material,
                ticker=ticker,
                # 시간 포함 tz-aware ISO. 일봉의 날짜(YYYY-MM-DD)와 달리 시각까지 담는다.
                price_date=timestamp.isoformat(),
                close_price=float(close),
                stock_vol_20d=None,
            ))

    logger.info("장중 가격 수집 완료: %d건, 실패 종목 %s", len(points), failed or "없음")
    return MarketPriceResult(points=points, failed_tickers=failed, mock=False)
