package com.j11a.argus.observability.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.observability.MetricCatalogue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ArgusDashboardTest {

    private static final Path DASHBOARD = Path.of("grafana/dashboards/argus-observability.json");
    private static final Set<String> REQUIRED_VARIABLES = Set.of("DS_PROMETHEUS", "DS_LOKI", "DS_TEMPO");

    private static JsonNode dashboard;

    @BeforeAll
    static void load() throws IOException {
        dashboard = JsonMapper.builder().build().readTree(Files.readString(DASHBOARD));
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> node.asString("")).toList();
    }

    @Test
    void committedDashboardPassesValidation() {
        List<Violation> violations = new DashboardValidator(MetricCatalogue.all(), REQUIRED_VARIABLES, true).validate(dashboard);

        assertThat(violations).isEmpty();
    }

    @Test
    void dashboardIdentityMatchesContract() {
        assertThat(dashboard.path("uid").asString()).isEqualTo("argus-observability");
        assertThat(dashboard.path("title").asString()).isEqualTo("Argus — Observability");
        assertThat(texts(dashboard.path("tags"))).containsExactlyInAnyOrder("argus", "artemis", "observability");
        assertThat(dashboard.path("refresh").asString()).isEqualTo("30s");
        assertThat(dashboard.path("time").path("from").asString()).isEqualTo("now-6h");
        assertThat(dashboard.path("time").path("to").asString()).isEqualTo("now");
    }

    @Test
    void rowsAreInContractOrder() {
        List<String> rows = StreamSupport.stream(dashboard.path("panels").spliterator(), false)
                .filter(panel -> "row".equals(panel.path("type").asString()))
                .map(panel -> panel.path("title").asString())
                .toList();

        assertThat(rows).containsExactly(
                "Overview", "Polling", "Feed health", "Ingestion pipeline", "Deduplication", "Data quality", "Scheduled jobs",
                "API & HTTP", "JVM & runtime", "PostgreSQL & HikariCP", "Container", "Traces", "Logs");
    }

    @Test
    void requiredVariablesArePresent() {
        List<String> names = StreamSupport.stream(dashboard.path("templating").path("list").spliterator(), false)
                .map(variable -> variable.path("name").asString())
                .toList();

        assertThat(names).contains("DS_PROMETHEUS", "DS_LOKI", "DS_TEMPO", "source", "feed", "level", "search");
    }

    @Test
    void everyPanelHasADescription() {
        List<String> undocumented = StreamSupport.stream(dashboard.path("panels").spliterator(), false)
                .filter(panel -> !"row".equals(panel.path("type").asString()))
                .filter(panel -> panel.path("description").asString("").isBlank())
                .map(panel -> panel.path("title").asString())
                .toList();

        assertThat(undocumented).isEmpty();
    }

    @Test
    void containerPanelsSayTheyOnlyHaveDataOnTheRealStack() {
        List<JsonNode> container = StreamSupport.stream(dashboard.path("panels").spliterator(), false)
                .filter(panel -> panel.path("title").asString().startsWith("Container "))
                .toList();

        assertThat(container).hasSize(2).allSatisfy(panel ->
                assertThat(panel.path("description").asString()).contains("cAdvisor"));
    }

    @Test
    void liveLogSearchIsEscapedByGrafanaBeforeItReachesLoki() {
        List<String> lokiQueries = StreamSupport.stream(dashboard.path("panels").spliterator(), false)
                .filter(panel -> "logs".equals(panel.path("type").asString()))
                .flatMap(panel -> StreamSupport.stream(panel.path("targets").spliterator(), false))
                .map(target -> target.path("expr").asString())
                .toList();

        assertThat(lokiQueries).isNotEmpty().allSatisfy(query ->
                assertThat(query).contains("|= ${search:doublequote}").doesNotContain("\"$search\""));
    }

    @Test
    void lokiQueriesAreScopedToTheArgusService() {
        List<String> lokiQueries = StreamSupport.stream(dashboard.path("panels").spliterator(), false)
                .filter(panel -> "${DS_LOKI}".equals(panel.path("datasource").path("uid").asString()))
                .flatMap(panel -> StreamSupport.stream(panel.path("targets").spliterator(), false))
                .map(target -> target.path("expr").asString())
                .toList();

        assertThat(lokiQueries).isNotEmpty().allSatisfy(query -> assertThat(query).contains("{service=\"argus\""));
    }

    private static JsonNode panel(int id) {
        return StreamSupport.stream(dashboard.path("panels").spliterator(), false)
                .filter(panel -> panel.path("id").asInt() == id)
                .findFirst()
                .orElseThrow();
    }

    private static String expr(JsonNode panel, int target) {
        return panel.path("targets").path(target).path("expr").asString();
    }

    @Test
    void lastPollAgeIsEmptyUntilThePollGaugeIsPositive() {
        JsonNode lastPollAge = panel(40);

        assertThat(expr(lastPollAge, 0)).isEqualTo("time() - (max(argus_poll_last_success_seconds{job=\"argus\"}) > 0)");
        assertThat(lastPollAge.path("fieldConfig").path("defaults").path("noValue").asString()).isEqualTo("never");
        assertThat(lastPollAge.path("description").asString()).contains("even if some feeds failed");
    }

    @Test
    void feedHealthTableJoinsItsInstantQueriesOnFeedIdAndMapsMinusOneToNever() {
        JsonNode table = panel(49);

        assertThat(table.path("targets")).hasSize(3).allSatisfy(target ->
                assertThat(target.path("instant").asBoolean()).isTrue());
        assertThat(table.path("transformations").path(0).path("id").asString()).isEqualTo("joinByField");
        assertThat(table.path("transformations").path(0).path("options").path("byField").asString())
                .isEqualTo("feed_id");
        assertThat(table.path("fieldConfig").path("overrides").toString())
                .contains("\"-1\":{\"text\":\"never\"");
    }

    @Test
    void stalestFeedsPutsNeverSucceededFeedsFirst() {
        JsonNode stalest = panel(50);

        assertThat(expr(stalest, 0)).contains("== -1) * 0 + 1e12");
        assertThat(stalest.path("fieldConfig").path("defaults").path("mappings").toString())
                .contains("\"1000000000000\":{\"text\":\"never\"");
        assertThat(stalest.path("description").asString()).contains("never succeeded");
    }

    private record GridPos(int x, int y, int w, int h) {
        boolean overlaps(GridPos other) {
            return x < other.x + other.w && x + w > other.x && y < other.y + other.h && y + h > other.y;
        }
    }

    private static GridPos gridPos(JsonNode panel) {
        JsonNode gp = panel.path("gridPos");
        return new GridPos(gp.path("x").asInt(), gp.path("y").asInt(), gp.path("w").asInt(), gp.path("h").asInt());
    }

    @Test
    void ingestRowPanels32And33AreWidenedWithoutGap() {
        JsonNode p32 = panel(32);
        JsonNode p33 = panel(33);
        assertThat(gridPos(p32)).isEqualTo(new GridPos(0, 44, 12, 8));
        assertThat(gridPos(p33)).isEqualTo(new GridPos(12, 44, 12, 8));
    }

    @Test
    void deduplicationRowContainsPanels34And53Through59InOrder() {
        List<Integer> panelIds = StreamSupport.stream(dashboard.path("panels").spliterator(), false)
                .map(panel -> panel.path("id").asInt())
                .toList();
        int rowIdx = panelIds.indexOf(53);
        assertThat(rowIdx).isGreaterThanOrEqualTo(0);
        assertThat(panelIds.subList(rowIdx, rowIdx + 8))
                .containsExactly(53, 34, 54, 55, 56, 57, 58, 59);
    }

    @Test
    void deduplicationRowContainsExpectedPanelsWithExpressions() {
        JsonNode row = panel(53);
        assertThat(row.path("title").asString()).isEqualTo("Deduplication");
        assertThat(row.path("type").asString()).isEqualTo("row");
        assertThat(gridPos(row)).isEqualTo(new GridPos(0, 52, 24, 1));

        JsonNode p34 = panel(34);
        assertThat(p34.path("title").asString()).isEqualTo("Entry decisions");
        assertThat(gridPos(p34)).isEqualTo(new GridPos(0, 53, 12, 8));
        assertThat(expr(p34, 0)).isEqualTo(
                "sum by (decision) (rate(argus_ingest_entries_total{job=\"argus\",source=~\"$source\"}[$__rate_interval]))");
        assertThat(p34.path("fieldConfig").path("defaults").path("custom").path("stacking").path("mode").asString())
                .isEqualTo("normal");

        JsonNode p54 = panel(54);
        assertThat(p54.path("title").asString()).isEqualTo("Skip reasons");
        assertThat(gridPos(p54)).isEqualTo(new GridPos(12, 53, 12, 8));
        assertThat(expr(p54, 0)).isEqualTo(
                "sum by (reason) (rate(argus_ingest_entries_total{job=\"argus\",source=~\"$source\",decision=\"skipped\"}[$__rate_interval]))");
        assertThat(p54.path("fieldConfig").path("defaults").path("unit").asString()).isEqualTo("ops");

        JsonNode p55 = panel(55);
        assertThat(p55.path("title").asString()).isEqualTo("Link-fallback outcomes");
        assertThat(gridPos(p55)).isEqualTo(new GridPos(0, 61, 8, 8));
        assertThat(expr(p55, 0)).isEqualTo(
                "sum by (outcome) (rate(argus_ingest_link_fallback_total{job=\"argus\",source=~\"$source\"}[$__rate_interval]))");
        assertThat(p55.path("fieldConfig").path("defaults").path("unit").asString()).isEqualTo("ops");

        JsonNode p56 = panel(56);
        assertThat(p56.path("title").asString()).isEqualTo("Lock wait p95 by source");
        assertThat(gridPos(p56)).isEqualTo(new GridPos(8, 61, 8, 8));
        assertThat(expr(p56, 0)).isEqualTo(
                "histogram_quantile(0.95, sum by (le, source) (rate(argus_ingest_lock_wait_seconds_bucket{job=\"argus\",source=~\"$source\"}[$__rate_interval])))");
        assertThat(p56.path("fieldConfig").path("defaults").path("unit").asString()).isEqualTo("s");

        JsonNode p57 = panel(57);
        assertThat(p57.path("title").asString()).isEqualTo("Updates by reason");
        assertThat(gridPos(p57)).isEqualTo(new GridPos(16, 61, 8, 8));
        assertThat(expr(p57, 0)).isEqualTo(
                "sum by (reason) (rate(argus_ingest_entries_total{job=\"argus\",source=~\"$source\",decision=\"updated\"}[$__rate_interval]))");
        assertThat(p57.path("fieldConfig").path("defaults").path("unit").asString()).isEqualTo("ops");

        JsonNode p58 = panel(58);
        assertThat(p58.path("title").asString()).isEqualTo("Duplicate pressure");
        assertThat(gridPos(p58)).isEqualTo(new GridPos(0, 69, 12, 8));
        assertThat(expr(p58, 0)).isEqualTo(
                "sum(rate(argus_ingest_entries_total{job=\"argus\",source=~\"$source\",decision=~\"linked|unchanged\"}[$__rate_interval])) / clamp_min(sum(rate(argus_ingest_entries_total{job=\"argus\",source=~\"$source\"}[$__rate_interval])), 0.000000001)");
        assertThat(p58.path("fieldConfig").path("defaults").path("unit").asString()).isEqualTo("percentunit");

        JsonNode p59 = panel(59);
        assertThat(p59.path("title").asString()).isEqualTo("Cross-feed overlap");
        assertThat(gridPos(p59)).isEqualTo(new GridPos(12, 69, 12, 8));
        assertThat(expr(p59, 0)).isEqualTo(
                "sum(rate(argus_ingest_entries_total{job=\"argus\",source=~\"$source\",decision=\"linked\"}[$__rate_interval])) / clamp_min(sum(rate(argus_ingest_entries_total{job=\"argus\",source=~\"$source\",decision=~\"inserted|linked\"}[$__rate_interval])), 0.000000001)");
        assertThat(p59.path("fieldConfig").path("defaults").path("unit").asString()).isEqualTo("percentunit");
    }

    @Test
    void dataQualityRowStartsAtY77() {
        JsonNode dataQuality = panel(35);
        assertThat(dataQuality.path("title").asString()).isEqualTo("Data quality");
        assertThat(gridPos(dataQuality)).isEqualTo(new GridPos(0, 77, 24, 1));
    }

    @Test
    void noPanelsOverlap() {
        List<JsonNode> panels = StreamSupport.stream(dashboard.path("panels").spliterator(), false).toList();
        for (int i = 0; i < panels.size(); i++) {
            for (int j = i + 1; j < panels.size(); j++) {
                JsonNode a = panels.get(i);
                JsonNode b = panels.get(j);
                GridPos posA = gridPos(a);
                GridPos posB = gridPos(b);
                assertThat(posA.overlaps(posB))
                        .withFailMessage("Panels %s (%s) and %s (%s) overlap: %s vs %s",
                                a.path("id"), a.path("title"), b.path("id"), b.path("title"), posA, posB)
                        .isFalse();
            }
        }
    }
}
