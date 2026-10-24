# Maintaining performance and size

Keep these ownership boundaries when adding features:

- `TwidgetStore` serializes account/profile/history mutations. Refreshes carry a removal generation; old snapshots can add missing history but cannot replace newer observations. `AccountSnapshot` is a render-local, non-secret input; derive chart ranges and deltas from it.
- `ScheduleStore.transaction` makes one durable write per batch. Its edit callback does no network work. Reads have no commits or media cleanup; background `maintain` calls own retention and fallback reconciliation. Release media and deliver terminal notifications after successful persistence.
- `WeeklyTimelineRepository` owns short-lived shared provider walks. Analytics and activity keep separate post qualification policies. Keep paging coverage and fallback consent explicit. Cache identity includes the provider/account and a credential fingerprint for authenticated timelines.
- `ProfileImageLoader` owns disk and decoded caches plus shared image requests. UI uses memory hits or the image executor. Cached bitmaps are shared: callers must not recycle them. Render outputs created for widgets remain caller-owned.
- `AppExecutors` bounds general work and image work independently. `submit` returns a cancellation handle for view-owned work; application cache/worker refreshes may survive screen closure. Lifecycle checks still guard UI publication.
- `core/JsonText.kt` owns the existing generic parser; feature codecs own wire/storage mappings. Moving code does not change stored JSON.
- Bridge `http-policy.js` owns reusable middleware, authentication primitives and upstream capacity. Repositories own storage mutations, metadata patches and durable acknowledgement. Keep normal metadata patches separate from full migration replacements.

## Reproducible assets

The original Google Sans Flex font is in `design-assets/fonts/google_sans_flex.ttf`; its existing notices still apply. `python3 scripts/optimize-variable-font.py` requires FontTools and pins only `GRAD=0` and `opsz=18`. The generator verifies glyph order and all remaining axis ranges. Do not subset languages or glyphs. Android 8/9 extraction uses a new cache filename so an older six-axis resource cannot shadow the replacement.

The ten retained artwork variants use `cwebp -lossless -exact -m 6`. Keep their dimensions, resource names and qualifiers when replacing artwork. Lossless compression changes archive size; sampled profile/media decoding changes bitmap memory. They are separate costs.

## Verification

Run Android unit tests, bridge syntax/tests and lint after changes. Run lint variants separately: reused Kotlin FIR environments crashed in the local tooling during the audit implementation. Fresh serial processes with `LINT_DO_NOT_REUSE_UAST_ENV=true` passed; this [documented lint environment setting](https://googlesamples.github.io/android-custom-lint-rules/user-guide.html) is also set in Android CI jobs and does not disable analysis. The workflows run focused emulator regression checks for both distributions, plus a separately installed, minified `.audit` build. Its platform-only instrumentation runner avoids AndroidX test classes being removed by app/test dependency deduplication and adds no artificial app keep rules. The audit build is signed with the development certificate and never published.

Local examples (set a working JDK first):

```sh
./gradlew testGithubDebugUnitTest lintGithubDebug
(cd bridge && npm run check && npm test)
./gradlew connectedGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.tjg.twidget.data.AccountMutationInstrumentedTest,com.tjg.twidget.schedule.ScheduleTransactionsInstrumentedTest,com.tjg.twidget.main.DashboardSnapshotInstrumentedTest,com.tjg.twidget.ui.ImageCacheInstrumentedTest
./gradlew connectedGithubAuditAndroidTest -PauditTestBuildType=audit
python3 scripts/check-apk-size.py app/build/outputs/apk/github/release/app-github-release.apk app/build/outputs/apk/play/release/app-play-release.apk
```

CI PostgreSQL tests use `TWIDGET_TEST_POSTGRES_URL` and an isolated schema. They never read the production database URL. Without a configured test database the integration test is explicitly skipped. Before adding a ranking index, collect representative read-only plans with `bridge/scripts/history-query-plans.sql` and compare read savings against scan-write cost.

`cd bridge && npm run benchmark:local` characterizes 250 ranked page reads and 50 overlapping metadata writes against a 5,000-follower fixture over three runs. It uses disposable files and checks ranking coverage and acknowledged persistence. To compare a prior standalone `infrastructure.js` module, add `-- --baseline /absolute/path/to/baseline.mjs`; do not point it at server configuration or a live history file. Repeat on an idle host before treating synthetic timings as representative capacity measurements.

`DashboardSnapshotInstrumentedTest` records warm binding durations and checks view identity. Existing widget timing tests characterize render/RemoteViews costs. Compare like devices/builds and multiple runs; correctness checks and APK savings are not evidence of a claimed startup or battery percentage. Next profiling work should use Macrobenchmark journeys (cold/warm dashboard start, account switching, refresh, follower search and queue bulk actions), then generate app-specific Baseline Profiles from stable journeys. OEM routines, local AI and physical Samsung behavior still need device coverage beyond generic emulator smoke checks.

Universal release APKs have a 19,000,000-byte budget with headroom above the optimized build. Do not substitute AAB mapping metadata or universal APK size for Play's device-specific download size.
