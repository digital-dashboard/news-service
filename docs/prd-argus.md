# PRD: Project Argus — RSS News Aggregator on Spring Boot 4

> *Argus Panoptes, the hundred-eyed giant who never fully slept: only a few of his eyes closed at a time, so he was always watching.* Argus replaces news-service, and its scheduled pollers keep watching dozens of feeds for change.

## Problem Statement

news-service today is a thin proxy in front of NewsCatcher, a paid third-party news API. It holds no data of its own. Every dashboard refresh calls NewsCatcher live through a `RestTemplate` with an API key, filtered by a hardcoded list of 25 source domains. This has several consequences:

- The service is only as available, fast and affordable as NewsCatcher. When the upstream call fails, the dashboard has no news. The kiosk has already seen 500s on `/news/v1/headlines`.
- The list of sources is compiled into the code, so adding or removing an outlet means a code change and a redeploy.
- The service can only ask "what are the latest headlines?". It cannot answer the questions the household cares about: *What is happening with Arsenal? Is something big breaking right now? Did anyone write about X last week?*
- The same story from five outlets appears as five unrelated items, so the feed is noisy.
- It runs on Spring Boot 3.3.0 with an old dependency set (RestTemplate, springdoc 2.5, JaCoCo 0.8.12). The rest of the platform is moving to Spring Boot 4, and weather-service is already on 4.0.x.

The wider dashboard system (frontend, weather-service, proxy) is being redesigned. That frees the service to drop its NewsCatcher-shaped API and become a real product of its own.

## Solution

Rebuild news-service as **Argus**, a self-hosted RSS/Atom news aggregator on Spring Boot 4 (Java 21), backed by PostgreSQL.

- On a schedule, it polls a managed list of RSS/Atom feeds, parses them with ROME, and stores normalised, deduplicated articles. Conditional GET keeps polling cheap, and one bad feed never stops the others.
- Feeds are managed at runtime through an admin API: create, update, enable or disable, delete, check health, refresh on demand, import or export OPML, and discover a feed from a site URL. No redeploy is needed.
- Feeds are grouped into **sources** (outlets), assigned automatically by domain and editable. An article is stored **once per source**, no matter how many of that outlet's feeds carry it. Duplicates are caught when publishers change GUIDs, links carry tracking parameters, feeds list an item twice, feeds of one outlet are polled in parallel, or old items reappear after being purged. The same feed can't be subscribed twice under a different URL.
- Similar headlines from different outlets are grouped into **stories**. "Latest headlines" shows one entry per story ("covered by 4 sources"). A story that several outlets pick up within a short window is flagged as **breaking**.
- The household can set up **watches** on a subject, such as *Arsenal* (include "Arsenal", "Gunners"; exclude "Arsenal Tula"). Every new article is checked against every watch at ingest time, and a new or edited watch is also run over the articles already stored. Each watch keeps its own list of matches and a count of unseen matches.
- Full-text **search** across all retained articles uses PostgreSQL full-text search, ranked by relevance.
- A server-sent events stream pushes new headlines, breaking stories and watch matches to any connected client (the redesigned frontend).
- Articles carry an image URL where the feed provides one, so the new frontend can show visual cards.
- The service is observable: Actuator health for feeds, Micrometer metrics for every poll, and OpenAPI docs.

All NewsCatcher code, the v1 API and the `ApiResponse` envelope are removed. The new API is versioned `/news/v2`. It returns plain resource bodies with correct HTTP status codes, and errors as RFC 9457 Problem Details. The service uses built-in Spring features wherever they cover the need:

- Spring Scheduling, on virtual threads
- Spring Framework 7 resilience annotations
- Spring Data JPA
- Liquibase
- Spring Security
- `RestClient`
- `SseEmitter`
- Application events
- `@ConfigurationProperties`
- Actuator and Micrometer

## User Stories

### Reading news (household member, via the dashboard)

1. As a household member, I want to see the latest headlines from all my outlets in one place, so that I get an overview without opening several sites.
2. As a household member, I want each story to appear once even when several outlets cover it, so that the headline list isn't padded with near-duplicates.
3. As a household member, I want to see how many sources cover a story, so that I can tell what is significant from what is niche.
4. As a household member, I want to open a story and see every outlet's version of it, so that I can compare coverage.
5. As a household member, I want headlines filtered by country (e.g. Canada, UK, Zambia), so that I can focus on the region I care about right now.
6. As a household member, I want headlines filtered by topic (e.g. sport, business, world), so that the dashboard can show topic-specific panels.
7. As a household member, I want headlines limited to a recent window (e.g. last hour, last 24 hours), so that I only see fresh news on the wall display.
8. As a household member, I want each headline to have a short plain-text excerpt, so that I can understand the story without HTML tags or truncated markup.
9. As a household member, I want each headline to show its outlet, author and publish time, so that I know who wrote it and when.
10. As a household member, I want each headline to carry a link to the original article, so that I can scan the QR code and read the full story on my phone.
11. As a household member, I want headlines to carry an image when the outlet provides one, so that the redesigned dashboard can show visual cards.
12. As a household member, I want news to keep showing even if one outlet's feed is down, so that a single broken site doesn't blank the news panel.
13. As a household member, I want news to keep showing even if the internet is briefly unavailable, so that the dashboard serves the last stored articles instead of an error.

### Breaking news

14. As a household member, I want stories that several outlets pick up within a short time to be flagged as breaking, so that big events stand out.
15. As a household member, I want a dedicated list of current breaking stories, so that the dashboard can show a breaking-news banner.
16. As a household member, I want a story to stop being "breaking" after a few hours, so that the banner doesn't show yesterday's news.
17. As a household member, I want breaking detection to work without anyone labelling stories by hand, so that it runs unattended.
18. As a household member, I want the dashboard to be told immediately when a story becomes breaking, so that the banner appears without waiting for the next poll.

### Watches and topic monitoring

19. As a household member, I want to create a watch called "Arsenal" with the terms "Arsenal", "Gunners" and "Emirates Stadium", so that every article about my team is collected in one place.
20. As a household member, I want to add exclude terms such as "Arsenal Tula", so that unrelated articles that share a word don't pollute my watch.
21. As a household member, I want watch terms to match whole words and phrases case-insensitively, so that "Arsenal" matches "ARSENAL" but not "arsenals" or unrelated substrings.
22. As a household member, I want accented and unaccented spellings to match (e.g. "Ødegaard" and "Odegaard"), so that I don't miss articles over a diacritic.
23. As a household member, I want a watch to match on headline, excerpt and the outlet's own categories or tags, so that articles tagged "Arsenal" are caught even when the headline doesn't name the club.
24. As a household member, I want a new watch to be filled immediately with matching articles already stored, so that I don't start with an empty list.
25. As a household member, I want editing a watch's terms to recompute its matches, so that the list always reflects the current rules.
26. As a household member, I want to see the matches for a watch, newest first and paginated, so that I can catch up on everything about the subject.
27. As a household member, I want to see how many unseen matches each watch has, so that I know at a glance whether there's news about Arsenal.
28. As a household member, I want to mark a watch as seen, so that the unseen counter resets after I've caught up.
29. As a household member, I want to disable a watch temporarily without deleting it, so that I can pause e.g. a tournament watch in the off-season.
30. As a household member, I want watches for companies or any other words (e.g. "Shopify", "Zambia copper"), so that the feature isn't limited to sport.
31. As a household member, I want the dashboard to be told immediately when a watch gets a new match, so that it can show a live counter or toast.
32. As a household member, I want watch matches to also show which story they belong to, so that I can see the wider coverage of that event.

### Search

33. As a household member, I want to search all stored articles by keyword, so that I can find something I half-remember from last week.
34. As a household member, I want search to understand quoted phrases, OR and minus-terms ("arsenal -tula"), so that I can narrow a search without learning a query language.
35. As a household member, I want search results ranked by relevance, with headline matches above body matches, so that the best results come first.
36. As a household member, I want search to match word variants (e.g. "transfer" matching "transfers"), so that I don't have to guess the exact wording.
37. As a household member, I want search combinable with country, topic, feed and date filters, so that I can search e.g. only UK sport from the last 7 days.

### Feed management (maintainer / admin)

