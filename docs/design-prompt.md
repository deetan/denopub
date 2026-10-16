# Design prompt: Kino TV (for Claude Design / any UI design tool)

Paste the block below as-is. The wireframes it describes are already implemented in code
(`app/src/main/java/dev/dt/kinopub/ui/`), so this prompt asks for **visual polish within a fixed
structure**, not new information architecture.

---

Design a **10-foot TV interface** for a personal Android TV client of a movie/serial streaming
service. One user, watched on a 4K TV from a couch, controlled **only by a D-pad remote**
(up/down/left/right, OK, Back, play/pause). Design at **1920×1080**, keep content inside a 48 px
horizontal / 27 px vertical safe area.

**Principle: functional first, beautiful second.** Every screen answers one question in under
two seconds, from 3 m away. No decoration that does not carry information. Dark theme only.

**Focus is the cursor.** Exactly one element is focused at any time; it must be unmistakable
from the couch: 3 px accent border + 1.05× scale. Unfocused elements never look focused. Design
every component in both states (and "selected but not focused" for chips).

Tokens to start from (adjust if you have a better palette, keep the roles):
- background `#0E0F12`, surface `#1A1C21`, raised surface `#262931`
- text `#EDEEF0`, muted text `#9AA0A6`
- accent (focus + primary action) amber `#F2B33D`, text on accent `#14161A`
- "new" badge red `#E5484D`
- type: 36 / 24 / 18 / 15 / 13 sp; two weights (regular, semibold); Russian UI copy

## Screen 1: Home (most important)
Order = frequency of use. On launch the first "Продолжить" card is focused, so OK = resume.
1. **Продолжить** row: 16:9 cards ~300 dp wide. Card shows backdrop, title, `S2·E5`,
   "осталось 23 мин" or "следующая серия", progress bar along the bottom edge, red `+2` badge
   when new episodes arrived. OK = play immediately; long-press = details.
2. **Вишлист** row: 2:3 posters ~150 dp, header badge "3 новых", per-poster `НОВОЕ` badge for
   items added from the phone and not yet seen on TV.
3. **Новые фильмы**, **Новые сериалы**, **Популярное**: 2:3 posters with year · IMDb.
Rows scroll vertically; the focused row should stay at a predictable height.

## Screen 2: Player
Full-screen video (4K HDR). Overlays only on demand:
- **OSD** (auto-hides after 4 s): title, `S1·E3 · episode title`, thin progress bar,
  elapsed / remaining time. Bottom gradient for legibility over bright HDR.
- **Tracks panel** (↓ opens, Back closes): three horizontal rows of chips — **Озвучка**
  (`LostFilm · RU · 5.1 · AC3`, `Оригинал · EN · 5.1`…), **Субтитры** (`Выкл`, `RU`, `EN`,
  `EN (forced)`), **Качество** (`4K · H265`, `1080p`, `720p`), plus **Дальше › Следующая
  серия**. All options visible at once — no lists, no radio buttons, no scrolling. Current
  choice is "selected" and gets focus on open. Must stay readable over any video frame.
- Optional: "next episode in 10 s" card in the last seconds of an episode.

## Screen 3: Details
Left column: poster, title, `2021 · IMDb 7.9 · КП 7.5 · 4K`, primary button **Продолжить
S1·E3** (focused on open), secondary **В вишлист**. Right: 3–4 lines of plot, season tabs,
episode list where each row shows `3. Title`, status (`просмотрено ✓` / `остановились на 27
мин из 58` with a mini progress bar / duration). The resume episode is highlighted.

## Screen 4: Login
Centered: "Войти в kino.pub", "Откройте kino.pub/device и введите код", the code in very large
spaced monospace digits in accent color.

## Deliver
Each screen in default state plus focus variants; a component sheet (cards, chips, buttons,
badges, progress bar, episode row) with focused / selected / default states; final tokens.
