import { useEffect, useState } from 'preact/hooks';
import { authLost, tokens } from '../api/kinopub';
import type { ResumeTarget } from '../data/repo';
import { log } from '../log';
import { Auth } from './Auth';
import { Details } from './Details';
import { Home } from './Home';
import { Player } from './Player';

export type Route = { k: 'home' } | { k: 'details'; id: number } | { k: 'player'; target: ResumeTarget };

export interface Nav {
  open: (id: number) => void;
  play: (t: ResumeTarget) => void;
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

  if (!loggedIn) return <Auth onDone={() => setLoggedIn(true)} />;

  const nav: Nav = {
    open: (id) => setStack((s) => s.concat([{ k: 'details', id }])),
    play: (target) => setStack((s) => s.concat([{ k: 'player', target }])),
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
            {r.k === 'details' ? <Details nav={nav} active={active} id={r.id} /> : null}
            {r.k === 'player' ? <Player nav={nav} start={r.target} /> : null}
          </div>
        );
      })}
    </div>
  );
}
