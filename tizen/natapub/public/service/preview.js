// Natapub's row on the TV's home bar (Samsung Smart Hub Preview): a short-lived web service in the
// same package, started two ways:
//  - by Natapub (src/preview.ts) after Home loads, with the tiles as app-control data "tiles"
//    (caller "ForegroundApp"): kept in the package's data folder, then set;
//  - by the TV on its own (about every 10 minutes, or never — it varies): the tiles kept last time
//    are set again.
// It calls webapis.preview.setPreviewData and exits, as Samsung asks. Samsung may silently ignore
// calls less than 10 minutes apart; the app throttles its own sends.
// ES5 and Node core modules only (Tizen 5's Node is old). Logs go to the Mac's sink like the app's.
var http = null;
var fs = null;
try { http = require('http'); } catch (e) {}
try { fs = require('fs'); } catch (e) {}
var SINK = { host: '192.168.0.39', port: 8765, path: '/log' };
var TILES_FILE = 'preview-tiles.json';
var TILES_MAX = 65536;

function slog(s) {
  try { console.log('preview-svc ' + s); } catch (e) {}
  if (!http) return;
  try {
    var body = new Date().toISOString().substr(11, 12) + ' preview-svc: ' + s;
    var rq = http.request({ host: SINK.host, port: SINK.port, path: SINK.path, method: 'POST',
      headers: { 'Content-Length': Buffer.byteLength(body) } });
    rq.on('error', function () {});
    rq.end(body);
  } catch (e) {}
}

// Where the tiles are kept between runs. The package's own data folder is the natural place, but
// its path differs between firmwares (/opt/usr/apps/<pkg>/data did not exist on the 2019 TV), so
// a few candidates are tried in order; the first one that takes the write wins.
function dirs() {
  var r = [];
  try {
    if (typeof __filename === 'string') {
      var i = __filename.indexOf('/res/wgt/');
      if (i !== -1) r.push(__filename.slice(0, i) + '/data', __filename.slice(0, i) + '/shared/data');
    }
  } catch (e) {}
  r.push('/opt/usr/home/owner/apps_rw/natapubTV1/data');
  try { if (process.env.HOME) r.push(process.env.HOME + '/natapub'); } catch (e) {}
  try { r.push(require('os').tmpdir() + '/natapub'); } catch (e) {}
  return r;
}

function keep(json) {
  var ds = dirs();
  var errs = [];
  for (var k = 0; k < ds.length; k++) {
    try {
      try { fs.mkdirSync(ds[k]); } catch (e) {}
      fs.writeFileSync(ds[k] + '/' + TILES_FILE, json);
      slog('kept ' + json.length + ' bytes in ' + ds[k]);
      return;
    } catch (e) { errs.push(ds[k] + ': ' + e.code); }
  }
  slog('keep failed everywhere: ' + errs.join(', '));
}

function restore() {
  var ds = dirs();
  for (var k = 0; k < ds.length; k++) {
    try {
      var j = String(fs.readFileSync(ds[k] + '/' + TILES_FILE, 'utf8'));
      slog('restored ' + j.length + ' bytes from ' + ds[k]);
      return j;
    } catch (e) {}
  }
  return null;
}

var exitTimer = null;
function exitSoon() {
  // A moment for the last log post to leave; a request arriving meanwhile re-arms it.
  if (exitTimer) clearTimeout(exitTimer);
  exitTimer = setTimeout(function () { try { tizen.application.getCurrentApplication().exit(); } catch (e) {} }, 1500);
}

// Every request is handled: Home can send twice within a second (load, then kids flags), and the
// second lands in this same process.
function run() {
  if (exitTimer) { clearTimeout(exitTimer); exitTimer = null; }
  var json = null;
  var fromApp = false;
  try {
    var req = tizen.application.getCurrentApplication().getRequestedAppControl();
    var data = (req && req.appControl && req.appControl.data) || [];
    var caller = '';
    var tiles = null;
    data.forEach(function (d) {
      if (d.key === 'caller') caller = d.value && d.value[0];
      if (d.key === 'tiles') tiles = d.value && d.value[0];
    });
    if (caller === 'ForegroundApp' && typeof tiles === 'string' && tiles.length <= TILES_MAX) {
      json = tiles;
      fromApp = true;
    }
  } catch (e) { slog('appcontrol error ' + e.message); }
  if (fromApp) keep(json);
  else json = restore();
  var count = 0;
  var hosts = {};
  try {
    var o = JSON.parse(json);
    if (!o || !o.sections) throw new Error('no sections');
    o.sections.forEach(function (s) {
      (s.tiles || []).forEach(function (t) {
        count++;
        var m = /^(\w+:\/\/[^\/]+)/.exec(t.image_url || '');
        hosts[m ? m[1] : 'none'] = 1;
      });
    });
    slog((fromApp ? 'app' : 'TV') + ' run: ' + count + ' tiles, images from ' + Object.keys(hosts).join(' '));
  } catch (e) {
    slog((fromApp ? 'app' : 'TV') + ' run: nothing to set (' + e.message + ')');
    exitSoon();
    return;
  }
  if (typeof webapis === 'undefined' || !webapis.preview) {
    slog('webapis.preview missing');
    exitSoon();
    return;
  }
  try {
    webapis.preview.setPreviewData(json,
      function () { slog((fromApp ? 'app' : 'TV') + ' run: set ' + count + ' tiles (' + json.length + ' bytes)'); exitSoon(); },
      function (e) { slog('setPreviewData failed ' + (e && (e.name + ' ' + e.message))); exitSoon(); });
  } catch (e) {
    slog('setPreviewData threw ' + e.message);
    exitSoon();
  }
}

// Data from the app arrives with onRequest (getRequestedAppControl is empty in onStart).
module.exports.onStart = function () {};
module.exports.onRequest = function () { run(); };
module.exports.onExit = function () {};
