package com.j11a.argus.web.error;

import java.util.List;
import java.util.Map;

public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public ApiException(ErrorCode code, String detail, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    /** A VALIDATION_FAILED problem with one field error, shaped like the ones Bean Validation produces. */
    public static ApiException validationFailed(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.title(),
                Map.of(Problems.ERRORS_PROPERTY, List.of(new FieldProblem(field, message))));
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
