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
    void cataloguesThePhaseTwoIngestMeters() {
        assertThat(MetricCatalogue.all()).extracting(MeterSpec::name).containsExactlyInAnyOrder(
                MetricNames.FETCH, MetricNames.INGEST, MetricNames.FETCH_SIZE, MetricNames.INGEST_ENTRIES,
                MetricNames.PARSE_MISSING);
        assertThat(MetricCatalogue.all()).filteredOn(spec -> spec.name().equals(MetricNames.FETCH_SIZE))
                .singleElement().satisfies(spec -> assertThat(spec.prometheusBase())
                        .isEqualTo("argus_fetch_size_bytes"));
    }
}
