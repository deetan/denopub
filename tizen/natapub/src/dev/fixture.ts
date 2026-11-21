// Dev-only fake kino.pub (`npm run dev`, then open /?fixture). Answers every API path from memory
// and keeps writes in memory, so Home, menus, the title card and the player can be clicked
// through on the desktop without the owner's account. Never sends a request to kino.watch.
// Not part of the TV build (imported only under import.meta.env.DEV).
import { setTransport } from '../api/http';
import type { Req, Resp } from '../api/http';
import type { Item, Video } from '../api/models';
import { log } from '../log';

// Public test stream with several audio renditions and subtitles (Apple's bipbop sample).
const STREAM = 'https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8';

function poster(text: string, hue: number, w: number, h: number): string {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '">' +
    '<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="hsl(' + hue + ',55%,45%)"/>' +
    '<stop offset="1" stop-color="hsl(' + ((hue + 60) % 360) + ',55%,20%)"/></linearGradient></defs>' +
    '<rect width="100%" height="100%" fill="url(#g)"/><text x="50%" y="55%" font-family="sans-serif" font-size="' + Math.round(h / 9) +
    '" fill="white" text-anchor="middle">' + text + '</text></svg>';
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
}

function files() {
  return [1080, 720, 480].map((h) => ({ quality: h + 'p', codec: 'h264', w: Math.round((h * 16) / 9), h, url: { hls4: STREAM } }));
}
function audios() {
  return [
    { index: 1, lang: 'rus', codec: 'aac', channels: 2, type: { title: 'Многоголосый' }, author: { title: 'LostFilm' } },
    { index: 2, lang: 'eng', codec: 'aac', channels: 6, type: { title: 'Оригинал' }, author: null },
  ];
}
function ep(id: number, n: number, title: string, status: number, time: number): Video {
  return { id, number: n, title, duration: 1800, thumbnail: poster('E' + n, n * 40, 320, 180), watching: { status, time }, files: files(), audios: audios(), subtitles: [] } as Video;
}

const db: Record<number, Item> = {};
function serial(id: number, title: string, hue: number, genres: string[], seasons: number, progress: number, inWatchlist: boolean) {
  let k = 0;
  db[id] = {
    id, title, type: 'serial', year: 2020 + (id % 5), imdb_rating: 7 + (id % 3) / 2, kinopoisk_rating: 7.5, quality: 1080,
    plot: 'Тестовое описание сериала «' + title.split(' / ')[0] + '». Достаточно длинное, чтобы проверить обрезку текста на три строки и полное описание по OK. '.repeat(3),
    genres: genres.map((g) => ({ title: g })), in_watchlist: inWatchlist,
    posters: { medium: poster(title.split(' / ')[0], hue, 300, 450), big: poster(title.split(' / ')[0], hue, 300, 450), wide: poster(title.split(' / ')[0], hue, 640, 360) },
    seasons: Array.apply(null, Array(seasons)).map((_x: unknown, s: number) => ({
      number: s + 1,
      episodes: Array.apply(null, Array(8)).map((_y: unknown, e: number) => {
        k++;
        return ep(id * 1000 + k, e + 1, 'Серия номер ' + (e + 1), k < progress ? 1 : -1, k === progress ? 600 : 0);
      }),
    })),
  };
}
function movie(id: number, title: string, hue: number, genres: string[], time: number) {
  db[id] = {
    id, title, type: 'movie', year: 2015 + (id % 9), imdb_rating: 6.5 + (id % 4) / 2, quality: 1080,
    plot: 'Тестовое описание фильма «' + title.split(' / ')[0] + '».', genres: genres.map((g) => ({ title: g })),
    posters: { medium: poster(title.split(' / ')[0], hue, 300, 450), big: poster(title.split(' / ')[0], hue, 300, 450), wide: poster(title.split(' / ')[0], hue, 640, 360) },
    videos: [Object.assign(ep(id * 1000 + 1, 1, '', time > 0 ? 0 : -1, time), { duration: 6000 })],
  };
}
serial(1, 'Тед Лассо / Ted Lasso', 200, ['Комедия', 'Драма'], 3, 12, true);
serial(2, 'Основание / Foundation', 260, ['Фантастика'], 2, 9, false);
serial(3, 'Свинка Пеппа / Peppa Pig', 330, ['Мультфильм', 'Детский'], 2, 4, true);
movie(4, 'Дюна / Dune', 30, ['Фантастика'], 3200);
movie(5, 'Жизнь Пи / Life of Pi', 120, ['Приключения'], 900);
for (let i = 10; i < 40; i++) movie(i, 'Фильм ' + i + ' / Movie ' + i, (i * 37) % 360, ['Драма'], 0);
for (let i = 40; i < 55; i++) serial(i, 'Сериал ' + i + ' / Show ' + i, (i * 53) % 360, ['Драма'], 1, 0, false);

const wishlist = [12, 14, 41, 16, 18, 43, 20];
const writes: string[] = [];

function info(id: number) {
  const it = db[id];
  const wv = (v: Video) => ({ id: v.id, number: v.number, title: v.title, time: v.watching ? v.watching.time : 0, duration: v.duration, status: v.watching ? v.watching.status : -1, updated: 1763600000 + id });
  return {
    item: {
      id,
      videos: (it.videos || []).map(wv),
      seasons: (it.seasons || []).map((s) => ({ number: s.number, episodes: s.episodes.map(wv) })),
    },
  };
}

