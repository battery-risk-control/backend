package com.example.batteryrisk;

import com.example.batteryrisk.config.ErpSeedConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 전략 C(UTF-8 → MS949 폴백)가 두 인코딩 CSV 모두에서 한글을 보존하는지 검증. */
class ErpCsvCharsetFallbackTest {

    @SuppressWarnings("unchecked")
    private static List<String> readWithFallback(Path path) throws Exception {
        Method m = ErpSeedConfig.class.getDeclaredMethod("readAllLinesWithCharsetFallback", Path.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, path);
    }

    @Test
    void utf8CsvIsReadCorrectly(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("utf8.csv");
        Files.write(file, "code,name\nM001,양극재\n".getBytes(StandardCharsets.UTF_8));

        List<String> lines = readWithFallback(file);

        assertEquals("code,name", lines.get(0));
        assertEquals("M001,양극재", lines.get(1)); // 깨지지 않고 그대로
    }

    @Test
    void ms949CsvFallsBackAndIsReadCorrectly(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("ms949.csv");
        // 국내 엑셀이 CSV로 저장했을 때처럼 MS949로 인코딩된 파일
        Files.write(file, "code,name\nM001,양극재\n".getBytes(Charset.forName("MS949")));

        List<String> lines = readWithFallback(file); // UTF-8 실패 → MS949 폴백

        assertEquals("code,name", lines.get(0));
        assertEquals("M001,양극재", lines.get(1)); // 폴백으로 정상 복원
    }
}
