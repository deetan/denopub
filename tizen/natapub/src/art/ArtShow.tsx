// Port of ui/art/ArtShow.kt: full-screen paintings with a slow Ken Burns drift (wide scrolls pan
// end to end), 2.5 s cross-fades, a caption beside / above / under the painting (measured in the
// DOM), the clock + weather band on top and a 4 dp progress bar at the bottom.
//
// Every animation is a CSS transition of `transform` or `opacity` only (the TV is a 2019 armv7):
// the room change is translate+scale of a box laid out at full size, the drift is a scale (or a
// translateX for panoramas), the bar a scaleX. requestAnimationFrame only measures frame times.
import { useEffect, useRef, useState } from 'preact/hooks';
import { setOverlay, type KeyEv } from '../keys';
import { log } from '../log';
import { KEY, setScreenSaver } from '../platform';
import { S } from '../ui/strings';
import { deal, loadCatalog, refreshCatalog } from './catalog';
import { artistLine, imageUrl, isPanorama, type Painting } from './painting';
import { aqiLevel, getForecast, icon, lastForecast, windArrow, type Forecast, type WeatherSlot } from './weather';
import './art.css';

/** How long one painting stays (owner 2026-11-22: 40 s, same as Android; was 30 s). */
const SLIDE_MS = 40000;
const FADE_MS = 2500;
const ROOM_MS = 900;
/** Clock + weather band height; the painting and its caption are laid out below it. */
const TOP_BAR = 58;
const W = 960;
const H = 540 - TOP_BAR;
const SIDE_TEXT_W = 330;
const TEXT_MARGIN = 56;
/** Panoramas: the Commons width asked for (3840 like Android; lowered if the TV can't take it). */
const PANORAMA_W = 3840;

type Place = 'LEFT' | 'RIGHT' | 'BOTTOM' | 'TOP' | 'OVER';

interface Rect { x: number; y: number; w: number; h: number }

/** The largest rect of [a] inside the box, centred. */
function fit(a: number, x: number, y: number, w: number, h: number): Rect {
  const fw = a > w / h ? w : h * a;
  const fh = fw / a;
  return { x: x + (w - fw) / 2, y: y + (h - fh) / 2, w: fw, h: fh };
}

