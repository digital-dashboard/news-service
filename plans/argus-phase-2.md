# Phase 2 execution plan: first feed end-to-end

> Parent plan: `plans/argus.md` → Phase 2. PRD: `docs/prd-argus.md`. Previous phase: `plans/argus-phase-1.md`.

## Context

- **Repo today.** Phase 1 is merged to `main` and deployed (Gate B verified). The repo holds the platform, problem-details errors, a permit-all security chain, an empty metric catalogue, the dashboard skeleton and its validator, and a Testcontainers base (`AbstractIntegrationTest`).
- **Baseline.** On 2026-10-02, `./mvnw -B clean verify` on JDK 21 with Central-only settings was green: 158 tests, 0 failures, 0 skipped.
- **Untracked owner files.** `interactive_visual_im_b13838ce8d7ace99.html` and `.DS_Store` stay untouched.
- **Verified facts (Context7, jars, Maven Central):**
  - **Paging.** Boot 4.1 `spring.data.web.pageable.max-page-size` silently clamps (default 2000). Spring Data's `Pageable` resolver can therefore never return a 400.
  - **HTTP client settings.** `spring.http.clients.connect-timeout`, `read-timeout` and `redirects` (`follow`/`dont-follow`) are global client settings.
  - **RestClient starter.** `spring-boot-starter-restclient` exists for 4.1.1. `RestClient` built from the injected `RestClient.Builder` gets the observation (client span and `http.client.requests`). `RestClient.create()` does not.
  - **Read timeout.** Spring Web 7.0.9 `JdkClientHttpRequest$TimeoutHandler` wraps the response body stream. Verified by FeedFetcherTest: the read timeout is a total deadline that bounds body reads.
  - **Library versions.**
    - ROME 2.1.0 and `rome-modules` 2.1.0 (`com.rometools`) are the latest.
    - jsoup is at 1.23.2.
    - Guava is at 33.7.2-jre (`InternetDomainName.topPrivateDomain`).
    - None of these is on the classpath yet.

## Decisions (resolved with the owner)

1. **Schema: final keys, needed columns only.**
   - `source`, `feed`, `article` and `article_feed` get their final keys and constraints:
     - `source.key` is unique.
     - `feed.url` is unique.
     - `article` is `UNIQUE(source_id, guid_key)`, with a non-unique index on `(source_id, link_key)`.
     - `article_feed` has the primary key `(article_id, feed_id)`, cascading.
   - Only the columns phase 2 writes are created. Later phases add theirs through additive changesets:
     - feed health and validators, phase 3
     - `content_hash` and `purged_article`, phase 4
     - `self_url` and its partial unique index, phase 5
     - `story`, phase 6
     - tsvector, phase 8
2. **The stale Grafana provider is cleaned up in this phase.**
   - Delete `grafana/dashboards/dashboard.yaml` and its `ArgusDashboardTest` check.
   - `observability/README.md` says to import the dashboard through the UI or MCP into the Grafana folder "Argus". There are no provider or provisioning instructions.
   - The local Gate A kit may keep mounting the directory, but the README must not imply that production provisions it.
3. **A refresh that fails upstream returns 200** with the ingest report. Its `outcome` is `FAILED` and it carries a reason code. An unknown feed id gives 404 `FEED_NOT_FOUND`.
4. **`POST /feeds` body.**
   - `url` is required: http/https, at most 2048 characters.
   - `topic` is required: one of the 16-value `Topic` enum.
   - `name` is optional and defaults to the parsed feed title.
   - A new source is named after its registrable domain until phase 4's `PATCH /sources`.
5. **Interim additions.**
   - Exact cleaned-URL duplicates give 409 `FEED_URL_CONFLICT` with an `existingFeedId` property. An insert race on `feed.url` also maps to that 409. The scheme, `www.`, redirect and self-link checks stay in phase 5.
   - A minimal `GET /feeds/{id}` is added, so the 201 `Location` header resolves.

## Defaults chosen without a question (PRD-backed or conventional)

