package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.feed.poll.FeedPoller;
import com.j11a.argus.feed.poll.PollTestHooks;
import com.j11a.argus.feed.poll.PollTrigger;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.observability.MeterSpec;
import com.j11a.argus.observability.MetricCatalogue;
import com.j11a.argus.observability.MetricNames;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class PollMetricsIT extends AbstractIntegrationTest {

    private static final String PATH = "/metrics/poll-feed.xml";
    private static final Set<String> AUTOMATIC_TAGS = Set.of("error", "application");
    private static final Set<String> ALLOWED_FEED_ID_METERS = Set.of(
            MetricNames.FEED_STATE,
            MetricNames.FEED_CONSECUTIVE_FAILURES,
            MetricNames.FEED_SINCE_LAST_SUCCESS);

    private static final List<String> PHASE3_METER_NAMES = List.of(
            MetricNames.POLL,
            MetricNames.FETCH_RETRY,
            MetricNames.SCHEDULED_JOB,
            MetricNames.FEED_STATE,
            MetricNames.FEED_CONSECUTIVE_FAILURES,
            MetricNames.FEED_SINCE_LAST_SUCCESS,
            MetricNames.POLL_LAST_SUCCESS);

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private FeedPoller poller;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private FeedIngestService ingestService;

    private String scrape() throws Exception {
        return mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
    }

    private void exerciseAllMeters() {
        FeedResponse feed = createFeedFrom(PATH, "bbc-like-rss2.xml", Topic.WORLD);

        stub.serve(PATH, 503, "text/plain", new byte[0]);
        ingestService.refresh(feed.id());

        poller.poll(PollTrigger.MANUAL);

        PollTestHooks.runScheduledPoll(context);
        PollTestHooks.recordScheduledJob(context, "skipped");
        PollTestHooks.recordScheduledJob(context, "error");
        PollTestHooks.recordScheduledJob(context, "interrupted");
    }

    @Test
    void allSevenPhaseThreeMetersExistWithCataloguedTags() {
        exerciseAllMeters();

        for (String meterName : PHASE3_METER_NAMES) {
            MeterSpec spec = MetricCatalogue.all().stream()
                    .filter(s -> s.name().equals(meterName))
                    .findFirst()
                    .orElseThrow();

            Set<Meter> meters = registry.getMeters().stream()
                    .filter(meter -> meter.getId().getName().equals(spec.name()))
                    .collect(Collectors.toSet());

            assertThat(meters).as(spec.name()).isNotEmpty();
            assertThat(meters).allSatisfy(meter -> assertThat(tagKeys(meter))
                    .as(spec.name()).containsExactlyInAnyOrderElementsOf(spec.tags()));
        }
    }

    @Test
    void prometheusScrapeExposesAllPhaseThreePrometheusSeries() throws Exception {
        exerciseAllMeters();

        String body = scrape();

        assertThat(body).contains(
                "argus_poll_seconds_bucket",
                "argus_poll_seconds_count",
                "argus_poll_seconds_sum",
                "argus_poll_seconds_max",
                "argus_fetch_retry_total",
                "argus_scheduled_job_total",
                "argus_feed_state",
                "argus_feed_consecutive_failures",
                "argus_feed_since_last_success_seconds",
                "argus_poll_last_success_seconds");
    }

    @Test
    void feedIdTagIsStrictlyConfinedToFeedHealthGauges() {
        exerciseAllMeters();

        assertThat(registry.getMeters()).allSatisfy(meter -> {
            boolean isAllowed = ALLOWED_FEED_ID_METERS.contains(meter.getId().getName());
            if (!isAllowed) {
                assertThat(tagKeys(meter)).as(meter.getId().getName()).doesNotContain("feed_id", "feed.id");
            }
        });
    }

    private static Set<String> tagKeys(Meter meter) {
        return meter.getId().getTags().stream().map(Tag::getKey)
                .filter(key -> !AUTOMATIC_TAGS.contains(key))
                .collect(Collectors.toSet());
    }
}
