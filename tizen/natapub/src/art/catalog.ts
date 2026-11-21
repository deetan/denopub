// Port of Art.kt ArtCatalog: the bundled catalog (public/paintings.json, built from the Android
// asset), refreshed daily from GitHub `main`, and a persisted shuffled deck.
import { log } from '../log';
import { slimAll, type Painting, type RawPainting } from './painting';

const REMOTE = 'https://raw.githubusercontent.com/deetan/denopub/main/app/src/main/assets/art/paintings.json';
const K_CATALOG = 'natapub.art.catalog'; // { at, list } — slimmed, ~230 KB
const K_DECK = 'natapub.art.deck';
const K_LAST = 'natapub.art.last';
const DAY = 24 * 3600 * 1000;

interface Cached { at: number; list: Painting[] }

function get(url: string, timeout: number): Promise<string> {
  return new Promise((ok, fail) => {
    const x = new XMLHttpRequest();
    x.open('GET', url);
    x.timeout = timeout;
    // A packaged Tizen app reads its own files with status 0.
    x.onload = () => (x.status === 200 || (x.status === 0 && x.responseText) ? ok(x.responseText) : fail(new Error('HTTP ' + x.status)));
    x.onerror = () => fail(new Error('network'));
    x.ontimeout = () => fail(new Error('timeout'));
    x.send();
  });
}

function readCached(): Cached | null {
  try {
    const c = JSON.parse(localStorage.getItem(K_CATALOG) || 'null') as Cached | null;
    return c && c.list && c.list.length ? c : null;
  } catch (e) {
    return null;
  }
}

let bundled: Painting[] | null = null;

const stories = (l: Painting[]) => l.filter((p) => !!p.story).length;

/**
 * A downloaded copy replaces the bundled one only if it is not worse: at least as many paintings
 * (else it would hide works added in a release) and at least as many English stories (`storyEn`
 * reached the bundled file before GitHub `main`).
 */
function notWorse(l: Painting[], than: Painting[]): boolean {
  return l.length >= than.length && stories(l) >= stories(than);
}

export async function loadCatalog(): Promise<Painting[]> {
  if (!bundled) {
    const t0 = Date.now();
    try {
      bundled = JSON.parse(await get('./paintings.json', 15000)) as Painting[];
      log('art: bundled catalog', bundled.length, 'in', Date.now() - t0, 'ms');
    } catch (e) {
      log('art: bundled catalog failed:', (e as Error).message);
      bundled = [];
    }
  }
  const cached = readCached();
  const useCached = !!cached && notWorse(cached.list, bundled);
  const list = useCached && cached ? cached.list : bundled;
  log('art: catalog from', useCached ? 'GitHub copy' : 'bundle', '-', list.length, 'paintings,', stories(list), 'English stories' +
    (cached && !useCached ? ' (cached GitHub copy ' + cached.list.length + ' / ' + stories(cached.list) + ' ignored)' : ''));
  if (import.meta.env.DEV && /[?&]story/.test(location.search)) {
    // Desktop layout check before the catalog has English stories: a sample of the right length.
    const sample = 'Look closely at the faces: every one of them shows a different feeling. The painter worked for months, ' +
      'mixing colours from crushed stones and oils, and painted the light so softly that it seems to glow. Find the small dog in the corner.';
    list.forEach((p) => (p.story = p.story || sample));
  }
  return list;
}

/** Pulls the latest catalog from GitHub if the cached one is older than a day. */
export async function refreshCatalog(): Promise<void> {
  const c = readCached();
  let at = 0;
  try {
    at = Number(localStorage.getItem(K_CATALOG + '.at') || 0);
  } catch (e) {
    /* none */
  }
  if (Math.max(at, c ? c.at : 0) > Date.now() - DAY) return;
  try {
    const list = slimAll(JSON.parse(await get(REMOTE, 60000)) as RawPainting[]);
    try {
      localStorage.setItem(K_CATALOG + '.at', String(Date.now()));
    } catch (e) {
      /* quota */
    }
    if (!list.length) return;
    if (bundled && !notWorse(list, bundled)) {
      log('art: remote catalog', list.length, 'paintings /', stories(list), 'stories vs bundled', bundled.length, '/', stories(bundled), ': not kept');
      return;
    }
    try {
      localStorage.setItem(K_CATALOG, JSON.stringify({ at: Date.now(), list } as Cached));
      log('art: catalog refreshed,', list.length, 'paintings,', stories(list), 'English stories');
    } catch (e) {
      // Quota: never let the catalog crowd out the token or the Home cache.
      localStorage.removeItem(K_CATALOG);
      log('art: catalog refresh not stored:', (e as Error).message);
    }
  } catch (e) {
    log('art: catalog refresh failed:', (e as Error).message);
  }
}

function shuffled<T>(a: T[]): T[] {
  const r = a.slice();
  for (let i = r.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    const t = r[i];
    r[i] = r[j];
    r[j] = t;
  }
  return r;
}

/** Next painting from a persisted shuffled deck; reshuffles once every painting was shown. */
export function deal(all: Painting[]): Painting | null {
  if (!all.length) return null;
  const byId: Record<string, Painting> = {};
  all.forEach((p) => (byId[p.id] = p));
  let deck: string[] = [];
  let last: string | null = null;
  try {
    deck = (localStorage.getItem(K_DECK) || '').split(',').filter((id) => !!byId[id]);
    last = localStorage.getItem(K_LAST);
  } catch (e) {
    /* none */
  }
  if (!deck.length) {
    deck = shuffled(Object.keys(byId));
    if (deck.length > 1 && deck[0] === last) deck.push(deck.shift() as string);
  }
  const id = deck.shift() as string;
  try {
    localStorage.setItem(K_DECK, deck.join(','));
    localStorage.setItem(K_LAST, id);
  } catch (e) {
    /* quota */
  }
  return byId[id];
}
