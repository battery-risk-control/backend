package com.example.batteryrisk.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 계약서 원문 텍스트에서 계약번호/계약명/발효일/만료일을 뽑는다. LLM 없이 정규식만 쓴다 —
 * {@code data_prep/generate_erp_aligned_contracts.py}와
 * {@code data_prep/generate_outbound_aligned_contracts.py}가 만드는 계약서 텍스트는 우리가 직접
 * 정한 고정 포맷("Contract Number: ...", "Effective Date: ..." 같은 라벨이 항상 같은 자리에
 * 등장)이라 정규식으로 결정론적으로 뽑을 수 있다. 낯선 형식의 PDF는 매칭이 안 될 수 있는데, 그건
 * 의도된 동작이다 — 못 뽑은 필드는 null로 두고 프론트 미리보기 모달에서 사용자가 직접 채운다
 * (범용 계약서 파서를 목표로 하지 않음).
 */
public final class ContractFieldExtractor {
    private ContractFieldExtractor() {}

    // 템플릿엔 별도 "Contract Number:" 줄이 없고 "Contract ID: CTR-001 (BA-2025-0001)"처럼
    // 괄호 안에 계약번호가 들어있다(generate_erp_aligned_contracts.py TEMPLATE 참고).
    private static final Pattern CONTRACT_NUMBER = Pattern.compile(
            "Contract ID:\\s*\\S+\\s*\\(([^)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EFFECTIVE_DATE = Pattern.compile(
            "Effective Date:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPIRATION_DATE = Pattern.compile(
            "Expiration Date:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
    // "{material} SUPPLY AGREEMENT" 줄(인바운드/아웃바운드 템플릿 공통 헤더)을 계약명으로 쓴다.
    private static final Pattern CONTRACT_NAME = Pattern.compile(
            "^(.+ SUPPLY AGREEMENT(?:\\s*\\(\\w+\\))?)\\s*$", Pattern.MULTILINE);

    public record ExtractedFields(
            String contractNumber, String contractName,
            LocalDate effectiveDate, LocalDate expirationDate) {}

    public static ExtractedFields extract(String text) {
        return new ExtractedFields(
                find(CONTRACT_NUMBER, text),
                find(CONTRACT_NAME, text),
                findDate(EFFECTIVE_DATE, text),
                findDate(EXPIRATION_DATE, text));
    }

    private static String find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static LocalDate findDate(Pattern pattern, String text) {
        String raw = find(pattern, text);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
