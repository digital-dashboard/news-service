package com.j11a.argus.observability.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.observability.MeterKind;
import com.j11a.argus.observability.MeterSpec;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DashboardValidatorTest {

    private static final Set<String> REQUIRED = Set.of("DS_PROMETHEUS", "DS_LOKI", "DS_TEMPO");
    private final DashboardValidator validator = new DashboardValidator(List.of(), REQUIRED, false);

    private static JsonNode fixture(String name) throws IOException {
        try (InputStream in = DashboardValidatorTest.class.getResourceAsStream("/dashboards/" + name)) {
            return JsonMapper.builder().build().readTree(in);
        }
    }

    @Test
    void acceptsMinimalValidFixtureAndSkipsNonPrometheusQueries() throws IOException {
        assertThat(validator.validate(fixture("valid-minimal.json"))).isEmpty();
    }

    @ParameterizedTest(name = "{0} yields exactly one {1} violation")
    @CsvSource({
        "invalid-missing-uid.json, titleUid",
        "invalid-no-rows.json, hasRows",
        "invalid-no-timeseries.json, hasTimeseries",
        "invalid-missing-variable.json, requiredVariables",
        "invalid-duplicate-ids.json, uniquePanelIds",
        "invalid-missing-gridpos.json, gridPos",
        "invalid-literal-panel-datasource.json, literalDatasource",
        "invalid-literal-target-datasource.json, literalDatasource",
        "invalid-literal-templating-datasource.json, literalDatasource",
        "invalid-empty-target.json, nonEmptyTargets",
        "invalid-no-targets.json, nonEmptyTargets",
        "invalid-unknown-metric.json, unknownMetric",
        "invalid-unprefixed-metric.json, unknownMetric",
        "invalid-unknown-templating-metric.json, unknownMetric",
        "invalid-target-metric-on-inherited-datasource.json, unknownMetric"
    })
    void rejectsEachRuleViolationOnce(String file, String rule) throws IOException {
        List<Violation> violations = validator.validate(fixture(file));

        assertThat(violations).extracting(Violation::rule).containsExactly(rule);
    }

    @Test
    void unknownMetricViolationNamesMetricAndPanel() throws IOException {
        List<Violation> violations = validator.validate(fixture("invalid-unknown-metric.json"));

        assertThat(violations.get(0).message()).contains("http_server_request_seconds_bucket");
        assertThat(violations.get(0).where()).contains("Request rate");
    }

    @Test
    void acceptsCataloguedMetricFromSyntheticCatalogue() throws IOException {
        MeterSpec spec = new MeterSpec("argus.fetch", MeterKind.TIMER, null, Set.of());
        DashboardValidator withCatalogue = new DashboardValidator(List.of(spec), REQUIRED, false);

        assertThat(withCatalogue.validate(fixture("valid-catalogued-metric.json"))).isEmpty();
        assertThat(validator.validate(fixture("valid-catalogued-metric.json")))
                .extracting(Violation::rule).containsExactly("unknownMetric");
    }

    @Test
    void flagsUncataloguedMeterWhenCoverageRequired() throws IOException {
        MeterSpec spec = new MeterSpec("argus.fetch", MeterKind.TIMER, null, Set.of());
        MeterSpec other = new MeterSpec("argus.parse", MeterKind.COUNTER, null, Set.of());
        DashboardValidator strict = new DashboardValidator(List.of(spec, other), REQUIRED, true);

        List<Violation> violations = strict.validate(fixture("valid-catalogued-metric.json"));

        assertThat(violations).extracting(Violation::rule).containsExactly("catalogueCoverage");
        assertThat(violations.get(0).message()).contains("argus.parse");
    }

    @Test
    void coverageIsIgnoredWhenNotRequired() throws IOException {
        MeterSpec unused = new MeterSpec("argus.parse", MeterKind.COUNTER, null, Set.of());
        DashboardValidator lenient = new DashboardValidator(List.of(unused), REQUIRED, false);

        assertThat(lenient.validate(fixture("valid-minimal.json"))).isEmpty();
    }

    @Test
    void findsPanelsNestedInCollapsedRows() throws IOException {
        List<Violation> violations = validator.validate(fixture("invalid-nested-unknown-metric.json"));

        assertThat(violations).extracting(Violation::rule).containsExactly("unknownMetric");
    }
}
