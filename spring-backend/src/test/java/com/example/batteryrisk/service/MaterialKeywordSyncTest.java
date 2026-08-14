package com.example.batteryrisk.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자재 키워드 정규식이 두 곳에 존재하게 되어(성능 최적화로 V38 생성 컬럼을 도입) 둘이
 * 어긋나지 않도록 지키는 가드 테스트.
 *
 * <p>대상:
 * <ul>
 *   <li>Java — {@link RiskEventService#materialKeywordPattern()}가 {@code MATERIAL_KEYWORDS}로
 *       만드는 {@code \y키워드\y}를 {@code |}로 연결한 정규식(롤백 경로에서 다시 쓰는 값).</li>
 *   <li>SQL — {@code db/migration/V38__add_raw_events_material_matched.sql}의
 *       {@code raw_events.material_matched} 생성 컬럼 식에 박힌 정규식(운영에서 실제로 쓰는 값).</li>
 * </ul>
 *
 * <p>{@code MATERIAL_KEYWORDS}가 {@code Map.of}라 순회 순서가 비결정적이므로, 문자열 그대로가
 * 아니라 <b>대안(alternative) 집합</b>으로 비교한다. 누가 한쪽 키워드만 고치면 이 테스트가 즉시
 * 깨져, 뉴스 목록·건수·기준시각이 조용히 어긋나는 사고를 막는다.
 */
class MaterialKeywordSyncTest {

    private static final String V38_MIGRATION =
            "db/migration/V38__add_raw_events_material_matched.sql";

    /** {@code ... ~* '<정규식>'} 에서 작은따옴표로 둘러싼 정규식 리터럴을 뽑는다(패턴 안에 작은따옴표 없음). */
    private static final Pattern REGEX_LITERAL = Pattern.compile("~\\*\\s*'([^']*)'");

    @Test
    void v38MigrationRegexMatchesMaterialKeywordPattern() throws IOException {
        Set<String> javaAlternatives = alternatives(RiskEventService.materialKeywordPattern());
        Set<String> sqlAlternatives = alternatives(extractRegexFromMigration());

        assertThat(sqlAlternatives)
                .as("V38 생성 컬럼 정규식과 RiskEventService.materialKeywordPattern()의 키워드 집합이 "
                        + "달라졌습니다. 한쪽만 고치면 공급망 뉴스 목록/건수/기준시각이 어긋납니다 — "
                        + "키워드를 바꾸려면 새 마이그레이션으로 material_matched 컬럼을 재정의하세요.")
                .isEqualTo(javaAlternatives);
    }

    private static Set<String> alternatives(String regex) {
        return Arrays.stream(regex.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private String extractRegexFromMigration() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(V38_MIGRATION)) {
            assertThat(in).as("V38 마이그레이션 파일을 클래스패스에서 찾지 못했습니다: " + V38_MIGRATION).isNotNull();
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // `--` 줄 주석을 걷어낸다 — 이 파일의 롤백 안내 주석에도 예시로 `~* '...'`가 들어 있어,
            // 걷어내지 않으면 실제 생성 컬럼 식이 아니라 주석 속 예시를 뽑아 버린다.
            String sql = raw.lines()
                    .map(line -> {
                        int idx = line.indexOf("--");
                        return idx >= 0 ? line.substring(0, idx) : line;
                    })
                    .collect(Collectors.joining("\n"));
            Matcher m = REGEX_LITERAL.matcher(sql);
            assertThat(m.find())
                    .as("V38에서 `~* '<정규식>'` 형태의 자재 키워드 리터럴을 찾지 못했습니다.")
                    .isTrue();
            return m.group(1);
        }
    }
}
