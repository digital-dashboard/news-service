# Phase 3 execution plan: scheduled, resilient polling

> Parent plan: `plans/argus.md` → Phase 3. PRD: `docs/prd-argus.md`. Previous phases: `plans/argus-phase-1.md`, `plans/argus-phase-2.md`.

## Context

- **Repo today.** Phases 1 and 2 are merged to `main`, deployed, and Gate B verified.
  - Argus is an RSS aggregator on Spring Boot 4.1.1 / Java 21.
  - It runs on PostgreSQL 18 behind PgBouncer in transaction pooling mode.
- **Baseline.** `clean verify` is green.
  - 500 tests (unit tests plus 61 Testcontainers ITs), 0 skipped.
  - About 99.7% line+branch coverage.
  - 0 Sonar issues; the dashboard validator passes, with catalogue coverage on.
- **Untracked owner files.** `interactive_visual_im_b13838ce8d7ace99.html` and `.DS_Store` stay untouched.
- **Known live quirk to fix.** On the create path, the fetch metric is tagged `source=<feed URL host>` (e.g. `bbci.co.uk`). Ingest and refresh use the stored source key (`bbc.co.uk`). One outlet therefore shows as two series.
- **Verified against the Spring Framework 7.0.9 / Micrometer jars on the classpath:**
  - **`@EnableResilientMethods`** enables `@Retryable` and `@ConcurrencyLimit` (`org.springframework.resilience.annotation`).
  - **`@ConcurrencyLimit(value = 1, policy = ThrottlePolicy.REJECT)`** throws `org.springframework.resilience.InvocationRejectedException`, a subclass of `RejectedExecutionException`, on overlap.
  - **`@Retryable`** supports:
    - `includes` / `excludes` / `predicate`
    - `maxRetriesString`, `delayString`, `multiplierString`, `maxDelayString`, `jitterString`
    - **`timeout` / `timeoutString`**, an overall retry budget
  - **`MethodRetryEvent`** (`org.springframework.resilience.retry`) is published per failed attempt, with `getFailure()`, `isRetryAborted()` and the `MethodInvocation`.
  - **MDC propagation.** `io.micrometer.context.integration.Slf4jThreadLocalAccessor` exists. It must be registered with `ContextRegistry` (verify whether Boot already does) so that `ContextPropagatingTaskDecorator` carries MDC to virtual threads. The observation scope is carried by Micrometer's own accessor.
- **PgBouncer** in transaction mode:
  - no session state;
  - no `SET`;
  - only `pg_advisory_xact_lock` (none needed in this phase);
  - no connection held during HTTP fetching or parsing.
- **Graceful shutdown.** 30s (`server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 30s`).

## Decisions (resolved with the owner)

1. **Source-tag split fix.**
   - Polls and refreshes always tag with the stored `feed.getSource().getKey()`. That includes `argus.fetch`, `argus.fetch.size` and `argus.fetch.retry`.
   - **Create path (`POST /feeds`):** the source isn't known until after parsing, and an Observation's tags must be set before it stops. So on the create path only, the fetch is timed with a `Timer.Sample`. The sample is stopped once the source key has been resolved from the parsed site link, and its tags are `outcome`, `reason` and the resolved `source`. The `argus.fetch` span is still produced, via the tracer, so traces don't change.
   - If the create-path fetch or parse fails, there is no source, so it is tagged `source=unknown`. Feed hosts that never become sources stay out of the `source` label values.
2. **`POST /feeds/refresh` (manual poll-all).**
   - Synchronous 200 with an `AggregatePollReport`:
     - `pollId`, `trigger`, `durationMs`
     - `feedsPolled`, `succeeded`, `notModified`, `failed`
     - `entriesSeen`, `inserted`, `unchanged`, `skipped`
     - per-feed `reports` (`IngestReport` items)
   - An overlapping poll (manual or scheduled) raises `InvocationRejectedException`, which `GlobalExceptionHandler` maps to 409 `POLL_IN_PROGRESS`.
   - The worst-case duration is bounded by the per-fetch budget (decision 7) and the concurrency. The README notes that the call is synchronous.
