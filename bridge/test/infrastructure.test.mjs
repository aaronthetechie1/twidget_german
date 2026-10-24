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
  await repository.startTopFollowersScan("example", "head", (7000 + DAY), "head");
  await repository.appendTopFollowersPage("example", "head", [follower(2, 999), follower(3, 300)], "", (8000 + DAY));
  await repository.close();
  const resumed = new LocalHistoryRepository({ filePath, maxHistoryDays: 400 });
  await resumed.initialize();
  assert.equal((await resumed.getTopFollowersScan("example")).kind, "head");
  await resumed.completeTopFollowersScan("example", "head", (9000 + DAY));
  const snapshot = await resumed.getTopFollowersSnapshot("example");
  assert.equal(snapshot.kind, "head");
  assert.equal(snapshot.fullCompletedAt, 3000);
  assert.equal(snapshot.lastFullCompletedAt, 3000);
  assert.equal(snapshot.completedAt, (9000 + DAY));
  assert.equal(snapshot.total, 3);
  assert.deepEqual(snapshot.followers.map((value) => value.followers), [300, 200, 100]);
  assert.deepEqual(snapshot.followers.map((value) => value.scanIndex), [2, 1, 0]);
  await resumed.startTopFollowersScan("example", "next-full", (10000 + DAY));
  await resumed.appendTopFollowersPage("example", "next-full", [follower(3, 400)], "", (11000 + DAY));
  await resumed.completeTopFollowersScan("example", "next-full", (12000 + DAY));
  assert.equal((await resumed.getTopFollowersSnapshot("example")).total, 1);
  assert.equal((await resumed.getTopFollowersSnapshot("example")).fullCompletedAt, (12000 + DAY));
});

test("handle moves require matching identities, preserve all shared state and are idempotent", async t => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-handle-test-"));
  t.after(() => rm(temp, { recursive: true, force: true }));
  const repository = new LocalHistoryRepository({ filePath: path.join(temp, "history.json"), maxHistoryDays: 400 });
  await repository.initialize();
  const day = Date.now() - DAY;
  await repository.registerAccount("before", [sample(day, 10)], 10);
  await repository.setMeta("before", { userId: "123", banger: { url: "https://x.com/a/status/1" }, waybackAt: 77 });
  repository.store.topFollowers.before = { status: "complete", scanId: "scan", followers: [] };
  await assert.rejects(repository.renameAccount("before", "after", "999"), /source_identity_unverified/);
  await repository.registerAccount("other", [sample(day, 999)], 10);
  await repository.setMeta("other", { userId: "999" });
  await assert.rejects(repository.renameAccount("before", "other", "123"), /destination_identity_conflict/);
  assert.equal((await repository.getHistory("before"))[0].followers, 10);
  assert.equal((await repository.getHistory("other"))[0].followers, 999);
  assert.deepEqual(await repository.renameAccount("before", "after", "123"), { moved: true });
  assert.equal(await repository.hasAccount("before"), false);
  assert.equal((await repository.getHistory("after"))[0].followers, 10);
  assert.equal((await repository.getMeta("after")).waybackAt, 77);
  assert.equal(repository.store.topFollowers.after.scanId, "scan");
  assert.deepEqual(await repository.renameAccount("before", "after", "123"), { moved: false });
  const reloaded = new LocalHistoryRepository({ filePath: repository.filePath, maxHistoryDays: 400 });
  await reloaded.initialize();
  assert.equal((await reloaded.getMeta("after")).userId, "123");
  assert.equal(await reloaded.hasAccount("before"), false);
});

test("legacy shared history cannot be moved by a public claim", async t => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-legacy-handle-test-"));
  t.after(() => rm(temp, { recursive: true, force: true }));
  const repository = new LocalHistoryRepository({ filePath: path.join(temp, "history.json"), maxHistoryDays: 400 });
  await repository.initialize();
  await repository.registerAccount("legacy", [sample(Date.now(), 10)], 10);
  await assert.rejects(repository.renameAccount("legacy", "after", "123"), /source_identity_unverified/);
  assert.equal(await repository.hasAccount("legacy"), true);
  await repository.renameAccount("legacy", "after", "123", { allowLegacy: true });
  assert.equal((await repository.getMeta("after")).userId, "123");
});

test("a failed JSON publication is retryable without claiming completion or losing history", async t => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-handle-failure-test-"));
  t.after(() => rm(temp, { recursive: true, force: true }));
  const repository = new LocalHistoryRepository({ filePath: path.join(temp, "history.json"), maxHistoryDays: 400 });
  await repository.initialize();
  await repository.registerAccount("before", [sample(Date.now(), 10)], 10, "123");
  const persist = repository.persist.bind(repository);
  repository.persist = async () => { throw new Error("simulated_disk_failure"); };
  await assert.rejects(repository.renameAccount("before", "after", "123"), /simulated_disk_failure/);
  const disk = JSON.parse(await readFile(repository.filePath, "utf8"));
  assert.equal(disk.accounts.before.length, 1);
  repository.persist = persist;
  await repository.renameAccount("before", "after", "123");
  const recovered = JSON.parse(await readFile(repository.filePath, "utf8"));
  assert.equal(recovered.accounts.after.length, 1);
  assert.equal(recovered.accounts.before, undefined);
});

test("registration cannot assign legacy or recycled history to a different live ID", async t => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-identity-registration-test-"));
  t.after(() => rm(temp, { recursive: true, force: true }));
  const repository = new LocalHistoryRepository({ filePath: path.join(temp, "history.json"), maxHistoryDays: 400 });
  await repository.initialize();
  await repository.registerAccount("legacy", [sample(Date.now(), 10)], 10);
  assert.equal(await repository.registerAccount("legacy", [sample(Date.now(), 999)], 10, "123"), true);
  assert.equal((await repository.getMeta("legacy")).userId, undefined);
  await repository.registerAccount("known", [sample(Date.now(), 10)], 10, "123");
  assert.equal(await repository.registerAccount("known", [sample(Date.now(), 999)], 10, "999"), false);
  assert.equal((await repository.getHistory("known"))[0].followers, 10);
  assert.equal((await repository.getMeta("known")).userId, "123");
});

test("a handle move preserves stored archive observations without applying retention", async t => {
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-handle-archive-test-"));
  t.after(() => rm(temp, { recursive: true, force: true }));
  const repository = new LocalHistoryRepository({ filePath: path.join(temp, "history.json"), maxHistoryDays: 400, sampleRetentionDays: 100 });
  await repository.initialize();
  repository.store.accounts.before = [sample(Date.UTC(2020, 0, 1), 10), sample(Date.UTC(2020, 0, 2), 11)];
  repository.store.meta.before = { userId: "123" };
  await repository.persist();
  await repository.renameAccount("before", "after", "123");
  const disk = JSON.parse(await readFile(repository.filePath, "utf8"));
  assert.deepEqual(disk.accounts.after.map(row => row.followers), [10, 11]);
});
