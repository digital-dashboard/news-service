# Diagnostic logging plan

> Context: phase 4 is deployed. Two CBC feeds fail every poll with `reason=io`. The logs say only `Ingest FAILED: ... reason=io`, so finding the cause means reading code or running curl.
>
> Goal: the logs are the first line of diagnosis. Every failure says what failed, where and why, as structured fields that can be filtered in Loki.

## Decisions (owner, 2026-10-03)

1. **Levels for repeated failures**
   - Every failed fetch, parse or persist logs one **WARN** line.
   - When a feed crosses the failing threshold (default 3 consecutive failures), one **ERROR** line is logged, once per crossing.
   - Recovery after failures logs **INFO**.
2. **URLs in logs.** Logs carry only redacted URLs: scheme, host, port and path, produced by the existing `HttpUrls.redact`. There is never a query string or user-info. URLs that appear inside exception messages are redacted the same way.
3. **Scope.** This is a full audit:
   - failure paths, retries and health transitions;
   - create, delete, enable and disable of feeds, and the source PATCH, logged as audit lines;
   - client errors (4xx);
   - poll summaries.

   Every logger becomes Lombok `@Slf4j`.

## Verified facts

- In Spring Boot 4.1.1, `LogstashStructuredLogFormatter` writes MDC entries **and** SLF4J key-value pairs as top-level JSON fields (`ILoggingEvent::getKeyValuePairs`). So `log.atWarn().addKeyValue("feedId", id)...` produces `"feedId": 5` in Loki, and `{service="argus"} | json | reason="io"` works.
- Boot manages Lombok at **1.18.46**. The pom configures no compiler plugin of its own, so annotation processing comes from the classpath.
- The cause is lost today in three places:
  - `FeedFetcher.attempt` throws `new RetryableFetchException(reason, null)`.
  - `FetchResult.Failed` holds only `reason` and `httpStatus`.
  - `FeedLoader` turns `FeedParseException` into a reason string.
- `FeedHealthUpdater.recordFailure` and `recordSuccess` return `void`, so no caller knows the consecutive-failure count.
- `feed.last_error` stays a reason code only. That was a phase-3 decision: it is shown in `GET /feeds` and must not grow.

## Logging contract

### Fields
All structured fields are SLF4J key-values with camelCase names. They are emitted only when they apply.

| Field | Meaning |
|---|---|
| `feedId`, `sourceId` | Already MDC during ingest; added as key-values elsewhere. |
| `sourceKey` | For example `cbc.ca`. |
| `url` | Redacted feed URL. |
| `reason` | The `FetchFailureReason` tag or `FailureReasons` code. |
| `httpStatus` | Integer status code. |
| `errorType` | Simple class name of the **root cause**, for example `SSLHandshakeException` or `ConnectException`. |
| `errorMessage` | Root-cause message, with URLs redacted, control characters replaced and at most 300 characters. Not logged for `persist_failed` and `unexpected_error`: their messages can quote article rows. |
| `attempt`, `maxAttempts` | The fetch attempt that failed and the attempts allowed. Only on the retry INFO lines. |
| `consecutiveFailures`, `failingThreshold` | Health counters. |
| `durationMs` | Elapsed time. |
| `contentType`, `bodyBytes` | Parse failures. `contentType` is capped at 100 characters. |

Never log any of these:
- full URLs with query or user-info;
- the admin key or any header value from a request;
- article content;
- article GUIDs or links;
- stack traces for expected failures. Stack traces are only for unexpected exceptions, at ERROR.

### Message text
Each message is one human sentence that already contains the key facts. It must read well in the plain-text `dev` profile, which does not print key-values. For example:

`Ingest failed for feed 5 (cbc.ca): io SSLHandshakeException: Remote host terminated the handshake; 3 consecutive failures`

A retry INFO line carries the attempt counts: `Fetch attempt 1/3 for cbc.ca failed (io SSLHandshakeException), retrying`.

