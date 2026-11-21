// Port of ui/player/PlayerController.kt on hls.js (proven on the TV by tizen/spike): one session
// that may span several episodes; per-title TrackChoice; serialized progress writes.
import type HlsType from 'hls.js';
import type { Level, MediaPlaylist } from 'hls.js';
import { kp } from '../api/kinopub';
import type { AudioTrack, Item, MediaFile, Video } from '../api/models';
import { prefs } from '../data/prefs';
import type { TrackChoice } from '../data/prefs';
import * as repo from '../data/repo';
import type { ResumeTarget } from '../data/repo';
import { log } from '../log';

// hls.min.js (the spike's exact build) is loaded as a classic script before the app.
const HlsCtor = (window as any).Hls as typeof HlsType;

export interface TrackOption { key: string; label: string; detail: string | null; selected: boolean; value: number }

export interface EpisodeCard {
  season: number | null;
  episode: number;
  label: string;
  title: string | null;
  thumbnail: string | null;
  progress: number;
  current: boolean;
}

export interface PlayerUi {
  title: string;
  episodeLabel: string | null;
  audio: TrackOption[];
  subtitles: TrackOption[];
  qualities: TrackOption[];
  prev: EpisodeCard | null;
  next: EpisodeCard | null;
  episodes: EpisodeCard[];
  error: string | null;
}

const EMPTY: PlayerUi = { title: '', episodeLabel: null, audio: [], subtitles: [], qualities: [], prev: null, next: null, episodes: [], error: null };

