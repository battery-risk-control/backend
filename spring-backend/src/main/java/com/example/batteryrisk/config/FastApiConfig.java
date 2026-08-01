package com.example.batteryrisk.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class FastApiConfig {
    @Bean
    RestClient fastApiRestClient(@Value("${app.fastapi.base-url:http://localhost:8000}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        // use_llm=true 멀티에이전트 브리핑은 협상 루프(최대 2회 왕복)+Claude 구조화 출력 호출
        // (reviewer 재시도 시 최대 2회 더)이 겹쳐 일반 /analyze 호출보다 오래 걸린다.
        // 30s/90s 둘 다 이 경로에서 실제로 타임아웃났다(2026-07-31 실증) — 여유 있게 잡는다.
        requestFactory.setReadTimeout(180_000);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
    }
}
