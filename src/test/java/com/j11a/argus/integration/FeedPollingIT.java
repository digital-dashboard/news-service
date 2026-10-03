package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.feed.poll.AggregatePollReport;
import com.j11a.argus.feed.poll.FeedPoller;
import com.j11a.argus.feed.poll.PollTestHooks;
import com.j11a.argus.feed.poll.PollTrigger;
import com.j11a.argus.testsupport.AdminKeys;
import com.j11a.argus.testsupport.FeedStubServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class FeedPollingIT extends AbstractIntegrationTest {

    private static final String FEEDS = "/news/v2/feeds";
    private static final String ARTICLES = "/news/v2/articles";
    private static final AttributeKey<String> POLL_TRIGGER = AttributeKey.stringKey("trigger");

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    private FeedPoller poller;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private SpanCollectorConfig.CollectingSpanProcessor spans;

    @Test
    void fetchConcurrencyIsBoundedByConfiguredLimit() {
        for (int i = 1; i <= 6; i++) {
            String path = "/concurrency/feed-" + i + ".xml";
            createFeedFrom(path, "bbc-like-rss2.xml", Topic.WORLD);
            stub.serveFixtureWithDelay(path, "bbc-like-rss2.xml", 60);
        }

        stub.resetPeakInFlight();
        AggregatePollReport report = poller.poll(PollTrigger.MANUAL);

        assertThat(report.feedsPolled()).isEqualTo(6);
        assertThat(report.succeeded()).isEqualTo(6);
        assertThat(stub.peakInFlight()).isEqualTo(2);
    }

    @Test
    void overlappingManualPollReturns409AndScheduledPollRecordsSkipped() throws Exception {
        CountDownLatch enterLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        String path = "/overlap/feed.xml";
        createFeedFrom(path, "bbc-like-rss2.xml", Topic.WORLD);
        stub.serveWithLatch(path, enterLatch, releaseLatch, "bbc-like-rss2.xml");

        double skippedBefore = currentSkippedCount();

        CompletableFuture<AggregatePollReport> backgroundPoll = CompletableFuture.supplyAsync(
                () -> poller.poll(PollTrigger.MANUAL));

        boolean entered = enterLatch.await(5, TimeUnit.SECONDS);
        assertThat(entered).isTrue();

        try {
            mockMvc.perform(post(FEEDS + "/refresh").header(AdminKeys.HEADER, AdminKeys.VALID))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("POLL_IN_PROGRESS"));

            PollTestHooks.runScheduledPoll(context);
            assertThat(currentSkippedCount()).isEqualTo(skippedBefore + 1.0);
        } finally {
            releaseLatch.countDown();
        }

        AggregatePollReport report = backgroundPoll.get(5, TimeUnit.SECONDS);
        assertThat(report.succeeded()).isOne();
    }

    @Test
    void disabledFeedsAreOmittedFromPollAndToggleTakesEffectNextPoll() throws Exception {
        FeedResponse feed1 = createFeedFrom("/disabled/1.xml", "bbc-like-rss2.xml", Topic.WORLD);
        FeedResponse feed2 = createFeedFrom("/disabled/2.xml", "bbc-like-rss2.xml", Topic.TECH);

        mockMvc.perform(patch(FEEDS + "/" + feed2.id()).header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        AggregatePollReport report1 = poller.poll(PollTrigger.MANUAL);
        assertThat(report1.feedsPolled()).isEqualTo(1);
        assertThat(report1.reports()).extracting("feedId").containsExactly(feed1.id());

        mockMvc.perform(patch(FEEDS + "/" + feed2.id()).header(AdminKeys.HEADER, AdminKeys.VALID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        AggregatePollReport report2 = poller.poll(PollTrigger.MANUAL);
        assertThat(report2.feedsPolled()).isEqualTo(2);
        assertThat(report2.reports()).extracting("feedId").containsExactly(feed1.id(), feed2.id());
    }

    @Test
    void failingFeedDoesNotBlockOtherFeeds() {
        createFeedFrom("/isolation/ok.xml", "bbc-like-rss2.xml", Topic.WORLD);
        FeedResponse failingFeed = createFeedFrom("/isolation/fail.xml", "bbc-like-rss2.xml", Topic.TECH);

        stub.serve("/isolation/fail.xml", 500, "text/plain", FeedStubServer.utf8("Internal error"));

        AggregatePollReport report = poller.poll(PollTrigger.MANUAL);
        assertThat(report.feedsPolled()).isEqualTo(2);
        assertThat(report.succeeded()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);

        var failingRow = jdbcClient.sql("SELECT consecutive_failures, last_error FROM feed WHERE id = :id")
                .param("id", failingFeed.id()).query().singleRow();
        assertThat(failingRow.get("consecutive_failures")).isEqualTo(1);
        assertThat(failingRow.get("last_error")).isEqualTo("http_status 500");
    }

    @Test
    void articlesContinueServingWhenAllFeedsFail() throws Exception {
        createFeedFrom("/serving/feed.xml", "bbc-like-rss2.xml", Topic.WORLD);
        assertThat(jdbcClient.sql("SELECT count(*) FROM article").query(Long.class).single()).isEqualTo(2);

        stub.serve("/serving/feed.xml", 500, "text/plain", FeedStubServer.utf8("Down"));

        AggregatePollReport report = poller.poll(PollTrigger.MANUAL);
        assertThat(report.failed()).isEqualTo(1);

        mockMvc.perform(get(ARTICLES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void traceShapeAndMdcArePropagatedToWorkers(CapturedOutput output) {
        FeedResponse feed = createFeedFrom("/tracing/feed.xml", "bbc-like-rss2.xml", Topic.WORLD);

        AggregatePollReport report = poller.poll(PollTrigger.MANUAL);
        assertThat(report.succeeded()).isEqualTo(1);

        SpanData rootPollSpan = spans.spans().stream()
                .filter(span -> "argus.poll".equals(span.getName()))
                .filter(span -> "manual".equals(span.getAttributes().get(POLL_TRIGGER)))
                .reduce((first, second) -> second)
                .orElseThrow();

        List<SpanData> childIngestSpans = spans.spans().stream()
                .filter(span -> "argus.ingest".equals(span.getName()))
                .filter(span -> span.getParentSpanId().equals(rootPollSpan.getSpanId()))
                .toList();
        assertThat(childIngestSpans).hasSize(1);

        JsonNode workerLine = Arrays.stream(output.getOut().split("\n"))
                .filter(candidate -> candidate.contains("\"message\":\"Ingest COMPLETED"))
                .map(this::parseJson)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(workerLine.path("pollId").asString()).isEqualTo(report.pollId());
        assertThat(workerLine.path("feedId").asString()).isEqualTo(String.valueOf(feed.id()));
        assertThat(workerLine.path("sourceId").asString()).isEqualTo(String.valueOf(feed.source().id()));

        JsonNode summaryLine = Arrays.stream(output.getOut().split("\n"))
                .filter(candidate -> candidate.contains("Poll manual completed:"))
                .map(this::parseJson)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(summaryLine.path("pollId").asString()).isEqualTo(report.pollId());

        assertThat(MDC.get("pollId")).isNull();
    }

    @Test
    void aFeedDeletedWhileItIsBeingFetchedFailsCleanlyWithoutHealthWritesOrErrorLogs(CapturedOutput output)
            throws Exception {
        CountDownLatch enterLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        String path = "/deleted-mid-poll/feed.xml";
        FeedResponse feed = createFeedFrom(path, "bbc-like-rss2.xml", Topic.WORLD);
        stub.serveWithLatch(path, enterLatch, releaseLatch, "bbc-like-rss2.xml");

        CompletableFuture<AggregatePollReport> backgroundPoll = CompletableFuture.supplyAsync(
                () -> poller.poll(PollTrigger.MANUAL));
        assertThat(enterLatch.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            mockMvc.perform(delete(FEEDS + "/" + feed.id()).header(AdminKeys.HEADER, AdminKeys.VALID))
                    .andExpect(status().isNoContent());
        } finally {
            releaseLatch.countDown();
        }

        AggregatePollReport report = backgroundPoll.get(10, TimeUnit.SECONDS);
        assertThat(report.failed()).isOne();
        assertThat(report.reports().getFirst().failureReason()).isEqualTo("feed_deleted");
        assertThat(jdbcClient.sql("SELECT count(*) FROM feed").query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT count(*) FROM article").query(Long.class).single()).isZero();
        assertThat(output.getOut()).doesNotContain("\"level\":\"ERROR\"");
        assertThat(output.getOut()).contains("was deleted during the poll");
    }

    private double currentSkippedCount() {
        var counter = meterRegistry.find("argus.scheduled.job")
                .tag("outcome", "skipped")
                .counter();
        return counter != null ? counter.count() : 0.0;
    }

    private JsonNode parseJson(String line) {
        return mapper.readTree(line);
    }
}
