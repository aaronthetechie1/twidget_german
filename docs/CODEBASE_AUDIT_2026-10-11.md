# Codebase audit — 11 October 2026

The best return comes from protecting storage mutations, reducing repeated work in the UI, and optimizing bundled images and the variable font. A wholesale rewrite is unnecessary. Existing policies, compatibility components, and tests provide a good foundation for incremental changes.

This is an audit and implementation roadmap. Application and bridge source were left unchanged. The audit includes the existing uncommitted working tree, based on commit `3ec75d7e`; findings about new files are not claims about the last published release.

## Evidence and limits

Reviewed Android build configuration, manifests, dependency declarations, shrinker outputs, persistence, networking, refresh coordination, dashboard rendering, scheduling, follower browsing, widget artwork, fonts, Brief integration, bridge routes/repositories, and CI. Inventoried all production source and resources, and ran the checks below. Larger controllers were assessed through their state, method structure, and important execution paths; this is not a claim that every possible execution path was exercised.

| Check | Result |
| --- | --- |
| Android production source inventory | 179 Kotlin/Java files, 35,856 lines |
| Bridge production inventory | 6 JavaScript files, 3,384 lines |
| `testGithubDebugUnitTest` | 350 tests, zero failures/errors/skips |
| Bridge syntax check | Passed |
| Bridge `npm test` | 32 tests passed, including route smoke tests |
| `lintGithubDebug` | Failed: 4 error reports at 2 source locations, 723 warnings |
| `assembleGithubRelease assemblePlayRelease` | Both passed, with R8 and resource shrinking enabled |
| Fresh GitHub release APK | 24,080,243 bytes: 24.08 MB / 22.96 MiB |
| Fresh Play universal release APK | 24,058,242 bytes: 24.06 MB / 22.94 MiB |
| Local bridge concurrency reproduction | Confirmed a lost history sample and a lost independent metadata update |

Android checks used the installed JDK 26 and existing offline dependency caches. Bridge tests used local Node 26.4.0; CI pins Node 22.21.0, so this run does not establish parity with the pinned CI runtime. Initial sandbox failures were resolved by running the same checks with cache/loopback access. They were environmental failures, not test failures.

No app was installed, user data reset, remote service changed, or message/issue published. No fresh on-device frame, startup, battery, or allocation benchmark was run. No live PostgreSQL query plans were collected. Runtime improvements below are supported by concrete code paths but have no claimed millisecond or battery percentage improvement. APK size is not Play's device-specific download size or total installed storage. Old AAB outputs were inspected as corroboration only; their mapping metadata must not be counted as installed application code.

## Priorities

P1 means correctness or the most valuable immediate work. P2 means worthwhile efficiency and maintenance work. P3 means later work that should follow profiling or simpler improvements. Effort is relative, not a delivery promise.

| ID | Priority | Opportunity | Evidence | Effort |
| --- | --- | --- | --- | --- |
| A01 | P1 | Fix debug lint blockers | Fresh lint failure | Small |
| A02 | P1 | Make bridge history and metadata mutations atomic | Reproduced data loss | Medium |
| A03 | P1 | Serialize Android account/history mutations | Concurrent callers + unprotected compound operations | Medium |
| A04 | P1 | Batch scheduling persistence and move it off the UI thread | Main-thread commits and per-post whole-store writes | Medium |
| A05 | P1 | Compress retained artwork and pin unused font axes | Temporary size experiments | Small–medium |
| A06 | P2 | Build one dashboard snapshot; update existing cards | Repeated parsing and full view reconstruction | Medium |
| A07 | P2 | Share image requests and decoded bitmap cache | Repeated decoding, shared executor, no shared request deduplication | Medium |
| A08 | P2 | Share timeline retrieval between analytics and streaks | Duplicate provider pagination | Medium |
| A09 | P2 | Stop whole-window font scans on every layout | Global layout traversal | Medium |
| A10 | P2 | Rank followers once, search without reranking | Disk read at launch and sort per query | Small–medium |
| A11 | P2 | Narrow broad shrinker rules | Retained unused component classes in fresh R8 seeds | Small–medium |
| A12 | P2 | Split large controllers along responsibility boundaries | Source structure and dependency coupling | Medium, incremental |
| A13 | P2 | Bound HTTP responses and make cancellation/cleanup explicit | Unbounded text reads and no task cancellation API | Medium |
| A14 | P2/P3 | Improve bridge storage scaling | Synchronous full-store writes, repeated sorting, query/index mismatch | Medium |
| A15 | P2 | Add targeted release/device and performance gates | CI compiles device tests but does not execute them | Medium |
| A16 | P3 | Remove obsolete source resources and tidy build plumbing | 288 unused-resource warnings, incomplete syntax-check file list | Small, incremental |

