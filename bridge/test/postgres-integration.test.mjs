import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import pg from 'pg';
import { PostgresHistoryRepository } from '../src/infrastructure.js';

// Opt-in test database only. Each run owns an isolated schema; never use the production DATABASE_URL.
test('PostgreSQL overlapping samples, metadata patches and delete guards', {
  skip: !process.env.TWIDGET_TEST_POSTGRES_URL,
}, async () => {
  const connectionString = process.env.TWIDGET_TEST_POSTGRES_URL;
  const admin = new pg.Pool({ connectionString });
  const schema = `twidget_test_${crypto.randomBytes(8).toString('hex')}`;
  const pool = new pg.Pool({ connectionString, options: `-c search_path=${schema}` });
  try {
    await admin.query(`CREATE SCHEMA ${schema}`);
    const repository = new PostgresHistoryRepository({ pool, maxHistoryDays: 365 });
    await repository.initialize();
    const day = Date.UTC(2026, 9, 9);
    const sample = (ts, followers) => ({ ts, followers, following: 2, posts: 3, likes: 4 });
    await repository.registerAccount('audit', [sample(day, 100)], 10);
    await Promise.all([
      repository.storeSamples('audit', [sample(day + 86400000, 101)], { requireExisting: true }),
      repository.storeSamples('audit', [sample(day + 2 * 86400000, 102)], { requireExisting: true }),
      repository.patchMeta('audit', { waybackAt: 1 }),
      repository.patchMeta('audit', { banger: { url: 'https://example.com/post' } }),
    ]);
    assert.deepEqual((await repository.getHistory('audit')).map(s => s.followers), [100, 101, 102]);
    assert.equal((await repository.getMeta('audit')).waybackAt, 1);
    assert.equal((await repository.getMeta('audit')).banger.url, 'https://example.com/post');
    await repository.deleteAccount('audit');
    assert.equal(await repository.patchMeta('audit', { waybackAt: 2 }), false);
    assert.deepEqual(await repository.storeSamples('audit', [sample(day, 999)], { requireExisting: true }), []);
    assert.equal(await repository.hasAccount('audit'), false);
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA IF EXISTS ${schema} CASCADE`);
    await admin.end();
  }
});
