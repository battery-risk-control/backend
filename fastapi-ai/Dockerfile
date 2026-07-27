FROM python:3.12-slim
WORKDIR /app

# build-essential: chromadb 등 일부 패키지가 설치 시 C 확장을 컴파일할 수 있어 필요.
# curl: compose 헬스체크용.
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential curl \
 && rm -rf /var/lib/apt/lists/*

# requirements를 먼저 복사·설치하면 소스만 바뀌었을 때 설치 레이어를 캐시로 재사용한다.
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
