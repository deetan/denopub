import type { ComponentChildren, VNode } from 'preact';
import { useLayoutEffect, useRef, useState } from 'preact/hooks';
import { KEY, Press, useKeys } from '../keys';
import type { KeyEv } from '../keys';
import { S } from './strings';

export function Badge({ text, style }: { text: string; style?: Record<string, string | number> }) {
  return <span class="badge" style={style}>{text}</span>;
}

export function ProgressLine({ progress, height = 4, width }: { progress: number; height?: number; width?: number }) {
  const p = Math.max(0, Math.min(1, progress || 0));
  return (
    <div class="progress" style={{ height: height + 'px', width: width ? width + 'px' : '100%' }}>
      <div style={{ width: p * 100 + '%' }} />
    </div>
  );
}

export function Img({ src }: { src?: string | null }) {
  return src ? <img class="img" src={src} /> : null;
}

/** 2:3 poster with the title (and a muted subtitle) under it. */
export function PosterCard(p: {
  title: string; image?: string | null; subtitle?: string | null; badge?: string | null;
  serial?: boolean; focused: boolean; width?: number;
}) {
  const w = p.width || 150;
  return (
    <div class="poster" style={{ width: w + 'px' }}>
      <div class={'card' + (p.focused ? ' f' : '')} style={{ width: w + 'px', height: Math.round(w * 1.5) + 'px' }}>
        <div class="clip">
          <Img src={p.image} />
          {p.badge ? <span class="badge" style={{ position: 'absolute', right: '6px', top: '6px' }}>{p.badge}</span> : null}
          {p.serial ? <span class="serial-tag">{S.seriesTag}</span> : null}
        </div>
      </div>
      <div class="cap t-body ell">{p.title}</div>
      {p.subtitle ? <div class="t-label muted ell">{p.subtitle}</div> : null}
    </div>
  );
}

/** 16:9 "continue" card: what, which episode, how far in. */
export function ContinueCard(p: {
  title: string; image?: string | null; episodeLabel: string | null; meta: string; progress: number;
  newBadge: string | null; focused: boolean; width?: number;
}) {
  const w = p.width || 300;
  return (
    <div class={'card ccard' + (p.focused ? ' f' : '')} style={{ width: w + 'px', height: Math.round((w * 9) / 16) + 'px' }}>
      <div class="clip">
        <Img src={p.image} />
        <div class="grad-bottom" />
        {p.newBadge ? <span class="badge" style={{ position: 'absolute', right: '8px', top: '8px' }}>{p.newBadge}</span> : null}
        <div style={{ position: 'absolute', left: '8px', right: '8px', bottom: '6px' }}>
          <div class="t-body ell">{p.title}</div>
          <div class="t-label muted ell">{[p.episodeLabel, p.meta].filter(Boolean).join(' · ')}</div>
        </div>
        <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0 }}><ProgressLine progress={p.progress} /></div>
      </div>
    </div>
  );
}

export const rowOffsets: Record<string, number> = {};

/**
 * A horizontal row of fixed-width cards (a LazyRow): only the cards near the visible window are
 * rendered, and the row scrolls just enough to keep the focused card on screen.
 */
export function FixedRow<T>(p: {
  id: string; items: T[]; width: number; gap: number; padH?: number; view?: number; focus: number | null;
  render: (item: T, i: number, focused: boolean) => VNode;
}) {
  const padH = p.padH == null ? 48 : p.padH;
  const step = p.width + p.gap;
  const view = p.view || 960 - 2 * padH;
  let off = rowOffsets[p.id] || 0;
  if (p.focus != null) {
    const left = p.focus * step;
    if (left - off < 0) off = left;
    else if (left + p.width - off > view) off = left + p.width - view;
  }
  const maxOff = Math.max(0, p.items.length * step - p.gap - view);
  off = Math.max(0, Math.min(off, maxOff));
  rowOffsets[p.id] = off;
  const first = Math.max(0, Math.floor(off / step) - 2);
  const last = Math.min(p.items.length, Math.ceil((off + view) / step) + 3);
  const cards: VNode[] = [];
  for (let i = first; i < last; i++) {
    cards.push(
      <div key={i} style={{ marginRight: p.gap + 'px', flex: 'none' }}>{p.render(p.items[i], i, p.focus === i)}</div>,
    );
  }
  return (
    <div class="row-track" style={{ transform: 'translateX(' + (padH - off) + 'px)' }}>
      <div style={{ width: first * step + 'px', flex: 'none' }} />
      {cards}
    </div>
  );
}