- **`POST /feeds` ingests immediately, synchronously** (PRD feed registry), reusing the already parsed payload with no second download. If the ingest fails after the feed has committed, the failure is logged and the 201 still stands.
- **Fetch or parse failure on create** gives 422 `FEED_INVALID`. The problem gets a `reason` property drawn from the fetch or parse reason enum. The detail never contains upstream bodies or parser messages.
- **Writes** are every method other than GET, HEAD and OPTIONS. An unknown write route without a key therefore gets 401, not 404.
- **`GET /articles` items** carry: id, title, excerpt, author, link, imageUrl, categories, publishedAt, updatedAt, and a `source` summary (id, name, homepage, country).
  - feeds, storyId and storySourceCount are added in later phases. Additive changes don't break clients.
  - Sort order is `effective_at DESC, id DESC`.
  - There is no `sort` parameter.
- **Configurable User-Agent**, default the literal `Argus/0.1 (self-hosted RSS aggregator)` (resources are not Maven-filtered).

## Blueprint corrections (applied to the implementer prompts)

- **Schema.** The code-architect created every PRD column (health fields, `content_hash NOT NULL`, `story_id`, `self_url` with its partial index). That contradicts decision 1, so those columns are deferred.
- **Effective time.** `effective_at` is computed now: the later of published and updated, else the fetch time, capped at the fetch time. Newest-first sorting needs it, and phase 4 reuses it.
- **Key size cap.** `guid_key` and `link_key` longer than **512** characters become `sha256:<hex>`. The 1000-character cap proposed by the code-architect can exceed Postgres's ~2.7 kB btree limit once the text is multi-byte UTF-8.
- **Spans and timers.**
  - Only `argus.fetch` and `argus.ingest` are Observations, so they produce both timers and spans.
  - Parse and persist are plain Micrometer `Tracer` spans (`argus.parse`, `argus.persist`). As Observations they would publish uncatalogued `argus.parse`/`argus.persist` timers, which the catalogue-coverage rule then forces onto the dashboard.
  - The meter-registry test tolerates the automatic `error` tag and the `.active` long-task series.
- **Redirects.** The fetcher follows redirects itself, with `spring.http.clients.redirects=dont-follow`:
  - a maximum of 5 hops;
  - http/https only on every hop;
  - `Location` resolved against the current URL.
  
  `Fetched` carries `finalUrl` and `permanentTarget` (the end of the leading 301/308 chain, or null) from now on, so phases 3 and 5 don't change the signature. The JDK's own limit is a JVM-wide system property and reports neither value.
- **Size cap.** The body is read through a capped stream (max+1 bytes, plus an early `Content-Length` reject). `.body(byte[].class)` buffers without limit. The cap applies to the bytes after any decompression.
- **Insert path.**
  - Use `JdbcClient` with `INSERT … ON CONFLICT (source_id, guid_key) DO NOTHING RETURNING id`. On a conflict, select the existing id.
  - Then run `INSERT INTO article_feed … ON CONFLICT DO NOTHING`.
  - JPA is for reads only.
  - Persisting runs inside a separate `@Transactional` bean, or a `TransactionTemplate`, never a self-invoked `@Transactional`.
  - Fetch and parse hold no connection.
- **Entries without an identity.** An entry with neither GUID nor link gets `decision=skipped, reason=missing_identity`, plus the `guid` data-quality count. It is never inserted.
- **Interim key rule.**
  - The interim link cleaner: trim, lowercase the scheme and host, drop the fragment.
  - `guid_key` is the trimmed GUID, or the cleaned link when there is no GUID.
  - **Phase 4 must ship a re-key migration** (recompute keys and collapse collisions). Otherwise the first poll after phase 4 duplicates articles. This is added to phase 4's acceptance criteria in `plans/argus.md`.
- **Admin key.**
  - The filter is created with `new` inside `SecurityConfig`, not as a bean, to avoid double servlet registration.
  - It compares SHA-256 digests of the given and expected keys with `MessageDigest.isEqual`, so the key length doesn't leak.
  - On a match it sets an `ADMIN` authority. The key is never logged.
  - The entry point delegates to the `handlerExceptionResolver` bean with `ApiException(ADMIN_KEY_REQUIRED)`, so there is one problem+json rendering path.
  - `GlobalExceptionHandler` rethrows `AuthenticationException` and `AccessDeniedException`, leaving them to `ExceptionTranslationFilter`, instead of turning them into a 500.
- **`ApiException`** gains optional problem properties, for `existingFeedId` and `reason`.
- **Public-suffix fallback.** Guava throws on IPs, `localhost` and single-label hosts, so the resolver falls back to the lowercased host. LAN feeds are allowed.
- **DOCTYPE and XXE.** ROME disallows DOCTYPE by default, and some legitimate RSS 0.91 feeds carry one. Tests cover an XXE fixture, a billion-laughs fixture and an RSS 0.91 DOCTYPE fixture.
  - Allow doctypes only if the XXE and billion-laughs tests pass with them allowed.
  - Otherwise keep them disallowed and document the 0.91 limitation.
