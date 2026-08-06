#!/bin/sh
set -eu

ERP_DIR="${KG_ERP_DIR:-/data/erp}"
OUTBOUND_DIR="${KG_OUTBOUND_DIR:-/data/outbound}"

mkdir -p "$ERP_DIR" "$OUTBOUND_DIR"

# EFS에 없는 초기 파일만 복사합니다.
# 서비스 운영 중 수정된 CSV는 덮어쓰지 않습니다.
cp -rn /seed/erp/. "$ERP_DIR/"
cp -rn /seed/outbound/. "$OUTBOUND_DIR/"

exec "$@"