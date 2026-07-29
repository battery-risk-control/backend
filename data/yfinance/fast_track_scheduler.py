"""
패스트 트랙 yfinance 15분 주기 수집기

근거: 프로젝트 기획정의서_7.15 (4.1 A단계 - 패스트 트랙 15분~1시간 주기)
데이터 활용 및 모델 학습 기획정의서 v2 (1단계 표 - yfinance: 실시간 확인 시점마다)

주의:
- yfinance 데이터 자체가 15~20분 지연 데이터이므로, 15분 주기 폴링이면
    '지연 데이터를 지연 없이 최대한 자주 확인'하는 셈이 되어 설계 의도에 부합함.
- yfinance는 비공식 API 래퍼라 호출 제한이 명문화되어 있지 않음.
    과도한 동시 호출 시 429(rate limit) 에러가 날 수 있어 티커 간 짧은 delay를 둠.
    출처: https://github.com/ranaroussi/yfinance/issues/2128
"""

import time
import random
import logging
from datetime import datetime

import yfinance as yf
from apscheduler.schedulers.blocking import BlockingScheduler

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("fast_track")

# 패스트 트랙 대상 티커 (원자재 대표기업 + 교차검증용 + 환율)
FAST_TRACK_TICKERS = {
    "리튬_ALB": "ALB",
    "니켈_BHP": "BHP",
    "코발트_GLNCY": "GLNCY",
    "망간_S32": "S32.AX",
    "알루미늄_AA": "AA",
    "구리_FCX": "FCX",
    "흑연_SYR": "SYR.AX",
    "SQM": "SQM",
    "USD_KRW": "KRW=X",
}


def fetch_latest_snapshot():
    """각 티커의 최신 가격 스냅샷을 가져와 저장(현재는 로그 출력, 실제로는 DB insert로 교체)."""
    run_time = datetime.now().isoformat()
    logger.info(f"=== 패스트 트랙 수집 시작: {run_time} ===")

    for label, ticker in FAST_TRACK_TICKERS.items():
        try:
            # fast_info는 history()보다 가벼운 호출로 최신가/전일종가 등을 반환
            info = yf.Ticker(ticker).fast_info
            price = info.get("lastPrice")
            prev_close = info.get("previousClose")
            change_pct = (
                round((price - prev_close) / prev_close * 100, 3)
                if price and prev_close else None
            )

            logger.info(f"  {label}({ticker}): 현재가={price}, 전일대비={change_pct}%")

            # TODO: 실제 파이프라인에서는 여기서 DB/메시지큐로 적재
            # save_to_db(label, ticker, price, change_pct, run_time)

        except Exception as e:
            logger.warning(f"  {label}({ticker}) 수집 실패: {e}")

        # 티커 간 짧은 지연으로 rate limit 회피
        time.sleep(random.uniform(0.5, 1.5))

    logger.info("=== 패스트 트랙 수집 완료 ===\n")


if __name__ == "__main__":
    scheduler = BlockingScheduler()
    # 매 15분마다 실행 (기획정의서 4.1 패스트 트랙 주기와 동일)
    scheduler.add_job(fetch_latest_snapshot, "interval", minutes=15, next_run_time=datetime.now())

    logger.info("패스트 트랙 스케줄러 시작 (15분 주기)")
    try:
        scheduler.start()
    except (KeyboardInterrupt, SystemExit):
        logger.info("스케줄러 종료")