// Port of data/Repository.kt: kino.pub is the source of truth; local stamps only fill its gaps.
import { kp } from '../api/kinopub';
import { isSerial } from '../api/models';
import type { HistoryEntry, Item, Video, WatchingInfo, WatchingVideo } from '../api/models';
import { log } from '../log';
import { prefs } from './prefs';

export interface ResumeTarget {
  itemId: number;
  season: number | null; // null for movies
  episode: number; // episode number, or part number for movies
  startSec: number;
}

export interface ContinueEntry {
  itemId: number;
  title: string;
  poster: string | null;
  isSerial: boolean;
  target: ResumeTarget;
  durationSec: number;
  newEpisodes: number;
  remainingEpisodes: number;
  isKids: boolean;
  lastActivityMs: number;
}

export interface WishlistEntry { item: Item; isNew: boolean }

export interface HomeData {
  continueWatching: ContinueEntry[];
  wishlist: WishlistEntry[];
  freshMovies: Item[];
  freshSerials: Item[];
  hot: Item[];
}

export const episodeLabel = (e: ContinueEntry): string | null =>
  e.target.season != null ? 'S' + e.target.season + '·E' + e.target.episode : null;
export const progressOf = (e: ContinueEntry): number => (e.durationSec > 0 ? e.target.startSec / e.durationSec : 0);

export const continueAdult = (d: HomeData) => d.continueWatching.filter((e) => !e.isKids);
export const continueSerials = (d: HomeData) => continueAdult(d).filter((e) => e.isSerial);
export const continueMovies = (d: HomeData) => continueAdult(d).filter((e) => !e.isSerial);
export const continueKids = (d: HomeData) => d.continueWatching.filter((e) => e.isKids);

/** Kids row: animation and children's genres ("Семейный" and "Аниме" deliberately not). */
export function isKidsContent(item: Item): boolean {
  return (item.genres || []).some((g) => {
    const t = (g.title || '').toLowerCase();
    return t.indexOf('мульт') === 0 || t.indexOf('детск') === 0;
  });
}

const DAY = 24 * 3600 * 1000;
const MID_ONLY_MAX_AGE_MS = 120 * DAY;
const WISHLIST_TITLE = 'Wishlist';
// In priority order. Natapub's kino.pub account keeps its to-watch list in "watch" (owner, 2026-11-21);
// the Android names follow, and "Wishlist" is created only if none exists.
const WISHLIST_NAMES = ['watch', 'wishlist', 'хочу посмотреть', 'посмотреть'];

const maxOf = (xs: number[]) => xs.reduce((a, b) => (b > a ? b : a), 0);

function concat<T>(lists: T[][]): T[] {
  return ([] as T[]).concat.apply([], lists);
}

export function cachedHome(): HomeData | null {
  return prefs.cache<HomeData>('home');
}

/**
 * A part that fails — or comes back empty (a sleeping TV's dead network looks like that) — keeps
 * its previous content.
 */
export async function home(): Promise<HomeData> {
  const prev = cachedHome();
  async function part<T>(old: T[] | undefined, load: () => Promise<T[]>, name: string): Promise<T[]> {
    try {
      const v = await load();
      if (v.length) return v;
    } catch (e) {
      log('home: ' + name + ' failed:', (e as Error).message);
    }
    return old || [];
  }
  const r = await Promise.all([
    part(prev ? prev.continueWatching : undefined, () => continueWatching(), 'continue'),
    part(prev ? prev.wishlist : undefined, () => wishlist(), 'wishlist'),
    part(prev ? prev.freshMovies : undefined, () => kp.fresh('movie').then((x) => x.items || []), 'fresh movies'),
    part(prev ? prev.freshSerials : undefined, () => kp.fresh('serial').then((x) => x.items || []), 'fresh serials'),
    part(prev ? prev.hot : undefined, () => kp.hot('movie').then((x) => x.items || []), 'hot'),
  ]);
  const d: HomeData = { continueWatching: r[0], wishlist: r[1], freshMovies: r[2], freshSerials: r[3], hot: r[4] };
  prefs.saveCache('home', slimHome(d));
  return d;
}

/** Cached Home without the heavy item fields (localStorage is small). */
function slimHome(d: HomeData): HomeData {
  const slim = (it: Item): Item => ({
    id: it.id, title: it.title, type: it.type, year: it.year, posters: it.posters,
    imdb_rating: it.imdb_rating,
    // isSerial() also reads seasons; keep the answer in `type` for items that lack one.
    ...(isSerial(it) && !it.type ? { type: 'serial' } : {}),
  });
  return {
    continueWatching: d.continueWatching,
    wishlist: d.wishlist.map((w) => ({ item: slim(w.item), isNew: w.isNew })),
    freshMovies: d.freshMovies.map(slim),
    freshSerials: d.freshSerials.map(slim),
    hot: d.hot.map(slim),
  };
}