## A01 — Restore a clean lint gate

**Location:** [AnimatedTitleView.kt](../app/src/main/java/com/tjg/twidget/ui/AnimatedTitleView.kt), lines 44–51.

The new title view creates an inline array of framework attributes and uses literal indexes in `getDimension(1, ...)` and `getColor(2, ...)`. Lint reports these two locations twice, for four error reports, and fails the CI debug lint task. Compilation, unit tests, and release assembly still pass.

Define a named styleable in `attrs.xml` and use generated indexes, or document a tightly scoped ResourceType suppression if retaining this intentional lookup. Prefer generated indexes because array ordering becomes maintainable. Do not broadly suppress ResourceType or baseline these errors to make CI green.

**Acceptance:** debug lint has zero errors; title size/color behavior remains unchanged in light/dark mode and with XML attributes. Treat the 723 warnings as a triage list: compatibility-related KTX suggestions and cosmetic warnings are not equivalent to correctness failures.

## A02 — Protect bridge compound mutations

**Locations:** [infrastructure.js](../bridge/src/infrastructure.js), local `storeSamples` at line 150, local `setMeta` at 146, PostgreSQL `setMeta` at 489; [server.js](../bridge/src/server.js), `refreshBanger` and Wayback/enrollment metadata updates.

Local `storeSamples` awaits the existing history before merging and replacing it. Two calls can both read the same previous history, then each replace the account array. Synchronous file writes do not prevent this logical interleaving.

An isolated reproduction seeded `[100]`, then concurrently appended daily observations `101` and `102` through `Promise.all`. Expected history: `[100, 101, 102]`. Actual history: `[100, 102]`. The reproduction wrote only a temporary audit file.

Metadata has a related problem. Callers read a complete metadata object, add a field, and replace the full object. Two callers starting with `{ existing: true }` and independently adding `waybackAt` and `banger` leave only the later update. PostgreSQL also replaces the entire JSONB value, so moving to PostgreSQL alone does not resolve metadata lost updates.

Use a per-account mutation queue/lock for local compound operations. Replace whole-object metadata writes with explicit field patch/update operations, implemented atomically in PostgreSQL. Keep migrations that deliberately replace metadata separate from normal patches. Protect deletion versus an in-flight mutation too; a stale updater must not recreate a removed account.

**Acceptance:** deterministic overlapping sample updates preserve both samples; independent metadata patches preserve both fields; estimate/real precedence remains unchanged; deletion races do not resurrect accounts. Use a real PostgreSQL integration test for transaction semantics, since the existing SQL mock tests cannot demonstrate them.

## A03 — Protect Android storage before expanding concurrency

**Locations:** [TwidgetStore.kt](../app/src/main/java/com/tjg/twidget/data/TwidgetStore.kt), `addAccount` at 491, `removeAccount` at 503, `saveStats` at 732, `importFollowerHistory` at 747; [DailyStreakStore.kt](../app/src/main/java/com/tjg/twidget/data/DailyStreakStore.kt), line 26.

These methods read existing collections, merge/edit them, and write replacements without a shared mutation lock. RefreshWorker, widget tap refresh, dashboard refresh, imports, and account management can overlap. SharedPreferences makes individual operations safe; it does not make this entire sequence atomic. A late response can also overwrite newer stats or recreate an account after removal.

Unlike the bridge local-store issue, Android data loss was not reproduced on a device in this audit. This is a concrete race risk inferred from unsynchronized operations and independent callers.

