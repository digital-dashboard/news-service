# Argus observability

Nothing here is deployed by this repository. These are versioned blueprints that the owner applies to the existing Grafana, Prometheus, Loki, Promtail and Tempo stack by hand.

| File | Applies to | How |
|---|---|---|
| `prometheus/argus-scrape.yml` | Prometheus | Add as a `scrape_config_files` entry, or copy the job into `prometheus.yml` |
| `promtail/argus-job.yml` | Promtail | Merge into the `scrape_configs` list of `promtail-config.yml` (Promtail has no include mechanism) |
| `../grafana/dashboards/argus-observability.json` | Grafana | Provisioned from a directory, or imported through the UI |
| `../grafana/dashboards/dashboard.yaml` | Grafana | Dashboard provider for the Argus directory |
| `local/` | Your laptop | Throwaway Gate A stack, see below |

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
   - Create a directory `/etc/grafana/provisioning/dashboards-argus` containing `argus-observability.json`.
   - Put `dashboard.yaml` (provider `Argus Dashboards`, folder `Artemis`) next to the existing provider in `/etc/grafana/provisioning/dashboards/`.
   - The directory must not be inside `provisioning/dashboards`: the Hymenaios provider scans that tree recursively and would provision the dashboard twice.

## Design decisions

- **Task discovery, one mechanism per job.** Prometheus finds Argus through the Swarm tasks of the service, and keeps only the `grafana-overlay-network` address.
- **Network keep rule.** Argus is on three networks and task discovery yields one target per network, so the job keeps only `grafana-overlay-network`. Otherwise `up` shows two dead targets per task.
- **`traceId` is never a Loki label.** It is unbounded. Promtail extracts it, and Grafana's derived field recovers it from the log line. Only `level` is promoted.
- **Explicit `@timestamp`.** The Promtail job extracts `"@timestamp"` and uses it, so a delayed batch does not distort the log volume.
- **OTLP base URL appended by the app.** Spring Boot uses `management.opentelemetry.tracing.export.otlp.endpoint` verbatim. Argus takes `ARGUS_OTLP_BASE_URL` (no path) and appends `/v1/traces` itself, and ignores `OTEL_EXPORTER_OTLP_ENDPOINT`.
- **`scheduled_job` tag.** A metric tag named `job` would collide with Prometheus's own `job` label and be renamed `exported_job`.
- **Actuator requests get no span and no `http.server.requests` metric**, so Prometheus scrapes and health probes do not flood Tempo or skew latency.
- **The local stack mounts the whole `grafana/dashboards` directory.** This is harmless: Grafana ignores the yaml.

## Validating the dashboard

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -B test -Dtest='*Dashboard*Test'
```

The validator (test sources, `com.j11a.argus.observability.dashboard`) checks that the dashboard has a title and uid, rows and a timeseries panel, the `DS_PROMETHEUS`, `DS_LOKI` and `DS_TEMPO` variables, unique panel ids and a `gridPos` on every panel, no literal datasource uid anywhere (panel, target or variable), and non-empty targets. Every PromQL metric on a Prometheus target or variable must be a catalogued Argus series or an explicitly allowed framework or exporter series, with exact suffixes. A catalogue-coverage rule (every catalogued meter appears on the dashboard) exists but is switched off until a phase publishes custom meters.

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
- Grafana (http://127.0.0.1:3000, folder Artemis): every panel renders without a query error. Container CPU and memory are expected to be empty because there is no cAdvisor locally.
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
