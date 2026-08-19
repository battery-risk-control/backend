FROM python:3.12-slim
WORKDIR /app

# build-essential: chromadb 등 일부 패키지가 설치 시 C 확장을 컴파일할 수 있어 필요.
# curl: compose 헬스체크용.
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential curl \
 && rm -rf /var/lib/apt/lists/*

# requirements를 먼저 복사·설치하면 소스만 바뀌었을 때 설치 레이어를 캐시로 재사용한다.
COPY fastapi-ai/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY fastapi-ai/app ./app
COPY fastapi-ai/scripts ./scripts
COPY data_core ./data_core
COPY data/demo ./data/demo-seed
EXPOSE 8000
CMD ["sh", "-c", "mkdir -p /app/data/demo; if [ -s /app/data/demo-seed/gdelt_demo_manifest.json ]; then cp /app/data/demo-seed/gdelt_demo_manifest.json /app/data/demo/gdelt_demo_manifest.json; fi; if [ ! -s /app/data/demo/gdelt_demo_manifest.json ] || [ \"$(cat /app/data/demo/gdelt_demo_manifest.json)\" = \"[]\" ]; then python scripts/build_gdelt_demo_manifest.py --source /app/data_core/event_features_normalized.csv --cache-dir /app/data/demo/cache/gdelt --output /app/data/demo/gdelt_demo_manifest.json --report /app/data/demo/gdelt_recovery_report.json || true; fi; exec uvicorn app.main:app --host 0.0.0.0 --port 8000"]