function el(tag: string, cls: string, text?: string): HTMLElement {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

/** The caption block (also used, off-screen, to measure its height at a given width). */
function captionContent(p: Painting): HTMLElement {
  const c = el('div', 'art-capc');
  c.appendChild(el('div', 'art-title', p.title));
  c.appendChild(el('div', 'art-artist', artistLine(p)));
  if (p.museum) c.appendChild(el('div', 'art-sub', p.museum));
  // The room is reserved for the story from the start (measured with it); it fades in at 4 s.
  if (p.story) c.appendChild(el('div', 'art-story', p.story));
  return c;
}

// ---------- frame timing ----------

interface Frames { fade: number[]; drift: number[] }

function stats(a: number[]): string {
  if (!a.length) return 'n/a';
  const s = a.slice().sort((x, y) => x - y);
  const avg = a.reduce((x, y) => x + y, 0) / a.length;
  const p95 = s[Math.min(s.length - 1, Math.floor(s.length * 0.95))];
  const long = a.filter((x) => x > 50).length;
  return 'avg ' + avg.toFixed(1) + ' p95 ' + p95.toFixed(0) + ' max ' + s[s.length - 1].toFixed(0) + ' ms, ' + long + '/' + a.length + ' > 50 ms';
}

// ---------- one painting on screen ----------

class Slide {
  root: HTMLElement;
  private room: HTMLElement;
  private drift: HTMLElement;
  private pan: HTMLElement | null = null;
  private caption: HTMLElement;
  private place: Place;
  private full: Rect;
  private target: Rect;
  private a: number;
  private panorama: boolean;
  private bottomBand: number;
  private zoomIn: boolean;
  private startedAt = 0;
  private timers: number[] = [];
  captionOn: boolean;
  phase = 0;

  constructor(readonly p: Painting, img: HTMLImageElement, captionOn: boolean, private kenBurns: boolean, measure: (p: Painting, w: number) => number) {
    this.captionOn = captionOn;
    this.a = img.naturalWidth / img.naturalHeight; // the bitmap's aspect, not the catalog's
    this.panorama = isPanorama(this.a);
    const a = this.a;
    const m = 24;
    const fillsScreen = Math.abs(a / (W / H) - 1) < 0.08;
    this.full = fillsScreen ? fit(a, 0, 0, W, H) : fit(a, m, m, W - 2 * m, H - 2 * m);
    const sideText = measure(p, SIDE_TEXT_W);
    this.bottomBand = measure(p, W - 2 * TEXT_MARGIN) + 28;
    const sideX = TEXT_MARGIN + SIDE_TEXT_W + 32;
    const rooms: { [k: string]: Rect } = {};
    if (sideText <= H - 56) {
      rooms.LEFT = fit(a, sideX, m, W - sideX - 40, H - 2 * m); // text left, painting right
      rooms.RIGHT = fit(a, 40, m, W - sideX - 40, H - 2 * m); // painting left, text right
    }
    rooms.BOTTOM = fit(a, m, 16, W - 2 * m, H - this.bottomBand - 24); // painting above the text
    rooms.TOP = fit(a, m, this.bottomBand + 8, W - 2 * m, H - this.bottomBand - 24); // text above
    // Any room keeping the painting within 85 % of the best one is a candidate, picked at random
    // (owner 2026-11-15: not always the same arrangement); too small everywhere → over a scrim.
    const keys = Object.keys(rooms) as Place[];
    const best = Math.max.apply(null, keys.map((k) => rooms[k].w));
    const ok = keys.filter((k) => rooms[k].w >= 0.85 * best);
    const pick = <T,>(xs: T[]) => xs[Math.floor(Math.random() * xs.length)];
    if (this.panorama && this.bottomBand < H * 0.5) this.place = pick<Place>(['BOTTOM', 'TOP']);
    else if (this.panorama || best < 0.58 * this.full.w || !ok.length) this.place = 'OVER';
    else this.place = pick(ok);
    this.target = rooms[this.place] || this.full;
    this.zoomIn = Math.random() < 0.5;

    this.root = el('div', 'art-slide');
    // The painting's own colours, blurred and dimmed, fill the space around it: a tiny canvas
    // copy stretched to the screen (a CSS blur filter would be far too slow on this TV).
    const back = document.createElement('canvas');
    back.className = 'art-back';
    back.width = 192;
    back.height = 108;
    const t0 = Date.now();
    try {
      // Down to 32×18 in two steps (one big step aliases), then back up inside the canvas, where
      // the upscale is bilinear: the CSS stretch of a 32×18 canvas showed blocks.
      const mid = document.createElement('canvas');
      mid.width = 160;
      mid.height = 90;
      (mid.getContext('2d') as CanvasRenderingContext2D).drawImage(img, 0, 0, 160, 90);
      const tiny = document.createElement('canvas');
      tiny.width = 32;
      tiny.height = 18;
      (tiny.getContext('2d') as CanvasRenderingContext2D).drawImage(mid, 0, 0, 32, 18);
      (back.getContext('2d') as CanvasRenderingContext2D).drawImage(tiny, 0, 0, 192, 108);
    } catch (e) {
      /* no backdrop */
    }
    this.decodeMs = Date.now() - t0; // drawImage decodes the bitmap: the cost the fade would pay
    this.root.appendChild(back);
    this.root.appendChild(el('div', 'art-dim'));
    const area = el('div', 'art-area');
    this.root.appendChild(area);

    img.className = 'art-img';
    this.room = el('div', 'art-room');
    this.drift = el('div', 'art-drift');
    this.drift.appendChild(img);
    this.room.appendChild(this.drift);
    if (this.panorama) {
      // Full width of the scroll at the available height, panned from one end to the other.
      this.pan = el('div', 'art-pan');
      this.room.style.width = H * a + 'px';
      this.room.style.height = H + 'px';
      this.pan.appendChild(this.room);
      area.appendChild(this.pan);
    } else {
      const f = this.full;
      this.room.style.left = f.x + 'px';
      this.room.style.top = f.y + 'px';
      this.room.style.width = f.w + 'px';
      this.room.style.height = f.h + 'px';
      this.drift.style.transformOrigin = Math.round((0.25 + Math.random() * 0.5) * 100) + '% ' + Math.round((0.25 + Math.random() * 0.5) * 100) + '%';
      area.appendChild(this.room);
    }
    this.applyRoom(false);

    this.caption = el('div', 'art-cap art-cap-' + this.place.toLowerCase());
    if (this.place === 'LEFT' || this.place === 'RIGHT') this.caption.style.top = Math.max(0, (H - sideText) / 2) + 'px';
    this.caption.appendChild(captionContent(p));
    area.appendChild(this.caption);
  }

  decodeMs = 0;

  private roomOn(): boolean {
    return this.captionOn && this.place !== 'OVER';
  }

  /** Scale of the panorama when its caption takes the band above / below it. */
  private panScale(): number {
    return this.roomOn() ? (H - this.bottomBand - 16) / H : 1;
  }

  private applyRoom(animate: boolean): void {
    this.room.style.transition = animate ? 'transform ' + ROOM_MS + 'ms ease-in-out' : 'none';
    if (this.panorama) {
      const s = this.panScale();
      const y = this.roomOn() && this.place === 'TOP' ? this.bottomBand + 16 : 0;
      this.room.style.transform = 'translate(0px,' + y + 'px) scale(' + s + ')';
    } else if (this.roomOn()) {
      const f = this.full;
      const t = this.target;
      this.room.style.transform = 'translate(' + (t.x - f.x).toFixed(1) + 'px,' + (t.y - f.y).toFixed(1) + 'px) scale(' + (t.w / f.w).toFixed(4) + ')';
    } else {
      this.room.style.transform = 'translate(0px,0px) scale(1)';
    }
  }

  /** Starts (or re-aims, after ↑ ↓) the drift so it ends with the slide. */
  private applyDrift(initial: boolean): void {
    const total = SLIDE_MS + FADE_MS;
    const left = Math.max(1000, total - (Date.now() - this.startedAt));
    if (this.panorama && this.pan) {
      const end = -Math.max(0, H * this.a * this.panScale() - W);
      this.pan.style.transition = 'transform ' + left + 'ms ' + (initial ? 'cubic-bezier(0.4,0,0.2,1)' : 'ease-out');
      this.pan.style.transform = 'translateX(' + end.toFixed(1) + 'px)';
      return;
    }
    if (!this.kenBurns) {
      this.drift.style.transition = 'none';
      this.drift.style.transform = 'none';
      return;
    }
    const z = this.roomOn() ? 0.03 : 0.08; // Ken Burns 8 % at full size, 3 % beside a caption
    this.drift.style.transition = 'transform ' + left + 'ms linear';
    this.drift.style.transform = 'scale(' + (this.zoomIn ? 1 + z : 1) + ')';
  }

  /** Called once the slide is in the DOM: the fade and every timeline start here. */
  start(): void {
    this.startedAt = Date.now();
    if (this.panorama && this.pan) this.pan.style.transform = 'translateX(0px)';
    else if (this.kenBurns) this.drift.style.transform = 'scale(' + (this.zoomIn ? 1 : 1.08) + ')';
    void this.root.offsetWidth; // commit the start values before the transitions
    this.root.style.opacity = '1';
    this.applyDrift(true);
    // Caption timeline: clean picture → titles at 2 s → story at 4 s → clean for the last 4 s.
    this.timers.push(window.setTimeout(() => this.setPhase(1), 2000));
    this.timers.push(window.setTimeout(() => this.setPhase(2), 4000));
    this.timers.push(window.setTimeout(() => this.setPhase(0), SLIDE_MS - 4000));
  }

  private setPhase(n: number): void {
    this.phase = n;
    this.caption.classList.toggle('on', n >= 1 && this.captionOn);
    this.caption.classList.toggle('story', n >= 2);
  }

  setCaption(on: boolean): void {
    this.captionOn = on;
    this.caption.classList.toggle('on', this.phase >= 1 && on);
    this.applyRoom(true);
    this.applyDrift(false);
  }

  setKenBurns(on: boolean): void {
    this.kenBurns = on;
    this.applyDrift(false);
  }

  /** Fades out, then leaves the DOM. */
  leave(): void {
    this.timers.forEach((t) => clearTimeout(t));
    this.root.style.opacity = '0';
    window.setTimeout(() => {
      if (this.root.parentNode) this.root.parentNode.removeChild(this.root);
    }, FADE_MS + 100);
  }

  dispose(): void {
    this.timers.forEach((t) => clearTimeout(t));
    if (this.root.parentNode) this.root.parentNode.removeChild(this.root);
  }
}

// ---------- the show ----------

function loadImage(url: string, timeoutMs = 30000): Promise<HTMLImageElement> {
  return new Promise((ok, fail) => {
    const img = new Image();
    const t = window.setTimeout(() => fail(new Error('timeout')), timeoutMs);
    img.onload = () => {
      clearTimeout(t);
      ok(img);
    };
    img.onerror = () => {
      clearTimeout(t);
      fail(new Error('load error'));
    };
    img.src = url;
  });
}

class Show {
  private all: Painting[] = [];
  private history: Painting[] = [];
  private pos = -1;
  private slide: Slide | null = null;
  private advance = 0;
  private busy = false;
  private disposed = false;
  private captionOn = true;
  private kenBurns = true;
  private slowSlides = 0;
  private preloaded: { id: string; img: Promise<HTMLImageElement> } | null = null;
  private raf = 0;
  private lastFrame = 0;
  private frames: Frames = { fade: [], drift: [] };
  private shownAt = 0;
  private measureBox: HTMLElement;
  private measureRef: HTMLElement;
  private measured = false;

  constructor(private stage: HTMLElement, private bar: HTMLElement, measureBox: HTMLElement) {
    this.measureBox = measureBox;
    this.measureRef = el('div', 'art-ref');
    measureBox.appendChild(this.measureRef);
  }

  async run(): Promise<void> {
    this.all = await loadCatalog();
    if (this.disposed) return;
    refreshCatalog();
    log('art: start,', this.all.length, 'paintings');
    this.loop();
    await this.show(true);
  }

  /** Caption height (dp) at [width] dp, measured in the DOM. A 100 dp reference cancels the zoom. */
  private measure = (p: Painting, width: number): number => {
    const box = this.measureBox;
    const c = captionContent(p);
    c.style.width = width + 'px';
    box.appendChild(c);
    const unit = this.measureRef.offsetHeight / 100 || 1;
    const h = c.offsetHeight / unit;
    box.removeChild(c);
    if (!this.measured) {
      this.measured = true;
      log('art: measure', p.id, 'at', width, 'dp:', h.toFixed(1), 'dp (ref 100 dp =', this.measureRef.offsetHeight, 'px)');
    }
    return h + 4;
  };

  private fetch(p: Painting): Promise<HTMLImageElement> {
    if (this.preloaded && this.preloaded.id === p.id) {
      const r = this.preloaded.img;
      this.preloaded = null;
      return r;
    }
    const url = imageUrl(p, PANORAMA_W);
    return url ? loadImage(url) : Promise.reject(new Error('no image'));
  }

  /** Shows history[target] (dealing new paintings past the end); skips works whose image won't load. */
  async show(forward: boolean): Promise<void> {
    if (this.busy || this.disposed) return;
    this.busy = true;
    try {
      for (let attempt = 0; attempt < 6; attempt++) {
        const target = forward ? this.pos + 1 : this.pos - 1;
        if (target < 0) return;
        if (target >= this.history.length) {
          const d = deal(this.all);
          if (!d) return;
          this.history.push(d);
        }
        const p = this.history[target];
        const t0 = Date.now();
        let img: HTMLImageElement;
        try {
          img = await this.fetch(p);
        } catch (e) {
          log('art:', p.id, 'failed:', (e as Error).message, imageUrl(p, PANORAMA_W));
          this.history.splice(target, 1);
          if (!forward) this.pos--;
          continue;
        }
        if (this.disposed) return;
        const loadMs = Date.now() - t0;
        this.pos = target;
        this.present(p, img, loadMs);
        // Deal the next one now and start its download, so the switch lands when the bar ends.
        if (this.pos === this.history.length - 1) {
          const n = deal(this.all);
          if (n) {
            this.history.push(n);
            const url = imageUrl(n, PANORAMA_W);
            if (url) {
              const pr = loadImage(url);
              pr.catch(() => undefined);
              this.preloaded = { id: n.id, img: pr };
            }
          }
        }
        return;
      }
    } finally {
      this.busy = false;
    }
  }

  private present(p: Painting, img: HTMLImageElement, loadMs: number): void {
    this.logFrames();
    const s = new Slide(p, img, this.captionOn, this.kenBurns, this.measure);
    const old = this.slide;
    this.slide = s;
    this.stage.appendChild(s.root);
    const inserted = performance.now();
    // Two frames at opacity 0 first: the bitmap is rastered before the fade, not during it.
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (this.disposed || this.slide !== s) return;
      const firstFrame = Math.round(performance.now() - inserted);
      if (old) old.leave();
      s.start();
      this.shownAt = Date.now();
      this.frames = { fade: [], drift: [] };
      this.restartBar();
      log('art:', p.id, img.naturalWidth + 'x' + img.naturalHeight, 'load', loadMs, 'ms, decode', s.decodeMs, 'ms, first frame', firstFrame, 'ms');
    }));
    clearTimeout(this.advance);
    this.advance = window.setTimeout(() => this.show(true), SLIDE_MS);
  }

  private restartBar(): void {
    const b = this.bar;
    b.style.transition = 'none';
    b.style.transform = 'scaleX(0)';
    void b.offsetWidth;
    b.style.transition = 'transform ' + SLIDE_MS + 'ms linear';
    b.style.transform = 'scaleX(1)';
  }

  /** rAF deltas, bucketed into the cross-fade and the drift of the slide on screen. */
  private loop(): void {
    const tick = (t: number) => {
      if (this.disposed) return;
      // Only frames while the page is visible: a hidden page gets rAF at ~1 Hz (1000 ms "frames"
      // that once turned Ken Burns off although the TV showed it smoothly).
      const visible = document.visibilityState === 'visible';
      if (visible && this.lastFrame && this.shownAt) {
        const d = t - this.lastFrame;
        if (d < 2000) (Date.now() - this.shownAt < FADE_MS ? this.frames.fade : this.frames.drift).push(d);
      }
      this.lastFrame = visible ? t : 0;
      this.raf = requestAnimationFrame(tick);
    };
    this.raf = requestAnimationFrame(tick);
  }

  /** One line per slide; two slow (visible) drifts in a row turn Ken Burns off for this session. */
  private logFrames(): void {
    const s = this.slide;
    if (!s || !this.frames.drift.length) return;
    const f = this.frames;
    log('art: frames', s.p.id, '· fade', stats(f.fade), '· drift', stats(f.drift), this.kenBurns ? '' : '(no Ken Burns)');
    const sorted = f.drift.slice().sort((x, y) => x - y);
    const p95 = sorted[Math.floor(sorted.length * 0.95)] || 0;
    // Needs a real sample (≥ 5 s of visible frames) and p95 > 50 ms on two slides in a row.
    if (this.kenBurns && f.drift.length >= 150 && p95 > 50) {
      this.slowSlides++;
      if (this.slowSlides >= 2) {
        this.kenBurns = false;
        log('art: drift p95', p95.toFixed(0), 'ms twice: Ken Burns off');
      }
    } else this.slowSlides = 0;
  }

  key(e: KeyEv): 'wake' | void {
    if (!e.down) return undefined;
    if (e.code === KEY.LEFT || e.code === KEY.RIGHT) {
      if (!e.repeat) this.show(e.code === KEY.RIGHT);
      return undefined;
    }
    if (e.code === KEY.UP || e.code === KEY.DOWN) {
      if (!e.repeat) {
        this.captionOn = !this.captionOn;
        if (this.slide) this.slide.setCaption(this.captionOn);
      }
      return undefined;
    }
    return 'wake';
  }

  dispose(): void {
    this.disposed = true;
    this.logFrames();
    cancelAnimationFrame(this.raf);
    clearTimeout(this.advance);
    if (this.slide) this.slide.dispose();
  }
}

