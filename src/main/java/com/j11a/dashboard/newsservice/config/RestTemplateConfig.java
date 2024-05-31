package com.j11a.dashboard.newsservice.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class RestTemplateConfig {

    @Value("${newscatcher.apiKey}")
    private String apiKey;

    @Bean
    @Qualifier("newcatcherRestTemplate")
    @Primary
    public RestTemplate visualCrossingRestTemplate() {
        final RestTemplate restTemplate = new RestTemplate();

        List<ClientHttpRequestInterceptor> interceptors = restTemplate.getInterceptors();

        if (CollectionUtils.isEmpty(restTemplate.getInterceptors())) {
            interceptors = new ArrayList<>();
        }

        interceptors.add(new NewsCatchApiInterceptor(apiKey));
        restTemplate.setInterceptors(interceptors);

        return restTemplate;
    }


}
