# Twidget Bridge

The bridge is an optional Node service for public X/Twitter profile lookups,
weekly post analytics, and opt-in pooled daily history. FxTwitter is the primary
upstream; Rettiwt guest lookup is the profile fallback.

Twidget's FxTwitter mode does not require this service. Both profile data and
weekly analytics can be fetched directly by the Android app.

## Local development

```bash
npm ci
npm run dev
```

```bash
curl http://localhost:8787/health
curl http://localhost:8787/user/thatjoshguy
npm test
```

## Routes

- `GET /health` — small unauthenticated readiness response; returns `503` if the configured history backend is unavailable. Includes `publicMode: true` when `BRIDGE_API_TOKEN` is unset.
- `GET /user/:username` — normalized public profile data.
- `GET /analytics/:username` — recent original-post analytics, cached for 45 minutes.
- `GET /banger/:username` — opt-in, resumable Hall of Fame scan for the account's best balanced post.
- `GET /history/:username` — opt-in pooled daily history registration/read.
- `GET /history/:username/top-followers` — reads a completed shared ranking.
- `GET /history/:username/top-followers/all` — paginates the latest completed server-owned follower list.
- `GET /history/:username/top-followers/scan` — reads durable server-scan progress.
- `POST /history/:username/top-followers/scan` — starts or resumes a bounded server-owned scan for an already opted-in account, or reuses a fresh snapshot. Current clients use this only for the first user-requested ranking; subsequent refreshes are bridge-scheduled.
- `POST /history/:username/top-followers` — legacy trusted-publisher compatibility route; requires `TOP_FOLLOWERS_PUBLISH_TOKEN` on a public bridge or `BRIDGE_API_TOKEN` on a private bridge.
- `POST /history/:username/analytics-import` — reconstructs X Analytics movements and admits gap days only when live and stored follower anchors match within a tight margin.
- `DELETE /admin/history/:username` — operator-only permanent deletion; hidden unless `HISTORY_ADMIN_TOKEN` is configured.
- `GET /official/user/:username` — disabled publicly by default, even with an X bearer configured.
- `/oauth/x/*` — disabled by default.

## Production security model

The service validates X usernames, rate-limits before body parsing, applies a
smaller budget to expensive routes, caps tracked IPs and history accounts,
coalesces duplicate upstream requests, limits concurrent upstream work, and
sets short HTTP header/request/keep-alive limits. Ordinary profile lookups no
longer create persistent history records. Private accounts are rejected from
the pool and removed if a previously public pooled account becomes private.

Wayback archive fetching and client history uploads are off by default. Both
can amplify work or weaken data trust and should stay off on a public instance
unless there is a concrete operational reason to enable them.

`BRIDGE_API_TOKEN` protects all data routes for private/self-hosted instances.
Self-hosted deployments should always set this token. The maintainer-operated
shared bridge intentionally remains token-free; check `/health` for
`publicMode: true` when evaluating exposure.
Android sends the configured bridge token as both a Bearer token and the legacy
`X-Rettiwt-Api-Key` header. Do not ship a private token inside a publicly
distributed APK.

Top Followers provider credentials remain server-side. When a shared-history
user first asks to find their top follower, that request enrolls the account in
server-owned full scans once per `TOP_FOLLOWERS_FULL_SCAN_DAYS` (default 7,
allowed 1–90 days), including while phones are offline, as long as a client has used Top
Followers within `TOP_FOLLOWERS_ACTIVE_DAYS` (default 7, allowed 1–365 days).
Preview, archive, scan-status, and scan-start requests record activity, with
metadata writes throttled to once per hour per account in each bridge process.
Dormant accounts keep their stale ranking but stop starting paid scans; scans
already running still resume. A returning preview reader receives that stale
ranking immediately and triggers a refresh when due through the same capacity, global
daily, and per-IP daily limits as an explicit scan request. Legacy accounts use
their enrollment time as activity; those without it receive a persisted grace
period starting on the first scheduler run after deployment.
Accounts that only participate in pooled metric history are not scanned until
that explicit first request.

The default between full scans is to serve the existing snapshot, with its
original completion time. Preview reads and explicit scan requests also obey
the full-scan interval, so polling cannot bypass the weekly cadence.
`TOP_FOLLOWERS_FRESH_HOURS` (default 24) remains the immediate request-reuse
window; older snapshots are still reused until the configured scan is due.
`TOP_FOLLOWERS_REFRESH_HOURS` (default 24, range 1–168) now controls the cadence
of optional head checks and the cooldown before retrying a failed attempt,
rather than scheduling daily full scans. Failed full scans can retry after this
cooldown; successful full scans reset the full-scan clock.

