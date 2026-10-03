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
| `ARGUS_FETCH_READ_TIMEOUT` | | no | Total deadline per fetch request, including reading the body; applies to each redirect hop, default `15s` |
| `ARGUS_FETCH_USER_AGENT` | | no | User-Agent sent when fetching feeds, default `Argus/0.1 (self-hosted RSS aggregator)` |
| `ARGUS_FETCH_MAX_BODY_SIZE` | | no | Largest feed body accepted, default `5MB` |
| `ARGUS_FETCH_MAX_REDIRECTS` | | no | Redirects followed per fetch, default `5` |
| `ARGUS_FETCH_RETRY_MAX_RETRIES` | | no | Max retries on 5xx or transient I/O, default `2` |
| `ARGUS_FETCH_RETRY_DELAY` | | no | Initial delay before retry, default `1s` |
| `ARGUS_FETCH_RETRY_MULTIPLIER` | | no | Exponential backoff multiplier, default `2.0` |
| `ARGUS_FETCH_RETRY_TIMEOUT` | | no | Overall per-fetch retry budget, default `25s` |
| `ARGUS_POLL_CRON` | | no | Cron expression for scheduled feed polling, default `0 */15 * * * *` (set to `-` to disable) |
| `ARGUS_POLL_CONCURRENCY` | | no | Max concurrent virtual thread workers for feed polling, default `8` |
| `ARGUS_POLL_FAILING_THRESHOLD` | | no | Consecutive failure count at which an enabled feed is marked failing, default `3` |

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

## API

Everything is under `/news/v2`. Writes need the `X-Admin-Key` header; reads don't.

Feed URLs can carry tokens, and reads are open. Without a valid admin key, every response that contains a feed URL shows it without user-info and query string (`scheme://host[:port]/path`). With a valid `X-Admin-Key`, even on a `GET`, the full URL is returned. `siteUrl` is a public site link and is always shown as stored.

- `POST /feeds`: add a feed. Body `url`, `topic` and optional `name`. Returns 201, or 400 (validation, including a URL with user-info such as `http://user:pass@host/feed`), 401 (missing or wrong key), 409 `FEED_URL_CONFLICT` (that exact URL exists) or 422 `FEED_INVALID` (the URL could not be fetched or is not a feed; `reason` says why).
- `GET /feeds?page&size`: paged list of feeds with health (`state`, `consecutiveFailures`, `lastFetchedAt`, `lastSuccessAt`, `lastError`), ordered by `id ASC`. `page` defaults to 0; `size` defaults to 20 (max 100). URLs are redacted without the admin key.
- `GET /feeds/{id}`: one feed with health, or 404 `FEED_NOT_FOUND`. URL is redacted without the admin key.
- `PATCH /feeds/{id}`: toggle feed enabled state. Body `{"enabled": boolean}`. Needs `X-Admin-Key`. Returns the updated feed with its current state. Takes effect on the next poll.
- `DELETE /feeds/{id}`: delete a feed and its orphan articles in one transaction. Articles still linked to another feed are preserved; the source row is kept. Needs `X-Admin-Key`. Returns 204 or 404 `FEED_NOT_FOUND`.
- `POST /feeds/{id}/refresh`: fetch and ingest now, and return the ingest report. It answers 200 even when the upstream failed; the report then has `outcome=FAILED`. An unknown id is 404 `FEED_NOT_FOUND`.
- `POST /feeds/refresh`: synchronous manual poll of all enabled feeds across virtual thread workers. Returns 200 with an `AggregatePollReport`. Needs `X-Admin-Key`. If another poll is currently running (manual or scheduled), returns 409 `POLL_IN_PROGRESS`.
- `GET /articles?page&size`: articles, newest first. `page` is zero-based and defaults to 0; `size` is 1 to 100 and defaults to 20. A page whose offset (`page * size`) exceeds the 32-bit range is 400 `VALIDATION_FAILED`.

```bash
curl -X POST http://localhost:8080/news/v2/feeds \
  -H "X-Admin-Key: $ARGUS_ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org/feed.xml","topic":"TECH"}'
```

## Observability

Dashboard, scrape and log-shipping configuration, and the local verification stack are in [observability/README.md](observability/README.md). The product requirements are in [docs/prd-argus.md](docs/prd-argus.md).
