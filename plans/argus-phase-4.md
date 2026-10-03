# Phase 4 execution plan: sources and full article deduplication

> Parent plan: `plans/argus.md` → Phase 4. PRD: `docs/prd-argus.md` (stories 42–45, 59, 68–75, 77, 108).
> Previous phases: `plans/argus-phase-1.md`, `plans/argus-phase-2.md`, `plans/argus-phase-3.md`.
>
> **Audience.** This plan is executed by an orchestrator model (Gemini Flash) that dispatches subagents. It is
> written to be followed literally. Where it gives code, SQL or YAML, use it as given. Where something is not
> covered, **stop and report** instead of guessing. Section 2 (ground rules) goes verbatim into every subagent
> prompt, together with that subagent's work-package section.

---

## 0. Context

### What already exists

The service is Argus, an RSS aggregator on Spring Boot 4.1.1 / Java 21. It runs on PostgreSQL 18 behind
PgBouncer in **transaction pooling** mode. Schema changes go through Liquibase 5.0.3, and Guava 33.7.2 provides
`InternetDomainName`.

Phases 1–3 are merged to `main`, deployed and verified live.

The baseline on `main` (commit e8a6823) is green:
- `clean verify` passes with **629 tests**: 532 unit tests and 97 Testcontainers ITs, 0 skipped.
- Coverage is about 99.7%.
- Sonar reports 0 issues.

The phase 4 starting point in code:

| Area | Today |
|---|---|
| `url/Links.clean` | Interim cleaner: trims, lowercases scheme and host, drops the fragment. It is used for article links **and** for `feed.url`, `feed.site_url` and `source.homepage_url`. |
| `ingest/EntryKeys` | Static. `guidKey` is the trimmed raw GUID, or else the cleaned link. `linkKey` is the cleaned link. Keys over 512 characters become `sha256:<hex>`. |
| `article/ArticleInserter` | `INSERT … ON CONFLICT (source_id, guid_key) DO NOTHING`, then an `article_feed` link. |
| `ingest/ArticlePersister` | `@Transactional`. Sorts entries by GUID key and inserts each one. There is no lock and no link fallback, and edits are ignored. |
| `source/*` | `SourceResolver.keyFor(siteLink, feedUrl)` (registrable domain). `SourceService.findOrCreate` upserts a source whose name equals its key. |
| `article` | No `content_hash` column. `/articles` takes only `page` and `size`. `ArticleResponse` has the source summary but no feeds. |
| Seeds | None. Prod has one feed, BBC News `https://feeds.bbci.co.uk/news/rss.xml`, under source key `bbc.co.uk`. |

### Verified facts (do not re-derive; do not contradict)

**Liquibase 5.0.3**
- With no contexts set at runtime, **every** changeset runs, including ones that have a context.
- `context: "!test"` means the changeset is skipped when the context `test` is active.
- The Boot property is `spring.liquibase.contexts`.
- `liquibase.Liquibase` methods `update(Contexts)` and `update(int changesToApply, Contexts, LabelExpression)` exist and are **not** deprecated. The `update(..., Writer)` overloads **are** deprecated: do not use them.
- `liquibase.change.custom.CustomTaskChange` has `execute(Database)`. `CustomChange` declares `getConfirmationMessage()`, `setUp()`, `setFileOpener(ResourceAccessor)` and `validate(Database)`. The class needs a public no-arg constructor.

**Guava `InternetDomainName`** maps every seed host to the key in §4.8. The one exception is `feeds.bbci.co.uk`, which maps to `bbci.co.uk`, so the BBC source key is set explicitly. `foo.blogspot.com` maps to `foo.blogspot.com` (private suffix), and `x.y.com.au` maps to `y.com.au`.

**Postgres advisory locks**
- `pg_advisory_xact_lock(int, int)` is released at commit or rollback, which is safe under PgBouncer transaction pooling.
- The two-int keyspace is separate from the single-bigint one.
- Project rule: no `SET` statements of any kind.

**Dashboard and metrics**
- The dashboard validator runs with **catalogue coverage on**. Every meter in `MetricCatalogue` must be referenced by a dashboard panel, otherwise `ArgusDashboardTest` fails. So catalogue entries and their panels must land **in the same work package**.
- `IngestMetricsIT.everyCataloguedMeterExistsWithExactlyTheCataloguedTags` requires every catalogued meter to exist in the registry after one `feedService.create(...)`. New meters must therefore be registered on every persist, even with a count of 0.
- `management.metrics.distribution.percentiles-histogram.argus: true` already gives every `argus.*` timer a histogram.

**Deploy**
- The argus Swarm stack deploys with `order: start-first` and `failure_action: rollback`.
- Because of this, phase 4 is deployed **once with stop-first** (see §8), and `content_hash` stays **nullable**, so that a rollback to the phase-3 image keeps working.

**Not in this repo yet**
- There is no springdoc and there are no OpenAPI annotations. Phase 13 adds them, so phase 4 adds **none**.

---

## 1. Decisions (resolved with the owner; do not reopen)

1. **Deploy safety.** One stop-first deploy after a `pg_dump`. `article.content_hash` and `article_feed.content_hash` stay nullable (no NOT NULL changeset).
2. **Per-feed content hash.** `article_feed.content_hash` stores what *that feed* last showed.
   - An edit is detected only when a feed's own stored hash changes, or when the upstream time advances.
   - The first sighting of an article from another feed of the same source is `linked`, never an edit, so two feeds with slightly different copies never flip the article back and forth.
3. **Time-only edits count.** The upstream update time advancing with the same hash gives decision `updated`, reason `timestamp_only`, with `contentChanged=false`.
   - Only `updated_at_upstream`, `effective_at` (never decreasing) and `modified_at` are written.
   - Content edits are `updated`, reason `content_changed`.
4. **`/articles` filters in phase 4:** repeatable `topic` and `country` only.
   - `topic` matches if any linked feed has that topic. `country` matches the source's country.
   - Phase 6 adds `since`, `until`, `sourceId` and `feedId`.
5. **Seed gating:** the seed changeset has `context: "!test"`. The `it` profile sets `spring.liquibase.contexts: test`. Prod sets nothing, so it seeds on its next deploy.
6. **OpenAPI** annotations are deferred to phase 13. This is a recorded deviation from the Definition of Done.
7. **The tracking-parameter list is a fixed constant in code**, not configurable. This is a recorded PRD deviation. Changing the list later needs a re-key changeset, and a golden-key test guards against accidental drift.

### Decisions made in planning (owner may still veto at plan approval)

8. **Two URL cleaners.**
   - `url/StoredUrls.clean` is the old `Links.clean`, renamed and unchanged. It is used for anything stored or fetched: `feed.url`, `feed.site_url` and `source.homepage_url`.
   - `url/LinkCleaner.clean` is the new full cleaner. It is used **only** for article identity keys (`guid_key` and `link_key`).
   - Feed URLs are never folded, because a folded URL may not be fetchable. Phase 5 adds identity folding as a comparison.
9. **GUID key.** Trim the raw GUID.
   - If it parses as an absolute http(s) URL, clean it with `LinkCleaner`. This is the PRD rule "a GUID that is a permalink URL is cleaned like a link". `ParsedEntry` has no `isPermaLink`, so any URL-shaped GUID counts.
   - Otherwise use it verbatim.
   - If the GUID is blank, use the link key.
   - Keys over 512 characters become `sha256:<hex>`, as today.
10. **Batch collapse is by GUID key only.** The PRD's "or the same usable link key" clause is dead logic: a link shared by distinct GUIDs is unusable by definition.
11. **Usable link.**
    - The link key is non-null.
    - It is not the source homepage key.
    - It is not a *root* link: no path and no query after cleaning.
    - It is not shared by two or more distinct GUID keys in the same download.
    - Link fallback also needs **exactly one** existing article with that link key that is not already claimed in this batch.
12. **Insert safety net.**
    - The insert is `INSERT … ON CONFLICT (source_id, guid_key) DO UPDATE SET modified_at = EXCLUDED.modified_at RETURNING id, (xmax = 0) AS inserted`.
    - Under the lock a conflict cannot happen. If one does, it is absorbed: counted as `updated`, reason `insert_conflict`, and logged at WARN. The article content is not overwritten.
13. **Lock.**
    - `pg_try_advisory_xact_lock(4100, sourceId)` is tried first. On success the wait is 0.
    - Otherwise the blocking `pg_advisory_xact_lock(4100, sourceId)` is timed with `System.nanoTime()`, so the measured wait is pure lock wait and excludes time spent waiting for a pool connection.
    - It is behind `source/SourceLock`, which phase 5 reuses.
    - `FeedService.delete` also takes the lock, which fixes a delete-versus-ingest foreign-key race.
14. **The dashboard reuses panel 34.** "Entry decisions" moves from the *Ingestion pipeline* row into the new *Deduplication* row and becomes a stacked chart, so there is no duplicate panel. A cross-feed overlap panel is added next to "duplicate pressure".
15. **The re-key migration is a Liquibase `customChange`** (Java), not SQL or a startup task. It is tracked in `DATABASECHANGELOG` and runs before the scheduler. It recomputes keys and hashes and collapses GUID-key collisions to the oldest article.

---

## 2. Ground rules — copy verbatim into every subagent prompt

```
GROUND RULES (non-negotiable)
1. No real infrastructure. Never contact: any feed URL from the seed list or the internet, nexus-repo.jmeighty.com,
   Grafana, Prometheus, Loki, Tempo, Sonar, Jenkins, the Docker Swarm node "mantra", the shared Postgres or PgBouncer.
   Local Testcontainers and the in-repo FeedStubServer are fine. If something seems to need real access, STOP and
   report.
2. Git: work only in the worktree/branch you were given. Stage explicit paths only (never `git add -A`, `git add .`
   or `git commit -a`). Never commit or touch the untracked owner files `interactive_visual_im_b13838ce8d7ace99.html`
   and `.DS_Store`. No push, no reset, no rebase, no amend, no history rewrites, no branch switching or checkout of
   other branches. Commit only if your package says so, with the exact message given, conventional style, and NO
   trailers (no Co-Authored-By, no Signed-off-by).
3. Maven: always `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` and `./mvnw -B -s /tmp/argus-central-settings.xml
   ...` (the default ~/.m2 settings mirror to a private Nexus — never use them). Never run two Maven builds in the
   same directory at once.
4. Never generate, print or commit real secrets. Test keys must be obviously fake.
5. TDD: write the failing test first, see it fail, then implement. Keep coverage at ~99.7%; add no JaCoCo or Sonar
   exclusions. No new dependencies in pom.xml.
6. Style: match the surrounding code. Essential comments only (why, not what). Immutable records, constructor
   injection, `@Nullable` from org.jspecify, no Lombok. Functions under ~50 lines, files under ~400 lines.
   No `System.out`, no commented-out code, no TODOs, no `@SuppressWarnings` without a reason comment.
7. Only edit the files your package owns (listed in its section). If you must change another file, STOP and report
   which file and why.
8. If this plan and the code disagree, or a library behaves differently than stated, STOP and report with evidence.
   Do not improvise a different design.
9. Your final message must list: files changed, tests added, the exact verify command you ran and its result
   (tests run/failed/skipped), and anything you could not do.
```

