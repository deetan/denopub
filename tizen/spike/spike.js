// denopub Tizen playback spike. Chrome 63: no optional chaining / nullish coalescing.
'use strict';

var SINK = 'http://192.168.0.39:8765/log';   // Mac log sink (scratchpad sink.py)
var API = 'https://kino.watch/api/v1/';
var OAUTH = 'https://kino.watch/api/oauth2/device';
var TOKEN = 'https://kino.watch/api/oauth2/token';
var CLIENT = 'client_id=xbmc&client_secret=cgg3gtifu46urtfp2zp1nqtba0k2ezxh';
var K = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13, BACK: 10009, PLAYPAUSE: 10252, PLAY: 415, PAUSE: 19,
          FF: 417, RW: 412, CHUP: 427, CHDOWN: 428 };

var $ = function (id) { return document.getElementById(id); };
var logLines = [];
function log() {
  var s = Array.prototype.map.call(arguments, function (a) {
    return typeof a === 'string' ? a : JSON.stringify(a);
  }).join(' ');
  var line = new Date().toISOString().substr(11, 12) + ' ' + s;
  logLines.push(line); if (logLines.length > 18) logLines.shift();
  $('log').textContent = logLines.join('\n');
  try { var x = new XMLHttpRequest(); x.open('POST', SINK); x.send(line); } catch (e) {}
}
window.onerror = function (m, src, l, c) { log('ERROR', m, src + ':' + l + ':' + c); };

// ---------- HTTP ----------
function req(method, url, body, headers) {
  return new Promise(function (ok, fail) {
    var x = new XMLHttpRequest();
    x.open(method, url);
    Object.keys(headers || {}).forEach(function (h) { x.setRequestHeader(h, headers[h]); });
    x.timeout = 20000;
    x.onload = function () { ok({ status: x.status, text: x.responseText }); };
    x.onerror = function () { fail(new Error('network error ' + url)); };
    x.ontimeout = function () { fail(new Error('timeout ' + url)); };
    x.send(body || null);
  });
}
function form(url, body) {
  return req('POST', url, body, { 'Content-Type': 'application/x-www-form-urlencoded' })
    .then(function (r) { return JSON.parse(r.text); });
}
function token() { try { return JSON.parse(localStorage.getItem('tok') || 'null'); } catch (e) { return null; } }
function api(path) {
  var t = token();
  var refresh = (t && Date.now() > t.exp - 60000)
    ? form(TOKEN, 'grant_type=refresh_token&refresh_token=' + encodeURIComponent(t.refresh) + '&' + CLIENT)
        .then(function (r) { if (!r.access_token) throw new Error('refresh failed'); saveTok(r); })
    : Promise.resolve();
  return refresh.then(function () {
    return req('GET', API + path, null, { Authorization: 'Bearer ' + token().access });
  }).then(function (r) {
    if (r.status !== 200) throw new Error('HTTP ' + r.status + ' ' + path + ' ' + r.text.substr(0, 120));
    return JSON.parse(r.text);
  });
}
function saveTok(r) {
  var old = token() || {};
  localStorage.setItem('tok', JSON.stringify({
    access: r.access_token, refresh: r.refresh_token || old.refresh, exp: Date.now() + r.expires_in * 1000 }));
}

// ---------- Step 0: environment + cross-origin probes ----------
function probes() {
  log('UA', navigator.userAgent);
  log('screen', screen.width + 'x' + screen.height, 'avplay', !!(window.webapis && webapis.avplay),
      'MSE', !!window.MediaSource, 'Hls.isSupported', !!(window.Hls && Hls.isSupported()));
  try { log('tizen version', tizen.systeminfo.getCapability('http://tizen.org/feature/platform.version')); } catch (e) {}
  var p1 = req('POST', 'https://caching.graphql.imdb.com/', JSON.stringify({
    query: '{ title(id: "tt0903747") { titleText { text } } }' }), { 'Content-Type': 'application/json' })
    .then(function (r) { log('CORS imdb graphql', r.status, r.text.substr(0, 80)); })
    .catch(function (e) { log('CORS imdb graphql FAIL', e.message); });
  var p2 = req('GET', 'https://den.tanaev.su/movies/api/recommendations?kind=movie&limit=1', null,
    { 'x-imdb-user': 'ur0218725' })
    .then(function (r) { log('CORS movies service', r.status, r.text.substr(0, 80)); })
    .catch(function (e) { log('CORS movies service FAIL', e.message); });
  return Promise.all([p1, p2]);
}

