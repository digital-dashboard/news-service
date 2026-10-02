package com.j11a.argus.observability.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.j11a.argus.observability.MeterKind;
import com.j11a.argus.observability.MeterSpec;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class DashboardValidatorTest {

    private static final Set<String> REQUIRED = Set.of("DS_PROMETHEUS", "DS_LOKI", "DS_TEMPO");
    private static final int ROW_ID = 1;
    private static final int REQUEST_RATE_ID = 2;
    private static final int LOGS_ID = 3;
    private static final String UNKNOWN_METRIC = "bogus_metric";
    private static final String PROMETHEUS = "${DS_PROMETHEUS}";
    private static final String UNKNOWN_RATE = "sum(rate(http_server_request_seconds_bucket[5m]))";

    private static final ObjectNode VALID_MINIMAL = loadValidMinimal();

    private final DashboardValidator validator = new DashboardValidator(List.of(), REQUIRED, false);

    private static ObjectNode loadValidMinimal() {
        try (InputStream in = DashboardValidatorTest.class.getResourceAsStream("/dashboards/valid-minimal.json")) {
            return (ObjectNode) JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode mutated(Consumer<ObjectNode> edit) {
        ObjectNode copy = VALID_MINIMAL.deepCopy();
        edit.accept(copy);
        return copy;
    }

    private static ObjectNode panel(JsonNode dashboard, int id) {
        for (JsonNode top : dashboard.path("panels")) {
            if (top.path("id").asInt() == id) {
                return (ObjectNode) top;
            }
            for (JsonNode nested : top.path("panels")) {
                if (nested.path("id").asInt() == id) {
                    return (ObjectNode) nested;
                }
            }
        }
        throw new IllegalArgumentException("no panel " + id);
    }

    private static ObjectNode firstTarget(JsonNode dashboard, int panelId) {
        return (ObjectNode) panel(dashboard, panelId).path("targets").get(0);
    }

    private static ObjectNode variable(JsonNode dashboard, String name) {
        for (JsonNode variable : dashboard.path("templating").path("list")) {
            if (name.equals(variable.path("name").asString())) {
                return (ObjectNode) variable;
            }
        }
        throw new IllegalArgumentException("no variable " + name);
    }

    private static ObjectNode datasource(String uid) {
        return JsonMapper.builder().build().createObjectNode().put("type", "prometheus").put("uid", uid);
    }

    private static void moveIntoRow(ObjectNode dashboard, int panelId) {
        ObjectNode moved = panel(dashboard, panelId);
        ArrayNode top = (ArrayNode) dashboard.path("panels");
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).path("id").asInt() == panelId) {
                top.remove(i);
                break;
            }
        }
        ((ArrayNode) panel(dashboard, ROW_ID).path("panels")).add(moved);
    }

    static Stream<Arguments> violatingEdits() {
        return Stream.of(
                edit("missing uid", "titleUid", d -> d.remove("uid")),
                edit("no rows", "hasRows", d -> ((ArrayNode) d.path("panels")).remove(0)),
                edit("no timeseries", "hasTimeseries", d -> panel(d, REQUEST_RATE_ID).put("type", "stat")),
                edit("missing variable", "requiredVariables", d -> ((ArrayNode) d.path("templating").path("list")).remove(2)),
                edit("duplicate ids", "uniquePanelIds", d -> panel(d, LOGS_ID).put("id", REQUEST_RATE_ID)),
                edit("missing gridPos", "gridPos", d -> panel(d, LOGS_ID).remove("gridPos")),
                edit("literal panel datasource", "literalDatasource",
                        d -> panel(d, LOGS_ID).set("datasource", datasource("loki"))),
                edit("literal target datasource", "literalDatasource",
                        d -> firstTarget(d, REQUEST_RATE_ID).set("datasource", datasource("prometheus"))),
                edit("literal templating datasource", "literalDatasource",
                        d -> variable(d, "job").set("datasource", datasource("prometheus"))),
                edit("literal string-form datasource", "literalDatasource",
                        d -> panel(d, LOGS_ID).put("datasource", "loki")),
                edit("empty target", "nonEmptyTargets", d -> firstTarget(d, REQUEST_RATE_ID).put("expr", "")),
                edit("no targets", "nonEmptyTargets", d -> ((ArrayNode) panel(d, REQUEST_RATE_ID).path("targets")).removeAll()),
                edit("unknown metric", "unknownMetric", d -> firstTarget(d, REQUEST_RATE_ID).put("expr", UNKNOWN_RATE)),
                edit("unprefixed metric", "unknownMetric",
                        d -> firstTarget(d, REQUEST_RATE_ID).put("expr", "rate(fetch_seconds_count[5m])")),
                edit("unknown templating metric", "unknownMetric",
                        d -> variable(d, "job").put("query", "label_values(" + UNKNOWN_METRIC + ", job)")),
                edit("unknown metric on inherited datasource", "unknownMetric", d -> {
                    ObjectNode target = firstTarget(d, REQUEST_RATE_ID);
                    target.remove("datasource");
                    target.put("expr", UNKNOWN_METRIC);
                }),
                edit("unknown metric with null target datasource inheriting the panel", "unknownMetric", d -> {
                    ObjectNode target = firstTarget(d, REQUEST_RATE_ID);
                    target.putNull("datasource");
                    target.put("expr", UNKNOWN_METRIC);
                }),
                edit("unknown metric with string-form target datasource", "unknownMetric", d -> {
                    ObjectNode target = firstTarget(d, REQUEST_RATE_ID);
                    target.put("datasource", PROMETHEUS);
                    target.put("expr", UNKNOWN_METRIC);
                }),
                edit("unknown metric with string-form panel datasource", "unknownMetric", d -> {
                    ObjectNode target = firstTarget(d, REQUEST_RATE_ID);
                    target.remove("datasource");
                    target.put("expr", UNKNOWN_METRIC);
                    panel(d, REQUEST_RATE_ID).put("datasource", PROMETHEUS);
                }),
                edit("unknown metric in a collapsed row", "unknownMetric", d -> {
                    firstTarget(d, REQUEST_RATE_ID).put("expr", UNKNOWN_METRIC);
                    moveIntoRow(d, REQUEST_RATE_ID);
                }),
                edit("unrecognised variable query", "variableQuery", d -> variable(d, "job").put("query", "up")));
    }

    private static Arguments edit(String name, String rule, Consumer<ObjectNode> edit) {
        return Arguments.of(name, rule, edit);
    }

    @Test
    void acceptsMinimalValidFixtureAndSkipsNonPrometheusQueries() {
        assertThat(validator.validate(VALID_MINIMAL)).isEmpty();
    }

    @ParameterizedTest(name = "{0} yields exactly one {1} violation")
    @MethodSource("violatingEdits")
    void rejectsEachRuleViolationOnce(String name, String rule, Consumer<ObjectNode> edit) {
        List<Violation> violations = validator.validate(mutated(edit));

        assertThat(violations).extracting(Violation::rule).containsExactly(rule);
    }

    @Test
    void acceptsStringFormAndNullTargetDatasourcesThatResolveToPrometheus() {
        ObjectNode dashboard = mutated(d -> {
            ObjectNode panel = panel(d, REQUEST_RATE_ID);
            panel.put("datasource", PROMETHEUS);
            firstTarget(d, REQUEST_RATE_ID).putNull("datasource");
        });

        assertThat(validator.validate(dashboard)).isEmpty();
    }

    @Test
    void unknownMetricViolationNamesMetricAndPanel() {
        List<Violation> violations = validator.validate(
                mutated(d -> firstTarget(d, REQUEST_RATE_ID).put("expr", UNKNOWN_RATE)));

        assertThat(violations.get(0).message()).contains("http_server_request_seconds_bucket");
        assertThat(violations.get(0).where()).contains("Request rate");
    }

    @Test
    void unrecognisedVariableQueryViolationNamesTheVariable() {
        List<Violation> violations = validator.validate(mutated(d -> variable(d, "job").put("query", "up")));

        assertThat(violations.get(0).where()).isEqualTo("variable job");
        assertThat(violations.get(0).message()).contains("'up'");
    }

    @Test
    void acceptsCataloguedMetricFromSyntheticCatalogue() {
        MeterSpec spec = new MeterSpec("argus.fetch", MeterKind.TIMER, null, Set.of());
        DashboardValidator withCatalogue = new DashboardValidator(List.of(spec), REQUIRED, false);
        ObjectNode dashboard = mutated(d -> firstTarget(d, REQUEST_RATE_ID).put("expr", "rate(argus_fetch_seconds_count[5m])"));

        assertThat(withCatalogue.validate(dashboard)).isEmpty();
        assertThat(validator.validate(dashboard)).extracting(Violation::rule).containsExactly("unknownMetric");
    }

    @Test
    void flagsUncataloguedMeterWhenCoverageRequired() {
        MeterSpec spec = new MeterSpec("argus.fetch", MeterKind.TIMER, null, Set.of());
        MeterSpec other = new MeterSpec("argus.parse", MeterKind.COUNTER, null, Set.of());
        DashboardValidator strict = new DashboardValidator(List.of(spec, other), REQUIRED, true);
        ObjectNode dashboard = mutated(d -> firstTarget(d, REQUEST_RATE_ID).put("expr", "rate(argus_fetch_seconds_count[5m])"));

        List<Violation> violations = strict.validate(dashboard);

        assertThat(violations).extracting(Violation::rule).containsExactly("catalogueCoverage");
        assertThat(violations.get(0).message()).contains("argus.parse");
    }

    @Test
    void coverageIsIgnoredWhenNotRequired() {
        MeterSpec unused = new MeterSpec("argus.parse", MeterKind.COUNTER, null, Set.of());
        DashboardValidator lenient = new DashboardValidator(List.of(unused), REQUIRED, false);

        assertThat(lenient.validate(VALID_MINIMAL)).isEmpty();
    }
}