38. As the maintainer, I want feeds stored in the database rather than in code, so that I can add or remove outlets without a redeploy.
39. As the maintainer, I want to add a feed with a URL, display name and topic, so that its articles are attributed and filterable correctly.
40. As the maintainer, I want a new feed's URL to be fetched and parsed once when I add it, so that I'm told straight away if it isn't a valid RSS/Atom feed.
41. As the maintainer, I want adding the same feed twice to be rejected with a clear conflict error naming the existing feed, even when the second URL differs by scheme, `www.`, trailing slash, a redirect or a FeedBurner-style alias, so that I don't create duplicate subscriptions.
42. As the maintainer, I want to edit a feed's name, topic, source and URL, so that I can correct metadata or follow an outlet's feed move.
43. As the maintainer, I want each new feed automatically grouped under a source (outlet) based on its website's domain, so that BBC News and BBC World are recognised as the same outlet without extra setup.
44. As the maintainer, I want to override a feed's source when I create or edit it, so that an outlet on several domains (bbc.co.uk and bbc.com) is still treated as one source.
45. As the maintainer, I want to list sources, rename them, and set each one's home country, so that articles are attributed and filtered by outlet and country correctly.
46. As the maintainer, I want to merge two sources, or move a feed to another source, with any duplicate articles between them collapsed automatically, so that a correction leaves no leftover duplicates.
47. As the maintainer, I want a feed that permanently moves (301/308) to have its stored URL updated automatically, so that I don't have to chase outlets' URL changes.
48. As the maintainer, I want a feed whose permanent redirect lands on a feed I already subscribe to be disabled, with a health error naming the other feed, so that the same feed is never polled twice.
49. As the maintainer, I want to enable or disable a feed, so that I can pause a noisy or broken outlet without losing its history.
50. As the maintainer, I want to delete a feed along with its articles, so that removing an outlet leaves nothing behind.
51. As the maintainer, I want to list all feeds with their health (last fetched, last success, last error, consecutive failures), so that I can see which outlets are broken.
52. As the maintainer, I want to trigger a refresh of one feed on demand, so that I can check a fix immediately.
53. As the maintainer, I want to trigger a refresh of all feeds on demand, so that I can fill the database right after deployment.
54. As the maintainer, I want a manual refresh-all that is requested while a scheduled poll is running to be rejected or ignored, not run twice, so that the same feeds aren't fetched concurrently.
55. As the maintainer, I want to give a site URL (e.g. arsenal.com) and get back the RSS/Atom feeds it advertises, so that I don't have to dig through page source for the feed link.
56. As the maintainer, I want to import an OPML file, so that I can bring my subscriptions over from another RSS reader in one step.
57. As the maintainer, I want an OPML import to skip feeds that already exist and report what was added, skipped or invalid, so that I can re-import safely.
58. As the maintainer, I want to export all feeds as OPML, so that I can back up or move my subscription list.
59. As the maintainer, I want the service to start with a seed list of working feeds for the outlets I follow today, so that the switch from NewsCatcher doesn't lose coverage.
60. As the maintainer, I want write operations (feeds, watches, refresh, import) to require an admin key, so that anyone on the LAN can read news but not change configuration.
61. As the maintainer, I want read endpoints to need no credentials, so that the kiosk and other household devices work without secrets.

### Ingestion behaviour (maintainer / operator)