// ---------- Step 1: device-code login ----------
function login() {
  if (token()) return Promise.resolve();
  return form(OAUTH, 'grant_type=device_code&' + CLIENT).then(function (d) {
    log('CORS kino.watch device_code OK');
    $('ui').innerHTML = '<div>Open <b>' + d.verification_uri + '</b> and enter:</div><div id="code">' +
      d.user_code + '</div>';
    return new Promise(function (ok, fail) {
      var t = setInterval(function () {
        form(OAUTH, 'grant_type=device_token&code=' + encodeURIComponent(d.code) + '&' + CLIENT).then(function (r) {
          if (r.access_token) { clearInterval(t); saveTok(r); log('logged in'); ok(); }
          else if (r.error !== 'authorization_pending' && r.error !== 'slow_down') { clearInterval(t); fail(new Error(r.error)); }
        }).catch(function (e) { log('poll', e.message); });
      }, Math.max(5, d.interval || 5) * 1000);
    });
  });
}

// ---------- Generic focus menu ----------
var menu = null;
function showMenu(title, items, onPick) {
  menu = { items: items, i: 0, onPick: onPick };
  $('ui').style.display = '';
  $('ui').innerHTML = '<div><b>' + title + '</b></div><div class="menu" id="menu"></div>';
  drawMenu();
}
function drawMenu() {
  $('menu').innerHTML = menu.items.map(function (it, i) {
    return '<div class="' + (i === menu.i ? 'sel' : '') + '">' + it.label + '</div>';
  }).join('');
}

// ---------- Step 2: pick a title / file / engine ----------
function titles() {
  return Promise.all([
    api('watching/serials?subscribed=1').catch(function () { return { items: [] }; }),
    api('watching/movies').catch(function () { return { items: [] }; }),
  ]).then(function (rs) {
    var items = rs[0].items.slice(0, 6).map(function (s) { return { label: 'Serial: ' + s.title, id: s.id }; })
      .concat(rs[1].items.slice(0, 6).map(function (m) { return { label: 'Movie: ' + m.title, id: m.id }; }));
    showMenu('Pick a title', items, function (it) { openItem(it.id); });
  });
}
var current = null;
function openItem(id) {
  api('items/' + id).then(function (r) {
    var item = r.item;
    var v = item.seasons && item.seasons.length ? item.seasons[0].episodes[0] : item.videos[0];
    var files = v.files.slice().sort(function (a, b) { return b.h - a.h; });
    log('item', item.title, 'audios', v.audios.length, 'subs', v.subtitles.length,
        'files', files.map(function (f) { return f.quality + '/' + f.codec + ' ' + f.w + 'x' + f.h; }).join(', '));
    v.audios.forEach(function (a) {
      log('  audio', a.index, a.lang, a.codec, a.channels, a.type && a.type.title, a.author && a.author.title);
    });
    current = { item: item, video: v, files: files };
    var opts = [];
    files.forEach(function (f) {
      var tag = f.quality + ' ' + f.codec + ' ' + f.w + 'x' + f.h;
      if (f.url.hls4) {
        opts.push({ label: 'AVPlay hls4 ' + tag, engine: 'avplay', url: f.url.hls4, f: f });
        opts.push({ label: 'hls.js hls4 ' + tag, engine: 'hlsjs', url: f.url.hls4, f: f });
      }
      if (f.url.hls) opts.push({ label: 'AVPlay hls ' + tag, engine: 'avplay', url: f.url.hls, f: f });
    });
    showMenu(item.title + ' — pick a stream', opts, play);
  }).catch(function (e) { log('item fail', e.message); });
}

// ---------- Step 3: playback ----------
var P = null;   // { engine, ... }
var subIdx = -1;

function hud() {
  if (!P) { $('hud').style.display = 'none'; return; }
  $('hud').style.display = '';
  var lines = [P.label, 'time ' + fmt(P.time()) + ' / ' + fmt(P.duration()) + (P.paused ? '  [paused]' : '')];
  var tr = P.audioTracks();
  lines.push('audio (↑ / CH▲ next): ' + tr.length + ' tracks');
  tr.forEach(function (t, i) { lines.push((i === P.audioSel() ? ' ▶ ' : '   ') + t); });
  lines.push('subs (↓ / CH▼ next): ' + P.textLabel());
  lines.push('← → seek ∓30 s · OK pause · Back stop');
  if (P.lastSeek) lines.push('last seek: target ' + fmt(P.lastSeek.to) + ' landed ' + fmt(P.lastSeek.got));
  $('hud').textContent = lines.join('\n');
}
function fmt(ms) { var s = Math.floor(ms / 1000); return Math.floor(s / 60) + ':' + ('0' + s % 60).slice(-2); }

