package com.example.batteryrisk.service;

import com.example.batteryrisk.domain.Analysis;
import com.example.batteryrisk.domain.RawEvent;
import com.example.batteryrisk.dto.RiskEventDto.AiRecommendationItem;
import com.example.batteryrisk.dto.RiskEventDto.Coordinates;
import com.example.batteryrisk.dto.RiskEventDto.ErpView;
import com.example.batteryrisk.dto.RiskEventDto.MarketContext;
import com.example.batteryrisk.dto.RiskEventDto.NewsFeedItem;
import com.example.batteryrisk.dto.RiskEventDto.OutputArtifacts;
import com.example.batteryrisk.dto.RiskEventDto.QualityCheck;
import com.example.batteryrisk.dto.RiskEventDto.RagView;
import com.example.batteryrisk.dto.RiskEventDto.RiskBoardItem;
import com.example.batteryrisk.dto.RiskEventDto.RiskEvent;
import com.example.batteryrisk.repository.AnalysisRepository;
import com.example.batteryrisk.repository.AnalysisSupplierRecommendationRepository;
import com.example.batteryrisk.repository.RawEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 구매팀 대시보드의 허브인 리스크 이벤트 목록을 프론트 RiskEvent 계약으로 제공한다.
 *
 * <p><b>현재 상태(실데이터 전환 1단계 완료):</b>
 * <ul>
 *   <li>{@link #riskBoard()} — <b>실데이터</b>. {@code analyses}의 완료 분석을 읽어 공개 지도 마커로 변환한다.
 *       완료 분석이 0건이면 placeholder로 폴백한다.</li>
 *   <li>{@link #list(String, int)} — 아직 placeholder. erp_view·rag_view·quality_check가
 *       {@code briefings}/{@code severity_assessments}에서 와야 하는데 {@code analyses}와 이어줄 조인 키가 없다
 *       (briefing은 자재+공급사 기준, 분석은 뉴스 기준). 조인 키 확정이 선행 과제다.</li>
 * </ul>
 *
 * <p><b>남은 전환 경로(엔드포인트 계약은 불변, 이 클래스 구현부만 교체):</b>
 * <ul>
 *   <li>erp_view ← {@code severity_assessments}(safety_stock_days 등) + 공급사 대체 후보</li>
 *   <li>rag_view ← {@code briefings}(contract_evidence_summary, recommended_checks)</li>
 *   <li>quality_check ← briefing의 alternative_suppliers 인증(iatf_16949/ppap) 판정</li>
 * </ul>
 */
@Service
public class RiskEventService {
    private static final Logger log = LoggerFactory.getLogger(RiskEventService.class);

    /** 지도에 올릴 최대 마커 수. 중복 제거 후에도 과다하면 화면이 읽히지 않는다. */
    private static final int RISK_BOARD_MAX_MARKERS = 20;

    /** 중복 제거 전에 훑을 최신 분석 건수. 같은 뉴스가 반복 분석되므로 마커 상한보다 넉넉히 잡는다. */
    private static final int RISK_BOARD_SCAN_SIZE = 200;

    /** 이 값 미만이면 근거가 약하다고 보고 confidence_label을 "경고"로 표시한다. */
    private static final double CONFIDENCE_CONFIRMED_MIN = 0.7;

    /**
     * severity → 프론트 RiskGrade("심각/주의/정상"). 프론트 타입에 없는 등급(UNKNOWN 등)은 매핑하지 않고
     * 마커에서 제외한다 — 임의로 "정상"에 욱여넣으면 판정 불능을 안전으로 오표기하게 되기 때문이다.
     */
    private static final Map<String, String> GRADE_BY_SEVERITY = Map.of(
            "CRITICAL", "심각",
            "WARNING", "주의",
            "NORMAL", "정상");

    /**
     * 자재 대분류 → 화면 표기명. 키는 FastAPI {@code extraction_inference.MaterialCategory}(8종) 및
     * {@code materials.material_category}와 같은 값이다. 매핑에 없으면 원본 값을 그대로 노출한다
     * (Literal 강제 이전에 저장된 "lithium carbonate" 같은 과거 데이터를 숨기지 않기 위함).
     */
    private static final Map<String, String> MATERIAL_NAME_KO = Map.of(
            "LITHIUM", "리튬",
            "COBALT", "코발트",
            "NICKEL", "니켈",
            "GRAPHITE", "흑연",
            "MANGANESE", "망간",
            "COPPER", "구리",
            "ALUMINUM", "알루미늄",
            "RARE_EARTH", "희토류");

    /** 지도 마커용 국가 기준점(수도 좌표). analyses에는 country_code만 있어 좌표를 여기서 보충한다. */
    private record CountryRef(String name, double lat, double lng) {}

    /**
     * 배터리 공급망에 실제로 등장하는 국가 위주. 여기에 없는 국가코드는 country_name·coordinates가 null로
     * 나가고, 프론트 GlobalRiskBoard의 isLocated() 필터가 마커에서 자동 제외한다(등급·자재 정보 자체는
     * 응답에 남는다). 새 국가가 뉴스에 등장하면 여기 한 줄만 추가하면 된다.
     */
    private static final Map<String, CountryRef> COUNTRIES = Map.ofEntries(
            Map.entry("ID", new CountryRef("인도네시아", -6.2088, 106.8456)),
            Map.entry("CL", new CountryRef("칠레", -33.4489, -70.6693)),
            Map.entry("CD", new CountryRef("콩고민주공화국", -4.4419, 15.2663)),
            Map.entry("PH", new CountryRef("필리핀", 14.5995, 120.9842)),
            Map.entry("AU", new CountryRef("호주", -35.2809, 149.1300)),
            Map.entry("CN", new CountryRef("중국", 39.9042, 116.4074)),
            Map.entry("AR", new CountryRef("아르헨티나", -34.6037, -58.3816)),
            Map.entry("BO", new CountryRef("볼리비아", -16.4897, -68.1193)),
            Map.entry("PE", new CountryRef("페루", -12.0464, -77.0428)),
            Map.entry("BR", new CountryRef("브라질", -15.7939, -47.8828)),
            Map.entry("MX", new CountryRef("멕시코", 19.4326, -99.1332)),
            Map.entry("CA", new CountryRef("캐나다", 45.4215, -75.6972)),
            Map.entry("US", new CountryRef("미국", 38.9072, -77.0369)),
            Map.entry("ZA", new CountryRef("남아프리카공화국", -25.7479, 28.2293)),
            Map.entry("ZW", new CountryRef("짐바브웨", -17.8252, 31.0335)),
            Map.entry("ZM", new CountryRef("잠비아", -15.3875, 28.3228)),
            Map.entry("MG", new CountryRef("마다가스카르", -18.8792, 47.5079)),
            Map.entry("MZ", new CountryRef("모잠비크", -25.9692, 32.5732)),
            Map.entry("TZ", new CountryRef("탄자니아", -6.7924, 39.2083)),
            Map.entry("GA", new CountryRef("가봉", 0.4162, 9.4673)),
            Map.entry("NA", new CountryRef("나미비아", -22.5609, 17.0658)),
            Map.entry("NC", new CountryRef("뉴칼레도니아", -22.2758, 166.4580)),
            Map.entry("RU", new CountryRef("러시아", 55.7558, 37.6173)),
            Map.entry("KZ", new CountryRef("카자흐스탄", 51.1694, 71.4491)),
            Map.entry("TR", new CountryRef("튀르키예", 39.9334, 32.8597)),
            Map.entry("FI", new CountryRef("핀란드", 60.1699, 24.9384)),
            Map.entry("NO", new CountryRef("노르웨이", 59.9139, 10.7522)),
            Map.entry("DE", new CountryRef("독일", 52.5200, 13.4050)),
            Map.entry("IN", new CountryRef("인도", 28.6139, 77.2090)),
            Map.entry("MY", new CountryRef("말레이시아", 3.1390, 101.6869)),
            Map.entry("MM", new CountryRef("미얀마", 16.8661, 96.1951)),
            Map.entry("JP", new CountryRef("일본", 35.6762, 139.6503)),
            Map.entry("KR", new CountryRef("대한민국", 37.5665, 126.9780)));

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
     * 등급별 기본 권고 문구. 공개 화면이므로 ERP 내부 상세 없이 등급만으로 판단 가능한 일반 조치를 쓴다
     * (프론트 mock의 RECOMMENDATION_BY_GRADE와 같은 문구 — 실 API 전환 시 화면 표현이 바뀌지 않게).
     */
    private static final Map<String, String> RECOMMENDATION_BY_GRADE = Map.of(
            "심각", "즉시 대체 조달처 검토 필요",
            "주의", "대체 조달처 사전 확보 권고",
            "정상", "정기 모니터링 유지");

    /** 공개 뉴스 속보에 올릴 최대 건수. */
    private static final int NEWS_FEED_LIMIT = 20;

    /** 중복 제거·자재 필터 전에 훑을 최신 뉴스 건수. 노출 상한보다 넉넉히 잡는다. */
    private static final int NEWS_FEED_SCAN_SIZE = 200;

    /** 사용자에게 보이는 날짜는 서비스 기준 시간대로 표기한다(collected_at은 UTC Instant). */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 분석이 붙지 않은 뉴스의 자재를 제목에서 추정하기 위한 키워드.
     * FastAPI {@code extraction_inference._MATERIAL_KEYWORDS}를 화면 표기용으로 옮긴 것이며,
     * <b>표시 목적에만 쓴다</b> — F9 매칭·ERP 조인 같은 판단에는 쓰지 않으므로 두 곳이 다소 어긋나도
     * 기능이 깨지지 않는다. 자재를 추가할 때 이쪽을 잊어도 "기타"로 떨어질 뿐이다.
     */
    private static final Map<String, List<String>> MATERIAL_KEYWORDS = Map.of(
            "니켈", List.of("nickel", "니켈"),
            "코발트", List.of("cobalt", "코발트"),
            "리튬", List.of("lithium", "리튬"),
            "흑연", List.of("graphite", "흑연"),
            "망간", List.of("manganese", "망간"),
            "구리", List.of("copper", "구리"),
            "알루미늄", List.of("aluminum", "aluminium", "알루미늄"),
            "희토류", List.of("rare earth", "rare-earth", "희토류"));

    /** 키워드로도 자재를 못 정한 뉴스의 표기. 화면에서 빈 칸이 보이지 않게 한다. */
    private static final String UNCLASSIFIED_MATERIAL = "기타";

    private final AnalysisRepository analysisRepository;
    private final AnalysisSupplierRecommendationRepository supplierRecommendationRepository;
    private final RawEventRepository rawEventRepository;

    public RiskEventService(
            AnalysisRepository analysisRepository,
            AnalysisSupplierRecommendationRepository supplierRecommendationRepository,
            RawEventRepository rawEventRepository) {
        this.analysisRepository = analysisRepository;
        this.supplierRecommendationRepository = supplierRecommendationRepository;
        this.rawEventRepository = rawEventRepository;
    }

    /**
     * 리스크 이벤트 목록. grade("심각/주의/정상")로 선택 필터, limit로 최대 건수를 제한한다.
     *
     * <p>아직 placeholder다 — 클래스 javadoc의 조인 키 선행 과제 참고.
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

    /**
     * 비로그인 공개 지도용 안전 subset. erp_view/quality_check/rag_view/output_artifacts는 제외하고
     * 지도 마커에 필요한 등급·신뢰도·국가·좌표만 내려준다.
     *
     * <p>완료된 실제 분석({@code analyses})을 최신순으로 읽어 <b>(국가, 자재) 조합당 1건</b>만 남긴다.
     * 같은 뉴스를 반복 분석하면 동일 좌표에 마커가 겹쳐 찍혀 지도를 읽을 수 없기 때문이다.
     *
     * <p>실데이터가 0건이면 placeholder로 폴백한다 — 공개 화면이라 빈 지도보다 계약 형태가 살아 있는 화면을
     * 유지하는 편이 낫고, 시연 도중 DB가 비어도 화면이 깨지지 않는다.
     */
    public List<RiskBoardItem> riskBoard() {
        List<Analysis> candidates =
                analysisRepository.findRiskBoardCandidates(PageRequest.of(0, RISK_BOARD_SCAN_SIZE));

        Map<String, RiskBoardItem> markers = new LinkedHashMap<>();
        for (Analysis analysis : candidates) {
            String grade = GRADE_BY_SEVERITY.get(analysis.getSeverity());
            if (grade == null) {
                continue;
            }
            String key = analysis.getCountryCode() + "|" + analysis.getMaterialCategory();
            if (markers.containsKey(key)) {
                continue;
            }
            if (markers.size() >= RISK_BOARD_MAX_MARKERS) {
                log.debug("공개 리스크 보드 마커 상한({})에 도달해 이후 후보를 생략합니다.", RISK_BOARD_MAX_MARKERS);
                break;
            }
            markers.put(key, toBoardItem(analysis, grade));
        }

        if (markers.isEmpty()) {
            log.info("공개 리스크 보드에 올릴 완료 분석이 없어 placeholder로 폴백합니다. (후보 {}건)", candidates.size());
            return PLACEHOLDER_EVENTS.stream().map(RiskEventService::toBoardItem).toList();
        }
        return List.copyOf(markers.values());
    }

    /**
     * 비로그인 공개 화면의 AI 기반 권고 조치 리스트.
     *
     * <p><b>{@link #riskBoard()}와 같은 분석 집합에서 파생한다</b> — 지도와 권고 리스트가 서로 다른 자재를
     * 가리키는 일이 구조적으로 생기지 않게 하기 위함이다(둘을 따로 조회하면 정렬·상한·중복 제거 규칙이
     * 어긋나는 순간 화면이 모순된다). 따라서 riskBoard()가 placeholder로 폴백하면 이쪽도 함께 폴백한다.
     *
     * <p>문구는 등급 기반 일반 조치이며, F9가 대체 공급사를 이미 뽑아둔 분석이면 <b>후보 수만</b> 덧붙인다.
     * 공개 화면이라 공급사명·재고일수 등 ERP 내부 상세는 노출하지 않는다.
     */
    public List<AiRecommendationItem> aiRecommendations() {
        List<RiskBoardItem> board = riskBoard();
        Map<String, Long> candidateCounts = countSupplierCandidates(board);

        return board.stream()
                .map(item -> new AiRecommendationItem(
                        item.riskEventId(), item.material(), item.grade(), item.confidenceLabel(),
                        recommendationText(item.grade(), candidateCounts.get(item.riskEventId()))))
                .toList();
    }

    /**
     * 분석 id별 F9 대체 공급사 후보 수. placeholder 폴백 시 risk_event_id는 UUID가 아니므로(RISK-YYYY-...)
     * 파싱 가능한 것만 조회 대상으로 삼고, 조회 대상이 없으면 쿼리 자체를 생략한다.
     */
    private Map<String, Long> countSupplierCandidates(List<RiskBoardItem> board) {
        List<UUID> analysisIds = board.stream()
                .map(item -> parseUuidOrNull(item.riskEventId()))
                .filter(Objects::nonNull)
                .toList();
        if (analysisIds.isEmpty()) {
            return Map.of();
        }
        return supplierRecommendationRepository.findByAnalysisIdIn(analysisIds).stream()
                .collect(Collectors.groupingBy(
                        recommendation -> recommendation.getAnalysisId().toString(), Collectors.counting()));
    }

    private static UUID parseUuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String recommendationText(String grade, Long candidateCount) {
        String base = RECOMMENDATION_BY_GRADE.getOrDefault(grade, "정기 모니터링 유지");
        if (candidateCount == null || candidateCount == 0) {
            return base;
        }
        return base + " — 대체 후보 " + candidateCount + "곳 확인됨";
    }

    /**
     * 비로그인 공개 화면의 실시간 뉴스 속보. 수집 원본({@code raw_events})의 최신 뉴스를 그대로 보여준다.
     *
     * <p>지도·권고 리스트가 <b>분석 결과</b>를 보여주는 것과 달리 이쪽은 <b>수집 원본</b>이라, 분석(F3)이
     * 돌지 않아도 화면이 채워진다. 덕분에 {@code app.collection.analysis-enabled=false}로 두면
     * LLM 호출 없이 뉴스만 수집해 이 패널을 운영할 수 있다.
     *
     * <p>자재·신뢰도는 분석이 붙은 뉴스면 분석 결과에서, 아니면 제목 키워드에서 파생한다.
     * 수집된 뉴스가 0건이면 placeholder로 폴백한다(지도·권고 리스트와 같은 방침).
     */
    public List<NewsFeedItem> newsFeed() {
        List<RawEvent> events = rawEventRepository.findByDataTypeAndTitleIsNotNullOrderByCollectedAtDesc(
                "NEWS", PageRequest.of(0, NEWS_FEED_SCAN_SIZE));
        Map<UUID, Analysis> analysesById = loadAnalyses(events);

        // 같은 기사가 GDELT GlobalEventID만 다른 채로 여러 번 들어오므로 제목 기준으로 한 번만 남긴다.
        Map<String, NewsFeedItem> unique = new LinkedHashMap<>();
        for (RawEvent event : events) {
            unique.putIfAbsent(
                    event.getTitle().trim(),
                    toNewsFeedItem(event, analysesById.get(event.getTriggeredAnalysisId())));
        }

        // 자재가 특정된 뉴스만 노출한다. GDELT 트리아지는 생산국 이벤트를 폭넓게 통과시켜서 공급망과
        // 무관한 기사(정치·사건사고 등)가 상당수 섞여 들어오는데, "공급망 뉴스 속보" 패널에 그런 기사를
        // 올리면 화면이 오히려 망가진다 — 실측에서 19건 중 자재 매칭 0건이 나온 적이 있다.
        List<NewsFeedItem> supplyChainNews = unique.values().stream()
                .filter(item -> !UNCLASSIFIED_MATERIAL.equals(item.material()))
                .limit(NEWS_FEED_LIMIT)
                .toList();

        // 한 건도 없으면 무관한 기사를 채우는 대신 placeholder로 폴백한다(지도·권고 리스트와 같은 방침).
        // 공급망 뉴스가 수집되는 즉시 자동으로 실데이터로 전환된다.
        if (supplyChainNews.isEmpty()) {
            log.info("자재가 특정된 뉴스가 없어 공개 뉴스 속보를 placeholder로 폴백합니다. (수집 뉴스 {}건)", unique.size());
            return PLACEHOLDER_EVENTS.stream().map(RiskEventService::toNewsFeedItem).toList();
        }
        return supplyChainNews;
    }

    /** 뉴스에 붙은 분석을 한 번에 조회한다(분석이 없는 뉴스만 있으면 쿼리를 생략). */
    private Map<UUID, Analysis> loadAnalyses(List<RawEvent> events) {
        List<UUID> analysisIds = events.stream()
                .map(RawEvent::getTriggeredAnalysisId)
                .filter(Objects::nonNull)
                .toList();
        if (analysisIds.isEmpty()) {
            return Map.of();
        }
        return analysisRepository.findAllById(analysisIds).stream()
                .collect(Collectors.toMap(Analysis::getAnalysisId, analysis -> analysis));
    }

    private static NewsFeedItem toNewsFeedItem(RawEvent event, Analysis analysis) {
        String material = analysis != null && analysis.getMaterialCategory() != null
                ? MATERIAL_NAME_KO.getOrDefault(analysis.getMaterialCategory(), analysis.getMaterialCategory())
                : guessMaterial(event.getTitle(), event.getContent());
        return new NewsFeedItem(
                analysis != null ? analysis.getAnalysisId().toString() : "RAW-" + event.getId(),
                LocalDate.ofInstant(event.getCollectedAt(), DISPLAY_ZONE).toString(),
                material,
                event.getSource(),
                event.getTitle(),
                analysis != null ? confidenceLabel(analysis) : "참고");
    }

    /**
     * 분석이 없는 뉴스의 자재 추정. FastAPI {@code MockExtractionInference}와 같이 <b>제목+본문</b>을 본다 —
     * 제목만 보면 놓치는 기사가 많다(실측: 수집 150건 중 제목 매칭 0건, 본문 매칭 4건).
     * 여러 자재가 걸리면 먼저 매칭된 하나만 표기한다(속보 한 줄 표기용).
     */
    private static String guessMaterial(String title, String content) {
        String text = ((title == null ? "" : title) + " " + (content == null ? "" : content)).toLowerCase();
        return MATERIAL_KEYWORDS.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(text::contains))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(UNCLASSIFIED_MATERIAL);
    }

    /** placeholder RiskEvent → 뉴스 속보 변환(수집 뉴스 0건 폴백 전용). */
    private static NewsFeedItem toNewsFeedItem(RiskEvent event) {
        return new NewsFeedItem(
                event.riskEventId(),
                parsePlaceholderDate(event.riskEventId()),
                event.marketContext().material(),
                event.marketContext().source(),
                event.marketContext().eventSummary(),
                event.confidenceLabel());
    }

    /** placeholder id 형식 "RISK-yyyy-MMdd-nnn"에서 날짜를 뽑는다. 형식이 다르면 오늘 날짜로 둔다. */
    private static String parsePlaceholderDate(String riskEventId) {
        String[] parts = riskEventId.split("-");
        if (parts.length >= 3 && parts[1].length() == 4 && parts[2].length() == 4) {
            return parts[1] + "-" + parts[2].substring(0, 2) + "-" + parts[2].substring(2);
        }
        return LocalDate.now(DISPLAY_ZONE).toString();
    }

    private static RiskBoardItem toBoardItem(Analysis analysis, String grade) {
        CountryRef country = COUNTRIES.get(analysis.getCountryCode());
        String category = analysis.getMaterialCategory();
        return new RiskBoardItem(
                analysis.getAnalysisId().toString(),
                MATERIAL_NAME_KO.getOrDefault(category, category),
                grade,
                confidenceLabel(analysis),
                // summary_kr은 FastAPI 추출 결과라 analyses에 저장되지 않는다. 요약을 노출하려면
                // analyses에 summary 컬럼 추가가 선행되어야 하므로, 지금은 뉴스 제목을 쓴다.
                analysis.getEventTitle(),
                analysis.getCountryCode(),
                country != null ? country.name() : null,
                country != null ? new Coordinates(country.lat(), country.lng()) : null);
    }

    /**
     * 프론트 ConfidenceLabel("확정/경고/참고") 판정. analyses에 대응 컬럼이 없어 여기서 규칙으로 파생한다.
     *
     * <ul>
     *   <li>mock=true — 모델 결과를 확정으로 제시할 수 없으므로 "참고"</li>
     *   <li>confidence 미상 또는 0.7 미만 — 근거가 약하므로 "경고"</li>
     *   <li>그 외 — "확정"</li>
     * </ul>
     *
     * <p><b>주의 — 현재는 사실상 항상 "참고"다.</b> {@code analyses.mock}은 개별 분석이 mock이었는지가 아니라
     * FastAPI의 전역 설정 {@code MOCK_MODE}를 그대로 받아 저장한 값인데, docker-compose가 이를 "true"로
     * 고정하고 있어 실제 GPT 추출이 돌아도 true로 남는다. 등급을 과대 표기하는 쪽보다 보수적으로 "참고"에
     * 머무는 편이 안전해 규칙은 그대로 두되, 세 라벨을 실제로 구분하려면 둘 중 하나가 선행되어야 한다:
     * (1) MOCK_MODE를 실제 운영값으로 내리거나, (2) 분석 단위 mock 여부(extraction.mock)를 따로 저장.
     */
    private static String confidenceLabel(Analysis analysis) {
        if (analysis.isMock()) {
            return "참고";
        }
        Double confidence = analysis.getConfidence();
        if (confidence == null || confidence < CONFIDENCE_CONFIRMED_MIN) {
            return "경고";
        }
        return "확정";
    }

    /** placeholder RiskEvent → 공개 subset 변환(실데이터 0건 폴백 전용). */
    private static RiskBoardItem toBoardItem(RiskEvent event) {
        return new RiskBoardItem(
                event.riskEventId(),
                event.marketContext().material(),
                event.grade(),
                event.confidenceLabel(),
                event.marketContext().eventSummary(),
                event.marketContext().countryCode(),
                event.marketContext().countryName(),
                event.marketContext().coordinates());
    }
}
