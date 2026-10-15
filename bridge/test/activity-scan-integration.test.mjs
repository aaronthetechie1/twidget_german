import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import pg from 'pg';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { LocalHistoryRepository, PostgresHistoryRepository } from '../src/infrastructure.js';
import { createTopFollowersActivityRecorder } from '../src/top-followers.js';

async function repositoryFor(t, backend) {
  if (backend === 'json') {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'twidget-activity-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    const repository = new LocalHistoryRepository({ filePath: path.join(directory, 'history.json'), maxHistoryDays: 365 });
    await repository.initialize();
    return repository;
  }
  const admin = new pg.Pool({ connectionString: process.env.TWIDGET_TEST_POSTGRES_URL });
  const schema = 'twidget_activity_' + crypto.randomBytes(8).toString('hex');
  await admin.query(`CREATE SCHEMA ${schema}`);
  const pool = new pg.Pool({ connectionString: process.env.TWIDGET_TEST_POSTGRES_URL, options: `-c search_path=${schema}` });
  t.after(async () => { await pool.end(); await admin.query(`DROP SCHEMA ${schema} CASCADE`); await admin.end(); });
  const repository = new PostgresHistoryRepository({ pool, maxHistoryDays: 365 });
  await repository.initialize();
  return repository;
}

for (const backend of ['json', 'postgres']) {
  const options = { skip: backend === 'postgres' && !process.env.TWIDGET_TEST_POSTGRES_URL };
  test(`${backend}: activity preserves concurrent metadata and permanent identity`, options, async t => {
    const repository = await repositoryFor(t, backend);
    await repository.registerAccount('example', [], 10, '123456');
    const patch = { userId: '123456', banger: { score: 42 }, topFollowers: { cachedAt: 99 } };
    // Interleave another writer immediately before whichever write the recorder uses.
    const proxy = {
      hasAccount: repository.hasAccount.bind(repository),
      getMeta: repository.getMeta.bind(repository),
      async setMeta(key, meta) { await repository.patchMeta(key, patch); return repository.setMeta(key, meta); },
      async recordTopFollowersActivity(key, timestamp) { await repository.patchMeta(key, patch); return repository.recordTopFollowersActivity(key, timestamp); },
    };
    await createTopFollowersActivityRecorder(proxy, () => 2000)('example');
    assert.deepEqual(await repository.getMeta('example'), { ...patch, topFollowersLastSeenAt: 2000 });
    await repository.recordTopFollowersActivity('example', 1000);
    assert.equal((await repository.getMeta('example')).topFollowersLastSeenAt, 2000);
  });
  test(`${backend}: activity cannot resurrect a deleted or renamed account`, options, async t => {
    const repository = await repositoryFor(t, backend);
    for (const mutation of ['delete', 'rename']) {
      await repository.registerAccount('example', [], 10, '123456');
      const remove = () => mutation === 'delete' ? repository.deleteAccount('example') : repository.renameAccount('example', 'renamed', '123456');
      const proxy = {
        hasAccount: repository.hasAccount.bind(repository), getMeta: repository.getMeta.bind(repository),
        async setMeta(key, meta) { await remove(); return repository.setMeta(key, meta); },
        async recordTopFollowersActivity(key, timestamp) { await remove(); return repository.recordTopFollowersActivity(key, timestamp); },
      };
      await createTopFollowersActivityRecorder(proxy)('example');
      assert.equal(await repository.hasAccount('example'), false);
      assert.deepEqual(await repository.getMeta('example'), {});
      if (mutation === 'rename') assert.equal((await repository.getMeta('renamed')).userId, '123456');
    }
  });
}

const follower = id => ({ id, username: 'fan' + id, name: 'Fan', followers: Number(id), verified: false, avatar: '', mutual: null });
test('PostgreSQL: exact page counts, atomic head publication, weekly clock and rollback', { skip: !process.env.TWIDGET_TEST_POSTGRES_URL }, async t => {
  const r = await repositoryFor(t, 'postgres');
  await r.registerAccount('example', [], 10, '123456');
  const full = crypto.randomUUID();
  await r.startTopFollowersScan('example', full);
  await r.appendTopFollowersPage('example', full, [follower('1'), follower('2')], 'next');
  const overlap = await r.appendTopFollowersPage('example', full, [follower('2'), follower('3')], '');
  assert.equal(overlap.scanned, 3, 'an update must not increment the row count');
  const renamed = await r.appendTopFollowersPage('example', full, [{ ...follower('3'), username: 'changedfan' }], '');
  assert.equal(renamed.scanned, 3, 'a renamed follower replaces one row');
  const empty = await r.appendTopFollowersPage('example', full, [], '');
  assert.equal(empty.scanned, 3, 'empty pages leave the count intact');
  const published = await r.completeTopFollowersScan('example', full);
  const head = crypto.randomUUID();
  const started = await r.startTopFollowersScan('example', head, Date.now(), 'head');
  assert.equal(started.scanned, 3);
  assert.equal(started.fullCompletedAt, published.fullCompletedAt);
  await r.appendTopFollowersPage('example', head, [follower('3'), follower('4')], 'next');
  assert.equal((await r.getTopFollowersSnapshot('example')).total, 3);
  await r.pool.query(`CREATE FUNCTION fail_scan_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'review write failure'; END $$`);
  await r.pool.query(`CREATE TRIGGER fail_scan BEFORE UPDATE ON twidget_top_follower_scans FOR EACH ROW EXECUTE FUNCTION fail_scan_update()`);
  await assert.rejects(r.appendTopFollowersPage('example', head, [follower('5')], 'failed'), /review write failure/);
  await r.pool.query('DROP TRIGGER fail_scan ON twidget_top_follower_scans');
  assert.equal((await r.getTopFollowersScan('example')).scanned, 4);
  assert.equal(Number((await r.pool.query('SELECT count(*) AS total FROM twidget_top_followers WHERE scan_id = $1', [head])).rows[0].total), 4);
  const completed = await r.completeTopFollowersScan('example', head);
  assert.equal(completed.fullCompletedAt, published.fullCompletedAt);
  assert.equal(completed.lastFullCompletedAt, published.lastFullCompletedAt);
  assert.equal((await r.getTopFollowersSnapshot('example')).total, 4);
  const reconciled = crypto.randomUUID();
  await r.startTopFollowersScan('example', reconciled);
  await r.appendTopFollowersPage('example', reconciled, [follower('4')], '');
  await r.completeTopFollowersScan('example', reconciled);
  assert.equal((await r.getTopFollowersSnapshot('example')).total, 1);
});
