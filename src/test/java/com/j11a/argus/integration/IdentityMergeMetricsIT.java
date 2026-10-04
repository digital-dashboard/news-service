package com.j11a.argus.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.j11a.argus.feed.FeedInserter;
import com.j11a.argus.feed.NewFeed;
import com.j11a.argus.feed.Topic;
import com.j11a.argus.feed.identity.FeedIdentityTelemetry;
import com.j11a.argus.feed.identity.FeedRedirectApplier;
import com.j11a.argus.feed.identity.IdentityKind;
import com.j11a.argus.observability.MetricNames;
import com.j11a.argus.source.SourceMerger;
import com.j11a.argus.source.SourceService;
import com.j11a.argus.testsupport.MergeData;
import com.j11a.argus.web.error.ApiException;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Exact names, tags and counts of the identity and merge meters: redirects, identity conflicts, merges and collapses. */
class IdentityMergeMetricsIT extends AbstractIntegrationTest {

    private static final Instant EARLY = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant LATE = Instant.parse("2026-10-01T10:00:00Z");
    private static final String SOURCE_KEY = "redirect.example.test";
    private static final String OLD = "http://redirect.example.test/old";
    private static final String APPLIED = "permanent_applied";
    private static final String CONFLICT = "permanent_conflict";
    private static final String SKIPPED = "permanent_skipped";
    private static final String MERGED_KEY = "bbci.co.uk";
    private static final String TARGET_KEY = "bbc.co.uk";
    private static final String MOVING_URL = "https://bbci.co.uk/moving";
    private static final String TYPE = "type";
    private static final String OUTCOME = "outcome";
    private static final String MERGE = "merge";
    private static final String FEED_MOVE = "feed_move";
    private static final String COMPLETED = "completed";
    private static final String FAILED = "failed";
    private static final Set<String> AUTOMATIC_TAGS = Set.of("error", "application");

    @Autowired
    private FeedRedirectApplier redirectApplier;

    @Autowired
    private FeedIdentityTelemetry identityTelemetry;

    @Autowired
    private FeedInserter inserter;

    @Autowired
    private SourceService sources;

    @Autowired
    private SourceMerger merger;

    @Autowired
    private MeterRegistry registry;

    private MergeData data;

    @BeforeEach
    void seed() {
        data = new MergeData(jdbcClient);
    }

    private long insertFeed(String url) {
        long sourceId = sources.findOrCreate(SOURCE_KEY, null).getId();
        return inserter.insert(new NewFeed(sourceId, "F", url, null, null, Topic.TECH, null)).orElseThrow();
    }

    private double redirects(String outcome) {
        return meters().counter(MetricNames.FEED_REDIRECT, "source", SOURCE_KEY, OUTCOME, outcome);
    }

    private double conflicts(IdentityKind kind) {
        return meters().counter(MetricNames.FEED_IDENTITY_CONFLICT, "kind", kind.tag());
    }

    /** Summed over the error tag, which splits the failed series by exception type. */
    private long merges(String type, String outcome) {
        return registry.find(MetricNames.SOURCE_MERGE).tags(TYPE, type, OUTCOME, outcome).timers().stream()
                .mapToLong(Timer::count).sum();
    }

    private double collapsed(String type) {
        return meters().counter(MetricNames.ARTICLE_COLLAPSED, TYPE, type);
    }

    private static Set<String> tagKeys(Meter meter) {
        return meter.getId().getTags().stream().map(Tag::getKey)
                .filter(key -> !AUTOMATIC_TAGS.contains(key)).collect(Collectors.toSet());
    }

    private List<Meter> named(String name) {
        return registry.getMeters().stream().filter(meter -> meter.getId().getName().equals(name)).toList();
    }

    @Test
    void anAppliedAndAConflictingRedirectAreCountedBySourceAndOutcome() {
        long moved = insertFeed(OLD);
        insertFeed("https://redirect.example.test/held");
        long blocked = insertFeed("http://redirect.example.test/blocked");
        double appliedBefore = redirects(APPLIED);
        double conflictBefore = redirects(CONFLICT);

        redirectApplier.apply(moved, SOURCE_KEY, OLD, URI.create("https://redirect.example.test/new"));
        redirectApplier.apply(blocked, SOURCE_KEY, "http://redirect.example.test/blocked",
                URI.create("https://redirect.example.test/held"));

        assertThat(redirects(APPLIED)).isEqualTo(appliedBefore + 1);
        assertThat(redirects(CONFLICT)).isEqualTo(conflictBefore + 1);
        assertThat(named(MetricNames.FEED_REDIRECT)).isNotEmpty().allSatisfy(meter ->
                assertThat(tagKeys(meter)).containsExactlyInAnyOrder("source", OUTCOME));
    }

