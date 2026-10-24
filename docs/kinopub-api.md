# kino.pub API v1 reference (compact)

Sources: kinoapi.com (official docs, pages authentication/api_device/api_video/api_bookmarks/api_watching/api_references/api_history/user), plus open-source clients: quarckster/kodi.kino.pub (Python), kazhuravlev/filmax (Kotlin, Android TV, has its own CLAUDE.md with live-verified notes, dated Sep 2026). Facts tagged **[kodi]** / **[filmax]** come from those code bases; **UNVERIFIED** = not confirmed by docs or code, or contradictory.

Official docs have invalid JSON examples and typos; shapes below are normalized. Responses usually carry `"status": 200`.

## 1. Hosts, auth header, conventions

- Official base: `https://api.service-kp.com` (API at `/v1/...`, OAuth at `/oauth2/...`).
- Mirrors used by clients: `https://api.srvkp.com` [kodi], and [filmax] `https://smarttvcdn.online` (primary), `https://api.boramoraboom.ru`, `https://api.srvkp.com`. **On mirrors filmax prefixes paths with `api/`** (`{host}/api/v1/...`, `{host}/api/oauth2/device`) UNVERIFIED whether official host accepts that prefix. Health probe: `GET /api/v1/countries` (401 without token = host alive).
- Auth: `Authorization: Bearer <access_token>` on every `/v1` call.
- Mutations are mostly POST with form-urlencoded body; many watching endpoints are GET even when they mutate.
- 429 can occur: wait ~3-5 s, retry up to 2-3 times [kodi]. 401 = token expired: refresh once, retry once.
- Kodi sends a desktop-browser User-Agent on OAuth calls (UNVERIFIED whether required).
- Timestamps are unix seconds. `0` for rating/views/quality means "no data" [filmax].
- Pagination: items lists return `pagination {total, current, perpage}`; use `page`, `perpage` (default 25 for fresh/hot/popular, 40 for search).

## 2. OAuth device flow (`/oauth2`)

Client credentials: official docs say to request them from support@kino.pub. Known public ones (extracted from open source; may be revoked, rotate responsibly):

| Client | client_id | client_secret | Source |
|---|---|---|---|
| Kodi plugin | `xbmc` | `cgg3gtifu46urtfp2zp1nqtba0k2ezxh` | kodi auth.py |
| filmax Android | `android` | `rcaqh7wodackn9ll1uggvqkx2iib6umh` | filmax OAuth.kt |

(Note: a client_id determines which app the device is listed as; request your own via support for a production app.)

All OAuth calls are `POST`, params can go in query string or form body [kodi uses form body].

1. Get device code
   `POST /oauth2/device` params `grant_type=device_code`, `client_id`, `client_secret`
   -> 200 `{code, user_code, verification_uri:"https://kino.pub/device", expires_in:8600, interval:5}`
   Show `user_code` + `verification_uri`. Kodi gives up after 5 min of polling.
2. Poll (every `interval` s, never faster)
   `POST /oauth2/device` params `grant_type=device_token`, `client_id`, `client_secret`, `code=<code>`
   -> 400 `{"error":"authorization_pending"}` = keep polling (only non-critical error)
   -> 200 `{access_token, token_type:"bearer", expires_in:3600, refresh_token, scope:null}`
   After success call `/v1/device/notify` (section 4).
3. Refresh
   `POST /oauth2/token` params `grant_type=refresh_token`, `client_id`, `client_secret`, `refresh_token`
   -> 200 same shape as above. ALWAYS store the returned `refresh_token` (docs say a new one is issued; docs example echoes the old - treat as rotating). Refreshing invalidates previously issued tokens.
- Lifetimes: access 3600 s; refresh 30 days; device code 8600 s (docs) / user-facing window practically ~5 min [kodi].
- Errors (400 + `{"error": ...}`): `authorization_pending` (continue); `invalid_client` ("client credentials are invalid", fatal); [kodi] treats `code_expired`, `authorization_expired`, `invalid_refresh_token` as "restart device flow"; any other error = fatal. [filmax] treats 4xx on refresh (`invalid_grant`) as logout, 5xx/network = keep session. Full error list is on a separate docs page not fetched: UNVERIFIED completeness.
- Compute `expires_at = now + expires_in`; refresh proactively or on 401.

## 3. `GET /v1/user`

