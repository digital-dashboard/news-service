package com.j11a.argus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetricCatalogueTest {

    @Test
    void metricPrefixIsArgus() {
        assertThat(MetricNames.PREFIX).isEqualTo("argus");
    }

    @Test
    void allowedTagsExcludeLabelsPrometheusAddsItself() {
        assertThat(MetricCatalogue.allowedTags())
                .doesNotContain("job", "instance", "service")
                .contains(MetricNames.Tags.SCHEDULED_JOB);
    }

    @Test
    void catalogueSpecsAreUniquelyNamedUnderThePrefixAndUseAllowedTags() {
        List<String> names = MetricCatalogue.all().stream().map(MeterSpec::name).toList();

        assertThat(names).doesNotHaveDuplicates().allMatch(name -> name.startsWith(MetricNames.PREFIX + "."));
        MetricCatalogue.all().forEach(spec -> assertThat(MetricCatalogue.allowedTags()).containsAll(spec.tags()));
    }

    @Test
    void timerSeriesAreBucketCountSumAndMaxInSeconds() {
        MeterSpec spec = new MeterSpec("argus.fetch", MeterKind.TIMER, null, Set.of());

        assertThat(spec.prometheusBase()).isEqualTo("argus_fetch_seconds");
        assertThat(spec.prometheusSeries()).containsExactlyInAnyOrder(
                "argus_fetch_seconds_bucket", "argus_fetch_seconds_count", "argus_fetch_seconds_sum",
                "argus_fetch_seconds_max");
    }

    @Test
    void summarySeriesCarryTheBaseUnit() {
        MeterSpec spec = new MeterSpec("argus.fetch.body", MeterKind.DISTRIBUTION_SUMMARY, "bytes", Set.of());

        assertThat(spec.prometheusSeries()).containsExactlyInAnyOrder(
                "argus_fetch_body_bytes_bucket", "argus_fetch_body_bytes_count", "argus_fetch_body_bytes_sum",
                "argus_fetch_body_bytes_max");
    }

    @Test
    void counterSeriesHaveTheTotalSuffix() {
        MeterSpec spec = new MeterSpec("argus.articles.stored", MeterKind.COUNTER, null, Set.of());

        assertThat(spec.prometheusSeries()).containsExactly("argus_articles_stored_total");
    }

    @Test
    void gaugeSeriesIsTheBaseName() {
        MeterSpec spec = new MeterSpec("argus.feeds.active", MeterKind.GAUGE, null, Set.of());

        assertThat(spec.prometheusSeries()).containsExactly("argus_feeds_active");
    }

    @Test
    void cataloguesAllIngestAndPollMeters() {
        assertThat(MetricCatalogue.all()).extracting(MeterSpec::name).containsExactlyInAnyOrder(
                MetricNames.FETCH, MetricNames.INGEST, MetricNames.FETCH_SIZE, MetricNames.INGEST_ENTRIES,
                MetricNames.PARSE_MISSING, MetricNames.POLL, MetricNames.FETCH_RETRY, MetricNames.SCHEDULED_JOB,
                MetricNames.FEED_STATE, MetricNames.FEED_CONSECUTIVE_FAILURES, MetricNames.FEED_SINCE_LAST_SUCCESS,
                MetricNames.POLL_LAST_SUCCESS, MetricNames.INGEST_LINK_FALLBACK, MetricNames.INGEST_LOCK_WAIT,
                MetricNames.FEED_REDIRECT, MetricNames.FEED_IDENTITY_CONFLICT, MetricNames.SOURCE_MERGE,
                MetricNames.ARTICLE_COLLAPSED);
        assertThat(MetricCatalogue.all()).filteredOn(spec -> spec.name().equals(MetricNames.FETCH_SIZE))
                .singleElement().satisfies(spec -> assertThat(spec.prometheusBase())
                        .isEqualTo("argus_fetch_size_bytes"));
        assertThat(MetricCatalogue.all()).filteredOn(spec -> spec.name().equals(MetricNames.FEED_SINCE_LAST_SUCCESS))
                .singleElement().satisfies(spec -> assertThat(spec.prometheusBase())
                        .isEqualTo("argus_feed_since_last_success_seconds"));
        assertThat(MetricCatalogue.all()).filteredOn(spec -> spec.name().equals(MetricNames.POLL_LAST_SUCCESS))
                .singleElement().satisfies(spec -> assertThat(spec.prometheusBase())
                        .isEqualTo("argus_poll_last_success_seconds"));
    }

    @Test
    void cataloguesLinkFallbackAndLockWaitMeters() {
        assertThat(MetricCatalogue.all()).filteredOn(spec -> spec.name().equals(MetricNames.INGEST_LINK_FALLBACK))
                .singleElement().satisfies(spec -> {
                    assertThat(spec.kind()).isEqualTo(MeterKind.COUNTER);
                    assertThat(spec.baseUnit()).isNull();
                    assertThat(spec.tags()).containsExactlyInAnyOrder(MetricNames.Tags.SOURCE, MetricNames.Tags.OUTCOME);
                });
        assertThat(MetricCatalogue.all()).filteredOn(spec -> spec.name().equals(MetricNames.INGEST_LOCK_WAIT))
                .singleElement().satisfies(spec -> {
                    assertThat(spec.kind()).isEqualTo(MeterKind.TIMER);
                    assertThat(spec.baseUnit()).isNull();
                    assertThat(spec.tags()).containsExactly(MetricNames.Tags.SOURCE);
                    assertThat(spec.prometheusBase()).isEqualTo("argus_ingest_lock_wait_seconds");
                });
    }

    @Test
    void cataloguesPhaseFiveIdentityAndMergeMeters() {
        assertThat(specNamed(MetricNames.FEED_REDIRECT)).satisfies(spec -> {
            assertThat(spec.kind()).isEqualTo(MeterKind.COUNTER);
            assertThat(spec.baseUnit()).isNull();
            assertThat(spec.tags()).containsExactlyInAnyOrder(MetricNames.Tags.SOURCE, MetricNames.Tags.OUTCOME);
            assertThat(spec.prometheusSeries()).containsExactly("argus_feed_redirect_total");
        });
        assertThat(specNamed(MetricNames.FEED_IDENTITY_CONFLICT)).satisfies(spec -> {
            assertThat(spec.kind()).isEqualTo(MeterKind.COUNTER);
            assertThat(spec.tags()).containsExactly(MetricNames.Tags.KIND);
            assertThat(spec.prometheusSeries()).containsExactly("argus_feed_identity_conflict_total");
        });
        assertThat(specNamed(MetricNames.SOURCE_MERGE)).satisfies(spec -> {
            assertThat(spec.kind()).isEqualTo(MeterKind.TIMER);
            assertThat(spec.tags()).containsExactlyInAnyOrder(MetricNames.Tags.TYPE, MetricNames.Tags.OUTCOME);
            assertThat(spec.prometheusBase()).isEqualTo("argus_source_merge_seconds");
        });
        assertThat(specNamed(MetricNames.ARTICLE_COLLAPSED)).satisfies(spec -> {
            assertThat(spec.kind()).isEqualTo(MeterKind.COUNTER);
            assertThat(spec.tags()).containsExactly(MetricNames.Tags.TYPE);
            assertThat(spec.prometheusSeries()).containsExactly("argus_article_collapsed_total");
        });
    }

    private static MeterSpec specNamed(String name) {
        return MetricCatalogue.all().stream().filter(spec -> spec.name().equals(name)).findFirst().orElseThrow();
    }
}
