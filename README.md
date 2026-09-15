# MovieBox Downloader — Android (Kotlin + Jetpack Compose)

A native Android app that downloads movies & dramas from themoviebox.xyz.
Same features as the web version, as a real standalone app — no server
needed, everything runs on the phone.

## Features

- Search by name or paste a MovieBox link (auto content-type detection:
  movie, multi-part movie, or series)
- Detail page: cover, title, year, duration, country, IMDb rating,
  genres, description, cast
- Dub/audio language switcher (Hindi, English, Arabic, French, …)
- Season picker for series (S1–S10 for Friends, etc.)
- Quality picker (360p / 480p / 720p / 1080p) with file sizes — with a
  Retry button if the request fails and an explanatory message when a
  title is VIP-only
- Subtitle selection — SRT files saved next to the video; the section is
  always visible (shows the catalog language list until the downloadable
  .srt chips load)
- Always-visible download buttons: big full-width "Download movie" card
  for movies, a labeled Get button on every episode row, plus the batch
  bar for multi-episode selection
- Batch download: tick multiple episodes or a whole season
- Download history with live progress, open file, and one-tap re-download
  (fetches a fresh signed link)
- Saves to the public **Downloads/MovieBox** folder (system
  DownloadManager — downloads survive app close/reboot, pause/resume,
  system notification with progress)
- **Debug & diagnostics screen** (bug icon on the Home top bar):
  - One-tap **end-to-end test**: connectivity → guest token → search →
    detail → play streams → subtitles → CDN download probe (1KB Range
    request), with per-step timing, status and the raw server response
  - **Live API log** of every HTTP exchange the app makes while you use
    it
  - **Copy full report** — puts a complete diagnostic report on the
    clipboard for remote debugging
- Material 3 "Material You" design with dynamic color (Android 12+) and
  light/dark mode
- No login — uses the site's guest access token

## Requirements

- Android 8.0+ (API 26)
- To build: Android Studio (Koala or newer recommended) OR
  JDK 17+ + Android SDK 35

## Build

Open this folder in Android Studio and press **Run**.
Command line:

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Release build (signed):

```bash
./gradlew assembleRelease
```

## Tech

- Kotlin 2.0, Jetpack Compose (Material 3), single-Activity + ViewModel
- OkHttp + kotlinx.serialization (no Retrofit — fewer moving parts)
- Coil for images
- System DownloadManager + MediaStore (scoped-storage friendly,
  API 29+) / legacy external storage path (API 26–28 with runtime
  permission)
- JSON-file history store (no Room / no annotation processing —
  fast builds)

## API endpoints (reverse engineered)

| Purpose | Endpoint |
|---------|----------|
| Guest token | `GET https://h5-api.aoneroom.com/wefeed-h5api-bff/country-code` → `Set-Cookie: token=…` |
| Detail | `GET {API}/detail?detailPath={slug}` |
| Play links | `GET https://themoviebox.xyz/wefeed-h5api-bff/subject/play?subjectId=&se=&ep=&detailPath=&streamSignType=1` (Bearer + Referer) |
| Captions | `GET {API}/subject/caption?format=MP4&id={streamId}&subjectId=&detailPath=` (Bearer) |
| Search | `POST {API}/subject/search {"keyword","page","perPage"}` (Bearer) |

CDN video links require the browser User-Agent and
`Referer: https://themoviebox.xyz/` header (both are set in
`DownloadRepository`).

If the site ever changes its API, the endpoints live at the top of
`api/ApiClient.kt` and are easy to update.

## Notes