- **Encoding.** ROME `XmlReader` runs lenient, with the HTTP `Content-Type`. Fixtures prove the wrong-encoding-declaration case.
- **Logging hygiene.** Feed URLs may carry tokens, so query strings never go into logs or span attributes. `.uri(URI)` keeps `http.client.requests` at `uri="none"`; a test asserts this.
- **Schema drift.** Set `spring.jpa.hibernate.ddl-auto=validate` in the `it` profile, so entities and changesets can't drift.

## Design

### Packages (by feature)

| Package | Pure? | Contents |
|---|---|---|
| `feed.parse` | yes | `FeedParser.parse(byte[] body, URI feedUrl, @Nullable String contentType)` produces `ParsedFeed`/`ParsedEntry` records or throws `FeedParseException(Reason: MALFORMED_XML, NOT_A_FEED, EMPTY)`. Also `ExcerptBuilder` (jsoup; ~500 characters at a word boundary) and `ImageSelector`. |
| `feed.fetch` | no | `FeedFetcher.fetch(URI)` returns a sealed `FetchResult`: `Fetched(body, contentType, finalUrl, permanentTarget)` or `Failed(FetchFailureReason, Integer status)`. Reasons: `TIMEOUT, HTTP_STATUS, TOO_LARGE, INVALID_URL, REDIRECT_LIMIT, IO`. Config is `FetchProperties` (`argus.fetch`: user-agent, max-body-size 5MB, max-redirects 5). |
| `source` | resolver pure | `SourceResolver.keyFor(siteLink, feedUrl)`; the `Source` entity and repository; `SourceService.findOrCreate` (`ON CONFLICT (key) DO NOTHING`, then select; own transaction). |
| `feed` | no | The `Feed` entity and repository, the `Topic` enum, `FeedService` (create, get), `FeedController` (`POST /feeds`, `GET /feeds/{id}`, `POST /feeds/{id}/refresh`), DTOs. |
| `article` | no | The `Article` entity (reads), `ArticleInserter` (JdbcClient), `ArticleQueryService`, `ArticleController` (`GET /articles` with `@Min(0) page`, `@Min(1) @Max(100) size`, returning `PagedModel`), DTOs. |
| `ingest` | partly | `EntryKeys` and `EffectiveTime` (pure), `FeedIngestService` (`refresh(feedId)`, `ingestParsed(feed, parsed, fetchedAt)`), `ArticlePersister` (transactional), `IngestReport`, `IngestTelemetry`, the MDC scope. |
| `security` | verifier pure | `AdminKeyFilter`, `AdminKeyAuthenticationEntryPoint`, `SecurityConfig` changes. |

### Liquibase (`02`–`05`)

- **`source`:** `id` (identity), `key varchar(255)` unique not null, `name varchar(255)` not null, `homepage_url text`, `country char(2)`, `created_at`, `updated_at` (`timestamptz`).
- **`feed`:** `id`; `source_id` FK restrict not null; `name varchar(255)` not null; `url text` unique not null; `site_url text`; `topic varchar(20)` not null; `language varchar(16)`; `enabled boolean` not null default true; `created_at`; `updated_at`; index on `source_id`.
- **`article`:**
  - `id`, `source_id` FK restrict not null
  - `guid_key text` not null, `raw_guid text`, `link_key text`, `link text`
  - `title text` not null, `excerpt text`, `author text`, `image_url text`
  - `categories text[]` not null default `'{}'`
  - `published_at`, `updated_at_upstream`, `effective_at` not null, `fetched_at` not null, `modified_at` not null
  - Keys and indexes: `UNIQUE(source_id, guid_key)`, index `(source_id, link_key)`, index `(effective_at DESC, id DESC)`.
- **`article_feed`:** `article_id` and `feed_id`, both FK `ON DELETE CASCADE`; `first_seen_at`; PK `(article_id, feed_id)`; index on `feed_id`.

### Flows

