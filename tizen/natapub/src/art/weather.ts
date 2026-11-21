// Port of Weather.kt: Open-Meteo forecast (keyless), air from nearby Sensor.Community sensors
// (trimmed mean, humidity-corrected, US AQI) with the CAMS model as fallback, home = `HOME`
// (New Belgrade, not Android's Palilula) unless ipwho.is (once a day) puts the TV more than 30 km away. Cached in localStorage.
import { log } from '../log';
import { S } from '../ui/strings';

export interface WeatherSlot {
  label: string;
  code: number; // WMO weather code
  temp: number; // °C
  cloud: number; // %
  precipProb: number | null; // %
  precipMm: number;
  day: boolean;
}

export interface Forecast {
  now: WeatherSlot;
  hours: WeatherSlot[]; // in 3 and 6 hours
  tomorrow: WeatherSlot[]; // 09:00, 14:00, 19:00
  place: string | null; // shown only when not the home location
  fetchedAt: number;
  windMs: number | null;
  gustMs: number | null;
  windFrom: number | null; // degrees the wind blows FROM
  aqi: number | null; // US AQI
  pm25: number | null;
  humidity?: number | null; // % now (missing in caches written before it existed)
  lat?: number;
  lon?: number;
}

/**
 * Natapub's home: New Belgrade, Ledine / Block 44 (owner 2026-11-21: the Samsung TV is not where
 * the Chromecast is; Android keeps Palilula). Rounded to ~1 km on purpose: the repo is public.
 */
export const HOME = { lat: 44.8, lon: 20.38 };
const HOME_LAT = HOME.lat;
const HOME_LON = HOME.lon;
// v2: the v1 caches still held Android's Palilula (the location is cached for a day).
const K_CACHE = 'natapub.weather.v2';
const K_LOC = 'natapub.weather.loc.v2';
const DEV = import.meta.env.DEV;
// Desktop Chrome enforces CORS; the dev server proxies the APIs that don't send the headers.
const SENSORS = DEV ? '/sc/airrohr/v1/filter/area=' : 'https://data.sensor.community/airrohr/v1/filter/area=';

function fetchJson(url: string): Promise<any> {
  return new Promise((ok, fail) => {
    const x = new XMLHttpRequest();
    x.open('GET', url);
    x.timeout = 20000;
    x.onload = () => {
      if (x.status < 200 || x.status > 299) return fail(new Error('HTTP ' + x.status + ' for ' + url.split('?')[0]));
      try {
        ok(JSON.parse(x.responseText));
      } catch (e) {
        fail(new Error('bad JSON from ' + url.split('?')[0]));
      }
    };
    x.onerror = () => fail(new Error('network error for ' + url.split('?')[0]));
    x.ontimeout = () => fail(new Error('timeout for ' + url.split('?')[0]));
    x.send();
  });
}

function readJson<T>(key: string): T | null {
  try {
    return JSON.parse(localStorage.getItem(key) || 'null') as T | null;
  } catch (e) {
    return null;
  }
}

function writeJson(key: string, v: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(v));
  } catch (e) {
    /* quota */
  }
}

try {
  localStorage.removeItem('natapub.weather'); // v1 caches (Palilula)
  localStorage.removeItem('natapub.weather.loc');
} catch (e) {
  /* none */
}
let cached: Forecast | null = readJson<Forecast>(K_CACHE);

/** Last known forecast at once (may be stale), for the first frame. */
export function lastForecast(): Forecast | null {
  return cached;
}

export function distanceKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const dx = (lon1 - lon2) * 111.32 * Math.cos((((lat1 + lat2) / 2) * Math.PI) / 180);
  const dy = (lat1 - lat2) * 110.57;
  return Math.sqrt(dx * dx + dy * dy);
}

interface Loc { lat: number; lon: number; place: string | null; at: number }

/** HOME unless the IP says we are elsewhere; looked up at most once a day. */
async function location(): Promise<Loc> {
  const now = Date.now();
  const l = readJson<Loc>(K_LOC);
  // A cached "home" from before HOME moved is not home any more.
  const stale = l && !l.place && (l.lat !== HOME_LAT || l.lon !== HOME_LON);
  if (l && !stale && now - l.at < 24 * 3600 * 1000) return l;
  let r: Loc = { lat: HOME_LAT, lon: HOME_LON, place: null, at: now };
  try {
    const o = await fetchJson('https://ipwho.is/');
    if (o.success !== true) throw new Error('ipwho.is: ' + o.message);
    const km = distanceKm(o.latitude, o.longitude, HOME_LAT, HOME_LON);
    log('weather: ip says', o.city, Math.round(km), 'km from home');
    if (km > 30) r = { lat: o.latitude, lon: o.longitude, place: o.city || null, at: now };
  } catch (e) {
    log('weather: ip lookup failed:', (e as Error).message);
  }
  writeJson(K_LOC, r);
  return r;
}

/**
 * Low-cost laser sensors count water-swollen particles as dust when the air is humid. κ-Köhler
 * correction (Di Antonio et al. 2018, SDS011): PM / (1 + (κ/1.65) / (100/RH − 1)), κ = 0.62, RH
 * capped at 95 %. No humidity known → reading as is.
 */
