import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  LocalFixedWindowLimiter,
  LocalHistoryRepository,
  PostgresHistoryRepository,
  RedisFixedWindowLimiter,
  SharedJsonCache,
  mergeSamples,
  normalizeHistorySample,
} from "../src/infrastructure.js";

const DAY = 24 * 60 * 60 * 1000;

test("local history persists UTC samples and applies configured retention", async (t) => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-history-test-"));
  const filePath = path.join(temp, "history.json");
  t.after(() => rm(temp, { recursive: true, force: true }));
  let now = Date.UTC(2026, 3, 1);
  const repository = new LocalHistoryRepository({
    filePath,
    maxHistoryDays: 400,
    sampleRetentionDays: 30,
    inactiveAccountDays: 60,
    now: () => now,
  });
  await repository.initialize();
  assert.equal(await repository.registerAccount("old", [sample(now, 5)], 2), true);
  now = Date.UTC(2026, 6, 10);
  assert.equal(await repository.registerAccount("active", [
    sample(now - 45 * DAY, 8),
    sample(now - DAY, 10),
  ], 2), true);
  assert.equal(await repository.registerAccount("full", [sample(now, 1)], 2), false);
  assert.deepEqual((await repository.getHistory("active")).map((item) => item.followers), [10]);

  const result = await repository.prune();
  assert.deepEqual(result, { deletedAccounts: 1, deletedSamples: 1 });
  assert.deepEqual((await repository.getHistory("active")).map((item) => item.followers), [10]);
  assert.equal(await repository.hasAccount("old"), false);

  const stored = JSON.parse(await readFile(filePath, "utf8"));
  assert.equal(stored.version, 4);
  assert.equal(stored.accounts.active[0].ts, now - DAY);
});

test("local Top Followers scans publish atomically, paginate by rank, and expire", async (t) => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-top-followers-test-"));
  const filePath = path.join(temp, "history.json");
  t.after(() => rm(temp, { recursive: true, force: true }));
  let now = Date.UTC(2026, 7, 1);
  const repository = new LocalHistoryRepository({ filePath, maxHistoryDays: 400, now: () => now });
  await repository.initialize();
  await repository.registerAccount("example", [sample(now, 10)], 2);
  const started = await repository.startTopFollowersScan("example", "scan-1");
  assert.equal(started.status, "running");
  await repository.appendTopFollowersPage("example", "scan-1", [
    { id: "1", username: "one", name: "One", followers: 10, verified: false, avatar: "", mutual: null },
    { id: "2", username: "two", name: "Two", followers: 20, verified: true, avatar: "", mutual: true },
  ], "next");
  assert.equal(await repository.getTopFollowersSnapshot("example"), null);
  await repository.completeTopFollowersScan("example", "scan-1");
  const snapshot = await repository.getTopFollowersSnapshot("example", { offset: 0, limit: 1 });
  assert.equal(snapshot.total, 2);
  assert.equal(snapshot.followers[0].username, "two");
  await repository.startTopFollowersScan("example", "scan-2");
  assert.equal((await repository.getTopFollowersSnapshot("example")).followers[0].username, "two");
  await repository.failTopFollowersScan("example", "scan-2", "provider_unavailable");
  assert.equal((await repository.getTopFollowersSnapshot("example")).followers[0].username, "two");
  now += 31 * DAY;
  assert.equal(await repository.pruneTopFollowers(30), 1);
  assert.equal(await repository.getTopFollowersSnapshot("example"), null);
});

test("local metadata writes cannot erase or roll back Top Followers activity", async (t) => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-meta-test-"));
  t.after(() => rm(temp, { recursive: true, force: true }));
  const repository = new LocalHistoryRepository({ filePath: path.join(temp, "history.json"), maxHistoryDays: 400 });
  await repository.initialize();
  await repository.setMeta("example", { topFollowersLastSeenAt: 2000 });
  await repository.setMeta("example", { banger: { score: 1 } });
  assert.deepEqual(await repository.getMeta("example"), { topFollowersLastSeenAt: 2000, banger: { score: 1 } });
  await repository.setMeta("example", { topFollowersLastSeenAt: 1000 });
  assert.equal((await repository.getMeta("example")).topFollowersLastSeenAt, 2000);
  await repository.setMeta("example", { topFollowersLastSeenAt: 3000 });
  assert.equal((await repository.getMeta("example")).topFollowersLastSeenAt, 3000);
});

