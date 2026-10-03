package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.feed.api.FeedService;
import com.j11a.argus.feed.api.PatchFeedRequest;
import com.j11a.argus.source.PatchSourceRequest;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.testsupport.FeedStubServer;
import com.j11a.argus.testsupport.Fixtures;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.web.error.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Who changed what: create, delete, enable, disable and the source PATCH leave one audit line each. */
class AuditLoggingIT extends AbstractIntegrationTest {

    private static final String PATH = "/audit/feed.xml";
    private static final String TOKEN_QUERY = "?token=SECRET-TOKEN";

    @Autowired
    private SourceService sourceService;

    private static List<ILoggingEvent> from(LogCapture logs, Level level, Class<?> logger) {
        return logs.at(level).stream().filter(event -> logger.getName().equals(event.getLoggerName())).toList();
    }

    private static void assertNoSecret(LogCapture logs) {
        for (Level level : List.of(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR)) {
            logs.at(level).forEach(event -> assertThat(event.getFormattedMessage() + LogCapture.keyValues(event))
                    .doesNotContain("SECRET-TOKEN"));
        }
    }

    @Test
    void aCreatedFeedLogsAnInfoWithItsIdSourceAndRedactedUrl() {
        stub.serveFixture(PATH, "bbc-like-rss2.xml");

        try (LogCapture logs = LogCapture.start()) {
            FeedResponse created = feedService.create(
                    new CreateFeedRequest(stub.baseUrl() + PATH + TOKEN_QUERY, null, Topic.WORLD, null));

            assertThat(from(logs, Level.INFO, FeedService.class)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("feedId", created.id())
                        .containsEntry("sourceId", created.source().id())
                        .containsEntry("url", stub.baseUrl() + PATH)
                        .containsKey("sourceKey");
                assertThat(event.getFormattedMessage())
                        .startsWith("Feed " + created.id() + " created: " + created.name())
                        .contains("topic WORLD")
                        .endsWith("from " + stub.baseUrl() + PATH);
            });
            assertNoSecret(logs);
        }
    }

    @Test
    void aFeedThatCannotBeReadLogsAWarnWithTheReasonAndTheErrorType() {
        stub.serve(PATH, 200, "text/html", Fixtures.feed("not-a-feed.html"));
        CreateFeedRequest request = new CreateFeedRequest(stub.baseUrl() + PATH + TOKEN_QUERY, null, Topic.NEWS, null);

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> feedService.create(request)).isInstanceOf(ApiException.class);

            assertThat(from(logs, Level.WARN, FeedService.class)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event))
                            .containsEntry("url", stub.baseUrl() + PATH)
                            .containsEntry("reason", "not_a_feed")
                            .containsEntry("errorType", "FeedParseException")
                            .containsKey("errorMessage")
                            .containsEntry("contentType", "text/html"));
            assertNoSecret(logs);
        }
    }

    @Test
    void anUnreachableFeedLogsTheFetchFailureOnCreate() {
        stub.serve(PATH, 503, "text/plain", FeedStubServer.utf8("busy"));
        CreateFeedRequest request = new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS, null);

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> feedService.create(request)).isInstanceOf(ApiException.class);

            assertThat(from(logs, Level.WARN, FeedService.class)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event))
                            .containsEntry("reason", "http_status")
                            .containsEntry("errorType", "HttpStatus")
                            .containsEntry("errorMessage", "503 Service Unavailable")
                            .doesNotContainKeys("contentType", "bodyBytes"));
        }
    }

    @Test
    void anExactDuplicateUrlLogsAnInfoWithTheExistingFeedId() {
        stub.serveFixture(PATH, "bbc-like-rss2.xml");
        FeedResponse existing = feedService.create(
                new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS, null));
        CreateFeedRequest duplicate = new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS, null);

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> feedService.create(duplicate)).isInstanceOf(ApiException.class);

            assertThat(from(logs, Level.INFO, FeedService.class)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event))
                            .containsEntry("existingFeedId", existing.id())
                            .containsEntry("url", stub.baseUrl() + PATH));
        }
    }

    @Test
    void disablingAndEnablingAFeedLogsAnInfoEach() {
        stub.serveFixture(PATH, "bbc-like-rss2.xml");
        long id = feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS, null)).id();

        try (LogCapture logs = LogCapture.start()) {
            feedService.patch(id, new PatchFeedRequest(false));
            feedService.patch(id, new PatchFeedRequest(true));

            assertThat(from(logs, Level.INFO, FeedService.class)).satisfiesExactly(
                    disabled -> {
                        assertThat(disabled.getFormattedMessage()).isEqualTo("Feed " + id + " disabled");
                        assertThat(LogCapture.keyValues(disabled))
                                .containsEntry("feedId", id).containsEntry("enabled", false);
                    },
                    enabled -> {
                        assertThat(enabled.getFormattedMessage()).isEqualTo("Feed " + id + " enabled");
                        assertThat(LogCapture.keyValues(enabled)).containsEntry("enabled", true);
                    });
        }
    }

    @Test
    void deletingAFeedLogsAnInfoWithTheNumberOfArticlesRemoved() {
        stub.serveFixture(PATH, "bbc-like-rss2.xml");
        long id = feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS, null)).id();
        long articles = count("SELECT count(*) FROM article");
        assertThat(articles).isPositive();

        try (LogCapture logs = LogCapture.start()) {
            feedService.delete(id);

            assertThat(from(logs, Level.INFO, FeedService.class)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry("feedId", id)
                        .containsEntry("articlesRemoved", (int) articles)
                        .containsKey("sourceId");
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Feed " + id + " deleted along with " + articles + " articles");
            });
        }
    }

    @Test
    void aSourcePatchLogsTheChangedFieldsAndTheirNewValues() {
        stub.serveFixture(PATH, "bbc-like-rss2.xml");
        FeedResponse feed = feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS, null));
        long sourceId = feed.source().id();

        try (LogCapture logs = LogCapture.start()) {
            sourceService.patch(sourceId, new PatchSourceRequest("Renamed", "https://home.example.test/x?t=SECRET-TOKEN", "ca"));

            assertThat(from(logs, Level.INFO, SourceService.class)).singleElement().satisfies(event -> {
                Map<String, Object> fields = LogCapture.keyValues(event);
                assertThat(fields)
                        .containsEntry("sourceId", sourceId)
                        .containsEntry("changedFields", List.of("name", "homepage", "country"))
                        .containsEntry("newName", "Renamed")
                        .containsEntry("newHomepage", "https://home.example.test/x")
                        .containsEntry("newCountry", "CA")
                        .containsKey("sourceKey");
                assertThat(event.getFormattedMessage()).contains("updated").contains("name=Renamed");
            });
            assertNoSecret(logs);
        }
    }

    @Test
    void aPartialSourcePatchLogsOnlyTheFieldsThatChanged() {
        stub.serveFixture(PATH, "bbc-like-rss2.xml");
        long sourceId = feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS, null))
                .source().id();

        try (LogCapture logs = LogCapture.start()) {
            sourceService.patch(sourceId, new PatchSourceRequest(null, null, "gb"));

            assertThat(from(logs, Level.INFO, SourceService.class)).singleElement().satisfies(event ->
                    assertThat(LogCapture.keyValues(event))
                            .containsEntry("changedFields", List.of("country"))
                            .doesNotContainKeys("newName", "newHomepage"));
        }
    }
}
