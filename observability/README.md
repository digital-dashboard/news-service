# Argus observability

Nothing here is deployed by this repository. These are versioned blueprints that the owner applies to the existing Grafana, Prometheus, Loki, Promtail and Tempo stack by hand.

| File | Applies to | How |
|---|---|---|
| `prometheus/argus-scrape.yml` | Prometheus | Add as a `scrape_config_files` entry, or copy the job into `prometheus.yml` |
| `promtail/argus-job.yml` | Promtail | Merge into the `scrape_configs` list of `promtail-config.yml` (Promtail has no include mechanism) |
| `../grafana/dashboards/argus-observability.json` | Grafana | Imported into the "Argus" folder; the repo JSON is the single source |
| `local/` | Your laptop | Throwaway Gate A stack, see below |
| `local/grafana/dashboards.yaml` | Gate A Grafana only | Local-only dashboard provider; never used on the real stack |

## Prerequisites outside this repo

- A standalone Swarm stack `argus` (docker repo `argus/docker-compose.yml`; the service becomes `argus_argus`, which the jobs match) attached to `argus-overlay-network`, `postgres-overlay-network` and `grafana-overlay-network`. Create the consumer network once with `docker network create --driver overlay --attachable argus-overlay-network`; other services join it to call `http://argus:8080`.
- A PgBouncer `argus` entry in **transaction** pooling mode. Argus keeps no session state.
- A database `argus` whose user may run `CREATE EXTENSION IF NOT EXISTS unaccent` (or a DBA creates the extension first).
- Swarm secrets `argus_db_password` and `argus_admin_key`, and the environment variables `ARGUS_DB_URL` and `ARGUS_DB_USERNAME`.
- A database role and database for Argus, e.g. as a superuser: `CREATE ROLE argus LOGIN PASSWORD '...'; CREATE DATABASE argus OWNER argus;`. As the database owner, Argus can enable the trusted `unaccent` extension itself.
- Swarm `stop_grace_period: 40s`: graceful shutdown waits up to 30s and Swarm's default is 10s.
- Container memory of at least ~384 MB: the `HEALTHCHECK` starts a small JVM every 30s inside the container, so do not size the service below that.
- Never override the image's `HEALTHCHECK` with `wget` or `curl`: the runtime image has neither, and the image already ships an exec-form Java probe.
- The snapshot registry must accept root-level image names (`argus:<tag>`, no `common/` path), because the Jenkinsfile passes an empty `dockerRepoPath`.
- The Grafana datasources keep the uids `prometheus`, `loki` and `tempo`, the Loki derived field `"(?:traceId|trace_id)"\s*:\s*"([a-f0-9]{16,32})"`, and Tempo's `tracesToLogsV2` mapping `service.name` to the Loki label `service`.

## Edits to apply to the live stack

1. **Prometheus.** Add the job from `prometheus/argus-scrape.yml` (it discovers tasks through `tcp://docker-proxy:2375`, like the other Swarm jobs).
2. **Promtail.**
   - Merge `promtail/argus-job.yml` into `scrape_configs`.
   - In the existing `docker` job, change the drop rule's regex from `'.*hymenaois.*'` to `'.*(hymenaois|argus).*'`. Without this edit Argus logs are shipped twice.
   - Promtail runs with `-config.expand-env=true`, so keep dollar signs out of the fragment. Because the Promtail configuration is a Swarm config, bump its name (for example `promtail-config-v2`) and redeploy, or the old content stays mounted.
3. **Grafana.**
   - Import `../grafana/dashboards/argus-observability.json` into the Grafana folder "Argus" through the UI ("Import dashboard", then choose the Prometheus, Loki and Tempo datasources), overwriting the existing dashboard with the same uid.
   - Alternatively the Grafana MCP `update_dashboard` can push the same JSON, with the owner's OK.
   - The repo JSON is the single source. Never mount it as a Swarm config or a dashboard provider.

## Design decisions

