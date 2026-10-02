package com.j11a.argus.web.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

class ErrorCodeTest {

    private static final List<String> PUBLISHED_CODES = List.of(
            "VALIDATION_FAILED", "ADMIN_KEY_REQUIRED", "FEED_NOT_FOUND", "FEED_URL_CONFLICT", "FEED_INVALID",
            "SOURCE_NOT_FOUND", "SOURCE_MERGE_INVALID", "WATCH_NOT_FOUND", "WATCH_NAME_CONFLICT",
            "POLL_IN_PROGRESS", "OPML_INVALID", "BAD_REQUEST", "NOT_FOUND", "METHOD_NOT_ALLOWED",
            "NOT_ACCEPTABLE", "UNSUPPORTED_MEDIA_TYPE", "CONFLICT", "PAYLOAD_TOO_LARGE", "UNPROCESSABLE",
            "SERVICE_UNAVAILABLE", "INTERNAL_ERROR");

    @Test
    void namesMatchThePublishedContract() {
        Set<String> names = Arrays.stream(ErrorCode.values()).map(Enum::name).collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrderElementsOf(PUBLISHED_CODES);
    }

    @Test
    void everyCodeHasStatusTitleAndUrnType() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.status()).isNotNull();
            assertThat(code.title()).isNotBlank();
            assertThat(code.typeUri()).startsWith("urn:argus:problem:").doesNotContain("_");
        }
    }

    @Test
    void typeUriIsKebabCaseOfTheName() {
        assertThat(ErrorCode.FEED_URL_CONFLICT.typeUri()).isEqualTo("urn:argus:problem:feed-url-conflict");
    }

    @Test
    void unprocessableCodesUse422() {
        assertThat(ErrorCode.FEED_INVALID.status().value()).isEqualTo(422);
        assertThat(ErrorCode.SOURCE_MERGE_INVALID.status().value()).isEqualTo(422);
        assertThat(ErrorCode.OPML_INVALID.status().value()).isEqualTo(422);
    }

    @Test
    void forStatusMapsFrameworkStatuses() {
        assertThat(ErrorCode.forStatus(HttpStatus.BAD_REQUEST)).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(ErrorCode.forStatus(HttpStatus.NOT_FOUND)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ErrorCode.forStatus(HttpStatus.METHOD_NOT_ALLOWED)).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED);
        assertThat(ErrorCode.forStatus(HttpStatus.NOT_ACCEPTABLE)).isEqualTo(ErrorCode.NOT_ACCEPTABLE);
        assertThat(ErrorCode.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE))
                .isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(ErrorCode.forStatus(HttpStatus.CONFLICT)).isEqualTo(ErrorCode.CONFLICT);
        assertThat(ErrorCode.forStatus(HttpStatus.CONTENT_TOO_LARGE)).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(ErrorCode.forStatus(HttpStatus.UNPROCESSABLE_CONTENT)).isEqualTo(ErrorCode.UNPROCESSABLE);
        assertThat(ErrorCode.forStatus(HttpStatus.SERVICE_UNAVAILABLE)).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void forStatusFallsBackToTheStatusClass() {
        assertThat(ErrorCode.forStatus(HttpStatus.GONE)).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(ErrorCode.forStatus(HttpStatus.BAD_GATEWAY)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.forStatus(HttpStatusCode.valueOf(599))).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void newCodesUseTheirStatuses() {
        assertThat(ErrorCode.CONFLICT.status().value()).isEqualTo(409);
        assertThat(ErrorCode.PAYLOAD_TOO_LARGE.status().value()).isEqualTo(413);
        assertThat(ErrorCode.UNPROCESSABLE.status().value()).isEqualTo(422);
        assertThat(ErrorCode.SERVICE_UNAVAILABLE.status().value()).isEqualTo(503);
    }
}