- **`POST /feeds`** (the service is not transactional):
  1. Validate the request; failures are 400.
  2. Clean the URL. An exact duplicate is 409.
  3. Fetch and parse with no transaction open. A failure is 422 `FEED_INVALID` with `reason`.
  4. Transaction 1: find or create the source.
  5. Transaction 2: insert the feed.
  6. `ingestParsed`: the persist transaction plus meters.
  7. Return 201 with `Location: /news/v2/feeds/{id}` and the `FeedResponse`.
- **`POST /feeds/{id}/refresh`:**
  1. Load the feed with its source; a missing feed is 404.
  2. Start the `argus.ingest` observation. MDC gets `feedId` and `sourceId` through try-with-resources.
  3. Run the `argus.fetch` observation, with `source`, `outcome` and `reason` (`none` on success) set before it stops. The RestClient client span nests inside it.
  4. Run the `argus.parse` span; record the data-quality counters.
  5. Run the `argus.persist` span and its transaction.
  6. After the commit, record the decision counters.
  7. Write one INFO summary line.
  8. Return the `IngestReport`: feedId, outcome, failureReason, entriesSeen, inserted, unchanged, skipped.

### Meters (catalogue entries; tag keys already exist in `MetricNames.Tags`)

| Name | Kind | Tags |
|---|---|---|
| `argus.fetch` | timer (Observation) | `source`, `outcome` (`fetched`/`failed`), `reason` |
| `argus.ingest` | timer (Observation) | `source`, `outcome` |
| `argus.fetch.size` | summary, base unit `bytes` | `source` |
| `argus.ingest.entries` | counter | `source`, `decision` (`inserted`/`unchanged`/`skipped`), `reason` |
| `argus.parse.missing` | counter | `source`, `kind` (`date`/`guid`/`image`/`author`) |

The `source` tag value is the source key (the registrable domain), which is bounded. Feed and source ids are span attributes and MDC only. On the create path the fetch is tagged with the key resolved from the URL host.

### Dashboard

- **New rows** go after Overview and before API & HTTP, with new unique panel ids, a description on every panel and `${DS_PROMETHEUS}`. Later panels shift down. Queries filter on `source=~"$source"` and are NaN-safe at zero traffic (`clamp_min`, or `or vector(0)`).
  - **Ingestion pipeline:** fetch outcomes rate by outcome and reason; fetch p95 by source; ingest p95 by source; bytes per fetch; entry decisions rate.
  - **Data quality:** missing fields by kind as a rate, and as a share of entries seen (the denominator is the total of the decision counter).
- **`ArgusDashboardTest`:**
  - Update the expected row order.
  - Switch the catalogue-coverage rule **on**.
  - Delete the provider test.
- **Live dashboard.** The repo JSON is the source. The owner imports or patches the live dashboard after deploying; that step is not part of this work.

## Execution model

- **Branch:** `feat/argus-phase-2` from `main`. Local commits only, conventional messages, **no trailers**. Stage explicit paths, never `git add -A`. No push.
- **Implementers:** the plan-build-review implementer body through `general-purpose`, `model: sonnet`, TDD.
  - Maven always runs with JDK 21 (`JAVA_HOME=$(/usr/libexec/java_home -v 21)`) and the Central-only settings file `-s <scratchpad>/central-settings.xml`.
  - Comments are minimal.
  - No contact with real infrastructure: any `*.jmulenga.home` or `*.jmeighty.com` host, Grafana, Prometheus, Loki, Tempo, Swarm, Nexus, Jenkins, Sonar, or real RSS feeds. Feeds are served by a local JDK `HttpServer` stub with fixtures; no WireMock.
