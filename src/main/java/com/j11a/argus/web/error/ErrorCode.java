package com.j11a.argus.web.error;

import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    ADMIN_KEY_REQUIRED(HttpStatus.UNAUTHORIZED, "Admin key required"),
    FEED_NOT_FOUND(HttpStatus.NOT_FOUND, "Feed not found"),
    FEED_URL_CONFLICT(HttpStatus.CONFLICT, "Feed URL already exists"),
    FEED_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Feed invalid"),
    SOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Source not found"),
    SOURCE_MERGE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Source merge invalid"),
    WATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "Watch not found"),
    WATCH_NAME_CONFLICT(HttpStatus.CONFLICT, "Watch name already exists"),
    POLL_IN_PROGRESS(HttpStatus.CONFLICT, "Poll already in progress"),
    OPML_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "OPML invalid"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    CONFLICT(HttpStatus.CONFLICT, "Conflict"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Payload too large"),
    UNPROCESSABLE(HttpStatus.UNPROCESSABLE_CONTENT, "Unprocessable"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private static final String TYPE_PREFIX = "urn:argus:problem:";

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String typeUri() {
        return TYPE_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static ErrorCode forStatus(HttpStatusCode statusCode) {
        HttpStatus known = HttpStatus.resolve(statusCode.value());
        ErrorCode mapped = known == null ? null : switch (known) {
            case NOT_FOUND -> NOT_FOUND;
            case METHOD_NOT_ALLOWED -> METHOD_NOT_ALLOWED;
            case NOT_ACCEPTABLE -> NOT_ACCEPTABLE;
            case CONFLICT -> CONFLICT;
            case CONTENT_TOO_LARGE -> PAYLOAD_TOO_LARGE;
            case UNSUPPORTED_MEDIA_TYPE -> UNSUPPORTED_MEDIA_TYPE;
            case UNPROCESSABLE_CONTENT -> UNPROCESSABLE;
            case SERVICE_UNAVAILABLE -> SERVICE_UNAVAILABLE;
            default -> null;
        };
        if (mapped != null) {
            return mapped;
        }
        return statusCode.is5xxServerError() ? INTERNAL_ERROR : BAD_REQUEST;
    }
}
