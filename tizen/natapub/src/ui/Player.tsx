import { useEffect, useRef, useState } from 'preact/hooks';
import type { ResumeTarget } from '../data/repo';
import { clock, clockTenths } from '../format';
import { Hold, KEY, Press, useKeys } from '../keys';
import type { KeyEv } from '../keys';
import { log } from '../log';
import { setScreenSaver } from '../platform';
import { segText } from '../api/skip';
import type { Segment } from '../api/skip';
import { PlayerController } from '../player/controller';
import type { EpisodeCard, PlayerUi, TrackOption } from '../player/controller';
import type { Nav } from './App';
import { ChipRow, FixedRow, Img, ProgressLine } from './common';
import { S } from './strings';

type Overlay = 'none' | 'fine' | 'osd' | 'tracks';

/** Quick-jump step by its number in the burst: 2 s ×3 → 5 s ×3 → 10 s ×4 → 30 s ×4 → 1 min ×4 → 2 min ×4 → 3 min. */
function jumpStepMs(n: number): number {
  if (n <= 3) return 2000;
  if (n <= 6) return 5000;
  if (n <= 10) return 10000;
  if (n <= 14) return 30000;
  if (n <= 18) return 60000;
  if (n <= 22) return 120000;
  return 180000;
}
/** OSD scrub step by hold time: 10 s → 30 s → 1 min → 2 min → 5 min. */
function seekStepMs(held: number): number {
  if (held < 600) return 10000;
  if (held < 1500) return 30000;
  if (held < 3000) return 60000;
  if (held < 5000) return 120000;
  return 300000;
}
/** Fine stepping by hold time: 0.5 → 1 → 2 → 5 s. */
function fineStepMs(held: number): number {
  if (held < 600) return 500;
  if (held < 1500) return 1000;
  if (held < 3000) return 2000;
  return 5000;
}
const stepLabel = (ms: number) => (ms >= 60000 ? S.min(ms / 60000) : S.sec(ms / 1000));
const stepLabelFine = (ms: number) => (ms < 1000 ? S.halfSec : S.sec(ms / 1000));
/** Signed burst distance: "+2 с", "−45 с", "+1 мин 30 с". */
function jumpLabel(ms: number): string {
  const sec = Math.round(Math.abs(ms) / 1000);
  const sign = ms < 0 ? '−' : '+';
  if (sec < 60) return sign + S.sec(sec);
  if (sec % 60 === 0) return sign + S.min(sec / 60);
  return sign + S.minSec(Math.floor(sec / 60), sec % 60);
}

function frac(pos: number, dur: number): number {
  return dur > 0 ? Math.max(0, Math.min(1, pos / dur)) : 0;
}

/** Track + played part + a white marker at the position (and an accent one at the scrub point). */
function SeekBar({ pos, dur, scrub, emphasized }: { pos: number; dur: number; scrub: number | null; emphasized: boolean }) {
  const at = (f: number) => 'calc(' + f * 100 + '% - ' + f * 4 + 'px)';
  return (
    <div class="seekbar">
      <ProgressLine progress={frac(pos, dur)} height={emphasized ? 8 : 4} />
      <div class="mark" style={{ left: at(frac(pos, dur)) }} />
      {scrub != null ? <div class="mark scrub" style={{ left: at(frac(scrub, dur)) }} /> : null}
    </div>
  );
}

/**
 * Remote map (PlayerScreen.kt):
 *   ↑ (video)  → FINE mode: pauses, top bar, ← → step 0.5 s (held: 1, 2, 5 s); ↓ / Back resume, OK play/pause
 *   ↓ once     → OSD: seek bar; ← → scrub, commits ~0.9 s after release or on OK
 *   ↓ again    → TRACKS: episodes / voiceover / subtitles / quality; ↑ from its top row → OSD
 *   ← → (video)→ quick jump with a growing step and the signed distance of the burst
 *   OK         → pause / resume (big sign that fades into a corner badge)
 *   Back       → panel → OSD, scrub → cancel, otherwise exit (progress is saved)
 */
