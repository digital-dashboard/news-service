package com.j11a.dashboard.newsservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatusCode;

import java.time.LocalDateTime;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class ApiResponse<T> {
    private HttpStatusCode status;
    private ApiResponseSubCodes subCode;
    private String message;
    private LocalDateTime timestamp = LocalDateTime.now();
    private T data;
}