- v1.5.2 — SafeSearch v3.2 "no poster leaks" + full crude vocabulary:
  (1) Blocked content no longer shows its cover in search results. Search
  results carry only title + genre (no description), so clean-titled adult
  items (e.g. "Seduced by My Best Friend", "The Young Seducers") passed the
  cheap check and rendered their poster — only to be blocked after tapping.
  When SafeSearch is ON, every result is now verified against its detail
  page (full isAdultDetail: title + description + genres) BEFORE the grid
  renders — bounded concurrency 6, 10s per-item timeout, fail-open on
  network error, verdicts cached per detailPath. The hidden count badge
  reports cheap + detail-verified hides. (2) Full crude sexual vocabulary
  added on the user's request (dick, fuck + stem, ass/asshole, arse,
  pussy/pushy, boobes, bum, snatch, cunt, twat, cock, cum, jizz, horny,
  slut/whore/bitch, step-family, bdsm/fetish, adult-site names xnxx/xvideos/
  brazzers …, misspellings pusy/dik/dyck/fuk/phuck, censored forms f*ck /
  f**k / f uck, and word-form gaps seduced/seducer). Over-blocking is
  accepted by design ("Kick-Ass", "Dick Tracy", "Snatch" hide under
  SafeSearch) — SafeSearch is a toggle. (3) Fuzzy false-positive fixed:
  "chennai" is edit-distance 2 from "hentai" and was hiding the Indian
  "Beast" (2022) — added a fuzzy whitelist. Anatomy words are whole-word
  only (no stems) so "Dickens"/"cocktail"/"assassin"/"Moana"/"Puss in
  Boots"/"JFK" stay visible. Regression suite now 301 checks — all pass.
