import { useEffect, useRef, useState } from 'preact/hooks';
import { kp } from '../api/kinopub';
import { isSerial } from '../api/models';
import type { Item } from '../api/models';
import { prefs } from '../data/prefs';
import * as repo from '../data/repo';
import type { ContinueEntry, HomeData } from '../data/repo';
import { formatMinutes, plural, splitTitle } from '../format';
import { KEY, Press, useKeys } from '../keys';
import type { KeyEv } from '../keys';
import { log } from '../log';
import { exitApp } from '../platform';
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
  cont('cs', 'Сериалы', repo.continueSerials(d));
  cont('cm', 'Фильмы', repo.continueMovies(d));
  cont('ck', 'Детям', repo.continueKids(d));
  const newIn = d.wishlist.filter((w) => w.isNew).length;
  if (d.wishlist.length) {
    rows.push({
      key: 'w', label: 'Вишлист', continue: false,
      badge: newIn > 0 ? newIn + ' ' + plural(newIn, 'новое', 'новых', 'новых') : null,
      cards: d.wishlist.map((w) => ({ kind: 'p', item: w.item, isNew: w.isNew, wish: true } as Card)),
    });
  }
  const posters = (key: string, label: string, items: Item[]) => {
    if (items.length) rows.push({ key, label, continue: false, cards: items.map((item) => ({ kind: 'p', item } as Card)) });
  };
  posters('fm', 'Новые фильмы', d.freshMovies);
  posters('fs', 'Новые сериалы', d.freshSerials);
  posters('hot', 'Популярное', d.hot);
  return rows;
}

function continueMeta(e: ContinueEntry): string {
  const left = Math.max(0, e.durationSec - e.target.startSec);
  if (e.target.startSec > 0) return 'ещё ' + formatMinutes(left);
  return e.isSerial ? 'след. серия' : 'сначала';
}

/** Big description of the focused "continue" card (ContinueHero): 92 dp, two columns. */
function Hero({ e }: { e: ContinueEntry }) {
  const [ru, orig] = splitTitle(e.title);
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
              <span style={{ marginLeft: '10px' }}><Badge text={'+' + e.newEpisodes + ' ' + plural(e.newEpisodes, 'новая', 'новые', 'новых')} /></span>
            ) : null}
          </div>
          {e.isSerial && e.remainingEpisodes > 1 ? (
            <div class="t-label muted" style={{ marginTop: '4px' }}>
              ещё {e.remainingEpisodes} {plural(e.remainingEpisodes, 'серия', 'серии', 'серий')} впереди
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
    setData((cur) => (cur ? Object.assign({}, cur, {
      continueWatching: cur.continueWatching.map((e) => (kids.indexOf(e.itemId) >= 0 ? Object.assign({}, e, { isKids: true }) : e)),
    }) : cur));
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
    block().then(() => showToast(done), (e) => showToast('Не получилось: ' + (e as Error).message)).then(() => refresh());
  }
  const setWishlisted = (id: number, on: boolean) =>
    act(on ? 'Добавлено в вишлист' : 'Убрано из вишлиста', () => repo.setWishlisted(id, on));
  const setWatching = (id: number) => act('Добавлено в «Продолжить»', async () => repo.setWatching(await kp.item(id), true));
  const setItemWatched = (id: number) => act('Отмечено просмотренным', async () => repo.setItemWatched(await kp.item(id), true));

  function itemMenu(item: Item, inWishlist: boolean) {
    const id = item.id;
    setMenu({
      title: splitTitle(item.title)[0],
      actions: [
        { label: 'Открыть карточку', run: () => nav.open(id) },
        inWishlist
          ? { label: 'Убрать из вишлиста', run: () => setWishlisted(id, false) }
          : { label: 'Добавить в вишлист', run: () => setWishlisted(id, true) },
        { label: 'Начал смотреть', hint: 'добавить в «Продолжить»', run: () => setWatching(id) },
        { label: 'Отметить просмотренным', run: () => setItemWatched(id) },
      ],
    });
  }

  function continueMenu(e: ContinueEntry) {
    const ep = repo.episodeLabel(e);
    const actions: MenuAction[] = [{ label: 'Открыть карточку', run: () => nav.open(e.itemId) }];
    if (ep) {
      actions.push({
        label: 'Отметить ' + ep + ' просмотренной', hint: 'продолжить со следующей',
        run: () => act('Отмечено просмотренным', () => repo.setEpisodeWatched(e.itemId, e.target.season, e.target.episode, true)),
      });
    }
    actions.push(
      { label: e.isSerial ? 'Отметить весь сериал просмотренным' : 'Отметить просмотренным', run: () => setItemWatched(e.itemId) },
      { label: 'Убрать из «Продолжить»', hint: 'очистить историю на kino.pub', run: () => act('Убрано из «Продолжить»', () => repo.removeFromContinue(e.itemId)) },
      { label: 'Добавить в вишлист', run: () => setWishlisted(e.itemId, true) },
    );
    setMenu({ title: splitTitle(e.title)[0], actions });
  }

  useKeys((e: KeyEv) => {
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
    else if (e.code === KEY.UP) moveRow(-1);
    else if (e.code === KEY.DOWN) moveRow(1);
    return true;
  }, active && !menu);

  if (!data || !rows.length) {
    return (
      <div class="screen">
        <div class="center-msg">
          <div class="t-body" style={{ color: error ? 'var(--badge)' : 'var(--muted)' }}>
            {error ? 'Не загрузилось: ' + error : data ? 'Пусто' : 'Загрузка…'}
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
          title={splitTitle(e.title)[0]} image={e.poster} episodeLabel={repo.episodeLabel(e)} meta={continueMeta(e)}
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
        title={splitTitle(it.title)[0]} image={it.posters ? it.posters.medium : null} subtitle={subtitle}
        badge={c.isNew ? 'НОВОЕ' : null} serial={isSerial(it)} focused={focused} width={PW}
      />
    );
  };

  return (
    <div class="screen">
      <div ref={track} class="vtrack" style={{ paddingBottom: '27px' }}>
        {contRows.length ? (
          <div data-row="continue" style={{ marginBottom: '28px' }}>
            {heroEntry ? <Hero e={heroEntry} /> : null}
            {contRows.map((r) => (
              <div key={r.key} data-row={r.key} class={fRow === r ? 'f' : ''} style={{ marginTop: '6px' }}>
                <div class="t-label muted" style={{ padding: '0 48px', marginBottom: '6px' }}>{r.label}</div>
                <FixedRow id={'home:' + r.key} items={r.cards} width={CW} gap={GAP} focus={fRow === r ? fCol : null} render={renderCard(r)} />
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
            <FixedRow id={'home:' + r.key} items={r.cards} width={PW} gap={GAP} focus={fRow === r ? fCol : null} render={renderCard(r)} />
          </div>
        ))}
      </div>
      {exitArmed ? <div class="toast big">Нажмите «Назад» ещё раз, чтобы выйти</div> : null}
      {toast ? <div class="toast">{toast}</div> : null}
      {menu ? <ActionMenu title={menu.title} actions={menu.actions} onClose={() => setMenu(null)} /> : null}
    </div>
  );
}

const colMemory: Record<string, number> = {};
