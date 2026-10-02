package com.j11a.argus.testsupport;

import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @GetMapping("/boom")
    public String boom() {
        throw new IllegalStateException(SECRET_DETAIL);
    }
}
