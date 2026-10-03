package com.j11a.argus.observability;

/** Names of the structured log fields. Dashboards and Loki queries depend on them, so they live in one place. */
public final class LogKeys {

    public static final String FEED_ID = "feedId";
    public static final String SOURCE_ID = "sourceId";
    public static final String SOURCE_KEY = "sourceKey";
    public static final String URL = "url";
    public static final String REASON = "reason";
    public static final String HTTP_STATUS = "httpStatus";
    public static final String ERROR_TYPE = "errorType";
    public static final String ERROR_MESSAGE = "errorMessage";
    public static final String ATTEMPT = "attempt";
    public static final String MAX_ATTEMPTS = "maxAttempts";
    public static final String CONSECUTIVE_FAILURES = "consecutiveFailures";
    public static final String FAILING_THRESHOLD = "failingThreshold";
    public static final String DURATION_MS = "durationMs";
    public static final String CONTENT_TYPE = "contentType";
    public static final String BODY_BYTES = "bodyBytes";
    public static final String EXISTING_FEED_ID = "existingFeedId";
    public static final String FAILED_FEED_IDS = "failedFeedIds";
    public static final String FAILED = "failed";
    public static final String FEEDS_POLLED = "feedsPolled";
    public static final String CODE = "code";
    public static final String STATUS = "status";
    public static final String METHOD = "method";
    public static final String PATH = "path";
    public static final String ENABLED = "enabled";
    public static final String ARTICLES_REMOVED = "articlesRemoved";
    public static final String CHANGED_FIELDS = "changedFields";
    public static final String CRON = "cron";
    public static final String CONCURRENCY = "concurrency";
    public static final String ENABLED_FEEDS = "enabledFeeds";
    public static final String FAILING_FEEDS = "failingFeeds";

    private LogKeys() {
    }
}