test("history merge never lets an estimate replace a real daily sample", () => {
  const now = Date.UTC(2026, 6, 10);
  const merged = mergeSamples(
    [{ ...sample(now, 100), est: false }],
    [{ ...sample(now, 999), est: true }],
    { preferExisting: false, maxHistoryDays: 400, sampleRetentionDays: 0, now },
  );
  assert.equal(merged.length, 1);
  assert.equal(merged[0].followers, 100);
  assert.equal(merged[0].est, undefined);
});

test("legacy Wayback zero fields migrate to unknown while observed client zero remains known", async (t) => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-history-known-test-"));
  const filePath = path.join(temp, "history.json");
  t.after(() => rm(temp, { recursive: true, force: true }));
  const ts = Date.UTC(2026, 6, 10);
  await writeFile(filePath, JSON.stringify({
    version: 2,
    accounts: {
      example: [
        { ...sample(ts - DAY, 10), following: 0, posts: 0, likes: 0, src: "wayback" },
        { ...sample(ts, 11), following: 0, posts: 0, likes: 0, src: "client" },
      ],
    },
    meta: {},
  }));
  const repository = new LocalHistoryRepository({ filePath, maxHistoryDays: 400, now: () => ts });
  await repository.initialize();
  const [wayback, client] = await repository.getHistory("example");
  assert.deepEqual(
    [wayback.followersKnown, wayback.followingKnown, wayback.postsKnown, wayback.likesKnown],
    [true, false, false, false],
  );
  assert.deepEqual(
    [client.followersKnown, client.followingKnown, client.postsKnown, client.likesKnown],
    [true, true, true, true],
  );
  assert.equal(JSON.parse(await readFile(filePath, "utf8")).version, 3);
});

test("same-day merge preserves a previously known metric when a later source omits it", () => {
  const ts = Date.UTC(2026, 6, 10);
  const merged = mergeSamples(
    [{ ...sample(ts, 100), following: 7, followingKnown: true }],
    [{ ...sample(ts, 101), following: 0, followingKnown: false, src: "wayback" }],
    { preferExisting: false, maxHistoryDays: 400, sampleRetentionDays: 0, now: ts },
  );
  assert.equal(merged[0].followers, 101);
  assert.equal(merged[0].following, 7);
  assert.equal(merged[0].followingKnown, true);
  assert.equal(normalizeHistorySample({ ...sample(ts, 0), followers: 0, src: "client" }).followersKnown, true);
  assert.equal(normalizeHistorySample({
    ...sample(ts, 0),
    following: 0,
    followingKnown: true,
    src: "wayback",
  }).followingKnown, true);
});

test("bounded local limiter rejects new keys at capacity without weakening a bucket", async () => {
  const limiter = new LocalFixedWindowLimiter({ windowMs: 60_000, maxKeys: 1 });
  assert.equal((await limiter.take("first")).count, 1);
  assert.equal((await limiter.take("first")).count, 2);
  assert.equal((await limiter.take("second")).capacityReached, true);
});

test("Redis limiter and JSON cache use shared atomic/TTL commands", async () => {
  const calls = [];
  const client = {
    async eval(_script, options) {
      calls.push(["eval", options]);
      return [3, 2500];
    },
    async get(key) {
      calls.push(["get", key]);
      return JSON.stringify({ fetchedAt: 1, data: { followers: 7 } });
    },
    async set(key, value, options) {
      calls.push(["set", key, JSON.parse(value), options]);
      return "OK";
    },
  };
  const limiter = new RedisFixedWindowLimiter({ client, prefix: "test:rate", windowMs: 60_000 });
  assert.deepEqual(await limiter.take("127.0.0.1"), { count: 3, retryMs: 2500 });

  const cache = new SharedJsonCache({ client, prefix: "test:cache", maxEntries: 2 });
  assert.equal((await cache.get("user")).data.followers, 7);
  await cache.set("user", { fetchedAt: 2, data: { followers: 8 } }, 5000);
  assert.deepEqual(calls.at(-1).at(-1), { PX: 5000 });
});