- **Task discovery, one mechanism per job.** Prometheus finds Argus through the Swarm tasks of the service, and keeps only the `grafana-overlay-network` address.
- **Network keep rule.** Argus is on three networks and task discovery yields one target per network, so the job keeps only `grafana-overlay-network`. Otherwise `up` shows two dead targets per task.
- **`traceId` is never a Loki label.** It is unbounded. Promtail extracts it, and Grafana's derived field recovers it from the log line. Only `level` is promoted.
- **Explicit `@timestamp`.** The Promtail job extracts `"@timestamp"` and uses it, so a delayed batch does not distort the log volume.
- **OTLP base URL appended by the app.** Spring Boot uses `management.opentelemetry.tracing.export.otlp.endpoint` verbatim. Argus takes `ARGUS_OTLP_BASE_URL` (no path) and appends `/v1/traces` itself, and ignores `OTEL_EXPORTER_OTLP_ENDPOINT`.
- **`scheduled_job` tag.** A metric tag named `job` would collide with Prometheus's own `job` label and be renamed `exported_job`.
- **Actuator requests get no span and no `http.server.requests` metric**, so Prometheus scrapes and health probes do not flood Tempo or skew latency.
- **The local stack provisions the dashboard from `../../grafana/dashboards`** through `local/grafana/dashboards.yaml` (provider "Argus (local)", folder "Argus"). That provider exists only in the Gate A kit.

## What the rows answer

- **Overview:** is the service up, how long has it run, are requests failing or slow, age of the last completed poll (`never` until the first poll after a restart), counts of enabled and failing feeds, and total articles inserted in the last 24h.
- **Polling:** duration of feed polling runs (p95 and max by trigger), poll outcomes (`completed`: the run reached the end even if some feeds failed; `failed`: the run itself threw; `interrupted`: shutdown cut it short), transient HTTP fetch retries by source, and the share of fetches that ended in 304 Not Modified. The `source` variable filters the retries and the 304 ratio.
- **Feed health:** one table row per feed with its state, consecutive failures and time since the last successful fetch (including 304), with a link to the feed's Loki logs. Failures are orange from 1 and red from 3; time since last success is orange from 1 hour and red from 6 hours, and `never` means the feed has not succeeded since it was added. **Stalest feeds** lists the five longest-stale feeds; a feed that never succeeded is lifted above all others and shows as `never`.
- **Ingestion pipeline:** are feed fetches succeeding and how fast (outcomes by reason, fetch and ingest p95 per source), and how large the downloads are. The `source` variable filters these panels.
- **Deduplication:** what happened to entries and how sources serialise ingest. All panels follow the `source` variable.
  - **Entry decisions:** rate of each decision (inserted, updated, linked, unchanged, skipped), so a feed's mix is visible at a glance.
  - **Skip reasons:** skipped entries by reason. `missing_identity` means an entry had no usable GUID or link; `batch_duplicate` means a repeat within one download.
  - **Link-fallback outcomes:** how often a link match replaced a stored GUID (`guid_replaced`) and how often the homepage (`guarded_homepage`) or shared-link (`guarded_shared`) guard refused a link match.
  - **Lock wait p95 by source:** how long ingest waited for a source's advisory lock. Rising values mean feeds of one source are contending.
  - **Updates by reason:** updated entries as `content_changed`, `timestamp_only` or `insert_conflict` (a concurrent insert was absorbed). A steady `timestamp_only` stream on a feed means its update times move without edits.
  - **Duplicate pressure:** linked plus unchanged as a share of entries seen: how much of each download was already known.
  - **Cross-feed overlap:** linked as a share of inserted plus linked: how much new-to-this-feed content other feeds of the source already supplied.
- **Data quality:** how often parsed entries lack a field (date, GUID, image, author), as a rate and as a share of all entries seen, so a feed that stops providing a field stands out.
- **Scheduled jobs:** scheduled poll runs by outcome: `success` (the poll completed), `skipped` (the previous poll was still running), `error` (the poll failed unexpectedly) or `interrupted` (shutdown cut it short, expected during a deploy).
- **API & HTTP, JVM & runtime, PostgreSQL & HikariCP, Container:** request rate, latency and status; heap, GC, threads and CPU; pool and database health; container CPU and memory.
- **Traces, Logs:** slow and errored traces from Tempo, and the live Loki log stream.

## Logs

