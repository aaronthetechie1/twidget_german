import { readdirSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

function files(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? files(path) : entry.isFile() && /\.(?:js|mjs)$/.test(path) ? [path] : [];
  });
}
for (const path of [...files('src'), ...files('scripts')].sort()) {
  const result = spawnSync(process.execPath, ['--check', path], { stdio: 'inherit' });
  if (result.status !== 0 || result.error) process.exit(result.status || 1);
}
