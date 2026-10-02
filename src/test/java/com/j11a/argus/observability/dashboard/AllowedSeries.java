package com.j11a.argus.observability.dashboard;

import com.j11a.argus.observability.MeterSpec;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Series a dashboard query may reference: the catalogue plus what the framework and exporters publish.
 * External series are the literal Prometheus names, as scraped.
 */
final class AllowedSeries {

    private static final Set<String> EXTERNAL = Set.of(
            "http_server_requests_seconds_bucket", "http_server_requests_seconds_count",
            "http_server_requests_seconds_sum", "http_server_requests_seconds_max",
            "http_client_requests_seconds_bucket", "http_client_requests_seconds_count",
            "http_client_requests_seconds_sum", "http_client_requests_seconds_max",
            "jvm_gc_pause_seconds_bucket", "jvm_gc_pause_seconds_count",
            "jvm_gc_pause_seconds_sum", "jvm_gc_pause_seconds_max",
            "hikaricp_connections_acquire_seconds_bucket", "hikaricp_connections_acquire_seconds_count",
            "hikaricp_connections_acquire_seconds_sum", "hikaricp_connections_acquire_seconds_max",
            "jvm_memory_used_bytes", "jvm_memory_max_bytes", "jvm_threads_live_threads",
            "process_uptime_seconds", "process_cpu_usage", "system_cpu_usage",
            "hikaricp_connections_active", "hikaricp_connections_idle", "hikaricp_connections_pending",
            "hikaricp_connections_max", "hikaricp_connections_min",
            "container_cpu_usage_seconds_total", "container_memory_working_set_bytes",
            "pg_up", "pg_stat_database_numbackends", "pg_stat_database_xact_commit", "pg_database_size_bytes",
            "up");

    private AllowedSeries() {
    }

    static Set<String> of(List<MeterSpec> catalogue) {
        Set<String> allowed = new HashSet<>(EXTERNAL);
        catalogue.forEach(spec -> allowed.addAll(spec.prometheusSeries()));
        return Set.copyOf(allowed);
    }
}