3. **`GET /feeds` and `GET /feeds/{id}`.**
   - The list is a `PagedModel<FeedResponse>`, ordered by `id ASC`. It uses the same validation as `/articles`: `@Min(0) page`, `@Min(1) @Max(100) size`, default size 20, and 400 on anything invalid.
   - **Both the list and the detail view** include health:
     - `lastFetchedAt`, `lastSuccessAt`
     - `lastError`, holding a reason code only (decision 9)
     - `consecutiveFailures`
     - `state`: `healthy`, `failing` or `disabled`
   - Feed URLs are redacted (no user-info or query string) unless the request carries a valid `X-Admin-Key` (`AdminAccess.isAdmin()`).
4. **`PATCH /feeds/{id}`.**
   - Takes `PatchFeedRequest(@NotNull Boolean enabled)` and needs the admin key. An empty body or `null` gives 400.
   - Returns the updated `FeedResponse`. The change takes effect on the next poll without a restart.
   - Other feed edits stay deferred to Phases 4–5.
5. **`DELETE /feeds/{id}`.** Needs the admin key and returns 204, or 404 `FEED_NOT_FOUND`. It runs in **one transaction**:
   1. Collect the ids of the articles linked to this feed.
   2. Delete the feed. `article_feed` cascades.
   3. Delete those collected articles that have no remaining `article_feed` row.

   It never scans the whole article table. The source row is kept; sources are only removed by a merge in Phase 5. The feed's gauge rows are removed right away (decision 8).
6. **Polling.**
   - Properties live in a new validated `PollProperties` record (`argus.poll.*`):
     - `cron`, default `0 */15 * * * *` (`ARGUS_POLL_CRON`)
     - `concurrency`, default 8 (`ARGUS_POLL_CONCURRENCY`)
     - `failing-threshold`, default 3 (`ARGUS_POLL_FAILING_THRESHOLD`)
   - `argus.poll.cron: "-"` disables the schedule. **The `it` profile sets it to `-`**, so the cron can never fire during tests. There is no separate `enabled` flag.
7. **Retries and the fetch budget.**
   - Retry config lives in the existing `FetchProperties` record (`argus.fetch.retry.*`):
     - `max-retries`, default 2 (`ARGUS_FETCH_RETRY_MAX_RETRIES`)
     - `delay`, default 1s (`ARGUS_FETCH_RETRY_DELAY`)
     - `multiplier`, default 2.0 (`ARGUS_FETCH_RETRY_MULTIPLIER`)
     - `timeout`, an overall budget per fetch, default 25s (`ARGUS_FETCH_RETRY_TIMEOUT`)
   - **Retries are per whole fetch, not per redirect hop.** The retryable unit is one complete `fetch(url, validators)` call, redirects included.
   - Only 5xx responses and transient I/O are retried: `HttpTimeoutException`, `SocketTimeoutException`, connect or reset failures. 4xx, redirect-limit, invalid-URL and too-large responses are never retried.
   - Once retries are exhausted, the last failure becomes a `Failed(reason, status)` result, never an exception that leaks out.
8. **Feed health gauges.**
   - The values are **value functions over the current feed rows**, not snapshots:
     - `argus.feed.state` (1 per feed, `state` = `healthy` | `failing` | `disabled`, mutually exclusive)
     - `argus.feed.consecutive.failures`
     - `argus.feed.since.last.success`, in seconds, computed as `now − lastSuccessAt` at scrape time, and `-1` if the feed has never succeeded
   - The rows (which feeds exist, and their state) are refreshed:
     - at startup;
     - after every poll;
     - after every create, PATCH, DELETE and refresh.
   - Deleted feeds disappear from the next scrape.
   - **Deliberate PRD exception:** these gauges carry a `feed_id` tag. That is bounded (about 25–40 feeds) and required by the parent plan's per-feed feed-health panels.