function find(id: number, season: number | null, video: number | null): Video[] {
  const it = db[id];
  if (!it) return [];
  if (season != null) {
    const s = (it.seasons || []).filter((x) => x.number === season)[0];
    return s ? s.episodes.filter((e) => video == null || e.number === video) : [];
  }
  return (it.videos || []).filter((v) => video == null || v.number === video);
}

function route(method: string, path: string, q: Record<string, string>, body: Record<string, string>): unknown {
  const n = (k: string) => (q[k] != null ? Number(q[k]) : body[k] != null ? Number(body[k]) : null);
  if (path === 'oauth2/device') {
    if (body.grant_type === 'device_code') return { code: 'dev', user_code: 'FIXTURE', verification_uri: 'https://kino.pub/device', interval: 5, expires_in: 300 };
    return { access_token: 'fixture', refresh_token: 'fixture', expires_in: 3600 };
  }
  if (path === 'oauth2/token') return { access_token: 'fixture', refresh_token: 'fixture', expires_in: 3600 };
  if (path === 'v1/history') return { history: q.page === '1' ? [1, 4, 2].map((id, i) => ({ time: 0, counter: 1, first_seen: 0, last_seen: 1763600000 - i * 3600, item: db[id] })) : [] };
  if (path === 'v1/watching/serials') {
    const ids = q.subscribed === '1' ? [1, 3] : [2];
    return { items: ids.map((id) => ({ id, title: db[id].title, type: 'serial', posters: db[id].posters, new: id === 1 ? 2 : 0 })) };
  }
  if (path === 'v1/watching/movies') return { items: [4, 5].map((id) => ({ id, title: db[id].title, type: 'movie', posters: db[id].posters })) };
  if (path === 'v1/watching') return info(Number(q.id));
  let m = /^v1\/items\/(\d+)$/.exec(path);
  if (m) return { item: db[Number(m[1])] };
  if (path === 'v1/items/fresh') return { items: Object.keys(db).map(Number).filter((id) => (q.type === 'serial') === (db[id].type === 'serial')).slice(0, 20).map((id) => db[id]) };
  if (path === 'v1/items/hot') return { items: [15, 17, 19, 21, 23, 25].map((id) => db[id]) };
  if (path === 'v1/items/similar') return { items: [11, 13, 42, 22, 24, 44, 26].map((id) => db[id]) };
  if (path === 'v1/bookmarks') return { items: [{ id: 77, title: 'Wishlist', count: wishlist.length }] };
  if (path === 'v1/bookmarks/77') return { items: wishlist.map((id) => db[id]) };
  if (path === 'v1/bookmarks/get-item-folders') return { folders: wishlist.indexOf(Number(q.item)) >= 0 ? [{ id: 77, title: 'Wishlist' }] : [] };
  if (path === 'v1/bookmarks/add') { const id = n('item') as number; if (wishlist.indexOf(id) < 0) wishlist.unshift(id); return {}; }
  if (path === 'v1/bookmarks/remove-item') { const i = wishlist.indexOf(n('item') as number); if (i >= 0) wishlist.splice(i, 1); return { status: 200 }; }
  if (path === 'v1/watching/marktime') {
    find(n('id') as number, n('season'), n('video')).forEach((v) => { v.watching = { status: v.watching && v.watching.status === 1 ? 1 : 0, time: n('time') as number }; });
    return {};
  }
  if (path === 'v1/watching/toggle') {
    find(n('id') as number, n('season'), n('video')).forEach((v) => { v.watching = { status: n('status') === 1 ? 1 : -1, time: 0 }; });
    return {};
  }
  if (path === 'v1/watching/togglewatchlist') { const it = db[n('id') as number]; it.in_watchlist = !it.in_watchlist; return { watching: it.in_watchlist }; }
  if (path === 'v1/history/clear-for-item') return {};
  m = /^v1\/device/.exec(path);
  if (m || path === 'v1/references/streaming-type') return { device: { id: 1 }, items: [{ id: 4, code: 'hls4' }] };
  log('fixture: unknown', method, path);
  return {};
}

function parseQ(s: string): Record<string, string> {
  const out: Record<string, string> = {};
  s.split('&').filter(Boolean).forEach((kv) => {
    const i = kv.indexOf('=');
    out[decodeURIComponent(i < 0 ? kv : kv.substr(0, i))] = i < 0 ? '' : decodeURIComponent(kv.substr(i + 1));
  });
  return out;
}

export function install(): void {
  localStorage.setItem('natapub.tok', JSON.stringify({ access: 'fixture', refresh: 'fixture', exp: Date.now() + 3600e3 }));
  setTransport((r: Req): Promise<Resp> => {
    const i = r.url.indexOf('/api/');
    const rest = r.url.substr(i + 5);
    const [path, query] = rest.split('?');
    const body = parseQ(r.body || '');
    if (path.indexOf('watching/') === 0 || path.indexOf('v1/watching/') === 0 || path.indexOf('bookmarks/') >= 0 || path.indexOf('clear') >= 0) {
      if (!/serials|movies|get-item-folders/.test(path)) writes.push(r.method + ' ' + path + ' ' + (query || '') + ' ' + (r.body || ''));
    }
    const res = route(r.method, path, parseQ(query || ''), body);
    (window as any).fixtureWrites = writes;
    return new Promise((ok) => setTimeout(() => ok({ status: 200, text: JSON.stringify(res) }), 60));
  });
  log('fixture mode: in-memory kino.pub');
}
