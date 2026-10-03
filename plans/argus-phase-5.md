# Phase 5 execution plan: feed identity and source merge

> Parent plan: `plans/argus.md` → Phase 5. PRD: `docs/prd-argus.md` (stories 41, 42, 46, 47, 48).
> Previous: `plans/argus-phase-4.md`, `plans/argus-logging.md` (logging contract, binding for this phase).
> Built here with Sonnet implementer subagents; reviewed by the six-reviewer gate.

## 0. Context

`main` at 9a911a8 is green: 812 unit tests and 173 Testcontainers ITs, 0 failures, 0 skipped (`clean verify`, 2026-10-03).

What exists:
- `FeedService.create` rejects only an **exact** cleaned URL (`inserter.findIdByUrl`). It does not check the redirect or the self link.
- `PATCH /feeds/{id}` takes only `{enabled}`. There is no `PUT`, so story 42 was never built.
- `FeedFetcher` already reports `finalUrl` and `permanentTarget`, the end of a leading 301/308 chain. `FetchResult.NotModified` carries it, but `FeedLoader.Loaded.Parsed` drops it and `CreateLoaded.Created` lacks `finalUrl`. Nothing applies redirects.
- `ParsedFeed.selfLink` is parsed, but there is no `feed.self_url` column.
- `Feed.url` and `Feed.source` are `updatable=false`, so all writes to them go through JdbcClient.
- `SourceLock.acquire(sourceId)` takes the two-int xact advisory lock in namespace 4100. Its propagation is MANDATORY.
- `ArticleRekeyer` collapses to the lowest id. Its link fold copies `first_seen_at` but not `article_feed.content_hash`, which costs one missed edit, healed by `backfillFeedHash`.
- `ErrorCode.SOURCE_MERGE_INVALID` (422) exists but is unused.
- Stories (phase 6), watches (phase 9) and purged articles (phase 11) don't exist yet.

Verified facts:
- Postgres unique indexes treat NULLs as distinct, so a plain unique index on nullable `self_url` allows many NULLs. Liquibase `createIndex` has no `where` attribute, and no partial index is needed.
- `MetricNames.Tags.ALL` has `kind` and not `via`. The master plan's allowed-tag list is closed, so the conflict counter uses `kind`.
- **Stale-source race.** `ArticlePersister.persist` locks `feed.getSource().getId()`, which was loaded before the fetch. Once merge and move exist, an ingest overlapping one would write into the old, possibly deleted, source. `FeedService.delete` reads `source_id` before locking.
- A null `article_feed.content_hash` makes the resolver return `Unchanged(backfillFeedHash=true)`, which writes the hash.

## 1. Decisions

Owner, 2026-10-03:
1. **Feed editing is a PATCH only.**
   - `PATCH /feeds/{id}` takes `enabled`, `name`, `topic`, `sourceId` and `url`, all optional, and at least one is required.
   - There is no `PUT /feeds/{id}`. Remove it from the master plan's route list.
2. **Narrow source-key rule** (automatic resolution only; an explicit `sourceId` is never checked):
   - Compute the site-link key as today.
   - If no source has that key yet, create it (as today).
   - If a source with that key exists, attach to it only when one of these vouches for it:
     - the feed host's key,
     - the self link's key, or
     - the registrable domain of a host already used by one of that source's feeds.
   - Otherwise use the feed host's key (find or create) and log WARN that the site link was not trusted.
3. **Moving a feed copies shared articles.** An article linked to F and to feeds staying in S is copied into T in the move transaction:
   - The copy has the same keys, content, `fetched_at` and `effective_at`.
   - F's link moves to the copy, and F's link is removed from the S original.
4. **An emptied source is deleted after a feed move** when it has no feeds and no articles left, in the same transaction, and the audit log says so. Merge always deletes the source.
5. **An auto-disable logs WARN.** Merges, moves and applied redirects log INFO audit lines.
6. **Built here** with Sonnet implementers, local commits only.

Planning defaults (veto at approval):

7. **Identity.**
   - `UrlIdentity.fold(cleanedUrl)` works on `StoredUrls.clean` output:
     - drops the scheme;
     - lowercases the host and strips one leading `www.`;
     - drops the default port;
     - strips trailing `/` from the path, so an empty path equals `/`;
     - keeps the query and its order;
     - drops the fragment.
   - The fold is a comparison only, never stored or logged.
   - A feed's identity set is {fold(url), fold(self_url)}.
   - A candidate conflicts when any of its identities equals any identity of another feed.
   - The lookup is in Java over `SELECT id, url, self_url FROM feed`; the feed list is admin-curated and small.
   - The DB backstops are `uq_feed_url` and a new `uq_feed_self_url`.