function play(opt) {
  $('ui').style.display = 'none';
  menu = null;
  log('play', opt.engine, opt.url);
  P = opt.engine === 'avplay' ? avplayEngine(opt) : hlsjsEngine(opt);
  P.label = opt.label;
  P.tick = setInterval(hud, 500);
}
function stop() {
  if (!P) return;
  clearInterval(P.tick);
  try { P.stop(); } catch (e) { log('stop err', e.message); }
  P = null; $('sub').textContent = ''; hud();
  openItem(current.item.id);
}

function avplayEngine(opt) {
  var av = webapis.avplay;
  var e = { paused: false };
  av.open(opt.url);
  av.setDisplayRect(0, 0, 1920, 1080);
  if (opt.f.h >= 2160 || opt.f.w >= 3840) {
    try { av.setStreamingProperty('SET_MODE_4K', 'TRUE'); log('SET_MODE_4K ok'); } catch (x) { log('SET_MODE_4K fail', x.message); }
  }
  av.setListener({
    onbufferingstart: function () { log('avplay buffering…'); },
    onbufferingcomplete: function () { log('avplay buffered'); },
    onstreamcompleted: function () { log('avplay completed'); },
    onerror: function (t) { log('avplay ERROR', t); },
    onevent: function (t, d) { log('avplay event', t, d); },
    onsubtitlechange: function (dur, text) { $('sub').textContent = text; setTimeout(function () { $('sub').textContent = ''; }, dur); },
  });
  var t0 = Date.now();
  av.prepareAsync(function () {
    log('avplay prepared in', Date.now() - t0, 'ms; duration', av.getDuration());
    try {
      var info = av.getTotalTrackInfo();
      for (var i = 0; i < info.length; i++) log('  track', info[i].index, info[i].type, info[i].extra_info);
    } catch (x) { log('getTotalTrackInfo fail', x.message); }
    av.play();
    log('avplay play; first frame after', Date.now() - t0, 'ms (approx)');
  }, function (err) { log('avplay prepare FAIL', err && (err.name + ' ' + err.message)); });
  function audio() {
    try {
      return av.getTotalTrackInfo().filter(function (t) { return t.type === 'AUDIO'; });
    } catch (x) { return []; }
  }
  e.time = function () { try { return av.getCurrentTime(); } catch (x) { return 0; } };
  e.duration = function () { try { return av.getDuration(); } catch (x) { return 0; } };
  e.audioTracks = function () { return audio().map(function (t) { return '#' + t.index + ' ' + t.extra_info; }); };
  e.audioSel = function () {
    try {
      var cur = av.getCurrentStreamInfo().filter(function (s) { return s.type === 'AUDIO'; })[0];
      var list = audio();
      for (var i = 0; i < list.length; i++) if (cur && list[i].index === cur.index) return i;
    } catch (x) {}
    return -1;
  };
  e.nextAudio = function () {
    var list = audio(); if (!list.length) return;
    var n = list[(e.audioSel() + 1) % list.length];
    var t0 = Date.now();
    try { av.setSelectTrack('AUDIO', n.index); log('audio -> #' + n.index, 'in', Date.now() - t0, 'ms'); }
    catch (x) { log('setSelectTrack fail', x.name, x.message); }
  };
  e.seek = function (delta) {
    var to = Math.max(0, e.time() + delta);
    av.seekTo(to, function () {
      setTimeout(function () { e.lastSeek = { to: to, got: e.time() }; log('seek', fmt(to), '->', fmt(e.lastSeek.got)); }, 400);
    }, function (x) { log('seek fail', x && x.message); });
  };
  e.toggle = function () { if (e.paused) av.play(); else av.pause(); e.paused = !e.paused; };
  var textSel = -1;
  e.nextText = function () {
    var list = []; try { list = av.getTotalTrackInfo().filter(function (t) { return t.type === 'TEXT'; }); } catch (x) {}
    textSel = textSel + 1 >= list.length ? -1 : textSel + 1;
    try {
      if (textSel < 0) { av.setSilentSubtitle(true); log('avplay subs off'); return; }
      av.setSilentSubtitle(false);
      av.setSelectTrack('TEXT', list[textSel].index);
      log('avplay TEXT ->', list[textSel].index, list[textSel].extra_info);
    } catch (x) { log('avplay TEXT fail', x.name, x.message); }
  };
  e.textLabel = function () { return textSel < 0 ? 'off' : 'TEXT #' + textSel; };
  e.stop = function () { av.stop(); av.close(); };
  return e;
}

