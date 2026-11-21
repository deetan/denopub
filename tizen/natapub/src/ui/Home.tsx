import { useEffect, useRef, useState } from 'preact/hooks';
import { kp } from '../api/kinopub';
import { isSerial } from '../api/models';
import type { Item } from '../api/models';
import { prefs } from '../data/prefs';
import * as repo from '../data/repo';
import type { ContinueEntry, HomeData } from '../data/repo';
import { displayTitle, otherTitle } from '../format';
import { S } from './strings';
import { KEY, Press, useKeys } from '../keys';
import type { KeyEv } from '../keys';
import { log } from '../log';
import { exitApp } from '../platform';
import { publish as publishPreview } from '../preview';
import type { Nav } from './App';
import {
  ActionMenu, Badge, ContinueCard, FixedRow, Img, PosterCard, ProgressLine, rowOffsets, useToast, useVScroll,
} from './common';
import type { MenuAction } from './common';

type Card = { kind: 'c'; e: ContinueEntry } | { kind: 'p'; item: Item; isNew?: boolean; wish?: boolean };

interface Row { key: string; label: string; continue: boolean; cards: Card[]; badge?: string | null }

const CW = 160; // continue card width (dp)
const PW = 150; // poster width
const GAP = 16; // K.cardGap

function cardId(c: Card): number {
  return c.kind === 'c' ? c.e.itemId : c.item.id;
}

function rowsOf(d: HomeData): Row[] {
  const rows: Row[] = [];
  const cont = (key: string, label: string, list: ContinueEntry[]) => {
    if (list.length) rows.push({ key, label, continue: true, cards: list.map((e) => ({ kind: 'c', e } as Card)) });
  };
  cont('cs', S.rowSeries, repo.continueSerials(d));
  cont('cm', S.rowMovies, repo.continueMovies(d));
  cont('ck', S.rowKids, repo.continueKids(d));
  const newIn = d.wishlist.filter((w) => w.isNew).length;
  if (d.wishlist.length) {
    rows.push({
      key: 'w', label: S.rowWishlist, continue: false,
      badge: newIn > 0 ? S.newCount(newIn) : null,
      cards: d.wishlist.map((w) => ({ kind: 'p', item: w.item, isNew: w.isNew, wish: true } as Card)),
    });
  }
  const posters = (key: string, label: string, items: Item[]) => {
    if (items.length) rows.push({ key, label, continue: false, cards: items.map((item) => ({ kind: 'p', item } as Card)) });
  };
  posters('fm', S.rowNewMovies, d.freshMovies);
  posters('fs', S.rowNewSeries, d.freshSerials);
  posters('hot', S.rowPopular, d.hot);
  return rows;
}

function continueMeta(e: ContinueEntry): string {
  const left = Math.max(0, e.durationSec - e.target.startSec);
  if (e.target.startSec > 0) return S.timeLeft(left);
  return e.isSerial ? S.nextEpisode : S.fromStart;
}