The orchestrator creates the Central-only settings file once:

```bash
printf '<settings/>\n' > /tmp/argus-central-settings.xml
```

---

## 3. Execution model

### Branches and worktrees

Parallel subagents must not share a checkout: they would break each other's compile and the shared `target/`.
Every parallel work package therefore runs in its **own git worktree**. The orchestrator owns the main checkout and
does all merges.

```bash
cd /Users/joshua/Documents/digital-dashboard/news-service
git switch -c feat/argus-phase-4 main          # orchestrator, once
# after Wave 0 is committed on feat/argus-phase-4:
git worktree add ../argus-p4-wpA -b feat/argus-phase-4-wpA feat/argus-phase-4
# ... one per package in the wave
# merging (orchestrator, main checkout, on feat/argus-phase-4):
git merge --no-ff feat/argus-phase-4-wpA -m "merge: phase 4 WP-A pure dedup cores"
# after the wave's merges are verified:
git worktree remove ../argus-p4-wpA && git branch -d feat/argus-phase-4-wpA
```

Each worktree has its own `target/`, so builds in different worktrees can run at the same time. Testcontainers in
parallel worktrees each start their own Postgres, which is fine.

### Waves

```
Wave 0  (serial)   WP-0 foundation & contracts                       → orchestrator gate G0
Wave 1  (parallel) WP-A pure cores │ WP-C sources & feed API │ WP-D articles API
                                                                     → merge A, C, D → gate G1
Wave 2  (parallel) WP-B persistence & ingest │ WP-E catalogue & dashboard │ WP-F re-key migration │ WP-G seed
                                                                     → merge B, E, F, G → gate G2
Wave 3  (serial)   WP-H cross-cutting ITs & docs                     → gate G3 → owner review gate
```

Why the waves are ordered this way:
- WP-B needs WP-A's resolver and cleaner, and WP-C/WP-D's APIs for its ITs.
- WP-E's catalogue entries are only satisfiable once WP-B registers the meters. Both land in Wave 2, and G2 runs the full build.
- WP-F needs WP-A's `LinkCleaner`, `EntryKeys` and `ContentHash`.
- Shared-file conflicts are designed out: WP-0 pre-creates every file that two packages would otherwise both add to (the changelog master, the placeholder changesets, the contract types).

### File ownership

The table below is the authority. A file not listed belongs to nobody, so changing it means STOP and report.

| File (under `src/main/java/com/j11a/argus/` unless a full path is given) | Owner |
|---|---|
| `url/StoredUrls.java` (renamed from `Links.java`), `src/test/.../url/StoredUrlsTest.java` (renamed from `LinksTest.java`) | WP-0 |
| `url/LinkCleaner.java`, `url/TrackingParams.java`, their tests | WP-A |
| `ingest/EntryKeys.java`, `EntryKeysTest`, additions to `SourceResolverTest` | WP-A |
| `ingest/ContentHash.java`, test | WP-A |
| `ingest/dedup/*` contract types (records/enums) | WP-0 creates; WP-A may only add Javadoc |
| `ingest/dedup/EntryDedupResolver.java` | WP-0 creates the stub; WP-A implements |
| `ingest/PersistCounts.java`, `ingest/IngestReport.java`, `feed/poll/AggregatePollReport.java`, and their call sites in tests | WP-0 |
| `ingest/IngestTelemetry.java`, `IngestTelemetryTest` | WP-0 (`recordDecisions` only), then WP-B |
| `ingest/FeedIngestService.java`, `FeedIngestServiceTest` | WP-0 (log line only), then WP-B |
| `ingest/ArticlePersister.java`, `ArticlePersisterTest`, `ingest/ExistingArticleLoader.java`, `ingest/DecisionApplier.java` | WP-B |
| `article/ArticleWriter.java` (replaces `ArticleInserter.java` and `InsertOutcome.java`), `article/NewArticle.java`, `article/ArticleEdit.java` | WP-B |
| `source/SourceLock.java` | WP-B |
| `feed/api/FeedService.java` | WP-0 (rename call sites), WP-C (`create`), WP-B (`delete`) |
| `feed/api/CreateFeedRequest.java` and its call sites | WP-0 |
| `feed/FeedSummary.java` | WP-0 |
| `source/SourceController.java`, `SourceQueryService.java`, `SourceResponse.java`, `PatchSourceRequest.java`, `CountryCodes.java`, `source/SourceService.java`, `source/SourceRepository.java`, `feed/FeedRepository.java` | WP-C |
| `article/Article.java`, `ArticleRepository.java`, `ArticleQueryService.java`, `ArticleController.java`, `ArticleResponse.java`, `ArticleSpecifications.java`, `ArticleFilter.java` and their tests | WP-D |
| `observability/MetricNames.java` | WP-0 |
| `observability/MetricCatalogue.java`, `MetricCatalogueTest`, `grafana/dashboards/argus-observability.json`, `ArgusDashboardTest` | WP-E |
| `src/main/resources/db/changelog/db.changelog-master.yaml` | WP-0 only |
| `changes/07-add-content-hash.yaml` | WP-0 |
| `changes/08-rekey-articles.yaml`, `migration/RekeyArticlesChange.java` | WP-0 placeholder; WP-F implements |
| `migration/ArticleRekeyer.java` and its tests | WP-F |
| `changes/09-seed-sources-and-feeds.yaml` | WP-0 placeholder; WP-G implements |
| `src/test/resources/application-it.yml`, `ApplicationYamlTest` | WP-0 |
| `src/test/java/com/j11a/argus/testsupport/ScratchDatabase.java` | WP-0 |
| New ITs | the package that lists them |
| `README.md`, `observability/README.md`, `plans/argus.md` (tick boxes) | WP-H |

### Gates (orchestrator, main checkout, after merging a wave)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -B -s /tmp/argus-central-settings.xml clean verify > /tmp/argus-gate.log 2>&1; echo EXIT=$?
python3 - <<'PY'
import glob,re
for d in ("target/surefire-reports","target/failsafe-reports"):
    t=s=f=e=0
    for x in glob.glob(d+"/TEST-*.xml"):
        h=open(x).read(3000); g=lambda k:int(re.search(k+r'="(\d+)"',h).group(1))
        t+=g("tests"); s+=g("skipped"); f+=g("failures"); e+=g("errors")
    print(d,"tests",t,"skipped",s,"failures",f,"errors",e)
PY
git log --oneline feat/argus-phase-4 -15 && git status --short && git reflog -10
```

A gate passes only if all of the following hold:
- `EXIT=0`
- 0 skipped, 0 failures, 0 errors
- `git status` shows only the owner's untracked files
- `git reflog` shows no reset, rebase or amend

If a gate fails, the orchestrator fixes the problem itself only when the fix is mechanical, such as a merge
leftover or a missing import. Otherwise it sends the failure back to the owning subagent. **No new wave starts
on a red gate.**

**Stub check before G2.** `grep -rn 'UnsupportedOperationException("WP-' src/main` must print nothing.

---

## 4. Design reference (shared by all packages)

### 4.1 `LinkCleaner` rules (WP-A)

`public final class LinkCleaner` has a private constructor and one method, `public static @Nullable String clean(@Nullable String link)`. Apply these steps in order:

1. Parse with `HttpUrls.parseHttp(link)`. If that returns empty, return `null`.
2. The scheme is always `https`.
3. Lowercase the host. Then, in a loop, strip a leading `www.`, `m.` or `amp.` as long as what remains still contains a dot (`www.co.uk` must not become `co.uk`; `m.example.com` becomes `example.com`).
4. Drop user-info.
5. Drop port 80 and port 443. Keep any other port.
6. Path: start from `getRawPath()`, keeping escapes and case. In a loop, strip a trailing `/` and a trailing `/amp` segment (case-insensitive). So `/a/amp/` becomes `/a`, and `/` becomes the empty path.
7. Query: start from `getRawQuery()` and split on `&`. Drop:
   - empty segments;
   - tracking params: the param name, percent-decoded and lowercased, matches `TrackingParams`;
   - `amp=1` and `outputType=amp` (name match is case-insensitive).

   Keep everything else, including params with no value. Sort by the raw segment string and keep duplicates.
8. Drop the fragment.
9. Rebuild as `https://` + host + (`:port` if kept) + path + (`?` + joined query, if any segments remain).

`TrackingParams` is a final class with `static boolean isTracking(String lowercasedName)`:
- Exact names: `fbclid`, `gclid`, `mc_cid`, `mc_eid`, `cmpid`, `ref`.
- Prefixes: `utm_`, `at_`.

`LinkCleaner` must be **idempotent**: `clean(clean(x)).equals(clean(x))` for every `x`.

A **root link** is a cleaned link with an empty path and no query (for example `https://bbc.co.uk`). Expose this as `static boolean isRoot(String cleanedLink)`.

### 4.2 Keys and hash (WP-A)

**`EntryKeys`** stays static, with the same public signatures:

```java
public static @Nullable String guidKey(@Nullable String rawGuid, @Nullable String link)
// strip rawGuid; if blank -> linkKey(link);
// else if HttpUrls.parseHttp(guid).isPresent() -> capped(LinkCleaner.clean(guid));
// else capped(guid)
public static @Nullable String linkKey(@Nullable String link)   // capped(LinkCleaner.clean(link))
```

`MAX_KEY_LENGTH` (512) and the `sha256:` capping are unchanged.

**`ContentHash`**:

```java
public static String of(String title, @Nullable String excerpt, List<String> categories)
```

Normalise each text value like this:
1. `java.text.Normalizer` NFKC.
2. Replace ` ` with a space.
3. Collapse every run of whitespace to a single space.
4. `strip()`.

Case is **kept**.

Categories: normalise each one the same way, drop blanks, de-duplicate case-insensitively (keep the first spelling), and sort case-insensitively.

Payload: `title + '\u001F' + excerpt(or "") + '\u001F' + String.join("\u001E", categories)`. The hash is the lowercase hex SHA-256 of the UTF-8 payload, computed with the existing `crypto/Sha256`.

