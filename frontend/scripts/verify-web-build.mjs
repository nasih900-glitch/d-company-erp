import { access, readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const distRoot = path.resolve('dist');
const indexPath = path.join(distRoot, 'index.html');
const html = await readFile(indexPath, 'utf8');
const assetReferences = [
  ...html.matchAll(/(?:src|href)="([^"]*assets\/[^"]+)"/g),
].map((match) => match[1]);

if (assetReferences.length === 0) {
  throw new Error('Built index.html does not reference any compiled assets.');
}

for (const reference of assetReferences) {
  if (!reference.startsWith('/assets/')) {
    throw new Error(
      `Web asset path ${JSON.stringify(reference)} is not root-relative; ` +
        'nested routes such as /public/menu would render a blank screen.',
    );
  }

  const assetPath = path.join(distRoot, reference.slice(1));
  await access(assetPath);
}

process.stdout.write(
  `Verified ${assetReferences.length} root-relative web asset references.\n`,
);