- v1.5.1 — SafeSearch v3.1 "poster guard": adult content that the site does
  NOT tag as "Adult" and whose titles carry no English keyword used to pass
  the filter, so its explicit cover art still appeared in search results.
  Closed by (1) a new hentai-OVA naming-pattern layer: this site's untagged
  eroge-adaptation OVAs almost all end in "The Animation"/"The Anime"
  ("Mesudachi The Animation", "Sei Yariman Sisters Pakopako Nikki The
  Animation") — legit shows using the same pattern (Danganronpa, Persona
  franchises) are protected by a whitelist, and the pattern is applied to
  titles only, never to descriptions; (2) Layer 1 now also blocks the site's
  "Erotic" genre tag (its softcore catalog — verified legit erotic thrillers
  like Basic Instinct / Fifty Shades are NOT tagged Erotic); (3) ~55 new
  title blocklist entries and romanized Japanese adult vocabulary harvested
  live from the catalog (yariman, chikan, sukebe, sekuhara, enkou, kutsujoku,
  kisaku/shusaku, ingyaku, enjo kouhai …); (4) missed English adult terms
  (rape/orgasm) and censored spellings ("F*cked" → "f cked" after
  normalization). Regression suite extended to 139 checks
  (scripts/test-filter-v3.ts) — all harvested hentai hidden, all legit
  controls (Danganronpa/Persona "The Animation", Batman "The Animated
  Series", Mahou Shoujo Madoka, Fifty Shades, Chicken Little …) still
  visible.
- v1.5.0 — UI v2 "Cinematic Dark" redesign: always-dark OLED-friendly
  palette (near-black #0A0C11 surfaces, brighter cinema blue, warm amber
  accent) — light mode retired so the app looks identical everywhere;
  bottom nav now has three tabs (Home / Downloads / Debug with the
  active-download badge, outlined+filled icon states); Home gets
  persistent recent-search chips (tap to re-search, X to remove, stored
  in prefs, max 8), a shimmering skeleton poster grid while loading, a
  clear (X) button in the search field, and press-scale animation on
  poster cards; Detail drops the top app bar for a floating scrim back
  button over a taller 330dp hero, and episode rows now use numbered
  colored badges (tap row = batch-select, Get = download that episode);
  Downloads rows get color-coded status pills (blue Queued, amber
  Downloading, green Completed, red Failed, gray Canceled) with
  amber-tinted progress bars.
- v1.4.2 — SafeSearch v3, variation-proof: indirect searches like
  "pornx", "p0rn", "s3x", "hantaiy", "henati" are now caught by
  (a) de-leet normalization applied to both the text and the keyword
  lists (p0rn → porn, h3ntai → hentai), (b) prefix stems so any word
  starting with porn/sex/hentai/ecchi matches ("pornx", "pornstar"),
  (c) fuzzy edit-distance for misspellings ("hantaiy" ≈ hentai), and
  (d) explicit common misspellings (pron, hantai, henati, hentia,
  etchi). New query gate: obviously adult-intent searches are refused
  outright with a clear message before any network call. Stems are
  deliberately tiny (no "anal"/"xxx" stems) so legit titles like
  "Analysis", "xxxHolic", "Se7en", "M3gan", "101 Dalmatians" still
  pass. All in `util/ContentFilter.kt`, one place to tune.
- v1.4.1 — SafeSearch v2, three layers: (1) the site's own "Adult"
  genre tag is now read from search results and detail pages (the
  catalog tags its Midnight/adult content, e.g. "Anime,Short,Adult") —
  blocked everywhere; (2) a curated title blocklist for untagged adult
  anime (Yoasobi Gurashi, Adam's Sweet Agony, Please Put Them On
  Takamine-san, Momoiro Bouenkyou / SWAMP STAMP "Anime Edition", NTR
  naming) — hidden directly in search results, not just on open; (3)
  expanded keyword list (~100 words incl. ecchi/lewd/fanservice,
  affair/step-family terms, censored, gravure). All in
  `util/ContentFilter.kt`, one place to tune.
- v1.4.0 — SafeSearch content filter (on by default, toggle on the search
  screen): hides adult titles from search results and blocks detail pages
  whose title/description/genres contain adult keywords (hentai, ecchi,
  porn terms, and site-specific markers like "uncut"/"blue film"). The
  filter is a pure word/phrase matcher (no false hits inside longer words)
  in `util/ContentFilter.kt` — edit the PHRASES list to tune it. Every
  hidden result is counted on screen and logged in the live activity log.
- v1.3.0 — UI overhaul: removed the cast section from the detail page;
  new branded cinematic theme (blue + amber, fixed palette instead of
  the default purple); Home gets a logo header, filled pill search bar
  and poster cards with gradient scrims (title/year overlaid, type
  badge); Detail gets a taller hero with IMDb/meta row and genre badge,
  accent-bar section labels, polished episode rows and big gradient
  download buttons; the batch bar now slides in/out animated as a
  floating pill; Downloads rows are cleaner with status icons and a
  list summary line.
- v1.2.1 — fixed downloads failing on Xiaomi/MIUI/HyperOS devices
  ("IllegalArgumentException: Not a file URI: null"): some OEM
  DownloadProviders reject MediaStore content:// destinations. The
  downloader now uses a destination cascade — public Downloads via
  MediaStore first, then (if the device refuses it) the app's private
  staging folder with a plain file:// URI, and the finished file is
  moved into public Downloads/MovieBox automatically when the download
  completes (retried on next app launch if the app was closed). The
  live log records which strategy was used and every move.
- v1.2.0 — REAL end-to-end debug: a unified always-on activity log
  (AppLog) records everything between opening and closing the app —
  process/activity lifecycle, screen navigation, every user action,
  every HTTP request with full request/response detail (token
  shortened), parse results, download enqueues with 10% progress
  milestones, completions with speed, failures with DownloadManager
  reason codes. The Debug screen now has three tabs: Live (filterable
  real-time feed, per-entry copy, copy-all, Android share), E2E test,
  and Session (device info + the previous session's saved log). The
  log persists to filesDir/applog.jsonl and survives restarts.
- v1.1.1 — added `ACCESS_NETWORK_STATE` to the manifest (the diagnostic
  report crashed with SecurityException when reading the network state)
  and made the report builder fully crash-proof; the report now also
  shows the permission state.
- v1.1.0 — fixed the guest-token request running on the main thread
  (NetworkOnMainThreadException made search/quality/subtitles fail on
  real devices), made the QUALITY and SUBTITLES sections always visible
  with retry, added always-visible download buttons, and added the
  Debug & diagnostics screen.
- Use for personal viewing only — the content is copyrighted.
- Debug APK is signed with the debug keystore — install via
  "Install unknown apps" on your phone.
