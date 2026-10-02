package com.j11a.argus.observability.dashboard;

import com.j11a.argus.observability.MeterKind;
import com.j11a.argus.observability.MeterSpec;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Series a dashboard query may reference: the catalogue plus what the framework and exporters publish.
 * External series reuse {@link MeterSpec} so their suffix sets are exact; the dotted names are only a
 * spelling of the Prometheus names.
 */
final class AllowedSeries {

    private static final String BYTES = "bytes";
    private static final String SECONDS = "seconds";

    private static final List<MeterSpec> EXTERNAL = List.of(
            external("http.server.requests", MeterKind.TIMER, null),
            external("http.client.requests", MeterKind.TIMER, null),
            external("jvm.gc.pause", MeterKind.TIMER, null),
            external("hikaricp.connections.acquire", MeterKind.TIMER, null),
            external("jvm.memory.used", MeterKind.GAUGE, BYTES),
            external("jvm.memory.max", MeterKind.GAUGE, BYTES),
            external("jvm.threads.live", MeterKind.GAUGE, "threads"),
            external("process.uptime", MeterKind.GAUGE, SECONDS),
            external("process.cpu.usage", MeterKind.GAUGE, null),
            external("system.cpu.usage", MeterKind.GAUGE, null),
            external("hikaricp.connections.active", MeterKind.GAUGE, null),
            external("hikaricp.connections.idle", MeterKind.GAUGE, null),
            external("hikaricp.connections.pending", MeterKind.GAUGE, null),
            external("hikaricp.connections.max", MeterKind.GAUGE, null),
            external("hikaricp.connections.min", MeterKind.GAUGE, null),
            external("container.cpu.usage.seconds", MeterKind.COUNTER, null),
            external("container.memory.working.set", MeterKind.GAUGE, BYTES),
            external("pg.up", MeterKind.GAUGE, null),
            external("pg.stat.database.numbackends", MeterKind.GAUGE, null),
            external("pg.stat.database.xact.commit", MeterKind.GAUGE, null),
            external("pg.database.size", MeterKind.GAUGE, BYTES),
            external("up", MeterKind.GAUGE, null));

    private AllowedSeries() {
    }

    static Set<String> of(List<MeterSpec> catalogue) {
        Set<String> allowed = new HashSet<>();
        Stream.concat(EXTERNAL.stream(), catalogue.stream()).forEach(spec -> allowed.addAll(spec.prometheusSeries()));
        return Set.copyOf(allowed);
    }

    private static MeterSpec external(String name, MeterKind kind, String baseUnit) {
        return new MeterSpec(name, kind, baseUnit, Set.of());
    }
}
