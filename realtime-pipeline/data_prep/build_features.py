import pandas as pd
import numpy as np
import yfinance as yf
from datetime import datetime, timedelta
import os
import sys
from tqdm import tqdm

# gdacs_live.py는 src/에 있음 — realtime_risk_pipeline.py를 통해 호출될 때는 이미
# sys.path에 잡혀있지만, 이 스크립트를 단독 실행(python data_prep/build_features.py)할
# 때도 동작하도록 여기서도 추가해둠.
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))
import gdacs_live

def load_bdi_data(bdi_path):
    print("BDI(발틱운임지수) 데이터 로딩 및 피처 엔지니어링...")
    bdi = pd.read_csv(bdi_path)
    
    # 날짜 컬럼 파싱 (보통 'Date' 또는 첫번째 컬럼)
    date_col = bdi.columns[0] if 'Date' not in bdi.columns else 'Date'
    val_col = bdi.columns[1] if 'BDI' not in bdi.columns else 'BDI'
    
    # 쉼표 제거 및 숫자 변환
    bdi[val_col] = pd.to_numeric(bdi[val_col].astype(str).str.replace(',', ''), errors='coerce')
        
    bdi['Date'] = pd.to_datetime(bdi[date_col], format='mixed', errors='coerce')
    bdi = bdi.sort_values('Date').set_index('Date')
    
    # 결측치 채우기 (영업일 기준 포워드 필)
    bdi = bdi.ffill()
    
    # 전일 대비 변동률 (%)
    bdi['bdi_pct_change'] = bdi[val_col].pct_change() * 100
    
    # 20일 이동평균 기반 Z-score (최근 운임 급등락 여부)
    roll_mean = bdi[val_col].rolling(window=20).mean()
    roll_std = bdi[val_col].rolling(window=20).std()
    bdi['bdi_zscore_20d'] = (bdi[val_col] - roll_mean) / roll_std
    
    # 누락된 주말/휴일 처리를 위해 전체 날짜 리인덱싱 후 ffill
    idx = pd.date_range(bdi.index.min(), pd.Timestamp.today())
    bdi = bdi.reindex(idx).ffill()
    bdi.index.name = 'Date'
    
    return bdi[['bdi_pct_change', 'bdi_zscore_20d']]

YFINANCE_CACHE_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data_core", "yfinance_price_cache.csv"
)


def _load_closes_cached(tickers):
    """
    2026-07-25: 매 실행마다 2018년부터 전체를 재다운로드하던 것을 캐싱으로 교체.

    15분마다(또는 하루에도 여러 번) build_features.py를 돌리면서 매번 8년치
    가격을 처음부터 다시 받는 게 실행시간의 대부분을 차지했다(실측: yfinance
    다운로드 포함 "몇 분" 소요). 어차피 어제까지의 종가는 절대 안 바뀌므로,
    로컬 캐시(data_core/yfinance_price_cache.csv)에 이미 있는 날짜는 건너뛰고
    "캐시의 마지막 날짜 다음날 ~ 오늘"만 증분으로 받아서 이어붙인다.

    캐시 파일이 없으면(최초 1회) 기존과 동일하게 2018-01-01부터 전체를 받는다.
    """
    today_str = datetime.today().strftime('%Y-%m-%d')

    if os.path.exists(YFINANCE_CACHE_PATH):
        cached = pd.read_csv(YFINANCE_CACHE_PATH, index_col="Date", parse_dates=True)
        # 캐시 생성 이후 티커 목록이 바뀐 적이 없다는 전제(현재 하드코딩된 목록이라 안전).
        # 혹시 컬럼이 달라졌으면(티커 추가/제거) 안전하게 캐시를 버리고 전체 재다운로드.
        if set(cached.columns) != set(tickers):
            print("  캐시의 티커 목록이 현재와 달라 캐시를 버리고 전체 재다운로드합니다.")
        else:
            last_cached_date = cached.index.max()
            fetch_start = (last_cached_date + timedelta(days=1)).strftime('%Y-%m-%d')
            if fetch_start > today_str:
                print(f"  캐시가 최신 상태({last_cached_date.date()}) — 재다운로드 생략.")
                return cached
            print(f"  캐시 발견: {last_cached_date.date()}까지 있음 — {fetch_start} ~ {today_str}만 증분 다운로드.")
            try:
                new_data = yf.download(tickers, start=fetch_start, end=today_str, progress=False)
                new_closes = new_data['Close'] if not new_data.empty else pd.DataFrame(columns=tickers)
            except Exception as e:
                print(f"  증분 다운로드 실패({e}) — 기존 캐시만 사용.")
                return cached
            combined = pd.concat([cached, new_closes])
            combined = combined[~combined.index.duplicated(keep='last')].sort_index()
            combined.to_csv(YFINANCE_CACHE_PATH, encoding='utf-8-sig')
            return combined

    print("  캐시 없음 — 최초 1회 전체 기간(2018-01-01~) 다운로드.")
    data = yf.download(tickers, start="2018-01-01", end=today_str, progress=False)
    closes = data['Close']
    os.makedirs(os.path.dirname(YFINANCE_CACHE_PATH), exist_ok=True)
    closes.to_csv(YFINANCE_CACHE_PATH, encoding='utf-8-sig')
    return closes


