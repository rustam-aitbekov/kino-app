# oc-kg

Native Android TV app ("Кино") for browsing and watching films from **oc.kg** and TV series from **ts.kg**
with a TV remote. Personal use.

- **Start screen:** choose OC.KG (films) or TS.KG (series)
- **Home (both sites):** a top bar like the websites, with three tabs and Поиск on the right
  - OC.KG: **Бестселлеры** (Продолжить просмотр, Популярное сейчас, Новинки, Подборки, bestseller categories) ·
    **Каталог** (all films; Сортировка / Жанр / Страна filters) · **Подборки** (the site's TOP lists)
  - TS.KG: **Главная** (Продолжить просмотр, Новые серии, Новые сериалы, Популярные, Подборки, Категории) ·
    **Каталог** (Сортировка / Категория / Жанр / Страна / Год) · **Подборки**
- **Film page:** backdrop, poster, ratings (IMDb / КиноПоиск / OC.KG), description, frames, cast, similar films.
  Film cards show "year · IMDb" and "КП · OC".
- **Series page:** seasons as rows of episodes, Продолжить (exact episode and time), voice-over switch, frames
- **Player:** native ExoPlayer (Media3), straight from the sites' video servers with no website and no advert videos.
  Resumes where you stopped; series continue to the next episode, with a "Следующая серия" button and optional
  subtitles. Remote: OK = pause/play, ←/→ = −10 s / +10 s, Back = hide controls / exit.
- **Search:** built-in on-screen keyboard with Russian (А–Я), English and digits (the TV's keyboard has no Russian)

Target device: Android 6.0+ (minSdk 23), tested on a Skyworth 43G6.

## How it gets data

**oc.kg** has an internal JSON API (`api.php`), the same one the website uses:

```
https://oc.kg/api.php?format=json&action[0]=Video.getMovie&movie_id[0]=24030
```

Methods used: `Video.getBestsellers`, `getPopMovies`, `getCatalog` (offset/size/genre/country/selection/order),
`getGenres`, `getCountries`, `search`, `getMovie`. Parameter values are encoded like JavaScript's `escape()`
(Cyrillic as `%uXXXX`) and sent in a POST body (Android 6 rejects `%u` in URLs). See `data/OcApi.kt`.
The video URL is read from the file's player page (`pl.php`), skipping the `/background/…` advert videos.
Подборки are read from the site's own page template, since the API only returns their ids.

Poster thumbnails exist at 200 px wide in each poster's own aspect ratio (`_120x171` → `_200x285`);
the exact size the API returns is used as a fallback.

**ts.kg** has no catalogue API, so lists and show pages are read from the HTML (`data/TsApi.kt`). Episodes
come from `/show/episode/episode.json` (HLS stream + subtitles), the same request the site's player makes.

Both sites are undocumented and can change without notice.

## Build & install

Requires the Android SDK and Android Studio's JDK (or any JDK 17+).

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`tools/make-icons.ps1` regenerates the TV banner and launcher icon.