/** Big description of the focused "continue" card (ContinueHero): 92 dp, two columns. */
function Hero({ e }: { e: ContinueEntry }) {
  const ru = displayTitle(e.title);
  const orig = otherTitle(e.title);
  const ep = repo.episodeLabel(e);
  return (
    <div class="hero">
      <Img src={e.poster} />
      <div class="shade" />
      <div class="content">
        <div style={{ width: '420px', flex: 'none', marginRight: '32px' }}>
          <div class="t-headline ell">{ru}</div>
          {orig ? <div class="t-body muted ell">{orig}</div> : null}
        </div>
        <div style={{ minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center' }}>
            <span class="t-body ell">{[ep, continueMeta(e)].filter(Boolean).join('  ·  ')}</span>
            {e.newEpisodes > 0 ? (
              <span style={{ marginLeft: '10px' }}><Badge text={S.newEpisodes(e.newEpisodes)} /></span>
            ) : null}
          </div>
          {e.isSerial && e.remainingEpisodes > 1 ? (
            <div class="t-label muted" style={{ marginTop: '4px' }}>
              {S.episodesAhead(e.remainingEpisodes)}
            </div>
          ) : null}
          <div style={{ marginTop: '4px' }}><ProgressLine progress={repo.progressOf(e)} width={260} height={3} /></div>
        </div>
      </div>
    </div>
  );
}

/**
 * Home (HomeScreen.kt): Continue (hero + Сериалы / Фильмы / Детям), Вишлист, Новые фильмы,
 * Новые сериалы, Популярное. Cached Home shows at once; fresh data replaces it.
 */
export function Home({ nav, active }: { nav: Nav; active: boolean }) {
  const [data, setData] = useState<HomeData | null>(() => repo.cachedHome());
  const [error, setError] = useState<string | null>(null);
  const [focus, setFocus] = useState<{ row: string; id: number } | null>(null);
  const [hero, setHero] = useState<ContinueEntry | null>(null);
  // Top-right buttons (Android: «Поиск», then «Картины» to its right): which one has focus, if any.
  const [top, setTop] = useState<'search' | 'art' | null>(null);
  const onSearch = top !== null;
  const [menu, setMenu] = useState<{ title: string; actions: MenuAction[] } | null>(null);
  const [toast, showToast] = useToast();
  const [exitArmed, setExitArmed] = useState(false);
  const userMoved = useRef(false);
  const press = useRef(new Press()).current;
  const track = useRef<HTMLDivElement>(null);
  const exitTimer = useRef(0);
  const dwell = useRef<{ id: number; t: number } | null>(null);
  const loading = useRef(false);

  const rows = data ? rowsOf(data) : [];

  async function classifyNewTitles(d: HomeData) {
    const unknown = repo.unclassified(d.continueWatching);
    if (!unknown.length) return;
    const kids = await repo.classifyKids(unknown).catch(() => [] as number[]);
    if (!kids.length) return;
    const mark = (list: ContinueEntry[]) => list.map((e) => (kids.indexOf(e.itemId) >= 0 ? Object.assign({}, e, { isKids: true }) : e));
    setData((cur) => (cur ? Object.assign({}, cur, { continueWatching: mark(cur.continueWatching) }) : cur));
    publishPreview(Object.assign({}, d, { continueWatching: mark(d.continueWatching) }));
  }

  async function refresh() {
    if (loading.current) return;
    loading.current = true;
    const t0 = Date.now();
    try {
      const d = await repo.home();
      setData(d);
      setError(null);
      log('home: loaded in', Date.now() - t0, 'ms;', d.continueWatching.length, 'continue,', d.wishlist.length, 'wishlist');
      publishPreview(d);
      classifyNewTitles(d);
    } catch (e) {
      setError((e as Error).message);
      log('home: failed', (e as Error).message);
    } finally {
      loading.current = false;
    }
  }

  // Every visit (first mount and each return from a title card / the player) reloads Home.
  useEffect(() => {
    if (active) refresh();
  }, [active]);
  // Back in the foreground (TV switched on, app resumed): reload.
  useEffect(() => {
    const h = () => {
      if (!document.hidden && active) {
        log('home: foreground, reload');
        refresh();
      }
    };
    document.addEventListener('visibilitychange', h);
    return () => document.removeEventListener('visibilitychange', h);
  }, [active]);

  // Focus: the card you left (kept by item id), else the first card of the first row.
  let fRow = focus ? rows.filter((r) => r.key === focus.row)[0] : undefined;
  let fCol = fRow ? fRow.cards.map(cardId).indexOf(focus ? focus.id : -1) : -1;
  if (fRow && fCol < 0) fCol = Math.min(fRow.cards.length - 1, colMemory[fRow.key] || 0);
  if (!fRow && rows.length) {
    fRow = rows[0];
    fCol = 0;
  }
  const fCard = fRow && fCol >= 0 ? fRow.cards[fCol] : null;
  if (fRow) colMemory[fRow.key] = fCol;

  // Fresh data landed and the owner hasn't moved yet: back to the first card.
  useEffect(() => {
    if (!userMoved.current && rows.length && (!focus || focus.row !== rows[0].key || focus.id !== cardId(rows[0].cards[0]))) {
      rowOffsets['home:' + rows[0].key] = 0;
      setFocus({ row: rows[0].key, id: cardId(rows[0].cards[0]) });
    }
  }, [data]);

  // The hero describes the last focused continue card.
  const heroEntry = fCard && fCard.kind === 'c' ? fCard.e : hero || (data && data.continueWatching[0]) || null;
  useEffect(() => {
    if (fCard && fCard.kind === 'c' && fCard.e !== hero) setHero(fCard.e);
    // Wishlist "new" = focused for 1.5 s on the TV.
    const id = fCard && fCard.kind === 'p' && fCard.wish && fCard.isNew ? fCard.item.id : null;
    if (!dwell.current || dwell.current.id !== id) {
      if (dwell.current) clearTimeout(dwell.current.t);
      dwell.current = id == null ? null : { id, t: window.setTimeout(() => prefs.markWishlistSeen([id]), 1500) };
    }
  });

  useVScroll(track, [fRow ? fRow.key : '', data], 16, () => {
    if (!fRow || fRow.continue) return 0;
    const el = track.current ? (track.current.querySelector('[data-row="' + fRow.key + '"]') as HTMLElement | null) : null;
    return el ? el.offsetTop - 20 : null;
  });

  function move(row: Row, col: number) {
    userMoved.current = true;
    setFocus({ row: row.key, id: cardId(row.cards[col]) });
  }

  /** ↑ ↓: the card in the next row nearest to the focused card's centre on screen (2D focus search). */
  function moveRow(dir: number) {
    if (!fRow) return;
    const i = rows.indexOf(fRow) + dir;
    if (i < 0 || i >= rows.length) return;
    const next = rows[i];
    const w = (r: Row) => (r.continue ? CW : PW);
    const x = 48 - (rowOffsets['home:' + fRow.key] || 0) + fCol * (w(fRow) + GAP) + w(fRow) / 2;
    const off = rowOffsets['home:' + next.key] || 0;
    const col = Math.round((x - 48 + off - w(next) / 2) / (w(next) + GAP));
    move(next, Math.max(0, Math.min(next.cards.length - 1, col)));
  }

  function act(done: string, block: () => Promise<unknown>) {
    block().then(() => showToast(done), (e) => showToast(S.failed((e as Error).message))).then(() => refresh());
  }
  const setWishlisted = (id: number, on: boolean) =>
    act(on ? S.addedToWishlist : S.removedFromWishlist, () => repo.setWishlisted(id, on));
  const setWatching = (id: number) => act(S.addedToContinue, async () => repo.setWatching(await kp.item(id), true));
  const setItemWatched = (id: number) => act(S.markedWatched, async () => repo.setItemWatched(await kp.item(id), true));

  function itemMenu(item: Item, inWishlist: boolean) {
    const id = item.id;
    setMenu({
      title: displayTitle(item.title),
      actions: [
        { label: S.openCard, run: () => nav.open(id) },
        inWishlist
          ? { label: S.removeFromWishlist, run: () => setWishlisted(id, false) }
          : { label: S.addToWishlist, run: () => setWishlisted(id, true) },
        { label: S.startedWatching, hint: S.startedWatchingHint, run: () => setWatching(id) },
        { label: S.markWatched, run: () => setItemWatched(id) },
      ],
    });
  }

  function continueMenu(e: ContinueEntry) {
    const ep = repo.episodeLabel(e);
    const actions: MenuAction[] = [{ label: S.openCard, run: () => nav.open(e.itemId) }];
    if (ep) {
      actions.push({
        label: S.markEpisodeWatched(ep), hint: S.markEpisodeWatchedHint,
        run: () => act(S.markedWatched, () => repo.setEpisodeWatched(e.itemId, e.target.season, e.target.episode, true)),
      });
    }
    actions.push(
      { label: e.isSerial ? S.markSeriesWatched : S.markWatched, run: () => setItemWatched(e.itemId) },
      { label: S.removeFromContinue, hint: S.removeFromContinueHint, run: () => act(S.removedFromContinue, () => repo.removeFromContinue(e.itemId)) },
      { label: S.addToWishlist, run: () => setWishlisted(e.itemId, true) },
    );
    setMenu({ title: displayTitle(e.title), actions });
  }

  useKeys((e: KeyEv) => {
    // Search and Screensaver, top-right (Android: always visible; ↑ from the first row reaches
    // Search, → from it the paintings button). The paintings return here with focus on the button.
    if (top) {
      if (e.code === KEY.OK) {
        if (!e.down) {
          if (top === 'search') nav.search();
          else nav.paintings();
        }
        return true;
      }
      if (!e.down) return true;
      if (e.back || e.code === KEY.DOWN) setTop(null);
      else if (e.code === KEY.RIGHT) setTop('art');
      else if (e.code === KEY.LEFT) setTop('search');
      return true;
    }
    if (!fRow || !fCard) {
      if (e.down && e.back) exitApp();
      return true;
    }
    const handled = press.handle(e, fCard, (c) => {
      if (c.kind === 'c') nav.play(c.e.target);
      else nav.open(c.item.id);
    }, (c) => {
      if (c.kind === 'c') continueMenu(c.e);
      else itemMenu(c.item, !!c.wish);
    });
    if (handled || !e.down) return true;
    if (e.back) {
      // First Back: to the first card. There, a second Back within 2.5 s exits.
      const first = rows[0];
      const atFirst = fRow === first && fCol === 0;
      if (!atFirst) {
        userMoved.current = true;
        rowOffsets['home:' + first.key] = 0;
        setFocus({ row: first.key, id: cardId(first.cards[0]) });
        setExitArmed(false);
      } else if (exitArmed) {
        exitApp();
      } else {
        setExitArmed(true);
        clearTimeout(exitTimer.current);
        exitTimer.current = window.setTimeout(() => setExitArmed(false), 2500);
      }
      return true;
    }
    if (e.code === KEY.LEFT && fCol > 0) move(fRow, fCol - 1);
    else if (e.code === KEY.RIGHT && fCol < fRow.cards.length - 1) move(fRow, fCol + 1);
    else if (e.code === KEY.UP && rows.indexOf(fRow) === 0) { userMoved.current = true; setTop('search'); }
    else if (e.code === KEY.UP) moveRow(-1);
    else if (e.code === KEY.DOWN) moveRow(1);
    return true;
  }, active && !menu);

  if (!data || !rows.length) {
    return (
      <div class="screen">
        <div class="center-msg">
          <div class="t-body" style={{ color: error ? 'var(--badge)' : 'var(--muted)' }}>
            {error ? S.loadFailed(error) : data ? S.empty : S.loading}
          </div>
        </div>
      </div>
    );
  }

  const contRows = rows.filter((r) => r.continue);
  const otherRows = rows.filter((r) => !r.continue);
  const renderCard = (row: Row) => (c: Card, _i: number, focused: boolean) => {
    if (c.kind === 'c') {
      const e = c.e;
      return (
        <ContinueCard
          title={displayTitle(e.title)} image={e.poster} episodeLabel={repo.episodeLabel(e)} meta={continueMeta(e)}
          progress={repo.progressOf(e)} newBadge={e.newEpisodes > 0 ? '+' + e.newEpisodes : null} focused={focused} width={CW}
        />
      );
    }
    const it = c.item;
    const subtitle = row.key === 'w'
      ? (it.year ? String(it.year) : null)
      : [it.year ? String(it.year) : null, it.imdb_rating ? 'IMDb ' + it.imdb_rating.toFixed(1) : null].filter(Boolean).join(' · ');
    return (
      <PosterCard
        title={displayTitle(it.title)} image={it.posters ? it.posters.medium : null} subtitle={subtitle}
        badge={c.isNew ? S.newBadge : null} serial={isSerial(it)} focused={focused} width={PW}
      />
    );
  };

  return (
    <div class="screen">
      <div class="home-top">
        <div class={'btn outline' + (top === 'search' ? ' f' : '')}>{S.search}</div>
        <div class={'btn outline home-art' + (top === 'art' ? ' f' : '')}>{S.screensaver}</div>
      </div>
      <div ref={track} class="vtrack" style={{ paddingBottom: '27px' }}>
        {contRows.length ? (
          <div data-row="continue" style={{ marginBottom: '28px' }}>
            {heroEntry ? <Hero e={heroEntry} /> : null}
            {contRows.map((r) => (
              <div key={r.key} data-row={r.key} class={fRow === r ? 'f' : ''} style={{ marginTop: '6px' }}>
                <div class="t-label muted" style={{ padding: '0 48px', marginBottom: '6px' }}>{r.label}</div>
                <FixedRow id={'home:' + r.key} items={r.cards} width={CW} gap={GAP} focus={!onSearch && fRow === r ? fCol : null} render={renderCard(r)} />
              </div>
            ))}
          </div>
        ) : null}
        {otherRows.map((r) => (
          <div key={r.key} data-row={r.key} style={{ marginBottom: '28px' }}>
            <div class="section-title">
              <span class="t-headline">{r.label}</span>
              {r.badge ? <Badge text={r.badge} /> : null}
            </div>
            <FixedRow id={'home:' + r.key} items={r.cards} width={PW} gap={GAP} focus={!onSearch && fRow === r ? fCol : null} render={renderCard(r)} />
          </div>
        ))}
      </div>
      {exitArmed ? <div class="toast big">{S.pressBackAgain}</div> : null}
      {toast ? <div class="toast">{toast}</div> : null}
      {menu ? <ActionMenu title={menu.title} actions={menu.actions} onClose={() => setMenu(null)} /> : null}
    </div>
  );
}

const colMemory: Record<string, number> = {};
