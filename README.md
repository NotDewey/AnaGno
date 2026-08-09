# Comic Reader — V1 Skeleton

A working Android Studio project skeleton for the V1 MVP described in the plan:
CBZ import, library grid, reader with horizontal swipe / vertical scroll / pinch &
double-tap zoom / landscape double-page, last-page memory, bookmarks, dark theme,
and Coil-based image caching.

## Opening the project

1. Open this folder (`ComicReader/`) directly in Android Studio (Koala or newer).
2. Let Gradle sync — it will pull Compose BOM 2024.06, Hilt, Room, Coil, Commons Compress.
3. Run on a device/emulator with API 26+.

## Structure

```
app/src/main/java/com/comicreader/app/
├── ComicReaderApp.kt          # @HiltAndroidApp application class
├── MainActivity.kt            # single Activity hosting Compose nav graph
├── di/AppModule.kt            # Hilt providers: Room DB, DAOs, CbzExtractor
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt
│   │   ├── entities/          # ComicEntity, BookmarkEntity, PanelEntity (V2-ready)
│   │   └── dao/                # ComicDao, BookmarkDao, PanelDao
│   ├── cbz/CbzExtractor.kt     # unzips CBZ (via SAF Uri) into cache, natural-sorts pages
│   └── repository/ComicRepository.kt  # single source of truth used by ViewModels
├── domain/model/Models.kt      # Comic, Bookmark, Panel — plain data classes
└── ui/
    ├── theme/                  # Material3 theme, dynamic color, dark mode
    ├── navigation/NavGraph.kt  # Library <-> Reader
    ├── library/                # LibraryScreen + LibraryViewModel (grid, import, search, favorite)
    └── reader/                  # ReaderScreen + ReaderViewModel (pager, zoom, scroll, bookmarks)
```

## What's already wired up

- **Import**: SAF `OpenMultipleDocuments` picker → persisted URI permission → extraction → Room insert.
- **Library**: adaptive grid of covers via Coil, favorite toggle, search-as-you-type, reading-progress bar.
- **Reader**:
  - `HorizontalPager` for page-by-page swiping; in landscape it groups pages into
    two-up spreads as a first pass at "double-page mode" (still needs RTL-aware
    ordering per series — see TODO below).
  - A `LazyColumn` alternative for vertical scroll (webtoon-style) mode.
  - Pinch-to-zoom + double-tap-to-zoom via raw `detectTransformGestures` /
    `detectTapGestures`, implemented so it doesn't fight the pager's swipe gesture.
  - Last-read page is written back to Room on every page change.
  - Bookmarks: add-current-page action wired to the DB; a bookmarks *list/jump* UI
    isn't built yet (see TODO).
- **Panel/PanelEntity table already exists** in the schema (unused by V1's reader) so
  Guided View (V2) can be added later without a destructive DB migration.

## Known TODOs before calling V1 "done"

1. **Bookmark list UI** — currently you can add a bookmark for the current page,
   but there's no drawer/sheet listing bookmarks to jump back to. Quick add: a
   `ModalBottomSheet` in `ReaderScreen` fed by `state.bookmarks`.
2. **True double-page spread logic** — right now it naively chunks pages by 2 in
   landscape; a real implementation should let a series declare reading direction
   (LTR/RTL) and should not force-pair a cover page with page 2.
2. **Fast image caching tuning** — Coil's defaults are already reasonable, but for
   very large libraries add a custom `ImageLoader` in a Hilt module with a larger
   disk cache and enable `crossfade`.
4. **Delete-from-library swipe/long-press** — `ComicRepository.deleteComic` exists;
   just needs a UI trigger (long-press → confirm dialog) in `LibraryScreen`.
5. **Error/empty states for corrupt CBZ files** — `CbzExtractor` will throw if the
   zip is unreadable; wrap `importCbz` calls in the ViewModel with a try/catch and
   surface a Snackbar.
6. **Instrumented + unit tests** — none included yet; `ComicRepository` and
   `CbzExtractor.NaturalOrderComparator` are the two most valuable things to test
   first since they're pure logic with no Android framework dependency.

## Where V2 (Guided View) hooks in

`PanelEntity` / `PanelDao` are already part of the schema. The plan's
`PanelDetectorInterface` idea slots in as a new module:

```kotlin
interface PanelDetector {
    suspend fun detectPanels(pageBitmapPath: String): List<PanelRect>
}
```

Bind whichever implementation (heuristic CV first, TFLite model later) via Hilt's
`@Binds`, so `ReaderViewModel`/`ReaderScreen` never need to know which one is active.
