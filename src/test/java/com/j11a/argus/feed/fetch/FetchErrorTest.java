package com.j11a.argus.feed.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.url.LogSafe;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class FetchErrorTest {

    private static final Logger LOG = LoggerFactory.getLogger(FetchErrorTest.class);

    private static Map<String, Object> fieldsOf(FetchError error, String contentType, Integer bodyBytes) {
        try (LogCapture logs = LogCapture.start()) {
            FetchError.addFields(LOG.atInfo(), error, contentType, bodyBytes).setMessage("probe").log();
            return LogCapture.keyValues(logs.at(Level.INFO).getFirst());
        }
    }

    @Test
    void theConstructorCapsTheMessageAndReplacesControlCharacters() {
        FetchError error = new FetchError("T", "a\nb\u0000c" + "x".repeat(LogSafe.MAX_MESSAGE_LENGTH));

        assertThat(error.message()).hasSize(LogSafe.MAX_MESSAGE_LENGTH).startsWith("a b c");
    }

    @Test
    void aNullMessageStaysNull() {
        assertThat(new FetchError("T", null).message()).isNull();
    }

    @Test
    void typeOnlyNeverCarriesTheExceptionText() {
        FetchError error = FetchError.typeOnly(new IllegalStateException("duplicate key (guid)=(GUID-SECRET)"));

        assertThat(error).isEqualTo(new FetchError("IllegalStateException", null));
    }

    @Test
    void ofMessageRedactsUrlsAndTurnsNullOrBlankIntoNoMessage() {
        assertThat(FetchError.ofMessage("T", "  see https://h.test/p?k=SECRET  ").message())
                .isEqualTo("see https://h.test/p");
        assertThat(FetchError.ofMessage("T", null).message()).isNull();
        assertThat(FetchError.ofMessage("T", "   ").message()).isNull();
    }

    @Test
    void ofCauseUsesTheRootCause() {
        FetchError error = FetchError.of(new IOException("outer", new IllegalArgumentException("inner")));

        assertThat(error).isEqualTo(new FetchError("IllegalArgumentException", "inner"));
    }

    @Test
    void addFieldsEmitsOnlyTheDetailsThatAreKnown() {
        assertThat(fieldsOf(null, null, null)).isEmpty();
        assertThat(fieldsOf(new FetchError("T", "m"), "text/html", 12))
                .containsEntry("errorType", "T")
                .containsEntry("errorMessage", "m")
                .containsEntry("contentType", "text/html")
                .containsEntry("bodyBytes", 12);
    }

    @Test
    void addFieldsCapsTheContentTypeAndStripsControlCharacters() {
        Object contentType = fieldsOf(null, "text/\r\nhtml" + "y".repeat(200), null).get("contentType");

        assertThat(contentType).asString().hasSize(100).startsWith("text/  html");
    }
}
