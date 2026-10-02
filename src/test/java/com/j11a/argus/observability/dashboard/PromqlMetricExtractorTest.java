package com.j11a.argus.observability.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PromqlMetricExtractorTest {

    @Test
    void extractsMetricFromRateWithRangeVariable() {
        assertThat(PromqlMetricExtractor.metricsIn("rate(http_server_requests_seconds_count{job=\"argus\"}[$__rate_interval])"))
                .containsExactly("http_server_requests_seconds_count");
    }

    @Test
    void extractsMetricInsideHistogramQuantileAndIgnoresByClause() {
        String expr = "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri!~\"/a|/b\"}[5m])))";

        assertThat(PromqlMetricExtractor.metricsIn(expr)).containsExactly("http_server_requests_seconds_bucket");
    }

    @Test
    void ignoresTrailingByAndWithoutClauses() {
        assertThat(PromqlMetricExtractor.metricsIn("sum(jvm_memory_used_bytes) by (area, id)"))
                .containsExactly("jvm_memory_used_bytes");
        assertThat(PromqlMetricExtractor.metricsIn("sum without (instance) (jvm_threads_live_threads)"))
                .containsExactly("jvm_threads_live_threads");
    }

    @Test
    void ignoresVectorMatchingClauses() {
        String expr = "a_total / on (job) group_left (version) b_total";

        assertThat(PromqlMetricExtractor.metricsIn(expr)).containsExactlyInAnyOrder("a_total", "b_total");
    }

    @Test
    void ignoresOffsetDurationAndRangeSelectors() {
        assertThat(PromqlMetricExtractor.metricsIn("rate(process_cpu_usage[1h] offset 5m)"))
                .containsExactly("process_cpu_usage");
    }

    @Test
    void ignoresLabelMatchersAndStringLiterals() {
        assertThat(PromqlMetricExtractor.metricsIn("up{job=\"not_a_metric\", service=~\"also_not\"}"))
                .containsExactly("up");
        assertThat(PromqlMetricExtractor.metricsIn("label_replace(up, \"dst\", \"$1\", \"src\", \"(.*)\")"))
                .containsExactly("up");
    }

    @Test
    void ignoresGrafanaVariablesInEveryForm() {
        assertThat(PromqlMetricExtractor.metricsIn("up{job=\"${job}\"} + $other + ${DS_PROMETHEUS} + $__interval"))
                .containsExactly("up");
    }

    @Test
    void numbersAndDurationsProduceNoTokens() {
        assertThat(PromqlMetricExtractor.metricsIn("0.95")).isEmpty();
        assertThat(PromqlMetricExtractor.metricsIn("1e3")).isEmpty();
        assertThat(PromqlMetricExtractor.metricsIn("5m")).isEmpty();
        assertThat(PromqlMetricExtractor.metricsIn("clamp_min(x_bytes, 0.000000001) * 1e3 > 5m"))
                .containsExactly("x_bytes");
    }

    @Test
    void ignoresFunctionsAndKeywords() {
        assertThat(PromqlMetricExtractor.metricsIn("clamp_min(sum(rate(a_total[1m])), 1) or vector(0) and bool b"))
                .containsExactlyInAnyOrder("a_total", "b");
    }

    @Test
    void keepsColonRecordingRuleNames() {
        assertThat(PromqlMetricExtractor.metricsIn("job:http_requests:rate5m")).containsExactly("job:http_requests:rate5m");
    }
}
