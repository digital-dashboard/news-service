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
        List<Violation> violations = new DashboardValidator(MetricCatalogue.ALL, REQUIRED_VARIABLES, true).validate(dashboard);

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
                "Overview", "Ingestion pipeline", "Data quality", "API & HTTP", "JVM & runtime",
                "PostgreSQL & HikariCP", "Container", "Traces", "Logs");
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
}
