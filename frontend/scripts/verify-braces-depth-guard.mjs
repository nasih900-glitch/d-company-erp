import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourceRoot = path.join(frontendRoot, 'vendor', 'dcompany-braces-depth-guard');
const provenance = JSON.parse(readFileSync(path.join(sourceRoot, 'PROVENANCE.json'), 'utf8'));
const installedRoot = path.dirname(require.resolve('@dcompany/braces-depth-guard/package.json'));
const installedProvenance = JSON.parse(
  readFileSync(path.join(installedRoot, 'PROVENANCE.json'), 'utf8'),
);
const guard = require('@dcompany/braces-depth-guard');
const compatibilityAdapter = require('braces');

const sha256 = (filePath) => createHash('sha256').update(readFileSync(filePath)).digest('hex');
const nested = (depth) => `${'{'.repeat(depth)}a${'}'.repeat(depth)}`;

assert.equal(provenance.schema, 'dcompany.local-derivative-provenance.v1');
assert.equal(provenance.package.name, '@dcompany/braces-depth-guard');
assert.equal(provenance.package.version, '3.0.3-dcompany.1');
assert.equal(provenance.derived_from.name, '@dieub/braces-depth-guard');
assert.equal(provenance.derived_from.version, '3.0.3-pn.3');
assert.equal(
  provenance.derived_from.tarball_sha256,
  'bfddf5527888add524fb533818cd5c56ed66e9d0454705e1a4ef002de9905227',
);
assert.equal(compatibilityAdapter, guard, 'the braces adapter must preserve module identity');
assert.deepEqual(installedProvenance, provenance, 'installed provenance must match the source package');

for (const entry of provenance.runtime_files) {
  assert.equal(sha256(path.join(sourceRoot, entry.path)), entry.result_sha256, entry.path);
  assert.equal(
    sha256(path.join(installedRoot, entry.path)),
    entry.result_sha256,
    `installed ${entry.path}`,
  );
}
assert.equal(
  sha256(path.join(sourceRoot, provenance.patch.file)),
  provenance.patch.sha256,
  'the reviewed local patch must not drift',
);
assert.equal(
  sha256(path.join(installedRoot, provenance.patch.file)),
  provenance.patch.sha256,
  'the installed package must include the reviewed local patch',
);

const tailwindDirectory = path.dirname(require.resolve('tailwindcss/package.json'));
for (const consumer of ['micromatch', 'chokidar']) {
  const consumerDirectory = path.dirname(require.resolve(
    `${consumer}/package.json`,
    { paths: [tailwindDirectory] },
  ));
  const resolvedAdapter = require(require.resolve('braces', { paths: [consumerDirectory] }));
  assert.equal(resolvedAdapter, guard, `${consumer} must resolve the guarded implementation`);
}

for (const method of ['parse', 'compile', 'expand', 'stringify']) {
  assert.doesNotThrow(() => guard[method](nested(100)), `${method} must accept depth 100`);
  assert.throws(
    () => guard[method](nested(101)),
    /depth/i,
    `${method} must reject depth 101`,
  );
  if (method !== 'parse') {
    assert.throws(
      () => guard[method](nested(5), { maxDepth: 4 }),
      /depth/i,
      `${method} must honor a lower maxDepth`,
    );
  }
}

const parentChain = (depth) => {
  const root = { type: 'root', queue: [] };
  let current = root;
  for (let index = 0; index < depth; index += 1) {
    current = { type: 'paren', parent: current, nodes: [] };
  }
  return current;
};

assert.doesNotThrow(() => guard.expand(parentChain(4), { maxDepth: 4 }));
assert.throws(() => guard.expand(parentChain(5), { maxDepth: 4 }), RangeError);
assert.doesNotThrow(() => guard.expand(parentChain(100)));
assert.throws(() => guard.expand(parentChain(101)), RangeError);
assert.throws(() => guard.expand(parentChain(10_000), { maxDepth: 4 }), RangeError);

const parentCycle = { type: 'paren', nodes: [] };
parentCycle.parent = parentCycle;
assert.throws(() => guard.expand(parentCycle), RangeError);

for (const method of ['compile', 'expand', 'stringify']) {
  const childCycle = { type: 'root', nodes: [] };
  childCycle.nodes.push(childCycle);
  assert.throws(() => guard[method](childCycle), RangeError, `${method} must reject child cycles`);
}

for (const maxLength of [Number.NaN, -1]) {
  assert.throws(() => guard.parse('a'.repeat(10_001), { maxLength }), /length/i);
}
assert.throws(
  () => guard.parse('a'.repeat(10_001), { maxLength: Number.POSITIVE_INFINITY }),
  /length/i,
);

const ordinaryCases = new Map([
  ['a/{b,c}/d', ['a/b/d', 'a/c/d']],
  ['file-{01..05}', ['file-01', 'file-02', 'file-03', 'file-04', 'file-05']],
  ['x{a,{b,c}}y', ['xay', 'xby', 'xcy']],
  ['{a,b}{c,d}', ['ac', 'ad', 'bc', 'bd']],
  ['foo\\{a,b\\}', ['foo{a,b}']],
]);
for (const [pattern, expected] of ordinaryCases) {
  assert.deepEqual(guard.expand(pattern), expected, pattern);
}

let getterReads = 0;
assert.doesNotThrow(() => guard.compile(
  { type: 'root', nodes: [] },
  { get maxDepth() { getterReads += 1; return getterReads === 1 ? 1 : Number.NaN; } },
));
assert.equal(getterReads, 1, 'maxDepth must be normalized once per direct AST traversal');

console.log('Verified D Company braces depth guard, provenance, adapter identity, and consumers.');
