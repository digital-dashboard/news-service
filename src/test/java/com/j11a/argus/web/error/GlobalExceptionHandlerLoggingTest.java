package com.j11a.argus.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.j11a.argus.config.WebMvcConfig;
import com.j11a.argus.security.SecurityConfig;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.testsupport.ProbeController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(ProbeController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, WebMvcConfig.class, AdminKeys.SliceProperties.class})
class GlobalExceptionHandlerLoggingTest {

    private static final String PROBE = "/news/v2/probe";

    @Autowired
    private MockMvc mockMvc;

    private static List<ILoggingEvent> handled(LogCapture logs, Level level) {
        return logs.at(level, GlobalExceptionHandler.class);
    }

    private static void withDebugLogging(Runnable test) {
        ch.qos.logback.classic.Logger handlerLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        handlerLogger.setLevel(Level.DEBUG);
        try {
            test.run();
        } finally {
            handlerLogger.setLevel(null);
        }
    }

    @Test
    void aMissingAdminKeyIsAWarnWithMethodAndPathAndNeverTheKeyOrTheQuery() throws Exception {
        String wrongKey = "wrong-key-SECRETVALUE";
        try (LogCapture logs = LogCapture.start()) {
            mockMvc.perform(post(PROBE + "?token=QUERYSECRET").header(AdminKeys.HEADER, wrongKey));

            assertThat(handled(logs, Level.WARN)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("code", "ADMIN_KEY_REQUIRED")
                        .containsEntry("status", 401)
                        .containsEntry("method", "POST")
                        .containsEntry("path", PROBE);
                assertThat(event.getFormattedMessage() + LogCapture.keyValues(event))
                        .doesNotContain("SECRETVALUE")
                        .doesNotContain("QUERYSECRET");
            });
        }
    }

    @Test
    void anApiClientErrorIsAnInfoWithCodeStatusMethodAndPath() throws Exception {
        try (LogCapture logs = LogCapture.start()) {
            mockMvc.perform(get(PROBE + "/api-error"));

            assertThat(handled(logs, Level.INFO)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event))
                            .containsEntry("code", "FEED_NOT_FOUND")
                            .containsEntry("status", 404)
                            .containsEntry("method", "GET")
                            .containsEntry("path", PROBE + "/api-error"));
        }
    }

    @Test
    void aFrameworkClientErrorInsideTheApiIsAnInfo() throws Exception {
        try (LogCapture logs = LogCapture.start()) {
            mockMvc.perform(get(PROBE + "/count").param("value", "abc"));

            assertThat(handled(logs, Level.INFO)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event))
                            .containsEntry("code", "VALIDATION_FAILED")
                            .containsEntry("status", 400));
        }
    }

    @Test
    void aPollAlreadyRunningIsAnInfo() throws Exception {
        try (LogCapture logs = LogCapture.start()) {
            mockMvc.perform(get(PROBE + "/rejected"));

            assertThat(handled(logs, Level.INFO)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event)).containsEntry("code", "POLL_IN_PROGRESS"));
        }
    }

    @Test
    void anUnknownRouteInsideTheApiIsAnInfo() throws Exception {
        try (LogCapture logs = LogCapture.start()) {
            mockMvc.perform(get("/news/v2/nope"));

            assertThat(handled(logs, Level.INFO)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event)).containsEntry("code", "NOT_FOUND"));
        }
    }

    @Test
    void anUnknownRouteOutsideTheApiIsOnlyADebug() {
        withDebugLogging(() -> {
            try (LogCapture logs = LogCapture.start()) {
                perform(get("/wp-login.php"));

                assertThat(handled(logs, Level.DEBUG)).singleElement().satisfies(event ->
                        assertThat(LogCapture.keyValues(event)).containsEntry("path", "/wp-login.php"));
                assertThat(handled(logs, Level.INFO)).isEmpty();
                assertThat(handled(logs, Level.WARN)).isEmpty();
            }
        });
    }

    @Test
    void aMissingAdminKeyOutsideTheApiIsOnlyADebug() {
        withDebugLogging(() -> {
            try (LogCapture logs = LogCapture.start()) {
                perform(post("/wp-admin/setup.php"));

                assertThat(handled(logs, Level.DEBUG)).singleElement().satisfies(event ->
                        assertThat(LogCapture.keyValues(event)).containsEntry("code", "ADMIN_KEY_REQUIRED"));
                assertThat(handled(logs, Level.WARN)).isEmpty();
            }
        });
    }

    @Test
    void theLoggedPathIsCappedAtTwoHundredCharacters() throws Exception {
        try (LogCapture logs = LogCapture.start()) {
            mockMvc.perform(get(PROBE + "/" + "a".repeat(400)));

            assertThat(handled(logs, Level.INFO)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event).get("path")).asString().hasSize(200).startsWith(PROBE);
                assertThat(event.getFormattedMessage()).doesNotContain("a".repeat(201));
            });
        }
    }

    @Test
    void anUnexpectedExceptionIsAnErrorWithTheStackTrace() throws Exception {
        try (LogCapture logs = LogCapture.start()) {
            mockMvc.perform(get(PROBE + "/boom"));

            assertThat(handled(logs, Level.ERROR)).singleElement().satisfies(event -> {
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getFormattedMessage()).isEqualTo("Unhandled exception: GET " + PROBE + "/boom");
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("code", "INTERNAL_ERROR")
                        .containsEntry("status", 500)
                        .containsEntry("path", PROBE + "/boom");
            });
        }
    }

    @Test
    void aHandledServerErrorIsAWarnWithoutAStackTraceAndNotUnhandled() throws Exception {
        try (LogCapture logs = LogCapture.start()) {
            mockMvc.perform(get(PROBE + "/unavailable")).andExpect(status().isServiceUnavailable());

            assertThat(handled(logs, Level.ERROR)).isEmpty();
            assertThat(handled(logs, Level.WARN)).singleElement().satisfies(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Request failed: SERVICE_UNAVAILABLE 503 GET " + PROBE + "/unavailable");
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("code", "SERVICE_UNAVAILABLE")
                        .containsEntry("status", 503);
            });
        }
    }

    @Test
    void anIngestAlreadyLoggedAnswersLikeAnUnexpectedErrorButIsNotLoggedWithItsStackTraceAgain() throws Exception {
        try (LogCapture logs = LogCapture.start()) {
            mockMvc.perform(get(PROBE + "/ingest-failed"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));

            assertThat(handled(logs, Level.ERROR)).isEmpty();
            assertThat(handled(logs, Level.WARN)).singleElement().satisfies(event ->
                    assertThat(event.getThrowableProxy()).isNull());
            logs.assertNothingLogged(ProbeController.SECRET_DETAIL);
        }
    }

    @Test
    void feedCreationRejectionsAreOnlyDebugBecauseFeedServiceLogsThem() {
        withDebugLogging(() -> {
            try (LogCapture logs = LogCapture.start()) {
                perform(get(PROBE + "/feed-invalid"));
                perform(get(PROBE + "/feed-conflict"));

                assertThat(handled(logs, Level.DEBUG)).hasSize(2);
                assertThat(handled(logs, Level.INFO)).isEmpty();
                assertThat(handled(logs, Level.WARN)).isEmpty();
            }
        });
    }

    private void perform(MockHttpServletRequestBuilder request) {
        try {
            mockMvc.perform(request);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
