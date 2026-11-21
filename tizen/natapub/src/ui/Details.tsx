import { useEffect, useRef, useState } from 'preact/hooks';
import { kp } from '../api/kinopub';
import { isSerial } from '../api/models';
import type { Item, Video } from '../api/models';
import * as repo from '../data/repo';
import type { ResumeTarget } from '../data/repo';
import { displayTitle, otherTitle } from '../format';
import { S, formatMinutes } from './strings';
import { KEY, Press, useKeys } from '../keys';
import type { KeyEv } from '../keys';
import { log } from '../log';
import type { Nav } from './App';
import { ChipRow, FixedRow, Img, PosterCard, ProgressLine, useToast, useVScroll } from './common';

/** marktime stores time without changing status: "in progress" = time > 0, not finished. */
function resumeSec(v: Video): number {
  const w = v.watching;
  return w && w.status !== 1 ? w.time || 0 : 0;
}
const watchedV = (v: Video) => !!v.watching && v.watching.status === 1;

function allVideos(it: Item): Video[] {
  const eps: Video[] = [];
  (it.seasons || []).forEach((s) => s.episodes.forEach((e) => eps.push(e)));
  return eps.concat(it.videos || []);
}

function isFullyWatched(it: Item): boolean {
  if ((it.seasons || []).length) return (it.seasons || []).every((s) => s.episodes.every(watchedV));
  const vs = it.videos || [];
  return vs.length > 0 && vs.every(watchedV);
}

/** Same rule as Home: first unfinished episode at/after the last touched one. */
function resumeTarget(it: Item): ResumeTarget | null {
  if ((it.seasons || []).length) {
    const flat = repo.flatEpisodes(it);
    let last = -1;
    flat.forEach(([, e], i) => {
      if (watchedV(e) || resumeSec(e) > 0) last = i;
    });
    const next = flat.slice(Math.max(last, 0)).filter(([, e]) => !watchedV(e))[0];
    if (!next) return null;
    return { itemId: it.id, season: next[0], episode: next[1].number, startSec: resumeSec(next[1]) };
  }
  const v = (it.videos || []).filter((x) => resumeSec(x) > 0 || (!!x.watching && x.watching.status === 0))[0];
  return v ? { itemId: it.id, season: null, episode: v.number, startSec: resumeSec(v) } : null;
}

type FocusRow = { key: string; size: number };

/**
 * Title card: poster, facts, Russian plot; the action row (play/resume, wishlist, «Начал
 * смотреть», watched); seasons and episodes; «Похожие» (kino.pub items/similar).
 */
