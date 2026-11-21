// Port of data/api/SkipSegments.kt: intro / recap / credits times from two free, keyless,
// crowdsourced databases, looked up by the title's IMDb id + season/episode:
//  - TheIntroDB `api.theintrodb.org/v2/media` — arrays of {start_ms,end_ms}, null = file edge
//  - IntroDB    `api.introdb.app/segments`    — one {start_ms,end_ms} per kind
// Conservative merge: when both answer for a kind they must overlap, else the kind is dropped.
// A kind is also dropped when it doesn't fit the episode length (another release's timeline).
// Both answer from the TV (packaged apps need no CORS headers).
import { log } from '../log';

export type SegmentKind = 'recap' | 'intro' | 'credits';
const KINDS: SegmentKind[] = ['recap', 'intro', 'credits'];

/** A skippable stretch. endMs = null means "until the end of the file". */
export interface Segment { kind: SegmentKind; startMs: number; endMs: number | null }

type ByKind = Record<SegmentKind, Segment[]>;

const cache: Record<string, Segment[]> = {};

/** GET → parsed object; 404 "media not found" is a real answer (nothing known); null = failure. */
function fetchJson(url: string): Promise<any | null> {
  return new Promise((ok) => {
    try {
      const x = new XMLHttpRequest();
      x.open('GET', url);
      x.timeout = 15000;
      x.onload = () => {
        if (x.status === 404) return ok({});
        if (x.status < 200 || x.status >= 300) return ok(null);
        try {
          ok(JSON.parse(x.responseText));
        } catch (e) {
          ok(null);
        }
      };
      x.onerror = () => ok(null);
      x.ontimeout = () => ok(null);
      x.send();
    } catch (e) {
      ok(null);
    }
  });
}

function ms(o: any, key: string): number | null {
  const v = o ? o[key] : null;
  return typeof v === 'number' && isFinite(v) ? Math.floor(v) : null;
}

function fromTheIntroDb(o: any): ByKind {
  const arr = (e: any, k: SegmentKind): Segment[] => {
    if (!Array.isArray(e)) return [];
    const out: Segment[] = [];
    e.forEach((x: any) => {
      const s = ms(x, 'start_ms') || 0;
      const end = ms(x, 'end_ms');
      // {0,0} is a "none" placeholder (seen for Bluey recap/preview).
      if (end != null && end <= s) return;
      out.push({ kind: k, startMs: s, endMs: end });
    });
    return out;
  };
  return { recap: arr(o.recap, 'recap'), intro: arr(o.intro, 'intro'), credits: arr(o.credits, 'credits') };
}

function fromIntroDb(o: any): ByKind {
  const one = (e: any, k: SegmentKind): Segment[] => {
    if (!e || typeof e !== 'object') return [];
    const s = ms(e, 'start_ms') || 0;
    const end = ms(e, 'end_ms');
    if (end == null || end <= s) return [];
    return [{ kind: k, startMs: s, endMs: end }];
  };
  return { recap: one(o.recap, 'recap'), intro: one(o.intro, 'intro'), credits: one(o.outro, 'credits') };
}

const BIG = Number.MAX_VALUE;
const overlaps = (x: Segment, y: Segment) => x.startMs < (y.endMs == null ? BIG : y.endMs) && y.startMs < (x.endMs == null ? BIG : x.endMs);
const known = (xs: (number | null)[]) => xs.filter((v): v is number => v != null);

function merge(k: SegmentKind, a: Segment[], b: Segment[]): Segment | null {
  if (!a.length && !b.length) return null;
  if (!a.length) return b.length === 1 ? b[0] : null;
  // TheIntroDB sometimes holds two different intros for one episode: unusable alone.
  if (!b.length) return a.length === 1 ? a[0] : null;
  const y = b[0];
  const x = a.filter((s) => overlaps(s, y))[0];
  if (!x) return null;
  const ends = known([x.endMs, y.endMs]);
  if (k === 'credits') {
    // From the earlier start (it's only a button) — Silo S1E3: 61:02 vs 61:48 of a 62:02 file.
    // End: the earlier known one (a post-credits scene may follow).
    return { kind: k, startMs: Math.min(x.startMs, y.startMs), endMs: ends.length ? Math.min.apply(null, ends) : null };
  }
  // Ends within 20 s: the later one (Silo S1E3: IntroDB's 3:42 was exact, TheIntroDB's 3:25.6
  // left 16 s of title cards). Further apart: the earlier, so a bad entry can't swallow story.
  let end: number | null = null;
  if (ends.length === 2 && Math.max(ends[0], ends[1]) - Math.min(ends[0], ends[1]) <= 20000) end = Math.max(ends[0], ends[1]);
  else if (ends.length) end = Math.min.apply(null, ends);
  return { kind: k, startMs: Math.min(x.startMs, y.startMs), endMs: end };
}

/** Sanity against our own file: openings in the first third, credits in the last 30 % and ≤ 8 min. */
function fits(s: Segment, durMs: number): boolean {
  if (durMs <= 0) return true;
  if (s.kind === 'credits') return s.startMs >= Math.floor((durMs * 7) / 10) && s.startMs < durMs && durMs - s.startMs <= 8 * 60000;
  return s.endMs != null && s.endMs < durMs / 3;
}

const fmt = (b: ByKind | null) => {
  if (!b) return 'null';
  const all: Segment[] = [];
  KINDS.forEach((k) => b[k].forEach((s) => all.push(s)));
  return '[' + all.map(segText).join(', ') + ']';
};
export const segText = (s: Segment) => s.kind + ' ' + s.startMs + '-' + (s.endMs == null ? 'end' : s.endMs);

export async function segmentsFor(imdbNumericId: number, season: number | null, episode: number | null, durationSec: number): Promise<Segment[]> {
  let tt = String(imdbNumericId);
  while (tt.length < 7) tt = '0' + tt;
  tt = 'tt' + tt;
  const key = tt + '/' + season + '/' + episode;
  if (cache[key]) return cache[key];
  const q = season != null && episode != null ? '&season=' + season + '&episode=' + episode : '';
  const [ra, rb] = await Promise.all([
    fetchJson('https://api.theintrodb.org/v2/media?imdb_id=' + tt + q),
    fetchJson('https://api.introdb.app/segments?imdb_id=' + tt + q),
  ]);
  const a = ra ? fromTheIntroDb(ra) : null;
  const b = rb ? fromIntroDb(rb) : null;
  const durMs = durationSec * 1000;
  const merged: Segment[] = [];
  KINDS.forEach((k) => {
    const m = merge(k, a ? a[k] : [], b ? b[k] : []);
    if (m && fits(m, durMs)) merged.push(m);
  });
  log('skip', key, 'tidb=' + fmt(a), 'idb=' + fmt(b), '->', '[' + merged.map(segText).join(', ') + ']');
  // Only cache real answers: a network failure (both null) is retried on the next load.
  if (a || b) cache[key] = merged;
  return merged;
}
