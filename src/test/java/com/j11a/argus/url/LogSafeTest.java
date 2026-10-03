package com.j11a.argus.url;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import org.junit.jupiter.api.Test;

class LogSafeTest {

    @Test
    void redactUrlsKeepsSchemeHostPortAndPathOnly() {
        String redacted = LogSafe.redactUrls(
                "GET https://user:pw@feeds.example.test:8443/rss.xml?token=SECRET#frag failed");

        assertThat(redacted).isEqualTo("GET https://feeds.example.test:8443/rss.xml failed");
    }

    @Test
    void redactUrlsHandlesEveryUrlInTheText() {
        String redacted = LogSafe.redactUrls("a http://one.test/x?k=SECRET b https://two.test/y?k=SECRET2 c");

        assertThat(redacted).isEqualTo("a http://one.test/x b https://two.test/y c")
                .doesNotContain("SECRET");
    }

    @Test
    void redactUrlsReplacesAnUnparseableUrlWithAPlaceholderInsteadOfLeakingIt() {
        String redacted = LogSafe.redactUrls("bad https://exa<mple.test/?token=SECRET");

        assertThat(redacted).isEqualTo("bad [url]");
    }

    @Test
    void redactUrlsLeavesTextWithoutUrlsAlone() {
        assertThat(LogSafe.redactUrls("connection reset by peer")).isEqualTo("connection reset by peer");
    }

    @Test
    void errorTypeAndMessageComeFromTheRootCause() {
        Exception wrapped = new IllegalStateException("outer",
                new IOException("middle", new ConnectException("Connection refused")));

        assertThat(LogSafe.errorType(wrapped)).isEqualTo("ConnectException");
        assertThat(LogSafe.errorMessage(wrapped)).isEqualTo("Connection refused");
    }

    @Test
    void aClosedChannelExceptionUnderAConnectExceptionIsNotTheReportedCause() {
        ConnectException connect = new ConnectException();
        connect.initCause(new ClosedChannelException());
        Exception refused = new IOException("wrapper", connect);

        assertThat(LogSafe.errorType(refused)).isEqualTo("ConnectException");
    }

    @Test
    void errorMessageRedactsUrlsInsideTheRootCauseMessage() {
        Exception error = new IOException("GET https://host.test/feed?token=SECRET returned garbage");

        assertThat(LogSafe.errorMessage(error)).isEqualTo("GET https://host.test/feed returned garbage");
    }

    @Test
    void errorMessageIsCappedAtThreeHundredCharacters() {
        Exception error = new IOException("x".repeat(LogSafe.MAX_MESSAGE_LENGTH + 50));

        assertThat(LogSafe.errorMessage(error)).hasSize(LogSafe.MAX_MESSAGE_LENGTH);
    }

    @Test
    void errorMessageIsNullWhenTheRootCauseHasNoMessage() {
        assertThat(LogSafe.errorMessage(new IOException())).isNull();
        assertThat(LogSafe.errorMessage(new IOException("   "))).isNull();
    }

    @Test
    void aSelfReferencingCauseDoesNotLoopForever() {
        Exception error = new Exception("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(LogSafe.errorMessage(error)).isEqualTo("self");
    }
}
