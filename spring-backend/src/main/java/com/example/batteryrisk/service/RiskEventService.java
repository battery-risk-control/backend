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
import com.example.batteryrisk.repository.ProcurementRiskRepository;
import com.example.batteryrisk.repository.RawEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * 뉴스 속보의 "경고" 판정 기준이 되는 외부신호(severity) 점수.
     *
     * <p>FastAPI {@code severity_engine.WARNING_MIN}과 같은 값이다 — 그쪽이 이 점수로 WARNING 등급을
     * 매기므로, 화면에서 "주의할 만하다"고 표시하는 기준도 같아야 한다. 그쪽 값을 바꾸면 여기도 바꾼다
     * (실측 4,081건의 70th 퍼센타일로 캘리브레이션된 값이라 임의로 조정할 성질이 아니다).
     */
    private static final double EXTERNAL_SIGNAL_WARNING_MIN = 67.0;

    /**
     * severity → 프론트 RiskGrade("심각/주의/정상"). 프론트 타입에 없는 등급(UNKNOWN 등)은 매핑하지 않고
     * 마커에서 제외한다 — 임의로 "정상"에 욱여넣으면 판정 불능을 안전으로 오표기하게 되기 때문이다.
     */
    private static final Map<String, String> GRADE_BY_SEVERITY = Map.of(
            "CRITICAL", "심각",
            "WARNING", "주의",
            "NORMAL", "정상");

    /**
     * severity(외부신호) 또는 procurement_risk_level(멀티에이전트 종합) → 화면 등급.
     *
     * <p>두 축이 같은 어휘(CRITICAL/WARNING/NORMAL)를 쓰므로 변환도 하나만 둔다 — 표를 두 벌로
     * 나누면 같은 뉴스가 지도·속보·모니터링 화면에서 서로 다른 등급으로 보이게 된다.
     * 매핑에 없는 값(UNKNOWN 등)은 null이며, 호출부가 "판정 불능"으로 다룬다.
     */
    static String gradeOf(String severityOrRiskLevel) {
        return severityOrRiskLevel == null ? null : GRADE_BY_SEVERITY.get(severityOrRiskLevel);
    }

    /** 국가 기준점 좌표(수도). 국가 미상이거나 좌표 표에 없으면 null — 화면이 좌표 블록을 숨긴다. */
    static Coordinates coordinatesOf(String countryCode) {
        CountryRef ref = countryCode == null ? null : COUNTRIES.get(countryCode);
        return ref == null ? null : new Coordinates(ref.lat(), ref.lng());
    }

    /**
     * 자재 대분류 → 화면 표기명. 키는 FastAPI {@code extraction_inference.MaterialCategory}(8종) 및
     * {@code materials.material_category}와 같은 값이다. 매핑에 없으면 원본 값을 그대로 노출한다
     * (Literal 강제 이전에 저장된 "lithium carbonate" 같은 과거 데이터를 숨기지 않기 위함).
     *
     * <p>같은 공개 대시보드를 그리는 {@link MarketPriceService}도 이 표기명을 쓴다 — 지도·속보와
     * 가격 패널이 같은 자재를 다른 이름으로 부르지 않도록 한 곳에서만 정의한다(package-private).
     */
    static final Map<String, String> MATERIAL_NAME_KO = Map.of(
            "LITHIUM", "리튬",
            "COBALT", "코발트",
            "NICKEL", "니켈",
            "GRAPHITE", "흑연",
            "MANGANESE", "망간",
            "COPPER", "구리",
            "ALUMINUM", "알루미늄",
            "RARE_EARTH", "희토류");

    /**
     * 국가코드 → 한글 국가명. 좌표 표({@link #COUNTRIES})가 이미 93개국의 한글명을 갖고 있어
     * 별도 표를 만들지 않는다 — 두 벌이 되면 지도와 다른 패널이 같은 나라를 다르게 부르게 된다.
     *
     * <p>표에 없는 코드는 코드 자체를 돌려준다(빈 문자열보다 낫다 — 화면에 무엇이라도 남는다).
     * {@link MarketPriceService}의 가격 추이 "국가·지역" 필터가 쓴다.
     */
    static String countryNameKo(String countryCode) {
        // 수집 원본에는 국가를 특정하지 못한 기사가 흔하다(country_code IS NULL).
        // COUNTRIES는 Map.of라 get(null)이 NPE를 던지므로 여기서 먼저 막는다.
        if (countryCode == null) {
            return null;
        }
        CountryRef ref = COUNTRIES.get(countryCode);
        return ref == null ? countryCode : ref.name();
    }

    /** 지도 마커용 국가 기준점(수도 좌표). analyses에는 country_code만 있어 좌표를 여기서 보충한다. */
    private record CountryRef(String name, double lat, double lng) {}

    /**
     * 지도 마커 좌표. 여기에 없는 국가코드는 country_name·coordinates가 null로 나가고, 프론트
     * GlobalRiskBoard의 isLocated() 필터가 마커에서 자동 제외한다(등급·자재 정보 자체는 응답에 남는다).
     *
     * <p><b>{@code GdeltEventArchiveService.ISO2_TO_FIPS}의 모든 국가를 포함해야 한다.</b> 좌표가 없어도
     * {@link #riskBoard()}의 마커 상한({@code RISK_BOARD_MAX_MARKERS}) 슬롯은 똑같이 차지하기 때문에,
     * 수집 대상국이 좌표 테이블보다 많으면 좌표 없는 항목이 상한을 먼저 채워 인도네시아·칠레 같은 실제
     * 공급망 국가가 지도에서 밀려난다. 상한 쪽을 건드려 해결하면 안 된다 — {@link #aiRecommendations()}가
     * 같은 결과를 재사용하므로 좌표가 필요 없는 권고 리스트까지 함께 잘려나간다.
     *
     * <p>수집 대상국(83) ∪ 수동 분석·과거 임포트 경로로만 들어오는 국가(10) = 93개국.
     * ISO2_TO_FIPS에 국가를 추가하면 여기에도 반드시 한 줄 추가할 것.
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
            Map.entry("KR", new CountryRef("대한민국", 37.5665, 126.9780)),

            // 아래는 ISO2_TO_FIPS(수집 대상 83개국)에 있으나 좌표가 없던 60개국. 값은 각국 수도 기준점.
            // 아시아·오세아니아
            Map.entry("KP", new CountryRef("북한", 39.0392, 125.7625)),
            Map.entry("VN", new CountryRef("베트남", 21.0285, 105.8542)),
            Map.entry("TH", new CountryRef("태국", 13.7563, 100.5018)),
            Map.entry("KH", new CountryRef("캄보디아", 11.5564, 104.9282)),
            Map.entry("TW", new CountryRef("대만", 25.0330, 121.5654)),
            Map.entry("PK", new CountryRef("파키스탄", 33.6844, 73.0479)),
            Map.entry("BD", new CountryRef("방글라데시", 23.8103, 90.4125)),
            Map.entry("AF", new CountryRef("아프가니스탄", 34.5553, 69.2075)),
            Map.entry("NZ", new CountryRef("뉴질랜드", -41.2865, 174.7762)),
            Map.entry("PG", new CountryRef("파푸아뉴기니", -9.4438, 147.1803)),
            Map.entry("FJ", new CountryRef("피지", -18.1416, 178.4419)),
            // 중동
            Map.entry("BH", new CountryRef("바레인", 26.2285, 50.5860)),
            Map.entry("SA", new CountryRef("사우디아라비아", 24.7136, 46.6753)),
            Map.entry("AE", new CountryRef("아랍에미리트", 24.4539, 54.3773)),
            Map.entry("QA", new CountryRef("카타르", 25.2854, 51.5310)),
            Map.entry("KW", new CountryRef("쿠웨이트", 29.3759, 47.9774)),
            Map.entry("OM", new CountryRef("오만", 23.5880, 58.3829)),
            Map.entry("YE", new CountryRef("예멘", 15.3694, 44.1910)),
            Map.entry("JO", new CountryRef("요르단", 31.9454, 35.9284)),
            Map.entry("LB", new CountryRef("레바논", 33.8938, 35.5018)),
            Map.entry("SY", new CountryRef("시리아", 33.5138, 36.2765)),
            Map.entry("IQ", new CountryRef("이라크", 33.3152, 44.3661)),
            Map.entry("IR", new CountryRef("이란", 35.6892, 51.3890)),
            Map.entry("IL", new CountryRef("이스라엘", 31.7683, 35.2137)),
            Map.entry("CY", new CountryRef("키프로스", 35.1856, 33.3823)),
            // 유럽
            Map.entry("GB", new CountryRef("영국", 51.5074, -0.1278)),
            Map.entry("FR", new CountryRef("프랑스", 48.8566, 2.3522)),
            Map.entry("ES", new CountryRef("스페인", 40.4168, -3.7038)),
            Map.entry("IT", new CountryRef("이탈리아", 41.9028, 12.4964)),
            Map.entry("NL", new CountryRef("네덜란드", 52.3676, 4.9041)),
            Map.entry("SE", new CountryRef("스웨덴", 59.3293, 18.0686)),
            Map.entry("PL", new CountryRef("폴란드", 52.2297, 21.0122)),
            Map.entry("CZ", new CountryRef("체코", 50.0755, 14.4378)),
            Map.entry("CH", new CountryRef("스위스", 46.9480, 7.4474)),
            Map.entry("RO", new CountryRef("루마니아", 44.4268, 26.1025)),
            Map.entry("GR", new CountryRef("그리스", 37.9838, 23.7275)),
            Map.entry("IE", new CountryRef("아일랜드", 53.3498, -6.2603)),
            Map.entry("MK", new CountryRef("북마케도니아", 41.9973, 21.4280)),
            Map.entry("MT", new CountryRef("몰타", 35.8989, 14.5146)),
            Map.entry("UA", new CountryRef("우크라이나", 50.4501, 30.5234)),
            // 아프리카
            Map.entry("EG", new CountryRef("이집트", 30.0444, 31.2357)),
            Map.entry("MA", new CountryRef("모로코", 34.0209, -6.8416)),
            Map.entry("DZ", new CountryRef("알제리", 36.7538, 3.0588)),
            Map.entry("SD", new CountryRef("수단", 15.5007, 32.5599)),
            Map.entry("ET", new CountryRef("에티오피아", 9.0320, 38.7469)),
            Map.entry("KE", new CountryRef("케냐", -1.2921, 36.8219)),
            Map.entry("NG", new CountryRef("나이지리아", 9.0765, 7.3986)),
            Map.entry("NE", new CountryRef("니제르", 13.5117, 2.1251)),
            Map.entry("ML", new CountryRef("말리", 12.6392, -8.0029)),
            Map.entry("SN", new CountryRef("세네갈", 14.7167, -17.4677)),
            Map.entry("GH", new CountryRef("가나", 5.6037, -0.1870)),
            Map.entry("MW", new CountryRef("말라위", -13.9626, 33.7741)),
            Map.entry("GQ", new CountryRef("적도기니", 3.7523, 8.7742)),
            // 아메리카
            Map.entry("CO", new CountryRef("콜롬비아", 4.7110, -74.0721)),
            Map.entry("VE", new CountryRef("베네수엘라", 10.4806, -66.9036)),
            Map.entry("GY", new CountryRef("가이아나", 6.8013, -58.1551)),
            Map.entry("CU", new CountryRef("쿠바", 23.1136, -82.3666)),
            Map.entry("HT", new CountryRef("아이티", 18.5944, -72.3074)),
            Map.entry("JM", new CountryRef("자메이카", 17.9714, -76.7931)),
            Map.entry("BZ", new CountryRef("벨리즈", 17.2510, -88.7590)));

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

    /** 공개 뉴스 속보에 올릴 기본 건수. 호출자가 limit을 주지 않으면 이 값을 쓴다. */
    private static final int NEWS_FEED_LIMIT = 20;

    /**
     * limit 상한. 마퀴는 한 자릿수, 목록은 수십 건이면 충분한데 상한이 없으면 스캔 구간 전체가
     * 그대로 나가버린다 — 공개 API라 호출자를 신뢰할 수 없으므로 서버에서 자른다.
     */
    private static final int NEWS_FEED_MAX_LIMIT = 100;

    /** 중복 제거·자재 필터 전에 훑을 최신 뉴스 건수. 노출 상한보다 넉넉히 잡는다. */
    private static final int NEWS_FEED_SCAN_SIZE = 200;

    /** 사용자에게 보이는 날짜는 서비스 기준 시간대로 표기한다(collected_at은 UTC Instant). */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 분석이 붙지 않은 뉴스의 자재를 제목·본문에서 추정하기 위한 키워드.
     * FastAPI {@code extraction_inference._MATERIAL_KEYWORDS}를 옮긴 것이며 키는 자재 대분류
     * ({@code materials.material_category})와 같은 값이다.
     *
     * <p>주 용도는 <b>화면 표기</b>다 — 자재를 추가할 때 이쪽을 잊어도 "기타"로 떨어질 뿐이다.
     * 다만 리스크 모니터링의 "ERP·계약 영향 분석"은 분석에 {@code material_category}가 없을 때
     * 이 추정값으로 ERP 자재를 고르므로(F3가 NORMAL 뉴스에는 카테고리를 붙이지 않는다),
     * 키를 한글 표기명이 아니라 대분류 코드로 둔다.
     */
    static final Map<String, List<String>> MATERIAL_KEYWORDS = Map.of(
            "NICKEL", List.of("nickel", "니켈"),
            "COBALT", List.of("cobalt", "코발트"),
            "LITHIUM", List.of("lithium", "리튬"),
            "GRAPHITE", List.of("graphite", "흑연"),
            "MANGANESE", List.of("manganese", "망간"),
            "COPPER", List.of("copper", "구리"),
            "ALUMINUM", List.of("aluminum", "aluminium", "알루미늄"),
            "RARE_EARTH", List.of("rare earth", "rare-earth", "희토류"));

    /** 키워드로도 자재를 못 정한 뉴스의 표기. 화면에서 빈 칸이 보이지 않게 한다. */
    static final String UNCLASSIFIED_MATERIAL = "기타";

    /** 자재 대분류 → 화면 표기명. 대분류가 없으면 "기타", 표에 없으면 원본 값을 그대로 쓴다. */
    static String materialNameKo(String materialCategory) {
        return materialCategory == null
                ? UNCLASSIFIED_MATERIAL
                : MATERIAL_NAME_KO.getOrDefault(materialCategory, materialCategory);
    }

    private final AnalysisRepository analysisRepository;
    private final AnalysisSupplierRecommendationRepository supplierRecommendationRepository;
    private final RawEventRepository rawEventRepository;
    private final ProcurementRiskRepository procurementRiskRepository;

    public RiskEventService(
            AnalysisRepository analysisRepository,
            AnalysisSupplierRecommendationRepository supplierRecommendationRepository,
            RawEventRepository rawEventRepository,
            ProcurementRiskRepository procurementRiskRepository) {
        this.analysisRepository = analysisRepository;
        this.supplierRecommendationRepository = supplierRecommendationRepository;
        this.rawEventRepository = rawEventRepository;
        this.procurementRiskRepository = procurementRiskRepository;
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

        // 뉴스 속보와 같은 두 축을 붙인다 — 지도와 속보가 같은 뉴스를 다른 등급·다른 신뢰도로
        // 부르면 화면끼리 모순된다.
        Map<UUID, String> riskLevelsByAnalysisId = procurementRiskRepository
                .findLatestRiskLevelsByAnalysisIds(
                        candidates.stream().map(Analysis::getAnalysisId).collect(Collectors.toSet()));
        Map<UUID, String> koreanTitles = loadKoreanTitles(candidates);

        Map<String, RiskBoardItem> markers = new LinkedHashMap<>();
        for (Analysis analysis : candidates) {
            // 자재 미상 분석은 findRiskBoardCandidates가 이미 걸러준다(materialCategory IS NOT NULL).
            // 이 필터가 없으면 GDELT 트리아지가 생산국 기준으로 통과시킨 공급망 무관 기사가 마커로
            // 올라온다 — 실측에서 국가가 붙은 19건 중 13건이 자재 미상이었고 그중 11건은
            // NOT_RELEVANT("Colonel Sanders' bow tie" 같은 기사)였다.
            String riskLevel = riskLevelsByAnalysisId.get(analysis.getAnalysisId());
            // 마커는 색이 있어야 그려지므로 등급이 반드시 필요하다. 멀티에이전트 종합 등급이
            // 있으면 그것을, 아직 없으면 외부신호(severity) 등급을 쓴다. "어디까지 검증된 값인가"는
            // 등급이 아니라 신뢰도 배지(확정/경고/참고)가 말해준다.
            String grade = gradeOf(riskLevel != null ? riskLevel : analysis.getSeverity());
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
            markers.put(key, toBoardItem(
                    analysis, grade,
                    newsConfidenceLabel(analysis, riskLevel),
                    koreanTitles.get(analysis.getAnalysisId())));
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
     *
     * <p>같은 응답이 <b>마퀴(한 줄 속보)와 뉴스 목록 양쪽</b>을 채운다 — 같은 자료를 두 API로 나누면
     * 두 화면이 서로 다른 기사를 가리키게 되므로, 노출 건수만 {@code limit}으로 조절한다.
     *
     * @param countryCode 지도 마커 클릭 시 그 국가로 좁힌다. null/공백이면 전체.
     * @param limit       노출 건수. 1..{@value #NEWS_FEED_MAX_LIMIT}로 잘린다.
     */
    public List<NewsFeedItem> newsFeed(String countryCode, int limit) {
        int cappedLimit = Math.max(1, Math.min(limit, NEWS_FEED_MAX_LIMIT));
        String country = countryCode == null || countryCode.isBlank()
                ? null
                : countryCode.trim().toUpperCase(Locale.ROOT);

        PageRequest scanWindow = PageRequest.of(0, NEWS_FEED_SCAN_SIZE);
        List<RawEvent> events = country == null
                ? rawEventRepository.findByDataTypeAndTitleIsNotNullOrderByCollectedAtDesc("NEWS", scanWindow)
                : rawEventRepository.findByDataTypeAndCountryCodeAndTitleIsNotNullOrderByCollectedAtDesc(
                        "NEWS", country, scanWindow);
        Map<UUID, Analysis> analysesById = loadAnalyses(events);
        // 멀티에이전트(ERP·계약)까지 통과해 종합 위험도가 나온 뉴스만 등급 배지를 받는다.
        Map<UUID, String> riskLevelsByAnalysisId =
                procurementRiskRepository.findLatestRiskLevelsByAnalysisIds(analysesById.keySet());

        // 같은 기사가 GDELT GlobalEventID만 다른 채로 여러 번 들어오므로 제목 기준으로 한 번만 남긴다.
        Map<String, NewsFeedItem> unique = new LinkedHashMap<>();
        for (RawEvent event : events) {
            Analysis analysis = analysisOf(analysesById, event);
            String riskLevel = analysis == null
                    ? null
                    : riskLevelsByAnalysisId.get(analysis.getAnalysisId());
            unique.putIfAbsent(event.getTitle().trim(), toNewsFeedItem(event, analysis, riskLevel));
        }

        // 자재가 특정된 뉴스만 노출한다. GDELT 트리아지는 생산국 이벤트를 폭넓게 통과시켜서 공급망과
        // 무관한 기사(정치·사건사고 등)가 상당수 섞여 들어오는데, "공급망 뉴스 속보" 패널에 그런 기사를
        // 올리면 화면이 오히려 망가진다 — 실측에서 19건 중 자재 매칭 0건이 나온 적이 있다.
        List<NewsFeedItem> supplyChainNews = unique.values().stream()
                .filter(item -> !UNCLASSIFIED_MATERIAL.equals(item.material()))
                .limit(cappedLimit)
                .toList();

        // 국가로 좁힌 요청은 폴백하지 않는다. placeholder는 인도네시아·칠레 등 특정 국가 기사라서,
        // 칠레를 클릭했는데 인도네시아 placeholder가 뜨면 지도와 패널이 다른 나라를 가리키게 된다.
        // 해당 국가 뉴스가 없다는 사실을 빈 배열로 그대로 전달하는 편이 정확하다.
        if (supplyChainNews.isEmpty() && country != null) {
            log.info("국가 {}에 해당하는 공급망 뉴스가 없습니다. (스캔 {}건)", country, unique.size());
            return List.of();
        }

        // 한 건도 없으면 무관한 기사를 채우는 대신 placeholder로 폴백한다(지도·권고 리스트와 같은 방침).
        // 공급망 뉴스가 수집되는 즉시 자동으로 실데이터로 전환된다.
        if (supplyChainNews.isEmpty()) {
            log.info("자재가 특정된 뉴스가 없어 공개 뉴스 속보를 placeholder로 폴백합니다. (수집 뉴스 {}건)", unique.size());
            return PLACEHOLDER_EVENTS.stream()
                    .map(RiskEventService::toNewsFeedItem)
                    .limit(cappedLimit)
                    .toList();
        }
        return supplyChainNews;
    }

    /**
     * 뉴스에 붙은 분석. 분석이 안 붙은 뉴스는 null이다.
     *
     * <p>{@code map.get(event.getTriggeredAnalysisId())}로 바로 부르면 안 된다 — 스캔 구간의 모든
     * 뉴스에 분석이 없으면 {@link #loadAnalyses}가 {@code Map.of()}(불변 맵)를 돌려주는데, 거기에
     * null 키로 get을 하면 NPE가 난다. 그 조합은 예외 상황이 아니라 <b>기본 운영 모드</b>다
     * ({@code app.collection.analysis-enabled=false}면 수집만 하고 분석은 돌지 않는다).
     */
    private static Analysis analysisOf(Map<UUID, Analysis> analysesById, RawEvent event) {
        UUID analysisId = event.getTriggeredAnalysisId();
        return analysisId == null ? null : analysesById.get(analysisId);
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

    /**
     * 수집 원본 + 분석 결과 → 속보 1건.
     *
     * <p><b>등급(grade)과 신뢰도(confidence_label)는 파이프라인의 "어디까지 갔는가"를 나타낸다.</b>
     * 값의 크기가 아니라 도달 단계다:
     *
     * <ul>
     *   <li><b>확정</b> — 멀티에이전트(ERP·계약)까지 통과해 종합 위험도가 나온 뉴스.
     *       이때만 심각/주의/정상 배지를 붙인다.</li>
     *   <li><b>경고</b> — 외부신호(severity) 점수는 나왔지만 멀티에이전트를 아직 안 거친 뉴스 중,
     *       점수가 {@link #EXTERNAL_SIGNAL_WARNING_MIN} 이상인 것. 근거가 한 축뿐이라 등급은 붙이지 않는다.</li>
     *   <li><b>참고</b> — 분석이 없거나 점수가 기준 미만인 뉴스. 수집만 된 상태.</li>
     * </ul>
     *
     * <p>판정하지 않은 기사를 "정상"으로 보이게 하면 안 되므로, 확정이 아닌 건 grade를 null로 두고
     * 프론트가 배지를 생략한다.
     *
     * @param riskLevel 멀티에이전트 종합 등급(NORMAL/WARNING/CRITICAL). 없으면 null.
     */
    private static NewsFeedItem toNewsFeedItem(RawEvent event, Analysis analysis, String riskLevel) {
        String material = analysis != null && analysis.getMaterialCategory() != null
                ? MATERIAL_NAME_KO.getOrDefault(analysis.getMaterialCategory(), analysis.getMaterialCategory())
                : guessMaterial(event.getTitle(), event.getContent());
        String original = event.getTitle();
        // 번역본이 있으면 그걸 표시하고, 없으면 원문을 그대로 쓴다. 번역은 별도 스케줄러가
        // 뒤늦게 채우므로 미번역 기사도 화면에는 정상 노출된다 — 번역이 화면을 막지 않는다.
        String translatedTitle = event.getTitleKo();
        boolean translated = translatedTitle != null && !translatedTitle.isBlank();
        return new NewsFeedItem(
                analysis != null ? analysis.getAnalysisId().toString() : "RAW-" + event.getId(),
                LocalDate.ofInstant(event.getCollectedAt(), DISPLAY_ZONE).toString(),
                event.getCollectedAt(),
                material,
                gradeOf(riskLevel),
                event.getSource(),
                translated ? translatedTitle : original,
                original,
                translated,
                newsConfidenceLabel(analysis, riskLevel),
                event.getCountryCode(),
                linkableUrl(event.getSourceUrl()));
    }

    /**
     * 화면에서 클릭 가능한 링크만 통과시킨다. 절대 http(s) URL이 아니면 null로 만든다.
     *
     * <p>{@code POST /collection/test-news}로 넣은 검증 데이터에 Swagger 기본값 {@code "string"}이
     * 그대로 저장된 적이 있는데, 프론트가 그걸 href에 넣으니 상대 경로로 해석돼
     * {@code localhost:4173/string}으로 이동했다. 크롤러가 상대 경로나 빈 값을 줄 수도 있다.
     *
     * <p>소비하는 화면마다 검사하게 두면 한 곳을 빠뜨렸을 때 같은 증상이 되살아나므로 여기서 막는다.
     * null이면 프론트가 링크 대신 텍스트로 렌더한다.
     */
    static String linkableUrl(String sourceUrl) {
        if (sourceUrl == null) {
            return null;
        }
        String trimmed = sourceUrl.trim();
        return trimmed.startsWith("http://") || trimmed.startsWith("https://") ? trimmed : null;
    }

    /** 확정/경고/참고 판정. 규칙은 {@link #toNewsFeedItem(RawEvent, Analysis, String)} 참고. */
    static String newsConfidenceLabel(Analysis analysis, String riskLevel) {
        if (riskLevel != null) {
            return "확정";
        }
        Double externalScore = analysis == null ? null : analysis.getSeverityScore();
        if (externalScore != null && externalScore >= EXTERNAL_SIGNAL_WARNING_MIN) {
            return "경고";
        }
        return "참고";
    }

    /**
     * 분석이 없는 뉴스의 자재 대분류 추정. FastAPI {@code MockExtractionInference}와 같이
     * <b>제목+본문</b>을 본다 — 제목만 보면 놓치는 기사가 많다(실측: 수집 150건 중 제목 매칭 0건,
     * 본문 매칭 4건). 여러 자재가 걸리면 먼저 매칭된 하나만 쓴다. 아무것도 안 걸리면 null.
     */
    static String guessMaterialCategory(String title, String content) {
        String text = ((title == null ? "" : title) + " " + (content == null ? "" : content)).toLowerCase();
        return MATERIAL_KEYWORDS.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(text::contains))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /** 자재 추정 결과를 화면 표기명으로. 못 정하면 "기타". */
    private static String guessMaterial(String title, String content) {
        return materialNameKo(guessMaterialCategory(title, content));
    }

    /** placeholder RiskEvent → 뉴스 속보 변환(수집 뉴스 0건 폴백 전용). */
    private static NewsFeedItem toNewsFeedItem(RiskEvent event) {
        String date = parsePlaceholderDate(event.riskEventId());
        String summary = event.marketContext().eventSummary();
        return new NewsFeedItem(
                event.riskEventId(),
                date,
                LocalDate.parse(date).atStartOfDay(DISPLAY_ZONE).toInstant(),
                event.marketContext().material(),
                event.grade(),
                event.marketContext().source(),
                // placeholder 문구는 처음부터 한국어라 원문·표시용이 같고 번역 대상이 아니다.
                summary,
                summary,
                false,
                event.confidenceLabel(),
                event.marketContext().countryCode(),
                // placeholder는 실제 기사가 아니므로 원문 링크가 없다. 프론트가 "원문" 버튼을 숨긴다.
                null);
    }

    /** placeholder id 형식 "RISK-yyyy-MMdd-nnn"에서 날짜를 뽑는다. 형식이 다르면 오늘 날짜로 둔다. */
    private static String parsePlaceholderDate(String riskEventId) {
        String[] parts = riskEventId.split("-");
        if (parts.length >= 3 && parts[1].length() == 4 && parts[2].length() == 4) {
            return parts[1] + "-" + parts[2].substring(0, 2) + "-" + parts[2].substring(2);
        }
        return LocalDate.now(DISPLAY_ZONE).toString();
    }

    /**
     * 분석 → 지도 마커.
     *
     * @param confidenceLabel 파이프라인 도달 단계(확정/경고/참고). 뉴스 속보와 같은 규칙을 쓴다.
     * @param koreanTitle     번역된 제목. 없으면 원문(영문)을 그대로 쓴다.
     */
    private static RiskBoardItem toBoardItem(
            Analysis analysis, String grade, String confidenceLabel, String koreanTitle) {
        CountryRef country = COUNTRIES.get(analysis.getCountryCode());
        String category = analysis.getMaterialCategory();
        return new RiskBoardItem(
                analysis.getAnalysisId().toString(),
                MATERIAL_NAME_KO.getOrDefault(category, category),
                grade,
                confidenceLabel,
                // summary_kr은 FastAPI 추출 결과라 analyses에 저장되지 않는다. 요약을 노출하려면
                // analyses에 summary 컬럼 추가가 선행되어야 하므로, 지금은 뉴스 제목을 쓴다.
                // 번역본이 있으면 그걸 쓴다 — 없으면 지도만 영문 제목이 떠 속보와 어긋난다.
                koreanTitle != null && !koreanTitle.isBlank() ? koreanTitle : analysis.getEventTitle(),
                analysis.getCountryCode(),
                country != null ? country.name() : null,
                country != null ? new Coordinates(country.lat(), country.lng()) : null,
                linkableUrl(analysis.getSourceUrl()),
                analysis.getCreatedAt());
    }

    /** 분석 id → 번역된 뉴스 제목. 번역 스케줄러가 아직 안 돈 기사는 빠진다(원문으로 폴백). */
    private Map<UUID, String> loadKoreanTitles(List<Analysis> analyses) {
        if (analyses.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> titles = new LinkedHashMap<>();
        for (RawEvent event : rawEventRepository.findByTriggeredAnalysisIdIn(
                analyses.stream().map(Analysis::getAnalysisId).collect(Collectors.toSet()))) {
            if (event.getTitleKo() != null && !event.getTitleKo().isBlank()) {
                titles.putIfAbsent(event.getTriggeredAnalysisId(), event.getTitleKo());
            }
        }
        return titles;
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
                event.marketContext().coordinates(),
                // placeholder는 실제 기사가 아니라 원문 링크가 없다. 지어내면 화면에 눌리지 않는
                // 버튼이 생긴다. 시각도 같은 이유로 비운다.
                null,
                null);
    }
}