Argus logs one JSON object per line. The MDC keys (`pollId`, `feedId`, `sourceId`) and the structured fields below are top-level JSON fields, so Loki filters them after `| json`. Every message is also a readable sentence containing the key facts, because the plain-text `dev` profile does not print the fields. The full contract is in `plans/argus-logging.md`.

### Fields

They are emitted only when they apply.

| Field | Meaning |
|---|---|
| `feedId`, `sourceId` | The feed and source. MDC during an ingest, a field elsewhere. |
| `sourceKey` | The source key, for example `cbc.ca`. |
| `url` | The redacted feed URL: scheme, host, port and path. Never a query string or user-info. |
| `reason` | The failure reason tag, for example `io`, `http_status`, `not_a_feed`, `persist_failed`. |
| `httpStatus` | The HTTP status code of the failed fetch. |
| `errorType` | Simple class name of the root cause, for example `ConnectException` or `SSLHandshakeException`. |
| `errorMessage` | The root-cause message with URLs redacted, at most 300 characters. |
| `attempt`, `maxAttempts` | The fetch attempt that failed and how many there are, on retry lines. |
| `consecutiveFailures`, `failingThreshold` | The feed's health counters. On a recovery line `consecutiveFailures` is the count before the recovery. |
| `durationMs` | Elapsed time of an ingest or a poll. |
| `contentType`, `bodyBytes` | The response of a feed that could not be parsed. |
| `failedFeedIds` | On the poll summary: the feeds that failed in that poll. |
| `code`, `status`, `method`, `path` | Client errors: the problem code, HTTP status, method and path (no query string). |

Logs never carry the admin key or any request header, article content, article GUIDs or links, a full URL, or a stack trace for an expected failure.

### Levels for feeds

- **WARN**: every failed fetch, parse or persist of a feed, one line each, with every field above that applies.
- **ERROR**: once, when a feed reaches the failing threshold (default 3 consecutive failures): `Feed N (key) is now failing after 3 consecutive failures; last error: ...`. A stack trace is logged at ERROR only for unexpected exceptions.
- **INFO**: a retry of a fetch, a feed recovering after failures, an ingest summary, a poll summary, and the audit lines for feed create, delete, enable, disable and source PATCH.
- Client errors (4xx) are INFO. A missing or wrong admin key is a WARN with method and path only. A 404 or 405 outside `/news/v2` is DEBUG, to keep scanner noise out.

### Example LogQL

- One feed: `{service="argus"} | json | feedId="5"`
- All errors: `{service="argus", level="ERROR"}`
- Why feeds fail, readable: `{service="argus"} | json | reason="io" | line_format "{{.sourceKey}} {{.errorType}}: {{.errorMessage}}"`

## Validating the dashboard

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -B test -Dtest='*Dashboard*Test'
```

The validator (test sources, `com.j11a.argus.observability.dashboard`) checks that the dashboard has a title and uid, rows and a timeseries panel, the `DS_PROMETHEUS`, `DS_LOKI` and `DS_TEMPO` variables, unique panel ids and a `gridPos` on every panel, no literal datasource uid anywhere (panel, target or variable), and non-empty targets. Every PromQL metric on a Prometheus target or variable must be a catalogued Argus series or an explicitly allowed framework or exporter series, with exact suffixes. Catalogue coverage is on: every catalogued meter must appear on the dashboard, so a new meter fails the build until it has a panel.

## Gate A: local verification

Gate A runs the built image against a throwaway Prometheus, Loki, Promtail, Tempo and Grafana on your laptop. Every port is bound to `127.0.0.1` and every credential is fake.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -B clean verify
cd observability/local
docker compose -p argus-gate-a up -d --build
./traffic.sh
```

Then check:

