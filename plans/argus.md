# Plan: Project Argus — RSS News Aggregator on Spring Boot 4

> Source PRD: `docs/prd-argus.md` (user stories 1–111)

## Architectural decisions

Durable decisions that apply across all phases:

- **Identity**: the project is **Argus**:
  - Artifact, application name, Swarm service, database, Sonar key, OTel `service.name`, Prometheus and Promtail jobs: `argus`
  - Package root: `com.j11a.argus`
  - Image: `argus` (no repository path)
  - Metric prefix: `argus.`
  - Dashboard: uid `argus-observability`

  The public API stays at `/news/v2`. The Swarm service carries a `news-service` network alias until the proxy's target is changed to `argus`.
- **Platform**: Spring Boot 4.x on Java 21, PostgreSQL, schema managed by Liquibase. Single instance. Built-in Spring features are preferred over hand-rolled code:
  - `@Scheduled` on virtual threads
  - Framework 7 `@Retryable` and `@ConcurrencyLimit`
  - `RestClient`
  - Spring Data JPA (with Specifications)
  - Spring Security
  - `ProblemDetail`
  - Application events with after-commit listeners
  - `SseEmitter`
  - `@ConfigurationProperties` with `@Validated`
  - Actuator, Micrometer and the Observation API
  - Spring Boot's OpenTelemetry support
  - Spring Boot structured logging
- **Routes**: everything lives under `/news/v2`, which the existing gateway `/news/**` rule already routes.
  - Read endpoints:
    - `/articles`, `/articles/{id}`
    - `/headlines`
    - `/stories/{id}`
    - `/breaking`
    - `/sources`, `/sources/{id}`
    - `/feeds`, `/feeds/{id}`, `/feeds/export`
    - `/watches`, `/watches/{id}`, `/watches/{id}/matches`
    - `/stream`
  - Write endpoints:
    - `PATCH /sources/{id}`, `POST /sources/{id}/merge`
    - `POST /feeds`, `PUT /feeds/{id}`, `PATCH /feeds/{id}`, `DELETE /feeds/{id}`
    - `/feeds/{id}/refresh`, `/feeds/refresh`, `/feeds/discover`, `/feeds/import`
    - watch create, update and delete, `/watches/{id}/seen`
  - API docs are served under `/news/v2/api-docs` and `/news/v2/swagger-ui`, so they're reachable through the gateway.
  - Actuator stays at `/actuator/*` on the service port. It's reachable on the overlay network for Prometheus and health checks, but **not** routed by the gateway.
- **Auth**: reads are open. Writes require the `X-Admin-Key` header. A stateless Spring Security filter chain checks it against a required environment-configured key, using constant-time comparison; the key is never logged. A missing or wrong key gives 401 `ADMIN_KEY_REQUIRED`. Actuator health and Prometheus endpoints are permitted without the key.
- **Response format**:
  - Plain resource bodies, no envelope.
  - Paged responses use Spring Data `PagedModel` (items plus page metadata). Default page size 20, maximum 100.
  - camelCase fields, ISO-8601 UTC timestamps; the JVM and database session run in UTC.
  - Errors are `application/problem+json` with a stable `code` extension. Codes: `VALIDATION_FAILED`, `ADMIN_KEY_REQUIRED`, `FEED_NOT_FOUND`, `FEED_URL_CONFLICT` (with `existingFeedId`), `FEED_INVALID`, `SOURCE_NOT_FOUND`, `SOURCE_MERGE_INVALID`, `WATCH_NOT_FOUND`, `WATCH_NAME_CONFLICT`, `POLL_IN_PROGRESS`, `OPML_INVALID`.
- **Schema** (final shape; each phase adds what it needs through Liquibase changesets):
  - `source`: an outlet. Unique key = the registrable domain. Holds name, homepage and country.
  - `feed`: belongs to a source. The URL is cleaned and unique, and there's an optional unique self URL. Holds topic, enabled flag, ETag and Last-Modified validators, and health fields.
  - `article`: belongs to a source and is unique on (source, GUID key). There's a non-unique index on (source, link key). Holds a content hash, published, upstream-updated, effective and first-seen (fetched) times, a story reference, and a generated full-text search column.
  - `article_feed`: an article–feed link recording every feed of the source the article appeared in.
  - `story`: holds normalised title tokens, first and last seen times, a distinct-source count, and breaking-since.
  - `watch`: holds include and exclude terms, enabled, and last-seen-at.
  - `watch_match`: primary key (watch, article).
  - `purged_article`: source, GUID key, link key, content hash, effective time and purge time.
  - Topic belongs to the feed; country belongs to the source.
- **Deduplication model**:
  - Articles are unique **per source**.
  - Matching order: GUID key first, then a usable link key. Homepage links and links shared within a batch are never used for matching.
  - Concurrency: a per-source Postgres advisory transaction lock, plus `INSERT … ON CONFLICT` as the final safeguard.
  - Cross-outlet duplicates are grouped into stories, never merged into one article.
