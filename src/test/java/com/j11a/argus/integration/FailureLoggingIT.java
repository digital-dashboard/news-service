package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.testsupport.FeedStubServer;
import com.j11a.argus.testsupport.Fixtures;
import com.j11a.argus.testsupport.LogCapture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** What an operator reads in Loki when a feed misbehaves: one WARN per failure, one ERROR per threshold crossing. */
class FailureLoggingIT extends AbstractIntegrationTest {

    private static final String PATH = "/failing/feed.xml";
    private static final String TOKEN_QUERY = "?token=SECRET-TOKEN";
    private static final byte[] EMPTY_FEED = Fixtures.emptyRss("https://failing.example.test");

    @Autowired
    private FeedIngestService ingestService;

    private long createHealthyFeed() {
        stub.serve(PATH, 200, "application/rss+xml", EMPTY_FEED, Map.of("ETag", "\"v1\""));
        return feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH + TOKEN_QUERY, null, Topic.NEWS, null))
                .id();
    }

    private String sourceKey(long feedId) {
        return jdbcClient.sql("SELECT s.key FROM source s JOIN feed f ON f.source_id = s.id WHERE f.id = :id")
                .param("id", feedId).query(String.class).single();
    }

    private void failWith503() {
        stub.serve(PATH, 503, "text/plain", FeedStubServer.utf8("busy"));
    }

    private static List<ILoggingEvent> fromIngest(LogCapture logs, Level level) {
        return logs.at(level, FeedIngestService.class);
    }

    @Test
    void aFailedFetchLogsOneWarnWithEveryDiagnosticFieldAndNoSecrets() {
        long feedId = createHealthyFeed();
        failWith503();

        try (LogCapture logs = LogCapture.start()) {
            IngestReport report = ingestService.refresh(feedId);

            assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.FAILED);
            assertThat(fromIngest(logs, Level.WARN)).singleElement().satisfies(event -> {
                assertThat(LogCapture.field(event, "feedId")).hasToString(String.valueOf(feedId));
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("sourceKey", sourceKey(feedId))
                        .containsEntry("url", stub.baseUrl() + PATH)
                        .containsEntry("reason", "http_status")
                        .containsEntry("httpStatus", 503)
                        .containsEntry("errorType", "HttpStatus")
                        .containsEntry("errorMessage", "503 Service Unavailable")
                        .containsEntry("consecutiveFailures", 1)
                        .containsEntry("failingThreshold", 3)
                        .doesNotContainKeys("contentType", "bodyBytes");
                assertThat(event.getFormattedMessage())
                        .contains("feed " + feedId)
                        .contains("http_status HttpStatus: 503 Service Unavailable");
            });
            assertThat(fromIngest(logs, Level.ERROR)).isEmpty();
            logs.assertNothingLogged("SECRET-TOKEN");
        }
    }

    @Test
    void theThirdFailureAlsoLogsExactlyOneErrorAndTheFourthDoesNot() {
        long feedId = createHealthyFeed();
        failWith503();

        try (LogCapture logs = LogCapture.start()) {
            ingestService.refresh(feedId);
            ingestService.refresh(feedId);
            assertThat(fromIngest(logs, Level.ERROR)).isEmpty();

            ingestService.refresh(feedId);
            assertThat(fromIngest(logs, Level.ERROR)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("consecutiveFailures", 3)
                        .containsEntry("failingThreshold", 3)
                        .containsEntry("reason", "http_status")
                        .containsEntry("errorType", "HttpStatus")
                        .containsEntry("url", stub.baseUrl() + PATH);
                assertThat(event.getFormattedMessage())
                        .contains("is now failing after 3 consecutive failures")
                        .contains("http_status HttpStatus: 503 Service Unavailable");
            });

            ingestService.refresh(feedId);
            assertThat(fromIngest(logs, Level.ERROR)).hasSize(1);
            assertThat(fromIngest(logs, Level.WARN)).hasSize(4);
            logs.assertNothingLogged("SECRET-TOKEN");
        }
    }

    @Test
    void aSuccessAfterFailuresLogsAnInfoRecoveryWithThePreviousCountOnce() {
        long feedId = createHealthyFeed();
        failWith503();
        ingestService.refresh(feedId);
        ingestService.refresh(feedId);
        stub.serve(PATH, 200, "application/rss+xml", EMPTY_FEED);

        try (LogCapture logs = LogCapture.start()) {
            ingestService.refresh(feedId);
            ingestService.refresh(feedId);

            assertThat(fromIngest(logs, Level.INFO).stream()
                    .filter(event -> event.getFormattedMessage().contains("recovered"))).singleElement()
                    .satisfies(event -> {
                        assertThat(event.getFormattedMessage())
                                .contains("Feed " + feedId + " (" + sourceKey(feedId) + ")")
                                .contains("recovered after 2 consecutive failures");
                        assertThat(LogCapture.keyValues(event)).containsEntry("consecutiveFailures", 2);
                    });
        }
    }

    @Test
    void aNotModifiedAnswerAfterAFailureAlsoLogsTheRecovery() {
        long feedId = createHealthyFeed();
        failWith503();
        ingestService.refresh(feedId);
        stub.serve(PATH, 304, null, new byte[0], Map.of("ETag", "\"v1\""));

        try (LogCapture logs = LogCapture.start()) {
            IngestReport report = ingestService.refresh(feedId);

            assertThat(report.outcome()).isEqualTo(IngestReport.Outcome.NOT_MODIFIED);
            assertThat(fromIngest(logs, Level.INFO).stream()
                    .filter(event -> event.getFormattedMessage().contains("recovered"))).singleElement()
                    .satisfies(event -> assertThat(LogCapture.keyValues(event)).containsEntry("consecutiveFailures", 1));
        }
    }

    @Test
    void aFeedThatNeverFailedLogsNoRecovery() {
        long feedId = createHealthyFeed();

        try (LogCapture logs = LogCapture.start()) {
            ingestService.refresh(feedId);

            assertThat(logs.messagesAt(Level.INFO)).noneMatch(message -> message.contains("recovered"));
        }
    }

    @Test
    void aParseFailureLogsWarnWithTheContentTypeAndBodySize() {
        long feedId = createHealthyFeed();
        byte[] html = Fixtures.feed("not-a-feed.html");
        stub.serve(PATH, 200, "text/html", html);

        try (LogCapture logs = LogCapture.start()) {
            IngestReport report = ingestService.refresh(feedId);

            assertThat(report.failureReason()).isEqualTo("not_a_feed");
            assertThat(fromIngest(logs, Level.WARN)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event))
                            .containsEntry("reason", "not_a_feed")
                            .containsEntry("errorType", "FeedParseException")
                            .containsEntry("contentType", "text/html")
                            .containsEntry("bodyBytes", html.length)
                            .doesNotContainKey("httpStatus"));
        }
    }

    @Test
    void aConnectionRefusedLogsTheConnectExceptionTypeAndTheRedactedUrl() throws Exception {
        long feedId = createHealthyFeed();
        String deadBase;
        try (FeedStubServer closed = new FeedStubServer()) {
            deadBase = closed.baseUrl();
        }
        jdbcClient.sql("UPDATE feed SET url = :url WHERE id = :id")
                .param("url", deadBase + PATH + TOKEN_QUERY).param("id", feedId).update();

        try (LogCapture logs = LogCapture.start()) {
            ingestService.refresh(feedId);

            assertThat(fromIngest(logs, Level.WARN)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event))
                            .containsEntry("reason", "io")
                            .containsEntry("errorType", "ConnectException")
                            .containsEntry("url", deadBase + PATH));
            logs.assertNothingLogged("SECRET-TOKEN");
        }
    }
}
