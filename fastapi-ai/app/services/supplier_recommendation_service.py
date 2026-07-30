import logging

import httpx

from app.core.config import get_settings
from app.schemas.supplier import RankedSupplierRecommendation, SupplierCandidate, SupplierRecommendationResult

logger = logging.getLogger(__name__)


class SupplierRecommendationService:
    """F9: Spring이 필수조건으로 걸러준 적격 공급사 후보만 비교·우선순위화합니다.

    실제 LLM 연동 전까지는 리드타임/가격/리스크등급/우선순위 기반 점수로 정렬하는 mock 규칙이며,
    Spring이 보내지 않은 후보를 새로 만들어내지 않습니다.
    """

    def recommend(
        self, material_category: str, risk_note: str | None,
        erp_alternative_supplier_risk_score: float | None = None,
        erp_supplier_risk_scores: dict[str, float] | None = None,
    ) -> SupplierRecommendationResult:
        candidates = self._fetch_qualified_candidates(material_category)
        if not candidates:
            return SupplierRecommendationResult(
                material_category=material_category, recommendations=[],
                caveats=["적격 공급사 후보가 없습니다(자격 미달이거나 Spring 조회 실패)."],
            )

        ranked = sorted(
            candidates,
            key=lambda c: self._score(c, erp_alternative_supplier_risk_score, erp_supplier_risk_scores),
            reverse=True,
        )
        recommendations = [
            self._build_recommendation(candidate, rank, candidates, erp_supplier_risk_scores)
            for rank, candidate in enumerate(ranked, start=1)
        ]
        return SupplierRecommendationResult(
            material_category=material_category,
            recommendations=recommendations,
            caveats=self._build_caveats(risk_note, erp_alternative_supplier_risk_score, erp_supplier_risk_scores),
        )

    def _fetch_qualified_candidates(self, material_category: str) -> list[SupplierCandidate]:
        settings = get_settings()
        try:
            response = httpx.get(
                f"{settings.spring_base_url}/api/v1/suppliers/qualified",
                params={"material_category": material_category}, timeout=5.0,
            )
            response.raise_for_status()
            data = response.json()["data"]
            return [SupplierCandidate(**candidate) for candidate in data["candidates"]]
        except (httpx.HTTPError, KeyError, ValueError) as exception:
            logger.warning("Spring 적격 공급사 조회 실패: %s", exception)
            return []

    def _score(
        self, candidate: SupplierCandidate, erp_alternative_supplier_risk_score: float | None,
        erp_supplier_risk_scores: dict[str, float] | None = None,
    ) -> float:
        """공급사별 순위 계산. 위험 평가는 우리 자체 risk_level이 아니라 ERP Exposure Agent가 계산한
        위험점수를 가중치 없이 그대로 반영합니다 — 공급사별 평가(erp_supplier_risk_scores)가 있으면
        그 공급사 고유의 점수를, 없으면(Agent가 공급사별 평가를 못 한 경우) 자재 전체 점수를 그대로 씁니다.
        """
        score = 0.0
        if candidate.priority_rank is not None:
            score -= candidate.priority_rank * 10
        if not candidate.is_alternative:
            score += 15
        if candidate.lead_time_days is not None:
            score -= candidate.lead_time_days * 0.3
        if candidate.latest_unit_price is not None:
            score -= candidate.latest_unit_price * 0.05
        risk_score = self._resolve_erp_risk_score(
            candidate, erp_alternative_supplier_risk_score, erp_supplier_risk_scores)
        if risk_score is not None:
            score -= risk_score
        return score

    def _resolve_erp_risk_score(
        self, candidate: SupplierCandidate, material_risk_score: float | None,
        erp_supplier_risk_scores: dict[str, float] | None,
    ) -> float | None:
        if erp_supplier_risk_scores and candidate.supplier_code in erp_supplier_risk_scores:
            return erp_supplier_risk_scores[candidate.supplier_code]
        return material_risk_score

    def _build_recommendation(
        self, candidate: SupplierCandidate, rank: int, all_candidates: list[SupplierCandidate],
        erp_supplier_risk_scores: dict[str, float] | None = None,
    ) -> RankedSupplierRecommendation:
        pros: list[str] = []
        cons: list[str] = []

        lead_times = [c.lead_time_days for c in all_candidates if c.lead_time_days is not None]
        if candidate.lead_time_days is not None and lead_times:
            if candidate.lead_time_days == min(lead_times):
                pros.append(f"리드타임이 후보 중 가장 짧음({candidate.lead_time_days}일)")
            elif len(lead_times) > 1 and candidate.lead_time_days == max(lead_times):
                cons.append(f"리드타임이 후보 중 가장 김({candidate.lead_time_days}일)")

        prices = [c.latest_unit_price for c in all_candidates if c.latest_unit_price is not None]
        if candidate.latest_unit_price is not None and prices:
            if candidate.latest_unit_price == min(prices):
                pros.append(f"단가가 후보 중 가장 낮음({candidate.latest_unit_price})")
            elif len(prices) > 1 and candidate.latest_unit_price == max(prices):
                cons.append(f"단가가 후보 중 가장 높음({candidate.latest_unit_price})")

        if erp_supplier_risk_scores:
            my_score = erp_supplier_risk_scores.get(candidate.supplier_code)
            peer_scores = [
                erp_supplier_risk_scores[c.supplier_code]
                for c in all_candidates if c.supplier_code in erp_supplier_risk_scores
            ]
            if my_score is not None and peer_scores:
                if my_score == min(peer_scores):
                    pros.append(f"ERP 공급사 평가 위험도가 후보 중 가장 낮음({my_score:.0f}/100)")
                elif len(peer_scores) > 1 and my_score == max(peer_scores):
                    cons.append(f"ERP 공급사 평가 위험도가 후보 중 가장 높음({my_score:.0f}/100)")

        if candidate.is_alternative:
            cons.append("주 공급사가 아닌 대체 공급사")
        else:
            pros.append("주력(비대체) 공급사")

        reason_parts = [", ".join(pros[:2]) if pros else "특이 강점 없음"]
        reason = f"{rank}순위 후보: " + " / ".join(reason_parts)

        return RankedSupplierRecommendation(
            supplier_id=candidate.supplier_id, supplier_code=candidate.supplier_code,
            supplier_name=candidate.supplier_name, rank=rank, pros=pros, cons=cons,
            recommendation_reason=reason,
        )

    def _build_caveats(
        self, risk_note: str | None,
        erp_alternative_supplier_risk_score: float | None = None,
        erp_supplier_risk_scores: dict[str, float] | None = None,
    ) -> list[str]:
        caveats: list[str] = []
        if erp_supplier_risk_scores:
            caveats.append(
                "ERP Exposure Agent가 공급사별로 평가한 위험도(자격상태·거래상태·capacity)를 "
                "후보 순위에 반영했습니다."
            )
        elif erp_alternative_supplier_risk_score is not None and erp_alternative_supplier_risk_score >= 50:
            caveats.append(
                f"ERP Exposure Agent 기준 이 자재의 대체 공급 리스크가 높습니다"
                f"(점수 {erp_alternative_supplier_risk_score:.0f}/100). 공급사별 평가가 아직 없어 "
                f"순위 자체보다는 참고 정보로만 반영됐습니다."
            )
        if risk_note:
            caveats.append(f"요청된 리스크 상황 참고: {risk_note}")
        return caveats
