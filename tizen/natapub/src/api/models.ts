// kino.pub API v1 shapes (only the fields we use). Mirrors data/api/Models.kt; see docs/kinopub-api.md.

export interface Posters { small?: string; medium?: string; big?: string; wide?: string }
export interface Named { id?: number; title?: string }

export interface WatchState { status: number; time: number } // status -1 not started, 0 in progress, 1 watched

export interface FileUrls { http?: string; hls?: string; hls2?: string; hls4?: string }
export interface MediaFile { quality?: string; quality_id?: number; codec?: string; w: number; h: number; url: FileUrls }

export interface AudioTrack {
  id?: number;
  index: number;
  codec?: string;
  channels?: number;
  lang?: string;
  type?: Named | null;
  author?: Named | null;
}

export interface Subtitle { lang: string; shift?: number; embed?: boolean; forced?: boolean; url?: string }

export interface Video {
  id: number;
  number: number;
  title?: string;
  thumbnail?: string;
  duration: number;
  watched?: number;
  watching?: WatchState | null;
  files: MediaFile[];
  audios: AudioTrack[];
  subtitles: Subtitle[];
}

export interface Season { id?: number; number: number; title?: string; episodes: Video[]; watching?: WatchState | null }

export interface Item {
  id: number;
  title: string;
  type?: string;
  subtype?: string;
  year?: number;
  plot?: string;
  posters?: Posters;
  imdb?: number;
  imdb_rating?: number;
  kinopoisk_rating?: number;
  genres?: Named[];
  quality?: number;
  finished?: boolean;
  new?: number;
  in_watchlist?: boolean;
  subscribed?: boolean;
  videos?: Video[];
  seasons?: Season[];
}

export const SERIAL_TYPES = ['serial', 'docuserial', 'tvshow'];

export function isSerial(it: Item): boolean {
  return (it.seasons || []).length > 0 || SERIAL_TYPES.indexOf(it.type || '') >= 0;
}

export interface ItemsResponse { items: Item[] }

export interface WatchingSerial { id: number; title: string; type?: string; posters?: Posters; total?: number; watched?: number; new?: number; subscribed?: boolean }
export interface WatchingMovie { id: number; title: string; type?: string; posters?: Posters }

export interface WatchingVideo { id: number; number: number; title?: string; time: number; duration: number; status: number; updated?: number | null }
export interface WatchingSeason { id?: number; number: number; status?: number; episodes: WatchingVideo[] }
export interface WatchingInfo { id?: number; videos: WatchingVideo[]; seasons: WatchingSeason[] }

export interface HistoryEntry { time: number; counter: number; first_seen: number; last_seen: number; item: Item }

export interface BookmarkFolder { id: number; title: string; count?: number }

export interface DeviceCode { code: string; user_code: string; verification_uri: string; interval?: number; expires_in?: number }
export interface TokenResponse { access_token?: string; refresh_token?: string; expires_in?: number; error?: string }