    @Test
    void aRedirectClaimedOnlyAsAnotherFeedsSelfLinkIsCountedAsSkippedWithTheSameTags() {
        long holder = insertFeed("https://redirect.example.test/holder");
        jdbcClient.sql("UPDATE feed SET self_url = 'https://redirect.example.test/claimed' WHERE id = :id")
                .param("id", holder).update();
        long id = insertFeed(OLD);
        double skippedBefore = redirects(SKIPPED);
        double appliedBefore = redirects(APPLIED);
        double conflictBefore = redirects(CONFLICT);

        redirectApplier.apply(id, SOURCE_KEY, OLD, URI.create("https://redirect.example.test/claimed"));

        assertThat(redirects(SKIPPED)).isEqualTo(skippedBefore + 1);
        assertThat(redirects(APPLIED)).isEqualTo(appliedBefore);
        assertThat(redirects(CONFLICT)).isEqualTo(conflictBefore);
        assertThat(named(MetricNames.FEED_REDIRECT)).isNotEmpty().allSatisfy(meter ->
                assertThat(tagKeys(meter)).containsExactlyInAnyOrder("source", OUTCOME));
    }

    @Test
    void aRedirectThatChangesNothingCountsNothing() {
        long id = insertFeed(OLD);
        double appliedBefore = redirects(APPLIED);
        double conflictBefore = redirects(CONFLICT);

        redirectApplier.apply(id, SOURCE_KEY, OLD, URI.create(OLD));

        assertThat(redirects(APPLIED)).isEqualTo(appliedBefore);
        assertThat(redirects(CONFLICT)).isEqualTo(conflictBefore);
    }

    @Test
    void everyConflictKindExistsFromStartupAndCountsOnItsOwn() {
        for (IdentityKind kind : IdentityKind.values()) {
            assertThat(registry.find(MetricNames.FEED_IDENTITY_CONFLICT).tag("kind", kind.tag()).counter())
                    .as(kind.tag()).isNotNull();
        }
        double[] before = Arrays.stream(IdentityKind.values()).mapToDouble(this::conflicts).toArray();

        identityTelemetry.conflict(IdentityKind.REDIRECT);
        identityTelemetry.conflict(IdentityKind.SELF_LINK);
        identityTelemetry.conflict(IdentityKind.SELF_LINK);

        assertThat(conflicts(IdentityKind.ENTERED)).isEqualTo(before[IdentityKind.ENTERED.ordinal()]);
        assertThat(conflicts(IdentityKind.REDIRECT)).isEqualTo(before[IdentityKind.REDIRECT.ordinal()] + 1);
        assertThat(conflicts(IdentityKind.SELF_LINK)).isEqualTo(before[IdentityKind.SELF_LINK.ordinal()] + 2);
        assertThat(named(MetricNames.FEED_IDENTITY_CONFLICT)).hasSize(IdentityKind.values().length).allSatisfy(meter ->
                assertThat(tagKeys(meter)).containsExactly("kind"));
    }

    @Test
    void aMergeIsTimedAsCompletedAndCountsTheCollapsedArticles() {
        long source = data.source(MERGED_KEY, "https://www.bbci.co.uk");
        long target = data.source(TARGET_KEY, "https://www.bbc.co.uk");
        long sourceFeed = data.feed(source, "https://bbci.co.uk/feed");
        long targetFeed = data.feed(target, "https://bbc.co.uk/feed");
        long duplicate = data.article(source, "g1", null, LATE);
        long survivor = data.article(target, "g1", null, EARLY);
        data.link(duplicate, sourceFeed, LATE, "h");
        data.link(survivor, targetFeed, EARLY, "h");
        long completedBefore = merges(MERGE, COMPLETED);
        double collapsedBefore = collapsed(MERGE);

        merger.merge(source, target);

        assertThat(merges(MERGE, COMPLETED)).isEqualTo(completedBefore + 1);
        assertThat(collapsed(MERGE)).isEqualTo(collapsedBefore + 1);
        assertThat(named(MetricNames.SOURCE_MERGE)).isNotEmpty().allSatisfy(meter ->
                assertThat(tagKeys(meter)).containsExactlyInAnyOrder(TYPE, OUTCOME));
    }