No params. -> 
```json
{"status":200,"user":{"username":"x","reg_date":1235345666,
 "subscription":{"active":true,"end_time":12356456456,"days":3.5},
 "profile":{"name":"Ivan","avatar":"http://..."}}}
```
`reg_date` shown as string in docs. No `id` [filmax]. Subscription may be nested in `user` or top level [filmax]; `days` fractional; `end_time` unix s.

## 4. Device endpoints

| Method/path | Notes |
|---|---|
| `GET /v1/device` | `{status, devices:[Device]}` |
| `GET /v1/device/info` | current device: `{status, device:Device}` [filmax: 500 in Sep 2026 - UNVERIFIED if still] |
| `GET /v1/device/{id}` | one device |
| `POST /v1/device/notify` | body `title`, `hardware`, `software` -> `{status:200}`. Docs: call after activation and on each app launch. Kodi: `title`=friendly name/hostname, `hardware`=`platform.machine()` (e.g. arm64), `software`="Kodi 21.0". Use e.g. title "Living room TV", hardware "<Build.MODEL>", software "Android TV <ver>/app <ver>". |
| `POST /v1/device/unlink` | unlink current device (logout) |
| `POST /v1/device/remove?id=N` or `POST /v1/device/N/remove` | `{status, current:bool}`; removing current = logout |
| `GET /v1/device/{id}/settings` | see below |
| `POST /v1/device/{id}/settings` | body = map name->value -> `{status}` |

Device: `{id,title,hardware,software,created,updated,last_seen,is_browser,settings}`.

Settings GET response: `{status, settings:{<name>:{label,value,type?}}}`. No `type` = checkbox (value 1/0); `type:"list"` => `value` is array of `{id,label,description,selected}`. (Docs example uses `useSsl`, probably legacy for `supportSsl`; UNVERIFIED which name is live.)

Settings fields (POST names):

| Field | Type | Meaning |
|---|---|---|
| `supportSsl` | bool | device supports SSL (HTTPS links) |
| `supportHevc` | bool | device supports HEVC/H.265 |
| `supportHdr` | bool | device supports HDR (10-bit) |
| `support4k` | bool | device supports UHD/4K |
| `mixedPlaylist` | bool | **hls4 only**: playlist built from all available AVC + HEVC files |
| `streamingType` | int | id from `/references/streaming-type`; [filmax] `0`=HTTP, `1`=HLS, `2`=HLS4 (UNVERIFIED vs live reference list) |
| `serverLocation` | int | id from `/references/server-location`; `0` = auto [filmax] |

Recommended for Android TV 4K HDR: POST `supportSsl=1 supportHevc=1 support4k=1 supportHdr=1 streamingType=<hls4 id> mixedPlaylist=1` (booleans as 1/0 or true/false: UNVERIFIED which the server accepts; docs example shows `true/false`). Settings are server-side per device and change which `files[]` are returned. Kodi does NOT call these endpoints (it picks stream type locally and only uses `device/notify`).

## 5. Catalog

References (all GET, no auth nuance): `/v1/types` -> `[{id,title}]` ids: movie, serial, 3D, concert, documovie, docuserial, tvshow. `/v1/genres?type=` -> `[{id,title,type}]` (type groups: movie, docu, music, tvshow). `/v1/countries` -> `[{id,title}]`.

### `GET /v1/items`
Params: `type`; `title` (>=3 chars, LIKE); `genre` (comma ids); `country` (comma ids); `year` (`2020` or `2015-2020`); `finished` (0/1 serials); `actor`, `director` (`A1,A2+A3` = A1 OR (A2 AND A3)); `letter`; `conditions[]` (e.g. `year>=2020`, AND-ed; repeated key); `force[]` (`quality`,`advert`,`erotic` skip user filters); `quality[]` (quality ids; [filmax] `quality=4` = 2160p-only filter); `sort` default `updated-` (`-` suffix = DESC per docs; [filmax] says `sort=-field` DESC verified live - CONFLICT, UNVERIFIED: test both). Sort fields: id, year, title, created, updated, rating, views, watchers. `page`, `perpage`.