export function Details({ nav, active, id }: { nav: Nav; active: boolean; id: number }) {
  const [item, setItem] = useState<Item | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [wishlisted, setWishlisted] = useState<boolean | null>(null);
  const [similar, setSimilar] = useState<Item[]>([]);
  const [season, setSeason] = useState<number | null>(null);
  const [row, setRow] = useState('actions');
  const [cols, setCols] = useState<Record<string, number>>({});
  const [showPlot, setShowPlot] = useState(false);
  const [toast, showToast] = useToast();
  const [reloads, setReloads] = useState(0);
  const press = useRef(new Press()).current;
  const track = useRef<HTMLDivElement>(null);
  const wasActive = useRef(active);
  const itemRef = useRef<Item | null>(null);
  itemRef.current = item;

  // Load (and re-read after every write and on return from the player). kino.pub down → retry
  // by itself, 5 s, 10 s … 1 min, only while on screen.
  useEffect(() => {
    let alive = true;
    let wait = 5000;
    let timer = 0;
    const load = () => {
      kp.item(id).then((it) => {
        if (!alive) return;
        setItem(it);
        setError(null);
      }, (e) => {
        if (!alive) return;
        setError((e as Error).message || S.notResponding0);
        log('details', id, 'load failed:', (e as Error).message);
        if (itemRef.current) return; // a failed re-read keeps the page as it was
        timer = window.setTimeout(function again() {
          if (document.hidden) {
            timer = window.setTimeout(again, 2000);
            return;
          }
          load();
        }, wait);
        wait = Math.min(wait * 2, 60000);
      });
    };
    load();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [id, reloads]);
  useEffect(() => {
    repo.isInWishlist(id).then(setWishlisted, () => setWishlisted(null));
    kp.similar(id).then((xs) => {
      const seen: Record<number, boolean> = {};
      setSimilar(xs.filter((s) => {
        const ok = s.id !== id && (s.type || '').toLowerCase() !== '3d' && !seen[s.id];
        seen[s.id] = true;
        return ok;
      }).slice(0, 24));
    }, () => setSimilar([]));
  }, [id]);
  useEffect(() => {
    if (active && !wasActive.current) setReloads((n) => n + 1); // back from the player
    wasActive.current = active;
  }, [active]);

  const resume = item ? resumeTarget(item) : null;
  const serial = item ? (item.seasons || []).length > 0 : false;
  useEffect(() => {
    if (item && season == null) setSeason(resume && resume.season != null ? resume.season : (item.seasons || [])[0] ? (item.seasons || [])[0].number : null);
  }, [item]);

  const seasons = item ? (item.seasons || []) : [];
  const episodes = seasons.filter((s) => s.number === season)[0] ? seasons.filter((s) => s.number === season)[0].episodes : [];
  const fullyWatched = item ? isFullyWatched(item) : false;
  const watching = item
    ? (isSerial(item) && item.in_watchlist === true) || (allVideos(item).some((v) => resumeSec(v) > 0 || watchedV(v)) && !fullyWatched)
    : false;

  // Focus rows, top to bottom.
  const rows: FocusRow[] = [];
  if (item && item.plot) rows.push({ key: 'plot', size: 1 });
  rows.push({ key: 'actions', size: 4 });
  if (serial) {
    rows.push({ key: 'seasons', size: seasons.length });
    episodes.forEach((e) => rows.push({ key: 'ep' + e.number, size: 1 }));
  }
  if (similar.length) rows.push({ key: 'similar', size: similar.length });
  let ri = rows.map((r) => r.key).indexOf(row);
  if (ri < 0) ri = rows.map((r) => r.key).indexOf('actions');
  const fr = rows[ri];
  const col = Math.min(fr.size - 1, cols[fr.key] != null ? cols[fr.key] : fr.key === 'seasons' ? Math.max(0, seasons.map((s) => s.number).indexOf(season as number)) : 0);

  useVScroll(track, [fr.key, col, season, item, similar.length], 16, () => (serial ? null : fr.key === 'similar' ? null : 0));

  function act(done: string, block: () => Promise<unknown>) {
    block().then(() => showToast(done), (e) => showToast(S.failed((e as Error).message))).then(() => setReloads((n) => n + 1));
  }

  function focusRow(key: string, c?: number) {
    setRow(key);
    if (c != null) setCols((m) => Object.assign({}, m, { [key]: c }));
  }

  function verticalTo(i: number) {
    if (i < 0 || i >= rows.length) return;
    const next = rows[i];
    // Seasons → episodes: land on the resume episode of this season (else the first).
    if (fr.key === 'seasons' && next.key.indexOf('ep') === 0 && resume && resume.season === season) {
      const k = 'ep' + resume.episode;
      if (rows.some((r) => r.key === k)) {
        focusRow(k);
        return;
      }
    }
    focusRow(next.key);
  }

  function onClick() {
    if (!item) return;
    const it = item;
    if (fr.key === 'plot') setShowPlot(true);
    else if (fr.key === 'actions') {
      if (col === 0) nav.play(resume || { itemId: it.id, season: seasons[0] ? seasons[0].number : null, episode: 1, startSec: 0 });
      else if (col === 1) {
        const on = wishlisted !== true;
        act(on ? S.addedToWishlist : S.removedFromWishlist, async () => setWishlisted(await repo.setWishlisted(it.id, on)));
      } else if (col === 2) {
        act(watching ? S.removedFromContinue : S.addedToContinue, () =>
          watching ? repo.removeFromContinue(it.id) : repo.setWatching(it, true));
      } else if (col === 3) {
        act(fullyWatched ? S.unmarked : S.markedWatched, () => repo.setItemWatched(it, !fullyWatched));
      }
    } else if (fr.key === 'seasons') setSeason(seasons[col].number);
    else if (fr.key.indexOf('ep') === 0) {
      const e = episodes.filter((x) => 'ep' + x.number === fr.key)[0];
      if (e) nav.play({ itemId: it.id, season, episode: e.number, startSec: resumeSec(e) });
    } else if (fr.key === 'similar') nav.open(similar[col].id);
  }

  function onLong() {
    if (!item) return;
    const it = item;
    if (fr.key === 'seasons') {
      // Long-press a season chip: mark the whole season watched (or unwatched if it already is).
      const s = seasons[col];
      const done = s.episodes.every(watchedV);
      act(done ? S.seasonUnmarked(s.number) : S.seasonWatched(s.number), () => repo.setSeasonWatched(it.id, s.number, !done));
    } else if (fr.key.indexOf('ep') === 0 && season != null) {
      // Long-press an episode: toggle "просмотрено" right away.
      const e = episodes.filter((x) => 'ep' + x.number === fr.key)[0];
      if (!e) return;
      const w = watchedV(e);
      const s = season;
      act(w ? S.episodeUnmarked('S' + s + '·E' + e.number) : S.episodeWatched('S' + s + '·E' + e.number), () => repo.setEpisodeWatched(it.id, s, e.number, !w));
    } else onClick();
  }

  useKeys((e: KeyEv) => {
    if (!item) {
      if (e.down && e.back) nav.back();
      else if (press.handle(e, 0, () => { setError(null); setReloads((n) => n + 1); })) return true;
      return true;
    }
    const longable = fr.key === 'seasons' || fr.key.indexOf('ep') === 0;
    if (press.handle(e, 0, onClick, longable ? onLong : undefined)) return true;
    if (!e.down) return true;
    if (e.back) nav.back();
    else if (e.code === KEY.LEFT && col > 0) focusRow(fr.key, col - 1);
    else if (e.code === KEY.RIGHT && col < fr.size - 1) focusRow(fr.key, col + 1);
    else if (e.code === KEY.UP) verticalTo(ri - 1);
    else if (e.code === KEY.DOWN) verticalTo(ri + 1);
    return true;
  }, active && !showPlot);

  if (!item) {
    return (
      <div class="screen">
        <div class="center-msg">
          {error == null ? <div class="t-body muted">{S.loading}</div> : (
            <div>
              <div class="t-body clamp3" style={{ color: 'var(--badge)', maxWidth: '700px' }}>{error.substr(0, 200)}</div>
              <div class="t-label muted" style={{ marginTop: '16px' }}>{S.retrying}</div>
              <div class="btn fill f" style={{ marginTop: '16px' }}>{S.retry}</div>
            </div>
          )}
        </div>
      </div>
    );
  }

  const titleRu = displayTitle(item.title);
  const titleOrig = otherTitle(item.title);
  const facts = [
    titleOrig,
    item.year ? String(item.year) : null,
    item.imdb_rating ? 'IMDb ' + item.imdb_rating.toFixed(1) : null,
    item.kinopoisk_rating ? S.kp + ' ' + item.kinopoisk_rating.toFixed(1) : null,
    item.quality ? (item.quality >= 2160 ? '4K' : item.quality + 'p') : null,
  ].filter(Boolean).join('  ·  ');
  const r = resume;
  const playLabel = !r ? S.play
    : r.season != null && r.startSec > 0 ? S.resumeEp(r.season, r.episode, r.startSec)
      : r.season != null ? S.playEp(r.season, r.episode)
        : r.startSec > 0 ? S.resumeAt(r.startSec) : S.play;
  const isF = (key: string, c = 0) => fr.key === key && col === c;
  const btn = (c: number, label: string, fill = false) => (
    <div class={'btn ' + (fill ? 'fill' : 'outline') + (isF('actions', c) ? ' f' : '')}>{label}</div>
  );

  const header = (
    <div class="head">
      <div class="pimg"><Img src={item.posters ? item.posters.big || item.posters.medium : null} /></div>
      <div class="info">
        <div class="t-headline ell">{titleRu}</div>
        <div class="t-title muted ell">{facts}</div>
        {(item.genres || []).length ? <div class="t-label muted">{(item.genres || []).map((g) => g.title).filter(Boolean).join(', ')}</div> : null}
        {item.plot ? (
          <div class={'plot' + (isF('plot') ? ' f' : '')}>
            <div class="t-body ru clamp3">{item.plot}</div>
          </div>
        ) : null}
      </div>
    </div>
  );
  const actions = (
    <div style={{ marginTop: '14px', padding: '4px 0', flex: 'none' }}>
      <ChipRow id={'d:' + id + ':actions'} focusKey={fr.key + col} viewW={864}>
        <div class="btnrow" style={{ display: 'flex', padding: '0 6px' }}>
          {btn(0, playLabel, true)}
          {btn(1, wishlisted === true ? S.inWishlist : S.toWishlist)}
          {btn(2, watching ? S.watchingOn : S.startedWatching)}
          {btn(3, fullyWatched ? S.watchedOn : S.markWatched)}
        </div>
      </ChipRow>
    </div>
  );
  const similarRow = similar.length ? (
    <div data-similar style={{ marginTop: '16px' }} class={fr.key === 'similar' ? 'f' : ''}>
      <div class="t-title" style={{ marginBottom: '6px' }}>{S.similar}</div>
      <div style={{ padding: '8px 0' }}>
        <FixedRow id={'d:' + id + ':similar'} items={similar} width={110} gap={16} padH={8} focus={fr.key === 'similar' ? col : null}
          render={(s, _i, focused) => (
            <PosterCard title={displayTitle(s.title)} image={s.posters ? s.posters.medium : null} width={110} focused={focused} serial={isSerial(s)}
              subtitle={[s.year ? String(s.year) : null, s.imdb_rating ? 'IMDb ' + s.imdb_rating.toFixed(1) : null].filter(Boolean).join(' · ')} />
          )} />
      </div>
    </div>
  ) : null;

  return (
    <div class="screen">
      {serial ? (
        <div class="details" style={{ height: '540px' }}>
          {header}
          {actions}
          <div style={{ marginTop: '6px', padding: '8px 20px', flex: 'none', marginLeft: '-20px', marginRight: '-20px' }}>
            <ChipRow id={'d:' + id + ':seasons'} focusKey={fr.key + col + ':' + season} viewW={904} pad={20}>
              <div class="chiprow" style={{ display: 'flex', padding: '0 20px' }}>
                {seasons.map((s, i) => {
                  const left = s.episodes.filter((e) => !watchedV(e)).length;
                  const label = S.season(s.number) + (left === 0 ? ' ✓' : '');
                  return <div key={s.number} class={'btn ' + (s.number === season ? 'fill' : 'outline') + (isF('seasons', i) ? ' f' : '')}>{label}</div>;
                })}
              </div>
            </ChipRow>
          </div>
          <div class="eps">
            <div ref={track} class="vtrack" style={{ paddingBottom: '24px' }}>
              {episodes.map((e) => {
                const st = watchedV(e) ? 1 : resumeSec(e) > 0 ? 0 : -1;
                const isResume = !!resume && resume.season === season && resume.episode === e.number;
                return (
                  <div key={e.id} class={'ep' + (isResume ? ' sel' : '') + (fr.key === 'ep' + e.number ? ' f' : '')}>
                    <div class="body">
                      <div class="t-title ell" style={{ fontSize: '16px' }}>{e.number}. {e.title && e.title.trim() ? displayTitle(e.title) : S.episode(e.number)}</div>
                      <div class="sup">
                        {st === 1 ? S.watched : st === 0 ? S.stoppedAt((e.watching && e.watching.time) || 0, e.duration) : formatMinutes(e.duration)}
                      </div>
                      {st === 0 && e.duration > 0 ? (
                        <div style={{ marginTop: '4px', width: '50%' }}><ProgressLine progress={((e.watching && e.watching.time) || 0) / e.duration} height={3} /></div>
                      ) : null}
                    </div>
                    {st === 1 ? <div class="tick">✓</div> : null}
                  </div>
                );
              })}
              {similarRow}
            </div>
          </div>
        </div>
      ) : (
        <div style={{ position: 'relative', height: '540px', overflow: 'hidden' }}>
          <div ref={track} class="vtrack details" style={{ paddingBottom: '27px' }}>
            {header}
            {actions}
            {similarRow}
          </div>
        </div>
      )}
      {showPlot ? <PlotDialog title={titleRu} ru={item.plot || ''} onClose={() => setShowPlot(false)} /> : null}
      {toast ? <div class="toast">{toast}</div> : null}
    </div>
  );
}

/** Full description; ↑ ↓ scroll long texts, OK or Back closes. */
function PlotDialog({ title, ru, onClose }: { title: string; ru: string; onClose: () => void }) {
  const body = useRef<HTMLDivElement>(null);
  const off = useRef(0);
  const press = useRef(new Press()).current;
  useKeys((e: KeyEv) => {
    if (press.handle(e, 0, onClose)) return true;
    if (!e.down) return true;
    if (e.back) onClose();
    else if ((e.code === KEY.DOWN || e.code === KEY.UP) && body.current && body.current.parentElement) {
      const max = Math.max(0, body.current.offsetHeight - body.current.parentElement.clientHeight);
      off.current = Math.max(0, Math.min(max, off.current + (e.code === KEY.DOWN ? 160 : -160)));
      body.current.style.transform = 'translateY(' + -off.current + 'px)';
    }
    return true;
  });
  return (
    <div class="scrim">
      <div class="plotdlg">
        <div style={{ maxHeight: '424px', overflow: 'hidden' }}>
          <div ref={body} class="vtrack">
            <div class="t-headline">{title}</div>
            <div class="t-title" style={{ marginTop: '16px', fontWeight: 400 }}>{ru}</div>
          </div>
        </div>
      </div>
    </div>
  );
}