/**
 * Everything in progress, most recent first. History gives order and the last-touched episode;
 * /watching gives exact per-episode status to pick the resume point. Every candidate is checked
 * (a cap once dropped Peppa).
 */
export async function continueWatching(limit = 80): Promise<ContinueEntry[]> {
  // One binge can fill a whole history page, so read two.
  const [h1, h2, serials, movies] = await Promise.all([
    kp.history(1),
    kp.history(2).catch(() => [] as HistoryEntry[]),
    kp.watchingSerials(),
    kp.watchingMovies(),
  ]);
  const history = h1.concat(h2);

  const newCounts: Record<number, number> = {};
  serials.forEach((s) => (newCounts[s.id] = s.new || 0));

  const lastWatchedMs: Record<number, number> = {};
  history.forEach((h) => {
    lastWatchedMs[h.item.id] = Math.max(lastWatchedMs[h.item.id] || 0, h.last_seen * 1000);
  });
  const now = Date.now();
  const startedAll = prefs.startedAt();
  const started: Record<string, number> = {};
  Object.keys(startedAll).forEach((k) => {
    if (now - startedAll[k] < 30 * DAY) started[k] = startedAll[k];
  });
  const played = prefs.playedAt();
  const recency = (id: number) => Math.max(lastWatchedMs[id] || 0, started[id] || 0, played[id] || 0);

  const listed: Record<number, Item> = {};
  serials.forEach((s) => (listed[s.id] = { id: s.id, title: s.title, type: s.type, posters: s.posters }));
  movies.forEach((m) => (listed[m.id] = { id: m.id, title: m.title, type: m.type, posters: m.posters }));

  const order: number[] = [];
  const candidates: Record<number, Item> = {};
  const put = (it: Item) => {
    if (candidates[it.id]) return false;
    candidates[it.id] = it;
    order.push(it.id);
    return true;
  };
  // Started / played titles first, so a fresh "Начал смотреть" is never cut by the limit.
  const local = Object.keys(played).concat(Object.keys(started)).map(Number);
  for (const id of local) {
    if (candidates[id]) continue;
    const it = listed[id] || (await kp.item(id, true).catch(() => null));
    if (it) put(it);
  }
  history.forEach((h) => put(h.item));
  serials.filter((s) => s.subscribed).forEach((s) => put(listed[s.id]));
  movies.forEach((m) => put(listed[m.id]));
  // Unsubscribed serials only with a half-watched episode touched in the last 120 days.
  const midOnly: Record<number, boolean> = {};
  serials.filter((s) => !s.subscribed).forEach((s) => {
    if (put(listed[s.id])) midOnly[s.id] = true;
  });

  const kids = prefs.kidsFlags();
  log('continue:', order.length, 'candidates:', order.map((id) => id + ':' + candidates[id].title.substr(0, 16)).join(', '));

  const updated: Record<number, number> = {};
  const results = await Promise.all(order.slice(0, limit).map(async (id) => {
    const item = candidates[id];
    try {
      const info = await kp.watching(id, true);
      const updatedMs = maxOf(concat(info.seasons.map((s) => s.episodes)).concat(info.videos)
        .map((v) => (v.updated || 0) * 1000));
      updated[id] = updatedMs;
      const stale = midOnly[id] && now - updatedMs > MID_ONLY_MAX_AGE_MS;
      const e = stale ? null : resumeEntry(item, info, newCounts[id] || 0, !!(started[id] || played[id]), !!midOnly[id]);
      if (!e) {
        log('continue: dropped', id, item.title, '(nothing to resume)');
        return null;
      }
      e.isKids = kids[id] === true;
      e.lastActivityMs = Math.max(recency(id), updatedMs);
      return e;
    } catch (err) {
      log('continue: failed', id, item.title, (err as Error).message.substr(0, 200));
      return null;
    }
  }));
  const list = results.filter((e): e is ContinueEntry => e != null);
  // Stable sort, newest first; untimed keep candidate order.
  const idx: Record<number, number> = {};
  list.forEach((e, i) => (idx[e.itemId] = i));
  list.sort((a, b) => b.lastActivityMs - a.lastActivityMs || idx[a.itemId] - idx[b.itemId]);
  return list;
}