// ---------- weather band ----------

function clock(): string {
  const d = new Date();
  const p = (n: number) => (n < 10 ? '0' : '') + n;
  return p(d.getHours()) + ':' + p(d.getMinutes());
}

/** Rain chance (always, when known) and the amount if ≥ 0.1 mm, stacked to stay narrow. */
function Rain({ s }: { s: WeatherSlot }) {
  if (s.precipProb == null && s.precipMm < 0.1) return null;
  return (
    <div class="wx-rain">
      {s.precipProb != null ? <div>{S.wxRain(s.precipProb)}</div> : null}
      {s.precipMm >= 0.1 ? <div class="wx-mm">{S.wxMm(s.precipMm)}</div> : null}
    </div>
  );
}

function Cell({ s, big, label }: { s: WeatherSlot; big?: boolean; label?: string }) {
  return (
    <div class="wx-cell">
      <div class="wx-label">{label || s.label}</div>
      <div class={'wx-row' + (big ? ' big' : '')}>
        <span class="wx-ico">{icon(s)}</span>
        <span class="wx-t">{(s.temp > 0 ? '+' : '') + s.temp + '°'}</span>
        <Rain s={s} />
      </div>
    </div>
  );
}

/** Once per run: does this TV draw the weather emoji in colour, in black and white, or as boxes? */
let glyphsLogged = false;
function logGlyphs(): void {
  if (glyphsLogged) return;
  glyphsLogged = true;
  try {
    const c = document.createElement('canvas');
    c.width = 40;
    c.height = 40;
    const g = c.getContext('2d') as CanvasRenderingContext2D;
    g.font = '28px sans-serif';
    const tofu = g.measureText('\uFFFF').width;
    const out = ['☀️', '⛅', '🌧', '🌨', '💨', '💧', '☔', '↖'].map((ch) => {
      g.clearRect(0, 0, 40, 40);
      g.fillStyle = '#808080';
      g.fillText(ch, 4, 30);
      const d = g.getImageData(0, 0, 40, 40).data;
      let ink = 0;
      let colour = 0;
      for (let i = 0; i < d.length; i += 4) {
        if (d[i + 3] > 40) {
          ink++;
          if (Math.max(d[i], d[i + 1], d[i + 2]) - Math.min(d[i], d[i + 1], d[i + 2]) > 40) colour++;
        }
      }
      return ch + (ink === 0 ? ' none' : colour > 10 ? ' colour' : ' mono') + (g.measureText(ch).width === tofu ? '?' : '');
    });
    log('art: glyphs', out.join(', '));
  } catch (e) {
    log('art: glyph check failed', (e as Error).message);
  }
}