62. As the operator, I want all enabled feeds polled automatically every 15 minutes (configurable), so that news stays fresh with no manual action.
63. As the operator, I want polling to send `If-None-Match` and `If-Modified-Since`, so that unchanged feeds cost a 304 and not a full download.
64. As the operator, I want feeds fetched in parallel, so that one slow outlet doesn't hold up the whole poll.
65. As the operator, I want each fetch to have connect and read timeouts and a maximum response size, so that a hung or huge feed can't stall or exhaust the service.
66. As the operator, I want transient network errors retried a few times with backoff, but 4xx responses not retried, so that blips recover while dead URLs fail fast.
67. As the operator, I want a failure in one feed to be recorded on that feed and not affect the others, so that problems are isolated and visible.
68. As the operator, I want articles deduplicated per source, not per feed, so that an article carried by both BBC News and BBC World is stored once.
69. As the operator, I want an article that appears in several feeds of one source to keep the topics of all of them, so that a BBC Football story also in BBC News shows up under both SPORT and NEWS.
70. As the operator, I want an article recognised as the same when its publisher changes its GUID but keeps its link, so that GUID churn doesn't create duplicates.
71. As the operator, I want link matching skipped for links that can't identify an article (the outlet's homepage, or one link shared by several GUIDs in the same download), so that distinct items in sloppy feeds are never merged.
72. As the operator, I want links cleaned before comparison (tracking parameters such as utm_*, fbclid and gclid removed; remaining parameters sorted; fragment, scheme, `www.`, `m.` and AMP variants folded) while keeping parameters that identify the article (e.g. `?p=123`), so that the same article isn't stored twice under different URLs and different articles aren't merged.
73. As the operator, I want an item listed twice in the same feed download stored once, so that a malformed feed can't break or duplicate a batch.
74. As the operator, I want feeds from the same source ingested one at a time, with different sources still in parallel, and the database to reject duplicate inserts as a final safeguard, so that parallel polls and manual refreshes can't race to insert the same article.
75. As the operator, I want an article whose title, excerpt or categories change upstream to be updated in place (detected by a content hash or the feed's update time), keeping its story and firing no new headline event, so that corrections reach the dashboard without false alerts.
76. As a household member, I want an edited article checked against my watches again, so that a headline that now mentions Arsenal still reaches my Arsenal watch.
77. As the operator, I want articles with no publish date to use the time they were fetched, and future-dated articles capped at the fetch time, so that ordering stays sensible.
78. As the operator, I want articles older than 30 days (configurable) purged automatically, so that the database stays small on home-lab hardware.
79. As the operator, I want purging to also remove orphaned stories and watch matches, so that no dangling data accumulates.
80. As a household member, I want purged articles that are still in an outlet's feed not to come back as "new", so that I don't get repeated headline and watch alerts for old news.
81. As a household member, I want a purged article that the outlet has genuinely updated since the purge (such as a long-running live blog) to come back once as a new article, so that I don't miss updates to ongoing coverage.

### Operability

82. As the operator, I want a health endpoint that reports feed health (how many feeds are failing and which), so that I can see degradation at a glance.
83. As the operator, I want the service's liveness check to depend only on the service itself and its readiness check on the service and the database, never on outlets, so that a flaky outlet never restarts the container.
84. As the operator, I want metrics for each poll (feeds polled, 304s, new articles, failures, duration per feed), so that I can graph ingestion in Grafana.
85. As the operator, I want structured logs for each poll with feed, outcome and counts, so that I can find problems without a debugger.
86. As the operator, I want database schema changes managed by Liquibase, so that upgrades are repeatable and reviewable.
87. As the operator, I want all configuration (database URL and credentials, admin key, schedule, retention, clustering thresholds) set through environment variables with sensible defaults, so that deployment needs no rebuild.
88. As the operator, I want the service to fail fast at startup if the admin key or database settings are missing or invalid, so that a misconfiguration is caught right away.
89. As the operator, I want the Docker image to run as a non-root user with a proper entrypoint, so that the container is secure and starts the app.

### API consumers (frontend developer)

90. As a frontend developer, I want interactive OpenAPI docs, so that I can build the new dashboard against a documented contract.
91. As a frontend developer, I want successful responses to be the resource itself (no wrapper envelope), so that client code is simpler.
92. As a frontend developer, I want paged responses in one consistent shape (items plus page metadata), so that I can write one pagination helper.
93. As a frontend developer, I want errors as `application/problem+json`, with correct HTTP status codes and a stable machine-readable `code`, so that I can handle each failure specifically.
94. As a frontend developer, I want timestamps in ISO-8601 UTC and camelCase field names, so that parsing is predictable.
95. As a frontend developer, I want invalid query parameters (e.g. page size above the maximum, unknown topic) rejected with a 400 problem response, so that bugs surface quickly instead of returning surprising data.
96. As a frontend developer, I want one SSE stream carrying typed events (new headline, breaking story, watch match), so that the dashboard can update live over one connection.
97. As a frontend developer, I want the SSE stream to send heartbeats and support reconnects, so that proxies don't drop idle connections.

### Platform upgrade (maintainer)

98. As the maintainer, I want the service on Spring Boot 4 and current dependency versions, so that it matches the rest of the platform and receives security fixes.
99. As the maintainer, I want Argus built by the same `jenkins-shared-lib` Spring pipeline as weather-service and proxy, so that every service gets the same secrets scan, lint, Sonar, dependency-check, Trivy, SBOM and multi-arch image without per-repo pipeline code.
100. As the maintainer, I want integration tests against a real PostgreSQL to run in that pipeline's Build & Test stage, so that SQL, migrations and full-text search are tested for real on every build.
101. As the maintainer, I want the image built for both amd64 and arm64, so that it runs on the home-lab server and on Raspberry Pi hardware.
102. As the maintainer, I want the unused NewsCatcher code, configuration and API key removed, so that no dead code or stale secrets remain.

### Observability on the Grafana stack (operator)

103. As the operator, I want an Argus Grafana dashboard kept in the repository, so that it is reviewed, versioned and restorable like code.
104. As the operator, I want the dashboard checked automatically against the metric names the service actually publishes, so that a renamed metric fails the check instead of silently emptying a panel.
105. As the operator, I want every poll, feed ingest and API request traced to Tempo, so that I can see which feed, query or lock made a run slow.
106. As the operator, I want JSON logs carrying the trace id plus feed and source context, shipped to Loki, so that I can jump from a trace to its logs and filter logs by feed.
107. As the operator, I want per-feed staleness and failure panels, so that I can spot an outlet that silently stopped publishing or keeps failing.
108. As the operator, I want deduplication and data-quality panels (decisions, skip reasons, link-fallback hits, entries missing dates or images), so that I can tell whether deduplication and parsing are working on real feeds.
109. As the operator, I want product panels (stories formed, breaking detections, watch matches, live-stream clients and events, search latency), so that I can see the product working, not just the JVM.
110. As the operator, I want counters for failed scheduled jobs and failed after-commit listeners, shown on the dashboard, so that a broken background job can't go unnoticed.
111. As the operator, I want the Prometheus scrape job and Promtail log pipeline for Argus kept in the repository as config to apply to the stack, so that connecting the service to Grafana is a copy-and-apply step.

## Implementation Decisions

### Project identity: Argus

The service is renamed from news-service to **Argus** everywhere a name is chosen, except the public API path:

| Identifier | Value |
|---|---|
| Maven artifact / Spring application name | `argus` |
| Base Java package | `com.j11a.argus` |
| Docker image | `argus` at the registry root, with no repository path (snapshot registry, via the shared pipeline) |
| Swarm stack, service and DNS name | stack `argus`, service `argus`, reached as `argus` on `argus-overlay-network` |
| Sonar project key | `argus` |
| PostgreSQL database and user | `argus` |
| OpenTelemetry `service.name` | `argus` |
| Metric prefix | `argus.` (Prometheus `argus_*`) |
| Prometheus and Promtail jobs | `argus` |
| Grafana dashboard | "Argus — Observability", uid `argus-observability` |
| Public API | `/news/v2/...` at `http://argus:8080` |

Argus runs as its own Swarm stack (`argus`, stack file in the docker repo at `argus/docker-compose.yml`), independent of the dashboard, because other services will use it too. Consumers join the external `argus-overlay-network` and call `http://argus:8080/news/v2/...`. The dashboard's existing proxy route (`/news/**` to `news-service:8080`) no longer reaches it; a gateway that should front Argus joins `argus-overlay-network` and routes to `http://argus:8080`. The git repository and its directory keep their current name; renaming the remote is the owner's call.

### Platform and dependencies

- **Spring Boot 4.x** (latest GA at implementation time; 4.1.x at the time of writing) on **Java 21**, matching weather-service and the existing CI agents and base images.
- Use Boot 4's modular starters: the Web MVC starter (replacing the deprecated `spring-boot-starter-web`), Data JPA, Liquibase, Validation, Actuator, Security, plus the matching Boot 4 test starters (Web MVC test, Data JPA test) and Spring Boot Testcontainers support.
- **Jackson 3** (Boot 4 default). No Jackson 2 compatibility layer.
- **PostgreSQL** JDBC driver, **Liquibase** for schema migrations.
- **ROME** (latest) for RSS/Atom parsing, including its media module for `media:*` elements. ROME's own HTTP fetcher is not used.
- **jsoup** for HTML-to-text conversion, image extraction from description HTML, and feed discovery.
- **springdoc-openapi** at the major version that supports Boot 4.
- **Micrometer** with the Prometheus registry, plus **Spring Boot 4's OpenTelemetry starter** (Micrometer tracing bridge, OTLP span exporter; OTLP metric export disabled, because Prometheus scrapes).
- **Lombok** stays optional. New DTOs and value types are Java records.
- **JaCoCo** upgraded (0.8.14 or later). Unit tests run under Surefire and Testcontainers integration tests under Failsafe, both included in coverage.
- Removed: RestTemplate configuration, the NewsCatcher interceptor and URL utility, the v1 controller, service and models, the `ApiResponse`/sub-code envelope, NewsCatcher configuration keys, and the local NewsCatcher API key.

### Built-in Spring features over hand-rolled code

| Need | Spring mechanism |
|---|---|
| Periodic polling and retention | Spring Scheduling (`@Scheduled` with cron from configuration) |
| Parallel per-feed fetching | Virtual threads enabled in Boot; fan-out over a virtual-thread task executor |
| No overlapping polls (scheduled vs manual) | Spring Framework 7 `@ConcurrencyLimit(1)` on the poll-all entry point. A manual refresh-all arriving during a poll returns 409 |
| Retrying transient fetch errors | Spring Framework 7 `@Retryable` with backoff, limited to I/O and 5xx failures (never 4xx) |
| Outbound HTTP | Boot-configured `RestClient` with timeouts, User-Agent and redirect policy set centrally |
| Decoupling ingest from SSE and metrics | `ApplicationEventPublisher` plus after-commit `@TransactionalEventListener`, so events fire only for committed data |
| Live push | Spring MVC `SseEmitter` |
| Admin key | Spring Security: a stateless filter chain with a custom API-key authentication filter. `GET` is permitted for all; write methods need the admin authority |
| Errors | Spring `ProblemDetail` with Boot's problem-details support, plus a `@RestControllerAdvice` that adds a stable `code` property |
| Paging | Spring Data `Pageable` with `PagedModel` DTO serialisation, giving a stable page JSON shape |
| Filtering | Spring Data JPA Specifications for structured filters. Native queries only where Postgres-specific features are needed (full-text search, ranking) |
| Configuration | `@ConfigurationProperties` records with `@Validated`, so the app fails fast on bad config |
| Health and metrics | Actuator `HealthIndicator`, Micrometer timers and counters, and Observation support |

The service runs as a **single instance**, so it needs no distributed scheduling lock (ShedLock, etc.). Per-source ingest serialisation uses Postgres advisory transaction locks. These work across connections, so they would stay correct if a second instance were ever added.

### Modules

The code is organised by feature (feeds, articles, stories, watches, ingestion, API), not by layer.

**Deep modules: pure logic, no Spring context, and the main focus of unit tests**

1. **Feed parser.** Input: raw feed bytes plus the feed's URL. Output: a parsed feed made of normalised entries. Responsibilities:
   - Handle RSS 0.9x, 1.0 and 2.0 and Atom 0.3 and 1.0.
   - Resolve relative links against the feed URL.
   - Turn description HTML into a plain-text excerpt: tags stripped, entities decoded, whitespace collapsed, truncated to about 500 characters at a word boundary.
   - Pick the author from entry author, `dc:creator` or the feed author.
   - Read the publish date and the update time separately (Atom `updated`, where present), with no fallback inside the parser.
   - Read categories and tags.
   - Choose an image URL in priority order: `media:thumbnail`, `media:content` (image), image enclosure, first `<img>` in the description or content.
   - Expose the raw GUID and the link. Key computation belongs to the dedup modules below.
   - Expose the feed's own self link (`atom:link rel="self"`) and its site link, for feed deduplication and source resolution.
   - Malformed XML gives a typed parse failure, never an unchecked crash.
2. **Link normaliser.** Used for article links, feed URLs and dedup. Deterministic, and applying it twice gives the same result. It:
   - Folds the scheme to one value, lowercases the host, and removes a leading `www.` or `m.`.
   - Folds AMP variants: an `amp.` subdomain, a trailing `/amp` path segment, and `amp=1` / `outputType=amp` parameters.
   - Removes the fragment and the trailing slash.
   - Removes tracking parameters from a configurable list (default includes `utm_*`, `fbclid`, `gclid`, `mc_cid`, `mc_eid`, `cmpid`, `ref`, `at_*`), keeps the rest, and sorts them.
   - Because matching is always within one source, folding hosts can't merge different outlets.
3. **Source resolver.** Maps a feed's site link (or its URL, if there's no site link) to a source key: the registrable domain, worked out with a public-suffix-aware library (e.g. `feeds.bbci.co.uk` → `bbci.co.uk`, `www.cbc.ca` → `cbc.ca`). An explicit source id on the request always wins over the derived key.
4. **Entry dedup resolver.** The core of article deduplication, as a pure function. It takes:
   - The parsed entries of one feed download
   - The source's homepage
   - A lookup of existing articles in the source by GUID key and by link key
   - A lookup of the recently purged list
   - The retention cutoff

   For each entry it returns one decision: **insert**, **update** (with the matched article id, and whether the content changed), **link-to-feed-only** (same content, seen from another feed of the source), or **skip** (with a reason: older than cutoff, recently purged, or duplicate within the batch). Rules:
   - **Keys:**
     - GUID key = the source plus the trimmed raw GUID. A GUID that is a permalink URL is cleaned like a link.
     - Link key = the source plus the cleaned link.
     - Content hash = a hash of the normalised title + excerpt + categories.
   - **Duplicates within one download:** collapse entries with the same GUID key, or the same usable link key. Keep the copy with the latest update time, or the last one in the document if they're tied.
   - **Usable links:** a link key is only used for matching if it isn't the source's homepage and isn't shared by several distinct GUIDs within the same download.
   - **Matching order:**
     1. A GUID-key match is that article.
     2. Otherwise a usable link-key match is that article, and its stored GUID key is replaced with the new one.
     3. If the GUID and the link point at different articles, the GUID wins.
   - **Effective time** = the later of the publish date and the update time, falling back to the fetch time, and capped at the fetch time.
   - **Retention cutoff:** an entry with no stored match whose effective time is before the cutoff is skipped.
   - **Recently purged:** an entry matching the purged list is skipped, unless it has been updated since the purge: its effective time is after the purge time, or its content hash differs from the purged hash. A re-admitted entry is an insert, and fires the normal events.
