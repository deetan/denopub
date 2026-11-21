import { log } from '../log';
import { ApiError, NotAuthorized, shortPath, throttled } from './http';
import type {
  BookmarkFolder, DeviceCode, HistoryEntry, Item, ItemsResponse, TokenResponse, WatchingInfo,
  WatchingMovie, WatchingSerial,
} from './models';

// kino.pub moved to kino.watch (2026-11). Desktop dev goes through the Vite proxy (CORS).
const ROOT = import.meta.env.DEV ? '/kw/api/' : 'https://kino.watch/api/';
const API = ROOT + 'v1/';
const OAUTH = ROOT + 'oauth2/device';
const TOKEN = ROOT + 'oauth2/token';
// Public client credentials of the open-source Kodi plugin (same as the Android app).
const CLIENT = { client_id: 'xbmc', client_secret: 'cgg3gtifu46urtfp2zp1nqtba0k2ezxh' };

type Q = Record<string, string | number | boolean | null | undefined>;

function qs(q: Q): string {
  return Object.keys(q)
    .filter((k) => q[k] !== null && q[k] !== undefined)
    .map((k) => encodeURIComponent(k) + '=' + encodeURIComponent(String(q[k])))
    .join('&');
}

// ---------- tokens (localStorage) ----------

interface Tokens { access: string; refresh: string; exp: number }
const TOK_KEY = 'natapub.tok';

export function tokens(): Tokens | null {
  try {
    return JSON.parse(localStorage.getItem(TOK_KEY) || 'null');
  } catch (e) {
    return null;
  }
}

function saveTokens(r: TokenResponse): void {
  const old = tokens();
  const t: Tokens = {
    access: r.access_token as string,
    refresh: r.refresh_token || (old ? old.refresh : ''),
    exp: Date.now() + (r.expires_in || 3600) * 1000,
  };
  localStorage.setItem(TOK_KEY, JSON.stringify(t));
}

export function clearTokens(): void {
  localStorage.removeItem(TOK_KEY);
}

/** Fired when kino.pub rejects the refresh token: the app shows the login screen. */
export const authLost: { listener: (() => void) | null } = { listener: null };

let refreshing: Promise<boolean> | null = null;

/**
 * Single-flight: Home fires dozens of calls at once and kino.pub rotates refresh tokens, so two
 * parallel refreshes would log the owner out.
 */