export function Player({ nav, start }: { nav: Nav; start: ResumeTarget }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const ctl = useRef<PlayerController | null>(null);
  const [ui, setUi] = useState<PlayerUi | null>(null);
  const [overlay, setOverlay] = useState<Overlay>('osd');
  const [lastInput, setLastInput] = useState(0);
  const [, setTick] = useState(0);
  const [scrubMs, setScrubMs] = useState<number | null>(null);
  const [scrubHint, setScrubHint] = useState('');
  const [fineLabel, setFineLabel] = useState(S.halfSec);
  const [fineBar, setFineBar] = useState(true);
  const [userPaused, setUserPaused] = useState(false);
  const [bigPause, setBigPause] = useState(false);
  const [jumpVisible, setJumpVisible] = useState(false);
  const [jumpTotal, setJumpTotal] = useState(0);
  const hold = useRef(new Hold()).current;
  const jump = useRef({ steps: 0, forward: true, at: 0, origin: 0 }).current;
  const fineResume = useRef(false);
  const [skipVisible, setSkipVisible] = useState(false);
  const skipShown = useRef(''); // the segment already offered, until playback leaves it
  const timers = useRef<Record<string, number>>({}).current;

  useEffect(() => {
    const v = videoRef.current as HTMLVideoElement;
    ctl.current = new PlayerController(v, start, (u) => setUi(u));
    setScreenSaver(false);
    const tick = window.setInterval(() => setTick((n) => n + 1), 250);
    // TV switched off / app hidden: pause (the pause writes the position).
    const vis = () => {
      if (document.hidden) {
        log('player: hidden, pausing');
        v.pause();
      }
    };
    document.addEventListener('visibilitychange', vis);
    return () => {
      clearInterval(tick);
      document.removeEventListener('visibilitychange', vis);
      hold.stop();
      Object.keys(timers).forEach((k) => clearTimeout(timers[k]));
      if (ctl.current) ctl.current.destroy();
      setScreenSaver(true);
    };
  }, []);

  const v = videoRef.current;
  const c = ctl.current;
  const pos = v ? v.currentTime * 1000 : 0;
  const dur = v && v.duration > 0 && isFinite(v.duration) ? v.duration * 1000 : 0;
  const wantsPlay = v ? !v.paused : true;

  const later = (name: string, ms: number, fn: () => void) => {
    clearTimeout(timers[name]);
    timers[name] = window.setTimeout(fn, ms);
  };
  const seekTo = (ms: number) => {
    if (v) v.currentTime = Math.max(0, Math.min(ms, dur || ms)) / 1000;
  };

  // Auto-hide: the OSD after 4 s while playing (not while scrubbing), the fine bar after 3 s idle.
  useEffect(() => {
    if (overlay === 'osd' && scrubMs == null) later('osd', 4000, () => {
      if (videoRef.current && !videoRef.current.paused) setOverlay((o) => (o === 'osd' ? 'none' : o));
    });
    else clearTimeout(timers.osd);
    if (overlay === 'fine') {
      setFineBar(true);
      later('fine', 3000, () => setFineBar(false));
    }
  }, [overlay, lastInput, scrubMs == null]);
  // Playing again clears the pause signs.
  useEffect(() => {
    if (wantsPlay) {
      setUserPaused(false);
      setBigPause(false);
    }
  }, [wantsPlay]);

  function userPause() {
    if (!v) return;
    v.pause();
    setUserPaused(true);
    setBigPause(true);
    later('big', 1200, () => setBigPause(false));
  }
  function togglePause() {
    if (!v) return;
    if (!v.paused) userPause();
    else v.play().catch(() => undefined);
  }
  const leaveFine = () => {
    if (overlay === 'fine') setOverlay('none');
  };
  function enterFine() {
    if (!v) return;
    fineResume.current = !v.paused;
    if (!v.paused) v.pause();
    setUserPaused(false);
    setBigPause(false);
    setOverlay('fine');
  }
  function exitFine() {
    setOverlay('none');
    if (fineResume.current && v) v.play().catch(() => undefined);
    fineResume.current = false;
  }

  function quickSeek(forward: boolean) {
    if (!v) return;
    const now = Date.now();
    const from = v.currentTime * 1000;
    if (!(jumpVisible && jump.forward === forward && now - jump.at < 1500)) {
      jump.steps = 0;
      jump.origin = from;
    }
    jump.steps++;
    jump.forward = forward;
    jump.at = now;
    const step = jumpStepMs(jump.steps);
    const to = Math.max(0, Math.min(dur || from + step, from + (forward ? step : -step)));
    setJumpTotal(to - jump.origin);
    setJumpVisible(true);
    later('jump', 1500, () => setJumpVisible(false));
    seekTo(to);
  }
  const cancelScrub = () => {
    clearTimeout(timers.scrub);
    setScrubMs(null);
  };
  function commitScrub(s: number | null) {
    if (s == null) return;
    seekTo(s);
    cancelScrub();
  }
  function scrub(forward: boolean, held: number) {
    const step = seekStepMs(held);
    clearTimeout(timers.scrub);
    setScrubMs((cur) => Math.max(0, Math.min(dur, (cur == null ? pos : cur) + (forward ? step : -step))));
    setScrubHint((forward ? '+' : '−') + stepLabel(step));
  }
  function fine(forward: boolean, held: number) {
    if (!v) return;
    setFineBar(true);
    later('fine', 3000, () => setFineBar(false));
    if (!v.paused) v.pause();
    setUserPaused(false);
    setBigPause(false);
    const step = fineStepMs(held);
    setFineLabel((forward ? '+' : '−') + stepLabelFine(step));
    seekTo(v.currentTime * 1000 + (forward ? step : -step));
  }

  // Skip button: shown for a few seconds when playback enters a known intro / recap / credits
  // stretch, then gone (OK goes back to pause). Seeking back into it brings it back.
  const segs = ui ? ui.segments : [];
  const active: Segment | null = segs.filter((s) => pos >= s.startMs && pos < (s.endMs == null ? dur : s.endMs) - 2000)[0] || null;
  const activeKey = active ? segText(active) : '';
  const bare = overlay === 'none';
  useEffect(() => {
    clearTimeout(timers.skip);
    if (!active) {
      skipShown.current = '';
      setSkipVisible(false);
      return;
    }
    // The window only runs with nothing else on screen (the player opens with the OSD up).
    if (!bare || skipShown.current === activeKey) return;
    setSkipVisible(true);
    later('skip', active.kind === 'credits' ? 10000 : 8000, () => {
      setSkipVisible(false);
      skipShown.current = activeKey;
    });
  }, [activeKey, bare]);
  const canSkip = skipVisible && !!active && bare && !(ui && ui.error);
  const skipLabel = !active ? '' : active.kind === 'recap' ? S.skipRecap : active.kind === 'intro' ? S.skipIntro
    : c && c.creditsLeadToNext(active) ? S.skipToNext : S.skipCredits;

  const arrows = (code: number) => code === KEY.LEFT || code === KEY.RIGHT;
  const isOk = (code: number) => code === KEY.OK;

  useKeys((e: KeyEv) => {
    if (!c || !v) {
      if (e.down && e.back) nav.back();
      return true;
    }
    setLastInput(Date.now());
    if (overlay === 'tracks') return true; // the panel has its own handler
    // Media keys.
    if (e.down && !e.repeat) {
      if (e.code === KEY.PLAYPAUSE || e.code === KEY.SPACE) { leaveFine(); togglePause(); return true; }
      if (e.code === KEY.PLAY) { leaveFine(); v.play().catch(() => undefined); return true; }
      if (e.code === KEY.PAUSE) { leaveFine(); userPause(); return true; }
      if (e.code === KEY.NEXT) { c.playNext(); return true; }
      if (e.code === KEY.STOP) { nav.back(); return true; }
    }
    if (e.code === KEY.RW || e.code === KEY.FF) {
      hold.handle(e, (code) => quickSeek(code === KEY.FF), 400, 220);
      return true;
    }
    if (!e.down) {
      if (arrows(e.code)) {
        const wasScrubbing = overlay === 'osd' && hold.active;
        hold.handle(e, () => undefined);
        // Releasing ← → while scrubbing: jump there after a beat (OK jumps at once).
        if (wasScrubbing) {
          const s = scrubMs;
          later('scrub', 900, () => setScrubMs((cur) => {
            if (cur != null) seekTo(cur);
            return null;
          }));
          if (s == null) clearTimeout(timers.scrub);
        }
      }
      return true;
    }
    if (e.back) {
      if (overlay === 'fine') exitFine();
      else if (scrubMs != null) cancelScrub();
      else nav.back();
      return true;
    }
    if (e.repeat) return true;
    if (overlay === 'fine') {
      if (arrows(e.code)) hold.handle(e, (code, _n, held) => fine(code === KEY.RIGHT, held), 400, 150);
      else if (e.code === KEY.UP) { setFineBar(true); later('fine', 3000, () => setFineBar(false)); }
      else if (e.code === KEY.DOWN) exitFine();
      else if (isOk(e.code)) { leaveFine(); togglePause(); }
    } else if (overlay === 'osd') {
      if (arrows(e.code)) hold.handle(e, (code, _n, held) => scrub(code === KEY.RIGHT, held), 300, 100);
      else if (e.code === KEY.DOWN) { cancelScrub(); setOverlay('tracks'); }
      else if (e.code === KEY.UP) { cancelScrub(); setOverlay('none'); }
      else if (isOk(e.code)) { if (scrubMs != null) commitScrub(scrubMs); else togglePause(); }
    } else {
      if (e.code === KEY.UP) enterFine();
      else if (e.code === KEY.DOWN) setOverlay('osd');
      else if (arrows(e.code)) hold.handle(e, (code) => quickSeek(code === KEY.RIGHT), 400, 220);
      else if (isOk(e.code) && canSkip && active) {
        log('skip: OK on', segText(active));
        setSkipVisible(false);
        c.skip(active);
      } else if (isOk(e.code)) togglePause(); // just pause/resume: the sign is enough
    }
    return true;
  });

  const err = ui && ui.error;
  const stats = overlay === 'osd' && c ? c.stats() : '';
  return (
    <div class="screen player">
      <video ref={videoRef} />
      <div class={'bigpause fade' + (bigPause && !wantsPlay && !err ? ' show' : '')}>❚❚</div>
      <div class={'cornerpause fade' + (userPaused && !bigPause && !wantsPlay && overlay === 'none' && !err ? ' show' : '')}>❚❚</div>
      <div class={'jumpbar fade' + (jumpVisible && overlay === 'none' ? ' show' : '')}>
        <div class="t-title" style={{ color: 'var(--accent)', marginBottom: '8px' }}>{jumpLabel(jumpTotal)}</div>
        <SeekBar pos={pos} dur={dur} scrub={null} emphasized={false} />
      </div>
      <div class={'skipbtn fade' + (canSkip ? ' show' : '')}>
        <span class="t-title">{skipLabel}</span>
        <span class="ok t-label">OK</span>
      </div>
      {err ? <div class="err t-body">{S.playbackError(err)}</div> : null}
      <div class={'topbar' + (overlay === 'fine' && fineBar ? ' show' : '')}>
        <SeekBar pos={pos} dur={dur} scrub={null} emphasized />
        <div class="spread" style={{ marginTop: '8px' }}>
          <span class="t-title" style={{ color: 'var(--accent)' }}>{clockTenths(pos)}</span>
          <span class="t-title muted">{fineLabel}</span>
        </div>
      </div>
      <div class={'osd' + (overlay === 'osd' || overlay === 'tracks' ? ' show' : '')}>
        <div style={{ display: 'flex', alignItems: 'flex-end' }}>
          <span class="t-headline ell" style={{ marginRight: '12px' }}>{ui ? ui.title : ''}</span>
          {ui && ui.episodeLabel ? <span class="t-title muted ell">{ui.episodeLabel}</span> : null}
        </div>
        {scrubMs != null ? (
          <div style={{ position: 'relative', height: '22px' }}>
            <div class="t-title" style={{ position: 'absolute', width: '120px', textAlign: 'center', color: 'var(--accent)', left: 'calc(' + frac(scrubMs, dur) * 100 + '% - ' + frac(scrubMs, dur) * 120 + 'px)' }}>
              {clock(scrubMs)}
            </div>
          </div>
        ) : null}
        <SeekBar pos={pos} dur={dur} scrub={scrubMs} emphasized={overlay === 'osd'} />
        <div class="spread">
          <span class="t-label muted">{clock(pos)}</span>
          {scrubMs != null ? <span class="t-title" style={{ color: 'var(--accent)' }}>{scrubHint}</span> : null}
          <span class="t-label muted">−{clock(Math.max(0, dur - pos))}</span>
        </div>
        {overlay === 'osd' && stats ? <div class="t-label" style={{ color: 'rgba(154,160,166,.8)' }}>{stats}</div> : null}
        {overlay === 'tracks' && ui && c ? (
          <TracksPanel ui={ui} ctl={c} onExitUp={() => setOverlay('osd')} onEpisodePicked={() => setOverlay('none')} />
        ) : null}
      </div>
    </div>
  );
}