8. **The identity lock** is `pg_advisory_xact_lock(4101, 0)`. It serialises check-then-write for create, URL patch, redirect-apply and the self-url backfill, each in a short transaction.
9. **Conflict order is entered → redirect (final URL) → self_link.**
   - The entered URL is checked before any download.
   - The first match wins, gives 409 `FEED_URL_CONFLICT` with `existingFeedId`, and increments `argus.feed.identity.conflict{kind}`.
10. **`self_url` is the cleaned self link and is always stored when present.**
    - On create and on a URL patch, it is set from the download.
    - On a successful poll where it is null or has changed, it is set if it doesn't conflict. On a conflict it is left alone and logged INFO, and the feed is never disabled for that.
    - This lets the 20 seeded feeds take part in self-link checks.
11. **A URL patch:**
    - downloads once (no retries) without holding a connection;
    - runs the three checks, excluding the feed's own id;
    - updates `url`;
    - sets `self_url` from the download;
    - clears `etag` and `last_modified`.

    Health fields are kept. An invalid feed gives 422 `FEED_INVALID`, as on create.
12. **Duplicate groups for merge and move:**
    - **Pass 1:** equal `guid_key`.
    - **Pass 2:** among unpaired articles, an equal non-null `link_key` held by exactly one article on each side. It is not a root link (`LinkCleaner.isRoot`) and not either source's homepage key.
    - **Survivor:** oldest by `(fetched_at, id)`. In a merge the survivor may be on either side; in a move it is always the T article, since the copy is new.
    - Losers are folded and then deleted before any `source_id` update, so no `uq_article_source_guid_key` collision can occur and no temporary keys are needed.
13. **Link fold:**
    - Group by `(survivor, feed_id)` first. Otherwise Postgres raises "ON CONFLICT DO UPDATE cannot affect row a second time".
    - Keep `MIN(first_seen_at)`, and the `content_hash` of the earliest-seen row.
    - On conflict: `first_seen_at = LEAST(…)`, `content_hash = COALESCE(existing, excluded)`.
    - Extract this into `article/ArticleCollapser` (JDBC `Connection`, no Spring), and let `ArticleRekeyer` use it. That also fixes the missing hash copy in the rekey path.
14. **Lock order:** both source locks in ascending id. Every other path takes at most one source lock, plus the identity lock, which is never held together with a source lock.
15. **Stale-source fix.**
    - `ArticlePersister.persist` and `FeedService.delete` re-read `feed.source_id` after taking the lock.
    - On a mismatch the transaction rolls back and `persist` retries once with the new source.
    - A second mismatch fails the ingest with reason `source_changed`, which does not count against health. The `IngestFailedException` path uses `recordFailure`, so skip that for this reason.
16. **The redirect hook is in `FeedIngestService.refreshLoaded`**, before persist, for `Parsed` and `NotModified`.
    - **Same URL:** no-op when `StoredUrls.clean(permanentTarget)` equals the stored URL.
    - **No conflict (including an http→https upgrade with an equal fold):**
      - `UPDATE feed SET url = :new WHERE id = :id AND url = :old`;
      - `argus.feed.redirect{outcome=permanent_applied}`;
      - INFO.
      - A `DuplicateKeyException` is treated as a conflict.
    - **Conflict with feed N:**
      - new `FeedHealthUpdater.recordDuplicate(feedId, N, now)` sets `enabled=false`, `last_error='duplicate of feed N'` and `last_fetched_at`, without incrementing failures;
      - `permanent_conflict`;
      - WARN;
      - the report is `failed(duplicate_feed)`;
      - no persist and no `recordSuccess`.
17. **Merge API.**
    - `POST /sources/{id}/merge` with body `{"targetSourceId": n}`.
    - 200 `{sourceId, targetSourceId, feedsMoved, articlesMoved, articlesCollapsed, linksFolded}`.
    - Errors:
      - 422 `SOURCE_MERGE_INVALID` when the ids are equal;
      - 404 `SOURCE_NOT_FOUND` for either id;
      - 400 for a missing or non-positive target;
      - 401 without the key.
    - A feed move through PATCH returns the updated feed.
