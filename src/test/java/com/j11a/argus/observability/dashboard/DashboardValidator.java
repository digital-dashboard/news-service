package com.j11a.argus.observability.dashboard;

import com.j11a.argus.observability.MeterSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Structural checks for a Grafana dashboard. A query that names a metric nobody publishes renders as an
 * empty panel rather than an error, so those are the failures worth catching before a human opens Grafana.
 */
final class DashboardValidator {

    static final String PROMETHEUS_DATASOURCE = "${DS_PROMETHEUS}";

    private static final Pattern DATASOURCE_VARIABLE = Pattern.compile("\\$\\{\\w+}");
    private static final Pattern LABEL_VALUES = Pattern.compile("^\\s*label_values\\((.*),\\s*\\w+\\s*\\)\\s*$", Pattern.DOTALL);
    private static final Pattern QUERY_RESULT = Pattern.compile("^\\s*query_result\\((.*)\\)\\s*$", Pattern.DOTALL);
    private static final Set<String> TARGETLESS_PANEL_TYPES = Set.of("row", "text");

    private final List<MeterSpec> catalogue;
    private final Set<String> allowedSeries;
    private final Set<String> requiredVariables;
    private final boolean requireCatalogueCoverage;

    DashboardValidator(List<MeterSpec> catalogue, Set<String> requiredVariables, boolean requireCatalogueCoverage) {
        this.catalogue = List.copyOf(catalogue);
        this.allowedSeries = AllowedSeries.of(catalogue);
        this.requiredVariables = Set.copyOf(requiredVariables);
        this.requireCatalogueCoverage = requireCatalogueCoverage;
    }

    List<Violation> validate(JsonNode dashboard) {
        List<JsonNode> panels = allPanels(dashboard);
        List<Violation> violations = new ArrayList<>();
        violations.addAll(titleAndUid(dashboard));
        violations.addAll(panelTypes(panels));
        violations.addAll(requiredVariables(dashboard));
        violations.addAll(panelIdsAndGridPos(panels));
        violations.addAll(literalDatasources(dashboard, panels));
        violations.addAll(nonEmptyTargets(panels));
        Set<String> referenced = new HashSet<>();
        violations.addAll(promqlMetrics(dashboard, panels, referenced));
        if (requireCatalogueCoverage) {
            violations.addAll(catalogueCoverage(referenced));
        }
        return List.copyOf(violations);
    }

    private static List<JsonNode> allPanels(JsonNode dashboard) {
        List<JsonNode> panels = new ArrayList<>();
        for (JsonNode panel : dashboard.path("panels")) {
            panels.add(panel);
            panel.path("panels").forEach(panels::add);
        }
        return panels;
    }

    private List<Violation> titleAndUid(JsonNode dashboard) {
        List<Violation> violations = new ArrayList<>();
        for (String field : List.of("title", "uid")) {
            if (text(dashboard, field).isBlank()) {
                violations.add(new Violation("titleUid", "dashboard", "missing " + field));
            }
        }
        return violations;
    }

    private List<Violation> panelTypes(List<JsonNode> panels) {
        List<Violation> violations = new ArrayList<>();
        if (panels.stream().noneMatch(panel -> "row".equals(text(panel, "type")))) {
            violations.add(new Violation("hasRows", "dashboard", "no row panels"));
        }
        if (panels.stream().noneMatch(panel -> "timeseries".equals(text(panel, "type")))) {
            violations.add(new Violation("hasTimeseries", "dashboard", "no timeseries panels"));
        }
        return violations;
    }

    private List<Violation> requiredVariables(JsonNode dashboard) {
        Set<String> declared = new HashSet<>();
        dashboard.path("templating").path("list").forEach(variable -> declared.add(text(variable, "name")));
        return requiredVariables.stream()
                .filter(name -> !declared.contains(name))
                .sorted()
                .map(name -> new Violation("requiredVariables", "templating", "missing variable " + name))
                .toList();
    }