### Lombok
- Add `org.projectlombok:lombok` with `<optional>true</optional>`. The version comes from the Boot BOM.
- Add `lombok.config` at the repo root:
  ```
  config.stopBubbling = true
  lombok.addLombokGeneratedAnnotation = true
  ```
  The second line makes JaCoCo and Sonar skip generated code.
- Every class that logs uses `@Slf4j`, with the field `log`. Replace all 8 existing `LoggerFactory` loggers.
- Check that `lombok` is **not** packaged in `target/argus.jar`, using `unzip -l target/argus.jar | grep -i lombok`. If it is, exclude it in `spring-boot-maven-plugin`.

## Changes

### 1. Carry the cause (`feed/fetch`)
- Add `url/LogSafe.java` with:
  - `static String redactUrls(String text)`, which replaces every `https?://\S+` match with `HttpUrls.redact(match)`;
  - `static @Nullable String errorMessage(Throwable)`, which takes the root-cause message, redacts it and caps it at 300 characters;
  - `static String errorType(Throwable)`, which returns the root-cause simple name.
- Add a nullable `@Nullable FetchError error` component to `FetchResult.Failed`, where `FetchError(String type, @Nullable String message)` is a record. Update the call sites.
- `RetryableFetchException` carries the original exception as its `cause`. `toFailedResult()` builds `FetchError` from it.
- Non-exception failures use a descriptive `FetchError` instead of null:
  - `TOO_LARGE`: `("BodyTooLarge", "body exceeded <N> bytes")`
  - `REDIRECT_LIMIT`: `("RedirectLimit", "more than <N> redirects")`
  - `INVALID_URL`: `("InvalidUrl", "<scheme or reason>")`
  - `HTTP_STATUS`: `("HttpStatus", "<status> <reason phrase>")`
- **Retries.** In `RetryingFeedFetcher`'s `RetryListener.beforeRetry`, log at **INFO**: `Fetch attempt {n}/{max} for {sourceKey} failed ({reason} {errorType}), retrying`, with key-values. Keep the existing retry counter.

### 2. Loader and ingest (`ingest`)
- `FeedLoader.Loaded.Failed` and `CreateLoaded.Failed` carry `@Nullable FetchError error`, plus `contentType` and `bodyBytes` for parse failures.
- A `FeedParseException` becomes `FetchError("FeedParseException", <redacted message>)`.
- `FeedIngestService.refreshLoaded`:
  - After `healthUpdater.recordFailure(...)`, which now returns the new count, log the failure **WARN** with every field in the contract.
  - If the new count equals `properties.failingThreshold()`, also log **ERROR**: `Feed {id} ({sourceKey}) is now failing after {n} consecutive failures; last error: {reason} {errorType}: {errorMessage}`.
  - The `unexpected_error` and `persist_failed` paths log one ERROR line with the full fields (without `errorMessage`) and the stack trace, instead of the WARN, and throw `IngestFailedException`. `FeedPoller` and the manual refresh endpoint do not log that failure again. If it also crosses the threshold, the threshold ERROR is logged as well.
- The existing per-ingest summary line keeps its current text. Add the `sourceKey` and `url` key-values, and `durationMs` if it is cheaply available.

### 3. Health (`feed/health/FeedHealthUpdater`)
- `int recordFailure(...)` returns the new `consecutive_failures`, using `UPDATE ... RETURNING consecutive_failures`.
- `int recordSuccess(...)` and `int recordNotModified(...)` return the count **before** the reset, using a CTE that reads the old value `FOR UPDATE` in the same statement.
- When that previous count was greater than 0, `FeedIngestService` logs **INFO**: `Feed {id} ({sourceKey}) recovered after {n} consecutive failures`.