5. **Story clusterer.** Takes a new article title plus the candidate stories active in the clustering window. Returns either an existing story or "start a new story", and separately decides whether a story is breaking. Responsibilities:
   - Normalise titles: lowercase, strip punctuation and accents, remove stop-words, drop very short tokens.
   - Measure similarity with Jaccard over title token sets. The threshold is configurable, default about 0.5.
   - Detect breaking: a story is breaking when at least N **distinct sources** (default 3) published into it within M minutes (default 60) of its first article. Feeds of the same source count once, so BBC News and BBC World don't count as two confirmations.
   - Expire breaking after a configurable duration (default 3 hours).
   - All thresholds come from configuration, and the module takes a clock for testability.
6. **Watch matcher.** Takes a watch definition (include terms, exclude terms) and an article's matchable text (title, excerpt, categories). Returns match or no-match. Matching rules:
   - Case-insensitive and accent-insensitive.
   - Whole-word or whole-phrase matching on word boundaries.
   - At least one include term must match, and no exclude term may match.
7. **OPML codec.** Reads OPML 1.0 and 2.0, flattening nested outline groups and keeping the outline category as a topic hint. Writes OPML 2.0 from the feed list. Malformed input gives a typed failure.
8. **Feed discoverer.** Takes a site URL and returns candidate feeds (URL, title, type). Takes an already fetched HTML document. If the input URL itself is a feed, it returns that URL. Fetching is delegated to the feed fetcher.

**Integration modules**

9. **Feed fetcher.** Input: feed URL plus the stored ETag and Last-Modified validators. Output: one of three results:
   - *Not modified*
   - *Fetched* (bytes, new validators, the final URL after redirects, and whether a **permanent** redirect, 301 or 308, was followed)
   - *Failed* (a classified reason: timeout, HTTP status, too large, invalid URL, I/O)

   It sends conditional-GET headers and a descriptive User-Agent. It follows a limited number of redirects and enforces a maximum body size (default about 5 MB). Only `http` and `https` are allowed. Transient failures are retried as described above.
10. **Feed registry** (feed and source management). It:
    - **Creates feeds.** Cleans the URL, fetches it once (following redirects), parses it, and gathers the identity URLs: the cleaned URL as entered, the cleaned final URL, and the cleaned self link. If any of them matches an existing feed's identity URLs, it rejects with 409 `FEED_URL_CONFLICT` naming that feed. Otherwise it resolves the source (explicit id, or find-or-create by the source resolver's key), saves the feed, and triggers an immediate ingest of it.
    - **Updates feeds.** Re-runs the same identity check on a URL change.
    - **Handles permanent redirects seen during polling.** The stored URL moves to the final URL. If that collides with another feed's identity URLs, the feed is disabled instead, with last-error "duplicate of feed {id}".
    - **Merges sources.** Merging two sources, or moving a feed to another source, runs in one transaction holding both sources' advisory locks:
      1. Move the feeds and articles to the target source.
      2. Re-run the dedup resolver's key rules across the combined articles. For each duplicate group, keep the oldest article and fold the others' feed links and watch matches into it. Duplicate match rows are dropped.
      3. Recalculate the distinct-source counts of affected stories.
      4. Delete the empty source when merging.
11. **Ingestion pipeline.** Input: a feed. Output: an ingest report (not-modified, inserted, updated, linked, skipped with reasons, or failure). For each feed:
    1. Fetch. If there was a permanent redirect, apply it through the feed registry.
    2. Parse.
    3. Open one transaction per feed and take a Postgres **advisory transaction lock on the feed's source id**. Feeds of the same source are processed one at a time, while different sources still run in parallel. The same lock covers manual refreshes that overlap a scheduled poll.
    4. Load existing articles in the source matching the batch's GUID and link keys, plus matching recently-purged entries, and run the **entry dedup resolver**.
    5. Apply the decisions:
       - **insert:** written with `INSERT … ON CONFLICT (source, guid key) DO UPDATE` as the final safety net. Then assign a story, re-evaluate the story's breaking state, match against enabled watches, and record the article–feed link.
       - **update with changed content:** update in place, keep the story, and re-run watch matching. New matches are added and existing matches are never removed. No `headline` event.
       - **update without changed content** and **link-to-feed-only:** record the article–feed link if it's new, and nothing else.
       - **skip:** counted in the report by reason.
    6. Commit.
    7. Update feed health in a separate transaction, so a failed ingest is still recorded.
    8. Publish domain events after commit:
       - `headline`: for inserts only
       - `breaking`: when a story becomes breaking
       - `watch-match`: for every new match, including matches from edited articles
12. **Polling orchestrator.** A scheduled cron entry point plus a manual "refresh all" and "refresh one". It fans out across enabled feeds on virtual threads, waits for completion, and returns an aggregate report. It is protected against overlapping runs. Per-source ordering comes from the advisory lock in the ingestion pipeline, not from the orchestrator.
13. **Retention job.** A scheduled daily job:
    1. Delete articles whose effective time is before the retention cutoff. Before deleting, record each one's source, GUID key, link key, content hash and purge time in the recently-purged table.
    2. Cascade to watch matches and article–feed links.
    3. Delete stories that have no articles left.
    4. Delete recently-purged rows older than the grace period (default 30 days).
