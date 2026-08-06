# KG 리졸버 서버.
#
# 빌드 컨텍스트는 **저장소 루트**여야 한다 — main.py가 sys.path에 <repo>/src를 넣고
# build_ontology_graph를 import하므로 kg_service/ 하나만으로는 빌드가 안 된다.
# (docker-compose.yml의 kg 서비스가 context: . / dockerfile: kg_service/Dockerfile 로 잡는다.)
FROM python:3.12-slim

WORKDIR /app

# 의존성만 먼저 복사해 레이어 캐시를 살린다. 코드가 바뀌어도 pip install을 다시 돌리지 않는다.
COPY kg_service/requirements.txt ./kg_service/requirements.txt
RUN pip install --no-cache-dir -r kg_service/requirements.txt

COPY kg_service/ ./kg_service/
COPY src/ ./src/

COPY data/ERP_data/spring-csv/ /seed/erp/
COPY data_ref/outbound_mock/ /seed/outbound/

COPY kg_service/docker-entrypoint.sh /usr/local/bin/kg-entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/kg-entrypoint.sh \
    && chmod +x /usr/local/bin/kg-entrypoint.sh

# CSV는 이미지에 굽지 않는다. compose가 읽기 전용 볼륨으로 붙이므로, 데이터를 고쳐도
# 재빌드 없이 컨테이너 재시작만으로 반영된다(운영에서 ERP 시드를 갈아끼우는 흐름과 같다).
ENV PYTHONUTF8=1 \
    PYTHONUNBUFFERED=1 \
    KG_ERP_DIR=/data/erp \
    KG_OUTBOUND_DIR=/data/outbound

WORKDIR /app/kg_service

EXPOSE 8100

ENTRYPOINT ["/usr/local/bin/kg-entrypoint.sh"]
CMD ["python", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8100"]
