package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.feed.api.FeedService;
import com.j11a.argus.feed.identity.FeedRedirectApplier;
import com.j11a.argus.observability.LogKeys;
import com.j11a.argus.source.PatchSourceRequest;
import com.j11a.argus.source.SourceMerger;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.testsupport.FeedStubServer;
import com.j11a.argus.testsupport.Fixtures;
import com.j11a.argus.testsupport.LogCapture;
import com.j11a.argus.testsupport.MergeData;
import com.j11a.argus.testsupport.PatchRequests;
import com.j11a.argus.web.error.ApiException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Who changed what: create, delete, enable, disable and the source PATCH leave one audit line each. */
class AuditLoggingIT extends AbstractIntegrationTest {

    private static final String PATH = "/audit/feed.xml";
    private static final String TOKEN_QUERY = "?token=SECRET-TOKEN";
    private static final String SECRET = "SECRET-TOKEN";
    private static final String REDIRECT_KEY = "redirect.example.test";
    private static final String MERGED_KEY = "bbci.co.uk";
    private static final String TARGET_KEY = "bbc.co.uk";
    private static final String OLD_URL = "http://redirect.example.test/old";
    private static final String NEW_URL = "https://redirect.example.test/new";
    private static final String HELD_URL = "https://redirect.example.test/held";
    private static final String GUID = "g1";
    private static final Instant FETCHED = Instant.parse("2026-10-01T08:00:00Z");

    @Autowired
    private SourceService sourceService;

    @Autowired
    private SourceMerger merger;

    @Autowired
    private FeedRedirectApplier redirectApplier;

    @Autowired
    private FeedInserter inserter;

    private static List<ILoggingEvent> from(LogCapture logs, Level level, Class<?> logger) {
        return logs.at(level, logger);
    }

    private ResultActions postFeed(String url) throws Exception {
        return mockMvc.perform(post("/news/v2/feeds").header(AdminKeys.HEADER, AdminKeys.VALID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + url + "\",\"topic\":\"NEWS\"}"));
    }

