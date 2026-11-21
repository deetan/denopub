import { useEffect, useRef, useState } from 'preact/hooks';
import type { Item } from '../api/models';
import { isSerial } from '../api/models';
import * as repo from '../data/repo';
import { displayTitle } from '../format';
import { KEY, useKeys } from '../keys';
import type { KeyEv } from '../keys';
import type { Nav } from './App';
import { PosterCard, useVScroll } from './common';
import { S } from './strings';

const COLS = 5; // Android: GridCells.Adaptive(150 dp) over 864 dp = 5
const PW = 150;
const GAP = 16;
const TYPES: [('movie' | 'serial' | null), string][] = [[null, S.searchAll], ['movie', S.searchMovies], ['serial', S.searchSeries]];

/** Where focus is: the text field, a type chip, or a result. */
type Zone = { z: 'field' } | { z: 'chip'; i: number } | { z: 'grid'; i: number };

/**
 * Search (ui/search/SearchScreen.kt): a plain <input>; focusing it opens the Tizen on-screen
 * keyboard. ↓ or the IME's Done leaves it for the chips / results, Cancel leaves it in place.
 * The screen stays mounted under the title card, so Back returns with the query and focus kept.
 */
export function Search({ nav, active }: { nav: Nav; active: boolean }) {
  const [query, setQuery] = useState('');
  const [type, setType] = useState<'movie' | 'serial' | null>(null);
  const [results, setResults] = useState<Item[]>([]);
  const [status, setStatus] = useState<string | null>(null);
  const [zone, setZone] = useState<Zone>({ z: 'field' });
  const [editing, setEditing] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const track = useRef<HTMLDivElement>(null);
  const seq = useRef(0);

  // Like Android: the field takes focus on open, so the keyboard is up at once.
  useEffect(() => { focusField(); }, []);

  useEffect(() => {
    const q = query.trim();
    const n = ++seq.current;
    if (q.length < 2) { setResults([]); setStatus(null); return undefined; }
    const t = window.setTimeout(() => {
      setStatus(S.searching);
      repo.search(q, type).then((items) => {
        if (n !== seq.current) return;
        setResults(items);
        setStatus(items.length ? null : S.nothingFound);
      }, (e) => {
        if (n === seq.current) setStatus(S.error((e as Error).message));
      });
    }, 400); // debounce typing
    return () => clearTimeout(t);
  }, [query, type]);

  // A result list that shrank must not strand focus past its end.
  useEffect(() => {
    if (zone.z === 'grid' && zone.i >= results.length) setZone(results.length ? { z: 'grid', i: results.length - 1 } : { z: 'field' });
  }, [results]);

  useVScroll(track, [zone.z, zone.z === 'grid' ? Math.floor(zone.i / COLS) : -1, results], 24);

  function focusField() {
    setZone({ z: 'field' });
    const el = input.current;
    if (el) { el.focus(); setEditing(true); }
  }
  function leaveField(to: Zone | null) {
    const el = input.current;
    if (el) el.blur();
    setEditing(false);
    if (to) setZone(to);
  }
  const down = (): Zone => (results.length ? { z: 'grid', i: 0 } : { z: 'chip', i: 0 });

  useKeys((e: KeyEv) => {
    if (editing) {
      if (!e.down) return true;
      if (e.code === KEY.DOWN || e.code === KEY.IME_DONE || e.code === KEY.OK) leaveField(down());
      else if (e.code === KEY.IME_CANCEL || e.back) leaveField({ z: 'field' });
      return true;
    }
    if (e.code === KEY.OK) {
      if (e.down) return true; // act on release, like every other OK
      if (zone.z === 'field') focusField();
      else if (zone.z === 'chip') setType(TYPES[zone.i][0]);
      else if (results[zone.i]) nav.open(results[zone.i].id);
      return true;
    }
    if (!e.down) return true;
    if (e.back) { nav.back(); return true; }
    if (zone.z === 'field') {
      if (e.code === KEY.RIGHT) setZone({ z: 'chip', i: 0 });
      else if (e.code === KEY.DOWN && results.length) setZone({ z: 'grid', i: 0 });
    } else if (zone.z === 'chip') {
      if (e.code === KEY.LEFT) setZone(zone.i > 0 ? { z: 'chip', i: zone.i - 1 } : { z: 'field' });
      else if (e.code === KEY.RIGHT && zone.i < TYPES.length - 1) setZone({ z: 'chip', i: zone.i + 1 });
      else if (e.code === KEY.DOWN && results.length) setZone({ z: 'grid', i: 0 });
    } else {
      const i = zone.i;
      if (e.code === KEY.LEFT && i % COLS > 0) setZone({ z: 'grid', i: i - 1 });
      else if (e.code === KEY.RIGHT && i % COLS < COLS - 1 && i + 1 < results.length) setZone({ z: 'grid', i: i + 1 });
      else if (e.code === KEY.DOWN && i + COLS < results.length) setZone({ z: 'grid', i: i + COLS });
      else if (e.code === KEY.DOWN && Math.floor(i / COLS) < Math.floor((results.length - 1) / COLS)) setZone({ z: 'grid', i: results.length - 1 });
      else if (e.code === KEY.UP) setZone(i >= COLS ? { z: 'grid', i: i - COLS } : { z: 'field' });
    }
    return true;
  }, active);

  const fieldOn = zone.z === 'field';
  return (
    <div class="screen">
      <div ref={track} class="vtrack" style={{ padding: '27px 48px' }}>
        <div style={{ display: 'flex', alignItems: 'center' }}>
          <input
            ref={input} class={'search-field' + (fieldOn ? ' f' : '')} type="text" value={query}
            placeholder={S.searchHint} autocomplete="off"
            onInput={(ev) => setQuery((ev.target as HTMLInputElement).value)}
            onBlur={() => setEditing(false)}
          />
          {TYPES.map(([t, label], i) => (
            <div key={label} class={'chip' + (type === t ? ' sel' : '') + (zone.z === 'chip' && zone.i === i ? ' f' : '')}>{label}</div>
          ))}
        </div>
        {status ? <div class="t-body muted" style={{ marginTop: '16px' }}>{status}</div> : null}
        <div style={{ marginTop: '16px', paddingTop: '12px' }}>
          {rowsOf(results).map((row, r) => (
            <div key={r} style={{ display: 'flex', marginBottom: '20px' }}>
              {row.map((it, c) => {
                const i = r * COLS + c;
                return (
                  <div key={it.id} class={zone.z === 'grid' && zone.i === i ? 'f' : ''} style={{ marginRight: c < COLS - 1 ? GAP + 'px' : '0' }}>
                    <PosterCard
                      title={displayTitle(it.title)} image={it.posters ? it.posters.medium : null}
                      subtitle={[it.year ? String(it.year) : null, it.imdb_rating ? 'IMDb ' + it.imdb_rating.toFixed(1) : null].filter(Boolean).join(' · ')}
                      serial={isSerial(it)} focused={zone.z === 'grid' && zone.i === i} width={PW}
                    />
                  </div>
                );
              })}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function rowsOf<T>(xs: T[]): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < xs.length; i += COLS) out.push(xs.slice(i, i + COLS));
  return out;
}