def fetch_yfinance_volatility():
    print("yfinance 주가 변동성 데이터 로딩...")

    # 주요 원자재와 티커 매핑 (한/영 통합 및 최적 프록시 반영)
    material_tickers = {
        '리튬': 'LIT', 'lithium': 'LIT',
        '니켈': 'VALE', 'nickel': 'VALE',
        '코발트': 'GLNCY', 'cobalt': 'GLNCY',
        '구리': 'HG=F', 'copper': 'HG=F', 'cobre': 'HG=F',
        '알루미늄': 'ALI=F', 'aluminum': 'ALI=F', 'aluminium': 'ALI=F',
        '희토류': 'REMX', 'rare earth minerals': 'REMX', 'rare earth elements': 'REMX',
        '흑연': 'SYR.AX', 'graphite': 'SYR.AX',
        '망간': 'S32.AX', 'manganese': 'S32.AX',
        '철': 'TIO=F', '철광석': 'TIO=F', 'iron ore': 'TIO=F', 'steel': 'TIO=F'
    }

    # 모든 티커의 데이터를 한번에 다운로드 (캐시 우선, 신규분만 증분)
    tickers = list(set(material_tickers.values()))
    try:
        closes = _load_closes_cached(tickers)
    except Exception as e:
        print(f"yfinance 다운로드 실패: {e}")
        return material_tickers, pd.DataFrame()

    # 각 종목별 20일 주가 변동성(수익률의 표준편차) 계산
    returns = closes.pct_change()
    volatility = returns.rolling(window=20).std() * np.sqrt(252) * 100 # 연율화된 변동성(%)
    
    # [중요] Data Leakage(미래 참조 편향) 방지: 기사 당일(t) 장 마감 전의 변동성이 
    # 모델에 노출되지 않도록 t-1일까지의 변동성으로 하루를 지연시킵니다.
    volatility = volatility.shift(1)

    
    # 주말/휴일 채우기
    idx = pd.date_range(volatility.index.min(), datetime.today())
    volatility = volatility.reindex(idx).ffill()
    volatility.index = pd.to_datetime(volatility.index)
    volatility.index.name = 'Date'
    
    return material_tickers, volatility

def load_weather_gdacs_data(base_path):
    """
    2026-07-24 수정: GDACS/OpenMeteo 조인을 언어 불일치(한글 국가명 매핑) 대신
    ISO 국가코드(ActionGeo_CountryCode, GDELT 원시 필드라 항상 일관된 포맷)로 바꿈.

    2026-07-27 수정: GDACS를 정적 스냅샷 파일 대신 실시간 API 조회(src/gdacs_live.py)로
    교체. 기존 정적 파일은 특정 시점에 한 번 받아둔 것이라 그 이후 발생한 실시간 이벤트에는
    영원히 매칭이 안 됐고(gdacs_alert_level 하드 게이트가 사실상 죽어있었음), 심지어 칠레를
    ISO2 'CL'로 매핑해놨는데 이 프로젝트 전역은 FIPS 'CI'를 쓰고 있어서 애초에 그것도
    안 맞았음. 이제 매 실행(15분 파이프라인 포함)마다 GDACS 공식 API에서 현재 진행 중인
    Orange/Red 경보를 가져와 정확한 FIPS 코드로 매핑한다(src/gdacs_live.py 참고).
    OpenMeteo(강수량)는 원래도 일별 연속 데이터라 날짜 윈도우 문제는 없음, 언어 문제만 고치면 됨.
    """
    print("기상(OpenMeteo) 및 재난(GDACS) 데이터 매핑 중...")
    import glob, json

    # GDACS — 실시간 API 조회로 교체 (2026-07-27)
    df_gdacs = gdacs_live.fetch_current_alerts()

    # OpenMeteo
    weather_list = []
    om_country_map = {'AR_SOMBRE_MUERTO': 'AR', 'CD_KAMBOVE': 'CD', 'CD_MUTANDA': 'CD', 'CL_ATACAMA': 'CL', 'ID_MOROWALI': 'ID', 'ID_WEDA_BAY': 'ID', 'SEA_LOMBOK': 'ID', 'SEA_MALACCA': 'MY', 'PORT_ANTOFAGASTA': 'CL', 'PORT_MOROWALI': 'ID', 'PORT_WEDA_BAY': 'ID'}
    om_files = glob.glob(os.path.join(base_path, '데이터셋', 'openmeteo_*', '*.json'))
    for f_path in om_files:
        basename = os.path.basename(f_path)
        matched_country = next((v for k, v in om_country_map.items() if k in basename), None)
        if matched_country:
            with open(f_path, 'r', encoding='utf-8') as f: om_data = json.load(f)
            hourly = om_data.get('hourly', {})
            if 'time' in hourly and 'precipitation' in hourly:
                temp_df = pd.DataFrame({'time': hourly['time'], 'precipitation': hourly['precipitation']})
                temp_df['Date'] = pd.to_datetime(temp_df['time']).dt.date
                daily = temp_df.groupby('Date')['precipitation'].sum().reset_index()
                daily['ActionGeo_CountryCode'] = matched_country
                daily['has_weather_data'] = 1 # 결측 플래그용
                weather_list.append(daily)

    if weather_list:
        df_weather = pd.concat(weather_list)
        df_weather['Date'] = pd.to_datetime(df_weather['Date'])
        df_weather = df_weather.groupby(['ActionGeo_CountryCode', 'Date'], as_index=False)[['precipitation', 'has_weather_data']].max()
        df_weather.rename(columns={'precipitation': 'rainfall_24h_mm'}, inplace=True)
    else:
        df_weather = pd.DataFrame(columns=['ActionGeo_CountryCode', 'Date', 'rainfall_24h_mm', 'has_weather_data'])

    return df_gdacs, df_weather

