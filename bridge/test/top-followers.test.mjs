import assert from "node:assert/strict";
import test from "node:test";
import { createTopFollowersActivityRecorder, prepareTopFollowersCache, shouldRefreshTopFollowers } from "../src/top-followers.js";

test("Top Followers activity writes are hourly, per account, and preserve metadata", async () => {
  const hour = 60 * 60 * 1000;
  let now = 10 * hour;
  const metadata = new Map([["example", { topFollowersEnrolledAt: 1, banger: { score: 42 } }], ["other", {}]]);
  const writes = [];
  const recordActivity = createTopFollowersActivityRecorder({
    async hasAccount(key) { return metadata.has(key); },
    async getMeta(key) { return metadata.get(key); },
    async setMeta(key, meta) {
      writes.push(key);
      metadata.set(key, meta);
    },
  }, () => now);

  await Promise.all([recordActivity("example"), recordActivity("example")]);
  assert.deepEqual(writes, ["example"]);
  assert.deepEqual(metadata.get("example"), {
    topFollowersEnrolledAt: 1, banger: { score: 42 }, topFollowersLastSeenAt: now,
  });
  now += hour / 4;
  await recordActivity("example");
  await recordActivity("other");
  await recordActivity("missing");
  assert.deepEqual(writes, ["example", "other"]);
  now += hour * 3 / 4;
  await recordActivity("example");
  assert.deepEqual(writes, ["example", "other", "example"]);
  assert.equal(metadata.get("example").topFollowersLastSeenAt, now);
});

test("failed activity writes can be retried", async () => {
  let attempts = 0;
  const recordActivity = createTopFollowersActivityRecorder({
    async hasAccount() { return true; },
    async getMeta() { return {}; },
    async setMeta() { if (++attempts === 1) throw new Error("storage unavailable"); },
  });
  await assert.rejects(recordActivity("example"), /storage unavailable/);
  await recordActivity("example");
  assert.equal(attempts, 2);
});

test("normalizes a bounded completed Top Followers result", () => {
  const cached = prepareTopFollowersCache({
    scanned: 250,
    pages: 5,
    top: [{
      id: "42",
      username: "Example",
      name: "Example User",
      followers: 1234,
      verified: true,
      avatar: "https://example.com/avatar.jpg",
    }],
  }, 123456);

  assert.deepEqual(cached, {
    version: 1,
    cachedAt: 123456,
    scanned: 250,
    pages: 5,
    top: [{
      id: "42",
      username: "Example",
      name: "Example User",
      followers: 1234,
      verified: true,
      avatar: "https://example.com/avatar.jpg",
    }],
  });
});

test("rejects malformed, duplicate, or unbounded shared rankings", () => {
  const valid = { id: "1", username: "Example", name: "Example", followers: 1, avatar: "" };
  assert.equal(prepareTopFollowersCache({ scanned: 1, pages: 1, top: [] }), null);
  assert.equal(prepareTopFollowersCache({ scanned: 1, pages: 1, top: [{ ...valid, username: "not valid" }] }), null);
  assert.equal(prepareTopFollowersCache({ scanned: 1, pages: 1, top: [{ ...valid, avatar: "http://example.com/a" }] }), null);
  assert.equal(prepareTopFollowersCache({ scanned: 2, pages: 1, top: [valid, valid] }), null);
});

test("daily refreshes run once per cadence and do not duplicate running scans", () => {
  const day = 24 * 60 * 60 * 1000;
  const now = 10 * day;
  assert.equal(shouldRefreshTopFollowers({ snapshot: null, scan: null, now, refreshMs: day }), true);
  assert.equal(shouldRefreshTopFollowers({
    snapshot: { completedAt: now - day + 1 }, scan: null, now, refreshMs: day,
  }), false);
  assert.equal(shouldRefreshTopFollowers({
    snapshot: { completedAt: now - day }, scan: null, now, refreshMs: day,
  }), true);
  assert.equal(shouldRefreshTopFollowers({
    snapshot: null, scan: { status: "failed", startedAt: now - 1000 }, now, refreshMs: day,
  }), false);
  assert.equal(shouldRefreshTopFollowers({
    snapshot: null, scan: { status: "running", startedAt: now - day * 2 }, now, refreshMs: day,
  }), false);
});