**Provider ordering is unverified:** neither the `followers_v2` adapter nor the
repository's docs/tests establish newest-first follower ordering. Head checks
are therefore off by default. Only set `TOP_FOLLOWERS_HEAD_CHECK=1` after
confirming newest-first ordering for the configured provider endpoint.
When enabled, a due head check fetches up to `TOP_FOLLOWERS_HEAD_PAGES` (default
1, range 1–5) from the beginning and merges new followers into the existing
ranked archive. A head check preserves existing follower profiles and does not
detect unfollows or refresh their follower counts; those changes are detected
on full scans. New followers beyond the configured head-page window can also
wait until the next full scan. Partial merges never reset the full-scan clock.

Endpoints and existing response fields remain compatible. `completedAt` and
`cachedAt` identify the latest published snapshot, including a head merge;
additive `fullCompletedAt` and `scanKind` (`full` or `head`) fields distinguish
full-scan freshness from partial updates. Old snapshots use their completion
time as the last full scan. `/health` exposes `history.topFollowers.fullScanDays`,
`headCheck`, `headPages`, and `providerOrderingVerified` (currently false),
alongside the existing settings.

Postgres page writes increment the scan count by actual inserted rows, in the
same transaction as the cursor. The single-instance JSON backend keeps its existing coalesced atomic writes. Expired provider cursors
can fail the attempt, which will restart after the retry cooldown. Head scans
stage a copy of the archive and publish atomically, so they still incur local
archive-copy/storage costs even though provider calls are bounded.

Ordinary app installs cannot submit follower rows. Client scan starts have
global and per-IP daily-start limits; scheduled work remains bounded by the
enrolled-account cap, activity window, refresh cadence, upstream concurrency, page cost, and
retention. The legacy ranking write remains fail-closed behind
`TOP_FOLLOWERS_PUBLISH_TOKEN` for compatibility and must never be exposed to an
app install.

Application middleware cannot absorb a volumetric or distributed DDoS attack.
Put a public production domain behind an edge WAF/DDoS service and monitor
Railway HTTP metrics. Railway's emergency Under Attack Mode can also be used,
but it may block native API clients and should not be the normal operating mode.

The default JSON history store is intentionally single-instance. PostgreSQL and
Redis adapters are included for multi-replica deployments; do not increase the
replica count until both are configured. PostgreSQL is the history authority,
while Redis shares fixed-window request budgets, history-registration limits,
profile/analytics caches, and scheduled-job locks across replicas.

### Multi-replica storage

Set these variables before adding replicas:

```bash
HISTORY_BACKEND=postgres
HISTORY_DATABASE_URL=postgresql://user:password@host/database?sslmode=require
REDIS_URL=redis://user:password@host:6379
```

The bridge creates its `twidget_history_accounts`, `twidget_history_samples`,
`twidget_top_follower_scans`, and `twidget_top_followers` tables and indexes at startup. An explicitly selected
PostgreSQL backend fails startup instead of falling back to a local JSON file;
this prevents a database outage from silently creating divergent history.
Likewise, a configured but unavailable Redis instance fails startup, and live
rate-limit failures return `503` rather than multiplying the budget per replica.

Every history sample carries `followersKnown`, `followingKnown`, `postsKnown`,
and `likesKnown`. Consumers must check the relevant flag before treating a
numeric value as observed. This matters for older Wayback captures, where an
absent field was historically serialized as zero. Startup schema migration
marks legacy Wayback zero fields unknown, keeps positive archive values known,
and preserves genuine observed zero values from live/client samples. Sample
`src` records `live`, `client`, or `wayback` provenance where available.

For participating clients, the bridge owns the provider scan and stages each
validated page in durable storage. Readers only see the latest scan after an
atomic completion transition; an interrupted scan can resume from its stored
cursor without replacing the previous completed snapshot. The original
top-five response remains available for older clients, while current clients
page through the full ranked list. These routes do not create history-pool
accounts on their own.

For the first deployment onto an existing PostgreSQL store, keep the bridge at
one replica and take a database backup. Startup takes a PostgreSQL advisory
transaction lock, adds the four nullable columns, backfills them, and only then
makes them `NOT NULL`; subsequent startups are no-ops. After readiness returns
200, verify there are no null flags before adding replicas:

```sql
SELECT count(*) AS incomplete
FROM twidget_history_samples
WHERE followers_known IS NULL OR following_known IS NULL
   OR posts_known IS NULL OR likes_known IS NULL;
```