### Item (list entry)
```json
{"id":1,"title":"A / Original","type":"movie","subtype":"multi|''","year":2006,
 "cast":"..","director":"..","voice":"..",
 "duration":{"average":123,"total":123},"langs":2,"ac3":0,"subtitles":3,"quality":1080,
 "genres":[{"id":1,"title":".."}],"countries":[{"id":1,"title":".."}],"plot":"..",
 "imdb":123,"imdb_rating":7.1,"imdb_votes":1,"kinopoisk":123,"kinopoisk_rating":7.1,"kinopoisk_votes":1,
 "rating":456,"views":15,"comments":5,"finished":false,"advert":true,
 "in_watchlist":true,"subscribed":true,
 "posters":{"small":"","medium":"","big":"","wide":null},
 "trailer":{"id":"..","url":".."}}
```
`quality` = frame height px (2160/1080/720/480); for serials = quality with most episodes. `subtype:"multi"` = multi-part film (uses `videos[]` with several entries). `imdb` numeric id (pad to 7 digits + `tt`). `duration.average` is per-episode for serials. `posters.wide` null when absent [filmax]. Title can be multi-line "Name / Original / ..." [filmax]. `tracklist[]` for concerts/music. List endpoints (items, search, similar, fresh/hot) return NO `videos`/`seasons`.

### `GET /v1/items/{id}` (params `nolinks=1` omits file URLs; docs warn default will flip to 1 and links will need `media-links`)
Movie/documovie/concert: `{item:{...Item, videos:[Video]}}`.
Serial/docuserial: `{item:{...Item, seasons:[{title,number,episodes:[Video]}]}}` (season number 0 = serial without seasons [filmax]).

Video (a.k.a. media; used for movie parts and episodes):
```json
{"id":1234,"number":1,"title":"..","thumbnail":"..","duration":1234,
 "watched":1,"watching":{"status":-1,"time":1234},
 "tracks":"1,2,3,4",
 "subtitles":[{"lang":"eng","shift":0,"embed":true,"forced":false,"url":"https://..."}],
 "audios":[{"id":15510,"index":1,"codec":"aac","channels":2,"lang":"ukr",
   "type":{"id":2,"title":"Многоголосый","short_title":"MVO"},
   "author":{"id":7,"title":"..","short_title":null}}],
 "files":[{"codec":"h264","w":1920,"h":1080,"quality":"1080p","quality_id":3,
   "url":{"http":"..","hls":"..","hls2":"..","hls4":".."}}]}
```
- `id` is the media id used by `media-links?mid=` and history. `number` (1-based) is what `watching/*` calls use as `video`; unique only within a season.
- `watching.status`/`watched`: -1 not watched, 0 in progress, 1 watched. `time` seconds. [filmax] treats last 90 s as finished.
- `duration`/`time` in seconds. Episode `thumbnail` often empty. Subtitle `url` may be null (drop). `forced` appears in newer payloads (UNVERIFIED). `embed:true` = burned into source file.
- Official docs sample for files shows only `url.http`/`url.hls`; real payloads also include `hls2`, `hls4` (per media-links doc). `codec` and `quality_id` are shown in media-links; in item JSON presence of `codec`/`quality_id` UNVERIFIED (may be absent).
- `audios[].index` is 1-based and equals manifest order in the HLS multi-audio streams; HLS `NAME` carries only a language code, real metadata is in `audios[]`; the original track has an EMPTY `lang` [filmax]. `type.short_title`: DUB (Дубляж), MVO, DVO, etc.; `author` = studio/voice actor (see references).

### 4K / HDR / Dolby Vision marking (important)
- No documented explicit HDR/DV/HEVC flag in item or file JSON. Documented signals: `file.quality` string ("2160p"/"4K"? exact label UNVERIFIED), `file.h` (2160), `file.codec` (`h264` vs `hevc`/`h265` - exact string UNVERIFIED), `quality_id` (4 = 2160p per [filmax] filter; reference `video-quality` gives ids, e.g. 1=480p, 2=720p, 3=1080p from docs sample).
- The site also exposes genre type `4k` in `/genres?type=4k` and a `4k` section (docs list it as genre filter value), so 4K titles can be discovered via `quality=4` or genre filter.
- HDR/DV: likely only reflected by `codec` hevc + `supportHdr=1` device setting changing what the server returns; there may be separate file entries/qualities suffixed (e.g. "2160p HDR") - UNVERIFIED. Discover empirically: with a Pro account call `/items/{id}` for a known HDR title with settings off vs on and diff `files[]`.

