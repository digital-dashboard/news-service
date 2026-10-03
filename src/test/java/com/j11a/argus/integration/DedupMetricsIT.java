package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.api.CreateFeedRequest;
import com.j11a.argus.feed.api.FeedResponse;
import com.j11a.argus.ingest.FeedIngestService;
import com.j11a.argus.observability.MetricNames;
import com.j11a.argus.testsupport.FeedStubServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DedupMetricsIT extends AbstractIntegrationTest {

    private static final AttributeKey<String> SOURCE_ID = AttributeKey.stringKey("source.id");
    private static final String COMMON_TAG = "application";
    private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of("feed_id", "feed.id", "url", "guid", "link");

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private FeedIngestService ingestService;

    @Autowired
    private SpanCollectorConfig.CollectingSpanProcessor spans;

    @Test
    void everyDecisionAndReasonIsCountedWithExactTags() {
        String shared = "news.example.test";
        String atom = "atom.example.test";
        double insertedBefore = meters().entries(shared, "inserted", "none");
        double linkedBefore = meters().entries(shared, "linked", "none");
        double unchangedBefore = meters().entries(shared, "unchanged", "none");
        double contentBefore = meters().entries(shared, "updated", "content_changed");
        double timestampBefore = meters().entries(atom, "updated", "timestamp_only");
        double batchBefore = meters().entries(atom, "skipped", "batch_duplicate");

        FeedResponse news = createFeedFrom("/dm/news.xml", "p4-shared-news.xml", Topic.NEWS);
        createFeedFrom("/dm/world.xml", "p4-shared-world.xml", Topic.WORLD);
        ingestService.refresh(news.id());
        FeedResponse edited = createFeedFrom("/dm/edit.xml", "p4-edited-v1.xml", Topic.NEWS);
        stub.serveFixture("/dm/edit.xml", "p4-edited-v2.xml");
        ingestService.refresh(edited.id());
        FeedResponse stamps = createFeedFrom("/dm/ts.xml", "p4-timestamp-v1.xml", Topic.TECH);
        stub.serveFixture("/dm/ts.xml", "p4-timestamp-v2.xml");
        ingestService.refresh(stamps.id());
        createFeedFrom("/dm/dup.xml", "p4-batch-dup.xml", Topic.TECH);

        assertThat(meters().entries(shared, "inserted", "none")).isGreaterThan(insertedBefore);
        assertThat(meters().entries(shared, "linked", "none")).isEqualTo(linkedBefore + 1);
        assertThat(meters().entries(shared, "unchanged", "none")).isGreaterThan(unchangedBefore);
        assertThat(meters().entries(shared, "updated", "content_changed")).isEqualTo(contentBefore + 1);
        assertThat(meters().entries(atom, "updated", "timestamp_only")).isEqualTo(timestampBefore + 1);
        assertThat(meters().entries(atom, "skipped", "batch_duplicate")).isEqualTo(batchBefore + 1);
    }

    @Test
    void allThreeLinkFallbackOutcomesExistAfterOneCreate() {
        String source = "fallback-series.example.test";
        stub.serve("/dm/fallback.xml", 200, "application/rss+xml", FeedStubServer.utf8(
                "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>T</title><link>https://" + source
                        + "/</link><description>d</description><item><title>One</title><link>https://" + source
                        + "/one</link><guid>one</guid></item></channel></rss>"));
        List<String> outcomes = List.of("guid_replaced", "guarded_homepage", "guarded_shared");
        for (String outcome : outcomes) {
            assertThat(fallbackSeries(source, outcome)).as("%s before create", outcome).isEmpty();
        }

        feedService.create(new CreateFeedRequest(stub.baseUrl() + "/dm/fallback.xml", null, Topic.WORLD, null));

        for (String outcome : outcomes) {
            assertThat(fallbackSeries(source, outcome)).as("%s after create", outcome).hasSize(1);
            assertThat(meters().fallback(source, outcome)).as(outcome).isZero();
        }
    }

    private List<Counter> fallbackSeries(String source, String outcome) {
        return registry.find(MetricNames.INGEST_LINK_FALLBACK).tag("source", source).tag("outcome", outcome)
                .counters().stream().toList();
    }

    @Test
    void theLockWaitTimerIsTaggedBySourceOnly() {
        FeedResponse feed = createFeedFrom("/dm/lock.xml", "bbc-like-rss2.xml", Topic.WORLD);
        String source = feed.source().name();
        long before = meters().lockWaits(source);

        ingestService.refresh(feed.id());

        Timer timer = registry.find(MetricNames.INGEST_LOCK_WAIT).tag("source", source).timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(before + 1);
        assertThat(timer.getId().getTags()).extracting(Tag::getKey)
                .filteredOn(key -> !COMMON_TAG.equals(key)).containsOnly("source");
    }

    @Test
    void noDedupMeterCarriesAFeedIdUrlOrGuidTag() {
        createFeedFrom("/dm/tags.xml", "p4-shared-news.xml", Topic.NEWS);

        List<Meter> dedupMeters = registry.getMeters().stream()
                .filter(meter -> Set.of(MetricNames.INGEST_ENTRIES, MetricNames.INGEST_LINK_FALLBACK,
                        MetricNames.INGEST_LOCK_WAIT).contains(meter.getId().getName()))
                .toList();
        assertThat(dedupMeters).isNotEmpty().allSatisfy(meter ->
                assertThat(meter.getId().getTags()).extracting(Tag::getKey).doesNotContainAnyElementsOf(FORBIDDEN_TAG_KEYS));
    }

    @Test
    void lockWaitAndResolveSpansAreChildrenOfThePersistSpan() {
        FeedResponse feed = createFeedFrom("/dm/spans.xml", "bbc-like-rss2.xml", Topic.WORLD);

        ingestService.refresh(feed.id());

        SpanData persist = lastSpan("argus.persist");
        List<SpanData> trace = spans.spans().stream()
                .filter(span -> span.getTraceId().equals(persist.getTraceId())).toList();
        for (String name : List.of("argus.lock.wait", "argus.resolve")) {
            SpanData child = trace.stream().filter(span -> span.getName().equals(name)).findFirst().orElseThrow();
            assertThat(child.getParentSpanId()).as(name).isEqualTo(persist.getSpanId());
            assertThat(child.getAttributes().get(SOURCE_ID)).as(name).isEqualTo(String.valueOf(feed.source().id()));
        }
        SpanData lock = trace.stream().filter(span -> span.getName().equals("argus.lock.wait")).findFirst().orElseThrow();
        assertThat(lock.getAttributes().get(AttributeKey.stringKey("contended"))).isEqualTo("false");
    }

    private SpanData lastSpan(String name) {
        return spans.spans().stream().filter(span -> span.getName().equals(name)).reduce((first, second) -> second)
                .orElseThrow();
    }
}