export function humidityCorrected(pm: number, rh: number | null): number {
  if (rh == null) return pm;
  const h = Math.min(95, Math.max(0, rh));
  const out = pm / (1 + 0.62 / 1.65 / (100 / h - 1));
  log('weather: PM2.5', pm.toFixed(1), 'at RH', Math.round(rh) + '% ->', out.toFixed(1));
  return out;
}

/** US AQI from PM2.5 (EPA breakpoints, 2024 revision). */
export function usAqi(pm: number): number {
  const c = Math.floor(pm * 10) / 10;
  const bp: [number, number, number, number][] = [
    [0.0, 9.0, 0, 50], [9.1, 35.4, 51, 100], [35.5, 55.4, 101, 150],
    [55.5, 125.4, 151, 200], [125.5, 225.4, 201, 300], [225.5, 325.4, 301, 500],
  ];
  const b = bp.filter((x) => c <= x[1])[0];
  if (!b) return 500;
  return Math.round(((b[3] - b[2]) / (b[1] - b[0])) * (c - b[0]) + b[2]);
}

/** Arrow showing where the wind blows TO (meteorological direction is where it comes FROM). */
export function windArrow(from: number): string {
  return ['↑', '↗', '→', '↘', '↓', '↙', '←', '↖'][Math.floor((((from + 180) % 360) + 22) / 45) % 8];
}

/** US AQI band → short English word and the usual colour. */
export function aqiLevel(aqi: number): [string, string] {
  if (aqi <= 50) return [S.aqiGood, '#00E400'];
  if (aqi <= 100) return [S.aqiModerate, '#FFFF00'];
  if (aqi <= 150) return [S.aqiSensitive, '#FF7E00'];
  if (aqi <= 200) return [S.aqiUnhealthy, '#FF0000'];
  if (aqi <= 300) return [S.aqiVeryUnhealthy, '#8F3F97'];
  return [S.aqiHazardous, '#7E0023'];
}

/** Emoji for a slot: rain/snow/storm/fog from the WMO code, otherwise by cloud cover. */
export function icon(s: WeatherSlot): string {
  switch (s.code) {
    case 95: case 96: case 99: return '⛈';
    case 71: case 73: case 75: case 77: case 85: case 86: return '🌨';
    case 51: case 53: case 55: case 56: case 57: return '🌦';
    case 61: case 63: case 65: case 66: case 67: case 80: case 81: case 82: return '🌧';
    case 45: case 48: return '🌫';
    default:
      if (s.cloud < 20) return s.day ? '☀️' : '🌙';
      if (s.cloud < 50) return s.day ? '🌤' : '🌙';
      if (s.cloud < 85) return '⛅';
      return '☁️';
  }
}

/**
 * PM2.5 of the outdoor Sensor.Community sensors within 3 km (newest reading per sensor),
 * figure-skating style: drop the lowest and the highest, average the rest. Null with < 3 sensors.
 */
async function groundPm25(lat: number, lon: number): Promise<number | null> {
  const q = lat + ',' + lon + ',3';
  // The 2019 TV doesn't trust Let's Encrypt's newer ECDSA chain (YE1) that sensor.community now
  // serves: "network error" over https. The data is public, so plain http is the fallback.
  const arr = (await fetchJson(SENSORS + q).catch((e: Error) => {
    if (DEV || !/network/.test(e.message)) throw e;
    return fetchJson(SENSORS.replace('https:', 'http:') + q);
  })) as any[];
  const latest: Record<string, [string, number]> = {};
  arr.forEach((r) => {
    if (r.location && Number(r.location.indoor) === 1) return;
    const id = r.sensor && r.sensor.id != null ? String(r.sensor.id) : null;
    const ts = r.timestamp as string | undefined;
    if (!id || !ts) return;
    const p2 = (r.sensordatavalues || []).filter((x: any) => x.value_type === 'P2')[0];
    const v = p2 ? parseFloat(p2.value) : NaN;
    if (isNaN(v) || v < 0 || v > 1000) return;
    if (!latest[id] || latest[id][0] < ts) latest[id] = [ts, v];
  });
  const vals = Object.keys(latest).map((k) => latest[k][1]).sort((a, b) => a - b);
  if (vals.length < 3) {
    log('weather: only', vals.length, 'sensors nearby, using the model');
    return null;
  }
  const mid = vals.slice(1, vals.length - 1);
  const m = mid.reduce((a, b) => a + b, 0) / mid.length;
  log('weather:', vals.length, 'sensors PM2.5', vals.map((v) => v.toFixed(1)).join(', '), '-> trimmed mean', m.toFixed(1));
  return m;
}

/** "2026-11-21" + 1 day, on the date string itself (Open-Meteo times are local and offset-less). */
function nextDate(d: string): string {
  const p = d.split('-').map(Number);
  return new Date(Date.UTC(p[0], p[1] - 1, p[2] + 1)).toISOString().substr(0, 10);
}

