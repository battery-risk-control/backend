from app.schemas.briefing import (
    BriefingComposeRequest,
    BriefingComposeResult,
    BriefingGenerationRequest,
    BriefingGenerationResult,
)
from app.schemas.common import ProcessingStatus

_TEMPLATE_VERSION = "briefing-template-v1"


class BriefingService:
    """LLM 없이 구조화 입력을 고정 템플릿으로 조립하는 결정적 브리핑 생성기입니다.

    새 내용을 해석하거나 법적 결론을 만들지 않고, 이미 계산된 사실(ERP 수치·Severity·RAG 근거)을
    한국어 문장 틀에 채워 넣기만 한다. 동일 입력은 항상 동일 브리핑을 만든다.
    """

    def generate(self, request: BriefingGenerationRequest) -> BriefingGenerationResult:
        """기존 Orchestration 흐름과의 호환용 Mock 진입점입니다."""
        return BriefingGenerationResult(
            briefing_id=7001,
            risk_id=request.risk_id,
            status=ProcessingStatus.COMPLETED,
            headline=f"리스크 {request.risk_id} 내부 브리핑",
            event_summary=request.event_summary,
            inventory_perspective=request.inventory_summary,
            contract_perspective=request.contract_summary,
            recommended_actions=[
                "입고 예정일과 실제 ETA를 확인한다.",
                "가격 조정 조항 적용 가능 여부를 검토한다.",
            ],
            mock=True,
        )

    def compose(self, request: BriefingComposeRequest) -> BriefingComposeResult:
        """S13: ERP·Severity·RAG·대체공급사 구조화 입력으로 템플릿 브리핑을 조립한다."""
        return BriefingComposeResult(
            headline=self._headline(request),
            risk_summary=self._risk_summary(request),
            inventory_summary=self._inventory_summary(request),
            supplier_dependency_summary=self._dependency_summary(request),
            supply_gap_summary=self._supply_gap_summary(request),
            contract_evidence_summary=self._contract_evidence_summary(request),
            alternative_supplier_summary=self._alternative_supplier_summary(request),
            recommended_checks=self._recommended_checks(request),
            warnings=self._warnings(request),
            template_version=_TEMPLATE_VERSION,
            mock=request.mock,
        )

    # --- 섹션별 템플릿 규칙 ---

    @staticmethod
    def _headline(r: BriefingComposeRequest) -> str:
        return f"[{r.severity}] {r.material_name}({r.erp_material_id}) 공급 리스크 브리핑"

    @staticmethod
    def _risk_summary(r: BriefingComposeRequest) -> str:
        base = f"현재 위험 등급은 {r.severity}(점수 {r.score:g})입니다."
        if r.reason_codes:
            base += " 주요 판단 근거: " + ", ".join(r.reason_codes) + "."
        return base

    @staticmethod
    def _inventory_summary(r: BriefingComposeRequest) -> str:
        if r.inventory_days is None:
            return "사용량 데이터가 없어 재고 소진 일수를 계산할 수 없습니다 (UNKNOWN)."
        text = f"가용재고는 약 {r.inventory_days:g}일치입니다."
        if r.safety_stock_days is not None:
            if r.inventory_days < r.safety_stock_days:
                text += f" 안전재고 기준({r.safety_stock_days:g}일) 미만이라 주의가 필요합니다."
            else:
                text += f" 안전재고 기준({r.safety_stock_days:g}일)을 충족합니다."
        return text

    @staticmethod
    def _dependency_summary(r: BriefingComposeRequest) -> str:
        if r.supplier_dependency_ratio is None:
            text = f"주 공급사는 {r.primary_supplier_id}입니다."
        else:
            pct = round(r.supplier_dependency_ratio * 100, 1)
            text = f"주 공급사 {r.primary_supplier_id}에 대한 의존도는 {pct:g}%입니다."
        text += f" 공급사 상태는 {r.primary_supplier_status}입니다."
        if r.feoc_status == "YES":
            text += " 이 공급사는 FEOC 규제 우려 대상입니다."
        return text

    @staticmethod
    def _supply_gap_summary(r: BriefingComposeRequest) -> str:
        if r.next_eta_days is None:
            return "확인된 입고 예정(ETA)이 없습니다."
        text = f"다음 입고 예정까지 약 {r.next_eta_days}일 남았습니다."
        if r.expected_supply_gap_days is not None:
            if r.expected_supply_gap_days > 0:
                text += f" 재고 소진 후 약 {r.expected_supply_gap_days:g}일의 공급 공백이 예상됩니다."
            else:
                text += " 현재 재고로 입고 시점까지 공급 공백 없이 버틸 수 있습니다."
        return text

    @staticmethod
    def _contract_evidence_summary(r: BriefingComposeRequest) -> str:
        if not r.contract_evidence:
            return "관련 계약 조항을 찾지 못했습니다. (근거 부족 — 담당자 확인 필요)"
        lines = [f"관련 조항 후보 {len(r.contract_evidence)}건이 검색되었습니다. 담당자 검토가 필요합니다."]
        for item in r.contract_evidence[:3]:
            snippet = item.content.strip().replace("\n", " ")
            if len(snippet) > 60:
                snippet = snippet[:60] + "…"
            lines.append(
                f"- 계약 {item.contract_id} p.{item.page_number} 청크 #{item.chunk_index} "
                f"(유사도 {item.similarity_score:g}): {snippet}"
            )
        return "\n".join(lines)

    @staticmethod
    def _alternative_supplier_summary(r: BriefingComposeRequest) -> str:
        if not r.alternative_suppliers:
            return "승인된 대체 공급사 후보가 없습니다."
        lines = [f"적격 대체 공급사 후보 {len(r.alternative_suppliers)}곳:"]
        for s in r.alternative_suppliers:
            certs = []
            if s.iatf_16949_certified:
                certs.append("IATF16949")
            if s.ppap_approved:
                certs.append("PPAP")
            cert_text = ("/".join(certs)) if certs else "인증 정보 없음"
            lead = f", Lead {s.lead_time_days}일" if s.lead_time_days is not None else ""
            lines.append(
                f"- {s.supplier_name}({s.erp_supplier_id}) "
                f"승인={s.approved_status}, FEOC={s.feoc_status}, {cert_text}{lead}"
            )
        return "\n".join(lines)

    @staticmethod
    def _recommended_checks(r: BriefingComposeRequest) -> list[str]:
        checks = ["입고 예정일과 실제 ETA를 담당자가 확인한다."]
        if r.contract_evidence:
            checks.append("검색된 계약 조항의 적용 가능 여부를 담당자가 검토한다.")
        else:
            checks.append("계약서 원문에서 가격 조정·공급 지연 관련 조항을 추가로 확인한다.")
        if r.alternative_suppliers:
            checks.append("적격 대체 공급사 후보의 단가·MOQ·납기를 비교한다.")
        if r.feoc_status == "YES":
            checks.append("FEOC 규제 대응 방안과 대체 조달 경로를 우선 검토한다.")
        return checks

    @staticmethod
    def _warnings(r: BriefingComposeRequest) -> list[str]:
        warnings: list[str] = []
        if r.data_quality_status and r.data_quality_status not in ("VALID",):
            warnings.append(f"데이터 품질 상태가 {r.data_quality_status}입니다. 일부 수치는 신뢰도가 낮을 수 있습니다.")
        if r.severity == "UNKNOWN":
            warnings.append("필수 입력 부족으로 위험 등급을 신뢰도 있게 계산하지 못했습니다.")
        if r.mock:
            warnings.append("본 브리핑은 Mock 분석 결과입니다. 실제 임베딩·LLM 결과가 아닙니다.")
        return warnings


def generate_briefing(request: BriefingGenerationRequest) -> BriefingGenerationResult:
    return BriefingService().generate(request)
