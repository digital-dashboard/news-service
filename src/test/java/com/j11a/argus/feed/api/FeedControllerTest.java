package com.j11a.argus.feed.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j11a.argus.config.WebMvcConfig;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.ingest.IngestReport;
import com.j11a.argus.security.SecurityConfig;
import com.j11a.argus.source.SourceSummary;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.web.error.ApiException;
import com.j11a.argus.web.error.ErrorCode;
import com.j11a.argus.web.error.GlobalExceptionHandler;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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

    private static MockHttpServletRequestBuilder adminPost(String path, String body) {
        return post(path).header(AdminKeys.HEADER, AdminKeys.VALID).contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static FeedResponse feed(long id) {
        return new FeedResponse(id, "Example", "https://example.test/rss.xml", "https://example.test", Topic.TECH,
                true, new SourceSummary(3, "example.test", "https://example.test", null),
                Instant.parse("2026-10-02T10:00:00Z"));
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
}