/** Titles in [entries] not yet classified as kids/adult. */
export function unclassified(entries: ContinueEntry[]): number[] {
  const known = prefs.kidsFlags();
  return entries.map((e) => e.itemId).filter((id) => known[id] === undefined);
}

/** Fetches genres for [ids] (background lane) and stores the verdict; returns kids' ids. */
export async function classifyKids(ids: number[]): Promise<number[]> {
  const flags: Record<string, boolean> = {};
  await Promise.all(ids.map((id) => kp.item(id, true).then((it) => (flags[id] = isKidsContent(it))).catch(() => null)));
  prefs.saveKidsFlags(flags);
  return Object.keys(flags).filter((k) => flags[k]).map(Number);
}

/**
 * [explicit]: started here ("Начал смотреть" or played). Then a title with no progress still gets
 * a card, from its first unwatched part/episode.
 */
function resumeEntry(item: Item, info: WatchingInfo, newEpisodes: number, explicit: boolean, midEpisodeOnly: boolean): ContinueEntry | null {
  const p = item.posters || {};
  const poster = p.wide || p.big || p.medium || null;
  const base = { itemId: item.id, title: item.title, poster, isKids: false, lastActivityMs: 0 };
  if (info.seasons.length) {
    const flat: [number, WatchingVideo][] = [];
    info.seasons.slice().sort((a, b) => a.number - b.number).forEach((s) => {
      s.episodes.slice().sort((a, b) => a.number - b.number).forEach((e) => flat.push([s.number, e]));
    });
    // marktime stores time without touching status: "in progress" = time > 0, status != 1.
    let lastTouched = -1;
    flat.forEach(([, e], i) => {
      if (e.status === 1 || e.time > 0) lastTouched = i;
    });
    if (lastTouched < 0 && !explicit && newEpisodes === 0) return null;
    if (midEpisodeOnly && !flat.some(([, e]) => e.time > 0 && e.status !== 1)) return null;
    const next = flat.slice(Math.max(lastTouched, 0)).filter(([, e]) => e.status !== 1)[0];
    if (!next) return null;
    const [season, ep] = next;
    return Object.assign(base, {
      isSerial: true,
      target: { itemId: item.id, season, episode: ep.number, startSec: ep.time },
      durationSec: ep.duration,
      newEpisodes,
      remainingEpisodes: flat.filter(([, e]) => e.status !== 1).length,
    });
  }
  const part = info.videos.filter((v) => v.status !== 1 && (v.status === 0 || v.time > 0))[0]
    || info.videos.filter((v) => explicit && v.status !== 1)[0];
  if (!part) return null;
  return Object.assign(base, {
    isSerial: false,
    target: { itemId: item.id, season: null, episode: part.number, startSec: part.time },
    durationSec: part.duration,
    newEpisodes: 0,
    remainingEpisodes: 0,
  });
}

// ---------- wishlist = a kino.pub bookmark folder ----------

let wishlistFolderId: number | null = null;
let wishlistFolderInfo = '';

async function wishlistFolder(): Promise<number> {
  if (wishlistFolderId != null) return wishlistFolderId;
  const folders = await kp.bookmarkFolders();
  const found = WISHLIST_NAMES.map((n) => folders.filter((f) => f.title.trim().toLowerCase() === n)[0])
    .filter((f) => !!f)[0];
  let id = found ? found.id : null;
  if (id == null) {
    const created = await kp.createBookmarkFolder(WISHLIST_TITLE);
    id = created ? created.id : (await kp.bookmarkFolders()).filter((f) => f.title === WISHLIST_TITLE)[0].id;
  }
  wishlistFolderId = id;
  // Same matching as Android; the account may simply have no such folder yet (then it is created).
  wishlistFolderInfo = (found ? '"' + found.title + '" count ' + (found.count != null ? found.count : '?') : '(created)') +
    '; all folders: ' + folders.map((f) => '"' + f.title + '" ' + (f.count != null ? f.count : '?')).join(', ');
  return id;
}

export async function wishlist(): Promise<WishlistEntry[]> {
  const folder = await wishlistFolder();
  const items = await kp.bookmarkItems(folder);
  log('wishlist: folder', folder, wishlistFolderInfo, '-> returned', items.length, 'items');
  const seen = prefs.seenWishlist();
  return items.map((item) => ({ item, isNew: seen.indexOf(item.id) < 0 }));
}

export async function isInWishlist(id: number): Promise<boolean> {
  const folder = await wishlistFolder();
  return (await kp.itemFolders(id)).some((f) => f.id === folder);
}