- **Live events**: `headline`, `breaking` and `watch-match`, published only after commit.
- **Third-party boundaries**:
  - Outbound HTTP to feed and site URLs only, through one configured `RestClient`: http/https only, timeouts, size cap, limited redirects, conditional GET, bounded concurrency.
  - ROME for parsing, jsoup for HTML.
  - A public-suffix-aware library for registrable domains.
  - No paid APIs.
- **Observability contract** (the Grafana stack: Prometheus + Loki + Tempo, datasource UIDs `prometheus`, `loki`, `tempo`; Hymenaios is the prior art):
  - **Metrics**:
    - Exposed at `/actuator/prometheus`. Every custom meter is named under `argus.` (Prometheus: `argus_*`) and declared in a single metric-names catalogue in the code.
    - Percentile histograms are enabled for `http.server.requests`, `http.client.requests` and every custom timer.
    - Allowed tags: `source`, `feed`, `feed_id`, `watch`, `trigger`, `outcome`, `reason`, `decision`, `type`, `scheduled_job`, `kind`, `state` (not `job`, which collides with Prometheus's target label). Never article ids, URLs, GUIDs, search text or watch terms.
    - Per-feed gauges use multi-gauges that are re-registered on each refresh, so deleted or renamed feeds leave no stale series.
  - **Traces**:
    - OTLP/HTTP to Tempo. Argus takes its own validated base URL (`argus.telemetry.otlp-base-url`, default `http://tempo:4318`) and appends `/v1/traces` itself, because Boot's endpoint property is used verbatim. Environment-variable mapping is disabled.
    - `service.name=argus`. Sampling is configurable, default 1.0. OTLP metric export is disabled.
    - Spans: a root span per poll; per-feed ingest with fetch, parse, resolve and persist children; HTTP server and `RestClient` spans; backfill; retention; merge.
    - Feed and source ids are span attributes.
  - **Logs**:
    - Structured console logs in the Logstash JSON format (`@timestamp`, `level`, `logger_name`, `message`, `traceId`, `spanId`, plus MDC keys `pollId`, `feedId`, `sourceId`).
    - Plain text in the tracked `dev` profile. `local` is reserved for the git-ignored personal override.
    - `traceId` is never a Loki label.
  - **Dashboard**:
    - One versioned JSON (title "Argus — Observability", uid `argus-observability`, folder "Artemis", tags `argus`, `artemis`, `observability`), with a Grafana provisioning file.
    - Datasources come from the template variables `DS_PROMETHEUS`, `DS_LOKI` and `DS_TEMPO`, never hardcoded UIDs.
    - A JUnit validator, modelled on Hymenaios' rules, checks:
      - Valid JSON
      - Required variables
      - Unique panel ids
      - No literal datasource UIDs
      - No empty targets
      - Every PromQL metric is in the allow-list

      Allowed series come from the Java metric catalogue itself plus an explicit list of framework and exporter series, so there is no hand-copied allow-list. The validator runs in `mvn verify`, locally and in CI.
  - **Stack configuration**:
    - A Prometheus job `argus`: `dockerswarm_sd_configs` task discovery through `tcp://docker-proxy:2375`, matched by the service-name suffix `(?:.+_)?argus`, running tasks only, keeping only the `grafana-overlay-network` address (Swarm yields one target per network), port 8080, path `/actuator/prometheus`, with `service`, `task_id` and `node` labels.
    - A Promtail job for `argus` containers with a JSON pipeline that extracts `level`, `logger_name` and `traceId`, extracts and uses `@timestamp`, and promotes only `level` to a label.
    - Both are kept in the repo with an observability README. Applying them to the docker/grafana stack is out of scope.
  - **Every phase ships its telemetry**: the meters, spans and log context for what it adds; the matching dashboard panels; and allow-list entries. Observability is not left to the end.
- **Testing**:
  - Pure modules get JUnit tests with no Spring.
  - The fetcher is tested against a stub HTTP server.
  - Persistence and ingestion are tested against Testcontainers Postgres via `@ServiceConnection`, CI-safe: no Ryuk, no socket mount, no reuse in CI, and the host and port Testcontainers reports.
  - Controllers get Web MVC slice tests.
  - Each phase adds metric assertions against the meter registry.
  - JaCoCo ≥ 80%. Everything, including the dashboard validator, runs in one `mvn verify`.

## Prerequisites outside this repository

These are not built by this plan, but the plan depends on them. Each is listed with the phase that first needs it.

- **Phase 1:**
  - **`jenkins-shared-lib`: optional image-path prefix.** Image-path construction moves into one shared helper used by all six image-building pipelines. An omitted `dockerRepoPath` keeps the `common` default; an explicitly empty one publishes `<artifactId>:<tag>` with no prefix. The library's tests cover the omitted, empty and set cases. Every existing consumer's image name is unchanged. This is merged before Argus's first pipeline run, as a separate change in that repository.
  - A PostgreSQL database `argus` and user for Argus on the shared Postgres. The `unaccent` extension must be available: either the user may create it, or a DBA pre-creates it.
  - Swarm secrets or environment variables for the database credentials and admin key.
  - A Swarm service `argus` in the `artemis-dashboard` stack (replacing the commented-out news-service entry). It needs a `news-service` network alias on `artemis-dashboard-network` until the proxy route's target is changed to `http://argus:8080`, attached to `artemis-dashboard-network`, `postgres-overlay-network` and `grafana-overlay-network`, relying on the image's exec-form Java health probe, which calls `/actuator/health/liveness`. The stack must not override it with a wget or curl check, because the image has no shell. This is needed so the gateway, the database, Prometheus and Tempo can reach it.
- **Phase 1, observability:**
  - The Prometheus `argus` job and the Promtail `argus` job, applied from this repo's observability config. The live Promtail `docker` job's drop regex becomes `.*(hymenaois|argus).*`, so logs aren't shipped twice. The dashboard gets its own Grafana provider and directory.
  - A PgBouncer `argus` database entry (transaction pooling), and confirmation that the snapshot registry accepts root-level image names.
  - The Loki datasource configured with a `traceId` derived field linking to Tempo, and Tempo with `tracesToLogsV2` to Loki. Both are already in place for Hymenaios; verify them.
- **Phase 13:** the dashboard provisioned into Grafana, or imported. During development it is pushed through the Grafana MCP to check that it renders.

## Definition of done (applies to every phase)

- [ ] Every acceptance criterion is covered by an automated test; unit tests for pure logic, Testcontainers for persistence.
- [ ] `mvn verify` is green: unit + integration tests, JaCoCo ≥ 80% on new code, and the dashboard validator.
- [ ] Schema changes are new Liquibase changesets; applied changesets are never edited.
- [ ] New endpoints carry OpenAPI annotations, including their problem responses.
- [ ] New behaviour emits its catalogued metrics, spans and MDC log context. The matching dashboard panels and allow-list entries are added, and a meter-registry test asserts the new meters and their tags.
- [ ] No secrets, URLs-as-tags, or unbounded label values. Errors are logged with context, never swallowed.
- [ ] The shared Jenkins pipeline is green, including hadolint, Sonar quality gate, dependency-check, Trivy and the multi-arch push.

---

## Phase 1: Boot 4 platform and telemetry tracer

**User stories**: 83, 86, 87, 88, 89, 91, 93, 94, 98, 99, 100, 101, 102, 103, 104, 105, 106, 111

### What to build

Move the service onto the new platform with no features yet, observable from the first commit:
- Rename the project to Argus: the Maven artifact and application name, the package root, the Sonar key and the image name (see Identity).
- Upgrade to Spring Boot 4 on Java 21 with current dependencies.
- Delete all NewsCatcher, RestTemplate, v1 and `ApiResponse` code and configuration.
- Connect to PostgreSQL with an empty Liquibase baseline that enables the `unaccent` extension.
- Add environment-driven, validated configuration that fails fast at startup (database settings, admin key, OTLP endpoint).
- Set up the problem-details error handling, the Testcontainers integration-test base, and Surefire + Failsafe + JaCoCo + the dashboard validator, so one `verify` runs everything.
- Replace the Jenkinsfile with the shared-library `standardSpringSnapshotPipeline`, and move to a `-SNAPSHOT` version.
- Rewrite the Dockerfile on the hardened multi-arch Corretto 21 base, with a non-root user, an exec-form entrypoint and a liveness health check.

Telemetry foundation:
- Expose the Prometheus endpoint, with percentile histograms for HTTP server and client requests.
- Export traces to Tempo over OTLP.
- Log in Logstash JSON format with `traceId`/`spanId`.
- Create the empty metric-names catalogue.
- Add the observability README with the Prometheus and Promtail jobs to apply.
- Create the dashboard skeleton (variables `DS_PROMETHEUS`, `DS_LOKI`, `DS_TEMPO`, `source`, `feed`, `level`, `search`; 6h range; 30s refresh) and the JUnit dashboard validator. The skeleton's rows:
  - **Overview** (first stats): target up, uptime, 5xx ratio, p95 latency excluding `/news/v2/stream`
  - **API & HTTP**: request rate by route, latency quantiles, status mix
  - **JVM & runtime**: heap used vs max, GC pause, live threads, CPU
  - **PostgreSQL & HikariCP**: connections active/idle/pending, acquisition p95, `pg_up`, the news database's size and commits from postgres-exporter
  - **Container**: cAdvisor CPU and memory for the `argus` task
  - **Traces**: a TraceQL table of slow or errored traces for `service.name="argus"`
  - **Logs**: volume by level, and a live stream filtered by `level` and `search`

### Acceptance criteria

- [ ] The app starts against Postgres, Liquibase applies cleanly to an empty database, and Actuator liveness (the app only) and readiness (the app and the database) report UP.
- [ ] Starting with a missing admin key, one that is too short, or no database settings fails with a clear message.
- [ ] An unknown route returns `application/problem+json` with a `code`.
- [ ] No NewsCatcher, RestTemplate, v1 or envelope code or configuration remains, and the local NewsCatcher key is removed.
- [ ] Every identifier in the Identity decision reads `argus`: the artifact, package root, application name, Sonar key, the image `argus` (no repository path), and `service.name`. The gateway's `/news/**` route reaches the service through the `news-service` alias.
- [ ] `/actuator/prometheus` serves `http_server_requests_seconds_bucket`, JVM and HikariCP series without an admin key. `/actuator` isn't reachable through `/news/**`.
- [ ] A request produces a trace in Tempo with `service.name=argus`, and the request's log line in Loki carries the same `traceId` and links to it.
- [ ] Log output is valid JSON with `level`, `logger_name`, `traceId` and `@timestamp` (asserted by a test).
- [ ] The dashboard validator passes on the skeleton and fails when a panel references an unpublished metric (asserted by a test of the validator).
- [ ] Gate A: on a local throwaway stack running the built image, every skeleton panel renders without query errors, and metrics, traces and logs link up. Gate B (the real stack after deployment) is a checklist in the observability README.
- [ ] A Testcontainers smoke test passes under `mvn verify`, and the JaCoCo XML report lands where Sonar expects it.
- [ ] The Jenkinsfile only calls `standardSpringSnapshotPipeline` with `dockerRepoPath: ''`, and the registry receives `argus:<version>`. The pipeline passes the hadolint, verify, Sonar, dependency-check, Trivy and multi-arch push stages.

---

## Phase 2: First feed end-to-end

**User stories**: 8, 9, 10, 11, 38, 39, 40, 52, 60, 61, 92, 95

### What to build

The thinnest complete path from an RSS URL to articles over HTTP:
- An admin adds a feed with `POST /feeds` (admin key required). The feed is fetched and parsed once to validate it; invalid feeds return 422 `FEED_INVALID`.
- `POST /feeds/{id}/refresh` ingests it, and `GET /articles` returns paged articles, newest first.
- Parsing produces a plain-text excerpt (about 500 characters, cut at a word boundary), author, link, publish time and image URL across RSS and Atom.
- Deduplication at this stage is a simple unique key on the GUID (or link), so a second refresh adds nothing. Phase 4 replaces it with the full per-source model.
- The minimum `source` and `feed` tables are introduced, with sources resolved by registrable domain, so the schema already has its final shape.

Telemetry:
- Meters:
  - The per-feed fetch timer (`source`, `outcome`, `reason`)
  - The per-feed ingest timer
  - The downloaded-bytes distribution
  - The entry decision counter (`inserted`/`unchanged` for now)
  - The parse data-quality counter (entries missing a date, GUID, image or author)
- A feed-ingest span with fetch, parse and persist children, the outbound `RestClient` span, and `feedId`/`sourceId` in MDC and as span attributes.
- Dashboard: a new **Ingestion pipeline** row (fetch outcomes rate, fetch p95 by source, bytes per fetch, entry decisions) and a **Data quality** row (missing fields by kind, as rate and share).

### Acceptance criteria

- [ ] Writes without the right admin key get 401 `ADMIN_KEY_REQUIRED`. Reads need no key.
- [ ] Adding a valid feed returns 201. A non-feed URL returns 422. Validation errors return 400 with field details.
- [ ] Refreshing a feed twice stores each entry exactly once.
- [ ] `GET /articles` returns the paged shape. Page size above 100 or an invalid parameter returns 400.
- [ ] Parser unit tests cover RSS 2.0 with media thumbnails, WordPress `content:encoded`, Atom 1.0, RSS 1.0, HTML-heavy descriptions, image priority, missing GUID or date, a wrong encoding declaration, and malformed XML (typed failure).
- [ ] A refresh produces one trace containing the fetch, parse and persist spans. Its log lines carry `feedId` and `sourceId`.
- [ ] A meter-registry test asserts the fetch, ingest, bytes, decision and data-quality meters with their tags after a stubbed ingest.

---

## Phase 3: Scheduled, resilient polling

**User stories**: 12, 13, 49, 50, 51, 53, 54, 62, 63, 64, 65, 66, 67, 107, 110

### What to build

The feed list now drives automatic ingestion:
- A configurable cron (default every 15 minutes) polls all enabled feeds in parallel on virtual threads, with a configurable maximum number of concurrent fetches (default 8), reading the feed list fresh from the database each run.
- Fetches use conditional GET with stored validators, timeouts, a size cap, a redirect limit and http/https only.
- I/O errors and 5xx responses are retried with backoff; 4xx responses are not.
- Each feed records its health: last fetched, last success, last error, consecutive failures.
- `POST /feeds/refresh` runs a poll on demand and returns 409 `POLL_IN_PROGRESS` if one is already running.
- Feed list and detail endpoints show health. `PATCH` enables or disables a feed, and `DELETE` removes it.
- Graceful shutdown lets an in-flight poll finish, or stops it cleanly within the shutdown timeout.
- Stored articles are still served when feeds or the internet are down.

Telemetry:
- Meters:
  - The poll timer (`trigger`, `outcome`)
  - The fetch-retry counter, fed by Spring's `MethodRetryEvent`
  - The scheduled-job run counter (`scheduled_job=poll`, `outcome`)
  - Feed gauges: by state, consecutive failures per feed, and seconds since last success per feed
  - The last-successful-poll timestamp
- A root span per poll, with per-feed child spans. `pollId` in MDC.
- One summary log line per poll and one per feed, with outcome and counts.
- Dashboard:
  - **Overview** stats: last successful poll age, feeds enabled/failing, new articles in 24h.
  - New **Polling** row: poll duration p95/max by trigger, poll outcomes, retries by source, 304 ratio (cache efficiency).
  - New **Feed health** row:
    - A table per feed with state, consecutive failures and last-success age, colour-thresholded, with a link to the feed's logs in Loki.
    - A "stalest feeds" top-k panel.
  - A **Scheduled jobs** row of silent-failure stats (job runs by outcome).

### Acceptance criteria

- [ ] Stub-server fetcher tests cover: 200 with validators, 304, 404 (not retried), 503 (retried, then failed), timeout, oversized body, redirect followed, redirect limit, non-http scheme, and the headers sent.
- [ ] A 304 leaves articles untouched and updates last-fetched-at.
- [ ] A failing feed increments its failure count and records the error, while other feeds still ingest. Success resets the count.
- [ ] A refresh-all requested during a running poll returns 409. Disabled feeds are skipped. Concurrent fetches never exceed the configured limit.
- [ ] A feed added or disabled through the API takes effect on the next poll without a restart.
- [ ] `GET /articles` keeps serving stored data while every feed fails.
- [ ] A poll that throws increments the scheduled-job counter with `outcome=error` and doesn't stop later polls.
- [ ] Deleting a feed removes its per-feed gauge series on the next refresh.
- [ ] The feed-health table shows a deliberately broken feed as failing, with the right failure count.

---

## Phase 4: Sources and full article deduplication

**User stories**: 42, 43, 44, 45, 59, 68, 69, 70, 71, 72, 73, 74, 75, 77, 108

### What to build

Replace the simple dedup with the per-source model:
- New feeds are grouped under a source by registrable domain. An explicit `sourceId` overrides this. Sources can be listed, and renamed or given a country with `PATCH`.
- The full link cleaner: a tracking-parameter list, remaining parameters sorted, and scheme, `www.`, `m.`, AMP, fragment and trailing-slash variants folded.
- The pure entry dedup resolver handles:
  - GUID-then-link matching, guarded against homepage links and links shared within a batch
  - GUID replacement when the link fallback matches
  - Collapsing repeats within one download
  - Linking an article to another feed of the same source only
  - Edit detection by content hash or upstream update time
  - Effective-time fallbacks (missing date → fetch time; future date → capped)
- Ingestion takes a per-source advisory lock and inserts with `ON CONFLICT`.
- Articles keep links to every feed they appeared in. Topic filters match any of those feeds, and country filters use the source.
- Article responses include the source summary and the list of feeds.
- A once-only Liquibase seed of the verified sources and feeds is added, with a context so tests can skip it.

Telemetry:
- Meters:
  - The entry decision counter gains `updated`, `linked` and `skipped` (with `reason=batch_duplicate`)
  - The link-fallback counter (`guid_replaced`, `guarded_homepage`, `guarded_shared`)
  - The advisory-lock wait timer per source
- A lock-wait span inside each feed ingest.
- Dashboard: a new **Deduplication** row:
  - Decisions as a stacked rate
  - Skip reasons
  - Link-fallback outcomes
  - Lock wait p95 by source
  - Duplicate pressure: linked plus unchanged as a share of entries seen

### Acceptance criteria

- [ ] Unit tests cover every link-cleaner rule (applying it twice gives the same result) and every source-resolver case, including multi-part public suffixes.
- [ ] Unit tests cover one rule each for the entry dedup resolver.
- [ ] The same article in two feeds of one source is stored once, with two feed links, and appears under both topics.
- [ ] Two feeds of one source ingested concurrently with overlapping items produce no duplicates. A forced conflicting insert is absorbed.
- [ ] A changed GUID with the same link updates the existing article. A homepage-link feed keeps its distinct items separate.
- [ ] An edited upstream article is updated in place, with no new article.
- [ ] With the seed context on, a fresh database starts with the seeded sources and feeds. A seeded feed deleted through the API stays deleted after a restart.
- [ ] Meter-registry tests assert each decision, link-fallback outcome and lock-wait meter with its tags.

---

## Phase 5: Feed identity and source merge

**User stories**: 41, 46, 47, 48

### What to build

Prevent duplicate subscriptions, and make source corrections clean:
- Creating or updating a feed checks the URL as entered, the final URL after redirects, and the feed's self link against every existing feed's identity URLs. A conflict returns 409 `FEED_URL_CONFLICT` with `existingFeedId`.
- During polling, a 301 or 308 updates the stored URL. If that would collide with another feed, the feed is disabled and its last error says "duplicate of feed {id}".
- `POST /sources/{id}/merge` and changing a feed's `sourceId` both:
  - Move feeds and articles in one transaction holding both source locks.
  - Collapse duplicates to the oldest article, folding in feed links and watch matches.
  - Recalculate story source counts.
  - Delete the empty source.

  Merging a source into itself gives `SOURCE_MERGE_INVALID`.

Telemetry:
- Meters:
  - The redirect counter (`permanent_applied`, `permanent_conflict`)
  - The feed-identity-conflict counter (`via=entered|redirect|self_link`)
  - The source-merge timer and the collapsed-article counter
- A merge span. Each merge and each auto-disable writes an audit log line at INFO with its ids and counts.
- Dashboard: **Feed health** gains redirect and conflict panels. **Deduplication** gains merges and collapsed articles.

### Acceptance criteria

- [ ] URLs that differ only by scheme, `www.` or trailing slash, a URL that redirects to an existing feed, and a matching self link all give 409 naming the existing feed.
- [ ] A permanent redirect updates the stored URL. A temporary redirect doesn't.
- [ ] A permanent redirect onto another subscribed feed disables the feed with the "duplicate of" error.
- [ ] Merging two sources with overlapping articles leaves one copy per duplicate group, with no duplicate link or match rows, and removes the empty source.
- [ ] Moving one feed to another source behaves like a merge for that feed's articles.
- [ ] Meter-registry tests assert the redirect, conflict and merge meters.

---

## Phase 6: Stories and headlines

**User stories**: 1, 2, 3, 4, 5, 6, 7

### What to build

Group similar headlines across outlets:
- The pure story clusterer: normalised title tokens, Jaccard similarity with a configurable threshold, and a clustering window.
- Every inserted article is assigned to a story at ingest, and the story's distinct-source count is kept up to date.
- `GET /headlines` returns one entry per active story: the earliest article as the representative, the source count and the latest activity time. It filters by `since` (default 24h), `country`, `topic` and `limit`.
- `GET /stories/{id}` returns every outlet's article in the story.
- `GET /articles` gains `since`, `until`, `sourceId`, `feedId`, `country` and `topic` filters, and each article shows its story id and source count.

Telemetry:
- Meters:
  - The story assignment counter (`new`, `joined`)
  - The stored-article count gauge and the newest-article age gauge, refreshed on a short cache rather than per scrape
- A clustering span inside ingest.
- Dashboard:
  - **Overview** stats: stored articles, newest-article age.
  - A new **Stories & breaking** row: stories created vs joined, join ratio, and the latency of the `/headlines` and `/stories` routes.

### Acceptance criteria

- [ ] Clusterer unit tests: similar titles join a story and dissimilar ones don't; the threshold boundary; stories outside the window are excluded; stop-words and punctuation don't cause false joins.
- [ ] One story covered by three outlets appears once in `/headlines` with `sourceCount` 3. Two feeds of one source count once.
- [ ] Country, topic and time filters narrow both `/headlines` and `/articles` correctly.
- [ ] `/stories/{id}` lists every article in the story. An unknown id returns a 404 problem response.
- [ ] A meter-registry test asserts the story meters. The article gauges don't issue a query per scrape.

---

## Phase 7: Breaking news

**User stories**: 14, 15, 16, 17

### What to build

Each story's breaking state is re-checked whenever an article is inserted:
- A story becomes breaking once N distinct sources (default 3) have published into it within M minutes (default 60) of its first article.
- Breaking expires after a configurable duration (default 3 hours). An injected clock keeps this testable.
- `GET /breaking` lists current breaking stories, newest first. Headlines carry a `breaking` flag.

Telemetry:
- Meters: the breaking-detection counter and the active-breaking gauge.
- Each detection writes an INFO log line with the story id and source count.
- Dashboard: an **Overview** stat for active breaking stories, and a **Stories & breaking** panel for detections over time, with annotations from Loki detection log lines.

### Acceptance criteria

- [ ] Unit tests: the threshold reached by distinct sources; not reached by repeats from one source or two feeds of one source; a burst outside the window doesn't count; expiry.
- [ ] Integration: the third distinct source joining a story makes it appear in `/breaking`, and it disappears after expiry.
- [ ] The active-breaking gauge rises on detection and falls on expiry.

---

## Phase 8: Search

**User stories**: 33, 34, 35, 36, 37

### What to build

Postgres full-text search:
- A generated, weighted full-text column (title weighted above excerpt and categories, accents removed, English stemming) with a GIN index.
- `GET /articles?q=` accepts web-search syntax (quoted phrases, OR, `-term`) and ranks by relevance, with recency as the tie-break.
- It combines with all existing filters and paging.

Telemetry:
- Search requests are observed with a low-cardinality tag (`mode=search|list`), never the query text.
- The search span records result count and duration; the query text goes in the logs at DEBUG only.
- Dashboard: an **API & HTTP** panel for search vs list latency p95, plus the slow-search traces.

### Acceptance criteria

- [ ] Title matches rank above excerpt-only matches.
- [ ] "transfers" finds "transfer". "Odegaard" finds "Ødegaard".
- [ ] Phrase, OR and minus syntax behave as documented.
- [ ] `q` combined with country, topic, source and date filters returns only articles matching every filter.
- [ ] The search query uses the GIN index, checked against the query plan in a Testcontainers test.

---

## Phase 9: Watches

**User stories**: 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 32, 76

### What to build

Topic monitoring:
- Watch create, update, delete and enable/disable (admin key), with validated terms: at least one include term, at most 20 terms, each at most 100 characters.
- The pure watch matcher: case- and accent-insensitive, whole words and phrases, with excludes winning. It checks title, excerpt and categories.
- Matching happens at ingest for inserted articles, and again for edited articles, where matches are added and never removed.
- Create and update backfill over retained articles: an SQL pre-filter, then the matcher.
- `GET /watches` includes `unseenCount`. `GET /watches/{id}/matches` is paged, newest first, with story info and an `unseenOnly` option. `POST /watches/{id}/seen` resets the count.
- Duplicate watch names give `WATCH_NAME_CONFLICT`.

Telemetry:
- Meters:
  - The watch-match counter (`watch`, `trigger=ingest|edit|backfill`)
  - The backfill timer (`outcome`)
  - The unseen-matches gauge per watch, removed when the watch is deleted
- A backfill span with candidate and match counts.
- Dashboard: a new **Watches** row: matches by watch and trigger, unseen per watch, and backfill duration.

### Acceptance criteria

- [ ] Matcher unit tests: case and accent handling; "Arsenal" doesn't match "arsenals"; phrases; excludes override includes; category matches; blank input.
- [ ] Creating a watch fills it from existing articles. Editing its terms recomputes the matches.
- [ ] A newly ingested matching article creates a match. An edited article whose new headline matches gains a match.
- [ ] Unseen count and mark-as-seen work. Disabled watches don't match new articles.
- [ ] Meter-registry tests assert the watch meters. Deleting a watch removes its gauge series.

---

## Phase 10: Live stream

**User stories**: 18, 31, 96, 97

### What to build

`GET /stream` serves `text/event-stream`:
- `headline` events for newly inserted articles only, never edits.
- `breaking` events when a story becomes breaking.
- `watch-match` events for every new match, including matches from edited articles.
- Events are published from after-commit listeners, so rolled-back work never emits.
- Heartbeat comments at a configurable interval, a maximum number of connections, dead-connection cleanup, and best-effort `Last-Event-ID` reconnects.

Telemetry:
- Meters:
  - The active-connections gauge and the opened counter
  - The disconnect counter by reason: `completed`, `timeout`, `error`, `rejected`
  - The events-sent counter by type
  - The after-commit listener failure counter (`listener`)
- The `/stream` route stays out of the latency stats and panels, because its timer measures connection lifetime, not processing time.
- Dashboard: an **Overview** stat for live stream clients, and a new **Live stream** row with connections, disconnect reasons and events by type. **Scheduled jobs** gains listener failures.

### Acceptance criteria

- [ ] A connected client receives `headline`, `breaking` and `watch-match` events produced by an ingest.
- [ ] An ingest that rolls back produces no events. Edits produce no `headline` event.
- [ ] Heartbeats arrive on schedule. Connections beyond the limit are refused cleanly. Dropped clients are cleaned up.
- [ ] A listener that throws increments the listener-failure counter and doesn't break delivery to other clients.

---

## Phase 11: Retention and the recently-purged list

**User stories**: 78, 79, 80, 81

### What to build

A daily scheduled purge:
1. Records each article older than the configured retention (default 30 days, by effective time) in `purged_article`.
2. Deletes those articles, cascading to watch matches and feed links.
3. Deletes stories left with no articles.
4. Deletes purged-list rows older than the grace period (default 30 days).

Each step is isolated, so a failing step is recorded and doesn't silently abort the others.

Ingestion applies the retention cutoff to unmatched entries, and skips entries on the purged list unless they've been updated since the purge (a later effective time or a different content hash). Those are re-inserted once, with normal events.

Telemetry:
- Meters:
  - The retention run timer
  - The purged-rows counter by kind
  - The recently-purged list size gauge
  - The scheduled-job run counter (`scheduled_job=retention`, `outcome`)
  - The entry decision counter gains skip reasons `before_cutoff` and `recently_purged`, plus a `readmitted` reason on inserts
- A retention span with one child per step.
- Dashboard: a new **Retention** row: rows purged by kind, run duration, purged-list size, and re-admissions. **Scheduled jobs** gains the retention outcome.

### Acceptance criteria

- [ ] After a purge, re-ingesting the same unchanged entry, including one with no publish date, inserts nothing and emits nothing.
- [ ] Re-ingesting an entry updated after the purge inserts it once and emits its events.
- [ ] Recent data is untouched. Empty stories and expired purged-list rows are removed.
- [ ] A failure injected into one step is counted and logged, and the remaining steps still run.

---

## Phase 12: OPML import/export and feed discovery

**User stories**: 55, 56, 57, 58

### What to build

- `POST /feeds/discover` takes a site URL and returns candidate feeds from the page's `<link rel="alternate">` tags, or the URL itself if it is a feed. It doesn't subscribe to anything.
- `POST /feeds/import` takes OPML 1.0 or 2.0 (nested outlines flattened, category used as a topic hint; request size limited). Each feed goes through the normal create path, including the identity check. The response reports added, skipped (already exists) and invalid feeds.
- `GET /feeds/export` returns OPML 2.0.

Telemetry:
- Meters: an import counter by result (`added`, `skipped`, `invalid`) and a discovery counter by outcome.
- An import span covering the per-feed creates.
- Dashboard: a **Feed health** panel for import and discovery activity.

### Acceptance criteria

- [ ] Discoverer unit tests: multiple alternate links, relative hrefs, RSS and Atom types, no links, input that is already a feed.
- [ ] OPML unit tests: nested outlines, 1.0 vs 2.0, missing attributes, malformed input gives `OPML_INVALID`, export then import gives the same feeds.
- [ ] Re-importing the same OPML adds nothing and reports every feed as skipped.

---

## Phase 13: Observability completion, dashboard sign-off and API docs

**User stories**: 82, 84, 85, 90, 109. Also completes 103, 104 and 111 (started in Phase 1) and 110 (started in Phase 3).

### What to build

Finish the cross-cutting work that the earlier phases have been building up:
- The feed-health indicator on the main health endpoint, kept out of the liveness and readiness groups. Its details list enabled and failing feeds (configurable threshold).
- A review of the full metric catalogue against the dashboard: every catalogued meter appears on at least one panel, and every panel has a description saying what it shows and what a bad reading means.
- Final dashboard layout, top to bottom:
  1. Overview
  2. Polling
  3. Ingestion pipeline
  4. Feed health
  5. Deduplication
  6. Data quality
  7. Stories & breaking
  8. Watches
  9. Live stream
  10. Retention
  11. Scheduled jobs (silent-failure guards)
  12. API & HTTP
  13. JVM & runtime
  14. PostgreSQL & HikariCP
  15. Container
  16. Traces
  17. Logs

  Polling, ingestion, feed health and dedup panels respond to the `source` and `feed` variables. The Traces table filters by the selected feed's span attribute, and Logs filter by `feedId`.
- Tempo span-metrics panels (from the metrics generator) for per-span-name latency, as a cross-check on the Micrometer timers.
- springdoc OpenAPI JSON and Swagger UI under `/news/v2`, documenting every endpoint, its problem responses, and the `X-Admin-Key` security scheme.
- The observability README is completed: what each row answers, the prerequisites, how to apply the Prometheus and Promtail jobs, how to provision or import the dashboard, and how to run the validator.

### Acceptance criteria

- [ ] The health endpoint shows the failing feeds, and liveness and readiness stay UP while every feed fails.
- [ ] Every meter in the catalogue is referenced by at least one panel, and every panel query uses a catalogued or allow-listed metric. The validator enforces both directions.
- [ ] The final dashboard, pushed to Grafana through the MCP, renders every panel with live data after a day of real polling. The `source`/`feed` variables filter correctly, a trace id in the Logs panel opens the trace in Tempo, and "Logs for this span" opens the matching Loki lines.
- [ ] Swagger UI loads through the gateway at `/news/v2/swagger-ui`. The OpenAPI document lists every endpoint with its error responses and the `X-Admin-Key` scheme.
- [ ] The observability README has been followed end-to-end against the docker/grafana stack, with no undocumented step.