/** 16:9 episode still with "S2·E5 · title" and how far it was watched. */
function EpisodeTile({ e, caption, focused }: { e: EpisodeCard; caption: string | null; focused: boolean }) {
  return (
    <div class={'tile' + (focused ? ' f' : '')}>
      <Img src={e.thumbnail} />
      <div class="grad" />
      {caption ? <div class="tcap">{caption}</div> : null}
      <div class="tbottom">
        <div class="t-label ell" style={{ padding: '5px 8px' }}>{[e.label, e.title].filter(Boolean).join(' · ')}</div>
        {e.progress > 0 ? <ProgressLine progress={e.progress} height={3} /> : null}
      </div>
    </div>
  );
}

type PRow = { key: 'eps' | 'audio' | 'subs' | 'quality'; label: string; size: number };

/**
 * The tracks panel: previous / next episode on top (long-press → every episode), then
 * voiceover, subtitles and quality. The next episode gets focus when it opens.
 */
function TracksPanel({ ui, ctl, onExitUp, onEpisodePicked }: {
  ui: PlayerUi; ctl: PlayerController; onExitUp: () => void; onEpisodePicked: () => void;
}) {
  const hasEpisodes = !!(ui.prev || ui.next);
  const [picking, setPicking] = useState(false);
  const tiles: { e: EpisodeCard; caption: string | null }[] = picking
    ? ui.episodes.map((e) => ({ e, caption: e.current ? S.now : null }))
    : ([] as { e: EpisodeCard; caption: string | null }[])
      .concat(ui.prev ? [{ e: ui.prev, caption: S.previous }] : [])
      .concat(ui.next ? [{ e: ui.next, caption: S.next }] : []);
  const rows: PRow[] = [];
  if (hasEpisodes) rows.push({ key: 'eps', label: picking ? S.rowAllEpisodes : S.rowEpisodes, size: tiles.length });
  if (ui.audio.length) rows.push({ key: 'audio', label: S.rowAudio, size: ui.audio.length });
  if (ui.subtitles.length) rows.push({ key: 'subs', label: S.rowSubtitles, size: ui.subtitles.length });
  if (ui.qualities.length) rows.push({ key: 'quality', label: S.rowQuality, size: ui.qualities.length });
  const sel = (o: TrackOption[]) => Math.max(0, o.map((x) => x.selected).indexOf(true));
  const initial = (): { row: string; cols: Record<string, number> } => ({
    row: hasEpisodes ? 'eps' : 'audio',
    cols: { eps: ui.next ? (ui.prev ? 1 : 0) : 0, audio: sel(ui.audio), subs: sel(ui.subtitles), quality: sel(ui.qualities) },
  });
  const [focus, setFocus] = useState(initial);
  const press = useRef(new Press()).current;
  let ri = rows.map((r) => r.key as string).indexOf(focus.row);
  if (ri < 0) ri = 0;
  const row = rows[ri];
  const col = row ? Math.min(row.size - 1, focus.cols[row.key] || 0) : 0;
  const setCol = (key: string, c: number) => setFocus((f) => ({ row: key, cols: Object.assign({}, f.cols, { [key]: c }) }));

  function pick() {
    if (!row) return;
    if (row.key === 'eps') {
      const t = tiles[col];
      if (!t) return;
      setPicking(false);
      ctl.playEpisode(t.e.season, t.e.episode);
      onEpisodePicked();
    } else if (row.key === 'audio') ctl.selectAudio(ui.audio[col].value);
    else if (row.key === 'subs') ctl.selectSubtitle(ui.subtitles[col].value < 0 ? null : ui.subtitles[col].value);
    else if (row.key === 'quality') ctl.selectQuality(ui.qualities[col].value);
  }

  useKeys((e: KeyEv) => {
    if (!row) {
      if (e.down && (e.back || e.code === KEY.UP)) onExitUp();
      return true;
    }
    const longable = row.key === 'eps' && !picking;
    if (press.handle(e, 0, pick, longable ? () => {
      // Long-press an episode card: that row becomes every episode, the current one focused.
      setPicking(true);
      setCol('eps', Math.max(0, ui.episodes.map((x) => x.current).indexOf(true)));
    } : undefined)) return true;
    if (!e.down) return true;
    if (e.back) {
      if (picking) {
        setPicking(false);
        setCol('eps', initial().cols.eps);
      } else onExitUp();
    } else if (e.code === KEY.LEFT && col > 0) setCol(row.key, col - 1);
    else if (e.code === KEY.RIGHT && col < row.size - 1) setCol(row.key, col + 1);
    else if (e.code === KEY.UP) {
      if (ri === 0) onExitUp();
      else setFocus((f) => ({ row: rows[ri - 1].key, cols: f.cols }));
    } else if (e.code === KEY.DOWN && ri < rows.length - 1) setFocus((f) => ({ row: rows[ri + 1].key, cols: f.cols }));
    return true;
  });

  const chipRow = (r: PRow, options: TrackOption[]) => {
    const focusedRow = row === r;
    return (
      <div class="prow" key={r.key}>
        <div class="plabel t-label muted">{r.label}</div>
        <div class="pbody">
          <ChipRow id={'p:' + r.key} focusKey={(focusedRow ? col : -1) + ':' + options.length} viewW={724}>
            {options.map((o, i) => (
              <div key={o.key} class={'chip' + (o.selected ? ' sel' : '') + (focusedRow && i === col ? ' f' : '')}>
                {[o.label, o.detail].filter(Boolean).join(' · ')}
              </div>
            ))}
          </ChipRow>
        </div>
      </div>
    );
  };

  return (
    <div class="panel">
      {rows.map((r) => {
        if (r.key === 'eps') {
          return (
            <div class="prow" key="eps">
              <div class="plabel t-label muted">{r.label}</div>
              <div class="pbody">
                <FixedRow id={picking ? 'p:all' : 'p:eps'} items={tiles} width={208} gap={16} padH={0} view={724} focus={row === r ? col : null}
                  render={(t, _i, focused) => <EpisodeTile e={t.e} caption={t.caption} focused={focused} />} />
              </div>
            </div>
          );
        }
        if (r.key === 'audio') return chipRow(r, ui.audio);
        if (r.key === 'subs') return chipRow(r, ui.subtitles);
        return chipRow(r, ui.qualities);
      })}
    </div>
  );
}