18. **No speculative hooks for phases 6, 9 and 11.** A one-line Javadoc on `ArticleCollapser` names where story counts, watch-match folding and purged rows will go.
19. **OpenAPI is still deferred to phase 13**, a recorded deviation as in phase 4.
20. **Deploy.**
    - One additive changeset `10-add-feed-self-url`: a nullable `self_url text` and a unique index `uq_feed_self_url`.
    - The normal start-first Jenkins deploy, which a rollback to the phase 4 image tolerates.
    - No pg_dump is required, but take one if convenient.

## 2. Ground rules (verbatim into every subagent prompt)

```
GROUND RULES (non-negotiable)
1. No real infrastructure. Never contact: any feed URL on the internet, nexus-repo.jmeighty.com, Grafana, Prometheus,
   Loki, Tempo, Sonar, Jenkins, the Swarm node "mantra", the shared Postgres or PgBouncer. Testcontainers and the
   in-repo FeedStubServer are fine. If something seems to need real access, STOP and report.
2. Git: work only in the worktree/branch you were given. Stage explicit paths only (never `git add -A`, `git add .`,
   `git commit -a`). Never touch the untracked owner files `interactive_visual_im_b13838ce8d7ace99.html` and
   `.DS_Store`. No push, reset, rebase, amend, stash or history rewrite; no checkout or switch of other branches.
   Commit only with the exact message given, conventional style, NO trailers (no Co-Authored-By, no Signed-off-by).
3. Maven: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` then
   `./mvnw -B -s /tmp/argus-central-settings.xml <goals>`. Never use ~/.m2/settings.xml.
4. TDD: write the test first and show it fails for the right reason. For every guard (lock, conflict check, re-read,
   dedup rule) show the test fails with the guarded code removed, then restore it.
5. Code style: match the surrounding code (JdbcClient SQL text blocks, Lombok @Slf4j, SLF4J key-values via LogKeys,
   records, sealed results, no mutation of inputs). Essential comments only. Functions < 50 lines, files < 400.
6. Logging contract: plans/argus-logging.md. Redacted URLs only (HttpUrls.redact); never folded keys, GUIDs, links,
   article content or the admin key. Audit lines carry ids and counts as key-values.
7. Sonar rules that recur here: S1192 (literal of 5+ chars used 3×), S2259 (repeated nullable accessor;
   Optional<Integer>.orElse unboxing), S5998, S5853/S5838 (AssertJ style), S1186, S1117, S1948, S3776, S5961.
8. Report honestly: list commits (hash + message), the exact verify command and its test counts, and anything
   skipped or not done.
