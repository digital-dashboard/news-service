package com.j11a.argus.observability;

import java.util.Set;

public final class MetricNames {

    public static final String PREFIX = "argus";

    public static final String FETCH = "argus.fetch";
    public static final String INGEST = "argus.ingest";
    public static final String FETCH_SIZE = "argus.fetch.size";
    public static final String INGEST_ENTRIES = "argus.ingest.entries";
    public static final String PARSE_MISSING = "argus.parse.missing";

    private MetricNames() {
    }

    public static final class Tags {

        public static final String SOURCE = "source";
        public static final String FEED = "feed";
        public static final String FEED_ID = "feed_id";
        public static final String WATCH = "watch";
        public static final String TRIGGER = "trigger";
        public static final String OUTCOME = "outcome";
        public static final String REASON = "reason";
        public static final String DECISION = "decision";
        public static final String TYPE = "type";
        // Not "job": Prometheus's own target label would turn it into exported_job.
        public static final String SCHEDULED_JOB = "scheduled_job";
        public static final String KIND = "kind";
        public static final String STATE = "state";

        public static final Set<String> ALL = Set.of(
                SOURCE, FEED, FEED_ID, WATCH, TRIGGER, OUTCOME, REASON, DECISION, TYPE, SCHEDULED_JOB, KIND, STATE);

        private Tags() {
        }
    }
}
