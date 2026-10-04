package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.feed.identity.FeedRedirectApplier;
import com.j11a.argus.feed.poll.PollTestHooks;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.observability.MeterSpec;
import com.j11a.argus.observability.MetricCatalogue;
import com.j11a.argus.observability.MetricNames;
import com.j11a.argus.source.SourceMerger;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.testsupport.MergeData;
import com.j11a.argus.web.error.ApiException;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class IngestMetricsIT extends AbstractIntegrationTest {

    private static final String REDIRECT_KEY = "redirect.example.test";
    private static final String PATH = "/metrics/sparse.xml";
    /** argus.fetch.retry.max-retries, which the it profile leaves at its default of 2. */
    private static final int MAX_RETRIES = 2;
    /** The entries are tagged with the stored source, which comes from the feed's site link. */
    private static final String SITE_SOURCE = "sparse.example.test";
    private static final Set<String> AUTOMATIC_TAGS = Set.of("error", "application");
    private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of("feed_id", "feed.id", "url", "guid", "link");

    @Autowired
    private FeedIngestService ingestService;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private FeedRedirectApplier redirectApplier;

    @Autowired
    private FeedInserter inserter;

    @Autowired
    private SourceService sources;

    @Autowired
    private SourceMerger merger;

    private double decisions(String decision, String reason) {
        return meters().entries(SITE_SOURCE, decision, reason);
    }

    private double missing(String kind) {
        return meters().counter(MetricNames.PARSE_MISSING, "source", SITE_SOURCE, "kind", kind);
    }

    private FeedResponse create() {
        return createFeedFrom(PATH, "missing-guid-date.xml", Topic.NEWS);
    }

    @Test
    void creatingAFeedRecordsFetchIngestDecisionAndDataQualityMeters() {
        long fetchBefore = meters().timerCount(MetricNames.FETCH, "source", SITE_SOURCE, "outcome", "fetched", "reason", "none");
        long sizeBefore = meters().summaryCount(MetricNames.FETCH_SIZE, "source", SITE_SOURCE);
        long ingestBefore = meters().timerCount(MetricNames.INGEST, "source", SITE_SOURCE, "outcome", "completed");
        double insertedBefore = decisions("inserted", "none");
        double skippedBefore = decisions("skipped", "missing_identity");
        double dateBefore = missing("date");
        double guidBefore = missing("guid");
        double imageBefore = missing("image");
        double authorBefore = missing("author");

        create();

        assertThat(meters().timerCount(MetricNames.FETCH, "source", SITE_SOURCE, "outcome", "fetched", "reason", "none"))
                .isEqualTo(fetchBefore + 1);
        assertThat(meters().summaryCount(MetricNames.FETCH_SIZE, "source", SITE_SOURCE)).isEqualTo(sizeBefore + 1);
        assertThat(registry.find(MetricNames.FETCH_SIZE).summary().getId().getBaseUnit()).isEqualTo("bytes");
        assertThat(meters().timerCount(MetricNames.INGEST, "source", SITE_SOURCE, "outcome", "completed"))
                .isEqualTo(ingestBefore + 1);
        assertThat(decisions("inserted", "none")).isEqualTo(insertedBefore + 2);
        assertThat(decisions("skipped", "missing_identity")).isEqualTo(skippedBefore + 1);
        assertThat(missing("date")).isEqualTo(dateBefore + 3);
        assertThat(missing("guid")).isEqualTo(guidBefore + 2);
        assertThat(missing("image")).isEqualTo(imageBefore + 3);
        assertThat(missing("author")).isEqualTo(authorBefore + 3);
    }

    @Test
    void aFailedCreateFetchIsTaggedUnknown() {
        stub.serve("/missing-create.xml", 404, "text/plain", new byte[0]);
        long unknownBefore = meters().timerCount(MetricNames.FETCH, "source", "unknown", "outcome", "failed", "reason", "http_status");

        CreateFeedRequest request = new CreateFeedRequest(stub.baseUrl() + "/missing-create.xml", null, Topic.TECH, null);

        assertThatThrownBy(() -> feedService.create(request)).isInstanceOf(ApiException.class);

        assertThat(meters().timerCount(MetricNames.FETCH, "source", "unknown", "outcome", "failed", "reason", "http_status"))
                .isEqualTo(unknownBefore + 1);
    }

    @Test
    void refreshingRecordsUnchangedEntriesAndTagsTheFetchWithTheStoredSource() {
        FeedResponse feed = create();
        long fetchBefore = meters().timerCount(MetricNames.FETCH, "source", SITE_SOURCE, "outcome", "fetched", "reason", "none");
        double unchangedBefore = decisions("unchanged", "none");

        ingestService.refresh(feed.id());

        assertThat(meters().timerCount(MetricNames.FETCH, "source", SITE_SOURCE, "outcome", "fetched", "reason", "none"))
                .isEqualTo(fetchBefore + 1);
        assertThat(decisions("unchanged", "none")).isEqualTo(unchangedBefore + 2);
    }

    @Test
    void aFailedFetchIsTaggedWithItsReasonAndRetriesAreCountedUnderStoredSource() {
        FeedResponse feed = create();
        stub.serve(PATH, 503, "text/plain", new byte[0]);
        long before = meters().timerCount(MetricNames.FETCH, "source", SITE_SOURCE, "outcome", "failed",
                "reason", "http_status");
        long failedIngestBefore = meters().timerCount(MetricNames.INGEST, "source", SITE_SOURCE, "outcome", "failed");
        double retriesBefore = meters().counter(MetricNames.FETCH_RETRY, "source", SITE_SOURCE);

        ingestService.refresh(feed.id());

        assertThat(meters().timerCount(MetricNames.FETCH, "source", SITE_SOURCE, "outcome", "failed", "reason", "http_status"))
                .isEqualTo(before + 1);
        assertThat(meters().timerCount(MetricNames.INGEST, "source", SITE_SOURCE, "outcome", "failed"))
                .isEqualTo(failedIngestBefore + 1);
        assertThat(meters().counter(MetricNames.FETCH_RETRY, "source", SITE_SOURCE)).isEqualTo(retriesBefore + MAX_RETRIES);
    }

    @Test
    void everyCataloguedMeterExistsWithExactlyTheCataloguedTags() {
        registerEveryCataloguedMeter();

        for (MeterSpec spec : MetricCatalogue.all()) {
            Set<Meter> meters = registry.getMeters().stream()
                    .filter(meter -> meter.getId().getName().equals(spec.name()))
                    .collect(Collectors.toSet());
            assertThat(meters).as(spec.name()).isNotEmpty();
            assertThat(meters).allSatisfy(meter -> assertThat(tagKeys(meter))
                    .as(spec.name()).containsExactlyInAnyOrderElementsOf(spec.tags()));
        }
    }

    /**
     * The create path registers fetch, ingest, decision and data-quality meters. A failing refresh adds
     * argus.fetch.retry, and the scheduled poll adds argus.poll and argus.scheduled.job (argus.poll.last.success is
     * a gauge registered at startup). The redirect and merge meters are driven through their beans, because the
     * redirect counter is registered on first use and the merge meters only exist once a merge has run.
     */
    private void registerEveryCataloguedMeter() {
        FeedResponse feed = create();
        stub.serve(PATH, 503, "text/plain", new byte[0]);
        ingestService.refresh(feed.id());
        PollTestHooks.runScheduledPoll(context);
        triggerRedirectAndMergeMeters();
    }

    private void triggerRedirectAndMergeMeters() {
        long redirected = insertFeed(REDIRECT_KEY, "http://redirect.example.test/old");
        insertFeed(REDIRECT_KEY, "https://redirect.example.test/held");
        redirectApplier.apply(redirected, REDIRECT_KEY, "http://redirect.example.test/old",
                URI.create("https://redirect.example.test/new"));
        redirectApplier.apply(redirected, REDIRECT_KEY, "https://redirect.example.test/new",
                URI.create("https://redirect.example.test/held"));

        MergeData data = new MergeData(jdbcClient);
        long from = data.source("merge-from.example.test", null);
        long into = data.source("merge-into.example.test", null);
        merger.merge(from, into);
    }

    private long insertFeed(String sourceKey, String url) {
        long sourceId = sources.findOrCreate(sourceKey, null).getId();
        return inserter.insert(new NewFeed(sourceId, "F", url, null, null, Topic.TECH, null)).orElseThrow();
    }

    @Test
    void theHttpClientMeterExistsWithoutAUriTagValueThatCouldCarryASecret() {
        create();

        assertThat(registry.find("http.client.requests").tag("uri", "none").timer()).isNotNull();
        assertThat(registry.find("http.client.requests").timers())
                .allSatisfy(timer -> assertThat(timer.getId().getTag("uri")).doesNotContain("/metrics"));
    }

    private static final Set<String> ALLOWED_FEED_ID_METERS = Set.of(
            MetricNames.FEED_STATE,
            MetricNames.FEED_CONSECUTIVE_FAILURES,
            MetricNames.FEED_SINCE_LAST_SUCCESS);

    @Test
    void noMeterCarriesAFeedIdUrlOrGuidTag() {
        create();

        assertThat(registry.getMeters()).allSatisfy(meter -> {
            Set<String> forbidden = ALLOWED_FEED_ID_METERS.contains(meter.getId().getName())
                    ? Set.of("feed.id", "url", "guid", "link")
                    : FORBIDDEN_TAG_KEYS;
            assertThat(tagKeys(meter)).doesNotContainAnyElementsOf(forbidden);
            if (meter.getId().getName().startsWith(MetricNames.PREFIX)) {
                assertThat(meter.getId().getTags()).extracting(Tag::getValue).noneMatch(value -> value.contains("://"));
            }
        });
    }

    private static Set<String> tagKeys(Meter meter) {
        return meter.getId().getTags().stream().map(Tag::getKey)
                .filter(key -> !AUTOMATIC_TAGS.contains(key))
                .collect(Collectors.toSet());
    }
}
