package com.example.batteryrisk.service;

import com.example.batteryrisk.dto.ErpImportDto;
import com.example.batteryrisk.exception.BusinessException;
import com.example.batteryrisk.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ERP 적재 영수증 — {@code /commit}이 발급하고 {@code /report}가 검증하는 서명된 쪽지.
 *
 * <p>왜 필요한가: 최종 반영 보고서(PDF)에는 "몇 건이 들어갔는지, 누가 승인했는지"가 찍힌다.
 * 그런데 이 화면은 잡 ID도 이력 테이블도 없는 무상태 구조라(초기 범위에서 제외됐다) 보고서를
 * 만들 때 서버가 그 숫자를 다시 알아낼 방법이 없다. 그렇다고 프론트가 들고 있던 값을 그대로
 * 받아 찍으면, <b>감사 근거로 쓰는 문서의 숫자를 브라우저가 정할 수 있게 된다</b> — 오류 0건에
 * 1만 건 반영이라고 적힌 PDF를 누구나 만들 수 있다는 뜻이다.
 *
 * <p>그래서 적재 시점에 서버가 사실을 적고 서명한다. 프론트는 이 문자열을 보관했다가 보고서
 * 요청에 그대로 되돌려줄 뿐이고, 한 글자라도 고치면 서명 검증에서 떨어진다. 상태를 서버에
 * 남기지 않으므로 인스턴스가 여러 대여도 동작한다.
 *
 * <p>서명 키는 {@code app.jwt.secret}에서 파생한다. 접근 토큰과 <b>다른 용도</b>이므로 접두사를
 * 붙여 키를 갈라둔다 — 같은 키를 쓰면 영수증을 인증 토큰 자리에 밀어 넣는 시도가 가능해진다.
 */
@Service
public class ErpImportReceiptService {
    /** 영수증 유효 시간. 반영 직후 보고서를 받는 흐름이라 길 이유가 없다. */
    private static final long VALIDITY_SECONDS = 3600;

    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_RECEIPT = "erp-import-receipt";
    private static final String CLAIM_COMMITTED_AT = "ca";
    private static final String CLAIM_TOTAL_INSERTED = "ti";
    private static final String CLAIM_TOTAL_UPDATED = "tu";
    private static final String CLAIM_RESULTS = "rs";
    private static final String CLAIM_KG_WARNING = "kw";

    private final SecretKey key;

    public ErpImportReceiptService(@Value("${app.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(("erp-import-receipt:" + secret).getBytes(StandardCharsets.UTF_8));
    }

    /** 적재 사실을 서명해 문자열 하나로 만든다. 승인자는 인자로 받지 않고 현재 인증에서 읽는다. */
    public String sign(
            OffsetDateTime committedAt,
            int totalInserted,
            int totalUpdated,
            List<ErpImportDto.TableResult> results,
            String kgSyncWarning) {
        // 라벨은 테이블 키에서 다시 만들 수 있으므로 싣지 않는다 — 영수증은 폼 필드로 오가서 짧을수록 좋다.
        List<Map<String, Object>> compact = new ArrayList<>();
        for (ErpImportDto.TableResult result : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("t", result.targetTable());
            row.put("i", result.inserted());
            row.put("u", result.updated());
            compact.add(row);
        }

        Instant now = Instant.now();
        return Jwts.builder()
                .subject(currentUsername())
                .claim(CLAIM_TYPE, TYPE_RECEIPT)
                .claim(CLAIM_COMMITTED_AT, committedAt.toInstant().toEpochMilli())
                .claim(CLAIM_TOTAL_INSERTED, totalInserted)
                .claim(CLAIM_TOTAL_UPDATED, totalUpdated)
                .claim(CLAIM_RESULTS, compact)
                .claim(CLAIM_KG_WARNING, kgSyncWarning)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(VALIDITY_SECONDS)))
                .signWith(key)
                .compact();
    }

    /**
     * 서명을 확인하고 적재 사실을 되꺼낸다. 위조·변조·만료는 전부 400으로 떨어뜨린다 —
     * 사용자에게는 "영수증이 유효하지 않으니 반영 전 보고서를 받으라"고 안내하면 되는 상황이다.
     */
    public Receipt verify(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "최종 반영 보고서를 만들려면 반영 시 발급된 영수증이 필요합니다.");
        }
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "반영 영수증이 유효하지 않거나 만료됐습니다. 반영 전 검증 보고서를 이용해 주세요.");
        }
        if (!TYPE_RECEIPT.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "반영 영수증이 아닙니다.");
        }

        List<TableResult> results = new ArrayList<>();
        Object raw = claims.get(CLAIM_RESULTS);
        if (raw instanceof List<?> rows) {
            for (Object element : rows) {
                if (!(element instanceof Map<?, ?> row)) continue;
                results.add(new TableResult(
                        String.valueOf(row.get("t")),
                        intOf(row.get("i")),
                        intOf(row.get("u"))));
            }
        }
        return new Receipt(
                claims.getSubject(),
                OffsetDateTime.ofInstant(
                        Instant.ofEpochMilli(claims.get(CLAIM_COMMITTED_AT, Number.class).longValue()),
                        ZoneId.systemDefault()),
                intOf(claims.get(CLAIM_TOTAL_INSERTED)),
                intOf(claims.get(CLAIM_TOTAL_UPDATED)),
                List.copyOf(results),
                claims.get(CLAIM_KG_WARNING, String.class));
    }

    /** 로그인한 사용자명. 스케줄러 등 인증 없는 경로에서 불릴 일은 없지만 방어적으로 처리한다. */
    public String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "unknown" : authentication.getName();
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** 서명이 확인된 적재 사실. */
    public record Receipt(
            String approver,
            OffsetDateTime committedAt,
            int totalInserted,
            int totalUpdated,
            List<TableResult> results,
            String kgSyncWarning
    ) {}

    /** 라벨이 빠진 테이블별 결과. 보고서 서비스가 테이블 키로 라벨을 다시 붙인다. */
    public record TableResult(String targetTable, int inserted, int updated) {}
}