**Golden-key test.** `KeyStabilityTest` holds fixed inputs and the expected `guidKey`, `linkKey` and `ContentHash` outputs as literals. It must fail if anyone changes the cleaner or the hash.

### 4.3 Dedup contract types (WP-0 creates them exactly; package `com.j11a.argus.ingest.dedup`)

```java
public record KeyedEntry(ParsedEntry entry, String guidKey, @Nullable String linkKey,
                         String contentHash, Instant effectiveAt, int position) {}

public record ExistingArticle(long id, String guidKey, @Nullable String linkKey,
                              @Nullable Instant updatedAtUpstream,
                              boolean linkedToFeed, @Nullable String feedContentHash) {}

@FunctionalInterface
public interface ExistingArticleLookup {
    /** One bulk read; never called with the homepage, root or batch-shared link keys. */
    List<ExistingArticle> find(Set<String> guidKeys, Set<String> linkKeys);
}

public enum SkipReason {
    MISSING_IDENTITY("missing_identity"), BATCH_DUPLICATE("batch_duplicate");
    // String tag() accessor
}

public enum UpdateReason {
    CONTENT_CHANGED("content_changed"), TIMESTAMP_ONLY("timestamp_only"), INSERT_CONFLICT("insert_conflict");
}

public enum LinkFallback {
    GUID_REPLACED("guid_replaced"), GUARDED_HOMEPAGE("guarded_homepage"), GUARDED_SHARED("guarded_shared");
}

public sealed interface EntryDecision {
    record Insert(KeyedEntry entry) implements EntryDecision {}
    /** The article content is rewritten only for CONTENT_CHANGED. */
    record Update(KeyedEntry entry, long articleId, UpdateReason reason, boolean guidReplaced,
                  boolean wasLinked) implements EntryDecision {}
    record Link(KeyedEntry entry, long articleId, boolean guidReplaced) implements EntryDecision {}
    record Unchanged(KeyedEntry entry, long articleId, boolean guidReplaced,
                     boolean backfillFeedHash) implements EntryDecision {}
    record Skip(ParsedEntry entry, SkipReason reason) implements EntryDecision {}
}

public record DedupInput(long feedId, List<ParsedEntry> entries, @Nullable String homepageKey,
                         Instant fetchedAt, ExistingArticleLookup lookup) {}

public record Resolution(List<EntryDecision> decisions, Map<LinkFallback, Integer> linkFallbacks) {
    // compact constructor: List.copyOf / Map.copyOf
}

public final class EntryDedupResolver {   // WP-0: body throws UnsupportedOperationException("WP-A")
    public Resolution resolve(DedupInput input) { ... }
}
```

Every enum has a `String tag()` accessor, written in the same style as `FetchFailureReason.tag()`.

### 4.4 Resolver algorithm (WP-A implements; one unit test per numbered rule)

Input: `DedupInput`. Output: `Resolution`. Decisions come out in **document order**: the order of `entries`.

1. **Key.** For each entry at position `i`, compute `guidKey = EntryKeys.guidKey(guid, link)`.
   - If it is `null`, the decision is `Skip(MISSING_IDENTITY)`.
   - Otherwise build a `KeyedEntry` with:
     - `linkKey = EntryKeys.linkKey(link)`
     - `contentHash = ContentHash.of(title, excerpt, categories)`
     - `effectiveAt = EffectiveTime.of(published, updated, fetchedAt)`
2. **Collapse within the batch** by `guidKey`. The survivor is the copy with the latest `entry.updatedAt()`, where `null` counts as earliest. On a tie, the copy with the **higher position** wins. Every other copy is `Skip(BATCH_DUPLICATE)`.
3. **Usable links.** Count the distinct `guidKey`s per `linkKey` among the survivors. A `linkKey` is *guarded* for one of two reasons:
   - **homepage**: it equals `homepageKey`, or `LinkCleaner.isRoot(linkKey)` is true;
   - **shared**: it is held by two or more distinct GUID keys. Homepage is checked first.
4. **One lookup.** Call `lookup.find(allSurvivorGuidKeys, usableLinkKeys)` exactly once. Skip the call entirely if both sets are empty. Index the result by `guidKey` (unique) and by `linkKey` (a list).
5. **GUID pass.** For each survivor whose `guidKey` matches an existing article, that article is the match, and its id is added to the `claimed` set.
6. **Link pass.** Only for survivors with no GUID match:
   - **Usable link with exactly one existing article** for that link key, and that article is not in `claimed`: it is the match. Set `guidReplaced = true` and add it to `claimed`. Count `GUID_REPLACED`.
   - **Usable link with two or more existing articles**, or the single one already claimed: no match. Count `GUARDED_SHARED`.
   - **Guarded link**: no match. Count `GUARDED_HOMEPAGE` or `GUARDED_SHARED` according to the guard.
   - **Usable link with no existing article, or a null link**: no match, and nothing is counted.
7. **Decide each survivor.**
   - **No match:** `Insert`.
   - **Matched, `linkedToFeed == false`:**
     - If the upstream time advanced (rule 8), `Update(TIMESTAMP_ONLY, wasLinked=false)`.
     - Otherwise `Link`.
     - A new feed's copy never rewrites content.
   - **Matched, `linkedToFeed == true`:**
     - If `feedContentHash == null` (a row from before the migration or from a rolled-back image), `Unchanged(backfillFeedHash=true)`.
     - Else if `feedContentHash` differs from `contentHash`, `Update(CONTENT_CHANGED, wasLinked=true)`.
     - Else if the upstream time advanced, `Update(TIMESTAMP_ONLY, wasLinked=true)`.
     - Otherwise `Unchanged(backfillFeedHash=false)`.
   - In every matched case, carry `guidReplaced` from rule 6.
8. **Upstream time advanced** means `entry.updatedAt() != null && (existing.updatedAtUpstream() == null || entry.updatedAt().isAfter(existing.updatedAtUpstream()))`.
9. `linkFallbacks` always contains **all three** keys, with 0 where nothing was counted.

Phase 11 hook: rule 7's "no match → Insert" lives in one private method, `decideUnmatched(KeyedEntry)`. Its Javadoc says phase 11 adds the retention cutoff and recently-purged checks there. Add no other parameters or dead code.

### 4.5 Persistence (WP-B)

**`source/SourceLock`** is a `@Component` using `JdbcClient`.

```java
static final int SOURCE_LOCK_NAMESPACE = 4100;
@Transactional(propagation = Propagation.MANDATORY)
public Duration acquire(long sourceId)
// 1. Boolean got = SELECT pg_try_advisory_xact_lock(:ns, :id)   -> if TRUE return Duration.ZERO
// 2. long start = System.nanoTime(); SELECT 1 FROM pg_advisory_xact_lock(:ns, :id); return Duration.ofNanos(System.nanoTime() - start)
// :id = Math.toIntExact(sourceId)
```

**`ingest/ExistingArticleLoader`** is a `@Component`. It has one method, `ExistingArticleLookup forFeed(long sourceId, long feedId)`, which returns a lambda that runs:

```sql
SELECT a.id, a.guid_key, a.link_key, a.updated_at_upstream,
       af.article_id IS NOT NULL AS linked, af.content_hash AS feed_content_hash
FROM article a
LEFT JOIN article_feed af ON af.article_id = a.id AND af.feed_id = :feedId
WHERE a.source_id = :sourceId
  AND (a.guid_key = ANY(CAST(:guidKeys AS text[])) OR a.link_key = ANY(CAST(:linkKeys AS text[])))
```

Bind the parameters as `String[]`. Spring only expands `Iterable`s, so an array binds as one parameter; the existing `categories` insert relies on the same thing. An empty array is valid.

**`article/ArticleWriter`** replaces `ArticleInserter` and `InsertOutcome`. Use `git mv ArticleInserter.java ArticleWriter.java`, and delete `InsertOutcome.java`.

```java
public record WriteResult(long id, boolean inserted) {}
public WriteResult insert(NewArticle a, OffsetDateTime now);
public void rewriteContent(ArticleEdit e, OffsetDateTime now);
public void advanceTimestamps(long id, @Nullable Instant updatedAtUpstream, Instant effectiveAt, boolean dated, OffsetDateTime now);
public void replaceGuid(long id, String guidKey, @Nullable String rawGuid, OffsetDateTime now);
public void link(long articleId, long feedId, String contentHash, OffsetDateTime now);
```

`NewArticle` gains `String contentHash`. `ArticleEdit` is a new record holding `id`, `linkKey`, `link`, `title`, `excerpt`, `author`, `imageUrl`, `categories`, `publishedAt`, `updatedAtUpstream`, `effectiveAt`, `dated` and `contentHash`.

The SQL:

```sql
-- insert
INSERT INTO article (source_id, guid_key, raw_guid, link_key, link, content_hash, title, excerpt, author, image_url,
                     categories, published_at, updated_at_upstream, effective_at, fetched_at, modified_at)
VALUES (:sourceId, :guidKey, :rawGuid, :linkKey, :link, :contentHash, :title, :excerpt, :author, :imageUrl,
        :categories, :publishedAt, :updatedAtUpstream, :effectiveAt, :fetchedAt, :modifiedAt)
ON CONFLICT (source_id, guid_key) DO UPDATE SET modified_at = EXCLUDED.modified_at
RETURNING id, (xmax = 0) AS inserted

-- rewriteContent (fetched_at, source_id, guid_key are never touched)
UPDATE article SET link_key = COALESCE(:linkKey, link_key), link = COALESCE(:link, link),
  title = :title, excerpt = :excerpt, author = COALESCE(:author, author),
  image_url = COALESCE(:imageUrl, image_url), categories = :categories,
  published_at = COALESCE(published_at, :publishedAt),
  updated_at_upstream = GREATEST(updated_at_upstream, :updatedAtUpstream),
  effective_at = CASE WHEN :dated THEN GREATEST(effective_at, :effectiveAt) ELSE effective_at END,
  content_hash = :contentHash, modified_at = :now
WHERE id = :id

-- advanceTimestamps
UPDATE article SET updated_at_upstream = GREATEST(updated_at_upstream, :updatedAtUpstream),
  effective_at = CASE WHEN :dated THEN GREATEST(effective_at, :effectiveAt) ELSE effective_at END,
  modified_at = :now
WHERE id = :id

-- replaceGuid
UPDATE article SET guid_key = :guidKey, raw_guid = :rawGuid, modified_at = :now WHERE id = :id

-- link (insert or refresh this feed's hash; first_seen_at never changes)
INSERT INTO article_feed (article_id, feed_id, first_seen_at, content_hash)
VALUES (:articleId, :feedId, :now, :contentHash)
ON CONFLICT (article_id, feed_id) DO UPDATE SET content_hash = EXCLUDED.content_hash
WHERE article_feed.content_hash IS DISTINCT FROM EXCLUDED.content_hash
```

