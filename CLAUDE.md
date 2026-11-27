# CLAUDE.md: notes for agents working on this repo

Native Android TV app **"Кино"** (`kg.home.kino`) for browsing and playing films from **oc.kg** and TV series from
**ts.kg**, driven by the TV remote. The start screen picks OC.KG (films, red) or TS.KG (series, blue). **Personal use only** (owner's own TV).
UI language is Russian; keep user-facing strings in Russian (`res/values/strings.xml`).

---

## 1. The target device

| | |
|---|---|
| TV | Skyworth **43G6**, Android TV **6.0.1 (API 23)**, MediaTek SoC, **armeabi-v7a** (32-bit), 1920×1080, density 320 (xhdpi → 960×540 dp) |
| IP / adb | **`192.168.0.104:5555`** (home Wi-Fi, PC is on `192.168.0.x`). Connect: `adb connect 192.168.0.104:5555` |
| adb binary | `C:\Users\rus\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe` (on PATH as `adb`) |
| System WebView | **106.0.5249.126** (installed by us; last version for Android 6). Chrome/WebView ≥107 needs Android 7 |
| Play Store | Updated to **49.6.14** (last for API 23). Official YouTube does **not** support Android 6 |
| HW decoders | `OMX.MTK.VIDEO.DECODER.AVC` (H.264) works; AAC audio works |

Other apps on the TV that we installed: **SmartTube** (`org.smarttube.stable`, YouTube replacement), **TV Bro** 1.8.6
(`com.phlox.tvwebbrowser`), the older WebView-wrapper app **OC.KG/TS.KG** (`kg.home.ockg`, source in
`C:\Users\rus\Downloads\tv\ockg-app`) was **uninstalled** on 2026-11-21 at the owner's request; only Кино remains. Removed for user 0: Netflix, Play Games, Play Movies,
Play Music, Zhuyin keyboard, Netflix button helper. Old YouTube (`com.google.android.youtube.tv`) is disabled.

### adb quirks (important, these cost a lot of time)
- **adbd on the TV hangs** and shows `offline` (`adb devices`). Restarting the PC adb server does NOT fix it.
  The owner must toggle **Settings → Developer options → USB debugging** off/on (or reboot the TV). Ask them.
  It hung after long bursts of key events (e.g. a shell loop of 35 `input keyevent`). **Keep input bursts short**;
  prefer `adb shell input tap X Y` / `input swipe` over long key sequences.
- Wi-Fi adb is **slow** (~0.1–0.5 MB/s): installing the ~7.6 MB APK takes 20–75 s. A 100 MB APK took 13 min.
  Use long timeouts or `run_in_background`.
- **Screenshots can't capture ExoPlayer video** (hardware overlay): the video area is black even when it is
  playing. Verify playback by showing the controls (`input keyevent KEYCODE_DPAD_UP`) and reading the time,
  or via logcat (`MediaCodec`, `OMX.MTK`). The controller and all normal views do show in screenshots.
- `adb shell input text` can't type Cyrillic. The search screen has its own on-screen keyboard (А-Я / ABC / 123):
  move over it with DPAD keys + CENTER to type Russian. The TV's Leanback IME has **English only** (no Russian).
- Non-exported activities can't be started with `am start` from the shell; navigate with keys from
  `PickerActivity` (the only exported activity).
- Git Bash rewrites device paths like `/system/...` into Windows paths. Run adb with device paths in PowerShell,
  or set `MSYS_NO_PATHCONV=1`.
- The TV's **CH−** key may be intercepted by the system on this model; use swipes to scroll WebViews.
- Screenshot recipe (PowerShell):
  `adb shell screencap -p /data/local/tmp/s.png; adb pull /data/local/tmp/s.png <scratchpad>\s.png`

---

## 2. Build

- **Gradle 9.4.1 wrapper, AGP 9.2.1 with built-in Kotlin** (do NOT apply `org.jetbrains.kotlin.android`;
  AGP 9 compiles Kotlin itself, KGP 2.2.10). compileSdk 36, **minSdk 23**, targetSdk 36, Java 17.
- JDK: Android Studio's JBR 21. SDK: `%LOCALAPPDATA%\Android\Sdk` (path in `local.properties`, gitignored).

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --console=plain -q
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n kg.home.kino/.ui.picker.PickerActivity
```
Release builds are signed with the debug key (`signingConfig = debug`). It's for personal sideloading only.
versionCode = git commit count (versionName `1.0.<count>`). **GitHub Actions** (`.github/workflows/release.yml`) builds
the release APK on every pull request merged into main (not on direct pushes; manual run via workflow_dispatch) and publishes a GitHub Release `v1.0.<count>` with `kino-1.0.<count>.apk`; it signs
with the owner's debug keystore from the secret `DEBUG_KEYSTORE_BASE64`, so CI APKs install over local builds.
`tools/make-icons.ps1` regenerates `res/drawable-nodpi/banner.png` (320×180 TV banner) and `icon.png`.
Keep that script **ASCII-only** (Windows PowerShell 5.1 misreads non-BOM UTF-8; Cyrillic is built from char codes).

Libraries: leanback 1.0.0, media3 1.4.1 (exoplayer, exoplayer-hls, ui), glide 4.16.0, coroutines 1.8.1,
lifecycle 2.8.7, fragment 1.8.5, core-ktx 1.15.0. Check minSdk ≤ 23 before adding or upgrading anything.

---

## 3. Code structure (`app/src/main/java/kg/home/kino/`)

```
data/
  Models.kt          MovieShort (ratingImdb/Kp/Oc), MovieDetails, VideoFile, Person, Genre, Selection, Progress (isSeries), …
  OcApi.kt           oc.kg JSON API client (POST body, see §4), selections(), localRatings() batch, stream URL resolver
  TsModels.kt        TsShow, TsShowDetails, TsSeason/TsEpisode, TsVoice, TsStream, TsSelection, TsFilter/TsFilterOptions
  TsApi.kt           ts.kg client: HTML scraping (home, /show catalogue, /category, /selection, show page) + episode JSON
  ProgressStore.kt   "continue watching" in SharedPreferences; films keyed by movie id, series by "ts:<slug>" (episode id
                     in fileId); save(force=true) points a series at its next episode (position 0)
ui/
  picker/PickerActivity.kt     launcher "Что смотрим?" → MainActivity.intent(series = false|true)
  browse/MainActivity.kt       shared home for both sites: top bar (logo · 3 tabs · Поиск), tab fragments shown/hidden;
                               focus coming up from content lands on the current tab; Back goes content → tabs → exit
  browse/BestsellersFragment   OC Бестселлеры (RowsSupportFragment): continue, popular, new, Подборки, bestseller rows
  browse/CatalogFragment       OC Каталог: Сортировка/Жанр/Страна buttons + embedded CatalogGridFragment
  browse/SelectionsFragment    OC Подборки grid
  ts/TsHomeFragment, TsCatalogFragment (Сортировка/Категория/Жанр/Страна/Год), TsSelectionsFragment   TS tabs
  ts/TsDetailsActivity         show page: actions (Смотреть / Продолжить / Озвучка), one row per season, frames
  details/…                    OC film page (DetailsSupportFragment); DescriptionPresenter shows IMDb · КиноПоиск · OC.KG;
                               "Комментарии (N)" is the last action button (only when N > 0; the count loads after the
                               page) and opens details/CommentsActivity: full-text list, newest first, ScrollView of
                               focusable TextViews so the remote moves comment by comment
  grid/GridActivity.kt         full-screen grid (genre / selection / TS category); CatalogGridFragment also embedded in tabs
  search/SearchActivity.kt     own on-screen keyboard (RU/EN/123) + results grid; no voice search
  player/PlayerActivity.kt     ExoPlayer; films (MP4) and TS episodes (HLS + optional VTT subtitles), auto next episode
  player/SafeRenderersFactory  forces ExoPlayer's set-output-surface workaround: the MTK decoder fails setOutputSurface
                               (CodecException 0x80000000 → ERROR_CODE_FAILED_RUNTIME_CHECK) when the surface changes;
                               PlayerActivity also retries once (prepare() at the same position) after any playback error
  common/Presenters.kt         MovieCardPresenter (wide = TS 5:4 posters on a light backing), Genre/Episode/Frame/Person
  common/Spacing.kt            SpacedListRowPresenter / SpacedGridPresenter (TS rows and grids)
  common/EmbeddedGrid.kt       hideBuiltInTitle(): collapse a VerticalGridSupportFragment's own title bar inside a tab
  common/ContinueRow.kt        "Продолжить просмотр" rows (OC + TS): hold OK on a card → dialog to remove it (detected from key
                               repeats; adb `input keyevent --longpress` works). HintRowHeaderPresenter shows a faded right-aligned
                               hint only while focus is in that row (showRowHintsWhenFocused)
  common/Nav.kt, Format.kt     click routing; formatTime(), formatRating(), ratingsLine()
```
Themes: `res/values/themes.xml` (Theme.Kino.* on top of Theme.Leanback.*). Brand red `#E53935`, TS blue `#1E88E5`.

Film cards: line 1 "2026 · IMDb 7.1", line 2 "КП 7.3 · OC 9.3" (owner's chosen order). Popular/bestseller lists lack the
OC rating; BestsellersFragment fills it in afterwards via `OcApi.localRatings` (batched getMovie, 20 per request).

Remote handling in the player (`PlayerActivity.dispatchKeyEvent`): with the controller hidden, **←/→ seek ±10 s**,
**OK** pause/play, **↑/↓** show the controller. With it shown, default PlayerView focus handling applies (time bar
key increment 10 s). **Back** hides the controller first, then exits. Progress is saved every 10 s and on pause.
Controller hides after 2.5 s with animation off (all parts at once). Series add "Следующая серия" and "Субтитры"
buttons; MEDIA_NEXT also skips. While our "Подготовка видео…" box shows, PlayerView's own buffering spinner is off.

---

## 4. oc.kg API (reverse-engineered, undocumented, can change)

Endpoint: `https://oc.kg/api.php?format=json&action[0]=<Method>&<param>[0]=<value>`
Response: `{"json":[{"status":200,"message":null,"response":{...}}]}`. No login or cookies needed.

**Encoding gotcha:** parameter values are encoded like JS `escape()`: Cyrillic as **`%uXXXX`**, `+`→`%2B`
(`OcApi.jsEscape`), as the site's JsHttpRequest does. **Send them in a POST body** (`OcApi.call`): Android 6's
`java.net.URL` rejects `%u` in a URL ("Invalid % sequence"), which broke Russian search. (Tested 2026-11-21: plain
UTF-8 `%D0%BE…` now returns the same results too.) `localRatings()` still uses GET (ids are ASCII).

**Подборки:** `getSelections` returns only ids, so `OcApi.selections()` parses `TEMPLATES.SELECTION` (id, image,
title) from the oc.kg home page HTML. Films of one selection: `getCatalog` with `selection=<id>`.
Sort codes for `order`: 0 newest added, 1 by year, 2 IMDb, 9 KinoPoisk, 3 local (OC), 8 trending, 6 hits (4 = personal, needs login).

| Method | Params | Notes |
|---|---|---|
| `Video.getBestsellers` | none | `bestsellers[]{name, movies[6]}`, 10 categories |
| `Video.getPopMovies` | none | `movies[]` with description, ratings |
| `Video.getCatalog` | `offset`, `size`, `genre`, `selection`, `country`, `order` | `{total, movies[]}`, newest first by default (≈5.8k films) |
| `Video.getGenres` / `getCountries` | none | `{id, name, count}` |
| `Video.search` | `query` | `{movies[], persones[]}` (covers are tiny 40×60) |
| `Video.getComments` | `movie_id` | `comments[]{user_name, text (may contain <br>), created_at, to_user_name, votes}`, oldest first; read-only (posting needs login) |
| `Video.getMovie` | `movie_id` | full details: `covers[{original,thumbnail}]`, `persones[]{roles}`, `files[]`, `other_movies[]` |
| others seen in `/js/LMS/Video/Action.js` | | getPerson(person_id), getRandomMovie, hitMovie, getSelections (returns `["1"]`, not useful) |

**Files / streams:** `files[]` is a tree (`is_dir`, `childs`); `OcApi.flattenFiles` flattens it. Each file has
`links.license` = `pl.php?player=ftp&uid=…&movieid=…&fileid=…&v=<hash>` and `path`
(`/home/video/video/a/25/X.mp4`). The **direct MP4** is in `pl.php` as `src: "https://p1.oc.kg:8082/video/…mp4?an=1"`.
That page also lists advert videos under `/background/…`, which the app skips. Fallback:
`https://p1.oc.kg:8082` + path minus `/home/video` + `?an=1`. Streams are H.264/AAC MP4, served without cookies
(Referer `https://oc.kg/` is sent anyway).

**Images:** thumbnails are `/media/thumbnails/x/y/<hash>_<W>x<H>.jpg`. Only some sizes exist. A **200-px-wide
version exists at the poster's own aspect ratio** (`_120x171` → `_200x285`, `_120x180` → `_200x300`);
see `OcApi.poster()`. Arbitrary sizes 404. The exact API size is the fallback (`MovieShort.posterFallback`).
Frames: `/media/frames/…/frame-NNNNN.jpg` (full-size stills, used as the details backdrop).
TLS: Sectigo chain → USERTrust RSA root, trusted on Android 6 (no custom trust needed).

---

## 5. TS.KG (implemented in `TsApi`, HTML scraping - fragile if the site changes)

- **JSON endpoints need `X-Requested-With: XMLHttpRequest`**, otherwise they return the HTML page.
- Episode: `GET /show/episode/episode.json?episode=<id>` → `video.url` (**HLS m3u8** on `daknet1.ts.kg`, no cookies
  needed), `video.subtitles` (`.vtt`, opt-in like on the site), `duration`, `overlay` (advert, ignored).
- **No comments on ts.kg** (checked 2026-11-22: none on show/episode pages, nothing in the JS). Shows have 👍/👎 votes
  (`fa-thumbs-up"></span> N` in the first `app-show-tags`), shown first on the info line of TsDetailsActivity.
  Listing cards carry them too (parsed in `parseCards`): TS cards are 2 lines, "year, genres" (`fitFirstLine`)
  then "👍 N  👎 N · voice". News (Новые серии) and search results have no votes.
- Show page `/show/<slug>`: title `id="h-show-title" data-title data-id`, seasons in `app-show-seasons-section-full`
  (`data-season="N"`, episode ids in `id="app-show-episode-title-<id>"`), voices box "Выбрать озвучку" (each
  voice-over is a separate show slug), stills `/shots2/<slug>/N.jpg`.
- Lists: home `/` (news list = Новые серии, `app-index-shows-new-m`, `app-index-shows-top-m`), `/category/<slug>`,
  catalogue `/show?categoryId=&genreId=&countryCode=&year=&sortBy=updated|new|a|z` (~51 per page; follow
  `class="next-page"`). Filter menus are the `<select>`s on `/show`. Cards: `app-shows-item-full` blocks; some
  selection pages lack `id="shows"` (fallback to `app-shows-container`), titles may be prefixed "1. ".
- Подборки: `/selection?page=N` (3 pages, ~125), each `/selection/<slug>` lists its shows.
- Search: `GET /shows/search/<utf8 query>` (XHR) → `[{name: "Рус (Orig)", url: "/show/slug"}]`.
- Posters `/posters/<slug>.png` 256×204 (5:4), `/posters2/` 2×; **transparent PNGs with the title text on top**,
  often black, so cards draw them on a light backing (`@color/poster_backing`).
- Adverts on ts.kg: `/static/MTIzNDU2NzgxMjM0NTY3O…`, `/olol1/…`, tracking `/bad/view|click`, `branding.js`.
  The app never requests these.

---

## 6. Gotchas already hit
- Leanback `VerticalGridSupportFragment` has only a setter: call `setOnItemViewSelectedListener(...)`, not property syntax.
- Glide: clear images with `Glide.with(context.applicationContext).clear(view)` in `onUnbindViewHolder`;
  `Glide.with(activity)` throws when the activity is already destroyed.
- `AbstractDetailsDescriptionPresenter` subtitle is single-line; extra info goes in the body.
- Don't override `View.isShown()` in custom views (framework uses it).
- Android 6 lacks Java 8 `Map.putIfAbsent` and friends (API 24): use Kotlin `getOrPut`. Crash only shows on the TV.
- `VerticalGridSupportFragment` needs its title view (NPE in onStart without it); hide it with `hideBuiltInTitle()`
  from `onStart`, which also removes leanback's "Up → title" focus rule so Up reaches our tabs/filter buttons.
- `PlayerView.setControllerAnimationEnabled(false)`: the default hide animation only handles media3's standard ids,
  so custom views (title, our buttons) disappeared at different times.
- Other TVs (Android 6+ / Google TV): the APK has no native libs (any ABI). The manifest sets
  `enableOnBackInvokedCallback="false"`, because with targetSdk 36 Android 16 would otherwise switch to predictive back
  and stop sending `KEYCODE_BACK`, which our Back handling needs. Gamepad `BUTTON_A` counts as OK. Keep sizes in dp
  (TVs differ in density).
- The repo has a GitHub remote (`origin`, `rustam-aitbekov/kino-app`, public). Commit or push only when the owner asks.
  `main` is protected by the ruleset `cicd` (PR required, 0 approvals, no force push or deletion): push a branch and
  open a PR with `gh pr create`; the owner merges it, which publishes the release APK.