Introduce repository-owned serialization for related account/profile/history changes and streak merges. Account membership changes and same-account history mutations need a consistent lock order. Reject obsolete responses using the account's generation/removal state and source timestamp. Add an equality fast path so refreshing an existing account does not rewrite the account list every time.

**Acceptance:** refresh/import overlap preserves trusted/imported precedence; add/remove overlap preserves the correct account set; removal cannot be undone by a stale response; older responses cannot replace newer snapshots. Keep legacy migration, monthly anchors, honest unknown metrics, and first-observation delta behavior intact.

## A04 — Batch scheduling writes and remove hidden work from reads

**Locations:** [ScheduleStore.kt](../app/src/main/java/com/tjg/twidget/schedule/ScheduleStore.kt), lines 140–174; [ScheduleQueueHostActivity.kt](../app/src/main/java/com/tjg/twidget/schedule/ScheduleQueueHostActivity.kt), `renderQueue`, `bulkSetPinned`, `togglePin`, `movePostsToTrash`; [BufferScheduleSync.kt](../app/src/main/java/com/tjg/twidget/schedule/BufferScheduleSync.kt), `reconcileRemotePosts`.

ScheduleStore correctly has a shared lock and durable writes. However, every mutation reparses and serializes the entire schedule list and calls synchronous `commit()`. Pinning and bulk UI actions invoke these methods directly from click handlers. Buffer reconciliation calls `upsert` once per remote post; a batch of N posts can produce N full-store serializations and commits. Read methods can purge trash, release/delete media, reconcile fallback states, and commit changes.

Add a transaction/batch mutation API that reads once, applies all changes, writes once, and releases media after successful persistence. Perform repository I/O on a background dispatcher/executor and publish a snapshot to the UI. Separate maintenance/reconciliation from ordinary reads. Keep `commit`-style durability for scheduling; replacing it blindly with `apply()` would change failure and crash behavior.

A database may eventually suit a large queue, but a batch API and read snapshot solve the immediate problem with less migration risk.

**Acceptance:** bulk pin/trash/restore and Buffer reconciliation make one durable commit per batch; no disk commit occurs on the UI thread; failed persistence preserves media; notifications are emitted once after a committed terminal transition; reminders, trash retention, local URI grants, and Buffer draft matching retain their existing semantics.

## A05 — The largest measured size opportunity

Fresh GitHub APK entries were identified using `aapt2 dump resources` because R8 obfuscates resource filenames. The ten retained PNG variants and the Google Sans Flex font account for about 15.33 MB of APK entry bytes. The APK's compressed DEX is only 2.57 MB and native entries total 0.225 MB. Artwork/font work should precede speculative dependency removal.

Temporary experiments used `cwebp -lossless -exact -m 6` and FontTools partial variable-font instancing. Source assets were not replaced.

| Retained asset group | Current APK entry bytes | Estimated replacement ZIP bytes | Potential reduction |
| --- | ---: | ---: | ---: |
| Notice header, four screen/theme variants | 7,175,149 | 4,886,664 | 2,288,485 |
| Widget device artwork, four theme/style variants | 4,518,540 | 3,117,490 | 1,401,050 |
| Brief settings preview, two themes | 891,401 | 605,545 | 285,856 |
| Google Sans Flex | 2,743,362 | 525,761 | 2,217,601 |
| **Total** | **15,328,452** | **9,135,460** | **6,192,992** |

The estimate is roughly 6.2 MB, or 25.7% of the current GitHub APK. That would put an otherwise identical universal APK near 17.9 MB. This is an archive-entry estimate, not an optimized APK build or Play download guarantee. WebP storage/compression decisions, resource metadata, and signing/alignment can change the final result.

