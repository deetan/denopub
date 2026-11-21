// The paintings catalog entry, slimmed for the TV, and its English caption fields. Plain
// TypeScript with erasable types only: scripts/paintings.mjs imports this file directly (Node
// strips the types) to build public/paintings.json and to check that no Russian reaches a caption.

/** One painting as Natapub keeps it: the English fields of app/src/main/assets/art/paintings.json. */
export interface Painting {
  id: string;
  title: string; // titleEn
  artist: string; // artistEn
  life: string; // artistLife, in English
  date: string; // in English
  museum: string; // museumEn
  story?: string; // storyEn (an English story for a child); absent → title block only
  commons?: string; // Wikimedia Commons file name (no "File:")
  url?: string; // direct URL (copyrighted 20th-century works)
  w: number;
  h: number;
}

/** The catalog's own shape (Art.kt `Painting`); only the fields we read. */
export interface RawPainting {
  id: string;
  titleRu?: string;
  titleEn?: string;
  date?: string;
  artistRu?: string;
  artistEn?: string;
  artistLife?: string;
  museumRu?: string;
  museumEn?: string;
  storyEn?: string;
  commons?: string | null;
  url?: string | null;
  w?: number;
  h?: number;
}

const ROMAN: Record<string, number> = { I: 1, V: 5, X: 10, L: 50, C: 100 };

function roman(s: string): number {
  let n = 0;
  for (let i = 0; i < s.length; i++) {
    const v = ROMAN[s[i]];
    const next = ROMAN[s[i + 1]] || 0;
    n += v < next ? -v : v;
  }
  return n;
}

function ordinal(n: number): string {
  const t = n % 100;
  const suf = t >= 11 && t <= 13 ? 'th' : ['th', 'st', 'nd', 'rd'][n % 10] || 'th';
  return n + suf;
}

/**
 * The catalog writes dates and life spans in Russian ("около 1305", "XVII век до н. э.",
 * "работал около 1480–1520"). This turns every form found in the 910 entries into English;
 * scripts/paintings.mjs fails the build if any Cyrillic survives.
 */
export function englishDate(s: string): string {
  let r = ' ' + s.trim() + ' ';
  const rules: [RegExp, string][] = [
    [/до н\. ?э\./g, 'BC'],
    [/н\. ?э\./g, 'AD'],
    [/первая половина/g, 'first half of the'],
    [/вторая половина/g, 'second half of the'],
    [/первая четверть/g, 'first quarter of the'],
    [/вторая четверть/g, 'second quarter of the'],
    [/третья четверть/g, 'third quarter of the'],
    [/последняя четверть/g, 'last quarter of the'],
    [/середина/g, 'mid'],
    [/начало/g, 'early'],
    [/конец/g, 'late'],
    [/работал(а)?/g, 'active'],
    [/род\./g, 'b.'],
    [/умер(ла)? в/g, 'd.'],
    [/умер(ла)?/g, 'd.'],
    [/около/g, 'c.'],
    [/после/g, 'after'],
    [/ до /g, ' before '],
    [/ или /g, ' or '],
    [/(\d+)-[ех]/g, '$1s'],
  ];
  rules.forEach((x) => (r = r.replace(x[0], x[1])));
  // "XVI–XV века" → "16th–15th centuries", "XVII век(а)" → "17th century".
  r = r.replace(/([IVXLC]+)[–-]([IVXLC]+) век(а|ов)?/g, (_m, a: string, b: string) =>
    ordinal(roman(a)) + '–' + ordinal(roman(b)) + ' centuries');
  r = r.replace(/([IVXLC]+) век(а|е)?/g, (_m, a: string) => ordinal(roman(a)) + ' century');
  return r.replace(/\s+/g, ' ').trim();
}

const CYRILLIC = /[А-Яа-яЁё]/;

/** Never Russian on the screensaver (owner 2026-11-21): a field that still has Cyrillic is dropped. */
function en(s: string | undefined): string {
  const t = (s || '').trim();
  return CYRILLIC.test(t) ? '' : t;
}

export function slim(p: RawPainting): Painting {
  return {
    id: p.id,
    title: en(p.titleEn),
    artist: en(p.artistEn),
    life: en(englishDate(p.artistLife || '')),
    date: en(englishDate(p.date || '')),
    museum: en(p.museumEn),
    story: en(p.storyEn) || undefined,
    commons: p.commons || undefined,
    url: p.url || undefined,
    w: p.w || 0,
    h: p.h || 0,
  };
}

/** Only entries with an image source are shown (Art.kt `load`). */
export function slimAll(list: RawPainting[]): Painting[] {
  return list.filter((p) => !!(p && p.id && (p.commons || p.url))).map(slim);
}

export function aspect(p: Painting): number {
  return p.w > 0 && p.h > 0 ? p.w / p.h : 1.5;
}

/** Very wide works (scrolls, panoramas) are panned at full height instead of fitted. */
export function isPanorama(a: number): boolean {
  return a > 2.3;
}

/** Caption lines, English only (the catalog's stories are Russian and are not shown). */
export function artistLine(p: Painting): string {
  let s = p.artist;
  if (p.life) s += ' (' + p.life + ')';
  if (p.date) s += '  ·  ' + p.date;
  return s;
}

/**
 * Commons serves standard thumbnail widths (1280 / 1920 / 3840); [panoramaWidth] is lowered on a
 * weak TV. The original file when it is smaller than the width asked for.
 */
export function imageUrl(p: Painting, panoramaWidth = 3840): string | null {
  if (p.commons) {
    const a = aspect(p);
    const width = isPanorama(a) ? panoramaWidth : a < 1 ? 1280 : 1920;
    const base = 'https://commons.wikimedia.org/wiki/Special:FilePath/' + encodeURIComponent(p.commons.replace(/ /g, '_'));
    return p.w > 0 && p.w < width ? base : base + '?width=' + width;
  }
  return p.url || null;
}