const TOMORROW: [string, string][] = [['09', S.wxMorning], ['14', S.wxAfternoon], ['19', S.wxEvening]];

function parse(o: any, place: string | null): Forecast {
  const cur = o.current;
  const h = o.hourly;
  const times = h.time as string[]; // "2026-11-21T14:00", local time
  const num = (k: string, i: number) => (h[k] && h[k][i] != null ? Number(h[k][i]) : null);
  const slot = (i: number, label: string): WeatherSlot => ({
    label,
    code: num('weather_code', i) || 0,
    temp: Math.round(num('temperature_2m', i) || 0),
    cloud: num('cloud_cover', i) || 0,
    precipProb: num('precipitation_probability', i),
    precipMm: num('precipitation', i) || 0,
    day: num('is_day', i) === 1,
  });
  const curTime = String(cur.time);
  const iNow = Math.max(0, times.indexOf(curTime.substr(0, 13) + ':00'));
  const now: WeatherSlot = {
    label: S.wxNow,
    code: Number(cur.weather_code),
    temp: Math.round(Number(cur.temperature_2m)),
    cloud: Number(cur.cloud_cover),
    precipProb: num('precipitation_probability', iNow),
    precipMm: Number(cur.precipitation) || 0,
    day: Number(cur.is_day) === 1,
  };
  const hours: WeatherSlot[] = [];
  [3, 6].forEach((d) => {
    const i = iNow + d;
    if (i < times.length) hours.push(slot(i, times[i].substr(11, 5)));
  });
  const tomorrowDate = nextDate(curTime.substr(0, 10));
  const tomorrow: WeatherSlot[] = [];
  TOMORROW.forEach((t) => {
    const i = times.indexOf(tomorrowDate + 'T' + t[0] + ':00');
    if (i >= 0) tomorrow.push(slot(i, t[1]));
  });
  const n = (v: unknown) => (v == null || isNaN(Number(v)) ? null : Number(v));
  return {
    now, hours, tomorrow, place, fetchedAt: Date.now(),
    windMs: n(cur.wind_speed_10m),
    gustMs: n(cur.wind_gusts_10m),
    windFrom: cur.wind_direction_10m == null ? null : Math.round(Number(cur.wind_direction_10m)),
    aqi: null,
    pm25: null,
  };
}

let inFlight: Promise<Forecast | null> | null = null;

/** Fresh forecast (refreshed at most every 15 min), or the cached one when the network fails. */
export function getForecast(): Promise<Forecast | null> {
  if (cached && Date.now() - cached.fetchedAt < 15 * 60000) return Promise.resolve(cached);
  if (inFlight) return inFlight;
  inFlight = (async () => {
    try {
      const loc = await location();
      const o = await fetchJson('https://api.open-meteo.com/v1/forecast?latitude=' + loc.lat + '&longitude=' + loc.lon +
        '&current=temperature_2m,relative_humidity_2m,precipitation,cloud_cover,weather_code,is_day,wind_speed_10m,wind_direction_10m,wind_gusts_10m' +
        '&wind_speed_unit=ms' +
        '&hourly=temperature_2m,precipitation_probability,precipitation,cloud_cover,weather_code,is_day' +
        '&timezone=auto&forecast_days=3');
      // Air quality is a separate API; a failure there must not lose the forecast.
      let air: any = null;
      try {
        air = (await fetchJson('https://air-quality-api.open-meteo.com/v1/air-quality?latitude=' + loc.lat + '&longitude=' + loc.lon +
          '&current=us_aqi,pm2_5&timezone=auto')).current;
      } catch (e) {
        log('weather: air quality:', (e as Error).message);
      }
      const rh = o.current && o.current.relative_humidity_2m != null ? Number(o.current.relative_humidity_2m) : null;
      let ground: number | null = null;
      try {
        const g = await groundPm25(loc.lat, loc.lon);
        ground = g == null ? null : humidityCorrected(g, rh);
      } catch (e) {
        log('weather: sensors:', (e as Error).message);
      }
      const f = parse(o, loc.place);
      f.humidity = rh == null ? null : Math.round(rh);
      f.lat = loc.lat;
      f.lon = loc.lon;
      f.pm25 = ground != null ? ground : air && air.pm2_5 != null ? Number(air.pm2_5) : null;
      f.aqi = ground != null ? usAqi(ground) : air && air.us_aqi != null ? Math.round(Number(air.us_aqi)) : null;
      cached = f;
      writeJson(K_CACHE, f);
      log('weather:', loc.lat + ',' + loc.lon, loc.place || 'home', f.now.temp + '° code', f.now.code, 'rh', f.humidity, 'rain', f.now.precipProb + '%',
        'wind', f.windMs, 'aqi', f.aqi, 'pm2.5', f.pm25 == null ? null : f.pm25.toFixed(1), ground != null ? '(sensors)' : '(model)');
      return f;
    } catch (e) {
      log('weather:', (e as Error).message);
      return cached;
    } finally {
      inFlight = null;
    }
  })();
  return inFlight;
}