- **Ordering.** The work packages run sequentially on one branch, one agent at a time:
  - **WP-1: pure modules, fetcher, dependencies.**
    - Covers `pom.xml`, `feed.parse`, `feed.fetch`, `SourceResolver`, `EntryKeys`, `EffectiveTime`.
    - Adds `FeedStubServer` and the fixtures (`src/test/resources/feeds/`):
      - RSS 2.0 with media thumbnails
      - WordPress `content:encoded` with `dc:creator`
      - Atom 1.0
      - RSS 1.0 RDF
      - an HTML-heavy description
      - image priority
      - missing GUID or date
      - relative links
      - wrong encoding
      - malformed XML
      - XXE
      - billion-laughs
      - RSS 0.91 with a DOCTYPE
      - not a feed (HTML)
      - empty
    - Tests: the parser, excerpt, resolver, keys and effective-time tests, and `FeedFetcherTest`:
      - 200
      - 404
      - 503
      - oversized
      - slow-drip timeout
      - redirect followed (final URL, permanent target)
      - redirect limit
      - non-http scheme, initial and on redirect
      - User-Agent sent
    - Commit: `feat: add feed parser, fetcher and source resolver`.
  - **WP-2: schema, security, ingest, API, telemetry.**
    - The changesets, entities, security filter and handler changes, the ingest pipeline, the controllers, the meters and the catalogue.
    - Tests:
      - `SchemaIT`
      - `SecurityConfigTest` (writes: missing, wrong and right key, for POST, PUT, PATCH and DELETE; GET and OPTIONS open)
      - `GlobalExceptionHandlerTest`
      - `FeedControllerTest`, `ArticleControllerTest`
      - `FeedIngestIT` (refresh twice stores each entry once)
      - `FeedApiIT` (201 and Location, 422, 400 with fields, 409 with `existingFeedId`, paging shape, size 101 / page −1 / size=abc give 400, reads with no key)
      - `IngestMetricsIT`
      - `IngestObservabilityIT` (one trace with fetch, parse, persist and client spans; JSON log lines with `feedId`/`sourceId`)
    - Commit: `feat: ingest a first feed end-to-end behind the admin key`.
  - **WP-3: dashboard, docs, Grafana cleanup.**
    - Dashboard rows and the `ArgusDashboardTest` changes. `README.md` covers the API and `argus.fetch.*` settings. `observability/README.md` gets the import workflow and the Gate B additions for the new panels.
    - In `plans/argus.md`: phase 4's re-key acceptance criterion, and a note that phase 2 already introduced exact-URL 409 and `GET /feeds/{id}`.
    - Commits:
      - `feat: add ingestion and data-quality dashboard rows`
      - `chore: drop Grafana provider provisioning in favour of import`
      - `docs: add phase 2 plan and update docs`. This one includes this file.
- **After WP-3:** I verify the reports myself (`git log`/`status`/`diff --stat`, re-run `verify`, read the riskiest code). Then come the six-reviewer gate, triage with the owner, fixes, and re-verification.

## Review fixes

Applied after the six-reviewer gate:

1. Entry links are accepted only if they are http(s); `javascript:`, `data:` and the like become null.
2. `GET /articles` rejects a page whose offset overflows an int with 400 `VALIDATION_FAILED` on `page`.
3. Feed name (255), language (16) and source key (255) are guarded against column overflow on create.
4. `ObservabilityConfig.withoutQuery` no longer double-encodes `%` and brackets IPv6 hosts.
5. The slow-drip test now drips; the read timeout is confirmed to be a total deadline.
6. One shared `url` package (`HttpUrls`, `Links`) replaces the duplicated scheme checks; the feed API moved to `feed.api`, which removes the package cycle.
7. The feed insert is `INSERT ... ON CONFLICT (url) DO NOTHING RETURNING id`, so a race gives 409 without a constraint-name match.
8. URLs with user-info are rejected on create, and by the fetcher on the first URL and on every redirect hop.
9. A relative `Location` against an empty path resolves to `https://host/feed.xml`.
10. `Fetched.permanentTarget` replaces `permanentRedirect`.
11. `argus.parse.missing` is recorded after the persist commit, next to the decision counters.
12. Entries are inserted in ascending `guid_key` order to avoid deadlocks between feeds.
13. The effective-time IT uses a feed dated 2100 and asserts `effective_at = fetched_at`.
14. A UTF-8 BOM is stripped before the windows-1252 fallback decode.
15. Feed URLs lose user-info and query string unless the request carries a valid admin key.

## Verification

```bash
cd /Users/joshua/Documents/digital-dashboard/news-service
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -B -s <scratchpad>/central-settings.xml clean verify   # unit + IT + validator + JaCoCo ≥ 80%
grep -hE 'Tests run:.*Skipped' target/surefire-reports/*.txt target/failsafe-reports/*.txt  # no skips
docker build -t argus:local .
```

Optional local smoke test: run `observability/local` against the image, POST a feed served by a local static file server, refresh it, and confirm that the new panels render.

**Done means:**

- Every acceptance criterion of `plans/argus.md` Phase 2 has a passing test.
- `verify` is green with nothing skipped.
- The commits exist on `feat/argus-phase-2`, and `git status` is clean apart from the owner's untracked files.
- The review gate is triaged and the fixes are re-verified.
- What remains for the owner: push and PR, deploy, import the dashboard, and the Gate B additions.
