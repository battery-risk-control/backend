package com.example.batteryrisk;

import com.example.batteryrisk.service.GdeltEventArchiveService;
import com.example.batteryrisk.service.RiskEventService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GDELT 국가 매핑 회귀 방지.
 *
 * <p>이 매핑이 줄어들어도 <b>컴파일도 기동도 테스트도 전부 통과한다</b> — 해당 국가의 기사가
 * country_code=null로 조용히 유실될 뿐이다. 실제로 브랜치 병합 과정에서 83개국 판본과 61개국
 * 구판본이 충돌했고, 구판본을 채택했다면 22개국이 아무 신호 없이 사라졌을 상황이었다.
 * 사람의 판단에만 의존하지 않도록 여기에 고정해 둔다.
 *
 * <p>private static 필드를 리플렉션으로 읽는다. 매핑 자체가 검증 대상이라 공개 API만으로는
 * "몇 개인지"를 확인할 수 없기 때문이다.
 */
class GdeltCountryMappingTest {

    /** 2차 확장(61→83)으로 들어온 국가들. 하나라도 빠지면 그 나라 기사가 통째로 유실된다. */
    private static final List<String> SECOND_EXPANSION_COUNTRIES = List.of(
            "AE", "BZ", "CH", "CY", "CZ", "FJ", "GH", "GQ", "GR", "GY", "IE",
            "JM", "KH", "MK", "MT", "MW", "MY", "NZ", "PG", "RO", "SN", "TW");

    @SuppressWarnings("unchecked")
    private static Map<String, ?> readStaticMap(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, ?>) field.get(null);
    }

    @Test
    void iso2ToFipsKeepsExpandedCoverage() throws Exception {
        Map<String, ?> iso2ToFips = readStaticMap(GdeltEventArchiveService.class, "ISO2_TO_FIPS");

        assertThat(iso2ToFips)
                .as("수집 대상국이 줄면 해당 국가 기사가 country_code=null로 유실된다")
                .hasSize(83);

        assertThat(iso2ToFips.keySet())
                .as("2차 확장분 22개국 — 빠지면 무증상으로 사라진다")
                .containsAll(SECOND_EXPANSION_COUNTRIES);
    }

    /**
     * ISO2와 FIPS가 다른 코드들. 눈으로 검토할 때 가장 틀리기 쉬운 지점이라 명시적으로 고정한다.
     * 특히 CH/BH는 두 체계에서 서로 다른 나라를 가리켜서, 잘못 매핑하면 조용한 오분류가 된다.
     */
    @Test
    void fipsToIso2ResolvesNonIdentityCodes() {
        GdeltEventArchiveService service = new GdeltEventArchiveService();

        // FIPS "CH"는 중국이고 스위스가 아니다. 스위스의 FIPS는 "SZ".
        assertThat(service.fipsToIso2("CH")).isEqualTo("CN");
        assertThat(service.fipsToIso2("SZ")).isEqualTo("CH");

        // FIPS "BH"는 벨리즈이고 바레인이 아니다. 바레인의 FIPS는 "BA".
        assertThat(service.fipsToIso2("BH")).isEqualTo("BZ");
        assertThat(service.fipsToIso2("BA")).isEqualTo("BH");

        assertThat(service.fipsToIso2("TC")).isEqualTo("AE");  // 아랍에미리트
        assertThat(service.fipsToIso2("EZ")).isEqualTo("CZ");  // 체코
        assertThat(service.fipsToIso2("CB")).isEqualTo("KH");  // 캄보디아
        assertThat(service.fipsToIso2("SG")).isEqualTo("SN");  // 세네갈
        assertThat(service.fipsToIso2("MI")).isEqualTo("MW");  // 말라위
        assertThat(service.fipsToIso2("PP")).isEqualTo("PG");  // 파푸아뉴기니
        assertThat(service.fipsToIso2("EK")).isEqualTo("GQ");  // 적도기니
        assertThat(service.fipsToIso2("UK")).isEqualTo("GB");  // 영국
        assertThat(service.fipsToIso2("RS")).isEqualTo("RU");  // 러시아
        assertThat(service.fipsToIso2("CI")).isEqualTo("CL");  // 칠레
        assertThat(service.fipsToIso2("CG")).isEqualTo("CD");  // 콩고민주공화국
        assertThat(service.fipsToIso2("RP")).isEqualTo("PH");  // 필리핀

        assertThat(service.fipsToIso2(null)).isNull();
        assertThat(service.fipsToIso2("??")).isNull();
    }

    /**
     * 파일 간 불변식: 수집 대상국은 모두 지도 좌표를 가져야 한다.
     *
     * <p>좌표가 없어도 riskBoard()의 마커 상한 슬롯은 똑같이 차지하므로, 수집 대상국이 좌표
     * 테이블보다 많으면 좌표 없는 항목이 상한을 먼저 채워 인도네시아·칠레 같은 실제 공급망
     * 국가가 지도에서 밀려난다. 응답은 200이고 로그도 조용해서 화면을 봐야만 알 수 있다.
     */
    @Test
    void everyCollectedCountryHasMapCoordinates() throws Exception {
        Map<String, ?> iso2ToFips = readStaticMap(GdeltEventArchiveService.class, "ISO2_TO_FIPS");
        Map<String, ?> countries = readStaticMap(RiskEventService.class, "COUNTRIES");

        TreeSet<String> missing = new TreeSet<>(iso2ToFips.keySet());
        missing.removeAll(countries.keySet());

        assertThat(missing)
                .as("좌표 없는 수집 대상국은 지도 마커 상한만 잡아먹고 화면에는 안 보인다 — "
                        + "RiskEventService.COUNTRIES에 수도 좌표를 추가할 것")
                .isEmpty();
    }
}
