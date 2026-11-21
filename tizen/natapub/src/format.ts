// Title and time helpers.

export { formatMinutes, plural } from './ui/strings';

/** kino.pub titles are "Русское / Original". Returns the two parts (second may be null). */
export function splitTitle(title: string): [string, string | null] {
  const i = title.indexOf(' / ');
  if (i < 0) return [title, null];
  const orig = title.substring(i + 3).trim();
  return [title.substring(0, i), orig || null];
}

const latin = /[A-Za-z]/;

/**
 * The title to show (owner, 2026-11-21: English wherever there is one): split on " / ", take the
 * part with Latin letters, else the whole string. Used for items and episode titles alike.
 */
export function displayTitle(raw: string | null | undefined): string {
  if (!raw) return '';
  const parts = raw.split(' / ').map((p) => p.trim()).filter(Boolean);
  if (parts.length < 2) return raw;
  const en = parts.filter((p) => latin.test(p))[0];
  return en || raw;
}

/** The other half of a "Русское / Original" title (the one displayTitle didn't pick), or null. */
export function otherTitle(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const shown = displayTitle(raw);
  if (shown === raw) return null;
  const rest = raw.split(' / ').map((p) => p.trim()).filter((p) => p && p !== shown);
  return rest.length ? rest.join(' / ') : null;
}

const pad2 = (n: number) => (n < 10 ? '0' : '') + n;

export function clock(ms: number): string {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return h > 0 ? h + ':' + pad2(m) + ':' + pad2(sec) : m + ':' + pad2(sec);
}

export function clockTenths(ms: number): string {
  return clock(ms) + '.' + Math.floor((ms % 1000) / 100);
}