**Artwork:** retain all variants and original dimensions initially. Lossless WebP preserves pixel content; it does not reduce decoded bitmap memory. The 2560×1440 tablet header alone can occupy approximately 14.1 MiB as ARGB_8888. After compression, separately evaluate appropriately sized decodes or density variants, with screenshots on high-density/foldable devices. Android supports this format throughout the app's API 26+ range; [Android's size guidance](https://developer.android.com/topic/performance/reduce-apk-size) recommends assessing WebP for packaged images.

**Font:** the 4,607,592-byte font has six axes. The app always requests `GRAD=0` and `opsz=18`, while varying weight, width, roundness, and slant. Pinning only the two constant axes produced a 921,828-byte font with all 616 original glyphs and the four variable axes still present. This removes unused variation data rather than removing languages or glyphs. Review all callers, update obsolete axis requests, retain font notices, and compare Android text metrics/rendering before adopting the output. Preserve all existing ranges for the four active axes.

The two small static Google Sans font files are already absent from the inspected release resource table. Deleting them helps source clarity but is not an additional release saving. Keep One UI Sans for devices without Samsung's system font; removing it would change behavior.

**Acceptance:** rebuild both distributions; compare resource contents and APK size; screenshot all font selections, dynamic widget typography, italic text, light/dark artwork, phone/tablet/foldable previews, English/German/French, and arbitrary account/post text. No glyph subsetting or supported-language removal is proposed.

## A06 — Make the dashboard consume one snapshot

**Locations:** [MainDashboardBinder.kt](../app/src/main/java/com/tjg/twidget/main/MainDashboardBinder.kt), `bindPage` at 235 and `removeAllViews` at 300; [TwidgetStore.kt](../app/src/main/java/com/tjg/twidget/data/TwidgetStore.kt), `fullHistory` at 590, `savedHistory` at 942, `todayDelta` at 780; [MainSyncController.kt](../app/src/main/java/com/tjg/twidget/main/MainSyncController.kt).

One bind loads current stats, ranged history, chart history, and full history separately. Those history getters each parse stored JSON, normalize/merge/sort samples, and allocate calendars. Each metric card's `todayDelta` repeats history and stats retrieval. Settings getters invoked during this work also migrate/check credential state and decrypt configured credentials.

Every dashboard bind then removes and recreates every card. Analytics and streak refresh complete independently and each trigger a full bind; the same screen can be reconstructed several times in succession. That repeats inflation, listeners, chart setup, image requests, and font layout work.

Introduce an immutable account snapshot containing stats, normalized history, delta baseline, analytics, import coverage, streak, and non-secret settings. Derive the different chart/range views from that snapshot. Reuse card views keyed by stable card IDs; rebuild structure only for account/layout/edit-mode changes, and coalesce independent background results into a single UI update.

Start with a per-render snapshot, which needs no persistent cache invalidation scheme. If adding longer-lived caches, invalidate on writes, imports, account/source changes, midnight, locale, and time-zone changes. Avoid putting decrypted secrets into a generic long-lived UI snapshot.

**Acceptance:** instrumentation counters show one history parse per account snapshot, stable card identity during data-only updates, and no unnecessary rebind of unrelated cards. Compare cold/warm dashboard startup and refresh frame timings before claiming a speedup. Preserve skeleton reveal, drag/drop, scroll position, accessibility focus, and estimate provenance.

## A07 — Treat images as shared work

**Locations:** [ProfileImageLoader.kt](../app/src/main/java/com/tjg/twidget/ui/ProfileImageLoader.kt), lines 38–186 and 229; [AppExecutors.kt](../app/src/main/java/com/tjg/twidget/core/AppExecutors.kt); widget avatar warming and drawer account icons.

The disk cache is bounded, but there is no decoded-bitmap memory cache or shared URL request deduplication. Disk hits still decode the bitmap every time. Drawer/widget callers also decode cached images synchronously; `warmAvatars` decodes images merely to determine whether they are missing. Avatars are decoded at full size, unlike the sampled media path. Disk keys use a 32-bit URL hash, which can collide.

Each request uses the same executor as sync and other disk/network work. It normally runs two workers and queues 32 jobs before adding workers up to its maximum of four. A burst of avatar work can queue important refreshes. Rejection defaults to an empty callback, so some callers silently retain fallback content. Concurrent downloads of one URL independently delete/rename the same destination file.

Add a byte-bounded decoded bitmap cache keyed by full URL identity and target size, one in-flight request per key, dimension-aware avatar sampling, a cheap disk-presence check, and synchronized atomic cache replacement. Use a dedicated bounded image execution budget or explicit task prioritization. Handle rejection/retry and release stale view requests when detached. Use a stable collision-resistant filename digest.

An established loader is an option, but compare its actual release contribution before adding it. A compact improvement to the existing loader may be sufficient. [Android's bitmap guidance](https://developer.android.com/topic/performance/graphics/load-bitmap) supports decoding near the display size and managing bitmap memory deliberately.

**Acceptance:** repeated bindings share one download/decode; cache memory stays within its byte budget; stale view requests cannot apply the wrong image; image bursts do not starve a manual refresh; widget RemoteViews bitmap budgets remain respected.

## A08 — Fetch a timeline once for multiple consumers

**Locations:** [AnalyticsClient.kt](../app/src/main/java/com/tjg/twidget/analytics/AnalyticsClient.kt), `fetchFxTwitter` at 191; [ActivityClient.kt](../app/src/main/java/com/tjg/twidget/analytics/ActivityClient.kt), `fetchFxTwitterActivity` at 125; dashboard refresh coordination.

Analytics and streak activity independently paginate the same FxTwitter weekly statuses endpoint. Both use a 100-row page size, up to ten pages, and a 200-status cap. A dashboard refresh can start both when stale, duplicating requests and JSON parsing. Their private in-flight sets prevent duplicates inside a controller, not across these consumers or separate screens.

Introduce a provider timeline repository that returns posts plus coverage/completeness metadata. Analytics and streak calculations remain separate pure consumers. Share in-flight requests and source-aware caches keyed by account, provider, window, and relevant configuration. Preserve fallback and opt-in routing; sharing a cache must not silently cause bridge contact or turn incomplete activity into complete activity.

ActivityClient currently records the normal freshness timestamp even after a failed fetch is converted into incomplete activity. Track success, attempt time, and retry/backoff separately so an upstream failure does not necessarily suppress recovery for the full one-hour or six-hour success interval.

**Acceptance:** a stale analytics+streak refresh performs one timeline walk for equivalent requests; provider changes invalidate the correct cache; partial pages, pinned old posts, repeated cursors, replies/reposts, and fetch failures preserve conservative coverage.

## A09 — Apply fonts when content changes, not on every global layout

**Location:** [TwidgetFonts.kt](../app/src/main/java/com/tjg/twidget/ui/TwidgetFonts.kt), lines 152–210; [EdgeToEdgeActivity.kt](../app/src/main/java/com/tjg/twidget/ui/EdgeToEdgeActivity.kt), `onContentChanged`.

Every observed window installs an OnGlobalLayoutListener that walks the full view tree and examines each TextView. The traversal performs resource-name lookup and classification repeatedly and can trigger more layout when it changes typefaces. Cached typefaces prevent repeated construction but not traversal. Mutable typeface maps and base-font fields are also accessed by UI and widget/background paths without a clear synchronization contract.

Apply typography during binding/inflation, on font selection changes, and for newly added dialog/popup content. Use explicit view roles or cached classification where practical. Keep a fallback observer for third-party content only when necessary, and coalesce it with a dirty flag. Make typeface caches bounded where keys vary dynamically and safe for background callers.

**Acceptance:** font application no longer scans an unchanged window after every unrelated layout; dynamically added preferences/dialogs and Samsung headings retain their styling; font changes in the back stack, accessibility sizing, and variable Canvas typography stay correct.

## A10 — Separate follower ranking from search

**Locations:** [TopFollowersBrowseActivity.kt](../app/src/main/java/com/tjg/twidget/followers/TopFollowersBrowseActivity.kt), archive read near line 195, `render` and `updateQuery`; [TopFollowersArchiveStore.kt](../app/src/main/java/com/tjg/twidget/followers/TopFollowersArchiveStore.kt), `readAll` at 99 and browser policy at 189.

Opening the browser reads and parses the entire JSONL archive on the UI thread. Every search change deduplicates, sorts the entire follower list, allocates ranked entries, lowercases names, and filters again. ListAdapter moves diffing off the UI thread, but not this preprocessing. The issue becomes significant for large archives, whose bridge scan cap is far above a small top-five list.

Load and rank on a background path when the archive changes. Cache the ranked snapshot and normalized search fields; filter it with debouncing/generation cancellation. Preserve ranks from the complete ranking rather than renumbering search results. Reuse the existing ListAdapter and avatar-on-attachment policy.

**Acceptance:** search does not sort unchanged input; opening a large archive does not do file I/O on the UI thread; results, tie ordering, rank checkpoints, fast scrolling, and accessibility remain stable. Benchmark large representative archives rather than only a few hundred followers.

## A11 — Allow the shrinker to discard unused components

**Location:** [proguard-rules.pro](../app/proguard-rules.pro), broad manifest-component and Worker rules.

Rules keep every public subclass of Activity, Application, Service, BroadcastReceiver, ContentProvider, and AppWidgetProvider across dependencies, plus every ListenableWorker subclass. These class-level rules do not keep every member, but they still root unnecessary classes/constructors and restrict optimization.

Fresh GitHub R8 seeds include `SystemForegroundService`, `QrScanActivity`, and `SeslEyeDropperActivity`; these are absent from the merged release manifest checked during the audit. SystemForegroundService is explicitly removed by the source manifest. This is evidence of unnecessary retention, though the total byte saving has not been measured.

Inspect the generated manifest keep rules and library consumer rules. Narrow application rules to actual dynamic/reflection entry points and legacy WorkManager classes. Preserve the scoped drawer, toolbar, and fast-scroller reflection rules, the Samsung metadata resource keep, and the SESL incompatibility/checkdiscard workaround until independently validated. [Android's R8 guidance](https://developer.android.com/topic/performance/app-optimization/keep-rules-best-practices) recommends specific keep targets and preservation of reflective members.

Do not remove the full SESL bundle or ML Kit merely because their dependency graphs are large. The SESL substitutions use AndroidX package names and have strict compatibility pins; ML Kit provides existing on-device Brief functionality. First inspect retained code with R8's configuration analyzer and compare fresh APKs after narrowly scoped changes.

**Acceptance:** compare mapping/seeds/usage and DEX size; run minified distribution tests for background workers, upgrade-surviving work, widgets, generated previews, Samsung routines/toolbar/reflection, and local AI. Constructor/name retention for persisted worker classes must remain correct.

## A12 — Reduce responsibility concentration and dependency coupling

Largest concentrations include ScheduleQueueHostActivity (1,785 lines), TwidgetBriefActivity (1,334), TwidgetStore (1,165), MainDashboardBinder (1,164), and bridge server.js (2,050). MainActivity is smaller after previous extraction but now inherits the queue host, so scheduling implementation remains coupled to the main screen. Line count alone is not a defect; these files combine several independently changing responsibilities.

Suggested seams:

| Current concentration | Incremental extraction |
| --- | --- |
| ScheduleQueueHostActivity | Queue state/presenter, list binding, calendar renderer, selection/bulk actions, chrome |
| TwidgetBriefActivity | Brief loading/generation state, card/action binding, entrance animation controller |
| MainDashboardBinder | Card registry/binders, pure insight calculations, dashboard layout/edit integration |
| TwidgetStore | Account/settings repository, profile/history repository, widget preferences, update state, milestone preferences |
| bridge server.js | Application factory, validated configuration, middleware, route groups, provider services, job scheduler, startup/shutdown |

Make screen ownership and lifecycle contracts explicit. Prefer composition for the shared queue, rather than relying on the dashboard inheriting the entire scheduling implementation. Move pure milestone/metric models out of the `main` UI package when they are shared by storage and Brief.

Core HttpTransport imports `schedule.json`; the general JSON AST/parser is defined inside ScheduleJsonCodec and used by unrelated features. Move the existing tested generic JSON layer to a neutral package first. Assess whether keeping two JSON mechanisms is worthwhile, but do not add a serialization framework solely to make imports look tidy or rewrite persisted formats in the same change.

Extract one responsibility per change and keep wire/storage codecs stable. Gradle feature modules can enforce boundaries later; they are not necessary to get the immediate benefits. The July audit's MainActivity/HTTP extractions were useful—continue that approach instead of discarding it.

**Acceptance:** extracted policies are callable without an Activity; existing UI behavior and persistence fixtures remain unchanged; maintainers can identify the owner of account state, background work, and card rendering without following cross-feature imports.

## A13 — Bound network work and give it lifecycle ownership

**Locations:** [HttpTransport.kt](../app/src/main/java/com/tjg/twidget/core/HttpTransport.kt), request at 41–68; [AppExecutors.kt](../app/src/main/java/com/tjg/twidget/core/AppExecutors.kt); provider fallback chains.

JSON responses are read through unbounded `readText()`, and request cleanup is not expressed in a `finally` block. Connect/read timeouts are configured, which is good, but individual timeouts are not a complete operation deadline across fallback chains. Executor submission returns a Boolean and offers no cancellation handle. Lifecycle-generation checks suppress stale UI delivery in some controllers while allowing obsolete network work to continue.

Add endpoint-specific response limits, explicit UTF-8 decoding, and deliberate connection/stream cleanup on success and exceptions. Preserve streaming binary paths and deliberate keep-alive behavior; benchmark any connection-reuse changes. Introduce cancellable task ownership and an overall operation budget for user-facing refreshes, while preserving application-owned work that intentionally survives a screen.

Centralize typed failure outcomes so rate limits, unavailable providers, cancellation, and parse errors do not all become a silent fallback or generic toast. Keep rejection feedback for user actions. Use the current execution framework or targeted lifecycle coroutines; a wholesale coroutine migration is not required.

**Acceptance:** oversized/malformed bodies fail safely, exceptional paths release streams, leaving a screen releases view-owned work, application refreshes retain their intended lifetime, and cancellation does not invalidate correct cached data or bypass fallback/consent rules.

## A14 — Make bridge storage costs proportional to the operation

**Locations:** [infrastructure.js](../bridge/src/infrastructure.js), `persist` at 328, local follower snapshot at 247, PostgreSQL rank index at 410 and snapshot query at 638; PostgreSQL initialization at 349.

The JSON backend serializes the full global store with indentation and writes/renames it synchronously for metadata changes and each follower page. This blocks the event loop and makes a small mutation cost depend on every account and archived follower. Local follower pages also reconstruct the dedupe set, and every snapshot request sorts the full completed follower list. Offset pagination repeats that work for subsequent pages.

Keep JSON viable for small self-hosted installations. After fixing A02, introduce serialized snapshot flushing/coalescing with explicit durability and shutdown behavior, batch pruning, and a cached ordered completed follower snapshot. Do not simply replace sync writes with racing async writes to one `.tmp` path. PostgreSQL remains the stronger route for large shared history, with no forced backend removal.

The PostgreSQL rank index is `(scan_id, followers DESC, follower_username)`, while ordering includes `lower(display_name)`, `lower(follower_username)`, follower ID, and scan index. It cannot fully satisfy that ordering as written. Measure with EXPLAIN on a large scan before adding a matching expression index or persisted rank/keyset paging. Preserve tie ordering and the existing API while comparing query plans.

Schema creation and legacy backfills run during each server startup. Introduce versioned migrations when scaling this backend, separating normal initialization from historical scans and DDL. Inspect the apparently redundant history `(username, date DESC)` index against the composite primary key before changing it; query evidence is needed, not just visual similarity.

**Acceptance:** event-loop delay and storage time are measured at representative scale; acknowledged writes remain durable; completed follower snapshots remain atomic; migration/restart/retention behavior matches both backends; live PostgreSQL tests cover rollback and ordering.

## A15 — Add evidence that guards optimization and refactoring

**Locations:** [.github/workflows/debug.yml](../.github/workflows/debug.yml), line 75; existing `androidTest` suites and distribution tests.

The project has substantial unit/policy and instrumented test source. Debug CI compiles Android device tests but no connected/managed-device test execution was found in the workflows. No app-owned benchmark or baseline-profile module was found. Release outputs contain merged library profile artifacts; that is not an app-specific journey profile.

Execute a small emulator suite for navigation, storage migrations, widgets, scheduling, and each distribution; include minified builds for shrinker-sensitive behavior. Keep real Samsung device coverage for OEM-only routines, lockscreen widgets, palette/toolbar behavior, and local AI capability. Add targeted concurrency/persistence tests from A02–A04, rather than tests that merely repeat implementations.

Add Macrobenchmark journeys for cold/warm dashboard start, account switching, refresh, follower search/scroll, and queue bulk actions. Measure history parsing, image decodes, store commits, request counts, and allocations. Generate app-specific Baseline Profiles once these journeys are stable. [Android's profile guidance](https://developer.android.com/topic/performance/baselineprofiles/overview) describes how journey profiles support runtime optimization; no generic percentage claim should replace measurement here.

Record a size budget for each fresh release APK and representative device-delivered Play APKs. Exclude mapping/debug metadata from installed-size comparisons. A passing unit suite cannot guarantee identical visuals, worker reflection, or device-specific behavior.

## A16 — Source hygiene and reproducible checks

Lint identifies 288 unused resources, including old preview layouts, demo artwork, static fonts, and strings. Verify manifest/OEM/resource-name lookups and keep rules before deleting candidates. R8 already removes many from release output, so fewer source files must not be presented as equivalent APK savings. Remove related translations and documentation references together; keep notices.

`bridge/package.json` hard-codes syntax checks for four of the six production JS files, omitting banger-score and top-followers-provider. Those are currently imported by tests, which catches syntax errors indirectly. Prefer a complete automatically enumerated syntax check for production and migration files. Do not imply the omitted files were untested.

Build logic already uses convention plugins, provider-based configuration, a version catalog, caching, and distribution policies. Preserve these. Investigate Gradle deprecation warnings at their source before Gradle 10, and document the SESL exclusion/pinning contract centrally. Repository content filters and dependency verification/locking are possible later reproducibility improvements, but verify against all required forked artifacts before enabling them.

## Functionality that cleanup must preserve

- Legacy launcher alias and widget provider component names in LegacyWidgetProviders: existing pinned widgets and shortcuts refer to those names.
- Legacy TopFollowersScanWorker and cancellation cleanup: persisted WorkManager entries can outlive an upgrade.
- English, German, and French resources on every Play install: widgets independently select their language, so re-enabling language splits is not a free optimization.
- One UI Sans fallback, Google Sans variable typography, all existing theme/style/layout variants, and accessibility sizing.
- Public/private account behavior, unknown versus observed-zero metrics, honest estimates, trusted/imported precedence, daily delta baseline, and historical anchors.
- Bridge opt-in, provider fallbacks, encrypted credential migration, offline cached behavior, rate limits, retention, and secret boundaries.
- Scheduling durability, local media ownership, trash restoration/retention, reminder restoration, Buffer status confirmation, and once-only notifications.
- On-device AI, cloud AI choices, diagnostic menus available in release, all widget types/previews, distribution-specific updates, and Samsung integration.

Debug menu removal, AI removal, language splitting, font glyph subsetting, and deleting compatibility shims are not part of the proposed functionality-preserving plan.

## Suggested implementation sequence

1. Fix A01 and establish the baseline measurements. Add only the targeted regression tests needed for upcoming correctness work.
2. Fix A02–A04: atomic mutations, stale-response protection, batch scheduling, background persistence. These establish safe foundations for caching and concurrency changes.
3. Implement A05 as a separate resource-focused change with screenshot comparisons and fresh release-size measurements. Expected size benefit is already quantified; integration remains to be validated.
4. Implement dashboard snapshots, image sharing, and timeline sharing (A06–A08), measuring each change independently.
5. Improve typography observation and follower search (A09–A10), then narrow shrinker rules (A11) with minified-device verification.
6. Extract controllers/repositories incrementally (A12), tighten HTTP ownership (A13), and improve bridge scaling (A14) where workload measurements justify it.
7. Keep A15's useful gates and A16's source cleanup alongside those changes. Avoid one giant architectural migration that makes feature regressions difficult to isolate.

The first meaningful milestone is protected storage, a clean lint gate, and a smaller APK with identical resources and behavior. The next milestone is fewer parses, decodes, commits, requests, and view rebuilds under the same user journeys.