### Streaming types (which gives multi-audio)
- `url.http` = progressive MP4 (single audio track, selected by... per-file; use `media-video-link`).
- `url.hls`, `url.hls2` = HLS variants; `url.hls4` = newest HLS manifest. Kodi's stream_type options: `hls`, `hls2`, `hls4` (default `hls4`), and `loc` option `ru`|`nl` [kodi].
- Multi-audio in ONE HLS manifest: HLS variants carry the audio renditions as `#EXT-X-MEDIA TYPE=AUDIO`; `audios[].index` maps to manifest order [filmax]. Which variant is best for multi-audio: **hls4 (default in Kodi and filmax, preferred order hls4 -> hls -> http)**; `mixedPlaylist` is hls4-only and merges AVC+HEVC renditions. That hls/hls2 lack multi-audio is UNVERIFIED (likely older: hls = single/limited audio).
- Variants live on different CDN hosts; some are DPI/SNI-blocked, so fall back hls4 -> hls -> http on source error [filmax]. `supportSsl` controls http vs https links.

### Other items endpoints
- `GET /v1/items/search?q=&type=&field=title|director|cast&perpage=40&sectioned=0|1` -> `{status,items:[],pagination}`; sectioned=1 -> `items:{movie:{items,pagination},...}`.
- `GET /v1/items/fresh|hot|popular?type=&page=0&perpage=25` -> same as `/items`. [filmax] says only `hot` and `new` exist (UNVERIFIED: `fresh` per docs vs `new` per filmax).
- `GET /v1/items/similar?id=` -> `{items:[Item]}`.
- `GET /v1/items/media-links?mid=<media id>` -> 
  `{files:[{codec,w,h,quality,quality_id,file:"/path.mp4",urls:{http,hls,hls2,hls4}}],subtitles:[{lang,shift,embed,file,url}]}` (note `urls`, not `url`, here). Use for fresh links when `nolinks=1`. CDN links valid well over 45 s; trailer URLs are temp m3u8 with expiring token [filmax].
- `GET /v1/items/media-video-link?file=<path>&type=http|hls|hls2|hls4` -> `{url}`.
- `GET /v1/items/vote?id=&like=0|1` -> `{voted,total,positive,negative,rating}`.
- `GET /v1/items/comments?id=` -> `{status,item,comments:[{id,depth,unread,deleted,message,created,rating,user:{id,name,avatar}}]}`.
- `GET /v1/items/trailer?id=` or `?sid=<youtube id>` -> `[{status,trailer:{id,url,files:[{url,quality,width,height}]}}]`.

## 6. Watching (all GET)

- `GET /v1/watching?id=&video=&season=` -> `{status,item:{id,title,type,seasons:[{id,number,status,episodes:[{id,number,title,duration,time,status,updated}]}]}}`; movies: `item.videos:[{id,number,title,duration,time,status,updated}]`. Omit video/season = all. Verified 2026-10-24: each episode/video also has `updated` (unix seconds of the last time/status change) — a reliable "last watched" per title, unlike `/v1/history`.
- `GET /v1/watching/movies` -> `{status,items:[{id,type,subtype,title,posters}]}` (unfinished movies/concerts/docs/3D). No timecode.
- `GET /v1/watching/serials?subscribed=0|1` -> `{status,items:[{id,type,subtype,title,posters,total,watched,new}]}`; `total`=episodes, `watched`, `new`=unwatched. [filmax]: `subscribed` splits series into subscribed/unsubscribed, query both; ignored for movies; `{type}` only `movies|serials`.
- `GET /v1/watching/marktime?id=&video=<number>&time=<sec>&season=<n>` -> `{status}`. `season` mandatory for serials (else 404). Does NOT set status [filmax].
- `GET /v1/watching/toggle?id=&season=&video=` -> `{status,watched:0|1}`. Omit `video` = whole season. [filmax]: `status=1` param makes it idempotent mark-watched; omit `season` for movies.
- `GET /v1/watching/togglewatchlist?id=` -> `{status,watching:true|false}` (series; [filmax] sees numeric 0/1). Never auto-retry (repeat undoes it).
- "Continue watching" pattern [filmax]: intersect `/v1/history` with `watching/{type}`; resume first episode with `status<=0`.

## 7. Bookmarks