/** "ENG #12", "RUS #04 Forced" → "ENG", "RUS FORCED". */
export function subtitleName(raw: string): string {
  return raw.replace(/\s*#\d+/g, '').replace(/\s+/g, ' ').trim().toUpperCase();
}

export function channelsLabel(ch?: number | null): string | null {
  if (!ch) return null;
  if (ch === 1) return 'моно';
  if (ch === 2) return '2.0';
  if (ch === 6) return '5.1';
  if (ch === 8) return '7.1';
  return ch + 'ch';
}

/** By width: wide films are 3840×1600 etc., so height alone mislabels 4K as 1080p. */
export function qualityLabel(f: MediaFile): string {
  if (f.w >= 3200 || f.h >= 2000) return '4K';
  if (f.w >= 1800 || f.h >= 1000) return '1080p';
  if (f.w >= 1200 || f.h >= 700) return '720p';
  if (f.w > 0) return f.h + 'p';
  return f.quality || f.h + 'p';
}

const playable = (f: MediaFile) => !!(f.url && (f.url.hls4 || f.url.hls || f.url.http));

/** Best file not above the quality cap; at equal height prefer HEVC. */
function pickFile(files: MediaFile[], maxHeight: number | null | undefined): MediaFile | null {
  const usable = files.filter(playable);
  let capped = usable.filter((f) => maxHeight == null || f.h <= maxHeight);
  if (!capped.length) capped = usable;
  let best: MediaFile | null = null;
  capped.forEach((f) => {
    if (!best || f.h > best.h || (f.h === best.h && f.codec === 'h265' && best.codec !== 'h265')) best = f;
  });
  return best;
}

export class PlayerController {
  ui: PlayerUi = EMPTY;
  item: Item | null = null;
  current: ResumeTarget;
  private v: Video | null = null;
  private file: MediaFile | null = null;
  private hls: HlsType | null = null;
  private choice: TrackChoice = {};
  private audioApplied = false;
  private subsApplied = false;
  private savedSec: Record<string, number> = {};
  private writes: Promise<unknown> = Promise.resolve();
  private timer = 0;
  private destroyed = false;
  private recovered = 0;
  // Between load() and the first 'playing' of the new file: currentTime still belongs to the old
  // one (or is 0), so no progress write may happen.
  private switching = true;
  ended = false;

  constructor(private video: HTMLVideoElement, start: ResumeTarget, private onUi: (ui: PlayerUi) => void) {
    this.current = start;
    video.addEventListener('ended', () => this.onEnded());
    // A deliberate pause writes even at 1 s.
    video.addEventListener('pause', () => {
      if (!this.ended && !video.ended && !video.seeking) this.saveProgress(true);
    });
    video.addEventListener('playing', () => {
      this.switching = false;
    });
    video.addEventListener('error', () => {
      const err = video.error;
      if (!this.hls && err) this.setUi({ error: 'код ' + err.code });
    });
    this.timer = window.setInterval(() => {
      if (!video.paused) this.saveProgress();
    }, 30000);
    this.init(start);
  }

  private setUi(p: Partial<PlayerUi>): void {
    this.ui = Object.assign({}, this.ui, p);
    if (!this.destroyed) this.onUi(this.ui);
  }

  private async init(start: ResumeTarget): Promise<void> {
    try {
      const it = await kp.item(start.itemId);
      if (this.destroyed) return;
      this.item = it;
      this.choice = prefs.trackChoice(it.id) || {};
      // Playing counts as activity: the title jumps to the front of its Continue row.
      prefs.markPlayed(it.id);
      this.load(start, start.startSec * 1000);
    } catch (e) {
      this.setUi({ error: (e as Error).message });
    }
  }

  // ---------- loading ----------

  private load(target: ResumeTarget, posMs: number): void {
    const it = this.item;
    if (!it) return;
    const v = repo.video(it, target.season, target.episode);
    if (!v) {
      this.setUi({ error: 'Нет такой серии' });
      return;
    }
    this.current = target;
    this.v = v;
    const f = pickFile(v.files || [], this.choice.maxHeight);
    if (!f) {
      this.setUi({ error: 'Нет файлов' });
      return;
    }
    this.file = f;
    this.switching = true;
    this.audioApplied = false;
    this.subsApplied = false;
    this.ended = false;
    this.recovered = 0;
    this.destroyHls();

    const url = (f.url.hls4 || f.url.hls || f.url.http) as string;
    log('play', it.id, target.season != null ? 'S' + target.season + 'E' + target.episode : 'part ' + target.episode,
      'from', Math.round(posMs / 1000), 's,', f.quality || f.h, f.codec, url.indexOf('.m3u8') >= 0 ? 'hls' : 'http');
    const video = this.video;
    if (url.indexOf('.m3u8') >= 0 && HlsCtor && HlsCtor.isSupported()) {
      const hls = new HlsCtor({
        maxBufferLength: 30,
        maxMaxBufferLength: 60,
        maxBufferSize: 40 * 1000 * 1000,
        backBufferLength: 30,
        startPosition: posMs / 1000,
        capLevelToPlayerSize: false,
      });
      this.hls = hls;
      const E = HlsCtor.Events;
      hls.on(E.MANIFEST_PARSED, (_e, d) => this.onManifest(d.levels));
      hls.on(E.AUDIO_TRACKS_UPDATED, () => this.onAudioTracks());
      hls.on(E.AUDIO_TRACK_SWITCHED, () => this.publishTracks());
      hls.on(E.SUBTITLE_TRACKS_UPDATED, () => this.onSubtitleTracks());
      hls.on(E.SUBTITLE_TRACK_SWITCH, () => this.publishTracks());
      hls.on(E.ERROR, (_e, d) => this.onHlsError(d));
      hls.loadSource(url);
      hls.attachMedia(video);
    } else {
      video.src = url;
      video.currentTime = posMs / 1000;
      video.play().catch(() => undefined);
    }

    const cards = this.episodeCards(it, target);
    let at = -1;
    cards.forEach((c, i) => {
      if (c.current) at = i;
    });
    this.setUi({
      title: it.title,
      episodeLabel: target.season != null ? 'S' + target.season + '·E' + target.episode + (v.title && v.title.trim() ? ' · ' + v.title : '') : null,
      audio: [],
      subtitles: [],
      qualities: this.qualityOptions(v.files || [], f),
      prev: at > 0 ? cards[at - 1] : null,
      next: at >= 0 && at + 1 < cards.length ? cards[at + 1] : null,
      episodes: cards,
      error: null,
    });
  }

  /** Stay at the chosen quality (the mixed manifest lists every one; levels come lowest first). */
  private onManifest(levels: Level[]): void {
    const hls = this.hls;
    const f = this.file;
    if (!hls || !f) return;
    let pick = -1;
    levels.forEach((l, i) => {
      const fits = (l.width || 0) <= f.w + 8 && (l.height || 0) <= f.h + 8;
      if (fits && (pick < 0 || (l.height || 0) > (levels[pick].height || 0))) pick = i;
    });
    if (pick < 0) pick = levels.length - 1;
    log('hls levels', levels.map((l) => l.width + 'x' + l.height + ' ' + (l.videoCodec || '')).join(', '), '-> pinned', pick);
    hls.currentLevel = pick;
    this.video.play().catch((e) => log('play()', (e as Error).message));
  }

  private onHlsError(d: { type: string; details: string; fatal: boolean }): void {
    log('hls error', d.type, d.details, d.fatal ? 'FATAL' : '');
    if (!d.fatal || !this.hls) return;
    if (this.recovered < 2 && d.type === 'mediaError') {
      this.recovered++;
      this.hls.recoverMediaError();
    } else if (this.recovered < 2 && d.type === 'networkError') {
      this.recovered++;
      this.hls.startLoad();
    } else {
      this.setUi({ error: d.details });
    }
  }

  private destroyHls(): void {
    if (this.hls) {
      this.hls.destroy();
      this.hls = null;
    }
  }

  /** Every episode in order across seasons (or the parts of a movie). */
  private episodeCards(it: Item, at: ResumeTarget): EpisodeCard[] {
    const all = repo.flatEpisodes(it);
    if (all.length < 2) return [];
    return all.map(([s, v]) => {
      const sec = this.resumeSec(s, v);
      const watched = sec === 0 && ((!!v.watching && v.watching.status === 1) || v.watched === 1);
      return {
        season: s,
        episode: v.number,
        label: s != null ? 'S' + s + '·E' + v.number : 'Часть ' + v.number,
        title: v.title && v.title.trim() ? v.title : null,
        thumbnail: v.thumbnail || null,
        progress: watched ? 1 : v.duration > 0 ? Math.min(1, sec / v.duration) : 0,
        current: s === at.season && v.number === at.episode,
      };
    });
  }

  /** This session's saved spot, else kino.pub's unfinished time, else 0. */
  private resumeSec(season: number | null, v: Video): number {
    const k = season + ':' + v.number;
    if (this.savedSec[k] != null) return this.savedSec[k];
    const w = v.watching;
    return w && w.status !== 1 && w.time > 0 ? w.time : 0;
  }

  /**
   * Switch to any episode. The one you leave keeps its position and is NOT marked watched —
   * unless it was left in its credits (owner, 2026-11-14).
   */
  playEpisode(season: number | null, episode: number): void {
    const it = this.item;
    if (!it || (season === this.current.season && episode === this.current.episode)) return;
    const v = repo.video(it, season, episode);
    if (!v) return;
    if (this.inCredits()) {
      const done = this.current;
      this.savedSec[done.season + ':' + done.episode] = 0;
      this.write(() => kp.setWatched(it.id, true, done.season, done.episode), 'watched (left in credits)');
    } else this.saveProgress();
    this.load({ itemId: it.id, season, episode, startSec: 0 }, this.resumeSec(season, v) * 1000);
  }

  playNext(): void {
    const it = this.item;
    if (!it) return;
    const n = repo.nextOf(it, this.current.season, this.current.episode);
    if (n) this.playEpisode(n[0], n[1]);
  }

  /** Played to the end: watched, then the next one (from its own saved spot), across seasons. */
  private onEnded(): void {
    const it = this.item;
    if (!it || this.ended) return;
    this.ended = true;
    const done = this.current;
    this.savedSec[done.season + ':' + done.episode] = 0;
    this.write(() => kp.setWatched(it.id, true, done.season, done.episode), 'watched (ended)');
    const n = repo.nextOf(it, done.season, done.episode);
    if (!n) return;
    const v = repo.video(it, n[0], n[1]);
    if (v) this.load({ itemId: it.id, season: n[0], episode: n[1], startSec: 0 }, this.resumeSec(n[0], v) * 1000);
  }

  /** marktime; [paused] = a deliberate pause, which writes even in the first seconds. */
  saveProgress(paused = false): void {
    const it = this.item;
    if (!it || this.ended || this.switching) return;
    const sec = Math.floor(this.video.currentTime || 0);
    if (sec < (paused ? 1 : 5)) return;
    const t = this.current;
    this.savedSec[t.season + ':' + t.episode] = sec;
    this.write(() => kp.markTime(it.id, t.episode, sec, t.season), 'marktime ' + sec);
    prefs.markPlayed(it.id); // keeps "last watched" right even when kino.pub history lags
  }

  /** Inside the last 5 % / last minute (credits databases are out of scope here). */
  private inCredits(): boolean {
    const dur = this.video.duration;
    if (!(dur > 0)) return false;
    const pos = this.video.currentTime;
    return pos >= dur * 0.95 || dur - pos <= 60;
  }

  /** Progress writes go out one at a time, in order: a late marktime can't overtake the toggle. */
  private write(block: () => Promise<unknown>, what: string): void {
    this.writes = this.writes.then(block).then(
      () => log('write ok:', what),
      (e) => log('write failed:', what, (e as Error).message),
    );
  }

  // ---------- tracks ----------

  private audioMeta(): { t: MediaPlaylist; i: number; meta: AudioTrack | null }[] {
    const hls = this.hls;
    if (!hls) return [];
    const audios = ((this.v && this.v.audios) || []).slice().sort((a, b) => a.index - b.index);
    return hls.audioTracks.map((t, i) => {
      // kino.pub names carry its 1-based index: "02. Дубляж. Невафильм (RUS)".
      const m = /^(\d+)\./.exec(t.name || '');
      const n = m ? parseInt(m[1], 10) : null;
      const meta = (n != null ? audios.filter((a) => a.index === n)[0] : null) || audios[i] || null;
      return { t, i, meta };
    });
  }

  /** Subtitles, most useful first: remembered → RU → EN → the rest. */
  private textTracks(): { t: MediaPlaylist; i: number; name: string }[] {
    const hls = this.hls;
    if (!hls) return [];
    const remembered = this.choice.subtitleLang ? subtitleName(this.choice.subtitleLang) : null;
    const all = hls.subtitleTracks.map((t, i) => ({ t, i, name: subtitleName(t.name || t.lang || 'Субтитры ' + (i + 1)) }));
    const rank = (x: { t: MediaPlaylist; name: string }) => {
      const lang = (x.t.lang || '').toLowerCase();
      if (remembered && x.name === remembered) return 0;
      if (lang.indexOf('ru') === 0 || x.name.indexOf('RU') === 0) return 1;
      if (lang.indexOf('en') === 0 || x.name.indexOf('EN') === 0) return 2;
      return 3;
    };
    return all.sort((a, b) => rank(a) - rank(b) || (a.name < b.name ? -1 : a.name > b.name ? 1 : a.i - b.i));
  }

  private onAudioTracks(): void {
    if (!this.audioApplied && this.hls && this.hls.audioTracks.length) {
      this.audioApplied = true;
      const opts = this.audioMeta();
      log('audio tracks', opts.map((o) => o.i + ':' + o.t.name + ' kp=' + (o.meta && o.meta.author ? o.meta.author.title : '')).join(' | '));
      const c = this.choice;
      let best = -1;
      let bestScore = 0;
      opts.forEach((o) => {
        const m = o.meta;
        let s = 0;
        if (c.audioAuthor && m && m.author && m.author.title === c.audioAuthor) s += 4;
        if (c.audioType && m && m.type && m.type.title === c.audioType) s += 1;
        if (c.audioLang && ((m && m.lang) || o.t.lang) === c.audioLang) s += 2;
        if (s > bestScore) {
          bestScore = s;
          best = o.i;
        }
      });
      if (best >= 0) this.selectAudio(best, false);
    }
    this.publishTracks();
  }

  private onSubtitleTracks(): void {
    if (!this.subsApplied && this.hls) {
      this.subsApplied = true;
      const want = this.choice.subtitleLang;
      if (!want) this.selectSubtitle(null, false);
      else {
        const i = this.bestSubtitle(want);
        this.selectSubtitle(i, false);
      }
    }
    this.publishTracks();
  }

  private bestSubtitle(want: string): number | null {
    const list = this.textTracks();
    const byName = list.filter((x) => x.name === subtitleName(want))[0];
    const byLang = list.filter((x) => (x.t.lang || '').toLowerCase() === want.toLowerCase())[0];
    const hit = byName || byLang;
    return hit ? hit.i : null;
  }

  publishTracks(): void {
    const hls = this.hls;
    if (!hls) return;
    const audio = this.audioMeta().map((o) => {
      const m = o.meta;
      const label = (m && m.author && m.author.title) || (m && m.type && m.type.title)
        || (o.t.name || '').replace(/^\d+\.\s*/, '').trim() || o.t.lang || 'Дорожка ' + (o.i + 1);
      const detail = [
        ((m && m.lang) || o.t.lang || '').toUpperCase() || null,
        channelsLabel(m ? m.channels : null),
        m && m.codec ? m.codec.toUpperCase() : null,
      ].filter(Boolean).join(' · ');
      return { key: 'a' + o.i, label, detail: detail || null, selected: hls.audioTrack === o.i, value: o.i };
    });
    const on = hls.subtitleTrack >= 0 && hls.subtitleDisplay;
    const seen: Record<string, number> = {};
    const subs: TrackOption[] = [{ key: 's-off', label: 'Выкл', detail: null, selected: !on, value: -1 }].concat(
      this.textTracks().map((x) => {
        seen[x.name] = (seen[x.name] || 0) + 1;
        const n = seen[x.name];
        return { key: 's' + x.i, label: n > 1 ? x.name + ' ' + n : x.name, detail: null, selected: on && hls.subtitleTrack === x.i, value: x.i };
      }),
    );
    this.setUi({ audio, subtitles: subs });
  }

  selectAudio(i: number, remember = true): void {
    const hls = this.hls;
    if (!hls) return;
    const o = this.audioMeta()[i];
    if (!o) return;
    log('selectAudio', i, '->', o.t.name);
    hls.audioTrack = i;
    if (remember) {
      const m = o.meta;
      this.saveChoice(Object.assign({}, this.choice, {
        audioLang: (m && m.lang) || o.t.lang || null,
        audioAuthor: m && m.author ? m.author.title || null : null,
        audioType: m && m.type ? m.type.title || null : null,
      }));
    }
    this.publishTracks();
  }

  /** null turns subtitles off. */
  selectSubtitle(i: number | null, remember = true): void {
    const hls = this.hls;
    if (!hls) return;
    if (i == null || i < 0) {
      hls.subtitleTrack = -1;
      hls.subtitleDisplay = false;
    } else {
      hls.subtitleDisplay = true;
      hls.subtitleTrack = i;
    }
    if (remember) {
      const t = i != null && i >= 0 ? hls.subtitleTracks[i] : null;
      this.saveChoice(Object.assign({}, this.choice, { subtitleLang: t ? subtitleName(t.name || t.lang || '') : null }));
    }
    this.publishTracks();
  }

  selectQuality(h: number): void {
    const pos = this.video.currentTime * 1000;
    this.saveChoice(Object.assign({}, this.choice, { maxHeight: h }));
    this.load(this.current, pos);
  }

  private saveChoice(c: TrackChoice): void {
    this.choice = c;
    if (this.item) prefs.saveTrackChoice(this.item.id, c);
  }

  private qualityOptions(files: MediaFile[], selected: MediaFile): TrackOption[] {
    const hs: number[] = [];
    files.filter(playable).forEach((f) => {
      if (hs.indexOf(f.h) < 0) hs.push(f.h);
    });
    hs.sort((a, b) => b - a);
    return hs.map((h) => {
      const same = files.filter((f) => f.h === h);
      const f = same.filter((x) => x.codec === 'h265')[0] || same[0];
      return { key: 'q' + h, label: qualityLabel(f), detail: f.codec ? f.codec.toUpperCase() : null, selected: h === selected.h, value: h };
    });
  }

  /** One-line playback stats for the OSD. */
  stats(): string {
    const parts: string[] = [];
    const v = this.video;
    if (v.videoWidth > 0) parts.push(v.videoWidth + '×' + v.videoHeight);
    const hls = this.hls;
    if (hls && hls.currentLevel >= 0 && hls.levels[hls.currentLevel]) {
      const l = hls.levels[hls.currentLevel];
      const codec = (l.videoCodec || '').indexOf('avc') === 0 ? 'AVC' : /^(hvc|hev)/.test(l.videoCodec || '') ? 'HEVC' : (l.videoCodec || '').toUpperCase();
      if (codec) parts.push(codec);
      if (l.bitrate > 0) parts.push('поток ' + (l.bitrate / 1e6).toFixed(1) + ' Мбит/с');
    }
    if (hls && hls.bandwidthEstimate > 0) parts.push('сеть ' + Math.round(hls.bandwidthEstimate / 1e6) + ' Мбит/с');
    let buf = 0;
    const b = v.buffered;
    for (let i = 0; i < b.length; i++) {
      if (b.start(i) <= v.currentTime + 0.5 && b.end(i) > v.currentTime) buf = b.end(i) - v.currentTime;
    }
    parts.push('буфер ' + Math.floor(buf) + ' с');
    return parts.join('  ·  ');
  }

  /** Leaving the player: last marktime, then tear down. */
  destroy(): void {
    this.saveProgress();
    this.destroyed = true;
    clearInterval(this.timer);
    this.destroyHls();
    this.video.removeAttribute('src');
    try {
      this.video.load();
    } catch (e) {
      /* ignore */
    }
  }
}
