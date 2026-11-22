// Buffering for kino.pub on the Samsung (2026-11-22: short freezes when kino.pub lags).
//
// hls.js buffers ahead max(maxBufferLength, 8 × maxBufferSize / bitrate) seconds, capped by
// maxMaxBufferLength — so maxBufferSize is the real lever: it buys long buffers on ordinary
// bitrates and stays at the 30 s floor on heavy ones, where the TV's MSE quota is the limit (the
// spike saw `bufferFullError`; hls.js answers it by halving maxMaxBufferLength, not fatally).
// The back buffer counts against the same quota, so it is kept short.
//
// A segment download that stalls mid-transfer used to wait for hls.js's 120 s load limit before a
// retry; 40 s (and 8 s to the first byte) retries while the buffer still has content.

import type HlsType from 'hls.js';
import type { FragLoadedData } from 'hls.js';
import { log } from '../log';

export const HLS_BUFFER = {
  maxBufferLength: 30,
  maxMaxBufferLength: 120,
  maxBufferSize: 100 * 1000 * 1000, // ≈ 100 s at 8 Mbit/s, 53 s at 15, the 30 s floor above 27
  backBufferLength: 10,
  fragLoadPolicy: {
    default: {
      maxTimeToFirstByteMs: 8000,
      maxLoadTimeMs: 40000,
      timeoutRetry: { maxNumRetry: 6, retryDelayMs: 0, maxRetryDelayMs: 0 },
      errorRetry: { maxNumRetry: 8, retryDelayMs: 1000, maxRetryDelayMs: 8000 },
    },
  },
};

/** Seconds buffered ahead of the playhead. */
function ahead(video: HTMLVideoElement): number {
  const b = video.buffered;
  const t = video.currentTime;
  for (let i = 0; i < b.length; i++) {
    if (b.start(i) <= t + 0.1 && t < b.end(i)) return Math.round(b.end(i) - t);
  }
  return 0;
}

/**
 * `buffer:` log lines: every stall in plain playback (position, buffered ahead, hls.js bandwidth
 * estimate) and how long it lasted. Video element listeners live as long as the player screen.
 */
export function logStalls(video: HTMLVideoElement, getHls: () => HlsType | null): void {
  let stallAt = 0;
  let seekAt = 0;
  const net = () => {
    const h = getHls();
    return h ? Math.round(h.bandwidthEstimate / 1e6) : 0;
  };
  video.addEventListener('seeking', () => {
    seekAt = Date.now();
  });
  video.addEventListener('waiting', () => {
    if (stallAt || video.paused) return;
    stallAt = Date.now();
    // Waiting right after a seek is expected; only a stall in plain playback is a freeze.
    if (stallAt - seekAt > 1500) {
      log('buffer: stall at', Math.round(video.currentTime), 's, ahead', ahead(video), 's, net', net(), 'Mbit/s');
    } else {
      stallAt = -stallAt;
    }
  });
  video.addEventListener('playing', () => {
    if (!stallAt) return;
    const seek = stallAt < 0;
    const ms = Date.now() - Math.abs(stallAt);
    if (!seek || ms > 3000) log('buffer:', seek ? 'seek' : 'stall', 'resumed after', ms, 'ms, net', net(), 'Mbit/s');
    stallAt = 0;
  });
}

/** hls.js FRAG_LOADED handler: logs fragment loads slower than 4 s. */
export function slowFragLogger(video: HTMLVideoElement) {
  return (_e: unknown, d: FragLoadedData): void => {
    const st = d.frag.stats;
    const ms = Math.round(st.loading.end - st.loading.start);
    if (ms < 4000) return;
    log('buffer: slow', d.frag.type, 'fragment', ms, 'ms,', Math.round(st.loaded / 1024), 'KB (' + Math.round(d.frag.duration) + 's of media), ahead', ahead(video), 's');
  };
}
