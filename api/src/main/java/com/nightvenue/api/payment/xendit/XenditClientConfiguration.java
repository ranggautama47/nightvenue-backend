package com.nightvenue.api.payment.xendit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Synchronous RestClient for the Xendit Payment Requests v3 API. The request
 * factory wires the configured connect/read timeouts so they actually apply
 * (no unbounded default client). No SDK dependency.
 */
@Configuration
@EnableConfigurationProperties(XenditProperties.class)
public class XenditClientConfiguration {

    @Bean
    public RestClient xenditRestClient(XenditProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
