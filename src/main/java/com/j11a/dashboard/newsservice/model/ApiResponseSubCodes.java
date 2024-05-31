package com.j11a.dashboard.newsservice.model;

import lombok.Getter;

@Getter
public enum ApiResponseSubCodes {

    WS_001("API Request successful"),
    WS_002("API Request failed"),
    WS_003("Unsuccessful API Call - Http Client Error"),
    WS_004("Unsuccessful API Call - Http Server Error"),
    WS_005("Internal Server Error");

    private final String description;

    ApiResponseSubCodes(final String description) {
        this.description = description;
    }

}