14. **Watch service.** Create, update, delete, enable and disable watches. On create or update it recomputes matches over the retained articles. A SQL pre-filter finds candidates containing any include term, then the watch matcher confirms them. This runs synchronously, which is fine at the expected volume (about 25–40 feeds × about 50 articles a day × 30 days, or under 50k rows). It also provides the unseen count and mark-as-seen.
15. **Query services.** Implement article listing (filters plus optional full-text query), headlines (collapsed stories), story detail, breaking stories, and watch matches. Topic filters match if *any* feed the article appeared in has that topic. Country filters use the source's country.
16. **Live event broadcaster.** Keeps the set of `SseEmitter` connections. Listens to after-commit domain events and broadcasts typed events. Sends a heartbeat at a configurable interval, removes dead emitters, and supports reconnects through `Last-Event-ID` on a best-effort basis (no event replay guarantee).
17. **Telemetry.** The service is observed on the existing Grafana stack: Prometheus for metrics, Loki for logs and Tempo for traces.
    - **Feed health indicator.** Health details list the enabled feeds and the failing ones (failing means three or more consecutive failures, configurable). The indicator joins the main health endpoint but stays out of the liveness and readiness groups.
    - **Metrics.** Micrometer, exposed at the Actuator Prometheus endpoint.
      - Every custom metric is named under one `argus.` prefix in a single metric-names catalogue, which is the source of truth for the dashboard check.
      - Percentile histograms are enabled for HTTP server and client requests and for every custom timer, so latency panels can compute p95.
      - Tags have bounded cardinality. Feed, source, watch, outcome and reason are allowed. Article ids, URLs, GUIDs, search text and watch terms never become tags.
      - The full catalogue:
        - Poll timer, tagged by trigger (`scheduled`/`manual`) and outcome
        - Per-feed fetch timer, tagged by source and outcome (`fetched`/`not_modified`/`failed`, with a failure reason)
        - Per-feed ingest timer
        - Distribution of downloaded bytes
        - Fetch-retry counter, fed by Spring's retry events
        - Advisory-lock wait timer
        - Entry decision counter: `inserted`/`updated`/`unchanged`/`linked`/`skipped`, with the skip reason
        - Link-fallback counter: GUID replaced / guarded homepage / guarded shared link
        - Parse data-quality counter for entries missing a date, GUID, image or author
        - Redirect and feed-identity-conflict counters
        - Source-merge timer and collapsed-article counter
        - Story assignment counter (new or joined)
        - Breaking-detection counter, and an active-breaking gauge
        - Watch-match counter, tagged by watch and trigger (`ingest`/`edit`/`backfill`), a backfill timer, and an unseen-matches gauge per watch
        - Live-stream metrics: active connections gauge, opened counter, events-sent counter by type, disconnect counter by reason
        - Retention: purged-rows counter by kind, run timer, and a recently-purged list size gauge
        - Scheduled-job run counter, tagged by `scheduled_job` and outcome
        - After-commit listener failure counter
        - Feed gauges: by state, consecutive failures per feed, and seconds since last success per feed
        - Last-successful-poll timestamp gauge
        - Stored-article count and newest-article age gauges, refreshed on a short cache rather than per scrape
    - **Traces.** OpenTelemetry through Spring Boot 4's OpenTelemetry support and the Micrometer Observation API, exported over OTLP/HTTP to Tempo. Boot's endpoint property is used verbatim, so Argus takes its own validated base URL and appends `/v1/traces` itself, with environment-variable mapping disabled.
      - Spans: a root span per poll, a child span per feed ingest (fetch, parse, resolve, persist), HTTP server spans, `RestClient` client spans, watch backfill, retention, source merge.
      - Feed and source ids are span attributes, never metric tags. Sampling is configurable, defaulting to 100%.
    - **Logs.** Spring Boot structured logging to the console in the Logstash JSON format: `@timestamp`, `level`, `logger_name`, `message`, plus `traceId`/`spanId` and MDC keys for poll id, feed id and source id. Promtail parses these fields with the job in `observability/promtail/argus-job.yml`, and `traceId` stays in the log text, never a Loki label.
    - **Dashboard.** One versioned dashboard JSON with a Grafana provisioning file, built on datasource template variables (no hardcoded UIDs), plus a JUnit validator over the metric catalogue, modelled on Hymenaios' rules. It fails on:
      - Invalid JSON
      - Missing template variables
      - Duplicate panel ids
      - A hardcoded datasource UID
      - A target with no query
      - A PromQL metric missing from the published catalogue

      The validator runs as part of the build.
    - **Stack configuration.** A Prometheus Swarm task-discovery scrape job for `argus`, matched by service-name suffix, and a Promtail job with a JSON parsing pipeline. Both live in the repository as config to apply to the docker/grafana stack, not applied by it.
18. **API layer.** Controllers, request validation, problem-details advice, Security configuration, and OpenAPI metadata.

### Data model (managed by Liquibase)

- **source**: id, name, key (the registrable domain; unique), homepage URL, country (ISO 3166-1 alpha-2), created-at, updated-at. One outlet. Its home country lives here, not on the feed.
- **feed**: id, source reference, name, url (cleaned; unique), self URL (cleaned; nullable), site URL, topic, language, enabled, ETag, Last-Modified, last-fetched-at, last-success-at, last-error (truncated), consecutive-failures, created-at, updated-at.
  - The **identity URLs** are the url and the self URL. A new or updated feed conflicts if any of its identity URLs equals any identity URL of another feed. This is enforced by a lookup in the feed registry, plus a unique index on url and a unique partial index on self URL.
- **article**: id, source reference, GUID key, raw GUID, link key, link, content hash, title, excerpt, author, image URL, categories (text array), published-at, updated-at-upstream (nullable), effective-at, fetched-at (first seen; never changed), modified-at (last local change), story reference. Constraints and indexes:
  - **Unique on (source, GUID key).** This is the final safeguard for `ON CONFLICT`.
  - **Non-unique index on (source, link key).** Link matching is guarded by the resolver, so it's not a constraint.
  - Index on effective-at, and on (story, effective-at).
  - A generated, stored `tsvector` column (title weighted A, excerpt and categories weighted B, English configuration, accents removed via `unaccent` through an immutable wrapper function) with a GIN index.
  - Entries with no GUID use their link key as the GUID key, so every article has one.
- **article_feed**: article reference, feed reference, first-seen-at. Primary key (article, feed). Both references cascade on delete. Records every feed of the source the article appeared in. Deleting a feed removes its links, and then any article with no remaining links.
- **purged_article**: source reference, GUID key, link key, content hash, effective-at, purged-at. Indexed on (source, GUID key) and (source, link key), and on purged-at for cleanup.
- **story**: id, representative title (taken from the first article), normalised title tokens, first-seen-at, last-seen-at, distinct-source count (distinct **sources**, not feeds), breaking-since (nullable). Indexed on last-seen-at and breaking-since.
- **watch**: id, name (unique), include terms (non-empty text array, at most 20 terms of at most 100 characters each), exclude terms (text array), enabled, last-seen-at, created-at, updated-at.
- **watch_match**: watch reference, article reference, matched-at. Primary key (watch, article). Both references cascade on delete. Indexed on (watch, matched-at).
- **Topic** stays a fixed enumeration, carried over from the current service: NEWS, TECH, WORLD, FINANCE, POLITICS, BUSINESS, ECONOMICS, ENTERTAINMENT, BEAUTY, TRAVEL, MUSIC, FOOD, SCIENCE, GAMING, ENERGY, SPORT. Topic belongs to the feed and country belongs to the source. An article gets the topics of every feed it appeared in.
- Seed sources and feeds come from a Liquibase changeset with a context or label, so they can be skipped in tests (see the appendix). The seed runs **once**. After that the database is the only source of truth for feeds. A seeded feed deleted through the API stays deleted after restarts and redeploys, and feed changes never need a new changeset.
- The polling orchestrator reads the enabled feeds from the database at the start of every run, with no caching. Feeds added, edited, disabled or deleted through the API are picked up by the next poll without a restart. A newly created feed is also ingested right after creation, so its articles appear without waiting for the next scheduled poll.
- Required Postgres extension: `unaccent`.

### API contract (`/news/v2` on `http://argus:8080`)

Reads need no credentials. Writes need the `X-Admin-Key` header. All timestamps are ISO-8601 UTC and all field names are camelCase.