/** Desktop `?wxworst`: the widest band we can expect, to prove it still fits one row. */
function worstCase(): Forecast {
  const slot = (label: string): WeatherSlot => ({ label, code: 63, temp: -14, cloud: 100, precipProb: 100, precipMm: 12.4, day: true });
  return {
    now: slot(S.wxNow), hours: [slot('23:00'), slot('02:00')],
    tomorrow: [slot(S.wxMorning), slot(S.wxAfternoon), slot(S.wxEvening)],
    place: 'Novi Sad', fetchedAt: Date.now(), windMs: 17, gustMs: 28, windFrom: 300, aqi: 288, pm25: 238, humidity: 100,
  };
}
const WORST = import.meta.env.DEV && /[?&]wxworst/.test(location.search);

function TopBar() {
  const [now, setNow] = useState(clock());
  const [f, setF] = useState<Forecast | null>(WORST ? worstCase() : lastForecast());
  const ref = useRef<HTMLDivElement>(null);
  // Too wide → drop the optional bits one level at a time: 1 = PM2.5 figure, 2 = rain mm too.
  const [tight, setTight] = useState(0);
  useEffect(() => {
    logGlyphs();
    const t = window.setInterval(() => setNow(clock()), 10000);
    let alive = true;
    const pull = () => getForecast().then((x) => {
      if (alive && x && !WORST) setF(x);
    });
    pull();
    const w = window.setInterval(pull, 10 * 60000);
    return () => {
      alive = false;
      clearInterval(t);
      clearInterval(w);
    };
  }, []);
  useEffect(() => {
    // The band must fit one 1080p row (it overflowed twice on Android).
    // Measured as the last cell's right edge against the band's inner edge (rects are in device
    // px; the 960 dp band width gives the zoom), so "slack" is the room left over in dp.
    const e = ref.current;
    if (!e || !f) return;
    const box = e.getBoundingClientRect();
    const unit = box.width / 960 || 1;
    const last = e.lastElementChild as HTMLElement | null;
    const right = last ? (last.getBoundingClientRect().right - box.left) / unit : 0;
    const inner = 960 - 20; // right padding
    const over = right > inner + 0.5;
    log('art: weather band', Math.round(right), 'of', inner, 'dp, slack', Math.round(inner - right), 'dp, scroll', e.scrollWidth, '/', e.clientWidth,
      over ? (tight < 2 ? 'too wide, dropping level ' + (tight + 1) : 'OVERFLOW') : 'fits', tight ? '(tight ' + tight + ')' : '');
    if (over && tight < 2) setTight(tight + 1);
  }, [f && f.fetchedAt, tight]);
  const gust = f && f.windMs != null && f.gustMs != null && f.gustMs >= f.windMs + 2 ? '–' + Math.round(f.gustMs) : '';
  return (
    <div class={'wx' + (tight ? ' tight' + tight : '')} ref={ref}>
      <div class="wx-clock">{now}</div>
      {f ? <Cell s={f.now} big /> : null}
      {f && (f.windMs != null || f.aqi != null || f.humidity != null) ? (
        <div class="wx-air">
          <div class="wx-small">
            {f.windMs != null ? S.wxWind(Math.round(f.windMs) + gust, f.windFrom != null ? windArrow(f.windFrom) : '') : ''}
            {f.humidity != null ? <span class={f.windMs != null ? 'wx-hum' : ''}>{S.wxHumidity(f.humidity)}</span> : null}
          </div>
          {f.aqi != null ? (
            <div class="wx-small">
              <span class="wx-dot" style={{ background: aqiLevel(f.aqi)[1] }} />
              {'AQI ' + f.aqi + ' · ' + aqiLevel(f.aqi)[0]}
              {f.pm25 != null ? <span class="wx-pm">{' · PM2.5 ' + Math.round(f.pm25)}</span> : null}
            </div>
          ) : null}
        </div>
      ) : null}
      {f ? f.hours.map((h) => <Cell key={h.label} s={h} />) : null}
      {f && f.tomorrow.length ? <div class="wx-sep" /> : null}
      {f ? f.tomorrow.map((t, i) => <Cell key={t.label} s={t} label={i === 0 ? S.wxDay(new Date(Date.now() + 86400000).getDay(), t.label) : t.label} />) : null}
      {f && f.place ? <div class="wx-label wx-place">{f.place}</div> : null}
    </div>
  );
}

/** The paintings overlay. [onWake] gets the key that dismissed it. */
export function ArtShow({ onWake }: { onWake: (code: number) => void }) {
  const stage = useRef<HTMLDivElement>(null);
  const bar = useRef<HTMLDivElement>(null);
  const measure = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const show = new Show(stage.current as HTMLElement, bar.current as HTMLElement, measure.current as HTMLElement);
    setScreenSaver(false); // the TV's own screensaver must not cover the paintings
    setOverlay((e) => {
      if (show.key(e) === 'wake') {
        setOverlay(null, e.code);
        onWake(e.code);
      }
    });
    show.run();
    return () => {
      setOverlay(null);
      show.dispose();
      setScreenSaver(true);
    };
  }, []);
  return (
    <div class="art">
      <div class="art-stage" ref={stage} />
      <TopBar />
      <div class="art-bar" ref={bar} />
      <div class="art-measure" ref={measure} />
    </div>
  );
}