### 4. API audit lines
- **`FeedService`**:
  - Create success logs INFO `Feed {id} created: {name} ({sourceKey}, topic {topic}) from {redacted url}`.
  - Create rejected 422 logs WARN with `url`, `reason`, `errorType` and `errorMessage`.
  - Create conflict 409 logs INFO with `url` and `existingFeedId`.
  - `GlobalExceptionHandler` logs neither of these again (DEBUG at most).
  - Delete logs INFO with `feedId` and the number of articles removed.
  - Enable or disable logs INFO.
- **`SourceService.patch`** logs INFO with `sourceId`, `sourceKey` and the names of the changed fields, plus their new values. Values are fine here: name, country, and the redacted homepage.
- **`GlobalExceptionHandler`**:
  - A genuinely unhandled exception logs ERROR with the stack trace: `Unhandled exception: {method} {path}`.
  - A handled 5xx (for example a 503 during shutdown) logs WARN without a stack trace: `Request failed: {code} {status} {method} {path}`.
  - 401 `ADMIN_KEY_REQUIRED` under `/news/v2` logs WARN with method and path, and DEBUG elsewhere. Never log the header.
  - Other 4xx log INFO with `code`, `status`, method and path (no query string).
  - 404 and 405 for paths outside `/news/v2` log DEBUG, to keep scanner noise down.

### 5. Poll summary and startup
- `PollingTelemetry`'s completed line adds `failedFeedIds=[5, 6, 13]`, both in the text and as a key-value. It is bounded by the number of feeds.
- One INFO line at startup, from an `ApplicationReadyEvent` listener: `Polling scheduled with cron {cron}, concurrency {n}; {enabled} enabled feeds, {failing} failing`.

### 6. Docs
- `observability/README.md` gets a **Logs** section covering:
  - the field table;
  - what WARN, ERROR and INFO mean for feeds;
  - example LogQL queries:
    - `{service="argus"} | json | feedId="5"`
    - `{service="argus", level="ERROR"}`
    - `{service="argus"} | json | reason="io" | line_format "{{.sourceKey}} {{.errorType}}: {{.errorMessage}}"`
- `plans/argus.md`: the observability contract's **Logs** bullet gains "structured key-value fields per the logging contract in `plans/argus-logging.md`".

## Tests (TDD)
- Extend `LogCapture` with `keyValues(ILoggingEvent)` to return a `Map<String, Object>`.
- **Unit:**
  - `LogSafeTest`: redaction of URLs inside messages, the 300-character cap, root-cause unwrapping.
  - `FeedFetcherTest` and `RetryingFeedFetcherTest`:
    - `Failed.error` is populated for a 503 (`HttpStatus`), for a connection refused to a closed port (`ConnectException`), and for too-large and redirect-limit failures.
    - Retries log INFO per retry.
- **ITs:**
  - `FeedHealthIT`, or a new `FailureLoggingIT`:
    - A failing stub feed logs one WARN per refresh, with `feedId`, `sourceKey`, a redacted `url` (assert that a `?token=SECRET` query is absent), `reason`, `httpStatus`, `errorType` and `consecutiveFailures`.
    - The third failure also logs exactly one ERROR, and a fourth failure does not log another.
    - A following success logs INFO recovery with the previous count.
  - A parse failure (the `not-a-feed.html` fixture) logs WARN with `errorType=FeedParseException`, `contentType` and `bodyBytes`.
  - Create rejected logs WARN, create success logs INFO, and a source PATCH logs INFO.
  - `JsonLoggingIT`: in the JSON (`logstash`) format, a key-value such as `feedId` appears as a top-level JSON field.
- No test may assert on whole message strings beyond the key facts. Assert fields.

## Execution
- Branch `feat/argus-diagnostic-logging` from `main`.
- One Sonnet implementer, TDD, one commit `feat: diagnostic structured logging for failures, health and audit events`, no trailers.
- Then the six-reviewer gate, triage and fixes. Then a PR, with Sonar read **before** merging.
- **Verify:** `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B -s /tmp/argus-central-settings.xml clean verify` is green with 0 skipped, and Lombok is absent from `target/argus.jar`.
