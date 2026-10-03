package com.j11a.argus.testsupport;

import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only controller, served at /news/v2/probe through the path prefix. */
@RestController
@RequestMapping("/probe")
public class ProbeController {

    public static final String LOG_MARKER = "probe-marker-7f3a";
    public static final String SECRET_DETAIL = "internal-detail-must-not-leak";

    private static final Logger LOG = LoggerFactory.getLogger(ProbeController.class);

    public record ProbeRequest(@NotBlank String name) {
    }

    public enum Mode { FAST, SLOW }

    public record ModeRequest(Mode mode) {
    }

    @GetMapping
    public String read() {
        LOG.info(LOG_MARKER);
        return "ok";
    }

    @PostMapping
    public String write(@Valid @RequestBody ProbeRequest request) {
        return request.name();
    }

    @GetMapping("/count")
    public int count(@RequestParam int value) {
        return value;
    }

    @GetMapping("/api-error")
    public String apiError() {
        throw new ApiException(ErrorCode.FEED_NOT_FOUND, "no such feed");
    }

    @GetMapping("/api-error-props")
    public String apiErrorWithProperties() {
        throw new ApiException(ErrorCode.FEED_URL_CONFLICT, "dup", Map.of("existingFeedId", 7));
    }

    @GetMapping("/denied")
    public String denied() {
        throw new AccessDeniedException("denied");
    }

    @PostMapping("/mode")
    public String mode(@RequestBody ModeRequest request) {
        return request.mode().name();
    }

    @PostMapping("/list")
    public int list(@RequestBody List<String> items) {
        return items.size();
    }

    @GetMapping("/boom")
    public String boom() {
        throw new IllegalStateException(SECRET_DETAIL);
    }

    @GetMapping("/rejected")
    public String rejected() {
        throw new org.springframework.resilience.InvocationRejectedException("limit reached", this);
    }
}
