// Synthetic local-backend characterization. Uses disposable files, never server configuration.
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { performance } from 'node:perf_hooks';
import * as current from '../src/infrastructure.js';

const baselineIndex = process.argv.indexOf('--baseline');
const modules = baselineIndex < 0 ? [] : [['baseline', await import(pathToFileURL(path.resolve(process.argv[baselineIndex + 1])).href)]];
modules.push(['current', current]);
const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'twidget-local-benchmark-'));
const results = [];
try {
  for (const [name, module] of modules) {
    for (let run = 1; run <= 3; run++) {
      const filePath = path.join(temp, `${name}-${run}.json`);
      const repository = new module.LocalHistoryRepository({ filePath, maxHistoryDays: 365 });
      await repository.initialize();
      await repository.registerAccount('audit', [{ ts: Date.now(), followers: 5000, following: 2, posts: 3, likes: 4 }], 10);
      await repository.startTopFollowersScan('audit', 'scan');
      const followers = Array.from({ length: 5000 }, (_, index) => ({
        id: String(index), username: `user${index}`, name: `User ${index}`,
        followers: (index * 7919) % 10000, avatar: '', verified: false,
      }));
      await repository.appendTopFollowersPage('audit', 'scan', followers, '');
      await repository.completeTopFollowersScan('audit', 'scan');
      const start = performance.now();
      for (let page = 0; page < 250; page++) {
        const snapshot = await repository.getTopFollowersSnapshot('audit', { offset: (page % 50) * 100, limit: 100 });
        if (snapshot.total !== 5000 || snapshot.followers.length !== 100) throw new Error('Unexpected ranking coverage');
      }
      const pageMs = performance.now() - start;
      const writeStart = performance.now();
      const timer = new Promise(resolve => setTimeout(() => resolve(performance.now() - writeStart), 0));
      await Promise.all(Array.from({ length: 50 }, (_, sample) => repository.setMeta('audit', { sample })));
      const writesMs = performance.now() - writeStart;
      const timerDelayMs = await timer;
      const stored = JSON.parse(await fs.readFile(filePath, 'utf8'));
      if (stored.meta.audit.sample !== 49) throw new Error('Acknowledged metadata was not persisted');
      results.push({ name, run, followers: 5000, pages: 250, concurrentMetaWrites: 50,
        pageMs, writesMs, timerDelayMs, fileBytes: (await fs.stat(filePath)).size });
      await repository.close();
    }
  }
  console.log(JSON.stringify(results, null, 2));
} finally {
  await fs.rm(temp, { recursive: true, force: true });
}
