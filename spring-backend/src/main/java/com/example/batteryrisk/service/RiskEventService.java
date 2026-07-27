package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.RiskEventDto.Coordinates;
import com.example.batteryrisk.dto.RiskEventDto.ErpView;
import com.example.batteryrisk.dto.RiskEventDto.MarketContext;
import com.example.batteryrisk.dto.RiskEventDto.OutputArtifacts;
import com.example.batteryrisk.dto.RiskEventDto.QualityCheck;
import com.example.batteryrisk.dto.RiskEventDto.RagView;
import com.example.batteryrisk.dto.RiskEventDto.RiskEvent;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 구매팀 대시보드의 허브인 리스크 이벤트 목록을 프론트 RiskEvent 계약으로 제공한다.
 *
 * <p><b>현재 상태(모델 배선 전):</b> market_context(뉴스·국가·좌표)·grade·confidence_label은
 * 뉴스 수집 + XGBoost 파이프라인 산출물인데 아직 미배선이라, 프론트 계약 검증을 위한 결정론적
 * placeholder 데이터를 반환한다({@link RealtimeAlertService}와 동일한 "계약 먼저" 방식).
 *
 * <p><b>실데이터 전환 경로(엔드포인트 계약은 불변, 이 클래스 구현부만 교체):</b>
 * <ul>
 *   <li>market_context / grade / confidence_label ← 뉴스 이벤트 + XGBoost Impact/Severity 결과</li>
 *   <li>erp_view ← {@code severity_assessments}(safety_stock_days 등) + 공급사 대체 후보</li>
 *   <li>rag_view ← {@code briefings}(contract_evidence_summary, recommended_checks)</li>
 *   <li>quality_check ← briefing의 alternative_suppliers 인증(iatf_16949/ppap) 판정</li>
 * </ul>
 * 이때 뉴스 이벤트 ↔ 자재 분석을 잇는 조인 키 확정이 선행되어야 한다(현재 briefing은 자재+공급사 기준).
 */
@Service
public class RiskEventService {

    private static final OutputArtifacts JSON_ONLY = new OutputArtifacts("json", null, true);

    private static final List<RiskEvent> PLACEHOLDER_EVENTS = List.of(
            new RiskEvent(
                    "RISK-2026-0721-001", "심각", "확정",
                    new MarketContext("data_ingestion_layer", "니켈",
                            "인도네시아 니켈 수출 관세 인상 발표로 현물가 18% 급등",
                            "ID", "인도네시아", new Coordinates(-6.2088, 106.8456)),
                    new ErpView(6, "NI-2201", List.of("공급사A", "공급사B")),
                    new QualityCheck("pass", List.of("IATF16949 인증", "PPAP 승인 이력"),
                            "대체 후보 2곳 모두 최근 감사 기준 충족"),
                    new RagView("기존 계약서 8조(가격 조정) — 원자재가 15% 이상 변동 시 재협상 조항 존재",
                            List.of("재협상 조항 발동 요건 충족 여부 확인", "단기 물량 우선 확보 조건 제시")),
                    JSON_ONLY),
            new RiskEvent(
                    "RISK-2026-0720-004", "주의", "참고",
                    new MarketContext("data_ingestion_layer", "리튬",
                            "칠레 리튬 광산 노조 파업 예고 보도, 공급 차질 가능성 제기",
                            "CL", "칠레", new Coordinates(-33.4489, -70.6693)),
                    new ErpView(21, "LI-1105", List.of("공급사C")),
                    new QualityCheck("pass", List.of("IATF16949 인증"),
                            "대체 후보 1곳 인증 유효, PPAP 이력 미확인"),
                    new RagView("기존 계약서 5조(공급 지연) — 2주 초과 지연 시 위약 조항 적용",
                            List.of("파업 현실화 시 위약 조항 적용 여부 사전 검토")),
                    JSON_ONLY),
            new RiskEvent(
                    "RISK-2026-0719-002", "주의", "경고",
                    new MarketContext("data_ingestion_layer", "코발트",
                            "콩고민주공화국 코발트 광산 관련 뉴스, 출처 교차검증 실패",
                            "CD", "콩고민주공화국", new Coordinates(-4.4419, 15.2663)),
                    new ErpView(34, "CO-3310", List.of()),
                    new QualityCheck("fail", List.of("IATF16949 인증", "PPAP 승인 이력"),
                            "단일 출처 미검증 보도 — ERP 실적 데이터와 교차 확인 불가"),
                    new RagView("해당 없음 — 검증 실패로 계약 조항 대조 보류", List.of()),
                    JSON_ONLY),
            new RiskEvent(
                    "RISK-2026-0718-011", "정상", "확정",
                    new MarketContext("data_ingestion_layer", "니켈",
                            "필리핀 니켈 광산 정기 점검 완료, 공급 일정 정상 유지",
                            "PH", "필리핀", new Coordinates(14.5995, 120.9842)),
                    new ErpView(45, "NI-2205", List.of("공급사A")),
                    new QualityCheck("pass", List.of("IATF16949 인증", "PPAP 승인 이력"),
                            "ERP 입고 실적과 계약 물량 일치"),
                    new RagView("기존 계약서 3조(정기 검수) — 별도 특이사항 없음", List.of()),
                    JSON_ONLY));

    /**
     * 리스크 이벤트 목록. grade("심각/주의/정상")로 선택 필터, limit로 최대 건수를 제한한다.
     *
     * @param grade null이면 전체, 아니면 해당 등급만
     * @param limit 1 이상 최대 반환 건수
     */
    public List<RiskEvent> list(String grade, int limit) {
        return PLACEHOLDER_EVENTS.stream()
                .filter(event -> grade == null || grade.isBlank() || event.grade().equals(grade.trim()))
                .limit(limit)
                .toList();
    }
}
