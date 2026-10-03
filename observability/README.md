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

- **Overview:** is the service up, how long has it run, are requests failing or slow, age of the last successful feed poll, counts of enabled and failing feeds, and total articles inserted in the last 24h.
- **Polling:** duration of feed polling runs (p95 and max by trigger), completed vs failed poll outcomes, transient HTTP fetch retries by source, and the ratio of 304 Not Modified responses.
- **Feed health:** per-feed operational state, consecutive failures, and duration since last success, with links to Loki logs, and the stalest feeds.
- **Ingestion pipeline:** are feed fetches succeeding and how fast (outcomes by reason, fetch and ingest p95 per source), how large the downloads are, and what happened to each entry (inserted, unchanged, skipped). The `source` variable filters these panels.
- **Data quality:** how often parsed entries lack a field (date, GUID, image, author), as a rate and as a share of all entries seen, so a feed that stops providing a field stands out.
- **Scheduled jobs:** background scheduled job runs broken down by outcome (`success`, `skipped`, `error`).
- **API & HTTP, JVM & runtime, PostgreSQL & HikariCP, Container:** request rate, latency and status; heap, GC, threads and CPU; pool and database health; container CPU and memory.
- **Traces, Logs:** slow and errored traces from Tempo, and the live Loki log stream.

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
