package com.j11a.argus.observability.dashboard;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pulls metric names out of a PromQL expression; everything that is not a metric is blanked out first. */
final class PromqlMetricExtractor {

    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|`[^`]*`");
    private static final Pattern GRAFANA_VARIABLE = Pattern.compile("\\$\\{[^}]*}|\\$\\w+");
    private static final Pattern RANGE = Pattern.compile("\\[[^\\]]*]");
    private static final Pattern OFFSET = Pattern.compile("\\boffset\\s+-?\\d+(?:ms|[smhdwy])+");
    private static final Pattern LABEL_MATCHERS = Pattern.compile("\\{[^}]*}");
    private static final Pattern GROUPING_CLAUSE =
            Pattern.compile("(?<![\\w:])(?:by|without|on|ignoring|group_left|group_right)\\s*\\([^)]*\\)");
    // The lookbehind keeps the unit of 5m and the exponent of 1e3 from being read as names.
    private static final Pattern TOKEN = Pattern.compile("(?<![\\w.:])[A-Za-z_:][\\w:]*");
    private static final Pattern CALL_PAREN = Pattern.compile("\\s*\\(");
    private static final Set<String> KEYWORDS = Set.of(
            "by", "without", "on", "ignoring", "group_left", "group_right",
            "and", "or", "unless", "offset", "bool", "inf", "nan");

    private PromqlMetricExtractor() {
    }

    static Set<String> metricsIn(String expression) {
        String stripped = expression;
        for (Pattern noise : new Pattern[] {STRING_LITERAL, GRAFANA_VARIABLE, RANGE, OFFSET, LABEL_MATCHERS, GROUPING_CLAUSE}) {
            stripped = noise.matcher(stripped).replaceAll(" ");
        }
        Set<String> metrics = new LinkedHashSet<>();
        Matcher token = TOKEN.matcher(stripped);
        while (token.find()) {
            if (!KEYWORDS.contains(token.group()) && !isFunctionCall(stripped, token.end())) {
                metrics.add(token.group());
            }
        }
        return metrics;
    }

    private static boolean isFunctionCall(String text, int tokenEnd) {
        return CALL_PAREN.matcher(text).region(tokenEnd, text.length()).lookingAt();
    }
}
