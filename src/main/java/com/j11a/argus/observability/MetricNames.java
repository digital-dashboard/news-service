package com.j11a.argus.observability;

/** Every custom meter name and tag key. Each later phase adds its constants here and its spec to {@link MetricCatalogue}. */
public final class MetricNames {

    public static final String PREFIX = "argus";

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

        private Tags() {
        }
    }
}