    private static List<ILoggingEvent> aboveDebug(LogCapture logs) {
        return Stream.of(Level.INFO, Level.WARN, Level.ERROR)
                .flatMap(level -> logs.at(level).stream())
                .filter(event -> event.getLoggerName().startsWith("com.j11a.argus"))
                .toList();
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
                        .contains("Feed " + created.id() + " created: " + created.name())
                        .contains("topic WORLD")
                        .contains("from " + stub.baseUrl() + PATH);
            });
            logs.assertNothingLogged(SECRET);
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
            logs.assertNothingLogged(SECRET);
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
    void aRejectedCreateRequestLogsExactlyOneLineAboveDebug() throws Exception {
        stub.serve(PATH, 200, "text/html", Fixtures.feed("not-a-feed.html"));

        try (LogCapture logs = LogCapture.start()) {
            postFeed(stub.baseUrl() + PATH).andExpect(status().isUnprocessableContent());

            assertThat(aboveDebug(logs)).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getLoggerName()).isEqualTo(FeedService.class.getName());
            });
        }
    }

    @Test
    void aConflictingCreateRequestLogsExactlyOneLineAboveDebug() throws Exception {
        stub.serveFixture(PATH, "bbc-like-rss2.xml");
        feedService.create(new CreateFeedRequest(stub.baseUrl() + PATH, null, Topic.NEWS, null));

        try (LogCapture logs = LogCapture.start()) {
            postFeed(stub.baseUrl() + PATH).andExpect(status().isConflict());

            assertThat(aboveDebug(logs)).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getLoggerName()).isEqualTo(FeedService.class.getName());
            });
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
            feedService.patch(id, PatchRequests.enabled(false));
            feedService.patch(id, PatchRequests.enabled(true));

            assertThat(from(logs, Level.INFO, FeedService.class)).satisfiesExactly(
                    disabled -> {
                        assertThat(disabled.getFormattedMessage()).contains("Feed " + id).contains("disabled");
                        assertThat(LogCapture.keyValues(disabled))
                                .containsEntry("feedId", id).containsEntry("enabled", false);
                    },
                    enabled -> {
                        assertThat(enabled.getFormattedMessage()).contains("Feed " + id).contains("enabled");
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
                        .contains("Feed " + id + " deleted")
                        .contains(articles + " articles");
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
                        .containsEntry(LogKeys.NEW_NAME, "Renamed")
                        .containsEntry(LogKeys.NEW_HOMEPAGE, "https://home.example.test/x")
                        .containsEntry(LogKeys.NEW_COUNTRY, "CA")
                        .containsKey("sourceKey");
                assertThat(event.getFormattedMessage()).contains("updated").contains("name=Renamed");
            });
            logs.assertNothingLogged(SECRET);
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
                            .doesNotContainKeys(LogKeys.NEW_NAME, LogKeys.NEW_HOMEPAGE));
        }
    }

    private long insertFeed(String sourceKey, String url) {
        long sourceId = sourceService.findOrCreate(sourceKey, null).getId();
        return inserter.insert(new NewFeed(sourceId, "F", url, null, null, Topic.TECH, null)).orElseThrow();
    }

    @Test
    void aSourceMergeLogsAnInfoWithBothSourceIdsAndTheCounts() {
        MergeData data = new MergeData(jdbcClient);
        long source = data.source(MERGED_KEY, null);
        long target = data.source(TARGET_KEY, null);
        data.feed(source, "https://bbci.co.uk/feed" + TOKEN_QUERY);
        data.article(source, GUID, null, FETCHED);

        try (LogCapture logs = LogCapture.start()) {
            merger.merge(source, target);

            assertThat(from(logs, Level.INFO, SourceMerger.class)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry(LogKeys.SOURCE_ID, source)
                        .containsEntry(LogKeys.SOURCE_KEY, MERGED_KEY)
                        .containsEntry(LogKeys.TARGET_SOURCE_ID, target)
                        .containsEntry(LogKeys.FEEDS_MOVED, 1)
                        .containsEntry(LogKeys.ARTICLES_MOVED, 1)
                        .containsEntry(LogKeys.ARTICLES_COLLAPSED, 0)
                        .containsEntry(LogKeys.LINKS_FOLDED, 0);
                assertThat(event.getFormattedMessage()).contains("Source " + source).contains("merged into " + target);
            });
            logs.assertNothingLogged(SECRET);
        }
    }

    @Test
    void aFeedMoveLogsAnInfoWithTheFeedBothSourcesAndTheCounts() {
        MergeData data = new MergeData(jdbcClient);
        long source = data.source(MERGED_KEY, null);
        long target = data.source(TARGET_KEY, null);
        long feed = data.feed(source, "https://bbci.co.uk/feed" + TOKEN_QUERY);
        long article = data.article(source, GUID, null, FETCHED);
        data.link(article, feed, FETCHED, "h");

        try (LogCapture logs = LogCapture.start()) {
            merger.moveFeed(feed, target);

            assertThat(from(logs, Level.INFO, SourceMerger.class)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry(LogKeys.FEED_ID, feed)
                        .containsEntry(LogKeys.SOURCE_ID, source)
                        .containsEntry(LogKeys.TARGET_SOURCE_ID, target)
                        .containsEntry(LogKeys.ARTICLES_MOVED, 1)
                        .containsEntry(LogKeys.ARTICLES_COPIED, 0)
                        .containsEntry(LogKeys.ARTICLES_COLLAPSED, 0)
                        .containsEntry(LogKeys.SOURCE_DELETED, true);
                assertThat(event.getFormattedMessage()).contains("Feed " + feed + " moved from source " + source);
            });
            logs.assertNothingLogged(SECRET);
        }
    }

    @Test
    void anAppliedPermanentRedirectLogsAnInfoWithRedactedOldAndNewUrls() {
        long feed = insertFeed(REDIRECT_KEY, OLD_URL + TOKEN_QUERY);

        try (LogCapture logs = LogCapture.start()) {
            redirectApplier.apply(feed, REDIRECT_KEY, OLD_URL + TOKEN_QUERY,
                    URI.create(NEW_URL + TOKEN_QUERY));

            assertThat(from(logs, Level.INFO, FeedRedirectApplier.class)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry(LogKeys.FEED_ID, feed)
                        .containsEntry(LogKeys.SOURCE_KEY, REDIRECT_KEY)
                        .containsEntry(LogKeys.URL, OLD_URL)
                        .containsEntry(LogKeys.NEW_URL, NEW_URL);
                assertThat(event.getFormattedMessage()).contains("Feed " + feed + " moved to");
            });
            logs.assertNothingLogged(SECRET);
        }
    }

    @Test
    void aRedirectOntoAnotherFeedLogsAWarnWithTheDuplicateReasonAndRedactedUrls() {
        long holder = insertFeed(REDIRECT_KEY, HELD_URL + TOKEN_QUERY);
        long feed = insertFeed(REDIRECT_KEY, OLD_URL);

        try (LogCapture logs = LogCapture.start()) {
            redirectApplier.apply(feed, REDIRECT_KEY, OLD_URL,
                    URI.create(HELD_URL + TOKEN_QUERY));

            assertThat(from(logs, Level.WARN, FeedRedirectApplier.class)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry(LogKeys.FEED_ID, feed)
                        .containsEntry(LogKeys.EXISTING_FEED_ID, holder)
                        .containsEntry(LogKeys.SOURCE_KEY, REDIRECT_KEY)
                        .containsEntry(LogKeys.REASON, "duplicate_feed")
                        .containsEntry(LogKeys.URL, OLD_URL)
                        .containsEntry(LogKeys.NEW_URL, HELD_URL);
                assertThat(event.getFormattedMessage()).contains("Feed " + feed + " disabled").contains("duplicates feed " + holder);
            });
            logs.assertNothingLogged(SECRET);
        }
    }

    @Test
    void aRedirectClaimedOnlyAsAnotherFeedsSelfLinkLogsAWarnWithRedactedUrlsAndLeavesTheFeedEnabled() {
        long holder = insertFeed(REDIRECT_KEY, "https://holder.example.test/f");
        jdbcClient.sql("UPDATE feed SET self_url = :s WHERE id = :id")
                .param("s", HELD_URL + TOKEN_QUERY).param("id", holder).update();
        long feed = insertFeed(REDIRECT_KEY, OLD_URL);

        try (LogCapture logs = LogCapture.start()) {
            redirectApplier.apply(feed, REDIRECT_KEY, OLD_URL, URI.create(HELD_URL + TOKEN_QUERY));

            assertThat(from(logs, Level.WARN, FeedRedirectApplier.class)).singleElement().satisfies(event -> {
                assertThat(LogCapture.keyValues(event))
                        .containsEntry(LogKeys.FEED_ID, feed)
                        .containsEntry(LogKeys.EXISTING_FEED_ID, holder)
                        .containsEntry(LogKeys.SOURCE_KEY, REDIRECT_KEY)
                        .containsEntry(LogKeys.REASON, "self_link_claimed")
                        .containsEntry(LogKeys.URL, OLD_URL)
                        .containsEntry(LogKeys.NEW_URL, HELD_URL);
                assertThat(event.getFormattedMessage()).contains("Feed " + feed + " keeps its URL")
                        .contains("claimed as a self link by feed " + holder);
            });
            logs.assertNothingLogged(SECRET);
        }
        assertThat(count("SELECT count(*) FROM feed WHERE id = " + feed + " AND enabled")).isOne();
    }
}
