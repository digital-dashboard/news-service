package com.j11a.dashboard.newsservice.config;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;

/**
 * Rest Template Interceptor that will add API key to http request
 */
@AllArgsConstructor
public class NewsCatchApiInterceptor implements ClientHttpRequestInterceptor {

    private String apiKey;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        final HttpHeaders headers = new HttpHeaders(request.getHeaders());
        headers.add("x-api-key", this.apiKey);

        final HttpRequest modifiedRequest = new HttpRequest() {
            @Override
            public HttpMethod getMethod() {
                return request.getMethod();
            }

            @Override
            public URI getURI() {
                return request.getURI();
            }

            @Override
            public HttpHeaders getHeaders() {

                return headers;
            }
        };

        return execution.execute(modifiedRequest, body);
    }
}