def extract_primary_material(material_list_str):
    # '["리튬", "니켈"]' 같은 형태에서 첫번째 주요 광물 추출
    if pd.isna(material_list_str) or material_list_str == '[]':
        return None
    try:
        import json
        mats = json.loads(material_list_str)
        if mats and isinstance(mats, list):
            return mats[0]
    except:
        pass
    return None

def main(force_full=False):
    # 하드코딩된 절대경로(구글드라이브 "다른 컴퓨터" 동기화 경로) 대신 스크립트
    # 위치 기준 상대경로로 계산 — 어느 컴퓨터/환경에서 실행해도 동작하도록 함
    base_path = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_path = os.path.join(base_path, 'data_core', 'event_features.csv')

    # 1. LLM 라벨링 결과 로드
    labels_path = os.path.join(base_path, 'data_core', 'cleaned_labeled_articles.csv')
    print(f"1. 라벨링 데이터 로드 중: {labels_path}")
    df_main = pd.read_csv(labels_path)

    # 필수 컬럼(XGBoost 타겟 및 중요 피처)이 없는 행(LLM 실패건) 필터링 (사용자님 요청사항)
    essential_cols = ['affected_material', 'tone_score', 'event_type', 'impact_domain_draft']
    df_main = df_main.dropna(subset=essential_cols).copy()

    # 2026-07-25: 증분 처리 추가.
    # 이전엔 실행할 때마다 cleaned_labeled_articles.csv 전체(계속 누적되며 커지는 파일)를
    # 처음부터 다시 피처 병합했다. 15분 크롤러가 계속 append하는 실시간 파이프라인에서
    # 이 방식은 실행 시간이 데이터가 쌓일수록 계속 길어진다. 이미 event_features.csv에
    # 있는 GlobalEventID는 건너뛰고, 새로 추가된 행만 계산해서 append하는 방식으로 변경.
    existing_df = None
    if not force_full and os.path.exists(out_path):
        existing_df = pd.read_csv(out_path)
        already_done = set(existing_df['GlobalEventID'])
        before = len(df_main)
        df_main = df_main[~df_main['GlobalEventID'].isin(already_done)].copy()
        print(f"  증분 모드: 기존 {len(already_done)}건 중복 제외, "
              f"신규 {len(df_main)}건만 처리 (원본 {before}건)")
        if df_main.empty:
            print("  신규 이벤트 없음 — 종료.")
            return
    else:
        print("  전체 재계산 모드 (기존 event_features.csv 없음 또는 --full 지정)")

    # 2. GDELT 메타데이터 로드 및 결합
    # (구 bq_metadata_extracted.csv + bq_precrawl_extra_fields.csv를 gdelt_event_metadata.csv로 통합함.
    #  기존 event_features.csv 스키마를 그대로 유지하기 위해 여기서 쓰던 6개 컬럼만 선택)
    bq_path = os.path.join(base_path, 'data_ref', 'gdelt_event_metadata.csv')
    print(f"2. GDELT 메타데이터 로드 및 결합: {bq_path}")
    df_meta = pd.read_csv(bq_path)[
        ['GlobalEventID', 'SQLDATE', 'GoldsteinScale', 'NumArticles', 'AvgTone',
         'Actor1Type1Code', 'Actor2Type1Code', 'ActionGeo_CountryCode']
    ]

    # GlobalEventID 기준으로 Left Join
    df = pd.merge(df_main, df_meta, on='GlobalEventID', how='left')
    
    # 날짜 파싱 (SQLDATE: YYYYMMDD -> Date)
    df['Date'] = pd.to_datetime(df['SQLDATE'].astype(str), format='%Y%m%d', errors='coerce')
    
    # Date가 없는 행 제거 (이후 시계열 병합 불가)
    df = df.dropna(subset=['Date'])
    
    # 3. BDI 지수 결합
    bdi_path = os.path.join(base_path, '데이터셋', 'BDI 2020.01.01-2026.07.14.csv')
    df_bdi = load_bdi_data(bdi_path)
    
    df = pd.merge(df, df_bdi, on='Date', how='left')
    # 결측치(BDI 데이터 범위 밖)는 0으로 채움
    df['bdi_pct_change'] = df['bdi_pct_change'].fillna(0)
    df['bdi_zscore_20d'] = df['bdi_zscore_20d'].fillna(0)
    
    # 4. yfinance 주가 변동성 결합
    material_tickers, df_volatility = fetch_yfinance_volatility()
    
    tqdm.pandas(desc="주가 변동성 매핑")
    def get_volatility(row):
        mat = extract_primary_material(row['affected_material'])
        date = pd.to_datetime(row['Date'])
        if mat and mat in material_tickers:
            ticker = material_tickers[mat]
            try:
                val = df_volatility.loc[date, ticker]
                if not pd.isna(val): return val
            except KeyError: pass
        return 0.0

    if not df_volatility.empty:
        df['stock_volatility_20d'] = df.progress_apply(get_volatility, axis=1)
    else:
        df['stock_volatility_20d'] = 0.0
        
    # 결측 플래그 (0.0 자체와 데이터가 진짜 없는 것을 구분)
    df['has_stock_data'] = (df['stock_volatility_20d'] != 0.0).astype(int)

        
    # 5. Open-Meteo & GDACS 결합
    df_gdacs, df_weather = load_weather_gdacs_data(base_path)
    
    # GDACS Merge (country 자유텍스트 대신 ActionGeo_CountryCode(ISO)로 조인 — 버그 수정)
    if not df_gdacs.empty:
        df = pd.merge(df, df_gdacs, on=['ActionGeo_CountryCode', 'Date'], how='left')
        df['gdacs_alert_level'] = df['gdacs_alert_level'].fillna(0.0)
    else:
        df['gdacs_alert_level'] = 0.0

    # Weather Merge (동일하게 ActionGeo_CountryCode로 조인)
    if not df_weather.empty:
        df = pd.merge(df, df_weather, on=['ActionGeo_CountryCode', 'Date'], how='left')
        df['rainfall_24h_mm'] = df['rainfall_24h_mm'].fillna(0.0)
        df['has_weather_data'] = df['has_weather_data'].fillna(0).astype(int)
    else:
        df['rainfall_24h_mm'] = 0.0
        df['has_weather_data'] = 0
    
    # 최종 전처리된 컬럼 중 불필요한 것 제거 (SQLDATE 등)
    drop_cols = ['SQLDATE']
    df = df.drop(columns=[c for c in drop_cols if c in df.columns])

    # 6. 최종 저장 — 증분 모드면 기존 결과에 신규분만 append
    if existing_df is not None:
        df = pd.concat([existing_df, df], ignore_index=True)
        df = df.drop_duplicates(subset=['GlobalEventID'], keep='last')

    # existing_df는 CSV에서 날짜 파싱 없이 문자열로 읽히고(예: "2020-11-25"),
    # 신규 계산분의 Date는 pd.to_datetime()이 만든 Timestamp라 그대로 concat하면
    # 저장 시 "2022-01-13 00:00:00"처럼 시간까지 섞여 나온다. 저장 직전 통일.
    df['Date'] = pd.to_datetime(df['Date'], format='mixed').dt.strftime('%Y-%m-%d')

    df.to_csv(out_path, index=False, encoding='utf-8-sig')
    print(f"\n4단계 피처 병합 완료! 최종 데이터가 {out_path} 에 저장되었습니다.")
    print(f"총 {len(df)}건의 리스크 이벤트 및 피처가 준비되었습니다.")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--full", action="store_true",
                         help="증분 처리 대신 전체(cleaned_labeled_articles.csv 전부)를 강제로 재계산")
    args = parser.parse_args()
    main(force_full=args.full)