Notes on the SQL:
- Postgres `GREATEST` ignores NULLs, so `GREATEST(NULL, x)` is `x`.
- `dated` = the entry has a published date or an updated date.
- `published_at` keeps the stored value. The incoming one is used only when the stored one is null.

**`ingest/DecisionApplier`** is a `@Component` with `PersistCounts apply(long sourceId, long feedId, Resolution r, Instant now)`. Apply the decisions sorted by `guidKey` ascending. Under the lock that is not needed for correctness; it is kept as cheap deadlock insurance, so say so in one comment.

| Decision | Writes | Counted as |
|---|---|---|
| `Insert` | `insert`. If `inserted`, then `link`. If not inserted (conflict): WARN log with the ids, then `link`. | `inserted`, or `updated[insert_conflict]` |
| `Update(CONTENT_CHANGED)` | `replaceGuid` if `guidReplaced`, `rewriteContent`, `link` | `updated[content_changed]` |
| `Update(TIMESTAMP_ONLY)` | `replaceGuid` if `guidReplaced`, `advanceTimestamps`, `link` | `updated[timestamp_only]` |
| `Link` | `replaceGuid` if `guidReplaced`, `link` | `linked` |
| `Unchanged` | `replaceGuid` if `guidReplaced`; `link` only if `backfillFeedHash` | `unchanged` |
| `Skip` | nothing | `skipped[reason]` |

Build the counts by grouping a stream, not with mutable counters.

**`ingest/ArticlePersister.persist(Feed feed, List<ParsedEntry> entries, Instant fetchedAt)`** stays `@Transactional`. Its order is fixed:
1. Acquire the lock: `Duration wait = telemetry.lockWait(sourceKey, sourceId, () -> sourceLock.acquire(sourceId))`.
2. Re-read the homepage under the lock with `SELECT homepage_url FROM source WHERE id = :id`, because a PATCH may have changed it. Then `homepageKey = EntryKeys.linkKey(homepageUrl)`.
3. Resolve: `telemetry.span("argus.resolve", () -> resolver.resolve(new DedupInput(feedId, entries, homepageKey, fetchedAt, loader.forFeed(sourceId, feedId))))`.
4. Apply: `applier.apply(...)`.
5. Return the `PersistCounts`, including `linkFallbacks`.

`EntryDedupResolver` becomes a bean through `@Bean EntryDedupResolver entryDedupResolver()` in `ArgusConfiguration`. WP-B owns that one-line addition.

**`FeedService.delete`.** Inside the existing transaction, first run `SELECT source_id FROM feed WHERE id = :id`. If there is no row, return 404. Otherwise `sourceLock.acquire(sourceId)`, then run the existing two deletes.

### 4.6 Counts, reports and telemetry contracts (WP-0 creates the shapes)

```java
public record PersistCounts(int inserted, Map<String, Integer> updated, int linked, int unchanged,
                            Map<String, Integer> skipped, Map<LinkFallback, Integer> linkFallbacks) {
    // Map.copyOf in compact ctor; updatedTotal(), skippedTotal()
}
public record IngestReport(long feedId, Outcome outcome, @Nullable String failureReason, int entriesSeen,
                           int inserted, int updated, int linked, int unchanged, int skipped) { ... }
// AggregatePollReport: add `updated` and `linked` sums, placed after `inserted`, in the same style
```

The invariant `entriesSeen == inserted + updated + linked + unchanged + skipped` must hold. Assert it in `IngestReport` and resolver tests.

The JSON change is additive. Keys of `updated` are `UpdateReason.tag()` values, and keys of `skipped` are `SkipReason.tag()` values.

Behaviour change to note in the WP-B commit: an in-batch duplicate used to count as `unchanged`; it is now `skipped{reason=batch_duplicate}`.

The meters (names added to `MetricNames` by WP-0; catalogued by WP-E; recorded by WP-B):

| Meter | Kind | Tags | Values |
|---|---|---|---|
| `argus.ingest.entries` (existing) | counter | `source`, `decision`, `reason` | `decision` ∈ `inserted`, `updated`, `linked`, `unchanged`, `skipped`. For `updated`, `reason` ∈ `content_changed`, `timestamp_only`, `insert_conflict`. For `skipped`, `reason` ∈ `missing_identity`, `batch_duplicate`. Otherwise `reason` = `none`. |
| `argus.ingest.link.fallback` (`INGEST_LINK_FALLBACK`) | counter | `source`, `outcome` | `guid_replaced`, `guarded_homepage`, `guarded_shared`. All three are incremented on **every** persist, by 0 when nothing was counted, so the series always exist. |
| `argus.ingest.lock.wait` (`INGEST_LOCK_WAIT`) | timer | `source` | Recorded on every persist, including `Duration.ZERO`. Use `Timer.builder(...).tag(SOURCE, key).register(meters).record(wait)`. |

The spans:
- `argus.lock.wait`, a child of the existing `argus.persist`, with attributes `source.id` and `contended` (`true`/`false`).
- `argus.resolve`, also a child of `argus.persist`.
- To support these, `IngestTelemetry.span` gains an overload that takes attributes.

### 4.7 Schema

**`07-add-content-hash.yaml`** (WP-0):

```yaml
databaseChangeLog:
  - changeSet:
      id: 07-add-content-hash
      author: argus
      changes:
        - addColumn:
            tableName: article
            columns:
              - column: { name: content_hash, type: text }
        - addColumn:
            tableName: article_feed
            columns:
              - column: { name: content_hash, type: text }
      rollback:
        - dropColumn: { tableName: article_feed, columnName: content_hash }
        - dropColumn: { tableName: article, columnName: content_hash }
```

**`08-rekey-articles.yaml`**. This is final from WP-0 on; WP-F only implements the class.

```yaml
databaseChangeLog:
  - changeSet:
      id: 08-rekey-articles
      author: argus
      comment: Irreversible. Recomputes guid/link keys with the phase-4 cleaner, collapses collisions, backfills hashes.
      changes:
        - customChange:
            class: com.j11a.argus.migration.RekeyArticlesChange
```

**`09-seed-sources-and-feeds.yaml`**:
- WP-0 placeholder: `context: "!test"`, one change `- sql: { sql: SELECT 1 }`.
- WP-G replaces the body with the seed SQL (§4.8). The id and context stay the same. Never add `runOnChange` or `runAlways`.

**Master changelog** (WP-0): add the includes for 07, 08 and 09, in that order, after 06.

`Article.java` maps **no** `content_hash` field, because no read needs it. `ddl-auto: validate` ignores unmapped columns.

### 4.8 Seed content (WP-G)

**Sources:**

```sql
INSERT INTO source (key, name, homepage_url, country, created_at, updated_at)
VALUES (...), ...
ON CONFLICT (key) DO UPDATE SET
  name = CASE WHEN source.name = source.key THEN EXCLUDED.name ELSE source.name END,
  homepage_url = COALESCE(source.homepage_url, EXCLUDED.homepage_url),
  country = COALESCE(source.country, EXCLUDED.country),
  updated_at = now();
```

The `ON CONFLICT` clause only fills in defaults. It never overwrites an owner edit. In prod, BBC already exists with `name = 'bbc.co.uk'` and no country, so the seed fills in both.

| key | name | homepage_url | country |
|---|---|---|---|
| bnnbloomberg.ca | BNN Bloomberg | https://www.bnnbloomberg.ca/ | CA |
| citynews.ca | CityNews | https://toronto.citynews.ca/ | CA |
| theglobeandmail.com | The Globe and Mail | https://www.theglobeandmail.com/ | CA |
| cbc.ca | CBC | https://www.cbc.ca/ | CA |
| globalnews.ca | Global News | https://globalnews.ca/ | CA |
| sportsnet.ca | Sportsnet | https://www.sportsnet.ca/ | CA |
| bbc.co.uk | BBC | https://www.bbc.co.uk/ | GB |
| dailymail.co.uk | Daily Mail | https://www.dailymail.co.uk/ | GB |
| mirror.co.uk | The Mirror | https://www.mirror.co.uk/ | GB |
| thesun.co.uk | The Sun | https://www.thesun.co.uk/ | GB |
| lusakatimes.com | Lusaka Times | https://www.lusakatimes.com/ | ZM |
| zambia24.com | Zambia24 | https://zambia24.com/ | ZM |
| mwebantu.com | Mwebantu | https://www.mwebantu.com/ | ZM |
| zambianbusinesstimes.com | Zambian Business Times | https://zambianbusinesstimes.com/ | ZM |
| zambianfootball.co.zm | Zambian Football | https://www.zambianfootball.co.zm/ | ZM |
| lusakastar.com | Lusaka Star | https://www.lusakastar.com/ | ZM |
| farmersreviewafrica.com | Farmers Review Africa | https://www.farmersreviewafrica.com/ | ZM |

**Feeds:**

```sql
INSERT INTO feed (source_id, name, url, topic, enabled, created_at, updated_at)
SELECT s.id, v.name, v.url, v.topic, true, now(), now()
FROM (VALUES (...), ...) AS v(source_key, name, url, topic)
JOIN source s ON s.key = v.source_key
ON CONFLICT (url) DO NOTHING;
```

`ON CONFLICT (url) DO NOTHING` means prod's existing BBC News row is left alone.

| source_key | name | url | topic |
|---|---|---|---|
| bnnbloomberg.ca | BNN Bloomberg | https://www.bnnbloomberg.ca/arc/outboundfeeds/rss/?outputType=xml | BUSINESS |
| citynews.ca | CityNews Toronto | https://toronto.citynews.ca/feed/ | NEWS |
| theglobeandmail.com | The Globe and Mail (Canada) | https://www.theglobeandmail.com/arc/outboundfeeds/rss/category/canada/ | NEWS |
| cbc.ca | CBC Top Stories | https://www.cbc.ca/webfeed/rss/rss-topstories | NEWS |
| cbc.ca | CBC Sports | https://www.cbc.ca/webfeed/rss/rss-sports | SPORT |
| globalnews.ca | Global News | https://globalnews.ca/feed/ | NEWS |
| sportsnet.ca | Sportsnet | https://www.sportsnet.ca/feed/ | SPORT |
| bbc.co.uk | BBC News | https://feeds.bbci.co.uk/news/rss.xml | NEWS |
| bbc.co.uk | BBC World | https://feeds.bbci.co.uk/news/world/rss.xml | WORLD |
| bbc.co.uk | BBC Football | https://feeds.bbci.co.uk/sport/football/rss.xml | SPORT |
| dailymail.co.uk | Daily Mail News | https://www.dailymail.co.uk/news/index.rss | NEWS |
| mirror.co.uk | The Mirror News | https://www.mirror.co.uk/news/?service=rss | NEWS |
| thesun.co.uk | The Sun | https://www.thesun.co.uk/feed/ | NEWS |
| lusakatimes.com | Lusaka Times | https://www.lusakatimes.com/feed/ | NEWS |
| zambia24.com | Zambia24 | https://zambia24.com/feed/ | NEWS |
| mwebantu.com | Mwebantu | https://www.mwebantu.com/feed/ | NEWS |
| zambianbusinesstimes.com | Zambian Business Times | https://zambianbusinesstimes.com/feed/ | BUSINESS |
| zambianfootball.co.zm | Zambian Football | https://www.zambianfootball.co.zm/feed/ | SPORT |
| lusakastar.com | Lusaka Star | https://www.lusakastar.com/feed/ | NEWS |
| farmersreviewafrica.com | Farmers Review Africa | https://www.farmersreviewafrica.com/feed/ | BUSINESS |

