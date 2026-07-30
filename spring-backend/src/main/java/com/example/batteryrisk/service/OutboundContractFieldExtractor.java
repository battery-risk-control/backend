package com.example.batteryrisk.service;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 아웃바운드 계약서(LG에너지솔루션 -> 완성차/ESS 고객사) 원문 텍스트에서 계약언어/물량/단가/
 * 납기일수/위약금을 뽑는다. {@link ContractFieldExtractor}와 같은 이유로 LLM 없이 정규식만
 * 쓴다 — {@code data_prep/generate_outbound_aligned_contracts.py}가 만드는 계약서 텍스트는
 * 고정 포맷이라 결정론적으로 뽑을 수 있다.
 *
 * <p>인바운드와 다른 점: 아웃바운드 계약({@code outbound_contracts.csv})엔 발효일/만료일 개념
 * 자체가 없다(계약번호도 "Contract ID: CTR-OUT-001"처럼 괄호 표기 없이 그대로 노출됨). 대신
 * 물량(GWh)/단가(USD/kWh)/납기일수/위약금(%)이 있다. Line-Stop Charge는 통화가 USD/KRW로
 * 혼용되는 포맷이라 정규식 추출 대상에서 제외하고 수동 입력 전용으로 남긴다.
 */
public final class OutboundContractFieldExtractor {
    private OutboundContractFieldExtractor() {}

    private static final Pattern CONTRACT_ID = Pattern.compile(
            "Contract ID:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTRACT_LANGUAGE = Pattern.compile(
            "Contract Language\\s*\\(per ERP metadata\\):\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUANTITY_GWH = Pattern.compile(
            "total capacity of\\s+([\\d.]+)\\s+GWh", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT_PRICE_USD_KWH = Pattern.compile(
            "base price per kWh shall be USD\\s+([\\d.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELIVERY_LEAD_TIME_DAYS = Pattern.compile(
            "delivery lead time of\\s+(\\d+)\\s+days", Pattern.CASE_INSENSITIVE);
    private static final Pattern PENALTY_PCT = Pattern.compile(
            "penalty of\\s+([\\d.]+)%", Pattern.CASE_INSENSITIVE);

    public record ExtractedFields(
            String contractId, String contractLanguage,
            BigDecimal quantityGwh, BigDecimal unitPriceUsdKwh,
            Integer deliveryLeadTimeDays, BigDecimal penaltyPct) {}

    public static ExtractedFields extract(String text) {
        return new ExtractedFields(
                find(CONTRACT_ID, text),
                find(CONTRACT_LANGUAGE, text),
                findDecimal(QUANTITY_GWH, text),
                findDecimal(UNIT_PRICE_USD_KWH, text),
                findInt(DELIVERY_LEAD_TIME_DAYS, text),
                findDecimal(PENALTY_PCT, text));
    }

    private static String find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static BigDecimal findDecimal(Pattern pattern, String text) {
        String raw = find(pattern, text);
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer findInt(Pattern pattern, String text) {
        String raw = find(pattern, text);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
