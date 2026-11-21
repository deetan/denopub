// Russian copy helpers, ported from ui/common/Cards.kt.

export function formatMinutes(sec: number): string {
  const m = Math.floor(sec / 60);
  return m >= 60 ? Math.floor(m / 60) + ' ч ' + (m % 60) + ' мин' : m + ' мин';
}

/** kino.pub titles are "Русское / Original". */
export function splitTitle(title: string): [string, string | null] {
  const i = title.indexOf(' / ');
  if (i < 0) return [title, null];
  const orig = title.substring(i + 3).trim();
  return [title.substring(0, i), orig || null];
}

/** plural(4, "серия", "серии", "серий") → "серии". */
export function plural(n: number, one: string, few: string, many: string): string {
  const m100 = n % 100;
  const m10 = n % 10;
  if (m100 >= 11 && m100 <= 14) return many;
  if (m10 === 1) return one;
  if (m10 >= 2 && m10 <= 4) return few;
  return many;
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
  return clock(ms) + ',' + Math.floor((ms % 1000) / 100);
}

export function fixed1(n: number): string {
  return n.toFixed(1);
}