function hlsjsEngine(opt) {
  var v = $('vid'); v.style.display = 'block';
  var e = { paused: false };
  var hls = new Hls({ maxBufferLength: 30 });
  var t0 = Date.now();
  hls.on(Hls.Events.MANIFEST_PARSED, function (_, d) {
    log('hls.js manifest: levels', d.levels.map(function (l) { return l.width + 'x' + l.height + ' ' + l.videoCodec; }).join(', '));
    log('hls.js subtitleTracks', hls.subtitleTracks.map(function (t) { return t.id + ':' + t.name + ':' + t.lang; }).join(' | '));
    log('hls.js audioTracks', hls.audioTracks.map(function (t) { return t.id + ':' + t.groupId + ':' + t.name; }).join(' | '));
    hls.currentLevel = 0;  // highest is first? log tells
    v.play();
  });
  hls.on(Hls.Events.ERROR, function (_, d) { log('hls.js ERROR', d.type, d.details, d.fatal); });
  v.addEventListener('playing', function once() { log('hls.js first frame after', Date.now() - t0, 'ms'); v.removeEventListener('playing', once); });
  hls.loadSource(opt.url);
  hls.attachMedia(v);
  e.time = function () { return v.currentTime * 1000; };
  e.duration = function () { return (v.duration || 0) * 1000; };
  e.audioTracks = function () { return hls.audioTracks.map(function (t) { return t.groupId + ' ' + t.name + ' ' + (t.lang || ''); }); };
  e.audioSel = function () { return hls.audioTrack; };
  e.nextAudio = function () {
    if (!hls.audioTracks.length) return;
    hls.audioTrack = (hls.audioTrack + 1) % hls.audioTracks.length; log('hls.js audio ->', hls.audioTrack);
  };
  e.seek = function (delta) {
    var to = Math.max(0, e.time() + delta);
    v.currentTime = to / 1000;
    v.addEventListener('seeked', function once() { e.lastSeek = { to: to, got: e.time() }; log('seek', fmt(to), '->', fmt(e.lastSeek.got)); v.removeEventListener('seeked', once); });
  };
  e.toggle = function () { if (v.paused) v.play(); else v.pause(); e.paused = v.paused; };
  e.nextText = function () {
    var n = hls.subtitleTracks.length;
    var cur = hls.subtitleTrack;
    var next = cur + 1 >= n ? -1 : cur + 1;
    hls.subtitleDisplay = true;
    hls.subtitleTrack = next;
    log('hls.js subs ->', next, n ? (next >= 0 ? hls.subtitleTracks[next].name + ' ' + hls.subtitleTracks[next].lang : '') : 'no subtitle tracks');
  };
  e.textLabel = function () {
    var i = hls.subtitleTrack; return i < 0 ? 'off (' + hls.subtitleTracks.length + ' tracks)' : hls.subtitleTracks[i].name;
  };
  e.stop = function () { hls.destroy(); v.removeAttribute('src'); v.load(); v.style.display = 'none'; };
  return e;
}

// ---------- Keys ----------
try {
  ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaFastForward', 'MediaRewind', 'ChannelUp', 'ChannelDown'].forEach(function (k) {
    tizen.tvinputdevice.registerKey(k);
  });
} catch (e) { log('registerKey fail', e.message); }

window.addEventListener('keydown', function (ev) { log('raw keydown', ev.keyCode, ev.key, 'target', ev.target && ev.target.tagName); }, true);
window.addEventListener('keyup', function (ev) { log('raw keyup', ev.keyCode); }, true);
document.addEventListener('keydown', function (ev) {
  var k = ev.keyCode;
  if (P) {
    if (k === K.LEFT || k === K.RW) P.seek(-30000);
    else if (k === K.RIGHT || k === K.FF) P.seek(30000);
    else if (k === K.UP || k === K.CHUP) P.nextAudio();
    else if (k === K.DOWN || k === K.CHDOWN) P.nextText();
    else if (k === K.OK || k === K.PLAYPAUSE || k === K.PLAY || k === K.PAUSE) P.toggle();
    else if (k === K.BACK) stop();
    else log('key', k);
    hud(); ev.preventDefault(); return;
  }
  if (menu) {
    if (k === K.UP) menu.i = Math.max(0, menu.i - 1);
    else if (k === K.DOWN) menu.i = Math.min(menu.items.length - 1, menu.i + 1);
    else if (k === K.OK) { menu.onPick(menu.items[menu.i]); return; }
    else if (k === K.BACK) { titles(); return; }
    drawMenu(); ev.preventDefault();
  } else if (k === K.BACK) {
    try { tizen.application.getCurrentApplication().exit(); } catch (e) {}
  }
});

// ---------- Start ----------
log('spike start');
probes().then(login).then(titles).catch(function (e) { log('FAIL', e.message); });
