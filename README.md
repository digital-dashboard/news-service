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
| `ARGUS_FETCH_USER_AGENT` | | no | User-Agent sent when fetching feeds, default `Argus/0.1 (self-hosted RSS reader)`; avoid words like "aggregator", which some CDNs (CBC) reject |
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

Feed URLs can carry tokens, and reads are open. Without a valid admin key, every response that contains a feed URL shows it without user-info and query string (`scheme://host[:port]/path`). With a valid `X-Admin-Key`, even on a `GET`, the full URL is returned. `siteUrl` is a public site link and is always shown as stored. The redaction strips user-info and the query string only: a token placed in the URL path is still visible, so keep tokens in the query string.

- `POST /feeds`: add a feed. Body `url`, `topic` and optional `name` and `sourceId`. Without `sourceId` the source is resolved automatically (see below); an explicit `sourceId` overrides that and is never checked against the feed. It makes a single download attempt: retries and conditional GET apply to polls and refreshes only. Returns 201, or 400 (validation, including a URL with user-info such as `http://user:pass@host/feed`), 401 (missing or wrong key), 409 `FEED_URL_CONFLICT` (the URL names an existing feed), 404 `SOURCE_NOT_FOUND` (`sourceId` names no source) or 422 `FEED_INVALID` (the URL could not be fetched or is not a feed; `reason` says why).
  - Identity: the URL as entered, the final URL after redirects and the feed's self link are each checked against the URL and self link of every existing feed, compared ignoring scheme, `www.`, a default port and a trailing slash; the query is significant. The entered URL is checked before the download, the other two after it, and the first match wins. The 409 body has `existingFeedId` and `kind` (`entered`, `redirect` or `self_link`), and nothing is stored. A self link is stored with the feed when present.
  - Automatic source: the site link's registrable domain is the source key, as before. If a source with that key already exists, the site link is trusted only when something vouches for it: the feed's own host has the same registrable domain, the self link does, or a feed of that source is already on the feed's host domain. Otherwise the feed joins the source of its own host's registrable domain (created if missing) and a WARN is logged.
