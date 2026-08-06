"""
배터리 원자재 공급망 리스크 감지 시스템 - yfinance 5년치 데이터 수집 스크립트

근거 문서:
- 데이터 활용 및 모델 학습 기획정의서 v2 (4단계 피처 테이블, 6단계 회귀 모듈)
- 프로젝트 기획정의서_7.15 (6. 데이터 소스, 패스트 트랙)
- 요구사항_정의서.xlsx (USR-14-07007: 공급망 외부 데이터 주기적 수집)

수집 기간: 2021-01-01 ~ 2026-06-30 (기획정의서 3.1 학습 데이터 수집 기간과 동일)

티커 검증 참고:
- 문서상 S32 -> 실제 Yahoo Finance 티커는 S32.AX (ASX 상장, South32 Limited)
- 문서상 SYR -> 실제 Yahoo Finance 티커는 SYR.AX (ASX 상장, Syrah Resources)
- 나머지(ALB, BHP, GLNCY, AA, FCX, SQM)는 미국 상장/ADR로 그대로 사용
출처: https://finance.yahoo.com/quote/S32.AX/ , https://finance.yahoo.com/quote/SYR.AX/
"""

import yfinance as yf
import pandas as pd
from pathlib import Path

# ---------------------------------------------------------------------------
# 1. 원자재 -> 대표 채굴기업 티커 매핑 (기획정의서 4단계 매핑표 기준, 티커 검증 반영)
# ---------------------------------------------------------------------------
MATERIAL_TICKER_MAP = {
    "리튬":   "ALB",     # Albemarle (미국 NYSE)
    "니켈":   "BHP",     # BHP Group (미국 ADR)
    "코발트": "GLNCY",   # Glencore (미국 ADR)
    "망간":   "S32.AX",  # South32 (ASX 상장 - 문서상 'S32'는 미조회, .AX 필요)
    "알루미늄": "AA",    # Alcoa (미국 NYSE)
    "구리":   "FCX",     # Freeport-McMoRan (미국 NYSE)
    "흑연":   "SYR.AX",  # Syrah Resources (ASX 상장 - 문서상 'SYR'은 미조회, .AX 필요)
    "희토류": "REMX",    # VanEck Rare Earth/Strategic Metals ETF (희토류 프록시 — 단일 상장사 부재)
}

# 뉴스/알람 신뢰도 교차검증용 주요 광산기업·ETF (기획정의서 6. 데이터 소스)
CROSS_CHECK_TICKERS = {
    "SQM": "SQM",              # 칠레 리튬 생산기업
    "Albemarle": "ALB",
}

# 실시간 환율 (yfinance 통화쌍 티커 표기법: {통화}=X)
FX_TICKERS = {
    "USD/KRW": "KRW=X",
}

START_DATE = "2021-01-01"
END_DATE = "2026-06-30"

OUTPUT_DIR = Path("./yfinance_data")
OUTPUT_DIR.mkdir(exist_ok=True)


def collect_ticker_history(ticker: str, label: str) -> pd.DataFrame:
    """
    단일 티커의 5년치 일봉 데이터를 수집한다.

    시간대(timezone) 정규화:
    yfinance는 거래소 현지시간대(tz-aware)로 날짜를 반환한다.
    예) 미국 상장 티커(ALB/BHP/FCX 등) -> America/New_York (UTC-5 또는 서머타임 시 UTC-4)
        호주 상장 티커(S32.AX/SYR.AX) -> Australia/Sydney (UTC+10 또는 UTC+11)
    이 상태 그대로면 (1) 같은 티커라도 3월/11월 서머타임 전환 시점에 UTC 오프셋이 바뀌고,
    (2) 거래소가 다른 티커끼리는 날짜 자체가 하루씩 밀릴 수 있어 GDELT 등 UTC 기준
    뉴스 이벤트 날짜와 조인할 때 어긋난다.
    -> 원본 Date는 보존하되, UTC로 변환한 event_date 컬럼을 별도로 만들어
       4단계 피처 테이블 조인 키로 사용한다.
    """
    print(f"[수집 중] {label} ({ticker}) ...")
    df = yf.Ticker(ticker).history(
        start=START_DATE,
        end=END_DATE,
        interval="1d",
        auto_adjust=True,   # 배당/액면분할 반영 수정주가
    )
    if df.empty:
        print(f"  -> 경고: {ticker} 데이터가 비어 있습니다. 티커를 확인하세요.")
        return df

    df = df.reset_index()
    df["Date_local"] = df["Date"]                              # 거래소 현지시간(원본 보존)
    df["Date"] = pd.to_datetime(df["Date"], utc=True)           # UTC로 통일
    df["event_date"] = df["Date"].dt.date                       # 조인용 날짜 키 (시간대 제거)
    df["material_or_label"] = label
    df["ticker"] = ticker
    return df