That is 17 sources and 20 feeds. The Independent is excluded because it is unverified.

The seed sets no `site_url`, `language`, ETag or Last-Modified. The first poll fills the health fields, and its fetches are unconditional.

### 4.9 Re-key migration (WP-F)

**`migration/RekeyArticlesChange implements CustomTaskChange`**:
- A public no-arg constructor.
- `execute(Database db)`:
  1. `Connection c = ((JdbcConnection) db.getConnection()).getUnderlyingConnection()`.
  2. `RekeyReport r = new ArticleRekeyer().rekey(c)`.
  3. Store `r` for `getConfirmationMessage()`, which returns `"Re-keyed articles: seen=…, rekeyed=…, collapsed=…, linksFolded=…"`.
  4. Wrap any `SQLException` in a `CustomChangeException`.
- `setUp()` and `setFileOpener()` do nothing. `validate()` returns `new ValidationErrors()`.

**`migration/ArticleRekeyer`** uses plain JDBC, inside Liquibase's transaction (it never commits itself):

1. **Read.** `SELECT id, source_id, guid_key, raw_guid, link, title, excerpt, categories FROM article ORDER BY source_id, id`, with fetch size 1000. For each row:
   - `newGuid = EntryKeys.guidKey(raw_guid, link)`. If null, keep the old `guid_key`.
   - `newLink = EntryKeys.linkKey(link)`.
   - `hash = ContentHash.of(title, excerpt, categories)`.
2. **Group** by `(source_id, newGuid)`. The survivor is the **lowest id**, which is the oldest. Every other row in the group is a loser.
3. **Fold.** For each loser, move its feed links onto the survivor, then delete the losers:
   ```sql
   INSERT INTO article_feed (article_id, feed_id, first_seen_at)
   SELECT ?, feed_id, first_seen_at FROM article_feed WHERE article_id = ?
   ON CONFLICT (article_id, feed_id) DO UPDATE SET first_seen_at = LEAST(article_feed.first_seen_at, EXCLUDED.first_seen_at)
   ```
   Then `DELETE FROM article WHERE id = ANY(?)`, built with `c.createArrayOf("bigint", …)`. The cascade removes the losers' links.
4. **Temporary keys.** For survivors whose GUID key changes, run `UPDATE article SET guid_key = '~rekey~' || id WHERE id = ANY(?)`. This avoids transient unique violations: `uq_article_source_guid_key` cannot be deferred, because it is the `ON CONFLICT` arbiter.
5. **Backfill.** For **every** survivor, in a JDBC batch: `UPDATE article SET guid_key = ?, link_key = ?, content_hash = ? WHERE id = ?`, followed by `UPDATE article_feed SET content_hash = ? WHERE article_id = ?`.

Only GUID-key collisions are collapsed. Rows that share just a link key stay separate, because the resolver guards them.

The migration is idempotent: a second run changes nothing, because the cleaner is idempotent.

---

## 5. Work packages

Each package below is a self-contained subagent brief. Prefix each one with §2 (ground rules) and give it §4.

### WP-0 — Foundation and contracts (Wave 0, serial, main checkout on `feat/argus-phase-4`)

**Goal:** add every shared type and every mechanical ripple, with **no behaviour change**. `clean verify` stays green.

**Deliverables:**
1. `git mv src/main/java/com/j11a/argus/url/Links.java …/url/StoredUrls.java`.
   - Rename the class.
   - Replace the Javadoc with: "Light normalisation for URLs that are stored and fetched (feed, site and homepage URLs). Article identity keys use LinkCleaner."
   - Update the callers: `FeedService`, `SourceService` and `EntryKeys`. For now `EntryKeys` keeps calling `StoredUrls.clean`; WP-A switches it.
   - `git mv` `LinksTest` to `StoredUrlsTest` in the same way.
2. The contract types of §4.3, in `ingest/dedup/`. `EntryDedupResolver.resolve` throws `new UnsupportedOperationException("WP-A")`. No tests yet, except a tiny test per enum asserting its tag values.
3. The §4.6 shapes: `PersistCounts`, `IngestReport` and `AggregatePollReport`.
   - `ArticlePersister` (old logic) builds the new `PersistCounts`: `updated = Map.of()`, `linked = 0`, and `linkFallbacks` all 0.
   - `IngestTelemetry.recordDecisions` emits `updated` per reason and `linked`. It keeps the `if (count > 0)` behaviour for decisions.
   - The `FeedIngestService` log line adds `updated=` and `linked=`.
   - Update every call site in the tests: `AggregatePollReportTest`, `FeedPollerTest`, `FeedPollerShutdownTest`, `FeedControllerTest`, `FeedIngestServiceTest`, `ArticlePersisterTest`, `IngestTelemetryTest`, `FeedHealthIT`, `PollShutdownIT`, and any other the compiler finds.
4. `CreateFeedRequest` gains a 4th component, `@Positive @Nullable Long sourceId`. `FeedService` ignores it for now; WP-C implements it. Update the call sites, including `AbstractIntegrationTest.createFeedFrom` (pass `null`).
5. `feed/FeedSummary.java`: `public record FeedSummary(long id, String name, Topic topic) { public static FeedSummary of(Feed f) }`, plus a unit test.
6. `MetricNames`: add `INGEST_LINK_FALLBACK = "argus.ingest.link.fallback"` and `INGEST_LOCK_WAIT = "argus.ingest.lock.wait"`. Do **not** touch `MetricCatalogue`.
7. Changesets 07, 08 and 09 (§4.7), with 08 and 09 as placeholders, and the master includes.
   - Create `migration/RekeyArticlesChange.java` as a valid no-op `CustomTaskChange`: `execute` does nothing and `getConfirmationMessage` returns `"Re-key placeholder"`.
8. `application-it.yml`: add `spring.liquibase.contexts: test`.
   - Add an `ApplicationYamlTest` case that activates the `it` profile and asserts the value.
   - Add a case asserting that the default (no profile) has no `spring.liquibase.contexts`.
9. `testsupport/ScratchDatabase.java`. This is a test helper for WP-F and WP-G:
   - `static ScratchDatabase create(PostgreSQLContainer pg)` runs `CREATE DATABASE scratch_<random hex>` through a `DriverManager` connection to the container's default database.
   - `Connection connect()`.
   - `void migrate(String contexts)`: a full update with `new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), new JdbcConnection(connect()))` and `update(new Contexts(contexts))`.
   - `void migrateFirst(int changesets, String contexts)`: `update(int, Contexts, new LabelExpression())`.
   - `close()` drops the database (`DROP DATABASE … WITH (FORCE)`).
   - The changelog creates the `unaccent` extension, so a fresh **database** is required, not a schema.
   - Add `ScratchDatabaseIT`: create, `migrateFirst(6, "test")`, check that `databasechangelog` has 6 rows, then close.
10. `SchemaIT`: assert that both `content_hash` columns exist and are nullable. `LiquibaseBaselineIT`: assert that `09-seed-sources-and-feeds` is **absent** from `databasechangelog` under the `test` context, and that 07 and 08 ran exactly once.

**Verify and commit** (orchestrator):
- Run G0. Expect about 629 tests plus the new ones, all green.
- Commit with `refactor: add phase 4 contracts, content-hash schema and seed/re-key placeholders`.

### WP-A — Pure cores (Wave 1, worktree `../argus-p4-wpA`, branch `feat/argus-phase-4-wpA`)

**Owns:** `url/LinkCleaner.java`, `url/TrackingParams.java`, `ingest/EntryKeys.java`, `ingest/ContentHash.java`, the `ingest/dedup/EntryDedupResolver.java` body, and their tests (`LinkCleanerTest`, `TrackingParamsTest`, `EntryKeysTest`, `ContentHashTest`, `KeyStabilityTest`, `EntryDedupResolverTest`). It may also add cases to `SourceResolverTest`: `www.zambianfootball.co.zm` → `zambianfootball.co.zm`, `x.y.com.au` → `y.com.au`, `foo.blogspot.com` → `foo.blogspot.com`, `feeds.bbci.co.uk` → `bbci.co.uk`. Add only the ones that are missing; these outputs are verified.

**Read first:** `url/HttpUrls.java`, `url/StoredUrls.java`, `ingest/EffectiveTime.java`, `crypto/Sha256.java`, `feed/parse/ParsedEntry.java`, and §4.1–§4.4.

**Deliverables:** implement §4.1, §4.2 and §4.4 exactly. All of it is pure Java with no Spring context.

**Tests (TDD), at least:**
- `LinkCleanerTest`: one parameterised case per rule in §4.1:
  - Scheme fold `http` → `https`.
  - Host lowercase.
  - `www.`, `m.` and `amp.` stripping, including stacked forms (`www.m.x.com` → `x.com`) and the remainder-must-contain-a-dot guard.
  - User-info dropped.
  - Ports 80/443 dropped and 8080 kept.
  - Trailing `/`, `/amp` and `/amp/` stripped; `/` becomes empty.
  - Every tracking param, exact and prefix, case-insensitive, including percent-encoded names.
  - `amp=1` and `outputType=amp` dropped; `outputType=xml` and `p=123` kept.
  - Remaining params sorted, duplicates kept, valueless params kept.
  - Fragment dropped.
  - Escapes and path case preserved.
  - Non-http, relative and null input give null.
  - `isRoot` true and false cases.
  - Idempotence: a corpus of every case above, plus a seeded `new Random(42)` generator of about 1000 URLs built from those components, asserting `clean(clean(x)).equals(clean(x))`.