- `GET /feeds?page&size`: paged list of feeds with health (`state`, `consecutiveFailures`, `lastFetchedAt`, `lastSuccessAt`, `lastError`), ordered by `id ASC`. `page` defaults to 0; `size` defaults to 20 (max 100). URLs are redacted without the admin key.
- `GET /feeds/{id}`: one feed with health, or 404 `FEED_NOT_FOUND`. URL is redacted without the admin key.
- `PATCH /feeds/{id}`: change a feed. Body fields `enabled`, `name` (1 to 255 characters, not blank, stripped), `topic`, `sourceId` and `url` (absolute http(s) URL, at most 2048 characters), all optional, at least one required. Needs `X-Admin-Key`. Returns the updated feed with its current state, and takes effect on the next poll. Enable or disable never overwrites health recorded by a concurrent fetch. Errors: 400 `VALIDATION_FAILED` (no field given, blank name, bad URL or bad `sourceId`), 401 (missing or wrong key), 404 `FEED_NOT_FOUND` or `SOURCE_NOT_FOUND` (`sourceId` names no source), 409 `FEED_URL_CONFLICT` (same body as on create; the feed's own URL and self link are not a conflict), 409 `CONFLICT` (see `sourceId`) or 422 `FEED_INVALID`.
  - Order: `url`, then `name`, `topic` and `enabled` together, then `sourceId`, each committed on its own. The request shape, the feed, the target source, the new URL's identity and its download are all checked before the first write. Only a conflict that arises while the request runs can still fail a later step, and then the earlier steps stay applied.
  - `url`: one download without retries, then the entered, redirect and self-link identity checks. A URL that cleans to the stored one is a no-op with no download. The stored URL and self link are replaced (the self link is taken from the download), `ETag` and `Last-Modified` are cleared and health is kept.
  - `sourceId`: the feed and its articles move to the target source in one transaction holding both source locks. Articles only this feed links move. Articles shared with feeds that stay in the old source are copied to the target, and this feed's link moves to the copy. A moved article that duplicates one in the target collapses into the target's article (same duplicate rules as a merge; the target's article survives). A source left with no feeds and no articles is deleted. A move to the feed's current source changes nothing. If the feed changes source concurrently twice while the move runs, the answer is 409 `CONFLICT`: retry.
- `DELETE /feeds/{id}`: delete a feed and its orphan articles in one transaction. Articles still linked to another feed are preserved; the source row is kept. Needs `X-Admin-Key`. Returns 204 or 404 `FEED_NOT_FOUND`.
- `POST /feeds/{id}/refresh`: fetch and ingest now, and return the ingest report. It answers 200 even when the upstream failed; the report then has `outcome=FAILED`. An unknown id is 404 `FEED_NOT_FOUND`.
- `POST /feeds/refresh`: manual poll of all enabled feeds across virtual thread workers. The request blocks until every feed has finished; the worst case is bounded by the retry budget (`ARGUS_FETCH_RETRY_TIMEOUT` plus one read timeout) and by the concurrency. Returns 200 with an `AggregatePollReport`. Needs `X-Admin-Key`. If another poll is currently running (manual or scheduled), returns 409 `POLL_IN_PROGRESS`. If shutdown interrupts the poll, returns 503 `SERVICE_UNAVAILABLE`; feeds that had finished stay stored.
- `GET /sources?page&size`: paged list of sources ordered by `id ASC`, each with `id`, `key`, `name`, `homepage`, `country`, `articleCount` and its `feeds` (`id`, `name`, `topic`; no URLs). `page` defaults to 0; `size` defaults to 20 (max 100).
- `GET /sources/{id}`: one source, or 404 `SOURCE_NOT_FOUND`.
- `PATCH /sources/{id}`: rename a source or set its homepage or country. Body fields `name` (1 to 255 characters, not blank), `homepage` (absolute http(s) URL, at most 2048 characters) and `country` (ISO 3166-1 alpha-2, case-insensitive, stored upper case), all optional; an omitted field is left unchanged, so a value cannot be cleared. Needs `X-Admin-Key`. Returns the updated source. Errors: 400 `VALIDATION_FAILED` (no field given, blank name, bad URL or bad country), 401 (missing or wrong key) or 404 `SOURCE_NOT_FOUND`.
- `POST /sources/{id}/merge`: merge a source into another. Body `{"targetSourceId": n}`. Needs `X-Admin-Key`. In one transaction holding both source locks, all feeds and articles of the source move to the target and the source is deleted. Returns 200 with `sourceId`, `targetSourceId`, `feedsMoved`, `articlesMoved`, `articlesCollapsed` and `linksFolded`. Duplicate articles collapse first: a pair is the same GUID key, or else a link held by exactly one article on each side that is neither a homepage nor a root link. The oldest article of each pair survives, the loser's feed links are folded into it without duplicate rows (keeping the earliest `first_seen_at`), and the loser is deleted. `linksFolded` counts the links written. Errors: 400 `VALIDATION_FAILED` (missing or non-positive `targetSourceId`), 401 (missing or wrong key), 404 `SOURCE_NOT_FOUND` (either id) or 422 `SOURCE_MERGE_INVALID` (a source merged into itself).
- `GET /articles?topic&country&page&size`: articles, newest first. `topic` (for example `NEWS`) and `country` (ISO 3166-1 alpha-2, case-insensitive) are repeatable. Values within one filter are alternatives, and the two filters combine with AND. An article matches `topic` through any feed it appeared in and `country` through its source. An invalid topic or country is 400 `VALIDATION_FAILED`. Each article has a `source` summary and `feeds`, the list of feeds it appeared in (`id`, `name`, `topic`, ordered by `id`). `page` is zero-based and defaults to 0; `size` is 1 to 100 and defaults to 20. A page whose offset (`page * size`) exceeds the 32-bit range is 400 `VALIDATION_FAILED`.

```bash
curl -X POST http://localhost:8080/news/v2/feeds \
  -H "X-Admin-Key: $ARGUS_ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org/feed.xml","topic":"TECH"}'
```

## Polling and outcomes

Polls and refreshes send the stored `ETag` and `Last-Modified` on every redirect hop, so an unchanged feed answers 304 and is not parsed again. I/O errors and 5xx responses are retried with exponential backoff (`ARGUS_FETCH_RETRY_*`); 4xx responses never are. Retries and conditional GET apply to polls and refreshes only.

Outcome values, as reported in the API and as metric tag values:

- `argus.ingest` (one feed): `completed` (fetched, parsed and stored), `not_modified` (304, nothing parsed or stored) or `failed` (the fetch, parse or persist failed). The ingest report's `outcome` field uses the same three values in upper case.
- `argus.fetch` (one download): `fetched`, `not_modified` or `failed`, with a `reason` tag for failures.
- `argus.poll` (one whole run): `completed` (the run reached the end, even if some feeds failed), `failed` (the run itself threw) or `interrupted` (shutdown cut it short).
- `argus.scheduled.job`: `success`, `skipped` (the previous poll was still running), `error` or `interrupted`.

A poll or refresh also keeps feed identity current:

- A 301 or 308 chain from the stored URL updates the stored URL to the end of the chain, even on a 304; a 302 or 307 never does. An http to https upgrade of the same address is applied as well.
- If that URL already belongs to another subscribed feed (by the identity rules above), the feed is disabled with last error `duplicate of feed N` and a WARN is logged. Nothing is stored for it, the ingest report is `FAILED` with reason `duplicate_feed`, and it is not counted as a failure.
- After a successful poll the feed's self link is stored when it is missing or has changed, unless it matches another feed, which is logged at INFO and never disables the feed. So two different feeds that advertise the same self link are treated as one feed: whichever records it first keeps it, and a later create or URL change naming it is a 409.
- Ingest failure reasons `duplicate_feed` and `source_changed` are new. `source_changed` means the feed changed source twice while its articles were being written; nothing was written and it does not count against the feed's health.

On shutdown the in-flight poll is interrupted rather than awaited; see the worst-case shutdown time in [observability/README.md](observability/README.md).

## Ingest reports and deduplication

`POST /feeds/{id}/refresh` returns an `IngestReport` and `POST /feeds/refresh` an `AggregatePollReport` with the same counters summed over all feeds. The counters are `entriesSeen`, `inserted`, `updated`, `linked`, `unchanged` and `skipped`, and they always add up: `entriesSeen` is the sum of the other five. The same decisions are the `decision` tag of `argus_ingest_entries_total`, with a `reason` tag where one applies:

- `inserted`: a new article.
- `updated`: an existing article changed. `content_changed` (title, excerpt or categories differ, so the article is rewritten), `timestamp_only` (only the upstream update time moved) or `insert_conflict` (the article already existed under that GUID, this feed was linked to it and its content left untouched).
- `linked`: the entry matched an existing article first seen through another feed of the same source, so this feed is linked to it, unless its upstream update time is newer (then it is `updated{timestamp_only}`).
- `unchanged`: already stored through this feed with nothing new.
- `skipped`: not stored. `missing_identity` (no usable GUID or link) or `batch_duplicate` (a repeat of an earlier entry in the same download).

Deduplication is per source. Ingest takes a Postgres advisory lock per source, so feeds of one source ingest one at a time, and inserts use `ON CONFLICT`. An entry matches an existing article by GUID first, then by cleaned link; the link fallback is guarded against homepage links and links shared by several entries in the same batch, and replaces the stored GUID when it matches. Edits are detected by a per-feed content hash, or by a newer upstream update time. An article is linked only to feeds of its own source.

The tracking parameters stripped from links are `fbclid`, `gclid`, `mc_cid`, `mc_eid`, `cmpid`, `ref` and any name starting with `utm_` or `at_`. The list is fixed and not configurable. The link cleaner, this list and the content hash define the stored `guid_key`, `link_key` and `content_hash` values, so changing any of them needs a new Liquibase re-key changeset; `KeyStabilityTest` guards the current outputs. Without one, the first poll after a deploy would duplicate existing articles.

## Seed

Liquibase changeset `09-seed-sources-and-feeds` adds 17 sources and 20 feeds (country, homepage and topic set). It runs once per database, under context `!test`, so tests skip it. After that the feeds belong to the database: a seeded feed deleted through the API stays deleted, and edits made through the API are kept. Existing rows are matched by source key and feed URL, and a source name the owner changed is kept.

## Recorded deviations

- OpenAPI annotations for the phase 4 and phase 5 endpoints are deferred to phase 13.
- There is no `PUT /feeds/{id}`: `PATCH /feeds/{id}` covers editing a feed (story 42).
- The identity-conflict meter `argus.feed.identity.conflict` is tagged `kind`, not `via`.

## Observability

Dashboard, scrape and log-shipping configuration, and the local verification stack are in [observability/README.md](observability/README.md). The product requirements are in [docs/prd-argus.md](docs/prd-argus.md).
