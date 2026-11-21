// Fails the build if the bundle (or our sources) use something Chromium 63 (Tizen 5.0) lacks.
// esbuild lowers syntax to chrome63 but never polyfills APIs, and CSS features are not lowered.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const walk = (d) => readdirSync(d).flatMap((f) => {
  const p = join(d, f);
  return statSync(p).isDirectory() ? walk(p) : [p];
});
const fail = [];
const check = (file, rules) => {
  // Comments may name the very features they warn about.
  const text = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"])\/\/.*$/gm, '$1');
  for (const [name, re] of rules) if (re.test(text)) fail.push(`${file}: ${name}`);
};

// Syntax that must not survive the build.
const distSyntax = [
  ['optional chaining ?.', /\?\.[A-Za-z_$[(]/],
  ['nullish ??', /\?\?/],
  ['import.meta', /import\.meta/],
  ['ES module import/export', /^\s*(import|export)\s[^(]/m],
  ['dev fixture in the TV build', /fixture mode|bipbop/],
];
// Runtime APIs newer than Chrome 63 (checked in our sources; hls.min.js is the spike-proven build).
const srcApis = [
  ['Array.prototype.flat', /\.flat\(/],
  ['Array.prototype.flatMap', /\.flatMap\(/],
  ['Object.fromEntries', /Object\.fromEntries/],
  ['Promise.allSettled', /allSettled/],
  ['String.replaceAll', /\.replaceAll\(/],
  ['Array/String .at()', /\.at\(-?\d/],
  ['AbortController', /AbortController/],
  ['ResizeObserver', /ResizeObserver/],
  ['queueMicrotask', /queueMicrotask/],
  ['globalThis', /globalThis/],
  ['matchAll', /\.matchAll\(/],
  ['IntersectionObserver', /IntersectionObserver/],
];
const cssRules = [
  ['flex/grid gap', /(^|[\s;{])(row-|column-)?gap\s*:/m],
  ['aspect-ratio', /aspect-ratio/],
  ['inset:', /(^|[\s;{])inset\s*:/m],
  ['clamp()', /clamp\(/],
  [':focus-visible', /:focus-visible/],
  [':is()/:where()', /:(is|where)\(/],
];

for (const f of walk('dist')) {
  if (f.endsWith('.js') && !f.endsWith('hls.min.js')) check(f, distSyntax);
  if (f.endsWith('.css')) check(f, cssRules);
}
for (const f of walk('src')) {
  if (/\.(ts|tsx)$/.test(f)) check(f, srcApis);
  if (f.endsWith('.css')) check(f, cssRules);
}
if (fail.length) {
  console.error('Chrome 63 check FAILED:\n  ' + fail.join('\n  '));
  process.exit(1);
}
console.log('Chrome 63 check: ok (' + walk('dist').length + ' files in dist)');
