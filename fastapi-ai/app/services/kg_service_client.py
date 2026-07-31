"""kg_service(온톨로지 그래프 리졸버, GET /resolve) 조회 클라이언트.

kg_service는 fastapi-ai와 별도 프로세스로 떠 있다(CLAUDE.md 결정사항 10번) — FastAPI가 ERP
DB에 직접 접근하지 않는 원칙과 같은 이유로, kg_service가 읽는 CSV 스냅샷도 별도 경계로 둔다.
호출 실패(연결 불가, 4xx/5xx, 응답 파싱 실패) 시 예외를 올리지 않고 "매칭 없음"으로 처리한다 —
KG는 멀티에이전트 진입 전 게이트일 뿐이라, 리졸버가 잠깐 죽어도 전체 브리핑 파이프라인이
막히면 안 된다(SupplierRecommendationService의 기존 폴백 패턴과 동일).
"""
import json
import logging

import httpx

from app.core.config import get_settings

logger = logging.getLogger(__name__)


def resolve_kg_context(
    country: str,
    affected_materials: list[str],
) -> dict:
    settings = get_settings()

    try:
        response = httpx.get(
            f"{settings.kg_service_base_url}/resolve",
            params={
                "country": country,
                "affected_materials": json.dumps(
                    affected_materials,
                ),
            },
            timeout=5.0,
        )
        response.raise_for_status()
        return response.json()
    except (httpx.HTTPError, ValueError) as exception:
        logger.warning(
            "KG 리졸버 조회 실패 (country=%s, affected_materials=%s): %s",
            country,
            affected_materials,
            exception,
        )
        return {
            "matched": False,
            "country": country,
            "affected_materials": affected_materials,
            "results": [],
        }
