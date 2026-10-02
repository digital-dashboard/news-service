package com.j11a.argus.observability.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.observability.MeterKind;
import com.j11a.argus.observability.MeterSpec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AllowedSeriesTest {

    @Test
    void externalTimersAllowOnlyTheirDistributionSeries() {
        Set<String> allowed = AllowedSeries.of(List.of());

        assertThat(allowed).contains(
                "http_server_requests_seconds_bucket", "http_server_requests_seconds_count",
                "http_server_requests_seconds_sum", "http_server_requests_seconds_max")
                .doesNotContain("http_server_requests_seconds", "http_server_requests_seconds_foo");
    }

    @Test
    void externalGaugesAndCountersAllowOnlyTheirOwnName() {
        Set<String> allowed = AllowedSeries.of(List.of());

        assertThat(allowed).contains("jvm_memory_used_bytes", "hikaricp_connections_active", "pg_up", "up",
                "pg_stat_database_xact_commit", "container_cpu_usage_seconds_total")
                .doesNotContain("jvm_memory_used_bytes_count", "hikaricp_connections", "uploads_total",
                "pg_stat_database_xact_commit_total");
    }

    @Test
    void catalogueSeriesAreAdded() {
        MeterSpec spec = new MeterSpec("argus.fetch", MeterKind.TIMER, null, Set.of());

        assertThat(AllowedSeries.of(List.of(spec))).contains("argus_fetch_seconds_bucket", "argus_fetch_seconds_count");
    }
}
