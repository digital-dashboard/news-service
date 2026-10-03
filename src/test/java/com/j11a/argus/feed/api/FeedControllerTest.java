package com.j11a.argus.feed.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j11a.argus.config.WebMvcConfig;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.health.FeedState;
import com.j11a.argus.feed.poll.AggregatePollReport;
import com.j11a.argus.feed.poll.FeedPoller;
import com.j11a.argus.feed.poll.PollInterruptedException;
import com.j11a.argus.feed.poll.PollTrigger;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.security.SecurityConfig;
import com.j11a.argus.source.SourceSummary;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import com.j11a.argus.web.error.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(FeedController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, WebMvcConfig.class, AdminKeys.SliceProperties.class})
class FeedControllerTest {

    private static final String FEEDS = "/news/v2/feeds";
    private static final String VALID_BODY = "{\"url\":\"https://example.test/rss.xml\",\"topic\":\"TECH\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeedService feeds;

    @MockitoBean
    private FeedIngestService ingest;

    @MockitoBean
    private FeedPoller poller;

    private static MockHttpServletRequestBuilder adminPost(String path, String body) {
        return post(path).header(AdminKeys.HEADER, AdminKeys.VALID).contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static FeedResponse feed(long id) {
        return new FeedResponse(id, "Example", "https://example.test/rss.xml", "https://example.test", Topic.TECH,
                true, new SourceSummary(3, "example.test", "https://example.test", null),
                Instant.parse("2026-10-02T10:00:00Z"), null, null, null, 0, FeedState.HEALTHY);
    }

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        when(feeds.create(any())).thenReturn(feed(42));

        mockMvc.perform(adminPost(FEEDS, VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/news/v2/feeds/42"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.topic").value("TECH"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.source.name").value("example.test"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-02T10:00:00Z"));
    }

    @Test
    void blankUrlIsAFieldError() throws Exception {
        mockMvc.perform(adminPost(FEEDS, "{\"url\":\"  \",\"topic\":\"TECH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='url')]").isNotEmpty());
        verifyNoInteractions(feeds);
    }

