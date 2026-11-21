import { useEffect, useRef, useState } from 'preact/hooks';
import { authLost, tokens } from '../api/kinopub';
import { ArtShow } from '../art/ArtShow';
import { lastKey, touchIdle } from '../keys';
import type { ResumeTarget } from '../data/repo';
import { log } from '../log';
import { Auth } from './Auth';
import { Details } from './Details';
import { Home } from './Home';
import { Player } from './Player';
import { Search } from './Search';

/**
 * Paintings after 3 minutes without a key press while nothing plays (owner 2026-11-21: a Tizen
 * web app can't be the system screensaver, so it runs inside Natapub). Desktop dev: `?idle=10`.
 */
const IDLE_MS = (() => {
  const m = import.meta.env.DEV ? /[?&]idle=(\d+)/.exec(location.search) : null;
  return m ? Number(m[1]) * 1000 : 3 * 60000;
})();

/** Desktop `?stay`: keep the paintings when the tab is hidden (screenshot tools hide it). */
const STAY = import.meta.env.DEV && /[?&]stay/.test(location.search);

export type Route = { k: 'home' } | { k: 'search' } | { k: 'details'; id: number } | { k: 'player'; target: ResumeTarget };

export interface Nav {
  open: (id: number) => void;
  play: (t: ResumeTarget) => void;
  search: () => void;
  /** The paintings now (Home's Screensaver button); any key but ← → ↑ ↓ returns. */
  paintings: () => void;
  back: () => void;
}

/**
 * A screen stack like the Android NavHost: screens under the top one stay mounted but hidden
 * (Home keeps its rows, scroll and focus) and get `active=false`, so they take no keys.
 */
export function App() {
  const [loggedIn, setLoggedIn] = useState(!!tokens());
  const [stack, setStack] = useState<Route[]>([{ k: 'home' }]);

  useEffect(() => {
    authLost.listener = () => {
      log('auth lost: back to login');
      setStack([{ k: 'home' }]);
      setLoggedIn(false);
    };
  }, []);

  const top = stack[stack.length - 1];
  const [art, setArt] = useState(false);
  const artOn = useRef(false);
  artOn.current = art;
  const idleAllowed = loggedIn && top.k !== 'player';
  useEffect(() => {
    if (!idleAllowed) {
      setArt(false);
      return undefined;
    }
    const t = window.setInterval(() => {
      if (!artOn.current && !document.hidden && Date.now() - lastKey() >= IDLE_MS) {
        log('art: idle', Math.round(IDLE_MS / 1000), 's on', top.k, '-> paintings');
        setArt(true);
      }
    }, 1000);
    return () => clearInterval(t);
  }, [idleAllowed, top.k]);
  useEffect(() => {
    // TV off / app in the background: no paintings, and the idle clock starts again on return.
    const vis = () => {
      touchIdle();
      if (document.hidden && !STAY) setArt(false);
    };
    document.addEventListener('visibilitychange', vis);
    return () => document.removeEventListener('visibilitychange', vis);
  }, []);

  if (!loggedIn) return <Auth onDone={() => setLoggedIn(true)} />;

  const nav: Nav = {
    open: (id) => setStack((s) => s.concat([{ k: 'details', id }])),
    play: (target) => setStack((s) => s.concat([{ k: 'player', target }])),
    search: () => setStack((s) => s.concat([{ k: 'search' }])),
    paintings: () => {
      log('art: opened from the Home button');
      setArt(true);
    },
    back: () => setStack((s) => (s.length > 1 ? s.slice(0, s.length - 1) : s)),
  };

  return (
    <div>
      {stack.map((r, i) => {
        const active = i === stack.length - 1;
        const key = i + ':' + r.k + (r.k === 'details' ? r.id : r.k === 'player' ? r.target.itemId : '');
        return (
          <div key={key} class={active ? '' : 'hidden'}>
            {r.k === 'home' ? <Home nav={nav} active={active} /> : null}
            {r.k === 'search' ? <Search nav={nav} active={active} /> : null}
            {r.k === 'details' ? <Details nav={nav} active={active} id={r.id} /> : null}
            {r.k === 'player' ? <Player nav={nav} start={r.target} /> : null}
          </div>
        );
      })}
      {art ? (
        <ArtShow
          onWake={(code) => {
            log('art: woken by key', code);
            touchIdle();
            setArt(false);
          }}
        />
      ) : null}
    </div>
  );
}