- Prometheus (http://127.0.0.1:9090): the `argus` target is up, with HTTP, JVM and Hikari series carrying `application="argus"`.
- Tempo (http://127.0.0.1:3200, or Grafana Explore): traces for `service.name=argus`, and no `/actuator` spans.
- Loki (Grafana Explore): `{service="argus"}` returns JSON lines, `level` is a label, and each line's `traceId` links to Tempo.
- Grafana (http://127.0.0.1:3000, folder Argus): every panel renders without a query error. Container CPU and memory are expected to be empty because there is no cAdvisor locally.
- `docker ps` reports the Argus container as `healthy`.

Tear it down with `docker compose -p argus-gate-a down -v`.

## Gate B: checklist on the real stack

Run after the owner deploys and applies the edits above.

- [ ] The Swarm service sets `stop_grace_period: 40s` and a memory limit of at least ~384 MB.
- [ ] The `argus` target is up in Prometheus, and there is exactly one target per task (no duplicate per-network targets).
- [ ] Tempo has traces for `service.name=argus`, and none of them is an `/actuator` span.
- [ ] Loki `{service="argus"}` returns parsed JSON lines with a `level` label, and each line appears once (the `docker` job no longer ships it).
- [ ] Loki to Tempo works: the `traceId` of a log line opens the trace.
- [ ] Tempo to Loki works: "Logs for this span" opens the matching log lines.
- [ ] The Container CPU and memory panels are populated (cAdvisor labels the service as `container_label_com_docker_swarm_service_name`).
- [ ] The PostgreSQL panels are populated, including `pg_database_size_bytes{datname="argus"}`.
- [ ] `docker service ps` and `docker ps` report the Argus task as `healthy`, using the image's own health check.

Phase 2 additions, after the dashboard is imported:

- [ ] After deploy, POST a feed with the admin key and refresh it (see the API section of the main README).
- [ ] The Ingestion pipeline and Data quality rows show data.
- [ ] After a refresh, Tempo shows an `argus.ingest` trace with fetch, parse and persist children and an outbound client span. (After POST /feeds, fetch and parse sit beside `argus.ingest`, not under it.)
- [ ] Loki ingest log lines carry `feedId` and `sourceId`.

Phase 3 additions, after the dashboard is imported:

- [ ] The poll runs on schedule, and the last-poll-age stat stays under 15 min.
- [ ] A deliberately broken feed shows as failing in the feed-health table, with the right failure count.
- [ ] A refresh-all (`POST /news/v2/feeds/refresh`) during a running poll returns 409.
- [ ] The 304 ratio rises on the second poll as unchanged feeds return Not Modified.
- [ ] Right after a restart the last-poll-age stat shows `never` until the first poll completes; that is expected, not a fault.
- [ ] During a deploy the poll outcome and job-run panels show `interrupted`, never `error`.

Phase 4 additions, after the dashboard is imported and the first poll has run:

- [ ] `GET /news/v2/sources` lists 17 sources, and BBC has 3 feeds and country GB.
- [ ] The first poll ingests the seeds; the "New articles 24h" stat jumps, which is expected.
- [ ] BBC News, World and Football produce `linked` decisions in Entry decisions, and Cross-feed overlap is above 0.
- [ ] Lock wait p95 shows values for `bbc.co.uk` and `cbc.ca`.
- [ ] A second poll is mostly `unchanged`, with `updated{timestamp_only}` near 0. If a feed shows a steady stream of `timestamp_only`, note it.
- [ ] The WordPress seeds (CityNews, Global News and others) send ETags, so the 304 ratio rises on the second poll. This closes the phase 3 note.

## Shutdown time

Swarm stops the container after 40 seconds. Argus fits inside that:

- `server.shutdown: graceful` with `spring.lifecycle.timeout-per-shutdown-phase: 30s`: in-flight HTTP requests get up to 30 seconds. Only a manual `POST /feeds/refresh` can use all of it.
- Boot's `spring.task.scheduling.shutdown.await-termination` is `false` (its default, also set explicitly in `application.yml`), so closing the scheduler interrupts a running scheduled poll instead of waiting for it. The poll cancels its outstanding feeds, stops submitting new ones and ends with the `interrupted` outcome.
- The poll executor then interrupts any worker that is still running and waits at most 10 seconds (`taskTerminationTimeout`) for the workers to leave.

Worst case: 30 seconds of graceful HTTP phase plus 10 seconds of executor termination is 40 seconds, so a worker that ignores its interrupt is the only way to reach the Swarm limit. A scheduled poll alone shuts down in well under 12 seconds, and normally in milliseconds. Feeds that had finished before the interrupt stay stored, and an interrupted feed's failure count is not increased.
