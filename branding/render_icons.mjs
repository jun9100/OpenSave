// Renders the PNG icons from savevault-icon.svg.
//
//   npm install --no-save @resvg/resvg-js@2.6.2
//   node branding/render_icons.mjs
//
// Then build the Windows .ico from the PNGs it leaves in branding/out
// (see README.md).
import { Resvg } from '@resvg/resvg-js';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const svg = readFileSync(join(here, 'savevault-icon.svg'));

function render(size) {
  return new Resvg(svg, { fitTo: { mode: 'width', value: size } }).render().asPng();
}

const targets = {
  'cmd/opensave-app/build/appicon.png': 1024,
  'cmd/opensave-app/frontend/src/assets/logo.png': 256,
};
for (const [path, size] of Object.entries(targets)) {
  writeFileSync(join(root, path), render(size));
  console.log(`${path} ${size}px`);
}

const out = join(here, 'out');
mkdirSync(out, { recursive: true });
for (const size of [16, 24, 32, 48, 64, 128, 256]) {
  writeFileSync(join(out, `icon-${size}.png`), render(size));
}
console.log(`ico sources in ${out}`);