- `EntryKeysTest`: a URL GUID is cleaned (`https://www.x.com/a#0` → `https://x.com/a`), a non-URL GUID is kept verbatim, a blank GUID uses the link key, both null gives null, and over 512 characters gives the hash.
- `ContentHashTest`: whitespace, NBSP and NFKC variants hash the same; category order, duplicates and case variants hash the same; a case change in the title hashes differently; a null excerpt equals `""`.
- `KeyStabilityTest`: golden literals for 5 inputs (a BBC-style `…#0` GUID with `?at_medium=…`, a WordPress `?p=123`, an AMP URL, a non-URL GUID, a long URL that gets capped).
- `EntryDedupResolverTest`: one test per numbered rule in §4.4. Use a lambda lookup that records its arguments. Required cases:
  - Missing identity is skipped.
  - Collapse keeps the latest `updatedAt`; a tie keeps the higher position; the losers are `BATCH_DUPLICATE`.
  - The homepage link is guarded and counted `GUARDED_HOMEPAGE`.
  - A root link is guarded.
  - A link shared by two GUIDs is guarded and counted `GUARDED_SHARED`.
  - The lookup is called once, never with guarded keys, and is skipped when both sets are empty.
  - A GUID match on a linked feed with the same hash is `Unchanged`.
  - The same with a null feed hash is `Unchanged(backfillFeedHash=true)`.
  - A different feed hash gives `Update(CONTENT_CHANGED)`.
  - The same hash with a later `updatedAt` gives `Update(TIMESTAMP_ONLY)`.
  - An equal or earlier `updatedAt` is `Unchanged`.
  - A GUID match on a not-yet-linked feed gives `Link`, or `Update(TIMESTAMP_ONLY, wasLinked=false)` when the time advanced.
  - The link fallback gives `guidReplaced=true` and is counted `GUID_REPLACED`.
  - The GUID wins over a link that points at a different article.
  - An article claimed by GUID is not link-matched by another entry (counted `GUARDED_SHARED`).
  - Two existing articles with the same link key give no match.
  - Output is in document order.
  - `linkFallbacks` always has 3 keys.
  - The invariant: total decisions equals entries in.