**Articles and stories**
- `GET /news/v2/articles`. Filters: `since`, `until`, `sourceId` (repeatable), `feedId` (repeatable), `country` (repeatable, matched on the source's country), `topic` (repeatable, matched on any feed the article appeared in), `q` (web-search syntax), `page`, `size` (default 20, max 100). Sorted by newest first, or by relevance when `q` is present. Each item includes: id, title, excerpt, author, link, imageUrl, categories, publishedAt, updatedAt (when the outlet last edited it, if known), a source summary (id, name, homepage, country), the feeds it appeared in (id, name, topic), storyId and storySourceCount.
- `GET /news/v2/articles/{id}`
- `GET /news/v2/headlines`. Filters: `since` (default 24h), `country`, `topic`, `limit` (default 20, max 100). Returns one entry per story with recent activity. Each entry has the representative article (the earliest in the story), sourceCount, the latest update time, and a breaking flag. Ordered by latest activity.
- `GET /news/v2/stories/{id}`: the story with all its articles.
- `GET /news/v2/breaking`: stories that are currently breaking, newest first.

**Sources** (writes need the admin key)
- `GET /news/v2/sources` and `GET /news/v2/sources/{id}`, each with its feeds and article count.
- `PATCH /news/v2/sources/{id}`: change the name, homepage or country.
- `POST /news/v2/sources/{id}/merge` with a target source id: merges this source into the target and collapses duplicates (see the feed registry). Returns the number of feeds moved and articles collapsed. The source is deleted afterwards.

Sources are created automatically by feed creation. There is no separate create endpoint.

**Feeds** (writes need the admin key)
- `GET /news/v2/feeds` and `GET /news/v2/feeds/{id}`, including the source and health fields.
- `POST /news/v2/feeds`: an optional `sourceId` overrides automatic source resolution. Validates by fetching and parsing once, then checks for duplicates against the URL as entered, the final URL after redirects, and the feed's self link. Returns 201 and starts an immediate ingest; 409 `FEED_URL_CONFLICT` if any identity URL matches an existing feed (the problem body includes `existingFeedId`); or 422 if the URL is not a valid feed.
- `PUT /news/v2/feeds/{id}`, `PATCH /news/v2/feeds/{id}` (e.g. enable or disable, or change `sourceId`, which moves the feed and collapses duplicates like a merge), `DELETE /news/v2/feeds/{id}`. Deleting removes the feed's article links and any articles with no remaining feed.
- `POST /news/v2/feeds/{id}/refresh`: returns the ingest report.
- `POST /news/v2/feeds/refresh`: returns the aggregate report, or 409 if a poll is already running.
- `POST /news/v2/feeds/discover` with a site URL: returns candidate feeds. It does not subscribe to them.
- `POST /news/v2/feeds/import`: accepts an OPML body. Returns counts and URLs for added, skipped and invalid feeds.
- `GET /news/v2/feeds/export`: returns OPML 2.0.

**Watches** (writes need the admin key)
- `GET /news/v2/watches`, each with its unseenCount.
- `GET /news/v2/watches/{id}`.
- `POST`, `PUT`, `PATCH` and `DELETE /news/v2/watches/{id}`. Create and update trigger a backfill. The response includes the resulting match count.
- `GET /news/v2/watches/{id}/matches?unseenOnly=&page=&size=`: articles with their story info, newest first.
- `POST /news/v2/watches/{id}/seen`: sets last-seen-at to now. Note: "seen" is a household-wide reading state, but it is a write, so it also needs the admin key. This is revisited if the kiosk needs to mark watches as seen.

**Live stream**
- `GET /news/v2/stream`: `text/event-stream` with these events:
  - `headline` (a newly inserted article, as a new story or as an addition to one; never for edits)
  - `breaking` (a story became breaking)
  - `watch-match` (watch id, watch name, article summary; also fired when an edited article newly matches)
  - a heartbeat comment

**Errors**
- Errors use `application/problem+json` with standard fields plus a `code`, for example `FEED_NOT_FOUND`, `FEED_URL_CONFLICT`, `SOURCE_NOT_FOUND`, `SOURCE_MERGE_INVALID` (e.g. merging a source into itself), `FEED_INVALID`, `WATCH_NOT_FOUND`, `WATCH_NAME_CONFLICT`, `VALIDATION_FAILED`, `ADMIN_KEY_REQUIRED`, `POLL_IN_PROGRESS`, `OPML_INVALID`.
- Validation errors list the failing fields.

**Docs**
- OpenAPI JSON and Swagger UI are exposed through springdoc.

### Configuration (environment variables with defaults, bound to validated properties)

| Setting | Default |
|---|---|
| Database URL, username, password | required |
| Admin API key | required, minimum length enforced |
| Poll cron | every 15 minutes |
| Fetch connect timeout | 5s |
| Fetch read timeout | 15s |
| Max fetch body | about 5 MB |
| Fetch retries | 2 |
| User-Agent | — |
| Retention days | 30 |
| Retention cron | daily, early morning |
| Purged-list grace period | 30 days |
| Tracking parameters removed from links | `utm_*`, `fbclid`, `gclid`, `mc_cid`, `mc_eid`, `cmpid`, `ref`, `at_*` (extendable) |
| Cluster window | 24h |
| Similarity threshold | 0.5 |
| Breaking min sources | 3 |
| Breaking window | 60m |
| Breaking expiry | 3h |
| SSE heartbeat interval | 25s |
| SSE max connections | 20 |
| OTLP base URL (Argus appends `/v1/traces`) | `http://tempo:4318` |
| Trace sampling probability | 1.0 |
| Log format | Logstash JSON in deployed environments, plain text locally |
| Failing-feed threshold | 3 consecutive failures |

Spring Cloud Config is **not** adopted. Configuration stays local, overridden by environment variables. A local-development profile file stays git-ignored.

### Build, CI and container

- **Jenkinsfile → shared library.** Replace the hand-written pipeline with a thin consumer of `jenkins-shared-lib`. It calls the `standardSpringSnapshotPipeline` step with an explicitly empty Docker repository path (`dockerRepoPath: ''`), so the image is published as `argus:<tag>`. The shared pipeline owns every stage:
  - Checkout
  - TruffleHog secrets scan
  - Build details (version check)
  - hadolint Dockerfile lint
  - `mvn clean verify`
  - SonarQube with quality gate
  - OWASP dependency-check (CVSS ≥ 4 fails the build)
  - Multi-arch buildx image for `linux/amd64` and `linux/arm64`
  - Trivy image and base-image scans
  - SBOM generation and publishing to Dependency-Track
  - Push to the Nexus snapshot registry
  - Notifications

  No pipeline logic stays in this repository.
- **Shared-library change (prerequisite).** Today every pipeline in `jenkins-shared-lib` builds `<dockerRepoPath>/<artifactId>` and treats an empty `dockerRepoPath` as unset, falling back to `common`, so a bare image name is impossible. The change:
  - Move image-path construction into one shared helper.
  - An **omitted** `dockerRepoPath` keeps the `common` default; an **explicitly empty** one means no path prefix.
  - All six pipelines that build images use the helper: Spring, Python, React and Next.js, snapshot and release.
  - Existing consumers (weather-service, proxy and the others) keep producing exactly the same image names.
  - The shared library's tests cover the omitted, empty and set cases.
- **What the service must do to fit that pipeline:**
  - **Version:** the pom version must end in `-SNAPSHOT` (e.g. `0.1.0-SNAPSHOT`). The snapshot pipeline aborts otherwise. Release builds belong to the shared `standardSpringReleasePipeline` and are out of scope here.
  - **Build & Test:** runs `clean verify` inside the shared Maven container (Maven 3.9 on Temurin 21). Wire Surefire (unit) and Failsafe (Testcontainers ITs) so that one `verify` runs both and produces the JaCoCo XML report for Sonar.
  - **Testcontainers in CI:** the shared Maven step already points Testcontainers at the Docker socket proxy, disables Ryuk and overrides the container host. The pipeline's post step prunes leftover Testcontainers containers. So:
    - The integration tests must not rely on Ryuk, on bind-mounting the Docker socket, or on container reuse. Testcontainers reuse is not used.
    - They must reach containers through the mapped host and port Testcontainers reports, never through hardcoded `localhost`.
  - **Sonar:**
    - The shared step runs the scan and passes the branch name. The pom still supplies the organisation, project key and coverage settings.
    - Correct the JaCoCo XML report path. It currently points at a nested `target/target` path.
    - Update the coverage exclusions for the new package layout (configuration classes, the application class, plain records).
  - **Dependency-check:** a file named exactly `suppressions.xml` at the repository root is picked up automatically. Add one only if a Boot 4 transitive dependency produces a vetted false positive.
  - **Secrets scan:** the repository must stay free of credentials. Personal local config lives in git-ignored files outside the jar. The old local profile holding the NewsCatcher key is deleted.
- **Dockerfile.** It must pass hadolint at the `warning` threshold and build for both amd64 and arm64:
  - Use the same hardened multi-arch base image family as weather-service: Corretto 21 Alpine from the internal hardened-images registry.
  - Add a non-root user.
  - Add an explicit exec-form entrypoint that runs the jar. The current image has none.
  - Add an exec-form container `HEALTHCHECK` that runs a tiny Java probe class against the Actuator liveness endpoint. The hardened base has no shell, wget or curl.
- PostgreSQL provisioning on the host is a **prerequisite**: database, user, the `unaccent` extension (which needs the right privileges, or pre-creation by a DBA), and network reachability from the `argus` container.

## Testing Decisions

### What makes a good test here

- Tests exercise behaviour through each module's public interface: inputs in, outputs or observable side effects out. They do not assert on private methods, internal collaborators or the exact SQL text.
- Deep modules are tested as plain JUnit 5 with no Spring context, so they run in milliseconds and can cover many edge cases.
- Fixtures are real data where possible: feed XML captured from actual outlets (trimmed), real OPML exports, and real HTML pages for discovery.
- Anything involving time takes an injected `Clock`, so tests are deterministic.
- Database behaviour is tested against real PostgreSQL. Full-text search, generated columns, array columns, `unaccent`, upserts and cascades all behave differently on H2.
- Tests follow Arrange-Act-Assert and have names that describe the behaviour ("returns not-modified when server responds 304").
- Target: at least 80% line coverage overall, enforced by JaCoCo and reported to Sonar. Configuration classes, the application class and plain records are excluded.

### Modules under test

1. **Unit (no Spring)**
   - **Feed parser**:
     - RSS 2.0 with `media:thumbnail` (BBC style)
     - WordPress RSS with `content:encoded` and `dc:creator`
     - Atom 1.0
     - RSS 1.0 (RDF)
     - Entries without GUID or date; Atom entries with separate published and updated times
     - The feed's self link and site link are exposed
     - Relative links
     - HTML-heavy descriptions (excerpt cleaning and truncation)
     - Image priority order
     - Malformed XML (typed failure)
     - Wrong encoding declaration
   - **Link normaliser**:
     - Each tracking parameter in the list is removed; identifying parameters (`?p=123`, `?id=`) are kept.
     - Remaining parameters are sorted, so their order doesn't matter.
     - Scheme, `www.`, `m.`, case, fragment and trailing slash are folded.
     - Each AMP variant is folded (`amp.` host, `/amp` suffix, `amp=1`, `outputType=amp`).
     - Applying it twice gives the same result.
   - **Source resolver**: subdomains collapse to the registrable domain; multi-part public suffixes are handled (`bbci.co.uk`, `zambianfootball.co.zm`); the site link is preferred over the feed URL; an explicit source id wins.
   - **Entry dedup resolver**, one test per rule:
     - A GUID match is an update.
     - A changed GUID with the same link is an update, and the GUID key is replaced.
     - GUID and link pointing at different articles: the GUID wins.
     - Homepage links are never used for matching.
     - A link shared by several GUIDs in one download is never used for matching.
     - Duplicates within one download collapse to the latest update.
     - The same content from another feed of the source is link-to-feed-only.
     - A content-hash change is reported as changed; the same hash as unchanged.
     - An unmatched entry older than the cutoff is skipped.
     - A recently purged entry with no update is skipped.
     - A recently purged entry with a later effective time, or a different hash, is an insert.
     - Undated entries use the fetch time; future dates are capped.
   - **Story clusterer**:
     - Similar titles join the same story; dissimilar ones don't.
     - Behaviour at the threshold boundary.
     - Stories outside the window are not candidates.
     - Breaking needs distinct sources, so two articles from one feed, or from two feeds of the same source, don't count.
     - Breaking needs the burst inside the window.
     - Breaking expires.
     - Stop-words and punctuation don't create false matches.
   - **Watch matcher**:
     - Case and accent insensitivity
     - Whole-word matching ("Arsenal" vs "arsenals")
     - Multi-word phrases
     - Excludes override includes
     - Matching on categories
     - Empty or blank inputs
   - **OPML codec**: nested outlines, OPML 1.0 vs 2.0, missing attributes, a round trip (export then import gives the same feeds), malformed input.
   - **Feed discoverer**: multiple alternate links, relative hrefs, Atom and RSS types, no links, input that is already a feed.
2. **Feed fetcher (stubbed HTTP server)**: 200 with validators captured, 304, 404 (not retried), 503 (retried, then failed), timeout, body larger than the limit, redirect followed (final URL reported, and 301/308 flagged as permanent while 302/307 are not), redirect limit exceeded, non-http scheme rejected, User-Agent and conditional headers sent.
3. **Integration (Testcontainers PostgreSQL via a `@Bean` container and a `DynamicPropertyRegistrar`, Liquibase applied, seed context off)**:
   - **Migrations**: Liquibase applies cleanly to an empty database.
   - **Ingestion pipeline**:
     - The first ingest inserts.
     - Re-ingesting the same feed creates no duplicates and updates changed titles.
     - The same article in two feeds of one source is stored once, with two article–feed links, and topic filters find it under both topics.
     - Two feeds of one source ingested **concurrently** with overlapping items produce no duplicates (advisory lock), and a forced conflicting insert is absorbed by `ON CONFLICT`.
     - A refresh of one feed during a scheduled poll produces no duplicates.
     - An edited article is updated in place, keeps its story, fires no `headline` event, and gains a new watch match plus a `watch-match` event when its new headline matches.
     - Purge followed by re-ingest of the same unchanged entry: nothing is re-inserted and no events fire.
     - Purge followed by re-ingest of an updated entry: inserted once, with events.
     - A 304 leaves articles unchanged and updates last-fetched-at.
     - A failure increments consecutive-failures and records the error, while other feeds still ingest.
     - Success resets the failure count.
     - New articles get story assignment across two feeds.
     - The breaking flag is set when the third distinct source joins a story, and is not set by a second feed of an existing source.
     - Watch matches are created at ingest.
     - Domain events are published only after commit.
   - **Polling orchestrator**: a concurrent refresh-all is rejected with poll-in-progress; disabled feeds are skipped.
   - **Watch backfill**: creating a watch matches existing articles; editing terms recomputes them; the unseen count and mark-as-seen work.
   - **Search**: ranking prefers title matches; stemming ("transfers" finds "transfer"); accent-insensitive; phrase and minus syntax; combined with filters.
   - **Headlines and breaking queries**: collapse to one entry per story, respect the time window and filters.
   - **Retention**: old articles are purged and recorded in the purged list; their watch matches and feed links cascade; empty stories are removed; recent data is untouched; purged-list rows older than the grace period are deleted.
   - **Feed registry**:
     - Creating a feed whose URL differs only by scheme, `www.` or trailing slash gives 409.
     - A URL that redirects to an existing feed gives 409.
     - A feed whose self link equals an existing feed's gives 409.
     - A new feed on a known domain joins the existing source; an explicit `sourceId` overrides it.
     - A permanent redirect during polling updates the stored URL; a redirect colliding with another feed disables the feed with a "duplicate of" error.
   - **Source merge and feed move**: duplicate articles collapse to the oldest; feed links and watch matches are folded in without duplicate rows; story source counts are recalculated; the empty source is removed.
   - **Feed deletion**: removes the feed's article links, and deletes only the articles left with no other feed, cascading to their matches.
4. **API slice tests (Web MVC test slice, service layer mocked)**:
   - For every controller: parameter validation and limits (400 problem responses with field errors), problem-detail shape and codes for 404, 409, 422 and 409-poll-in-progress, and the paging JSON shape.
   - Admin key: missing or wrong key on writes gives 401 with `ADMIN_KEY_REQUIRED`; correct key passes; `GET` needs no key.
   - The OPML import/export content types.
   - The SSE endpoint's content type and initial heartbeat.

5. **Telemetry**:
   - A metrics test runs an ingest against a stub feed and asserts that every catalogued meter exists with its expected tags in the meter registry.
   - The dashboard validator runs in the build and passes, and a deliberately misspelled metric makes it fail.
   - A test asserts that log output is valid JSON carrying `traceId` and the feed MDC keys.

### Prior art

- Hymenaios is the prior art for observability: its versioned dashboard, its validator script with a metric allow-list mirroring a metric-names class, its Prometheus task-discovery job and Promtail JSON pipeline, and its OpenTelemetry/OTLP configuration.

- The current service's tests use a JSON fixture under test resources and Mockito-mocked collaborators. Keep the fixture-file approach (now with XML, OPML and HTML fixtures) and drop the mocks of the HTTP client in favour of a stub server.
- weather-service already uses Boot 4's Web MVC test starter for controller slice tests, and is the reference for Boot 4 test wiring.
- Testcontainers is new to this codebase. Provide the container as a `@Bean` with a `DynamicPropertyRegistrar` that sets `argus.db.*`, because `@ServiceConnection` would fill `spring.datasource.*` and leave the validated `argus.db.*` blank. Use a shared integration-test base, or a reusable container configuration, so that the context and container start once per test run. Testcontainers reuse is not used. In CI the shared library's Maven step runs Testcontainers through the Docker socket proxy with Ryuk disabled (see Build, CI and container).

## Out of Scope

- Grafana alert rules. Thresholds are shown on dashboard panels only; alerting is a later piece of work.
- Applying the Prometheus and Promtail config to the running stack, and deploying the dashboard through provisioning. The repository supplies the files and the instructions; applying them is done in the docker/grafana stack.
- Any change to frontend-app, weather-service or the proxy/gateway. Argus is a standalone stack, so the dashboard's old `/news/**` route stops working; the redesigned frontend and any gateway that fronts Argus are wired up in their own projects.
- Keeping v1 compatible. `/news/v1/headlines` and the `ApiResponse` envelope are removed, not deprecated.
- User accounts, per-person watches or per-person read state. The service is single-household.
- External push notifications (webhooks, ntfy, Discord, email, mobile push). Only the pull API and the SSE stream are in scope.
- Scraping full article bodies from outlet websites, paywall handling, or readability extraction. Only feed-provided content is stored.
- ML/NLP clustering, embeddings, sentiment, entity extraction, translation or summarisation. Clustering is lexical, using token overlap.
- Cross-outlet deduplication beyond story clustering. Each outlet's article is stored. Clustering groups them.
- Running several instances or distributed locking.
- Provisioning PostgreSQL, compose stacks or host-level deployment wiring, beyond documenting the prerequisites.
- Spring Cloud Config adoption.
- Upgrading to a Java version newer than 21. The shared pipeline's Maven image is Temurin 21.
- Changes to `jenkins-shared-lib` beyond the optional image-path prefix described above, and release (non-snapshot) builds through `standardSpringReleasePipeline`.
- Per-feed poll intervals. One global schedule applies.

## Further Notes

- **Rough volume:** about 25–40 feeds × about 50 entries a day is roughly 1–2k articles a day, or 30–60k retained over 30 days. That fits easily in a small Postgres on home-lab hardware, and synchronous watch backfill and in-memory clustering candidate sets stay cheap at this size. Revisit if the number of feeds grows by an order of magnitude.
- **Deduplication scope:** deduplication is per source (one outlet). The same wire story run by different outlets is deliberately kept as separate articles and grouped into one story; that's what powers "covered by N sources" and breaking detection. The recently-purged list only exists to stop purged items that are still in a feed from returning as "new". Its grace period should exceed how long outlets keep items in their feeds; 30 days is generous for news feeds.
- **Security notes:**
  - Feed and discovery URLs are admin-supplied, so the fetcher's http/https-only rule, size cap, timeouts and redirect limit are the main safeguards. Private-network targets are not blocked, because some feeds may be self-hosted on the LAN.
  - The admin key is compared in constant time, never logged, and checked for a minimum length at startup.
  - The NewsCatcher API key in the untracked local profile should be revoked with NewsCatcher and deleted once the migration lands.
- **Clustering accuracy:** expect some false merges or splits at first. Thresholds are configurable so they can be tuned against real data. A future improvement could swap in Postgres `pg_trgm` similarity or embeddings behind the same clusterer interface.
- **Breaking-news tuning:** with UK, Canadian and Zambian outlets mixed, a regional event may never reach 3 distinct sources. A per-country breaking threshold is a possible later refinement.
- **Arsenal coverage:** the BBC Football feed in the seed list gives good Premier League coverage out of the box. arsenal.com's RSS endpoint returned 503 during research. Use feed discovery to find a working club or fan-site feed after launch.
- **Spring Boot 4 notes for the implementer:**
  - The Web starter has been renamed to the Web MVC starter.
  - Test slices now come from per-technology test starters.
  - Jackson 3 uses a new package namespace.
  - `@Retryable` and `@ConcurrencyLimit` are now in core Spring Framework 7 and are switched on with `@EnableResilientMethods`.
  - Testcontainers service connections need the Spring Boot Testcontainers module.
  - Check current docs (Context7) for exact artifact IDs and versions when implementing.

### Appendix: seed feeds (checked on 2026-10-01)

Feeds that returned valid RSS on 2026-10-01:

| Outlet | Feed URL | Country | Topic |
|---|---|---|---|
| BNN Bloomberg | https://www.bnnbloomberg.ca/arc/outboundfeeds/rss/?outputType=xml | CA | BUSINESS |
| CityNews Toronto | https://toronto.citynews.ca/feed/ | CA | NEWS |
| The Globe and Mail (Canada) | https://www.theglobeandmail.com/arc/outboundfeeds/rss/category/canada/ | CA | NEWS |
| CBC Top Stories | https://www.cbc.ca/webfeed/rss/rss-topstories | CA | NEWS |
| CBC Sports | https://www.cbc.ca/webfeed/rss/rss-sports | CA | SPORT |
| Global News | https://globalnews.ca/feed/ | CA | NEWS |
| Sportsnet | https://www.sportsnet.ca/feed/ | CA | SPORT |
| BBC News | https://feeds.bbci.co.uk/news/rss.xml | GB | NEWS |
| BBC World | https://feeds.bbci.co.uk/news/world/rss.xml | GB | WORLD |
| BBC Football | https://feeds.bbci.co.uk/sport/football/rss.xml | GB | SPORT |
| Daily Mail News | https://www.dailymail.co.uk/news/index.rss | GB | NEWS |
| The Mirror News | https://www.mirror.co.uk/news/?service=rss | GB | NEWS |
| The Sun | https://www.thesun.co.uk/feed/ | GB | NEWS |
| Lusaka Times | https://www.lusakatimes.com/feed/ | ZM | NEWS |
| Zambia24 | https://zambia24.com/feed/ | ZM | NEWS |
| Mwebantu | https://www.mwebantu.com/feed/ | ZM | NEWS |
| Zambian Business Times | https://zambianbusinesstimes.com/feed/ | ZM | BUSINESS |
| Zambian Football | https://www.zambianfootball.co.zm/feed/ | ZM | SPORT |
| Lusaka Star | https://www.lusakastar.com/feed/ | ZM | NEWS |
| Farmers Review Africa | https://www.farmersreviewafrica.com/feed/ | ZM | BUSINESS |

To verify before seeding: The Independent (https://www.independent.co.uk/news/uk/rss returned 200, but the format could not be confirmed).

Dropped for now (no working feed found at the guessed URL; use feed discovery later): CTV News, TSN, theScore, Daily Nation Zambia, Rainbow News Zambia, Zambian Observer (Cloudflare 530).
