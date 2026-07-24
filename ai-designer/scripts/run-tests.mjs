import { spawnSync } from 'node:child_process';
import { readdirSync } from 'node:fs';
import { resolve } from 'node:path';

const roots = process.argv.slice(2);
if (roots.length === 0) {
  console.error('Usage: node scripts/run-tests.mjs <directory> [...]');
  process.exit(2);
}

function collectTests(directory) {
  const entries = readdirSync(directory, { withFileTypes: true })
    .sort((left, right) => left.name.localeCompare(right.name));
  const files = [];
  for (const entry of entries) {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...collectTests(path));
    } else if (entry.isFile() && entry.name.endsWith('.test.ts')) {
      files.push(path);
    }
  }
  return files;
}

const files = roots.flatMap((root) => collectTests(resolve(root)));
if (files.length === 0) {
  console.error(`No test files found under: ${roots.join(', ')}`);
  process.exit(1);
}

// Running the TypeScript loader through Node avoids shell glob differences on Linux
// and avoids spawning the Windows-only tsx.cmd shim directly.
const result = spawnSync(process.execPath, ['--import', 'tsx', '--test', ...files], {
  cwd: process.cwd(),
  env: process.env,
  stdio: 'inherit',
  windowsHide: true,
});

if (result.error) {
  console.error(result.error);
  process.exit(1);
}
process.exit(result.status ?? 1);
