// Log lines go to the console and, when a sink listens on the Mac (tizen/tools/logsink.py), to
// it: the TV has no other way to show us what happened. Posts fail silently without a sink.

const SINK_DEFAULT = 'http://192.168.0.39:8765/log';

function sinkUrl(): string {
  try {
    return localStorage.getItem('natapub.sink') || SINK_DEFAULT;
  } catch (e) {
    return SINK_DEFAULT;
  }
}

function stamp(): string {
  return new Date().toISOString().substr(11, 12);
}

export function log(...args: unknown[]): void {
  const s = args.map((a) => (typeof a === 'string' ? a : safeJson(a))).join(' ');
  const line = stamp() + ' ' + s;
  // eslint-disable-next-line no-console
  console.log(line);
  try {
    const x = new XMLHttpRequest();
    x.open('POST', sinkUrl());
    x.send(line);
  } catch (e) {
    /* no sink */
  }
}

function safeJson(a: unknown): string {
  if (a instanceof Error) return a.name + ': ' + a.message;
  try {
    return JSON.stringify(a);
  } catch (e) {
    return String(a);
  }
}

export function installErrorHooks(): void {
  window.onerror = (m, src, l, c) => {
    log('ERROR', String(m), String(src) + ':' + l + ':' + c);
  };
  window.addEventListener('unhandledrejection', (e: PromiseRejectionEvent) => {
    const r = e.reason;
    log('ERROR unhandled rejection', r && r.message ? r.message : String(r));
  });
}