def compute_event_features(df: pd.DataFrame) -> pd.DataFrame:
    """
    4단계 피처 테이블용 파생 변수 계산:
    - stock_return_1d : 전일 대비 익일 수익률
    - stock_vol_20d   : 20일 롤링 변동성(표준편차)
    """
    df = df.sort_values("Date").copy()
    df["stock_return_1d"] = df["Close"].pct_change(1)
    df["stock_vol_20d"] = df["stock_return_1d"].rolling(window=20).std()
    return df


def compute_forward_cumulative_return(df: pd.DataFrame, horizon_days: int = 14) -> pd.DataFrame:
    """
    6단계 Impact Magnitude 회귀 모듈용 정답값:
    사건 발생일 기준 t+1 ~ t+horizon_days 누적 수익률
    (실제 파이프라인에서는 사건 발생일(event_date)과 조인해서 사용)
    """
    df = df.sort_values("Date").copy()
    df["fwd_cum_return"] = (
        df["Close"].shift(-horizon_days) / df["Close"].shift(-1) - 1
    )
    return df


def main():
    all_frames = []

    # 1) 원자재별 대표기업 주가 수집
    for material, ticker in MATERIAL_TICKER_MAP.items():
        df = collect_ticker_history(ticker, label=material)
        if df.empty:
            continue
        df = compute_event_features(df)
        df = compute_forward_cumulative_return(df, horizon_days=14)
        all_frames.append(df)
        # encoding="utf-8-sig": BOM을 붙여서 엑셀이 UTF-8로 정확히 인식하게 함
        # (BOM 없는 기본 UTF-8로 저장하면 엑셀이 CP949로 오인해 한글이 깨짐)
        df.to_csv(
            OUTPUT_DIR / f"{material}_{ticker.replace('.', '_')}.csv",
            index=False,
            encoding="utf-8-sig",
        )

    # 2) 뉴스 신뢰도 교차검증용 주요 광산기업
    for name, ticker in CROSS_CHECK_TICKERS.items():
        df = collect_ticker_history(ticker, label=f"crosscheck_{name}")
        if df.empty:
            continue
        df.to_csv(OUTPUT_DIR / f"crosscheck_{name}_{ticker}.csv", index=False, encoding="utf-8-sig")

    # 3) 환율 수집
    for pair, ticker in FX_TICKERS.items():
        df = collect_ticker_history(ticker, label=pair)
        if df.empty:
            continue
        df.to_csv(OUTPUT_DIR / f"fx_{ticker.replace('=', '_')}.csv", index=False, encoding="utf-8-sig")

    # 4) 전체 병합본 저장 (원자재 피처만)
    if all_frames:
        merged = pd.concat(all_frames, ignore_index=True)
        merged.to_csv(OUTPUT_DIR / "all_materials_merged.csv", index=False, encoding="utf-8-sig")
        print(f"\n완료: {len(merged)}행, {OUTPUT_DIR / 'all_materials_merged.csv'} 저장됨")


if __name__ == "__main__":
    main()