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

## Observability

Dashboard, scrape and log-shipping configuration, and the local verification stack are in [observability/README.md](observability/README.md). The product requirements are in [docs/prd-argus.md](docs/prd-argus.md).
