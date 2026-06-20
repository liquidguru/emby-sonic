# Codex task — Now Playing widget album art won't reliably show

The home-screen widget (4x2: art, title/artist, progress bar + times, transport,
cast icon) works **except the album art**. Art shows **only right after the
widget is (re)added in the current app session**; after a force-close → reopen →
play, it comes back **blank**. This has resisted multiple fixes — full writeup
below so you can take a fresh run. Judge on the real Pixel 8 Pro over wireless
adb (note: the wireless-debug port changes after Wi-Fi toggles / sleeps).

## Where things stand
- Baseline commit: `c534a46` (widget redesign, `setImageViewBitmap`).
- On top of that there are **uncommitted** experiments (this doc's WIP commit):
  FileProvider URI + launcher grant + `<queries>` + unique-filename cache-bust +
  diagnostic logging. **Still blank on the fresh-process path.**
- Everything else about the widget (layout, progress bar, elapsed/total times,
  transport controls, cast icon, "Casting to <device>") works.

## Key files
- `widget/NowPlayingWidget.kt` — builds the RemoteViews (`render` full update,
  `renderProgress` partial update, `applyProgress`, `buildViews`, `grantArtToHost`).
- `playback/PlaybackController.kt` — `startWidgetUpdates()` collector,
  `loadWidgetArt()`, `widgetArtUrl()`, `refreshWidget()`, fields
  `lastWidgetArtUrl` / `lastWidgetArtUri` / `lastWidgetHeavyKey` /
  `widgetProgressTicks`, const `WIDGET_HEAL_TICKS`.
- `widget/NowPlayingWidgetProvider.kt` — `onUpdate` → `playback.refreshWidget()`.
- `res/layout/widget_now_playing.xml`, `res/xml/now_playing_widget_info.xml`,
  `res/xml/widget_file_paths.xml`, manifest `<provider>` (FileProvider authority
  `${applicationId}.widgetart`) + `<queries>` for HOME.

## Reproduction
1. Add the widget while a song is playing → **art shows** and persists through
   pause/skip in that session.
2. Force-close the app (swipe from recents), reopen, play a song → **art blank**
   (the rest of the widget — title/artist/progress/controls — renders fine).
3. Re-adding the widget while playing makes it show again.

## What was tried, and the result
1. **Coil default loader + `BitmapDrawable` cast** (original): art never painted.
   Cause found: Coil's default image loader has no Emby auth interceptor, and
   `result.drawable` isn't always a `BitmapDrawable`. Fixed loading with
   `api_key` appended (`widgetArtUrl()`) + `drawable.toBitmap()`. Confirmed via
   log the bitmap loads (`hasBmp=true`, 512x460).
2. **`setImageViewBitmap`, full update every ~1s** (bitmap re-sent each tick):
   art does NOT paint at all. Strong evidence the launcher's RemoteViews **bitmap
   cache** is thrashed by frequent bitmap re-sends (size 512 and 256 both failed).
3. **`setImageViewBitmap`, bitmap only on track change + `partiallyUpdateAppWidget`
   for the per-second progress**: art DID paint ("washed perfectly") — but later
   went blank and only a widget re-add fixed it. Hypothesis: the launcher drops
   the cached bitmap on re-inflation / across app-process death, and nothing
   re-supplies it (partials carry no bitmap). Added a ~30s full "heal" render;
   still blank on the fresh-process path. Diagnostic log showed
   `full=true hasBmp=true` repeatedly with **no paint** in the fresh-process case
   → the launcher is rejecting/ignoring the bitmaps (cache pollution that
   survives our app's death; only a re-add with a fresh widget id clears it).
4. **FileProvider URI (`setImageViewUri`)** instead of a bitmap: first attempt →
   "**can't load widget**". Launcher log: `Permission Denial: opening provider
   androidx.core.content.FileProvider from com.google.android.apps.nexuslauncher
   ... not exported from UID`. Our `grantUriPermission` wasn't landing.
5. **Add `<queries>` for HOME + grant to resolved launcher**: grant now resolves
   `com.google.android.apps.nexuslauncher` and the **Permission Denial is gone**.
   But the widget is **still blank**. Cache file verified valid on device
   (`run-as ... ls -l cache/widget_art/` → a 215 KB PNG). Hypothesis: the
   launcher caches images **by URI** and won't re-read a URI it has already seen
   (it cached the earlier permission-denied/blank result for that stable URI).
6. **Unique filename per load** (`art_<millis>_<hash>.png`, prune older) so the
   URI changes every load and forces a reload: **still blank** on the
   fresh-process path.

## Confirmed facts (so you don't re-derive them)
- `loadWidgetArt` succeeds: a valid PNG is produced (215 KB on disk).
- `render` fires in the failing case with a real `artUri`
  (`content://guru.liquid.embysonic.widgetart/widget_art/art_*.png`), and the
  grant resolves `com.google.android.apps.nexuslauncher`. No Permission Denial.
- So: the app side sends a valid image + a readable URI, and the launcher still
  shows blank. The failure is in how the launcher loads/caches the image, on the
  fresh-process path specifically (works right after a re-add in-session).

## Diagnostic logging currently in place (remove when fixed)
- `NowPlayingWidget.render`: `Log.i("WidgetArt", "render ids=.. artUri=..")`
- `NowPlayingWidget.grantArtToHost`: `Log.i("WidgetArt", "grant home=.. uri=..")`
Capture with: `adb -s <dev> logcat -s WidgetArt:*` and grep launcher errors with
`Permission Denial|RemoteViews|AppWidgetHostView|exceeds`.

## Suggested next angles (not yet tried)
- **`AppWidgetManager.notifyAppWidgetViewDataChanged`** isn't relevant (no
  collection), but consider whether the launcher needs `updateAppWidget` (full)
  rather than any `partiallyUpdateAppWidget` having been the last call before the
  blank — try **full updates only** (no partial at all) with the URI (URI payload
  is cheap, so per-tick full updates are fine) and see if art persists.
- Verify the launcher actually re-reads: log from a `ContentProvider`/openFile
  hook whether the launcher opens the file in the failing case (if it never
  opens it, it's a launcher-side cache/url-dedup issue; if it opens it and still
  blank, it's decode/scaleType).
- Consider a **GlanceAppWidget** rewrite (Jetpack Glance handles image state and
  process death more robustly than hand-rolled RemoteViews) — bigger change but
  may sidestep the whole class of RemoteViews bitmap/URI caching problems.
- Confirm whether the in-app NowPlaying art and the widget art use the same URL;
  in-app art works, so the URL/auth is fine — the problem is purely the
  RemoteViews/launcher image path.

## Constraints
- Music app, dark-first. Keep the rest of the widget (progress/cast/controls) intact.
- Verify on the real Pixel 8 Pro **and** specifically the force-close → reopen →
  play path, not just re-adding the widget.
- Follow the AGENTS.md git routine (commit per step, push, pull the NAS checkout
  at `/volume1/docker/emby-sonic`). Remove the `WidgetArt` diagnostic logs and
  update `docs/spec.md` when fixed.