```

## 3. Work packages

Branch `feat/argus-phase-5` from `main`. Each wave-1 package works in its own git worktree on its own branch off the WP-0 commit, and I merge them into `feat/argus-phase-5` (`merge: phase 5 WP-x`).

### WP-0: foundation (serial, before everything)
- `db/changelog/changes/10-add-feed-self-url.yaml`:
  - `addColumn feed.self_url text`;
  - `createIndex uq_feed_self_url unique` on `self_url`;
  - rollback drops the index, then the column.
- Include it in the master changelog.
- `Feed.selfUrl` with its getter, `updatable=false`.
- `MetricNames`:
  - `FEED_REDIRECT = "argus.feed.redirect"`
  - `FEED_IDENTITY_CONFLICT = "argus.feed.identity.conflict"`
  - `SOURCE_MERGE = "argus.source.merge"`
  - `ARTICLE_COLLAPSED = "argus.article.collapsed"`
- `LogKeys`: `TARGET_SOURCE_ID`, `FEEDS_MOVED`, `ARTICLES_MOVED`, `ARTICLES_COPIED`, `ARTICLES_COLLAPSED`, `LINKS_FOLDED`, `SOURCE_DELETED`, `NEW_URL` (redacted), `KIND`. Reuse existing keys where one exists.
- `FailureReasons`: `DUPLICATE_FEED = "duplicate_feed"`, `SOURCE_CHANGED = "source_changed"`.
- Tests: `SchemaIT` (column, index, many NULLs allowed, a duplicate non-null rejected) and `LiquibaseBaselineIT` if it lists changesets.
- Commit: `feat: add feed self_url and phase 5 metric and log names`.

### Wave 1 (parallel, disjoint files)

**WP-A: identity core**
- **Owns:**
  - `feed/identity/UrlIdentity`
  - `feed/identity/IdentityKind` (`entered`, `redirect`, `self_link`, with `tag()`)
  - `feed/identity/FeedIdentityLock` (4101)
  - `feed/identity/FeedIdentityRegistry`:
    - `Optional<Conflict> findConflict(List<Candidate>, @Nullable Long excludeFeedId)`
    - `recordSelfUrl(feedId, selfLink)`
  - `feed/identity/FeedRedirectApplier`: sealed `NoChange | Applied | Conflict`, decision 16
  - `feed/identity/FeedIdentityTelemetry`: the redirect and conflict counters
  - `feed/FeedInserter` and `feed/NewFeed`: `self_url`, bare `ON CONFLICT DO NOTHING`
  - `feed/health/FeedHealthUpdater.recordDuplicate`
  - `source/SourceResolver` and its lookup support: decision 2. Pure key logic stays in `SourceResolver`; the existing-source/vouching query goes in `SourceService`, e.g. `resolveAutomatic(siteLink, selfLink, feedUrl)`
  - their tests
- **Tests:**
  - `UrlIdentityTest`: every fold rule, query kept, idempotence.
  - `FeedIdentityRegistryIT`:
    - each `kind`;
    - excluding self;
    - two concurrent creates of fold-equal URLs: one wins.
  - `FeedRedirectApplierIT`: no-change, applied, http→https applied, conflict, duplicate-key race.
  - `FeedHealthUpdater` `recordDuplicate` IT: failures not incremented.
  - `SourceResolverTest` and `SourceServiceIT`:
    - BBC stays `bbc.co.uk`;
    - a stray site link naming an existing source with no voucher gets the host key and a WARN;
    - a new site-link key is created;
    - an explicit `sourceId` is untouched.
- **Commit:** `feat: feed identity folding, conflict registry and redirect applier`.

**WP-C: collapse and merge**
- **Owns:**
  - `article/ArticleCollapser`: fold and delete, decision 13
  - `article/ArticleDuplicates`: pure pairing, decision 12
  - `migration/ArticleRekeyer` (delegates to the collapser)
  - `source/SourceMerger`:
    - `merge(sourceId, targetId)`
    - `moveFeed(feedId, targetId)`, implementing decisions 3, 4, 14 and 18
  - `source/MergeSourceRequest`
  - `source/SourceMergeResponse`
  - `source/MergeTelemetry`: an Observation `argus.source.merge` with low-cardinality `type=merge|feed_move` and `outcome`; source ids as high-cardinality span attributes; `argus.article.collapsed{type}` incremented, including by 0, on every run
  - the merge mapping in `source/SourceController`
  - their tests
- **Tests:**
  - `ArticleDuplicatesTest`, one rule each: guid pass, link 1:1 only, root link and homepage guarded, survivor `(fetched_at, id)`.
  - `ArticleCollapserIT`:
    - two losers on one survivor and feed: no "affect row a second time";
    - the hash is copied;
    - `LEAST` `first_seen_at`.
  - `ArticleRekeyerIT` and `RekeyMigrationIT` green, plus a test that the hash is now copied.
  - `SourceMergeIT`:
    - overlap by guid and by link;
    - no duplicate `article_feed`;
    - the source is deleted;
    - self-merge 422, missing 404;
    - a forced failure rolls everything back;
    - opposite concurrent merges A→B and B→A: one completes, the other gets 404, no deadlock;
    - a merge concurrent with an ingest on each source.
  - `FeedMoveIT`:
    - an exclusive article moves;
    - a shared article is copied and the original keeps the other feeds' links;
    - a matching T article absorbs it;
    - the emptied source is deleted, and a non-empty source is kept.
  - `SourceControllerTest` (slice): 200 shape, 400, 401, 404, 422.
  - Unit test: lock order min then max (`InOrder`).
- **Commit:** `feat: source merge and feed move with duplicate collapse`.

### Wave 2

**WP-B: integration** (after A and C are merged)
- **Owns:**
  - `ingest/FeedLoader`: `Parsed` gains `permanentTarget`; `Created` gains `finalUrl`
  - `ingest/FeedIngestService`:
    - redirect hook (decision 16);
    - self-url backfill (decision 10);
    - `source_changed` with no health penalty
  - `ingest/ArticlePersister`: re-read and retry once (decision 15)
  - `feed/api/FeedService`:
    - create uses identity checks and automatic source resolution;
    - patch per decisions 1 and 11, with a `sourceId` change delegating to `SourceMerger.moveFeed`;
    - delete re-reads under the lock
  - `feed/api/PatchFeedRequest`: all nullable, `isEmpty()`, `@Positive sourceId`, name 1–255 and not blank, url via `@AbsoluteHttpUrl`
  - `feed/api/FeedController`
  - their tests
- Order inside one PATCH:
  1. url, with download and checks;
  2. name, topic and enabled in one UPDATE;
  3. sourceId, through the merger.

  Each is its own transaction. Document that a failure in a later step leaves the earlier steps applied, or else validate everything up front. **Prefer up-front validation**: resolve the target source's existence and do the download before any write.
- **Tests:**
  - `FeedIdentityApiIT`, all giving 409 with `existingFeedId` and the right `kind`:
    - scheme, `www.` and trailing-slash variants;
    - a redirect to an existing feed;
    - a matching self link.
  - `FeedRedirectPollingIT`:
    - 301 and 308 update the URL; 302 and 307 don't;
    - a 304 after a 301 applies it;
    - a 301 onto another feed disables the feed with `duplicate of feed N`, no articles, gauge DISABLED and a WARN line.
  - `FeedPatchIT`:
    - each field;
    - empty body 400;
    - URL conflict 409;
    - invalid URL 422;
    - a `sourceId` move behaves like the merge for that feed.
  - `StaleSourceIT`: an ingest whose feed is moved mid-fetch lands in the new source, or reports `source_changed` without a failure increment.
  - `FeedLoaderTest` and `FeedIngestServiceTest` unit additions.
- **Commit:** `feat: identity checks on create and patch, redirects in polling, feed move via patch`.

**WP-E: catalogue, dashboard and meter ITs** (parallel with B; disjoint)
- **Owns:**
  - `MetricCatalogue` and `MetricCatalogueTest`, with four `MeterSpec`s:
    - `argus.feed.redirect` COUNTER with `source` and `outcome`
    - `argus.feed.identity.conflict` COUNTER with `kind`
    - `argus.source.merge` TIMER with `type` and `outcome`
    - `argus.article.collapsed` COUNTER with `type`
  - `grafana/dashboards/argus-observability.json`:
    - **Feed health row:** "Permanent redirects by outcome" and "Identity conflicts by kind".
    - **Deduplication row:** "Source merges: rate and p95" and "Collapsed articles by type".
    - New unique ids, and every later `gridPos.y` shifted.
  - `IngestMetricsIT.registerEveryCataloguedMeter` must trigger all four, or register them eagerly.
  - a new `IdentityMergeMetricsIT` asserting exact tags and counts.
  - `TraceExportIT` gains the merge span.
  - `AuditLoggingIT` gains the merge, move, redirect-applied INFO lines and the auto-disable WARN.
- E depends on the A and C telemetry classes being merged, and on B only for the redirect path in the ITs. If that blocks, E finishes after B.
- **Commit:** `feat: phase 5 meters, dashboard panels and telemetry tests`.

### Wave 3: WP-H docs (serial)
- `README.md`:
  - PATCH /feeds fields;
  - identity rules and 409 kinds;
  - redirects and auto-disable;
  - merge endpoint and move semantics;
  - narrow source-key rule;
  - recorded deviations: no PUT, OpenAPI deferred.
- `observability/README.md`: the new meters.
- `plans/argus.md`:
  - tick the phase 5 criteria;
  - drop `PUT /feeds/{id}` from routes;
  - note that the allowed tag is `kind` rather than `via`.
- **Commit:** `docs: document phase 5 feed identity, redirects and source merge`.

## 4. Gates

1. After WP-0 and after each wave, I verify:
   - `git log`, `git reflog`, `git status` and `git diff --stat` against the report;
   - a re-run of `clean verify`;
   - test counts and skips;
   - the riskiest diffs: the locks, the merge SQL and the stale-source retry.
2. After wave 3, the six-reviewer gate runs on `git diff main...feat/argus-phase-5`: security, comments, simplifier (report only), simplicity/DRY, correctness and conventions. Then triage with you, one fix commit (`fix: address phase 5 review`) and re-verify.
3. Before merge: push the branch (with your OK), run Jenkins, and compare the branch's Sonar issues with main's through the Sonar web API.

## 5. Verification and done

- `./mvnw -B -s /tmp/argus-central-settings.xml clean verify` is green: unit tests, ITs, dashboard validator and JaCoCo ≥ 80%. Test count rises from 985, with 0 skipped.
- Every acceptance criterion in `plans/argus.md` phase 5 maps to a named test above.
- **Gate B (live, after deploy, with you):**
  - `POST /feeds` with a scheme/`www.` variant of a seeded feed's URL: expect 409 `kind=entered` and nothing stored.
  - Merge a test source into another and check the response and the audit line in Loki.
  - Confirm `self_url` is backfilled for the seeded feeds after one poll.
  - Watch for `argus_feed_redirect_total` on the dashboard.
  - Import dashboard v6.
  - Take care of The Mirror (feed 13, 403 from the server): disable it. This is a carried-over item.