    private List<Violation> panelIdsAndGridPos(List<JsonNode> panels) {
        List<Violation> violations = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode panel : panels) {
            if (!panel.has("id")) {
                violations.add(new Violation("uniquePanelIds", describe(panel), "panel has no id"));
            } else if (!seen.add(panel.path("id").asInt())) {
                violations.add(new Violation("uniquePanelIds", describe(panel), "duplicate panel id " + panel.path("id").asInt()));
            }
            if (!panel.has("gridPos")) {
                violations.add(new Violation("gridPos", describe(panel), "panel has no gridPos"));
            }
        }
        return violations;
    }

    private List<Violation> literalDatasources(JsonNode dashboard, List<JsonNode> panels) {
        List<Violation> violations = new ArrayList<>();
        for (JsonNode panel : panels) {
            addIfLiteral(violations, panel.path("datasource"), describe(panel) + " datasource");
            for (JsonNode target : panel.path("targets")) {
                addIfLiteral(violations, target.path("datasource"), describe(panel) + " target " + text(target, "refId"));
            }
        }
        for (JsonNode variable : dashboard.path("templating").path("list")) {
            addIfLiteral(violations, variable.path("datasource"), "variable " + text(variable, "name"));
        }
        return violations;
    }

    private static void addIfLiteral(List<Violation> violations, JsonNode datasource, String where) {
        if (isAbsent(datasource)) {
            return;
        }
        String uid = datasourceUid(datasource);
        if (!DATASOURCE_VARIABLE.matcher(uid).matches()) {
            violations.add(new Violation("literalDatasource", where,
                    "datasource must be a ${DS_*} variable, found '" + uid + "'"));
        }
    }

    /** Grafana writes a datasource as an object, a bare uid string, or null; a target without one inherits the panel's. */
    private static String datasourceUid(JsonNode datasource) {
        return datasource.isObject() ? text(datasource, "uid") : datasource.asString("");
    }

    private static boolean isAbsent(JsonNode datasource) {
        return datasource.isMissingNode() || datasource.isNull();
    }

    private static String targetDatasourceUid(JsonNode target, JsonNode panel) {
        JsonNode own = target.path("datasource");
        return datasourceUid(isAbsent(own) ? panel.path("datasource") : own);
    }

    private List<Violation> nonEmptyTargets(List<JsonNode> panels) {
        List<Violation> violations = new ArrayList<>();
        for (JsonNode panel : panels) {
            if (TARGETLESS_PANEL_TYPES.contains(text(panel, "type"))) {
                continue;
            }
            if (panel.path("targets").isEmpty()) {
                violations.add(new Violation("nonEmptyTargets", describe(panel), "panel has no targets"));
            }
            for (JsonNode target : panel.path("targets")) {
                if (queryOf(target).isBlank()) {
                    violations.add(new Violation("nonEmptyTargets", describe(panel), "target " + text(target, "refId") + " has no query"));
                }
            }
        }
        return violations;
    }

    private List<Violation> promqlMetrics(JsonNode dashboard, List<JsonNode> panels, Set<String> referenced) {
        List<Violation> violations = new ArrayList<>();
        for (JsonNode panel : panels) {
            for (JsonNode target : panel.path("targets")) {
                if (PROMETHEUS_DATASOURCE.equals(targetDatasourceUid(target, panel))) {
                    checkMetrics(PromqlMetricExtractor.metricsIn(text(target, "expr")), describe(panel), violations, referenced);
                }
            }
        }
        for (JsonNode variable : dashboard.path("templating").path("list")) {
            if (PROMETHEUS_DATASOURCE.equals(datasourceUid(variable.path("datasource")))) {
                checkVariable(variable, violations, referenced);
            }
        }
        return violations;
    }

    private void checkVariable(JsonNode variable, List<Violation> violations, Set<String> referenced) {
        String where = "variable " + text(variable, "name");
        Optional<String> expression = variableExpression(queryOf(variable));
        if (expression.isEmpty()) {
            violations.add(new Violation("variableQuery", where,
                    "query is neither label_values(expr, label) nor query_result(expr): '" + queryOf(variable) + "'"));
            return;
        }
        checkMetrics(PromqlMetricExtractor.metricsIn(expression.get()), where, violations, referenced);
    }

    private void checkMetrics(Set<String> metrics, String where, List<Violation> violations, Set<String> referenced) {
        for (String metric : metrics) {
            referenced.add(metric);
            if (!allowedSeries.contains(metric)) {
                violations.add(new Violation("unknownMetric", where, "unknown metric '" + metric + "'"));
            }
        }
    }

    private List<Violation> catalogueCoverage(Set<String> referenced) {
        return catalogue.stream()
                .filter(spec -> spec.prometheusSeries().stream().noneMatch(referenced::contains))
                .map(spec -> new Violation("catalogueCoverage", "dashboard", "catalogued meter " + spec.name() + " is not referenced"))
                .toList();
    }

    /** Variable queries are label_values(expr, label) or query_result(expr); the label name is not a metric. */
    private static Optional<String> variableExpression(String query) {
        Matcher labelValues = LABEL_VALUES.matcher(query);
        if (labelValues.matches()) {
            return Optional.of(labelValues.group(1));
        }
        Matcher queryResult = QUERY_RESULT.matcher(query);
        return queryResult.matches() ? Optional.of(queryResult.group(1)) : Optional.empty();
    }

    private static String queryOf(JsonNode node) {
        JsonNode query = node.has("expr") ? node.path("expr") : node.path("query");
        return query.isObject() ? text(query, "query") : query.asString("");
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asString("");
    }

    private static String describe(JsonNode panel) {
        return "panel '" + text(panel, "title") + "' (id " + panel.path("id").asString("?") + ")";
    }
}