**Verify (in the worktree):**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -B -s /tmp/argus-central-settings.xml clean verify
```

All tests must pass, with 0 skipped.

**Commit (in the worktree):** `feat: add full link cleaner, content hash and entry dedup resolver`.

### WP-C — Sources and feed-create API (Wave 1, worktree `../argus-p4-wpC`, branch `feat/argus-phase-4-wpC`)

**Owns:** `source/SourceController.java`, `SourceQueryService.java`, `SourceResponse.java`, `PatchSourceRequest.java`, `CountryCodes.java`, `SourceService.java`, `SourceRepository.java`, `feed/FeedRepository.java`, `FeedService.create` (only that method and its helpers), and the tests `SourceControllerTest`, `SourceQueryServiceTest` (if useful), `CountryCodesTest`, `SourceApiIT`, plus additions to `FeedServiceTest`/`FeedControllerTest` for `sourceId`.

**Read first:** `feed/api/FeedController.java`, `FeedResponse.java`, `FeedService.java`, `FeedControllerTest.java`, `security/SecurityConfig.java` (writes already need the admin key), `web/error/*`, `article/ArticleController.java` (page validation pattern) and `integration/FeedApiIT.java`.

**Deliverables:**
1. `GET /sources` returns `PagedModel<SourceResponse>`, ordered by id ascending, with the same `page`/`size` validation as `/feeds` (default 20, max 100, 400 when invalid).
2. `GET /sources/{id}` returns a `SourceResponse`, or 404 `SOURCE_NOT_FOUND`.
3. The response is `record SourceResponse(long id, String key, String name, @Nullable String homepage, @Nullable String country, long articleCount, List<FeedSummary> feeds)`.
   - `feeds` is sorted by id and comes from `FeedRepository.findBySourceIdInOrderByIdAsc(Collection<Long>)`.
   - `articleCount` comes from **one** grouped query per page: `SELECT source_id, count(*) FROM article WHERE source_id = ANY(:ids) GROUP BY source_id`. A source with no articles shows 0.
   - No per-row queries.
4. `PATCH /sources/{id}` takes `PatchSourceRequest(@Size(min=1,max=255) @Nullable String name, @Size(max=2048) @AbsoluteHttpUrl @Nullable String homepage, @Nullable String country)`.
   - Null means unchanged. All three null gives 400 `VALIDATION_FAILED`.
   - The name is stripped, and a blank name gives 400.
   - The homepage is stored as `StoredUrls.clean(homepage)`.
   - The country goes through `CountryCodes.normalise`: uppercase, must be in `Locale.getISOCountries()`, else 400 `VALIDATION_FAILED` on field `country`.
   - The update is one atomic SQL `UPDATE … SET name = COALESCE(:name, name), … , updated_at = :now WHERE id = :id`. Zero rows gives 404.
   - It returns the updated `SourceResponse`.
   - The key is immutable.
5. `POST /feeds` with `sourceId`:
   - When `sourceId != null`, load the source **before** the download, giving 404 `SOURCE_NOT_FOUND` if it doesn't exist. Use it instead of `SourceResolver` plus `findOrCreate`.
   - The create-fetch timer is tagged with that source's key.
   - Everything else is unchanged.
6. `CountryCodes` is a final class with `static String normalise(String raw)`. It uppercases the input, checks membership in `Locale.getISOCountries()`, and throws `ApiException.validationFailed("country", "must be an ISO 3166-1 alpha-2 code")` otherwise. WP-C owns it. WP-D runs at the same time and must not import it (see WP-D item 5). WP-H later switches WP-D over to it.

**Tests:**
- `SourceControllerTest` (`@WebMvcTest`, imports as in `FeedControllerTest`):
  - List shape and paging 400s.
  - 404 problem body with `code=SOURCE_NOT_FOUND`.
  - PATCH without the key gives 401 `ADMIN_KEY_REQUIRED`.
  - Invalid country and empty body give 400.
  - A blank name gives 400.
- `CountryCodesTest`: `gb` gives `GB`, `UK` is rejected, `ZM` is accepted.
- `SourceApiIT`:
  - Two feeds of one source show in `feeds`, with `articleCount` correct.
  - PATCH name, homepage and country persist.
  - `POST /feeds` with `sourceId` attaches the feed to that source even though the fixture's site link points elsewhere.
  - An unknown `sourceId` gives 404 with no feed row created and no fetch made (check `stub.requests()`).

**Verify:** full `clean verify` in the worktree.

**Commit:** `feat: add sources API and explicit sourceId on feed create`.

### WP-D — Articles API: feeds list and topic/country filters (Wave 1, worktree `../argus-p4-wpD`, branch `feat/argus-phase-4-wpD`)

**Owns:** `article/Article.java`, `ArticleRepository.java`, `ArticleQueryService.java`, `ArticleController.java`, `ArticleResponse.java`, `ArticleSpecifications.java`, `ArticleFilter.java`, and their tests, plus a new `ArticleFilterIT`.

**Deliverables:**
1. `Article` gains a read-only mapping:
   ```java
   @ManyToMany(fetch = FetchType.LAZY)
   @JoinTable(name = "article_feed", joinColumns = @JoinColumn(name = "article_id"),
              inverseJoinColumns = @JoinColumn(name = "feed_id"))
   @BatchSize(size = 100)
   private Set<Feed> feeds = Set.of();
   ```
   - Add `getFeeds()`. The entity stays `@Immutable`.
   - Fetching the feeds of one page must be **one** extra query, not N. An IT asserts this with Hibernate statistics or a query-count check; if that proves impractical, assert it via `spring.jpa.properties.hibernate.generate_statistics` in a test-only property.
2. `ArticleResponse` gains `List<FeedSummary> feeds`, sorted by id, placed after `source`. It is mapped inside the read-only transaction.
3. `ArticleRepository extends JpaRepository<Article, Long>, JpaSpecificationExecutor<Article>`.
   - Replace `findPage` with an override `@EntityGraph(attributePaths = "source") Page<Article> findAll(Specification<Article> spec, Pageable pageable)`.
   - If the entity graph is not honoured, or breaks the count query, STOP and report with the SQL log.
4. `ArticleSpecifications`:
   - `topicIn(Collection<Topic>)` is an `EXISTS` subquery over `inner.join("feeds")`, so no duplicate rows and no `DISTINCT`.
   - `countryIn(Collection<String>)` is `root.get("source").get("country").in(...)`.
   - An empty collection gives `Specification.unrestricted()`. Verify that this method exists in Spring Data JPA 4.1.1 with `javap`; if it doesn't, use a lambda that returns `null`.
5. `ArticleController.list` gains `@RequestParam(required = false) List<Topic> topic` and `@RequestParam(required = false) List<String> country`.
   - Countries are validated by a private static helper in `ArticleController`. It uppercases the value and requires membership in `Locale.getISOCountries()`, otherwise 400 via `ApiException.validationFailed("country", "must be an ISO 3166-1 alpha-2 code")`. This is temporary, because WP-C's `CountryCodes` doesn't exist in this worktree; WP-H replaces it. Do **not** create `CountryCodes` here.
   - An unknown topic gives 400 through the existing type-mismatch handling. Verify with a test.
   - The ordering stays newest first.
6. `ArticleQueryService.list(ArticleFilter filter, int page, int size)`, where `record ArticleFilter(Set<Topic> topics, Set<String> countries)`.

**Tests:**
- `ArticleControllerTest`: params bind as repeatable, a bad country gives 400 with the field, a bad topic gives 400, and the response contains `feeds`.
- `ArticleResponseTest`: feeds are sorted.
- `ArticleFilterIT`, which inserts rows directly with `jdbcClient` (sources with countries, feeds with topics, articles, `article_feed` links). **It does not use the ingest path,** because WP-B is not merged yet.
  - An article linked to a NEWS feed and a SPORT feed appears once under `topic=NEWS`, once under `topic=SPORT`, and once under `topic=NEWS&topic=SPORT`. `page.totalElements` is correct in each case.
  - The country filter uses the source.
  - A combined topic and country filter.
  - Feeds are listed in the response.
  - Paging with filters returns no duplicates.
  - One batch query for feeds.

**Verify:** full `clean verify` in the worktree.

**Commit:** `feat: list article feeds and filter articles by topic and country`.

### Gate G1 (orchestrator)

1. Merge in this order: WP-A, WP-C, WP-D. Each is `--no-ff` with message `merge: phase 4 WP-<X> <title>`.
2. Resolve conflicts. The only expected one is test-file context near the `CreateFeedRequest` and `FeedService` imports. Keep both sides.
3. Run G0's command again (this is G1). Then remove the wave-1 worktrees.
4. Commit any mechanical merge fix as `fix: reconcile phase 4 wave 1 merge`.

### WP-B — Persistence and ingestion (Wave 2, worktree `../argus-p4-wpB`, branch `feat/argus-phase-4-wpB`)

**Owns:**
- `source/SourceLock.java`, `ingest/ExistingArticleLoader.java`, `ingest/DecisionApplier.java`, `ingest/ArticlePersister.java`, `ingest/IngestTelemetry.java`, `ingest/FeedIngestService.java`.
- `article/ArticleWriter.java` (`git mv` from `ArticleInserter`), `article/NewArticle.java`, `article/ArticleEdit.java`, the deletion of `article/InsertOutcome.java`.
- `FeedService.delete`, and the one bean line in `config/ArgusConfiguration.java`.
- Tests: `ArticlePersisterTest` (rewrite), `DecisionApplierTest`, `IngestTelemetryTest`, `FeedIngestServiceTest`, `ArticleWriterIT` (new; `FeedInserterIT` tests feeds and is not touched), `SourceLockIT`, `DedupIngestIT`, `ConcurrentIngestIT`, `ConflictAbsorptionIT`, `DedupMetricsIT`.
- New fixtures under `src/test/resources/feeds/` whose names start with `p4-`.

**Read first:** §4.4–§4.6, all of `ingest/`, `article/ArticleInserter.java`, `feed/api/FeedService.java`, `integration/FeedIngestIT.java`, `IngestMetricsIT.java`, `IngestObservabilityIT.java`, `testsupport/FeedStubServer.java`, and `src/test/resources/feeds/bbc-like-rss2.xml`.

**Deliverables:** implement §4.5 and the telemetry in §4.6.
- `IngestTelemetry.recordLinkFallbacks(sourceKey, Map)` increments all three outcomes, including by 0.
- `IngestTelemetry.lockWait(String sourceKey, long sourceId, Supplier<Duration> acquire)` wraps the call in the `argus.lock.wait` span (attributes `source.id`, `contended`), records the timer, and returns the wait.
- `FeedIngestService.persist` calls `recordDecisions` and `recordLinkFallbacks`.
- Delete `ArticlePersisterTest.entriesAreInsertedInAscendingGuidKeyOrderWhateverTheFeedOrder`. The order is now `DecisionApplier`'s job, so test it there.

**Fixtures.** Use RSS 2.0 and Atom, modelled on `bbc-like-rss2.xml`, with channel `<link>` values on `.example.test` domains:
- `p4-shared-news.xml` and `p4-shared-world.xml`: same channel link `https://news.example.test/`, one common item (same GUID), plus one unique item each.
- `p4-guid-v1.xml` and `p4-guid-v2.xml`: same link `https://news.example.test/a/1?utm_source=x`, different GUIDs.
- `p4-homepage-links.xml`: three items with distinct GUIDs, all linking to `https://blog.example.test/`, with channel link `https://blog.example.test/`.
- `p4-edited-v1.xml` and `p4-edited-v2.xml`: same GUID, with the title changed in v2.
- `p4-timestamp-v1.xml` and `p4-timestamp-v2.xml`: Atom, same content, with `<updated>` later in v2.
- `p4-batch-dup.xml`: the same GUID twice, with different `<updated>` values.

**Tests:**
- `SourceLockIT`:
  - Uncontended returns `Duration.ZERO`.
  - Contended: a second thread holds the lock in its own transaction (`TransactionTemplate`) until a latch is released. `acquire` blocks, then returns a wait of at least the hold time minus 50 ms.
  - Calling `acquire` outside a transaction throws `IllegalTransactionStateException`.
- `DedupIngestIT` (through `createFeedFrom` and `POST /feeds/{id}/refresh`):
  - **AC: shared article.** The same article in two feeds of one source is stored once, with two `article_feed` rows, and appears under both topics via `/articles?topic=`. Report counts: the second feed's common item is `linked`.
  - **AC: GUID change.** A changed GUID with the same link updates the existing article: the row count stays the same, `guid_key` is replaced, and the link-fallback counter shows `guid_replaced`.
  - **AC: homepage links.** A homepage-link feed keeps its 3 distinct items (3 rows), and the fallback counter shows `guarded_homepage` when it applies.
  - **AC: edit in place.** An edited upstream article gets the new title on the same id with `fetched_at` unchanged, counted `updated{reason=content_changed}`.
  - **Timestamp-only edit:** `updated_at_upstream` advances, the title is unchanged, counted `updated{reason=timestamp_only}`.
  - **Steady state:** a second refresh of an unchanged feed leaves `modified_at` unchanged on every row and counts everything as `unchanged`.
  - **Batch duplicate:** one row, and the counter shows `skipped{reason=batch_duplicate}`.
  - **Cross-feed variance:** feed B has the same GUID as feed A but a different title. B is `linked`, A's title is kept, and refreshing both twice gives no `updated` at all. This is the ping-pong guard.
- `ConcurrentIngestIT` (**AC: concurrency**):
  - (a) Two feeds of one source with 20 overlapping items, ingested at the same time from two threads released by a `CyclicBarrier`, repeated 5 times on fresh tables. Every time, `SELECT count(*) … GROUP BY source_id, guid_key HAVING count(*) > 1` returns nothing, and `argus.ingest.lock.wait` count ≥ 2.
  - (b) A deterministic contended case: hold the lock from the test as in `SourceLockIT`, start a refresh on another thread, assert it is waiting (`SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND wait_event = 'advisory'` reaches 1), release, and assert the refresh completes and the timer's max is > 0.
- `ConflictAbsorptionIT` (**AC: forced conflicting insert**): call `DecisionApplier.apply` directly inside a transaction, with an `Insert` decision for a `(source, guidKey)` that already exists. Assert no exception, one row, the link exists, counts `updated{insert_conflict}`, and a WARN log line (use `testsupport/LogCapture`).
- `ArticleWriterIT`:
  - `rewriteContent` keeps `fetched_at`.
  - A null author or image does not erase the stored one.
  - `effective_at` never decreases, and an undated entry never bumps it.
  - `published_at` keeps the stored value.
  - `link` refreshes the hash only when it differs.
- `DedupMetricsIT`:
  - Every decision and reason value from §4.6, with exact tags.
  - All three fallback outcomes exist after one create.
  - The lock-wait timer tagged by source.
  - No meter carries `feed_id`, `url` or `guid`.
  - The `argus.lock.wait` and `argus.resolve` spans exist under `argus.persist`, with attribute `source.id`. Reuse `SpanCollectorConfig` as `IngestObservabilityIT` does.
- `IngestMetricsIT`: update the expectations for the new decision vocabulary. Do **not** edit `everyCataloguedMeterExistsWithExactlyTheCataloguedTags`; it goes green when WP-E's catalogue merges.

**Before WP-E merges,** the catalogue lacks the two new meters, so `IngestMetricsIT.noMeterCarries…` and the catalogue tests may report uncatalogued meters in this worktree. If they do, report the exact failing tests in the final message. **Do not change catalogue files.** G2 must be green after all four merges.

**Verify:** full `clean verify` in the worktree. Any expected failure must be limited to the catalogue tests named above.

**Commit:** `feat: per-source advisory-locked dedup ingest with edits, links and fallbacks`.

### WP-E — Catalogue and dashboard (Wave 2, worktree `../argus-p4-wpE`, branch `feat/argus-phase-4-wpE`)

**Owns:** `observability/MetricCatalogue.java`, `MetricCatalogueTest`, `grafana/dashboards/argus-observability.json` and `ArgusDashboardTest`.

**Deliverables:**
1. Catalogue entries:
   - `new MeterSpec(MetricNames.INGEST_LINK_FALLBACK, MeterKind.COUNTER, null, Set.of(SOURCE, OUTCOME))`
   - `new MeterSpec(MetricNames.INGEST_LOCK_WAIT, MeterKind.TIMER, null, Set.of(SOURCE))`
2. Edit the dashboard JSON **with a Python script** that you commit nowhere (run it, then delete it). Do not hand-edit JSON positions. Keep the file's existing formatting (2-space indent) so the diff stays readable.
   - Shift `gridPos.y` of every panel with `y >= 52` (the *Data quality* row onward) down by **25**. Do this before inserting anything.
   - Insert a new row panel, **id 53**, `title: "Deduplication"`, at `y=52`, directly after the *Ingestion pipeline* row's panels in the `panels` array.
   - **Move panel 34** ("Entry decisions") into the Deduplication row at `y=53, x=0, w=12, h=8`, set `fieldConfig.defaults.custom.stacking.mode = "normal"`, and update its description.
   - Widen panels 32 and 33 to `w=12` at `x=0` and `x=12` (y=44), so that row has no gap.
   - New panels. Every one gets a non-blank `description`, `datasource: {"type":"prometheus","uid":"${DS_PROMETHEUS}"}`, `job="argus"` and `source=~"$source"` selectors, and `$__rate_interval`, in the same style as the existing panels.

     | id | gridPos | title | expr | unit |
     |---|---|---|---|---|
     | 54 | y53 x12 w12 h8 | Skip reasons | `sum by (reason) (rate(argus_ingest_entries_total{job="argus",source=~"$source",decision="skipped"}[$__rate_interval]))` | ops |
     | 55 | y61 x0 w8 h8 | Link-fallback outcomes | `sum by (outcome) (rate(argus_ingest_link_fallback_total{job="argus",source=~"$source"}[$__rate_interval]))` | ops |
     | 56 | y61 x8 w8 h8 | Lock wait p95 by source | `histogram_quantile(0.95, sum by (le, source) (rate(argus_ingest_lock_wait_seconds_bucket{job="argus",source=~"$source"}[$__rate_interval])))` | s |
     | 57 | y61 x16 w8 h8 | Updates by reason | `sum by (reason) (rate(argus_ingest_entries_total{job="argus",source=~"$source",decision="updated"}[$__rate_interval]))` | ops |
     | 58 | y69 x0 w12 h8 | Duplicate pressure | `sum(rate(argus_ingest_entries_total{job="argus",source=~"$source",decision=~"linked\|unchanged"}[$__rate_interval])) / clamp_min(sum(rate(argus_ingest_entries_total{job="argus",source=~"$source"}[$__rate_interval])), 0.000000001)` | percentunit |
     | 59 | y69 x12 w12 h8 | Cross-feed overlap | `sum(rate(argus_ingest_entries_total{job="argus",source=~"$source",decision="linked"}[$__rate_interval])) / clamp_min(sum(rate(argus_ingest_entries_total{job="argus",source=~"$source",decision=~"inserted\|linked"}[$__rate_interval])), 0.000000001)` | percentunit |

     The row spans y=52 to y=77 (1 + 8 + 8 + 8 = 25), which is why the shift is 25. At the end, the script must assert that no two panels overlap and that the *Data quality* row is at y=77.
   - Bump the dashboard `version` by 1.
3. `ArgusDashboardTest`:
   - The row order includes "Deduplication" between "Ingestion pipeline" and "Data quality".
   - Panels 34 and 53–59 exist in that row with the expressions above.
   - No overlapping `gridPos`.
   - Catalogue coverage stays on.

**Verify:** `./mvnw … clean verify -Dit.test=NoSuchIT -Dfailsafe.failIfNoSpecifiedTests=false`. Unit tests only, because the ITs need WP-B. The dashboard and catalogue unit tests must pass. Then run the full verify and report which ITs fail. They should only be the catalogue-presence ITs, which go green with WP-B.

**Commit:** `feat: add deduplication meters to the catalogue and a Deduplication dashboard row`.

### WP-F — Re-key migration (Wave 2, worktree `../argus-p4-wpF`, branch `feat/argus-phase-4-wpF`)

**Owns:** `migration/RekeyArticlesChange.java` (replacing the WP-0 placeholder), `migration/ArticleRekeyer.java`, `migration/RekeyReport.java`, `ArticleRekeyerIT` and `RekeyMigrationIT`.

**Deliverables:** implement §4.9.

**Tests:**
- `RekeyMigrationIT`. It extends `AbstractIntegrationTest` to get the container, then uses `ScratchDatabase`:
  1. `migrateFirst(7, "test")` applies 01–07.
  2. Insert phase-2-shaped rows with **interim keys**, as the old `Links.clean` would have produced them:
     - A source and two feeds.
     - Article A: raw GUID `https://www.news.example.test/a/1#0`, link `https://www.news.example.test/a/1?at_medium=rss`, guid key `https://www.news.example.test/a/1`.
     - Article B: raw GUID `http://news.example.test/a/1/`, a different interim key. It collides with A after re-keying, has a later id, and is linked to feed 2.
     - Article C: GUID `urn:uuid:123`, a non-URL that must be unchanged.
     - Articles D and E: different GUIDs, the same link. They must stay separate.
  3. `migrate("test")` applies the rest. This proves the custom change loads inside a real Liquibase run.
  4. Assert:
     - B is deleted and A survives with links to both feeds (the earliest `first_seen_at` kept).
     - A's `guid_key` is `https://news.example.test/a/1`.
     - Every `article.content_hash` and `article_feed.content_hash` is non-null and equals `ContentHash.of(...)`.
     - C's key is unchanged.
     - D and E both remain.
     - `databasechangelog` has `08-rekey-articles` exactly once.
  5. **Idempotence:** run `new ArticleRekeyer().rekey(conn)` again and assert the report shows 0 re-keyed and 0 collapsed, with no row changed.
  6. **AC "first poll after Phase 4 creates no duplicates":** a pure-level check. Run the resolver (`EntryDedupResolver`) over `ParsedEntry`s equal to the stored rows, with an `ExistingArticleLookup` reading the scratch database via the same SQL as `ExistingArticleLoader`. Assert zero `Insert` decisions and zero `Update` decisions.
- `ArticleRekeyerIT`: the chain case through the temporary-key phase. A survivor's new key equals another survivor's old key.

**Verify:** full `clean verify` in the worktree. If WP-B is not present, the ingest path still uses the old persister. That doesn't matter, because your ITs use the scratch database.

**Commit:** `feat: re-key stored articles with the phase 4 cleaner and backfill content hashes`.

### WP-G — Seed (Wave 2, worktree `../argus-p4-wpG`, branch `feat/argus-phase-4-wpG`)

**Owns:** `changes/09-seed-sources-and-feeds.yaml` (the body only; keep the id and the `context: "!test"`) and `SeedIT`.

**Deliverables:**
- The §4.8 SQL as two `sql` changes inside the one changeset.
- `splitStatements: false` is unnecessary. Use one `sql` change per statement.

**Tests (`SeedIT`, extends `AbstractIntegrationTest` for the container, uses `ScratchDatabase`):**
1. **Seeded.** `migrate("")` runs with no contexts, like prod. Assert 17 sources and 20 feeds with the exact keys, names, countries, topics and URLs from §4.8, every feed enabled, and the BBC source holding 3 feeds.
2. **Deleted stays deleted.** Delete the CBC Sports feed with SQL, run `migrate("")` again, and assert it is still gone and the counts are unchanged. The changeset must not re-run: check `databasechangelog` has it once.
3. **Test context skips it.** A second scratch database with `migrate("test")` has 0 sources and 0 feeds.
4. **Prod interaction.** On a fresh scratch database:
   - `migrateFirst(8, "")` applies everything up to and including 08.
   - Insert `source('bbc.co.uk', name='bbc.co.uk', country NULL)` and feed BBC News with the same URL.
   - `migrate("")`.
   - Assert there is still one BBC News feed, the BBC source now has `name='BBC'` and `country='GB'`, and BBC World and Football joined the same source.
   - With an owner-edited `name='BBC Online'`, the name is kept.
5. **Stored form.** For every seeded feed URL, `StoredUrls.clean(url).equals(url)`.
6. **Key consistency.** For every seeded feed, `SourceResolver.keyFor(null, URI.create(url))` equals its source key, except for an explicit exceptions map `{BBC feeds → "bbc.co.uk"}`.

**Verify:** full `clean verify` in the worktree.

**Commit:** `feat: seed the verified sources and feeds once`.

### Gate G2 (orchestrator)

1. Merge in this order: WP-B, WP-E, WP-F, WP-G. No file overlaps are expected.
2. Run the stub check (`grep` for `UnsupportedOperationException("WP-"`). It must print nothing.
3. Run the full gate. Everything must be green, including `IngestMetricsIT.everyCataloguedMeterExistsWithExactlyTheCataloguedTags`.
4. Remove the worktrees.

### WP-H — Tidy-ups and docs (Wave 3, serial, main checkout)

**Owns:** `ArticleController`/WP-D's inline country check (replace it with `CountryCodes.normalise` and delete the duplicate), `README.md`, `observability/README.md` and `plans/argus.md` (tick the Phase 4 boxes only when each is covered by a named test).

**Docs:**
- `README.md`:
  - The sources endpoints and the `sourceId` on create.
  - The `topic`/`country` filters and the `feeds` field.
  - The new `IngestReport` fields `updated` and `linked`, and their reasons.
  - The seed behaviour: it runs once, deleted feeds stay deleted, the `!test` context.
  - The fixed tracking-parameter list, and the rule that changing it needs a re-key changeset.
  - The deviation that OpenAPI annotations are deferred to phase 13.
- `observability/README.md`: what each Deduplication panel answers, plus the Phase 4 Gate B checks in §8.

**Verify:** the full gate (G3).

**Commit:** `docs: document phase 4 sources, dedup, seed and dashboard`. The orchestrator stops there and reports to the owner.

---

## 6. Acceptance criteria → tests

| Phase 4 AC (`plans/argus.md`) | Test |
|---|---|
| Link-cleaner rules and idempotence | `LinkCleanerTest`, `KeyStabilityTest` |
| Source-resolver cases incl. multi-part suffixes | `SourceResolverTest` (existing) + `SeedIT` key consistency. WP-A adds `co.zm`, `com.au` and `blogspot.com` cases to `SourceResolverTest` **only if they are missing**; otherwise it reports that. `SourceResolverTest` is co-owned by WP-A for that addition. |
| One test per resolver rule | `EntryDedupResolverTest` |
| Same article in two feeds stored once, two links, both topics | `DedupIngestIT`, `ArticleFilterIT` |
| Concurrent same-source ingest, no duplicates; forced conflict absorbed | `ConcurrentIngestIT`, `ConflictAbsorptionIT` |
| Changed GUID same link updates; homepage-link feed keeps items | `DedupIngestIT` |
| Edited upstream article updated in place | `DedupIngestIT`, `ArticleWriterIT` |
| Seed on a fresh database; deleted seeded feed stays deleted | `SeedIT` |
| Meter-registry tests for decisions, fallbacks and lock wait | `DedupMetricsIT`, `IngestTelemetryTest` |
| Re-key migration, no duplicates on the first poll | `RekeyMigrationIT`, `ArticleRekeyerIT` |

---

## 7. Final verification and review (owner side)

After G3, Claude runs the review gate on `git diff main...feat/argus-phase-4`, with six reviewers:
- security
- comments
- simplifier
- simplicity/DRY
- correctness
- conventions

The orchestrator does not run reviews. "Done" for the build means all of the following:
- G3 is green with 0 skipped tests.
- Every row in §6 has a passing named test.
- `git log main..feat/argus-phase-4` shows the WP commits and merge commits only, with no trailers.
- `git status` is clean apart from the owner's untracked files.
- `git reflog` shows no reset, rebase or amend.

---

## 8. Deploy runbook (owner)

1. Run the read-only check on prod: `SELECT id, key, name, homepage_url, country FROM source; SELECT id, source_id, url FROM feed;`. Confirm the BBC source key is `bbc.co.uk` and the feed URL is `https://feeds.bbci.co.uk/news/rss.xml`.
2. Take a backup: `pg_dump -t article -t article_feed -t source -t feed argus > argus-pre-phase4.sql`.
3. Deploy once with stop-first: `docker service update --update-order stop-first --image <phase-4 image> argus_argus`. The compose file is unchanged.
4. In the logs, confirm the Liquibase confirmation line `Re-keyed articles: …` and that changeset 09 ran.
5. Gate B checks:
   - `GET /news/v2/sources` lists 17 sources, and BBC has 3 feeds and country GB.
   - The first poll ingests the seeds. The "New articles 24h" stat jumps, which is expected.
   - BBC News, World and Football produce `linked` decisions, and Cross-feed overlap is above 0.
   - Lock wait p95 shows values for `bbc.co.uk` and `cbc.ca`.
   - A second poll is mostly `unchanged`, with `updated{timestamp_only}` near 0. If a feed shows a steady stream of `timestamp_only`, note it.
   - The WordPress seeds (CityNews, Global News and others) send ETags, so the 304 ratio rises on the second poll. This closes the phase 3 note.
6. Import the dashboard JSON (version bumped) into the Grafana folder "Argus" through the MCP/UI.
7. Rollback, if needed: redeploy the phase 3 image. The nullable `content_hash` columns keep it working. Articles it inserts carry interim keys; restore from the dump if duplicates matter.
