package com.j11a.dashboard.newsservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.j11a.dashboard.newsservice.model.ApiResponse;
import com.j11a.dashboard.newsservice.model.ApiResponseSubCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
@Slf4j
public class NewsControllerAdvice extends ResponseEntityExceptionHandler {

    @ExceptionHandler(value = { HttpServerErrorException.class })
    protected ResponseEntity<ApiResponse<JsonNode>> handleHttpServerErrorException(HttpServerErrorException ex) {
        log.error("HttpServerErrorException - {} - {}", ex.getStatusCode(), ex.getMessage(), ex);
        final ApiResponse<JsonNode> apiResponse = new ApiResponse<>();
        apiResponse.setSubCode(ApiResponseSubCodes.WS_004);
        apiResponse.setMessage(ApiResponseSubCodes.WS_004.getDescription());
        apiResponse.setData(ex.getResponseBodyAs(JsonNode.class));
        apiResponse.setStatus(ex.getStatusCode());

        return new ResponseEntity<>(apiResponse, ex.getStatusCode());
    }

    @ExceptionHandler(HttpClientErrorException.class)
    protected ResponseEntity<ApiResponse<JsonNode>> handleHttpClientErrorException(HttpClientErrorException ex) {
        log.error("HttpClientErrorException - {} - {}", ex.getStatusCode(), ex.getMessage(), ex);
        final ApiResponse<JsonNode> apiResponse = new ApiResponse<>();
        apiResponse.setData(ex.getResponseBodyAs(JsonNode.class));
        apiResponse.setSubCode(ApiResponseSubCodes.WS_003);
        apiResponse.setMessage(ApiResponseSubCodes.WS_003.getDescription());

        apiResponse.setStatus(ex.getStatusCode());

        return ResponseEntity.ok(apiResponse);
    }

    @ExceptionHandler(value = { Exception.class })
    protected ResponseEntity<ApiResponse<Object>> handleExceptions(Exception ex) {
        log.error("Exception - {} - {}", ex.getMessage(), ex.getLocalizedMessage(), ex);
        final ApiResponse<Object> apiResponse = new ApiResponse<>();
        apiResponse.setSubCode(ApiResponseSubCodes.WS_005);
        apiResponse.setMessage(ApiResponseSubCodes.WS_005.getDescription());
        apiResponse.setStatus(HttpStatusCode.valueOf(500));

        return ResponseEntity.internalServerError().body(apiResponse);
    }

}
