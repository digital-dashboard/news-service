# Argus

Argus is the news-aggregation service of the Artemis dashboard. It is a Spring Boot 4 / Java 21 API under `/news/v2` backed by PostgreSQL. It exposes health, Prometheus metrics, JSON logs and OpenTelemetry traces; product scope is in docs/prd-argus.md.

## Configuration

| Variable | Swarm secret file | Required | Purpose |
|---|---|---|---|
| `ARGUS_DB_URL` | | yes | JDBC URL, e.g. `jdbc:postgresql://pgbouncer:5432/argus` |
| `ARGUS_DB_USERNAME` | | yes | Database user; plain environment variable (a secret file is accepted but not needed) |
| `ARGUS_DB_PASSWORD` | `argus_db_password` | yes | Database password |
| `ARGUS_ADMIN_KEY` | `argus_admin_key` | yes | Admin API key, at least 32 characters |
| `ARGUS_OTLP_BASE_URL` | | no | OTLP base URL without a path, default `http://tempo:4318`; Argus appends `/v1/traces` |
| `ARGUS_TRACING_SAMPLING_PROBABILITY` | | no | Trace sampling, 0.0 to 1.0, default `1.0` |
| `ARGUS_FETCH_CONNECT_TIMEOUT` | | no | Feed fetch connect timeout, default `5s` |
| `ARGUS_FETCH_READ_TIMEOUT` | | no | Feed fetch read timeout, default `15s` |
| `ARGUS_FETCH_USER_AGENT` | | no | User-Agent sent when fetching feeds, default `Argus/0.1 (self-hosted RSS aggregator)` |
| `ARGUS_FETCH_MAX_BODY_SIZE` | | no | Largest feed body accepted, default `5MB` |
| `ARGUS_FETCH_MAX_REDIRECTS` | | no | Redirects followed per fetch, default `5` |

Secret files are read from `/run/secrets/`. Argus refuses to start when a required value is missing or invalid, and never prints the admin key or the password.

For local runs use `SPRING_PROFILES_ACTIVE=dev` (plain-text logs, trace export off). Personal overrides go in the git-ignored `config/application-local.yml`, which loads only with the local profile: use `SPRING_PROFILES_ACTIVE=dev,local`.

## Build and verify

The build needs JDK 21:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -B clean verify
```

Maven uses ~/.m2/settings.xml; pass -s <settings.xml> to override.

`verify` runs the unit tests, the Docker-backed integration tests (Testcontainers needs a running Docker), the dashboard validator and the JaCoCo line-coverage gate (80%). The container image is built from the result:

```bash
docker build -t argus:local .
```

## API (phase 2)

Everything is under `/news/v2`. Writes need the `X-Admin-Key` header; reads don't.

- `POST /feeds`: add a feed. Body `url`, `topic` and optional `name`. Returns 201, or 400 (validation), 401 (missing or wrong key), 409 `FEED_URL_CONFLICT` (that exact URL exists) or 422 `FEED_INVALID` (not a feed).
- `GET /feeds/{id}`: one feed.
- `POST /feeds/{id}/refresh`: fetch and ingest now, and return the ingest report. It answers 200 even when the upstream failed; the report then has `outcome=FAILED`.
- `GET /articles?page&size`: articles, newest first. `size` is 1 to 100 and defaults to 20.

```bash
curl -X POST http://localhost:8080/news/v2/feeds \
  -H "X-Admin-Key: $ARGUS_ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org/feed.xml","topic":"TECH"}'
```

## Observability

Dashboard, scrape and log-shipping configuration, and the local verification stack are in [observability/README.md](observability/README.md). The product requirements are in [docs/prd-argus.md](docs/prd-argus.md).
