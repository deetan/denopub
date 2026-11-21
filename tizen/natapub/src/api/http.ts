import { log } from '../log';
import { S } from '../ui/strings';

export class ApiError extends Error {
  code: number;
  constructor(code: number, message: string) {
    super(message);
    this.code = code;
    this.name = 'ApiError';
  }
}

export class NotAuthorized extends Error {
  constructor() {
    super('Not authorized');
    this.name = 'NotAuthorized';
  }
}

export interface Resp { status: number; text: string }

export interface Req {
  method: 'GET' | 'POST';
  url: string;
  body?: string;
  headers?: Record<string, string>;
  bg?: boolean; // background lane (Continue checks, kids flags)
}

function xhr(r: Req): Promise<Resp> {
  return new Promise((ok, fail) => {
    const x = new XMLHttpRequest();
    x.open(r.method, r.url);
    const h = r.headers || {};
    Object.keys(h).forEach((k) => x.setRequestHeader(k, h[k]));
    x.timeout = 20000;
    x.onload = () => ok({ status: x.status, text: x.responseText });
    x.onerror = () => fail(new ApiError(0, S.noNetwork(shortPath(r.url))));
    x.ontimeout = () => fail(new ApiError(0, S.notRespondingTimeout));
    x.send(r.body || null);
  });
}

export function shortPath(url: string): string {
  const i = url.indexOf('/api/');
  return (i >= 0 ? url.substring(i + 4) : url).split('?')[0];
}

// ---------- throttle: ≤ 4 calls at once, background ones share 2 of them ----------
// Port of KinopubApi.throttled: waking the TV fired ~60 /watching checks at once and kino.pub
// answered 429 "Rate limit exceeded" to the owner's next click.

const MAX_CALLS = 4;
const MAX_BACKGROUND = 2;
const RETRIES_429 = 3;

let active = 0;
let activeBg = 0;
const queue: { bg: boolean; go: () => void }[] = [];
let coolUntil = 0;

function pump(): void {
  for (let i = 0; i < queue.length; ) {
    const w = queue[i];
    if (active < MAX_CALLS && (!w.bg || activeBg < MAX_BACKGROUND)) {
      queue.splice(i, 1);
      active++;
      if (w.bg) activeBg++;
      w.go();
    } else {
      i++;
    }
    if (active >= MAX_CALLS) break;
  }
}

function acquire(bg: boolean): Promise<void> {
  return new Promise((go) => {
    queue.push({ bg, go });
    pump();
  });
}

function release(bg: boolean): void {
  active--;
  if (bg) activeBg--;
  pump();
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export type Transport = (r: Req) => Promise<Resp>;
let transport: Transport = xhr;

/** Dev fixture mode swaps the network for an in-memory fake (never reaches kino.watch). */
export function setTransport(t: Transport): void {
  transport = t;
}

/** Every kino.pub call goes through here. A 429 was not applied, so even toggles are safe to resend. */
export async function throttled(r: Req): Promise<Resp> {
  const bg = !!r.bg;
  for (let attempt = 0; ; attempt++) {
    const wait = coolUntil - Date.now();
    if (wait > 0) await sleep(wait);
    await acquire(bg);
    let res: Resp;
    try {
      res = await transport(r);
    } finally {
      release(bg);
    }
    if (res.status !== 429 || attempt === RETRIES_429) return res;
    const pause = 3000 << attempt; // 3, 6, 12 s
    coolUntil = Math.max(coolUntil, Date.now() + pause);
    log('kino.pub 429 on', shortPath(r.url), '(bg=' + bg + '), retry in', pause / 1000, 's');
  }
}
