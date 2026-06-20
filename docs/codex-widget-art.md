# Codex task - Now Playing widget album art reliability

> **RESOLVED 2026-06-20:** Album art now shows on the Pixel 8 Pro through the
> force-close -> reopen -> play path. The fix is on master: split the widget art
> tile into separate real-art and placeholder `ImageView`s, keep FileProvider URI
> artwork on full `updateAppWidget` renders, and remove progress-only partial
> updates plus the temporary `WidgetArt` diagnostics.

## Original bug

The 4x2 Now Playing home-screen widget showed title/artist, progress bar and
times, transport controls, cast icon, and casting subtitle, but album art was
blank after:

1. Force-close liquidWave.
2. Reopen the app.
3. Play a song.
4. Return to the launcher.

Album art did show immediately after re-adding the widget in the same app
session, which made this specific fresh-process path hard to diagnose.

## What was tried

- Coil auth/bitmap loading was fixed by appending `api_key` in
  `PlaybackController.widgetArtUrl()` and using `drawable.toBitmap()`.
- Sending `setImageViewBitmap` on every tick caused Pixel Launcher not to paint
  art reliably.
- Sending bitmaps only on heavy updates plus progress-only
  `partiallyUpdateAppWidget` worked briefly, then failed after process death.
- FileProvider URI art required `<queries>` for HOME and a launcher grant to
  avoid `Permission Denial`; the launcher still showed blank in the fresh-process
  path.
- Unique URI filenames avoided stale URI caching but did not fix the blank art.
- Full `updateAppWidget` renders with URI art alone were not enough.

## Root cause and fix

The same `ImageView` had been used for both the placeholder and real album art.
On the force-close path the widget could first render without content and tint
that view as a placeholder. Later renders set a real FileProvider URI on the same
host view, but Pixel Launcher preserved enough of the placeholder view/tint state
that the art did not visibly paint. Re-adding the widget while already playing
skipped that placeholder-first state, which is why re-add appeared to fix it.

The final layout uses:

- `widget_art_frame`: tinted rounded tile background.
- `widget_art`: real album art only; receives URI art and no placeholder tint.
- `widget_art_placeholder`: placeholder icon only; receives the accent tint.

`NowPlayingWidget.render()` now switches visibility between those two child
views. `PlaybackController.startWidgetUpdates()` sends complete RemoteViews
updates with the cached FileProvider URI on each widget tick, so the launcher
always receives a full replayable widget state.

## Verification

Built with `./gradlew :app:assembleDebug`, installed on the real Pixel 8 Pro,
and user-verified the exact failing path:

1. Force-close liquidWave.
2. Reopen.
3. Start playback.
4. Return to the home screen.

Album art appeared in the widget. Progress, times, transport controls, and cast
icon remained intact.
