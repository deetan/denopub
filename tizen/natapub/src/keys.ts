import { useEffect, useRef } from 'preact/hooks';
import { KEY, isBack } from './platform';

/**
 * One global key dispatcher. Screens and dialogs register handlers; only the newest active one
 * gets keys (a dialog on top of a screen swallows everything). Key auto-repeat was never seen on
 * the TV, so `repeat` is our own bookkeeping (keydown while the key is already down) and holds
 * are timed between keydown and keyup.
 */
export interface KeyEv {
  code: number;
  down: boolean; // false = keyup
  repeat: boolean; // keydown while already down
  back: boolean;
}

export type KeyHandler = (e: KeyEv) => boolean | void;

const stack: { h: { current: KeyHandler } }[] = [];
const isDown: Record<number, boolean> = {};

export function initKeys(): void {
  const handled = [KEY.LEFT, KEY.UP, KEY.RIGHT, KEY.DOWN, KEY.OK, KEY.BACK, KEY.BACKSPACE, KEY.SPACE];
  // While a text field has DOM focus (the IME is up) it keeps its typing keys: only the keys that
  // leave the field reach the screens, and Backspace is a delete there, not Back.
  const inField = (ev: KeyboardEvent) => {
    const t = ev.target as HTMLElement | null;
    return !!t && t.tagName === 'INPUT';
  };
  const fieldKeys = [KEY.UP, KEY.DOWN, KEY.OK, KEY.BACK, KEY.ESC, KEY.IME_DONE, KEY.IME_CANCEL];
  document.addEventListener('keydown', (ev) => {
    const code = ev.keyCode;
    if (inField(ev)) {
      if (fieldKeys.indexOf(code) < 0) return;
      isDown[code] = true;
      dispatch({ code, down: true, repeat: false, back: code === KEY.BACK || code === KEY.ESC });
      if (code !== KEY.OK) ev.preventDefault();
      return;
    }
    const repeat = !!isDown[code];
    isDown[code] = true;
    dispatch({ code, down: true, repeat, back: isBack(code) });
    if (handled.indexOf(code) >= 0 || code > 10000) ev.preventDefault();
  });
  document.addEventListener('keyup', (ev) => {
    const code = ev.keyCode;
    if (inField(ev) && fieldKeys.indexOf(code) < 0) return;
    isDown[code] = false;
    dispatch({ code, down: false, repeat: false, back: isBack(code) });
    if (handled.indexOf(code) >= 0) ev.preventDefault();
  });
}

function dispatch(e: KeyEv): void {
  const top = stack[stack.length - 1];
  if (top) top.h.current(e);
}

/** Registers [handler] while [active]; the latest-registered active handler wins. */
export function useKeys(handler: KeyHandler, active = true): void {
  const ref = useRef(handler);
  ref.current = handler;
  useEffect(() => {
    if (!active) return undefined;
    const entry = { h: ref };
    stack.push(entry);
    return () => {
      const i = stack.indexOf(entry);
      if (i >= 0) stack.splice(i, 1);
    };
  }, [active]);
}

export const LONG_PRESS_MS = 600;

/**
 * OK press machine: click on release, long-press after [LONG_PRESS_MS] of holding. A dialog
 * opened by the long-press gets the release, but its own machine never saw the press, so it
 * ignores it (the "held-OK guard": letting go must not run the first menu item).
 */
export class Press {
  private timer = 0;
  private armed = false;
  private long = false;
  private target: unknown = null;

  /** Returns true when the event was an OK key. */
  handle<T>(e: KeyEv, target: T, onClick: (t: T) => void, onLong?: (t: T) => void): boolean {
    if (e.code !== KEY.OK) return false;
    if (e.down) {
      if (e.repeat) return true;
      this.armed = true;
      this.long = false;
      this.target = target;
      clearTimeout(this.timer);
      if (onLong) {
        this.timer = window.setTimeout(() => {
          this.long = true;
          onLong(this.target as T);
        }, LONG_PRESS_MS);
      }
      return true;
    }
    clearTimeout(this.timer);
    if (this.armed && !this.long) {
      this.armed = false;
      onClick(this.target as T);
    }
    this.armed = false;
    return true;
  }

  reset(): void {
    clearTimeout(this.timer);
    this.armed = false;
  }
}

/**
 * Hold-to-repeat for ← →: the first keydown steps once, then (still held after [delay]) every
 * [every] ms until keyup. `n` counts steps of this hold (0 = the press itself).
 */
export class Hold {
  private t0 = 0;
  private t1 = 0;
  private code = 0;
  private n = 0;
  private started = 0;

  handle(e: KeyEv, step: (code: number, n: number, heldMs: number) => void, delay = 400, every = 150): void {
    if (e.down) {
      if (e.repeat) return;
      this.stop();
      this.code = e.code;
      this.n = 0;
      this.started = Date.now();
      step(e.code, 0, 0);
      this.t0 = window.setTimeout(() => {
        this.t1 = window.setInterval(() => {
          this.n++;
          const held = Date.now() - this.started;
          // Safety: a lost keyup must not scrub forever.
          if (held > 30000) {
            this.stop();
            return;
          }
          step(this.code, this.n, held);
        }, every);
      }, delay);
    } else if (e.code === this.code) {
      this.stop();
    }
  }

  stop(): void {
    clearTimeout(this.t0);
    clearInterval(this.t1);
    this.code = 0;
  }

  get active(): boolean {
    return this.code !== 0;
  }
}
export { KEY };