    @Test
    void aMergeWithoutDuplicatesAddsZeroButTheCollapsedSeriesExists() {
        long source = data.source(MERGED_KEY, null);
        long target = data.source(TARGET_KEY, null);
        data.article(source, "only-here", null, EARLY);
        long completedBefore = merges(MERGE, COMPLETED);
        double collapsedBefore = collapsed(MERGE);

        merger.merge(source, target);

        assertThat(merges(MERGE, COMPLETED)).isEqualTo(completedBefore + 1);
        assertThat(registry.find(MetricNames.ARTICLE_COLLAPSED).tag(TYPE, MERGE).counter()).isNotNull();
        assertThat(collapsed(MERGE)).isEqualTo(collapsedBefore);
    }

    @Test
    void aMergeOfAMissingSourceIsTimedAsFailedAndCollapsesNothing() {
        long target = data.source(TARGET_KEY, null);
        long failedBefore = merges(MERGE, FAILED);
        long completedBefore = merges(MERGE, COMPLETED);
        double collapsedBefore = collapsed(MERGE);

        assertThatThrownBy(() -> merger.merge(target + 99, target)).isInstanceOf(ApiException.class);

        assertThat(merges(MERGE, FAILED)).isEqualTo(failedBefore + 1);
        assertThat(merges(MERGE, COMPLETED)).isEqualTo(completedBefore);
        assertThat(collapsed(MERGE)).isEqualTo(collapsedBefore);
    }

    @Test
    void aSelfMergeIsRejectedBeforeTheObservationAndRecordsNothing() {
        long source = data.source(MERGED_KEY, null);
        long failedBefore = merges(MERGE, FAILED);
        long completedBefore = merges(MERGE, COMPLETED);

        assertThatThrownBy(() -> merger.merge(source, source)).isInstanceOf(ApiException.class);

        assertThat(merges(MERGE, FAILED)).isEqualTo(failedBefore);
        assertThat(merges(MERGE, COMPLETED)).isEqualTo(completedBefore);
    }

    @Test
    void aFeedMoveIsTimedAsFeedMoveAndCountsItsCollapsedArticles() {
        long source = data.source(MERGED_KEY, "https://www.bbci.co.uk");
        long target = data.source(TARGET_KEY, "https://www.bbc.co.uk");
        long moving = data.feed(source, MOVING_URL);
        long targetFeed = data.feed(target, "https://bbc.co.uk/feed");
        long original = data.article(source, "same", null, LATE);
        long survivor = data.article(target, "same", null, EARLY);
        data.link(original, moving, EARLY, "h-moving");
        data.link(survivor, targetFeed, LATE, "h-target");
        long completedBefore = merges(FEED_MOVE, COMPLETED);
        long mergeBefore = merges(MERGE, COMPLETED);
        double collapsedBefore = collapsed(FEED_MOVE);

        merger.moveFeed(moving, target);

        assertThat(merges(FEED_MOVE, COMPLETED)).isEqualTo(completedBefore + 1);
        assertThat(merges(MERGE, COMPLETED)).isEqualTo(mergeBefore);
        assertThat(collapsed(FEED_MOVE)).isEqualTo(collapsedBefore + 1);
    }

    @Test
    void aFeedMoveToAMissingSourceIsTimedAsFailed() {
        long source = data.source(MERGED_KEY, null);
        long moving = data.feed(source, MOVING_URL);
        long failedBefore = merges(FEED_MOVE, FAILED);

        assertThatThrownBy(() -> merger.moveFeed(moving, source + 99)).isInstanceOf(ApiException.class);

        assertThat(merges(FEED_MOVE, FAILED)).isEqualTo(failedBefore + 1);
    }

    @Test
    void aFeedMoveWithinItsOwnSourceRecordsNothing() {
        long source = data.source(MERGED_KEY, null);
        long moving = data.feed(source, MOVING_URL);
        long before = merges(FEED_MOVE, COMPLETED) + merges(FEED_MOVE, FAILED);

        merger.moveFeed(moving, source);

        assertThat(merges(FEED_MOVE, COMPLETED) + merges(FEED_MOVE, FAILED)).isEqualTo(before);
    }
}