- `GET /v1/bookmarks` -> `{status,items:[{id,title,views,count,created,updated}]}`
- `GET /v1/bookmarks/{folderId}` (or `/bookmarks/view?folder=`) -> `{status,folder:{id,title,views,created,updated},items:[Item]}` (paginated by `page`).
- `GET /v1/bookmarks/get-item-folders?item=` -> `{status,folders:[...]}`
- `POST /v1/bookmarks/create` `title` -> `{status,folder}`
- `POST /v1/bookmarks/add` `item`, `folder` -> `{status}` (server may store duplicate links [filmax])
- `POST /v1/bookmarks/remove-folder` `folder`
- `POST /v1/bookmarks/remove-item` `item`, optional `folder` (omitted = all folders)
- `POST /v1/bookmarks/toggle-item` `item`, `folder`
App's "Буду смотреть" is just a bookmark folder found by title [filmax].

## 8. History

- `GET /v1/history?page=&perpage=` (default 20, max 50 per docs; filmax uses up to 100: UNVERIFIED) -> `{history:[{time,counter,first_seen,last_seen,item:{},media:{}}],pagination:{total,current,perpage,total_items}}`. Per-episode entries, newest first; marathon of one show fills a page, so page until enough distinct titles. Entries lack genres/ratings; enrich via `/items/{id}`.
- `POST /v1/history/clear-for-media?id=<mediaId>`, `/clear-for-season?id=<seasonId>`, `/clear-for-item?id=<itemId>` -> 200 (body undocumented). clear-for-item also removes a movie from "continue watching" [filmax].

## 9. References (`GET /v1/references/*`) -> `{status,items:[...]}`

- `server-location` -> `[{id,location:"de",name:"Германия"}]` (live list not seen by filmax: only 0=auto known; Kodi uses `ru`/`nl` as `loc` UNVERIFIED mapping).
- `streaming-type` -> `[{id,code:"hls4",name:"HLS4",description}]` (use live call to map ids; do not hardcode).
- `voiceover-type` -> `[{id,title,short_title}]` e.g. Дубляж/DUB.
- `voiceover-author` -> `[{id,title,short_title}]`.
- `video-quality` -> `[{id,title:"480p",quality:480},{id:2,title:"720p"},...]`.

## 9a. Live TV — `GET /v1/tv` (verified 2026-10-18)

`{status, channels:[{id, name, title, logos:{s,m}, stream, embed, current, playlist, status}]}`.
`/v1/tv/index` (what Kodi calls) returns the same. ~50 sport channels (Eurosport 1/2, Okko ×4,
Match ×7, Sky, TNT, ESPN, Setanta…). `stream` = `https://edge-01.mycdn.video/<name>/index.m3u8?token=…`
— a one-variant master (1080p H.264, ~6.5 Mbit/s) → `media.m3u8` → 6 s MPEG-TS segments; plain
HTTPS, plays in ExoPlayer as-is. `current` is **always empty**: no guide from kino.pub — the app uses
the iptvx.one XMLTV feed (`https://iptvx.one/epg/epg_lite.xml.gz`; Eurosport times from
`eurosport1-pl`/`eurosport2-pl` — the Russian `eurosport1`/`eurosport2` run an hour off; ids like
`okko-sport`, `okko-sport-2` = Окко Прайм, `match-premier`, `match-football1`). Fallbacks:
programtv.ru (`/xmltv.xml.gz`, 0.9 MB, Match + Окко.Спорт only, no Eurosport); epg.pw times are wrong.

## 10. Suggested client flow for Android TV 4K/HDR

1. OAuth device flow -> store tokens (refresh 30 d).
2. `POST device/notify` (title/hardware/software).
3. `GET device/info` for `id` (fallback: `GET device`, match by title) then `POST device/{id}/settings` with support flags per real decoder capabilities (query MediaCodecList for HEVC/Dolby Vision/HDR10; display HDR caps).
4. `GET items/{id}`; pick file by `h`/`quality`/`codec`; prefer `url.hls4` -> `hls` -> `http`; map `audios[]` by index to manifest audio renditions; refresh with `media-links?mid=` if URL stale.
5. Progress: `watching/marktime` every ~10-30 s; at end `watching/toggle?status=1`.

## 11. Known gotchas / UNVERIFIED list

- HDR/DV flag location, exact `codec` strings, `quality` label for 4K: UNVERIFIED (see section 5).
- `fresh` vs `new`; sort direction syntax; boolean encoding for settings POST; settings key `useSsl` vs `supportSsl`; `device/info` 500s; official host vs `/api/` prefix: UNVERIFIED.
- Public client secrets above are from third-party repos and could be revoked.
