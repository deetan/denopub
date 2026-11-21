// Device-local state the kino.pub API has no slot for (port of LocalPrefs). localStorage only.

const P = 'natapub.';

function read<T>(key: string, def: T): T {
  try {
    const raw = localStorage.getItem(P + key);
    return raw == null ? def : (JSON.parse(raw) as T);
  } catch (e) {
    return def;
  }
}

function write(key: string, v: unknown): void {
  try {
    localStorage.setItem(P + key, JSON.stringify(v));
  } catch (e) {
    /* quota: ignore */
  }
}

/** What was picked last time for a title; carried to the next episode. */
export interface TrackChoice {
  audioLang?: string | null;
  audioAuthor?: string | null; // studio, e.g. "LostFilm" — the strongest signal
  audioType?: string | null;
  subtitleLang?: string | null; // null = off
  maxHeight?: number | null; // null = best available
}

export const prefs = {
  trackChoice(id: number): TrackChoice | null {
    return read<TrackChoice | null>('tracks_' + id, null) || read<TrackChoice | null>('tracks_default', null);
  },
  /** Saved per title and as the default for titles never opened before. */
  saveTrackChoice(id: number, c: TrackChoice): void {
    write('tracks_' + id, c);
    write('tracks_default', c);
  },

  /** Kids/adult verdict per title (genres never change). */
  kidsFlags(): Record<string, boolean> {
    return read<Record<string, boolean>>('kids', {});
  },
  saveKidsFlags(flags: Record<string, boolean>): void {
    const all = prefs.kidsFlags();
    Object.keys(flags).forEach((k) => (all[k] = flags[k]));
    write('kids', all);
  },

  /** "Начал смотреть" stamps, epoch ms per title. */
  startedAt(): Record<string, number> {
    return read<Record<string, number>>('started_at', {});
  },
  markStarted(id: number, on: boolean): void {
    const m = prefs.startedAt();
    if (on) m[id] = Date.now();
    else delete m[id];
    write('started_at', m);
  },

  /** Last playback here per title; cleared only by «Убрать из «Продолжить»». */
  playedAt(): Record<string, number> {
    return read<Record<string, number>>('played_at', {});
  },
  markPlayed(id: number, on = true): void {
    const m = prefs.playedAt();
    if (on) m[id] = Date.now();
    else delete m[id];
    write('played_at', m);
  },

  seenWishlist(): number[] {
    return read<number[]>('wishlist_seen', []);
  },
  markWishlistSeen(ids: number[]): void {
    const s = prefs.seenWishlist();
    ids.forEach((id) => {
      if (s.indexOf(id) < 0) s.push(id);
    });
    write('wishlist_seen', s);
  },

  cache<T>(key: string): T | null {
    return read<T | null>('cache_' + key, null);
  },
  saveCache(key: string, v: unknown): void {
    write('cache_' + key, v);
  },
};
