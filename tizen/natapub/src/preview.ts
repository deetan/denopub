// Natapub's row on the TV's home bar (Samsung Smart Hub Preview), shown above the Natapub tile
// when it is focused, like YouTube's: Paintings first (a fixed picture, PAINTINGS_IMAGE), then the
// Continue cards Series → Movies → Kids. Proven 2026-11-21 with the generic Tizen certificate.
//  - Home calls publish() after every load. The tiles go to the web service
//    (public/service/preview.js) as app-control data; it keeps them and calls
//    webapis.preview.setPreviewData (only a service may). Samsung silently drops calls less than
//    10 minutes apart, so sends are throttled here and unchanged tiles are not sent again.
//  - OK on a tile starts or resumes Natapub with the tile's action_data in the PAYLOAD
//    app-control key ({"values": "<action_data>"}); `appcontrol` fires when it is already running
//    (config.xml: reload="disable"). onDeepLink() hands them to App.
import type { ContinueEntry, HomeData, ResumeTarget } from './data/repo';
import * as repo from './data/repo';
import { displayTitle } from './format';
import { log } from './log';
import { isTizen } from './platform';
import { S } from './ui/strings';

declare const tizen: any;

const SERVICE_ID = 'natapubTV1.preview';
const K_SENT = 'natapub.preview.sentAt';
const MIN_GAP_MS = 10 * 60000 + 5000;
const MAX_CONTINUE = 15;

export type DeepLink = { k: 'art' } | { k: 'play'; target: ResumeTarget };

// ---------- tiles ----------

// The Paintings tile's picture: Levitan's "Above Eternal Peace", cropped 2:3, kept in this repo and
// served by GitHub (owner's pick, 2026-11-21). The home bar's image loader sends no User-Agent, and
// Wikimedia answers that with 403 — the tile was then dropped without a word. GitHub raw answers 200.
const PAINTINGS_IMAGE =
  'https://raw.githubusercontent.com/deetan/denopub/main/tizen/natapub/public/preview-paintings.jpg';

/** "S1·E5 · next episode", "1 h 10 min left" — the Continue card's caption. */
function continueText(e: ContinueEntry): string {
  const left = Math.max(0, e.durationSec - e.target.startSec);
  const meta = e.target.startSec > 0 ? S.timeLeft(left) : e.isSerial ? S.nextEpisode : S.fromStart;
  return [repo.episodeLabel(e), meta].filter(Boolean).join(' · ');
}

function continueTile(e: ContinueEntry) {
  const t = e.target;
  return {
    title: displayTitle(e.title),
    subtitle: continueText(e),
    image_ratio: e.posterWide ? '16by9' : '2by3',
    image_url: e.poster,
    action_data: JSON.stringify({ play: { i: t.itemId, s: t.season, e: t.episode, t: t.startSec } }),
    is_playable: true,
  };
}

function buildTiles(d: HomeData): string {
  const tiles: object[] = [
    {
      title: S.previewPaintings,
      subtitle: S.screensaver,
      // Same shape as the posters, so the row stays even.
      image_ratio: '2by3',
      image_url: PAINTINGS_IMAGE,
      action_data: JSON.stringify({ open: 'art' }),
      is_playable: true,
    },
  ];
  repo.continueSerials(d).concat(repo.continueMovies(d), repo.continueKids(d))
    .filter((e) => !!e.poster)
    .slice(0, MAX_CONTINUE)
    .forEach((e) => tiles.push(continueTile(e)));
  // One section: on this TV only one section showed. The row is circular and opens where it was last
  // left, so the Paintings tile (first) may sit at the far end — scroll ← from the first visible tile.
  return JSON.stringify({ sections: [{ title: 'Natapub', tiles }] });
}

// ---------- sending ----------

let pending: string | null = null;
let timer = 0;

// What was sent is remembered only for this run, so every app start sends once (heals a failed
// launch or a lost write); when, across runs, for the 10-minute rule.
let sentJson = '';

function lastSentAt(): number {
  try {
    return Number(localStorage.getItem(K_SENT)) || 0;
  } catch (e) {
    return 0;
  }
}

function send(json: string): void {
  pending = null;
  sentJson = json;
  try {
    localStorage.setItem(K_SENT, String(Date.now()));
  } catch (e) {
    /* quota: sent anyway */
  }
  try {
    tizen.application.launchAppControl(
      new tizen.ApplicationControl('http://tizen.org/appcontrol/operation/pick', null, null, null, [
        new tizen.ApplicationControlData('caller', ['ForegroundApp']),
        new tizen.ApplicationControlData('tiles', [json]),
      ]),
      SERVICE_ID,
      () => log('preview: sent', json.length, 'bytes to the service'),
      (e: { name?: string; message?: string }) => log('preview: service launch failed', e && e.name, e && e.message),
    );
  } catch (e) {
    log('preview: launchAppControl threw', (e as Error).message);
  }
}

/** After every Home load: the home bar row, if it changed (≥ 10 min apart; later ones wait). */
export async function publish(d: HomeData): Promise<void> {
  if (!isTizen) return;
  let json: string;
  try {
    json = buildTiles(d);
  } catch (e) {
    log('preview: tiles failed', (e as Error).message);
    return;
  }
  if (json === sentJson) return;
  const wait = lastSentAt() + MIN_GAP_MS - Date.now();
  clearTimeout(timer);
  // Sent at once either way: the service keeps the file, so even a call Samsung drops is set by
  // the TV's own next run of the service. Inside the 10 minutes it is sent once more after them.
  send(json);
  if (wait > 0) {
    pending = json;
    log('preview: sent inside 10 min of the last one; again in', Math.round(wait / 1000), 's');
    timer = window.setTimeout(() => {
      if (pending) send(pending);
    }, wait);
  }
}

// ---------- deep links ----------

let listener: ((l: DeepLink) => void) | null = null;
let early: DeepLink | null = null;

function parse(): DeepLink | null {
  let rq: any = null;
  try {
    rq = tizen.application.getCurrentApplication().getRequestedAppControl();
  } catch (e) {
    return null;
  }
  const data: { key: string; value: string[] }[] = (rq && rq.appControl && rq.appControl.data) || [];
  const p = data.filter((x) => x.key === 'PAYLOAD')[0];
  if (!p || !p.value || !p.value[0]) return null;
  try {
    const a = JSON.parse(JSON.parse(p.value[0]).values);
    log('preview: deep link', JSON.stringify(a));
    if (a.open === 'art') return { k: 'art' };
    if (a.play && typeof a.play.i === 'number') {
      return { k: 'play', target: { itemId: a.play.i, season: a.play.s == null ? null : a.play.s, episode: a.play.e, startSec: a.play.t || 0 } };
    }
  } catch (e) {
    log('preview: bad payload', p.value[0], (e as Error).message);
  }
  return null;
}

function deliver(l: DeepLink | null): void {
  if (!l) return;
  if (listener) listener(l);
  else early = l;
}

/** At boot: the deep link the app was started with, and every later one. */
export function initDeepLinks(): void {
  if (!isTizen) return;
  deliver(parse());
  window.addEventListener('appcontrol', () => deliver(parse()));
}

/** App subscribes; a link that arrived before it did is delivered at once. */
export function onDeepLink(fn: (l: DeepLink) => void): void {
  listener = fn;
  if (early) {
    const l = early;
    early = null;
    fn(l);
  }
}