function refresh(force = false): Promise<boolean> {
  const t = tokens();
  if (!t) return Promise.resolve(false);
  if (!force && Date.now() < t.exp - 60000) return Promise.resolve(true);
  if (refreshing) return refreshing;
  refreshing = (async () => {
    try {
      const r = await form<TokenResponse>(TOKEN, { grant_type: 'refresh_token', refresh_token: t.refresh }, true);
      if (!r.access_token) {
        log('token refresh rejected:', r.error || '?');
        clearTokens();
        if (authLost.listener) authLost.listener();
        return false;
      }
      saveTokens(r);
      log('token refreshed');
      return true;
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

// ---------- plumbing ----------

interface Opts { bg?: boolean; allowErrorBody?: boolean }

function parse<T>(status: number, text: string, path: string, allowErrorBody: boolean): T {
  // An HTML page instead of JSON = kino.pub's web server is down / mid-maintenance.
  if (text.replace(/^\s+/, '').charAt(0) === '<') throw new ApiError(status, 'kino.pub не отвечает (HTTP ' + status + '), попробуйте позже');
  if (status === 429) throw new ApiError(429, 'kino.pub: слишком много запросов, попробуйте через минуту');
  if ((status < 200 || status > 299) && !allowErrorBody) throw new ApiError(status, text.substr(0, 300) || 'HTTP ' + status + ' ' + path);
  if (!text) return {} as T;
  try {
    return JSON.parse(text) as T;
  } catch (e) {
    throw new ApiError(status, 'kino.pub: неверный ответ (' + path + ')');
  }
}

async function form<T>(url: string, body: Q, allowErrorBody: boolean): Promise<T> {
  const all: Q = {};
  Object.keys(body).forEach((k) => (all[k] = body[k]));
  all.client_id = CLIENT.client_id;
  all.client_secret = CLIENT.client_secret;
  const r = await throttled({ method: 'POST', url, body: qs(all), headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
  return parse<T>(r.status, r.text, shortPath(url), allowErrorBody);
}

async function call<T>(method: 'GET' | 'POST', path: string, q: Q, body: Q | null, o: Opts = {}): Promise<T> {
  const query = qs(q);
  const url = API + path + (query ? '?' + query : '');
  for (let attempt = 0; ; attempt++) {
    if (!(await refresh(attempt > 0))) throw new NotAuthorized();
    const t = tokens();
    if (!t) throw new NotAuthorized();
    const headers: Record<string, string> = { Authorization: 'Bearer ' + t.access };
    if (body) headers['Content-Type'] = 'application/x-www-form-urlencoded';
    const r = await throttled({ method, url, body: body ? qs(body) : undefined, headers, bg: o.bg });
    // 401 with a token we thought fresh: refresh once and retry once.
    if (r.status === 401) {
      if (attempt === 0) continue;
      throw new NotAuthorized();
    }
    return parse<T>(r.status, r.text, path, !!o.allowErrorBody);
  }
}

const get = <T>(path: string, q: Q = {}, o: Opts = {}) => call<T>('GET', path, q, null, o);
const post = <T>(path: string, q: Q, body: Q, o: Opts = {}) => call<T>('POST', path, q, body, o);

// ---------- OAuth device flow ----------

export function requestDeviceCode(): Promise<DeviceCode> {
  return form<DeviceCode>(OAUTH, { grant_type: 'device_code' }, false);
}

/** True once the owner confirmed the code; false while still pending. */
export async function pollDeviceToken(code: string): Promise<boolean> {
  const r = await form<TokenResponse>(OAUTH, { grant_type: 'device_token', code }, true);
  if (r.access_token) {
    saveTokens(r);
    return true;
  }
  if (r.error === 'authorization_pending' || r.error === 'slow_down') return false;
  throw new ApiError(400, r.error || 'device_token failed');
}

// ---------- content ----------

export const kp = {
  item: (id: number, bg = false) => get<{ item: Item }>('items/' + id, {}, { bg }).then((r) => r.item),
  /** Docs say `items/fresh`; some live clients see only `items/new`. Try both. */
  fresh: (type: string) =>
    get<ItemsResponse>('items/fresh', { type, page: 1 }).catch(() => get<ItemsResponse>('items/new', { type, page: 1 })),
  hot: (type: string) => get<ItemsResponse>('items/hot', { type }),
  similar: (id: number) => get<ItemsResponse>('items/similar', { id }).then((r) => r.items || []),

  // ---------- watching ----------
  /** `subscribed` splits serials into two disjoint lists; we want both. */
  watchingSerials: async (): Promise<WatchingSerial[]> => {
    const a = await get<{ items: WatchingSerial[] }>('watching/serials', { subscribed: 1 });
    const b = await get<{ items: WatchingSerial[] }>('watching/serials', { subscribed: 0 });
    return (a.items || []).map((s) => Object.assign({}, s, { subscribed: true }))
      .concat((b.items || []).map((s) => Object.assign({}, s, { subscribed: false })));
  },
  watchingMovies: () => get<{ items: WatchingMovie[] }>('watching/movies').then((r) => r.items || []),
  watching: (id: number, bg = false) => get<{ item: WatchingInfo }>('watching', { id }, { bg }).then((r) => {
    const it = r.item || ({} as WatchingInfo);
    return { id: it.id, videos: it.videos || [], seasons: it.seasons || [] } as WatchingInfo;
  }),
  history: (page: number) => get<{ history: HistoryEntry[] }>('history', { page, perpage: 50 }).then((r) => r.history || []),

  /** [video] is the episode/part number, [season] only for serials. */
  markTime: (id: number, video: number, time: number, season: number | null) =>
    get<unknown>('watching/marktime', { id, video, time, season }),
  /** Idempotent: status=1|0. video null + season set = whole season; both null = every part. */
  setWatched: (id: number, watched: boolean, season: number | null, video: number | null) =>
    get<unknown>('watching/toggle', { id, season, video, status: watched ? 1 : 0 }),
  /** Serials only: kino.pub's "Я смотрю". Toggles — never retry blindly. */
  toggleWatchlist: async (id: number): Promise<boolean> => {
    const r = await get<{ watching?: unknown }>('watching/togglewatchlist', { id });
    const w = String(r.watching);
    return w === 'true' || w === '1';
  },
  clearHistory: (id: number) => post<unknown>('history/clear-for-item', { id }, {}),

  // ---------- bookmarks ----------
  bookmarkFolders: () => get<{ items: BookmarkFolder[] }>('bookmarks').then((r) => r.items || []),
  bookmarkItems: (folder: number) => get<ItemsResponse>('bookmarks/' + folder, { page: 1 }).then((r) => r.items || []),
  createBookmarkFolder: (title: string) =>
    post<{ folder?: BookmarkFolder }>('bookmarks/create', {}, { title }).then((r) => r.folder || null),
  itemFolders: (item: number) => get<{ folders: BookmarkFolder[] }>('bookmarks/get-item-folders', { item }).then((r) => r.folders || []),
  removeBookmark: (item: number, folder: number) =>
    post<unknown>('bookmarks/remove-item', {}, { item, folder }).then((r) => log('bookmarks/remove-item', item, 'from', folder, '->', r)),
  addBookmark: (item: number, folder: number) => post<unknown>('bookmarks/add', {}, { item, folder }),

  // ---------- device ----------
  notifyDevice: (title: string, hardware: string, software: string) =>
    post<unknown>('device/notify', {}, { title, hardware, software }),
  deviceInfo: () => get<{ device: { id: number } }>('device/info').then((r) => r.device),
  streamingTypes: () => get<{ items: { id: number; code?: string }[] }>('references/streaming-type').then((r) => r.items || []),
  saveDeviceSettings: (id: number, s: Q) => post<unknown>('device/' + id + '/settings', {}, s),
};

/**
 * After the device-code login: name the device in the kino.pub cabinet and ask for hls4 (one
 * manifest with every voiceover). HEVC / 4K / HDR stay off: only H.264 ≤ 1080p was proven to
 * play through hls.js on the 2019 TV.
 */
export async function afterLogin(version: string): Promise<void> {
  try {
    await kp.notifyDevice('Natapub (Samsung TV)', 'Samsung Tizen TV', navigator.userAgent.substr(0, 80) + ' / Natapub ' + version);
  } catch (e) {
    log('device/notify failed', (e as Error).message);
  }
  try {
    const dev = await kp.deviceInfo();
    const hls4 = (await kp.streamingTypes()).filter((t) => t.code === 'hls4')[0];
    const s: Q = { supportSsl: 1, mixedPlaylist: 1 };
    if (hls4) s.streamingType = hls4.id;
    await kp.saveDeviceSettings(dev.id, s);
    log('device settings saved', dev.id, s);
  } catch (e) {
    log('device settings failed', (e as Error).message);
  }
}