/**
 * A row of variable-width chips/buttons: measured after render, scrolled minimally so the
 * focused child (class "f") stays inside [pad, 960 - pad] of the screen.
 */
export function ChipRow(p: { id: string; pad?: number; viewW?: number; children: ComponentChildren; focusKey: unknown }) {
  const track = useRef<HTMLDivElement>(null);
  const pad = p.pad == null ? 0 : p.pad;
  useLayoutEffect(() => {
    const el = track.current;
    if (!el) return;
    const f = el.querySelector('.f') as HTMLElement | null;
    const viewW = p.viewW || 960;
    let off = rowOffsets[p.id] || 0;
    if (f) {
      const left = f.offsetLeft;
      const right = left + f.offsetWidth;
      if (left - off < pad) off = left - pad;
      else if (right - off > viewW - pad) off = right - (viewW - pad);
    }
    off = Math.max(0, Math.min(off, Math.max(0, el.scrollWidth - viewW)));
    rowOffsets[p.id] = off;
    el.style.transform = 'translateX(' + -off + 'px)';
  }, [p.focusKey]);
  return <div ref={track} class="row-track" style={{ transform: 'translateX(' + -(rowOffsets[p.id] || 0) + 'px)' }}>{p.children}</div>;
}

/**
 * Keeps the focused element (class "f") of [track] inside the visible part of its parent:
 * moves the track vertically with a transform, scrolling as little as possible.
 */
export function useVScroll(track: { current: HTMLElement | null }, deps: unknown[], margin = 24, pinTop?: () => number | null) {
  const off = useRef(0);
  useLayoutEffect(() => {
    const el = track.current;
    if (!el || !el.parentElement) return;
    const viewH = el.parentElement.clientHeight;
    const pinned = pinTop ? pinTop() : null;
    let o = off.current;
    if (pinned != null) {
      o = pinned;
    } else {
      const f = el.querySelector('.f') as HTMLElement | null;
      if (f) {
        let top = 0;
        let n: HTMLElement | null = f;
        while (n && n !== el) {
          top += n.offsetTop;
          n = n.offsetParent as HTMLElement | null;
        }
        const bottom = top + f.offsetHeight;
        if (top - o < margin) o = top - margin;
        else if (bottom - o > viewH - margin) o = bottom - viewH + margin;
      }
    }
    o = Math.max(0, Math.min(o, Math.max(0, el.offsetHeight - viewH)));
    off.current = o;
    el.style.transform = 'translateY(' + -o + 'px)';
  }, deps);
}

export interface MenuAction { label: string; hint?: string; run: () => void }

/**
 * Long-press menu (ActionMenu.kt): first action focused, OK runs it, Back closes. It opens while
 * OK is still held; its own Press never saw that press, so the release runs nothing.
 */
export function ActionMenu(p: { title: string; actions: MenuAction[]; onClose: () => void }) {
  const [i, setI] = useState(0);
  const press = useRef(new Press()).current;
  useKeys((e: KeyEv) => {
    if (press.handle(e, i, (k) => { p.onClose(); p.actions[k].run(); })) return true;
    if (!e.down) return true;
    if (e.back) p.onClose();
    else if (e.code === KEY.UP) setI(Math.max(0, i - 1));
    else if (e.code === KEY.DOWN) setI(Math.min(p.actions.length - 1, i + 1));
    return true;
  });
  return (
    <div class="scrim">
      <div class="menu">
        <div class="mtitle t-title clamp2">{p.title}</div>
        {p.actions.map((a, k) => (
          <div key={k} class={'mitem' + (k === i ? ' f' : '')}>
            <div class="t-body" style={{ fontSize: '16px' }}>{a.label}</div>
            {a.hint ? <div class="hint">{a.hint}</div> : null}
          </div>
        ))}
      </div>
    </div>
  );
}

/** Short confirmation at the bottom ("Отмечено просмотренным"), gone after 2.5 s. */
export function useToast(): [string | null, (m: string) => void] {
  const [msg, setMsg] = useState<string | null>(null);
  const t = useRef(0);
  const show = (m: string) => {
    setMsg(m);
    clearTimeout(t.current);
    t.current = window.setTimeout(() => setMsg(null), 2500);
  };
  return [msg, show];
}
