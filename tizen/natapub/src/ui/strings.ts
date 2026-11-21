// Every piece of UI copy in one place (owner, 2026-11-21: Natapub's interface is in English,
// unlike denopub's Russian). Content from kino.pub stays as the API gives it, except titles: see
// displayTitle() in format.ts.

/** English plural: plural(1, 'episode') → "episode", plural(4, 'episode') → "episodes". */
export function plural(n: number, one: string, many = one + 's'): string {
  return n === 1 ? one : many;
}

/** "45 min", "1 h 5 min". */
export function formatMinutes(sec: number): string {
  const m = Math.floor(sec / 60);
  return m >= 60 ? Math.floor(m / 60) + ' h ' + (m % 60) + ' min' : m + ' min';
}

export const S = {
  // Search
  search: 'Search',
  searchHint: 'Movie or series title',
  searchAll: 'All',
  searchMovies: 'Movies',
  searchSeries: 'Series',
  searching: 'Searching…',
  nothingFound: 'Nothing found',

  // Login
  signIn: 'Sign in to kino.pub',
  openAndEnter: (uri: string) => 'Open ' + uri + ' and enter the code',
  error: (m: string) => 'Error: ' + m,

  // Errors from the network layer
  notResponding: (code: number) => 'kino.pub is not responding (HTTP ' + code + '), try again later',
  notRespondingTimeout: 'kino.pub is not responding (timeout)',
  tooManyRequests: 'kino.pub: too many requests, try again in a minute',
  badResponse: (path: string) => 'kino.pub: unexpected response (' + path + ')',
  noNetwork: (path: string) => 'No network (' + path + ')',
  failed: (m: string) => 'Failed: ' + m,

  // Home
  rowSeries: 'Series',
  rowMovies: 'Movies',
  rowKids: 'Kids',
  rowWishlist: 'Wishlist',
  rowNewMovies: 'New movies',
  rowNewSeries: 'New series',
  rowPopular: 'Popular',
  newCount: (n: number) => n + ' new',
  newEpisodes: (n: number) => '+' + n + ' new',
  timeLeft: (sec: number) => formatMinutes(sec) + ' left',
  nextEpisode: 'next episode',
  fromStart: 'from the start',
  episodesAhead: (n: number) => n + ' ' + plural(n, 'episode') + ' to go',
  newBadge: 'NEW',
  seriesTag: 'series',
  loading: 'Loading…',
  loadFailed: (m: string) => 'Failed to load: ' + m,
  empty: 'Nothing here',
  pressBackAgain: 'Press Back again to exit',

  // Menus and their confirmations
  openCard: 'Open title',
  addToWishlist: 'Add to wishlist',
  removeFromWishlist: 'Remove from wishlist',
  startedWatching: 'Started watching',
  startedWatchingHint: 'add to Continue',
  markWatched: 'Mark watched',
  markEpisodeWatched: (ep: string) => 'Mark ' + ep + ' watched',
  markEpisodeWatchedHint: 'continue with the next one',
  markSeriesWatched: 'Mark the whole series watched',
  removeFromContinue: 'Remove from Continue',
  removeFromContinueHint: 'clears the history on kino.pub',
  addedToWishlist: 'Added to wishlist',
  removedFromWishlist: 'Removed from wishlist',
  addedToContinue: 'Added to Continue',
  removedFromContinue: 'Removed from Continue',
  markedWatched: 'Marked watched',
  unmarked: 'Unmarked',

  // Title card
  notResponding0: 'kino.pub is not responding',
  retrying: 'Retrying by itself…',
  retry: 'Retry',
  kp: 'KP',
  play: 'Play',
  playEp: (s: number, e: number) => 'Play S' + s + '·E' + e,
  resumeEp: (s: number, e: number, sec: number) => 'Resume S' + s + '·E' + e + ' at ' + formatMinutes(sec),
  resumeAt: (sec: number) => 'Resume at ' + formatMinutes(sec),
  inWishlist: 'In wishlist ✓',
  toWishlist: 'Wishlist',
  watchingOn: 'Watching ✓',
  watchedOn: 'Watched ✓',
  season: (n: number) => 'Season ' + n,
  seasonWatched: (n: number) => 'Season ' + n + ' watched',
  seasonUnmarked: (n: number) => 'Season ' + n + ': unmarked',
  episodeWatched: (ep: string) => ep + ' watched',
  episodeUnmarked: (ep: string) => ep + ': unmarked',
  episode: (n: number) => 'Episode ' + n,
  watched: 'watched',
  stoppedAt: (at: number, of: number) => 'stopped at ' + formatMinutes(at) + ' of ' + formatMinutes(of),
  similar: 'Similar',

  // Player
  playbackError: (m: string) => 'Playback error: ' + m,
  noSuchEpisode: 'No such episode',
  noFiles: 'No files',
  errorCode: (c: number) => 'code ' + c,
  part: (n: number) => 'Part ' + n,
  subtitlesN: (n: number) => 'Subtitles ' + n,
  trackN: (n: number) => 'Track ' + n,
  off: 'Off',
  mono: 'mono',
  stream: (mbps: string) => 'stream ' + mbps + ' Mbit/s',
  network: (mbps: number) => 'network ' + mbps + ' Mbit/s',
  buffer: (s: number) => 'buffer ' + s + ' s',
  now: 'Now',
  previous: '‹ Previous',
  next: 'Next ›',
  rowEpisodes: 'Episodes',
  rowAllEpisodes: 'All episodes',
  rowAudio: 'Audio',
  rowSubtitles: 'Subtitles',
  rowQuality: 'Quality',
  sec: (n: number | string) => n + ' s',
  min: (n: number) => n + ' min',
  minSec: (m: number, s: number) => m + ' min ' + s + ' s',
  halfSec: '0.5 s',
};