9. **`last_error` content.**
   - It holds the failure's reason tag plus the HTTP status, e.g. `http_status 503` or `timeout`.
   - It never holds URLs, query strings, upstream bodies or parser messages, because feed URLs may carry tokens.
   - The column is `text`, written through a length-capped constant format.
10. **Failing.** A feed is failing when `consecutive_failures >= failing-threshold` and it is enabled.

## Design

### Packages and components

| Package | Component | Responsibility |
|---|---|---|
| `feed.fetch` | `FeedFetcher` | Sends a conditional GET (`If-None-Match` / `If-Modified-Since` from the stored validators) on the first hop. Captures `ETag` / `Last-Modified` from the final 200, maps 304 to `NotModified`, and keeps the per-hop URL validation. |
| `feed.fetch` | `RetryingFeedFetcher` | A Spring bean whose single `fetch(url, validators, sourceKey)` method is `@Retryable(includes = RetryableFetchException.class, maxRetriesString = …, delayString = …, multiplierString = …, timeoutString = …)`. It throws `RetryableFetchException` on 5xx or transient I/O, and converts exhaustion into `Failed`. **`sourceKey` is a method argument** so the retry listener can tag by the stored source. |
| `feed.fetch` | `FetchResult` | A sealed interface with three cases: `Fetched(body, contentType, finalUrl, permanentTarget, validators)`, `NotModified(finalUrl, permanentTarget, validators)` and `Failed(reason, httpStatus)`. `validators` is a small record (`etag`, `lastModified`). |
| `feed.health` | `FeedHealthUpdater` | Records health with **atomic SQL** through `JdbcClient`, so a manual refresh and a poll can't lose updates. It has three methods, `recordSuccess`, `recordNotModified` and `recordFailure`; each runs in its own short transaction, separate from the persist transaction, so a failed ingest is still recorded. A missing feed (deleted mid-poll) updates 0 rows and is ignored. |
| `feed.health` | `FeedHealthGauges` | Holds the `MultiGauge`s from decision 8, with value functions. `refresh()` re-reads the feed rows and re-registers them with `overwrite = true`. It is called at startup (`ApplicationReadyEvent`) and by the events in decision 8. |
| `feed.fetch` | `RetryTelemetryListener` | `@EventListener(MethodRetryEvent)` for `RetryingFeedFetcher.fetch` only. It reads `sourceKey` from the invocation arguments and increments `argus.fetch.retry{source}`. |
| `feed.poll` | `FeedPoller` | `poll(trigger)` is annotated `@ConcurrencyLimit(value = 1, policy = REJECT)`. It reads the enabled feeds fresh from the database, fans out on a virtual-thread `SimpleAsyncTaskExecutor` (`concurrencyLimit` = `argus.poll.concurrency`, `ContextPropagatingTaskDecorator`, a `taskTerminationTimeout` for shutdown) and waits for all feeds. It aggregates the `AggregatePollReport`. Each feed is isolated: one feed's exception becomes a FAILED report and never aborts the poll. |
| `feed.poll` | `FeedPollingScheduler` | `@Scheduled(cron = "${argus.poll.cron}")` calls `FeedPoller.poll(SCHEDULED)`. It records `argus.scheduled.job{scheduled_job=poll, outcome}`: `success`, `skipped` (on `InvocationRejectedException`) or `error` (any other exception, logged and swallowed so later runs continue). |
| `feed.poll` | `PollingTelemetry` | Owns the root `argus.poll` Observation and the `argus.poll.last.success` gauge (epoch seconds of the last completed poll). It puts `pollId` in MDC on the orchestrator thread; the decorator copies it to the workers. It writes one INFO summary line per poll. |
| `feed.api` | `FeedController` / `FeedService` | Add `GET /feeds`, `POST /feeds/refresh`, `PATCH /feeds/{id}` and `DELETE /feeds/{id}`. `GET /feeds/{id}` gains health. The create path implements decision 1's `Timer.Sample` tagging. |
| `ingest` | `FeedIngestService` | Handles the three fetch results: `Fetched` goes to persist plus `recordSuccess`, `NotModified` goes to `recordNotModified` only (no parse, no persist), and `Failed` goes to `recordFailure`. `IngestReport.Outcome` gains `NOT_MODIFIED`, and `argus.fetch` gains `outcome=not_modified`. One INFO summary line per feed, as today. |
| `observability` | `MetricNames` / `MetricCatalogue` | The 7 new meters below. |
| `config` | `ArgusConfiguration` | Registers `PollProperties`, adds `@EnableScheduling` and `@EnableResilientMethods`, and registers the `Slf4jThreadLocalAccessor` (if Boot doesn't already). |

### Liquibase changeset `06`

The file is `src/main/resources/db/changelog/changes/06-add-feed-health.yaml`, included from the master changelog. It has a rollback. Changesets 01–05 stay untouched. It adds:
- `feed.etag text`
- `feed.last_modified text`
- `feed.last_fetched_at timestamptz`
- `feed.last_success_at timestamptz`
- `feed.last_error text`
- `feed.consecutive_failures integer NOT NULL DEFAULT 0`
- the index `ix_feed_enabled` on `feed(enabled)`

### Meters (catalogue entries; all tag keys already exist in `MetricNames.Tags`)

| Name | Kind | Tags | Prometheus series |
|---|---|---|---|
| `argus.poll` | timer (Observation) | `trigger` (`scheduled`/`manual`), `outcome` (`completed`/`failed`) | `argus_poll_seconds_*` |
| `argus.fetch.retry` | counter | `source` | `argus_fetch_retry_total` |
| `argus.scheduled.job` | counter | `scheduled_job` (`poll`), `outcome` (`success`/`skipped`/`error`) | `argus_scheduled_job_total` |
| `argus.feed.state` | gauge | `feed_id`, `state` (`healthy`/`failing`/`disabled`) | `argus_feed_state` |
| `argus.feed.consecutive.failures` | gauge | `feed_id` | `argus_feed_consecutive_failures` |
| `argus.feed.since.last.success` | gauge, base unit `seconds` | `feed_id` | `argus_feed_since_last_success_seconds` |
| `argus.poll.last.success` | gauge, base unit `seconds` | none | `argus_poll_last_success_seconds` |

`argus.fetch` keeps its existing tags and gains `outcome=not_modified`. No meter name may end in its base unit, because `MeterSpec` appends the unit.

### Dashboard additions (`grafana/dashboards/argus-observability.json`)

Every new query follows the phase 2 conventions:
- `$__rate_interval`
- `job="argus"`
- `source=~"$source"` where the meter has a `source` tag
- a description on every panel
- `${DS_PROMETHEUS}`
- NaN-safe at zero traffic: `clamp_min(denominator, 0.000000001)`, `or vector(0)` on counts and stats, and `>= 0` after `histogram_quantile`

The panels:
- **Overview stats:**
  - **Last poll age:** `time() - max(argus_poll_last_success_seconds{job="argus"})`, with noValue "never".
  - **Feeds enabled / failing:**
    - enabled: `count(argus_feed_state{job="argus",state=~"healthy|failing"}) or vector(0)`
    - failing: `count(argus_feed_state{job="argus",state="failing"}) or vector(0)`
  - **New articles 24h:** `sum(increase(argus_ingest_entries_total{job="argus",decision="inserted"}[24h])) or vector(0)`.
- **Polling row:**
  - Poll duration:
    - p95 by trigger: `histogram_quantile(0.95, sum by (le, trigger) (rate(argus_poll_seconds_bucket{job="argus"}[$__rate_interval]))) >= 0`
    - max: `max by (trigger) (argus_poll_seconds_max{job="argus"})`
  - Poll outcomes: `sum by (trigger, outcome) (rate(argus_poll_seconds_count{job="argus"}[$__rate_interval]))`.
  - Retries by source: `sum by (source) (rate(argus_fetch_retry_total{job="argus",source=~"$source"}[$__rate_interval]))`.
  - 304 ratio: `sum(rate(argus_fetch_seconds_count{job="argus",source=~"$source",outcome="not_modified"}[$__rate_interval])) / clamp_min(sum(rate(argus_fetch_seconds_count{job="argus",source=~"$source"}[$__rate_interval])), 0.000000001)`.
- **Feed health row:**
  - **Feed health table.** One row per `feed_id`, joining state, consecutive failures and since-last-success, with threshold colours.
    - Its data link goes to Loki: `{service="argus"} | json | feedId="${__data.fields.feed_id}"`.
    - The logs are JSON, so a `feedId="…"` line filter would never match.
  - **Stalest feeds:** `topk(5, argus_feed_since_last_success_seconds{job="argus"})`.
- **Scheduled jobs row:**
  - Job runs by outcome: `sum by (scheduled_job, outcome) (increase(argus_scheduled_job_total{job="argus"}[$__rate_interval]))`.
- **`ArgusDashboardTest`:**
  - Row order becomes: `Overview`, `Polling`, `Feed health`, `Ingestion pipeline`, `Data quality`, `Scheduled jobs`, `API & HTTP`, `JVM & runtime`, `PostgreSQL & HikariCP`, `Container`, `Traces`, `Logs`.
  - Catalogue coverage stays on.
- **Live dashboard.** The owner imports the dashboard after deploying; this phase never touches it.

## Work packages and execution model

- **Git.** Branch `feat/argus-phase-3` from `main`.
  - Local commits only, with conventional messages and **no trailers**.
  - Stage explicit paths only.
  - Never rewrite history; no push until the owner says so.
- **Quality.** TDD, 0 Sonar issues, and coverage kept at about 99.7% with no new exclusions.
- **Maven.** JDK 21, run with a Central-only settings file (`-s <central-only settings.xml>`).

### WP-1: schema, properties, conditional GET and retries
- Changeset `06`; `Feed` entity fields; `PollProperties`; `FetchProperties.retry`; `ArgusConfiguration` changes.
- `FetchResult.NotModified` and validators; conditional headers and 304 handling in `FeedFetcher`; `RetryableFetchException`; `RetryingFeedFetcher`.
- Tests:
  - `SchemaIT`: changeset 06 applies, and `ddl-auto=validate` passes.
  - `FeedFetcherTest` (stub server):
    - a 200 captures the validators;
    - a 304 gives `NotModified`;
    - the conditional headers are sent, along with the User-Agent.
  - `RetryingFeedFetcherTest`, a Spring context test against the stub:
    - a 503 is retried 2 times and then gives `Failed(HTTP_STATUS, 503)`, with exactly 3 requests;
    - a 404 is never retried (1 request);
    - a timeout is retried;
    - redirects aren't retried per hop;
    - the overall `timeout` budget is respected.

### WP-2: health, 304 pipeline, source tags, retry telemetry and gauges
- `FeedHealthUpdater`, with atomic SQL.
- The `FeedIngestService` / `FeedLoader` three-case dispatch and `IngestReport.NOT_MODIFIED`.
- The decision-1 create-path `Timer.Sample`, with `source=unknown` on failure.
- `RetryTelemetryListener`.
- `FeedHealthGauges`, with value functions, the refresh triggers and the startup refresh.
- Tests:
  - A 304 leaves articles untouched and updates `last_fetched_at`.
  - A failure increments `consecutive_failures` and records `last_error` as a reason code, with no URL in it. Success resets the count.
  - Concurrent `recordFailure` calls lose no increments.
  - A refresh and a create both tag `argus.fetch` with the stored key, and a failed create is tagged `unknown`. This needs an IT with a site link on a different domain from the feed host.
  - Retries are counted under the stored source.
  - The gauges show the live since-last-success, the gauges exist right after startup, and deleting a feed removes its series.

### WP-3: poller, scheduler, shutdown and API
- `FeedPoller` (`@ConcurrencyLimit` REJECT; virtual-thread executor with the concurrency limit, `ContextPropagatingTaskDecorator`, `taskTerminationTimeout`; per-feed isolation).
- `FeedPollingScheduler` and `PollingTelemetry`.
- 409 mapping in `GlobalExceptionHandler`.
- The endpoints from decisions 2–5.
- Tests:
  - **Fetch concurrency:** with the limit at 2 and 6 slow stub feeds, the stub's peak in-flight count is never above 2.
  - **Overlap:** a refresh-all during a running poll gives 409 `POLL_IN_PROGRESS`, and a scheduled run during a poll records `outcome=skipped`.
  - **A scheduler run that throws** records `outcome=error`, and the next invocation still runs.
  - **Disabled feeds** are skipped. A feed added or disabled through the API takes effect on the next poll, because the feed list is read fresh.
  - **Isolation:** one failing feed doesn't stop the others.
  - **Serving during failures:** `GET /articles` keeps serving stored data while every feed fails.
  - **Trace shape:** one trace has a root `argus.poll` span with an `argus.ingest` child per feed. Worker log lines carry `pollId`, `feedId` and `sourceId`. There is one poll summary line and one line per feed.
  - **Graceful shutdown:** closing the context during a poll lets it finish, or stop, within the termination timeout. The executor ends with no lingering threads.
  - **API:**
    - `GET /feeds` and `/feeds/{id}` show health, and their paging validation gives 400;
    - URLs are redacted without the key and full with it;
    - `PATCH` with `null` or an empty body gives 400;
    - `DELETE` removes the orphan articles only, and keeps articles still linked to another feed;
    - a feed deleted mid-poll fails cleanly, with no health write;
    - every write needs the key.

### WP-4: catalogue, dashboard and docs
- Register the 7 meters in `MetricNames` and `MetricCatalogue`.
- Add the dashboard panels and update `ArgusDashboardTest`.
- `PollMetricsIT`: every new meter and tag value in the registry and the Prometheus scrape. No `feed_id`/`source` tag on any meter except as specified.
- `README.md`:
  - new endpoints;
  - the `ARGUS_POLL_*` and `ARGUS_FETCH_RETRY_*` variables;
  - the redaction rule for `GET /feeds`;
  - a note that `POST /feeds/refresh` is synchronous.
- `observability/README.md`: what the new rows answer, plus Phase 3 Gate B checks:
  - the poll runs on schedule, and the last-poll-age stat stays under 15 min;
  - a deliberately broken feed shows as failing in the feed-health table, with the right failure count;
  - a refresh-all during a poll returns 409;
  - the 304 ratio rises on the second poll.

## Verification

```bash
cd /Users/joshua/Documents/digital-dashboard/news-service
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -B -s <central-only settings.xml> clean verify
grep -hE 'Tests run:' target/surefire-reports/*.txt target/failsafe-reports/*.txt | awk '{r+=$3; f+=$5; e+=$7; s+=$9} END {print "run",r,"fail",f,"err",e,"skip",s}'
```

Done means:
- Every Phase 3 acceptance criterion in `plans/argus.md` is covered by a named test. The list above maps them.
- `clean verify` is green with 0 skipped tests.
- JaCoCo coverage is at about 99.7%, with no new exclusions.
- The owner's branch scan shows 0 Sonar issues.
- Commits are on `feat/argus-phase-3`, and `git status` is clean apart from the owner's untracked files.
