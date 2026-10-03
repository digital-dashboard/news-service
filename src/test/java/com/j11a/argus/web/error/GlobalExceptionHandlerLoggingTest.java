package com.j11a.argus.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

@WebMvcTest(ProbeController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, WebMvcConfig.class, AdminKeys.SliceProperties.class})
class GlobalExceptionHandlerLoggingTest {

    private static final String PROBE = "/news/v2/probe";
    private static final String HANDLER_LOGGER = GlobalExceptionHandler.class.getName();

    @Autowired
    private MockMvc mockMvc;

    private static List<ILoggingEvent> handled(LogCapture logs, Level level) {
        return logs.at(level).stream().filter(event -> HANDLER_LOGGER.equals(event.getLoggerName())).toList();
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
    void anUnknownRouteOutsideTheApiIsOnlyADebug() throws Exception {
        ch.qos.logback.classic.Logger handlerLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        handlerLogger.setLevel(Level.DEBUG);
        try (LogCapture logs = LogCapture.start()) {
            mockMvc.perform(get("/wp-login.php"));

            assertThat(handled(logs, Level.DEBUG)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event)).containsEntry("path", "/wp-login.php"));
            assertThat(handled(logs, Level.INFO)).isEmpty();
            assertThat(handled(logs, Level.WARN)).isEmpty();
        } finally {
            handlerLogger.setLevel(null);
        }
    }

    @Test
    void anUnexpectedExceptionIsAnErrorWithTheStackTrace() throws Exception {
        try (LogCapture logs = LogCapture.start()) {
            mockMvc.perform(get(PROBE + "/boom"));

            assertThat(handled(logs, Level.ERROR)).singleElement().satisfies(event -> {
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("code", "INTERNAL_ERROR")
                        .containsEntry("status", 500)
                        .containsEntry("path", PROBE + "/boom");
            });
        }
    }
}