/** Returns the new state. kino.pub can hold an item twice: remove until it's gone. */
export async function setWishlisted(id: number, on: boolean): Promise<boolean> {
  const folder = await wishlistFolder();
  const inIt = async () => (await kp.itemFolders(id)).some((f) => f.id === folder);
  if (on) {
    if (!(await inIt())) await kp.addBookmark(id, folder);
    return true;
  }
  for (let i = 0; i < 4; i++) {
    await kp.removeBookmark(id, folder);
    if (!(await inIt())) return false;
  }
  log('wishlist:', id, 'still in folder', folder, 'after removal');
  return inIt();
}

// ---------- details / playback ----------

export function video(item: Item, season: number | null, episode: number): Video | null {
  if (season == null) {
    const vs = item.videos || [];
    return vs.filter((v) => v.number === episode)[0] || vs[0] || null;
  }
  const s = (item.seasons || []).filter((x) => x.number === season)[0];
  return s ? s.episodes.filter((e) => e.number === episode)[0] || null : null;
}

/** Every episode in order across seasons (or the parts of a movie). */
export function flatEpisodes(item: Item): [number | null, Video][] {
  if ((item.seasons || []).length) {
    const out: [number | null, Video][] = [];
    (item.seasons || []).slice().sort((a, b) => a.number - b.number).forEach((s) => {
      s.episodes.slice().sort((a, b) => a.number - b.number).forEach((v) => out.push([s.number, v]));
    });
    return out;
  }
  return (item.videos || []).slice().sort((a, b) => a.number - b.number).map((v) => [null, v] as [number | null, Video]);
}

/** The episode after (season, episode), crossing season boundaries. */
export function nextOf(item: Item, season: number | null, episode: number): [number | null, number] | null {
  if (season == null) {
    const v = (item.videos || []).filter((x) => x.number === episode + 1)[0];
    return v ? [null, v.number] : null;
  }
  const flat = flatEpisodes(item);
  for (let i = 0; i < flat.length - 1; i++) {
    if (flat[i][0] === season && flat[i][1].number === episode) return [flat[i + 1][0], flat[i + 1][1].number];
  }
  return null;
}

// ---------- user actions (all written to kino.pub) ----------

export const setEpisodeWatched = (id: number, season: number | null, episode: number, watched: boolean) =>
  kp.setWatched(id, watched, season, episode);

export const setSeasonWatched = (id: number, season: number, watched: boolean) => kp.setWatched(id, watched, season, null);

/** Whole title: every season of a serial, every part of a movie. */
export async function setItemWatched(item: Item, watched: boolean): Promise<void> {
  if ((item.seasons || []).length) {
    for (const s of item.seasons || []) await kp.setWatched(item.id, watched, s.number, null);
  } else {
    for (const v of item.videos || []) await kp.setWatched(item.id, watched, null, v.number);
  }
}

/**
 * "Начал смотреть": serials → kino.pub "Я смотрю"; movies → a 1-second mark plus the local stamp
 * (kino.pub ignores a 1-second marktime, the stamp is what makes it show).
 */
export async function setWatching(item: Item, on: boolean): Promise<boolean> {
  prefs.markStarted(item.id, on);
  if (isSerial(item)) {
    const now = item.in_watchlist === true;
    return now !== on ? kp.toggleWatchlist(item.id) : now;
  }
  const v = (item.videos || [])[0];
  if (on) await kp.markTime(item.id, v ? v.number : 1, 1, null);
  else await kp.clearHistory(item.id);
  return on;
}

/** Drop from Continue on kino.pub: clear history and unsubscribe a serial. */
export async function removeFromContinue(id: number): Promise<void> {
  prefs.markStarted(id, false);
  prefs.markPlayed(id, false);
  await kp.clearHistory(id);
  try {
    const item = await kp.item(id);
    if (isSerial(item) && item.in_watchlist === true) await kp.toggleWatchlist(id);
  } catch (e) {
    log('removeFromContinue: unsubscribe failed', (e as Error).message);
  }
}

// ---------- search ----------

/** kino.pub search; 3D releases are separate items (type "3D") the owner never wants on the TV. */
export async function search(query: string, type: 'movie' | 'serial' | null): Promise<Item[]> {
  const items = await kp.search(query);
  return items.filter((it) => {
    const is3d = (it.type || '').toLowerCase() === '3d';
    if (is3d) log('search: hid 3D', it.id, it.title);
    if (is3d) return false;
    if (type === 'serial') return isSerial(it);
    if (type === 'movie') return !isSerial(it);
    return true;
  });
}