    @Test
    void nonHttpUrlIsAFieldError() throws Exception {
        mockMvc.perform(adminPost(FEEDS, "{\"url\":\"ftp://example.test/rss\",\"topic\":\"TECH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("url"));
    }

    @Test
    void urlWithUserInfoIsAFieldError() throws Exception {
        mockMvc.perform(adminPost(FEEDS, "{\"url\":\"http://user:pass@example.test/feed\",\"topic\":\"TECH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("url"));
        verifyNoInteractions(feeds);
    }

    @Test
    void relativeUrlIsAFieldError() throws Exception {
        mockMvc.perform(adminPost(FEEDS, "{\"url\":\"/rss.xml\",\"topic\":\"TECH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("url"));
    }

    @Test
    void missingTopicIsAFieldError() throws Exception {
        mockMvc.perform(adminPost(FEEDS, "{\"url\":\"https://example.test/rss.xml\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("topic"));
    }

    @Test
    void unknownTopicIsAFieldErrorNotA500() throws Exception {
        mockMvc.perform(adminPost(FEEDS, "{\"url\":\"https://example.test/rss.xml\",\"topic\":\"GOSSIP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("topic"));
    }

    @Test
    void overlongUrlIsAFieldError() throws Exception {
        String url = "https://example.test/" + "a".repeat(2048);

        mockMvc.perform(adminPost(FEEDS, "{\"url\":\"" + url + "\",\"topic\":\"TECH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("url"));
    }

    @Test
    void duplicateUrlIs409CarryingTheExistingFeedId() throws Exception {
        when(feeds.create(any())).thenThrow(new ApiException(ErrorCode.FEED_URL_CONFLICT, "exists",
                Map.of("existingFeedId", 9L)));

        mockMvc.perform(adminPost(FEEDS, VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEED_URL_CONFLICT"))
                .andExpect(jsonPath("$.existingFeedId").value(9));
    }

    @Test
    void unreadableFeedIs422CarryingTheReason() throws Exception {
        when(feeds.create(any())).thenThrow(new ApiException(ErrorCode.FEED_INVALID, "not readable",
                Map.of("reason", "not_a_feed")));

        mockMvc.perform(adminPost(FEEDS, VALID_BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FEED_INVALID"))
                .andExpect(jsonPath("$.reason").value("not_a_feed"));
    }

    @Test
    void getReturnsTheFeed() throws Exception {
        when(feeds.get(42)).thenReturn(feed(42));

        mockMvc.perform(get(FEEDS + "/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.test/rss.xml"));
    }

    @Test
    void getOfAnUnknownFeedIs404() throws Exception {
        when(feeds.get(7)).thenThrow(new ApiException(ErrorCode.FEED_NOT_FOUND, "no feed"));

        mockMvc.perform(get(FEEDS + "/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
    }

    @Test
    void refreshReturnsTheIngestReport() throws Exception {
        when(ingest.refresh(42)).thenReturn(
                new IngestReport(42, IngestReport.Outcome.COMPLETED, null, 5, 3, 2, 0));

        mockMvc.perform(adminPost(FEEDS + "/42/refresh", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedId").value(42))
                .andExpect(jsonPath("$.outcome").value("COMPLETED"))
                .andExpect(jsonPath("$.entriesSeen").value(5))
                .andExpect(jsonPath("$.inserted").value(3))
                .andExpect(jsonPath("$.unchanged").value(2))
                .andExpect(jsonPath("$.skipped").value(0));
    }

    @Test
    void failedRefreshIsStill200WithTheReason() throws Exception {
        when(ingest.refresh(42)).thenReturn(
                new IngestReport(42, IngestReport.Outcome.FAILED, "http_status", 0, 0, 0, 0));

        mockMvc.perform(adminPost(FEEDS + "/42/refresh", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("http_status"));
    }

    @Test
    void refreshOfAnUnknownFeedIs404() throws Exception {
        when(ingest.refresh(7)).thenThrow(new ApiException(ErrorCode.FEED_NOT_FOUND, "no feed"));

        mockMvc.perform(adminPost(FEEDS + "/7/refresh", ""))
                .andExpect(status().isNotFound());
    }

    @Test
    void createWithoutTheKeyIs401() throws Exception {
        mockMvc.perform(post(FEEDS).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_KEY_REQUIRED"));
        verifyNoInteractions(feeds);
    }

    @Test
    void refreshWithoutTheKeyIs401() throws Exception {
        mockMvc.perform(post(FEEDS + "/42/refresh"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(ingest);
    }

    @Test
    void listReturnsPagedShapeWithHealth() throws Exception {
        when(feeds.list(0, 20)).thenReturn(new PageImpl<>(List.of(feed(42)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(FEEDS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(42))
                .andExpect(jsonPath("$.content[0].state").value("healthy"))
                .andExpect(jsonPath("$.content[0].consecutiveFailures").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.size").value(20));
    }

    @Test
    void listOffsetBeyondIntRangeIsRejected() throws Exception {
        mockMvc.perform(get(FEEDS).param("page", "100000000").param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("page"));
        verifyNoInteractions(feeds);
    }

    static Stream<Arguments> badPaging() {
        return Stream.of(
                Arguments.of("size", "101"),
                Arguments.of("size", "0"),
                Arguments.of("page", "-1"),
                Arguments.of("size", "abc"));
    }

    @ParameterizedTest(name = "{0}={1} is 400 VALIDATION_FAILED")
    @MethodSource("badPaging")
    void listBadPagingIsRejected(String name, String value) throws Exception {
        mockMvc.perform(get(FEEDS).param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(name));
        verifyNoInteractions(feeds);
    }

    @Test
    void refreshAllRequiresAdminKey() throws Exception {
        mockMvc.perform(post(FEEDS + "/refresh"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(poller);
    }

    @Test
    void refreshAllCallsPollerAndReturnsAggregateReport() throws Exception {
        AggregatePollReport report = new AggregatePollReport(
                "poll-1", PollTrigger.MANUAL, 250, 2, 2, 0, 0, 10, 5, 5, 0, List.of());
        when(poller.poll(PollTrigger.MANUAL)).thenReturn(report);

        mockMvc.perform(post(FEEDS + "/refresh").header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pollId").value("poll-1"))
                .andExpect(jsonPath("$.trigger").value("manual"))
                .andExpect(jsonPath("$.durationMs").value(250))
                .andExpect(jsonPath("$.feedsPolled").value(2))
                .andExpect(jsonPath("$.succeeded").value(2));
    }

    @Test
    void refreshAllWhenPollInProgressReturns409() throws Exception {
        when(poller.poll(PollTrigger.MANUAL)).thenThrow(
                new InvocationRejectedException("running", poller));

        mockMvc.perform(post(FEEDS + "/refresh").header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLL_IN_PROGRESS"))
                .andExpect(jsonPath("$.type").value("urn:argus:problem:poll-in-progress"));
    }

    @Test
    void refreshAllInterruptedByShutdownReturns503() throws Exception {
        when(poller.poll(PollTrigger.MANUAL)).thenThrow(new PollInterruptedException(new InterruptedException()));

        mockMvc.perform(post(FEEDS + "/refresh").header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void patchRequiresAdminKey() throws Exception {
        mockMvc.perform(patch(FEEDS + "/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(feeds);
    }

    @Test
    void patchNullOrEmptyBodyIs400() throws Exception {
        mockMvc.perform(patch(FEEDS + "/42").header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(patch(FEEDS + "/42").header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verifyNoInteractions(feeds);
    }

    @Test
    void patchUnknownFeedIs404() throws Exception {
        when(feeds.patch(eq(7L), any())).thenThrow(new ApiException(ErrorCode.FEED_NOT_FOUND, "no feed"));

        mockMvc.perform(patch(FEEDS + "/7").header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
    }

    @Test
    void patchEnablesOrDisablesFeedAndReturnsUpdatedFeed() throws Exception {
        FeedResponse updated = new FeedResponse(42, "Example", "https://example.test/rss.xml", null, Topic.TECH,
                false, new SourceSummary(3, "example.test", "https://example.test", null),
                Instant.parse("2026-10-02T10:00:00Z"), null, null, null, 0, FeedState.DISABLED);
        when(feeds.patch(eq(42L), any())).thenReturn(updated);

        mockMvc.perform(patch(FEEDS + "/42").header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.state").value("disabled"));
    }

    @Test
    void deleteRequiresAdminKey() throws Exception {
        mockMvc.perform(delete(FEEDS + "/42"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(feeds);
    }

    @Test
    void deleteUnknownFeedIs404() throws Exception {
        doThrow(new ApiException(ErrorCode.FEED_NOT_FOUND, "no feed")).when(feeds).delete(7L);

        mockMvc.perform(delete(FEEDS + "/7").header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
    }

    @Test
    void deleteSuccessIs204() throws Exception {
        mockMvc.perform(delete(FEEDS + "/42").header(AdminKeys.HEADER, AdminKeys.VALID))
                .andExpect(status().isNoContent());

        verify(feeds).delete(42L);
    }
}