test("PostgreSQL repository creates cascading schema and stores dates in UTC", async () => {
  const calls = [];
  const client = {
    async query(sql, params) {
      calls.push({ sql, params });
      return { rows: [], rowCount: 0 };
    },
    release() { calls.push({ sql: "RELEASE" }); },
  };
  const pool = {
    async query(sql, params) {
      calls.push({ sql, params });
      return { rows: [], rowCount: 0 };
    },
    async connect() { return client; },
    async end() {},
  };
  const repository = new PostgresHistoryRepository({ pool, maxHistoryDays: 400 });
  await repository.initialize();
  const schema = calls.find((call) => /CREATE TABLE IF NOT EXISTS twidget_history_accounts/.test(call.sql));
  assert.ok(calls.some((call) => /twidget_history_schema/.test(call.sql)));
  assert.match(schema.sql, /ON DELETE CASCADE/);
  assert.match(schema.sql, /last_accessed_at/);
  assert.match(schema.sql, /ADD COLUMN IF NOT EXISTS following_known/);
  assert.match(schema.sql, /CREATE TABLE IF NOT EXISTS twidget_top_follower_scans/);
  assert.match(schema.sql, /CREATE TABLE IF NOT EXISTS twidget_top_followers/);
  assert.match(schema.sql, /NOT estimated AND \(source IS DISTINCT FROM 'wayback' OR following > 0\)/);
  assert.equal(await repository.healthCheck(), true);
  assert.ok(calls.some((call) => call.sql === "SELECT 1"));

  const ts = Date.UTC(2026, 6, 10);
  await repository.storeSamples("example", [sample(ts, 10)], { touchAccess: false });
  const sampleInsert = calls.find((call) => /INSERT INTO twidget_history_samples/.test(call.sql));
  assert.match(sampleInsert.sql, /AT TIME ZONE 'UTC'/);
  assert.match(sampleInsert.sql, /CASE WHEN EXCLUDED.following_known/);
  assert.deepEqual(sampleInsert.params.slice(2), [10, true, 1, true, 2, true, 3, true, "live", false]);
  assert.ok(calls.some((call) => call.sql === "COMMIT"));
  assert.ok(calls.some((call) => call.sql === "RELEASE"));

  await repository.setMeta("example", { topFollowersLastSeenAt: ts });
  const metaUpdate = calls.find((call) => /DO UPDATE SET metadata = CASE/.test(call.sql));
  assert.match(metaUpdate.sql, /twidget_history_accounts.metadata->>'topFollowersLastSeenAt'/);
  assert.match(metaUpdate.sql, /COALESCE\(\(EXCLUDED.metadata->>'topFollowersLastSeenAt'\)::bigint, 0\)/);
  assert.match(metaUpdate.sql, /THEN EXCLUDED.metadata \|\| jsonb_build_object/);
  assert.deepEqual(metaUpdate.params, ["example", JSON.stringify({ topFollowersLastSeenAt: ts })]);
});

function sample(ts, followers) {
  return {
    dayLabel: new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", timeZone: "UTC" }).format(new Date(ts)),
    followers,
    following: 1,
    posts: 2,
    likes: 3,
    ts,
  };
}


test("overlapping local writes preserve samples and independent metadata patches durably", async (t) => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-overlap-test-"));
  t.after(() => rm(temp, { recursive: true, force: true }));
  const filePath = path.join(temp, "history.json");
  const now = Date.UTC(2026, 9, 11);
  const repository = new LocalHistoryRepository({ filePath, maxHistoryDays: 400, now: () => now });
  await repository.initialize();
  await repository.registerAccount("example", [sample(now - 2 * DAY, 100)], 2);
  await Promise.all([
    repository.storeSamples("example", [sample(now - DAY, 101)]),
    repository.storeSamples("example", [sample(now, 102)]),
    repository.patchMeta("example", { waybackAt: 123 }),
    repository.patchMeta("example", { banger: { score: 456 } }),
  ]);
  const stored = JSON.parse(await readFile(filePath, "utf8"));
  assert.deepEqual(stored.accounts.example.map(value => value.followers), [100, 101, 102]);
  assert.deepEqual(stored.meta.example, { waybackAt: 123, banger: { score: 456 } });
  await repository.deleteAccount("example");
  assert.equal(await repository.patchMeta("example", { waybackAt: 999 }), false);
  assert.deepEqual(await repository.storeSamples("example", [sample(now, 999)], { requireExisting: true }), []);
  assert.equal(await repository.hasAccount("example"), false);
});

