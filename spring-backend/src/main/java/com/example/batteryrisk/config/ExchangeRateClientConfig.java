package com.example.batteryrisk.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 환율 수집이 쓰는 외부 클라이언트 둘.
 *
 * <p>두 클라이언트 모두 스케줄러·기동 훅에서만 쓰이고 사용자 요청 경로에는 없다. 공개 조회는
 * DB만 보므로 외부가 느려지거나 죽어도 화면이 막히지 않는다 — 공공 API 응답이 느릴 때가 있어
 * 읽기 타임아웃을 넉넉히 잡아둘 수 있는 이유다.
 */
@Configuration
public class ExchangeRateClientConfig {

    /**
     * 한국수출입은행 현재환율 API. 직접 고시 환율(21종)의 원천이다.
     *
     * <p>도메인 주의: 구 도메인 www.koreaexim.go.kr은 2026-04-30 병행 가동이 종료됐다.
     * 반드시 신규 도메인 oapi.koreaexim.go.kr을 쓴다.
     */
    @Bean
    RestClient koreaEximRestClient(
            @Value("${app.exchange-rate.base-url:https://oapi.koreaexim.go.kr}") String baseUrl) {
        return build(baseUrl);
    }

    /**
     * 재정환율 계산용 USD 기준 환율 소스(ExchangeRate-API 무료 open access).
     *
     * <p>수출입은행이 고시하지 않는 조달국 통화(칠레·아르헨티나·콩고·남아공·브라질·필리핀)의
     * USD 다리를 여기서 받는다. 인증키가 없고 하루 1회 갱신이며, 무료 등급 조건상
     * <b>화면에 "Rates By Exchange Rate API" 표시가 필요하다</b>(공개 대시보드 footer).
     */
    @Bean
    RestClient crossRateRestClient(
            @Value("${app.exchange-rate.cross-rate.base-url:https://open.er-api.com}") String baseUrl) {
        return build(baseUrl);
    }

    private static RestClient build(String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(15_000);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
    }
}
