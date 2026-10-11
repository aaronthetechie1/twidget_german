# Audit implementation — 11 October 2026

Implementation of [the audit](CODEBASE_AUDIT_2026-10-11.md). Existing uncommitted feature work and concurrent dashboard changes are preserved. The implementation and separate debug packaging are published to staging; unrelated animation work remains separate.

| Finding | Status | Implementation / evidence |
| --- | --- | --- |
| A01 lint | Implemented | Named framework styleable indexes; explicit API 31 hardware-blur contracts; versioned API 29 recoil with older-Android ripple fallback; standalone debug lint passed |
| A02 bridge mutations | Implemented | Atomic local merges; PostgreSQL JSONB patches; existing-account guards; durable serialized/coalesced async snapshots; overlapping local mutation regressions pass |
| A03 Android mutations | Implemented | Synchronized mutations; case-normalized removal generations; stale profile guard with history enrichment; account/removal device regression passes |
| A04 scheduling | Implemented | One commit per batch, pure reads, cached decode, explicit background maintenance and queue UI mutations; failed persistence retains media (device regression passes) |
| A05 size | Implemented | Ten lossless WebP variants; pin two constant font axes; original source + generator retained; glyphs/cmap/four active axis ranges verified unchanged |
| A06 dashboard | Implemented | One account history snapshot per bind; coalesced background completion binds; stable wrappers and reused metric/Brief/streak content; identity device check passes |
| A07 images | Implemented | Shared URL requests, 12 MiB decoded cache, 400px avatar/1600px media sampling, separate bounded executor, SHA-256 disk keys, atomic replaces; collision/cache/sampling device check passes |
| A08 timeline | Implemented | Shared FxTwitter/TwitterAPIs pagination, credential fingerprint identity, coverage metadata, separate activity/analytics qualification, short failed-attempt retry backoff |
| A09 fonts | Implemented incrementally | Synchronized typeface caches; one traversal per frame with unchanged TextView fast path; dynamic hierarchy discovery remains for compatibility |
| A10 followers | Implemented | Background streaming archive read and canonical rank once; background debounced filter with generation guards and owned cancellable tasks |
| A11 shrinker | Implemented and smoke-tested in both distributions | Generated manifest rules and consumer rules; preserve app worker constructors/names and OEM reflection, remove global component/worker rules |
| A12 maintainability | Implemented incrementally | Neutral core JSON parser; account snapshots; shared timeline repository/request cache; extracted bridge HTTP policy; ownership guide. Larger queue/Brief decomposition remains an incremental follow-up |
| A13 HTTP | Implemented incrementally | Bounded UTF-8 bodies, stream cleanup, preserve successful keep-alive pooling, deadline/interruption checks, cancellable executor submission and view-owned follower tasks; cancellation skips optional fallbacks. Refresh-wide budgets and unified failure presentation remain follow-ups |
| A14 bridge scaling | Implemented where measured | Coalesced asynchronous snapshots and batched retention; cached rank and dedup sets; disposable local benchmark; isolated-schema PostgreSQL regression wired to CI; read-only query-plan script. No speculative production index/migration |
| A15 verification | Implemented gates; profiling follow-up | Focused debug and minified emulator CI for both distributions; PostgreSQL CI service; size budget; regression + warm-bind characterization tests. Macrobenchmark/Baseline Profiles still require stable measured journeys |
| A16 hygiene | Implemented | Enumerated all production/migration JS syntax checks, removed obsolete static fonts, reproducible asset tooling and maintenance documentation |

## Verification record

- Android unit suite: 358 passing in the shared implementation checkout; 355 passing in the isolated staging checkout after excluding unrelated animation tests, with zero failures/errors/skips.
- Bridge: 35 passing; one PostgreSQL integration test explicitly skipped locally (no local PostgreSQL server); CI is configured to run it against its test service.
- Final `lintGithubDebug`, debug APK and instrumented-test compilation: passed with zero lint errors. Both release APK assemblies passed with shrinking and vital lint enabled. Local Kotlin FIR environment reuse caused internal analysis crashes; fresh serial processes with `LINT_DO_NOT_REUSE_UAST_ENV=true` completed successfully.
- Device regressions passed on a read-only API 37 ARM64 emulator: account mutation/removal, schedule commits/failure/media retention, dashboard card identity, image sampling/cache/collision, light/dark card rendering and widget styles.
- Minified GitHub and Play `.audit` builds: manifest activities/aliases, receivers, services and providers resolve; all six persisted worker names/constructors resolve; launcher resumes dashboard/onboarding. Platform-only Java runner passed in both distributions without additional app keep rules.
- Existing follower archive/fast-scroll/font tests passed. The keyboard/filter/recreation/voice-search regression also passed in both themes on a fresh emulator; an unresponsive launcher had intercepted focus in the earlier run. Search sizing reads the delivered insets during dispatch.
- Final GitHub release: 17,577,002 bytes versus 24,080,243 bytes (27.01% smaller). Play release: 17,554,373 versus 24,058,242 bytes (27.03% smaller). Both pass the 19,000,000-byte budget. These are universal APK sizes, not Play device-specific download sizes.
- Google Sans Flex: 921,828 bytes versus 4,607,592; all 616 glyphs, cmap and four active axis ranges preserved. Android outline/metric equivalence test passed for 50 styles across English, German, French and arbitrary account text.
- Local bridge synthetic benchmark, median of three runs against baseline `3ec75d7e`: 250 ranked page reads across 5,000 followers took 556.9 ms before / 1.8 ms after; 50 overlapping metadata writes took 211.8 / 3.9 ms; a zero-delay timer during those writes was delayed 212.1 / 0.5 ms. Compact snapshots were 585,496 versus 1,095,787 bytes. These local fixture measurements demonstrate removed repeated sorting and blocking writes; they are not production latency or Android performance claims. The repeatable tool is `bridge/scripts/benchmark-local-history.mjs`.

See [maintenance and verification guidance](MAINTAINING_PERFORMANCE.md). No startup, frame-time or battery percentage improvement is claimed from these checks. Physical Samsung/local-AI coverage, live representative PostgreSQL plans, and app-specific journey profiles remain follow-up measurements. Broader controller decomposition, versioned PostgreSQL migrations, refresh-wide budgets and unified failure presentation are explicit remaining implementation work.