To migrate an existing JSON store, back it up and run the idempotent importer
once before switching the backend:

```bash
HISTORY_DATABASE_URL='postgresql://…' npm run history:migrate -- /data/twidget-history.json
```

The importer never deletes or rewrites the source JSON file. Existing database
samples win on conflicts, except that a real sample can replace an estimate.

Retention is opt-in to avoid surprising deletion of existing history:

- `HISTORY_SAMPLE_RETENTION_DAYS` permanently deletes older samples.
- `HISTORY_INACTIVE_ACCOUNT_DAYS` deletes an account and its samples after no
  client history access or registration for that period. Scheduled collection
  does not keep an otherwise inactive account alive.
- `HISTORY_PRUNE_HOURS` controls cleanup frequency; cleanup also runs at boot.

Private-account detection still deletes pooled history immediately regardless
of the retention settings. PostgreSQL uses cascading deletion so samples and
metadata are removed together. Operators can also permanently delete an account
with `DELETE /admin/history/:username` and the separate `HISTORY_ADMIN_TOKEN`;
this credential must never be included in an app build.

## Environment

The safe reference values are in [`.env.example`](.env.example). Important
controls include:

- `BRIDGE_API_TOKEN` — optional token for private/self-hosted data routes.
- `TOP_FOLLOWERS_PUBLISH_TOKEN` — trusted shared-ranking writer credential for an otherwise public bridge.
- `TWITTERAPIS_API_KEY` — server-only key used for opted-in bridge-owned follower scans.
- `TOP_FOLLOWERS_FRESH_HOURS`, `TOP_FOLLOWERS_REFRESH_HOURS`, `TOP_FOLLOWERS_RETENTION_DAYS` — immediate request reuse, head-check/retry cadence, and permanent deletion windows (see cadence details above).
- `TOP_FOLLOWERS_FULL_SCAN_DAYS` — full-scan interval (default `7`, range `1–90`).
- `TOP_FOLLOWERS_HEAD_CHECK`, `TOP_FOLLOWERS_HEAD_PAGES` — opt-in partial checks (`0` by default) and their page cap (default `1`, range `1–5`); require confirmed newest-first provider ordering.
- `TOP_FOLLOWERS_ACTIVE_DAYS` — client activity window for scheduled scans (default `7`, range `1–365`); exposed as `history.topFollowers.activeDays` in `/health`.
- `TOP_FOLLOWERS_DAILY_SCAN_LIMIT`, `TOP_FOLLOWERS_DAILY_SCAN_LIMIT_PER_IP` — explicit and returning-preview client scan-start budgets.
- `TOP_FOLLOWERS_MAX_CONCURRENT`, `TOP_FOLLOWERS_MAX_PAGES` — scheduled and requested provider-work bounds.
- `TRUST_PROXY_HOPS` — exact reverse-proxy hop count; Railway uses `1`.
- `RATE_LIMIT_*`, `EXPENSIVE_RATE_LIMIT_MAX` — request budgets; shared when Redis is configured.
- `REDIS_URL` — shared rate limits, response caches, and scheduled-job locks.
- `MAX_CACHE_ENTRIES`, `OAUTH_MAX_PENDING` — hard memory bounds for public-input maps.
- `MAX_CONCURRENT_UPSTREAM` — process-wide upstream concurrency ceiling.
- `HISTORY_MAX_ACCOUNTS`, `HISTORY_REGISTRATIONS_PER_HOUR` — persistent-work caps.
- `BANGER_PAGES_PER_REQUEST`, `BANGER_MAX_POSTS` — bound each resumable historical scan and its total scope.
- `HISTORY_STORE_PATH` — defaults to `/data/twidget-history.json` when `/data` exists.
- `HISTORY_BACKEND=postgres` and `HISTORY_DATABASE_URL` — shared history for multiple replicas.
- `HISTORY_SAMPLE_RETENTION_DAYS`, `HISTORY_INACTIVE_ACCOUNT_DAYS` — optional permanent deletion policies.
- `HISTORY_ADMIN_TOKEN` — separate operator credential for explicit history deletion.
- `WAYBACK_BACKFILL=1` — explicitly enables archive fetching.
- `X_BEARER_TOKEN` and `PUBLIC_OFFICIAL_API=1` — official endpoint credentials and public exposure opt-in.
- `X_OAUTH_ENABLED=1`, `X_CLIENT_ID`, and `X_CALLBACK_URL` — OAuth opt-in and fixed callback.
- `CORS_ALLOW_ORIGIN` — exact browser origin; absent means no CORS header.
