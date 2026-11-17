import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendRoot = fileURLToPath(new URL('../', import.meta.url));
const rootRequire = createRequire(path.join(frontendRoot, 'package.json'));
const lock = JSON.parse(readFileSync(path.join(frontendRoot, 'package-lock.json'), 'utf8'));
const copies = Object.entries(lock.packages).filter(([name]) => name.endsWith('/picomatch'));
const expectedDirectories = ['vendor/picomatch-v2', 'vendor/picomatch-v4'];

assert.deepEqual([...new Set(copies.map(([, record]) => record.resolved))].sort(), expectedDirectories);
for (const [name, record] of copies) {
  assert.deepEqual(Object.keys(record).sort(), ['link', 'resolved'], name);
  assert.equal(record.link, true, name);
}

const consumers = [
  { chain: ['tailwindcss', 'chokidar', 'anymatch'], major: 2 },
  { chain: ['tailwindcss', 'chokidar', 'readdirp'], major: 2 },
  { chain: ['tailwindcss', 'fast-glob', 'micromatch'], major: 2 },
  { chain: ['vite'], major: 4 },
  { chain: ['vite', 'tinyglobby'], major: 4 },
  { chain: ['vite', 'tinyglobby', 'fdir'], major: 4 },
  { chain: ['vitest'], major: 4 },
  { chain: ['vitest', 'tinyglobby'], major: 4 },
  { chain: ['vitest', 'tinyglobby', 'fdir'], major: 4 },
];

for (const { chain, major } of consumers) {
  const consumerRequire = chain.reduce(
    (parentRequire, name) => createRequire(parentRequire.resolve(name)),
    rootRequire,
  );
  const entry = consumerRequire.resolve('picomatch');
  const installedDirectory = path.dirname(entry);
  const sourceDirectory = path.join(frontendRoot, 'vendor', `picomatch-v${major}`);
  for (const file of ['package.json', 'index.js', 'lib/parse.js']) {
    assert.deepEqual(
      readFileSync(path.join(installedDirectory, file)),
      readFileSync(path.join(sourceDirectory, file)),
      `${chain.join(' → ')} must use the reviewed Picomatch ${major} ${file}`,
    );
  }

  const match = consumerRequire(entry);
  for (const pattern of ['+(*)', '+(*a)', '*(*)', '**/dir/+(*a).js', '@(x|*(*a))']) {
    for (const compile of [match, match.makeRe, match.parse]) {
      assert.throws(
        () => compile(pattern),
        { name: 'SyntaxError', message: 'Repeated extglobs are disabled in the ERP web build' },
      );
    }
  }
  assert.equal(match.isMatch('src/orders.ts', '**/*.{ts,tsx}'), true);
  assert.equal(match.isMatch('src/orders.d.ts', '**/!(*.d).ts'), false);
  assert.equal(match.isMatch('src/orders.ts', '**/!(*.d).ts'), true);
}

console.log('Guarded Picomatch versions verified in the web build and test tools.');