test("PostgreSQL metadata patches update existing JSONB without enrolling deleted accounts", async () => {
  const calls = [];
  const repository = new PostgresHistoryRepository({
    pool: { async query(sql, params) { calls.push({ sql, params }); return { rowCount: 0 }; } },
    maxHistoryDays: 400,
  });
  assert.equal(await repository.patchMeta("removed", { banger: { score: 1 } }), false);
  assert.match(calls[0].sql, /metadata = metadata \|\| \$2::jsonb/);
  assert.doesNotMatch(calls[0].sql, /INSERT/);
});

const follower = (id, followers = Number(id)) => ({
  id: String(id), username: `fan${id}`, name: `Fan ${id}`, followers,
  verified: false, avatar: "", mutual: null,
});

test("JSON head scans merge new followers atomically and full scans remove unfollows", async (t) => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-head-test-"));
  t.after(() => rm(temp, { recursive: true, force: true }));
  const filePath = path.join(temp, "history.json");
  const repository = new LocalHistoryRepository({ filePath, maxHistoryDays: 400 });
  await repository.startTopFollowersScan("example", "full", 1000);
  await repository.appendTopFollowersPage("example", "full", [follower(1, 100), follower(2, 200)], "", 2000);
  await repository.completeTopFollowersScan("example", "full", 3000);
  await repository.startTopFollowersScan("example", "failed-head", 4000, "head");
  await repository.appendTopFollowersPage("example", "failed-head", [follower(3, 300)], "", 5000);
  assert.equal((await repository.getTopFollowersSnapshot("example")).total, 2);
  await repository.failTopFollowersScan("example", "failed-head", "provider_failure", 6000);
  assert.equal((await repository.getTopFollowersSnapshot("example")).total, 2);
  await repository.startTopFollowersScan("example", "head", 7000, "head");
  await repository.appendTopFollowersPage("example", "head", [follower(2, 999), follower(3, 300)], "", 8000);
  await repository.close();
  const resumed = new LocalHistoryRepository({ filePath, maxHistoryDays: 400 });
  await resumed.initialize();
  assert.equal((await resumed.getTopFollowersScan("example")).kind, "head");
  await resumed.completeTopFollowersScan("example", "head", 9000);
  const snapshot = await resumed.getTopFollowersSnapshot("example");
  assert.equal(snapshot.kind, "head");
  assert.equal(snapshot.fullCompletedAt, 3000);
  assert.equal(snapshot.completedAt, 9000);
  assert.equal(snapshot.total, 3);
  assert.deepEqual(snapshot.followers.map((value) => value.followers), [300, 200, 100]);
  assert.deepEqual(snapshot.followers.map((value) => value.scanIndex), [2, 1, 0]);
  await resumed.startTopFollowersScan("example", "next-full", 10000);
  await resumed.appendTopFollowersPage("example", "next-full", [follower(3, 400)], "", 11000);
  await resumed.completeTopFollowersScan("example", "next-full", 12000);
  assert.equal((await resumed.getTopFollowersSnapshot("example")).total, 1);
  assert.equal((await resumed.getTopFollowersSnapshot("example")).fullCompletedAt, 12000);
});

