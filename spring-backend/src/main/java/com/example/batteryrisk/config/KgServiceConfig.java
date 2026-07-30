package com.example.batteryrisk.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** KG 리졸버 서비스(kg_service/, 결정사항 10번 1단계) 호출용 RestClient. FastApiConfig와 같은 패턴. */
@Configuration
public class KgServiceConfig {
    @Bean
    RestClient kgServiceRestClient(@Value("${app.kg-service.base-url:http://localhost:8100}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(30_000);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
    }
}
