// Builds public/paintings.json (the bundled screensaver catalog) from the Android app's
// app/src/main/assets/art/paintings.json, keeping only the English fields Natapub shows, and fails
// if any caption field still holds Cyrillic after englishDate(). Run by `npm run dev` / `build`.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { slimAll } from '../src/art/painting.ts';

const here = dirname(fileURLToPath(import.meta.url));
const src = join(here, '../../../app/src/main/assets/art/paintings.json');
const out = join(here, '../public/paintings.json');
const raw = JSON.parse(readFileSync(src, 'utf8'));
const list = slimAll(raw);
// slim() drops any field that still holds Cyrillic; here a dropped date / life / title is a bug.
const cyr = /[А-Яа-яЁё]/;
const bad = raw.filter((r) => r.commons || r.url).filter((r, i) => {
  const p = list[i];
  return !p.title || (r.date && !p.date) || (r.artistLife && !p.life) || cyr.test(r.titleEn || '') || cyr.test(r.storyEn || '');
});
if (bad.length) {
  console.error('paintings: ' + bad.length + ' entries would show Russian or lose a caption field, e.g.\n  ' +
    bad.slice(0, 8).map((r) => r.id + ': ' + r.date + ' | ' + r.artistLife).join('\n  '));
  process.exit(1);
}
writeFileSync(out, JSON.stringify(list));
console.log('paintings: ' + list.length + ' works, ' + list.filter((p) => p.story).length + ' with an English story -> public/paintings.json (' + Math.round(JSON.stringify(list).length / 1024) + ' KB)');