test("Postgres page counters use actual inserted rows, allocate beyond gaps, and roll back failed pages", async () => {
  let state = { scanned: 2, pages: 1, cursor: "old" };
  let rows = [0, 2]; // A skipped duplicate left a hole at index 1.
  let before;
  let failUpdate = false;
  const calls = [];
  const client = {
    async query(sql, params = []) {
      calls.push({ sql, params });
      if (sql === "BEGIN") before = { state: { ...state }, rows: [...rows] };
      if (sql === "ROLLBACK") ({ state, rows } = before);
      if (/FOR UPDATE/.test(sql)) {
        assert.doesNotMatch(sql, /count\(\*\)/i);
        return { rows: [{ scanned: state.scanned }] };
      }
      if (/SELECT COALESCE\(max\(scan_index\)/.test(sql)) {
        assert.match(calls.at(-2).sql, /FOR UPDATE/, "allocate only after locking the scan");
        return { rows: [{ next_index: Math.max(...rows) + 1 }] };
      }
      if (/INSERT INTO twidget_top_followers/.test(sql)) {
        const payload = JSON.parse(params[1]);
        assert.equal(payload[0].scan_index, 3);
        rows.push(payload.at(-1).scan_index);
        return { rowCount: 1 }; // Two payload rows but only one new follower.
      }
      if (/UPDATE twidget_top_follower_scans SET/.test(sql)) {
        assert.match(sql, /scanned = scanned \+ \$4::int/);
        assert.doesNotMatch(sql, /count\(\*\)/i);
        if (failUpdate) throw new Error("write failed");
        state = { scanned: state.scanned + params[3], pages: state.pages + 1, cursor: params[2] };
        return { rows: [{ scan_id: "scan", status: "running", ...state }] };
      }
      return { rows: [], rowCount: 0 };
    },
    release() {},
  };
  const repository = new PostgresHistoryRepository({ pool: { async connect() { return client; } }, maxHistoryDays: 400 });
  const updated = await repository.appendTopFollowersPage("example", "scan", [follower(1), follower(3)], "next");
  assert.equal(updated.scanned, 3);
  assert.equal(updated.pages, 2);
  assert.equal(updated.cursor, "next");
  assert.equal(calls.at(-1).sql, "COMMIT");
  await repository.appendTopFollowersPage("example", "scan", [], "empty");
  assert.equal(state.scanned, 3, "empty pages add zero rows");
  assert.equal(state.pages, 3);
  assert.equal(calls.filter(({ sql }) => /INSERT INTO twidget_top_followers/.test(sql)).length, 1);
  // Start again from the same gap fixture to exercise rollback after INSERT.
  state = { scanned: 2, pages: 1, cursor: "old" };
  rows = [0, 2];
  failUpdate = true;
  await assert.rejects(repository.appendTopFollowersPage("example", "scan", [follower(4)], "failed"), /write failed/);
  assert.deepEqual(state, { scanned: 2, pages: 1, cursor: "old" });
  assert.deepEqual(rows, [0, 2]);
  assert.equal(calls.at(-1).sql, "ROLLBACK");
});

test("Postgres head scans stage the previous archive and preserve full-scan freshness on publish", async () => {
  const calls = [];
  let row;
  const fullCompletedAt = Date.UTC(2026, 7, 1);
  const client = {
    async query(sql, params) {
      calls.push({ sql, params });
      if (/SELECT scan_id, scanned, COALESCE/.test(sql)) {
        return { rows: [{ scan_id: "old", scanned: 2, full_completed_at: new Date(fullCompletedAt) }] };
      }
      if (/INSERT INTO twidget_top_follower_scans/.test(sql)) {
        assert.equal(params[2], "head");
        assert.equal(params[3], 2);
        assert.equal(params[4].getTime(), fullCompletedAt);
        row = { scan_id: "head", status: "running", kind: params[2], scanned: params[3], full_completed_at_ms: fullCompletedAt };
        return { rows: [row] };
      }
      if (/INSERT INTO twidget_top_followers/.test(sql)) {
        assert.match(sql, /SELECT \$1::uuid, scan_index, follower_id/);
        assert.deepEqual(params, ["head", "old"]);
      }
      if (/UPDATE twidget_top_follower_scans SET status = 'complete'/.test(sql)) {
        assert.match(sql, /CASE WHEN kind = 'head' THEN full_completed_at ELSE now\(\) END/);
        return { rows: [{ ...row, status: "complete", completed_at_ms: fullCompletedAt + DAY }] };
      }
      return { rows: [], rowCount: 0 };
    },
    release() {},
  };
  const repository = new PostgresHistoryRepository({ pool: { async connect() { return client; } }, maxHistoryDays: 400 });
  const started = await repository.startTopFollowersScan("example", "head", fullCompletedAt + DAY, "head");
  assert.equal(started.kind, "head");
  assert.equal(started.scanned, 2);
  assert.equal(started.fullCompletedAt, fullCompletedAt);
  assert.ok(calls.findIndex(({ sql }) => /INSERT INTO twidget_top_followers/.test(sql)) < calls.findIndex(({ sql }) => sql === "COMMIT"));
  assert.equal(calls.some(({ sql }) => /DELETE FROM twidget_top_follower_scans/.test(sql)), false, "old snapshot survives staging");
  const completed = await repository.completeTopFollowersScan("example", "head");
  assert.equal(completed.fullCompletedAt, fullCompletedAt);
  assert.equal(completed.completedAt, fullCompletedAt + DAY);
  assert.ok(calls.some(({ sql }) => /DELETE FROM twidget_top_follower_scans/.test(sql)));
  assert.equal(calls.at(-1).sql, "COMMIT");
});
