package com.j11a.dashboard.newsservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.j11a.dashboard.newsservice.model.ApiResponse;
import com.j11a.dashboard.newsservice.model.ApiResponseSubCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.*;

class NewsControllerAdviceTest {


    @Test
    void handleHttpServerErrorException() {
        HttpServerErrorException ex = new HttpServerErrorException(HttpStatusCode.valueOf(500), "Internal Server Error", "{\"code\": \"Internal Server Error\"}".getBytes(), Charset.defaultCharset());
        ex.setBodyConvertFunction(t -> {
            final ObjectMapper mapper = new ObjectMapper();
            return mapper.convertValue("{\"code\": \"Internal Server Error\"}", JsonNode.class);
        });
        NewsControllerAdvice advice = new NewsControllerAdvice();

        final ResponseEntity<ApiResponse<JsonNode>> resp = advice.handleHttpServerErrorException(ex);
        assertEquals(HttpStatusCode.valueOf(500), resp.getStatusCode());
        ApiResponse<JsonNode> body = resp.getBody();
        assertEquals(ApiResponseSubCodes.WS_004, body.getSubCode());
        assertEquals(HttpStatusCode.valueOf(500), body.getStatus());
    }

    @Test
    void handleHttpClientErrorException() {
        HttpClientErrorException ex = new HttpClientErrorException(HttpStatusCode.valueOf(400), "Internal Server Error", "{\"code\": \"Internal Server Error\"}".getBytes(), Charset.defaultCharset());
        ex.setBodyConvertFunction(t -> {
            final ObjectMapper mapper = new ObjectMapper();
            return mapper.convertValue("{\"code\": \"Internal Server Error\"}", JsonNode.class);
        });
        NewsControllerAdvice advice = new NewsControllerAdvice();

        final ResponseEntity<ApiResponse<JsonNode>> resp = advice.handleHttpClientErrorException(ex);
        assertEquals(HttpStatusCode.valueOf(200), resp.getStatusCode());
        ApiResponse<JsonNode> body = resp.getBody();
        assertEquals(ApiResponseSubCodes.WS_003, body.getSubCode());
        assertEquals(HttpStatusCode.valueOf(400), body.getStatus());
    }

    @Test
    void handleException() {
        Exception ex = new Exception("Internal Server Error");
        NewsControllerAdvice advice = new NewsControllerAdvice();

        final ResponseEntity<ApiResponse<Object>> resp = advice.handleExceptions(ex);
        assertEquals(HttpStatusCode.valueOf(500), resp.getStatusCode());
        ApiResponse<?> body = resp.getBody();
        assertEquals(ApiResponseSubCodes.WS_005, body.getSubCode());
        assertEquals(HttpStatusCode.valueOf(500), body.getStatus());
    }
}