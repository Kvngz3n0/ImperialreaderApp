# Imperial Reader

Imperial Reader is an Android app for discovering and reading manga, manhwa, manhua, comics, EPUB books, and web novels, as well as watching anime and other supported media. It brings online catalogs, downloaded chapters, and your own local files together in one library.

The current release is [V18](https://github.com/Kvngz3n0/ImperialreaderApp/releases/tag/V9) (version code 25). Its APK is debug-signed with the same certificate as the previous V18 build. Because its version code is unchanged, install it manually rather than relying on the in-app updater. The repository's `main` branch currently contains older V15 code; use the source ZIP attached to the release for the actual V18 build source.

> Online catalogs are third-party sites and services. Their availability, content, and site layouts can change; a source that stops working may need to be disabled or replaced.

## Get started quickly

### Install the app

1. Open the [GitHub Releases](https://github.com/Kvngz3n0/ImperialreaderApp/releases) page and download the APK from the latest release.
2. Open the APK on your Android device and approve installation from that source if Android asks.
3. Launch **Imperial Reader**. The app needs internet access to browse online catalogs. Notification permission is optional and may be requested on Android 13 and newer.

The app supports Android 8.0 (API 26) and later.

### Find and read something

1. Open **Browse** to discover titles in **Latest**, **Novels**, **Media**, or **Adult**. Use **Manage sources** in the three-dot menu to open source directories or the **Novel catalogue**.
2. Browse the aggregated feed or search the current section. Long-press a card to open its source or catalog.
3. Open a title. Add it to your library, then tap **Start** or a chapter to read.
4. Use the **Library**, **Updates**, and **History** tabs to return to saved titles, check for new chapters, and continue recent reading.

For manga and comics, tap the reader to show controls. Choose left-to-right, right-to-left, or vertical/webtoon mode in the reader; your default mode and other reading preferences are in **More → Settings**.
Library cards fade in as the grid first appears and fade in or out when search results or library entries change.

### Read your own files

1. In **More → Storage folder**, choose a folder and grant access. Imperial Reader creates `local/`, `downloads/`, and `autobackup/` inside it.
2. Put local files under `local/<Title>/`. Supported chapters include image folders, CBZ/ZIP/CBR/RAR archives, PDF, EPUB, PSD, PSB, and CLIP files.
3. Choose **More → Scan local library** to import or refresh your local titles.

PDFs open in a paged reader with page navigation and saved progress. You can also browse Project Gutenberg and Internet Archive for EPUBs, or browse supported web-novel catalogs including NovelArrow. Downloaded web-novel chapters can be packaged as an EPUB. EPUB reading includes adjustable text size, dark/sepia/light themes, text-to-speech, and a seekable chapter progress bar.

## What it can do

### Discover and search

- Browse manga, manhwa, manhua, webcomics, and western comics from built-in sources.
- MangaDot is available under **Sources**. Its chapter and reader requests may require solving a Cloudflare challenge in the in-app WebView.
- MangaBall and Toonkotsu are experimental sources and may require a Cloudflare challenge. Toonkotsu's encrypted chapter images are not yet supported.
- Toonily (`https://toonily.com`) uses the app's Madara engine and Cloudflare solver; it is listed with the webtoon, manhwa, and manhua sources.
- Browse online EPUB and web-novel catalogs, including Project Gutenberg, Internet Archive, Royal Road, NovelArrow, Witch Cult Translations, Baka-Tsuki, and Cyrisia. Cyrisia lists non-mature web novels; Baka-Tsuki projects without detected chapters are omitted.
- Browse anime and other supported media under **Media**. Anime entries list episodes; some media sources organize posts under creators and provide a **General** gallery for individual posts.
- Rule34 World is listed under **Media**. MioHentai lists individual video titles and uses the anime player; previously saved studio entries remain accessible.
- AnimeFlixer series appear under **Media** and use the native player with stream discovery. Its catalog includes series only; playback and downloads depend on individual video hosts.
- Atsumaru appears under **Sources** with its non-adult comic catalog, chapters and reader images. Its latest tab shows recently added titles.
- Anikoto appears under **Media** with separate sub/dub servers in the native player. Image-wrapped downloaded streams are prepared in temporary app cache for offline playback, which requires enough free space for a second copy while playing. Stream discovery and downloads depend on the video host and require local verification.
- Search across online catalogs from **Browse** or from the library's global search. Global search preserves same-title results from separate catalogs, combines manga sources when requested, and offers optional genre/tag and artist filters.
- Global search offers separate **Sources**, **Extensions**, and **Media** selections. Migration lists identify media destinations and search creator-based sources by creator.
- Add a custom manga site by name and URL. Imperial Reader attempts to detect supported site engines when you first open it.
- Enable or disable sources and open a source in the in-app WebView. The app attempts to resolve Cloudflare challenges in a WebView and shares its cookies with network requests. If that fails, open the site in the WebView, complete any challenge, and retry. This does not guarantee access or compatibility with every site.
- Adult (18+) sources and ratings are hidden by default and can be enabled in Settings after age confirmation.
- Tap a genre tag on a title to copy it, search the library, or search globally. Long genre lists have **Show more** and **Show less** controls.

### Organize your library

- Save titles to your library and group them into custom categories.
- Search, sort, and filter library entries, including by unread chapters, bookmarks, and downloaded chapters.
- Select multiple titles to move them between categories, remove them, or queue reviewed bulk migration matches. Manga, web-novel, and catalog-backed book entries use their compatible migration/import paths.
- See unread and downloaded/local badges, with display options in Settings.
- See series publication states as ongoing, completed, hiatus, or axed when a source provides status information.
- Refresh a title's chapter list or update the library for new chapters.

### Read, track, and download

- Read online or downloaded manga/comic chapters in left-to-right, right-to-left, or vertical/webtoon layouts.
- Resume reading progress, move between chapters, and optionally use screen-edge taps or volume keys for page navigation.
- Use double-tap zoom in the image reader. Direct video pages have playback controls, including seeking and speed, where supported.
- The **General** gallery on supported sources identifies photos and videos where possible and lets you save available media. A gallery search displays individual posts; searches in creator-oriented tabs find creators.
- Watch anime with episode navigation, server selection, and SUB/DUB selection where available. Some sources use their website's player; downloadable episodes require a direct playable stream.
- Bookmark chapters, mark chapters read or unread, and see reading history. Incognito mode disables reading-history entries.
- Queue chapter downloads, follow the queue in **More**, and read downloaded chapters offline.
- Browse and read EPUBs and web novels, seek between EPUB sections, resume saved chapter/page progress, adjust text size and theme, and listen with device text-to-speech.
- Optionally show only downloaded titles in the library.
- Enable the **Reading monitor** in Settings to track reading and anime viewing time. Its statistics screen provides date ranges, completion counts, daily activity, top titles and genres, breakdowns, CSV export, and a reset option. Time starts accumulating after tracking is enabled; completion counts can include existing read flags.

### Local files and backups

- Import local image folders and CBZ, ZIP, CBR/RAR (RAR 2–4), PDF, EPUB, PSD, PSB, and CLIP files as library titles. Photoshop and CLIP artwork is rendered from supported previews or decoded composites.
- Read local titles from the library and refresh the scan when files change.
- Choose where downloads and local files are stored; without a selected folder, the app uses private app storage.
- Export or restore a JSON backup containing library titles, categories, chapter read/bookmark/progress state, and history. Restore merges with the current library rather than deleting it.
- Run restores in the background and monitor progress or completion in the Android notification.
- Create automatic backups manually or let the app run them periodically; it keeps a rolling set of the five most recent automatic backups.

### Browse aggregation

Browse offers Latest, Novels, Media and Adult sections. Latest and Media aggregate enabled regular sources; Novels aggregates the EPUB, PDF and web-novel catalogs (entries open through the existing book flow); Adult uses enabled 18+ sources only when the existing age-confirmed setting is on. Mixed-content providers such as MangaDex are never excluded; they appear in Latest and, when 18+ is enabled, also in Adult. Sources are classified by their existing source-level flags; mixed-content providers without item ratings cannot be independently filtered by the feed.

Use **Manage sources** in the Browse three-dot menu to open the existing reading or media source directory, or the Novel catalogue; **Refresh** is in the same menu and pull-down refresh also works. Long-press an aggregated card to browse its provider. Source pages are interleaved by source position because the shared result model does not expose update timestamps. Feeds load more entries and retry failed sources automatically when you scroll near the bottom. The fetched-entry count and failure details are hidden unless enabled in More → Settings → Show number of entries fetched; Cloudflare challenges are shown regardless. Searching from Browse searches the current section in place and respects the global search concurrency setting.

## Settings

Open **More → Settings** to configure:

- **Cascading Animations** adds a brief sequential entrance to supported navigation, lists, and the More screen. New installs enable it by default; existing installs can opt in. Android's disabled animator setting takes precedence.
- **Library transitions** independently controls category changes and main-tab transitions with a 2.5-second animation. Choose fade, horizontal slide, fade and slide, or Cascade (the 2.7-second domino fade, applied to the Library grid and other tabs' content); the setting is enabled by default and respects Android's disabled animator setting.
- Default manga/comic reading direction, screen-on behavior, fullscreen mode, edge taps, and volume-key navigation.
- Choose Imperial, Ocean, Forest, Rose, AMOLED Black, or Android device colors; set the app to follow the system, light, or dark appearance. AMOLED Black uses a dark palette in every mode. Theme colors use Material color roles across app screens without tinting cover artwork or reader pages.
- **Manage Categories** under Library creates, renames, deletes, and drag-reorders Library categories. Deletion moves entries to Default without deleting their content.
- App lock, library grid columns, unread/download badges, and automatic library updates.
- Download concurrency, automatic app-update checks, automatic cover refresh, global-search concurrency/timeouts, and optional skipping of duplicate chapters and gaidens.
- Cover extraction for local entries.
- Visibility of adult sources and ratings.
- Reading monitor tracking and statistics.
- Optional source health monitoring. When enabled, opening a Browse list checks its enabled online catalogs (at most three at once); green means the last check returned entries, red means it failed, and gray means it has not been checked. The display shows failed health checks and the last successful check. These are probe results, not a count of all app requests. Sources are bundled with the app, so individual source-update availability cannot currently be determined.
- Clearing cached images/archive data or saved site cookies.

The EPUB reader has its own text-size controls, light/dark/sepia theme selector, seekable chapter progress, and text-to-speech button. WEBTOON episodes can include audio when the source provides it; entries without audio remain image-only.

## Build from source

### Requirements

- Android SDK platform 34 and Android build tools 34.0.0.
- JDK 17.
- Gradle 8.2.

### Build a debug APK

From the repository root:

```sh
cd android/ImperialReader
gradle assembleDebug
```

The APK is written to `android/ImperialReader/app/build/outputs/apk/debug/`. A GitHub Actions workflow also builds a debug APK when Android project files are pushed to `main` or `master`, or when manually dispatched; the uploaded workflow artifact is retained for 14 days.

To build a release APK, run `gradle assembleRelease` from `android/ImperialReader`. Without a configured signing key, Gradle produces an unsigned release APK.

## Repository layout

- `android/ImperialReader/` — Android application source and Gradle build.
- `.github/workflows/build-imperialreader-apk.yml` — repository APK build workflow.
- `artifacts/`, `lib/`, and `scripts/` — additional workspace artifacts and tooling.

## Notes

- The app depends on third-party websites and public catalog APIs. Site changes, network availability, regional restrictions, or anti-bot checks can affect browsing and downloads.
- Imperial Reader does not run Mihon/Keiyoushi APK extensions; its **Novels** tab contains results from built-in book and web-novel catalogs. QiManga is present but its live API responses could not be verified because the service returned HTTP 403.
- Application appearance is resource-based and separate from EPUB/PDF reader appearance. Cover artwork and comic pages are rendered without application theme tinting.
- Use custom sources only with sites and material you are authorized to access. Adult sources are intended only for adults 18 and older.
- Imperial Reader is an independent reader app and is not affiliated with the third-party catalogs it can access.
