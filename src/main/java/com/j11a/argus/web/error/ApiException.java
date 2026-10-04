package com.j11a.argus.web.error;

import java.util.List;
import java.util.Map;

public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> properties;

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

    public static ApiException feedNotFound(long id) {
        return new ApiException(ErrorCode.FEED_NOT_FOUND, "Feed " + id + " does not exist.");
    }

    public static ApiException sourceNotFound(long id) {
        return new ApiException(ErrorCode.SOURCE_NOT_FOUND, "Source " + id + " does not exist.");
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
