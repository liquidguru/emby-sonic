# Codex task — batch 4: sleep timer, audiobook speed, Android Auto

emby-sonic (liquidWave Android) is in daily use on a Pixel 8 Pro. Read
`AGENTS.md` and `docs/spec.md` first. HEAD at handoff is `adc9478`, tree clean.

---

## 1. Sleep timer

A timer that stops playback after a set duration. Useful for audiobooks in bed.

**UX:**
- Accessible from Now Playing (an icon button in the top bar, or an overflow
  menu item — pick whichever fits the existing layout without crowding it).
- Options: 5 min, 10 min, 15 min, 30 min, 45 min, 60 min, and "End of chapter"
  (for audiobooks — stop after the current track finishes).
- Once set, a small countdown indicator is visible on Now Playing (e.g. a chip
  showing "32m" that updates each minute). Tapping it cancels the timer.
- When the timer fires: fade volume to zero over ~3 seconds, then pause. Do not
  stop/destroy the session — just pause so the user can resume in the morning.
- Timer state survives screen rotation but does NOT survive process death
  (in-memory is fine; no need to persist across app restarts).
- Cancel the timer automatically if the user manually pauses.

**Implementation notes:**
- A simple coroutine-based countdown in `PlaybackController` is sufficient.
  No `AlarmManager` needed — the app holds a wakelock during playback already.
- "End of chapter" mode: watch for the track-end event and pause before
  `PlaybackController` auto-advances to the next track.

---

## 2. Audiobook playback speed

Variable speed for audiobooks (and only audiobooks — music stays at 1.0×).

**UX:**
- A speed button visible on Now Playing when `contentKind == AUDIOBOOK`.
  Not shown for music.
- Options: 0.75×, 1.0×, 1.25×, 1.5×, 1.75×, 2.0×.
- Current speed shown on the button (e.g. "1.5×").
- Persisted to `SettingsRepository` / DataStore so it survives restarts and
  applies to the next audiobook session automatically.
- Pitch correction on (use `PlaybackParameters(speed, pitch=1.0)` so faster
  speed doesn't chipmunk the voice).

**Implementation notes:**
- ExoPlayer supports `player.setPlaybackParameters(PlaybackParameters(speed))`.
  Apply when a queue with `ContentKind.AUDIOBOOK` starts, and reset to 1.0×
  when a music queue starts (so a shuffle of songs after an audiobook doesn't
  play at 1.5×).
- The speed setting lives alongside the existing EQ settings in
  `SettingsRepository`.

---

## 3. Android Auto browse tree

liquidWave should appear as a music source in Android Auto, letting Kaj browse
and play from the car display.

**UX goal (minimal viable):**
- liquidWave appears in the Android Auto media sources list.
- Browse tree exposes at least: Recent plays, Sonic Mixes, Albums, Artists.
- Tapping a Recent plays tile or a mix starts that queue.
- Playback controls (play/pause, next, previous) work from the Auto display.
- Now Playing shows track title, album, artist, and artwork on the Auto screen.

**Implementation notes:**
- This requires implementing `MediaLibraryService` (Media3) or upgrading the
  existing `MediaSessionService` to `MediaLibraryService`, which adds the
  `onGetLibraryRoot` / `onGetChildren` / `onGetItem` callbacks that Auto uses
  to build the browse tree.
- Declare `android.hardware.type.automotive` feature (not required) and add
  `<meta-data android:name="com.google.android.gms.car.application"
  android:resource="@xml/automotive_app_desc" />` plus an
  `automotive_app_desc.xml` declaring `<uses name="media"/>`.
- The existing `SonicPlaybackService` likely needs to extend
  `MediaLibraryService` rather than `MediaSessionService`. The player and
  session already exist; the main work is the browse callbacks.
- Keep the phone UI completely unchanged — the browse tree is only consulted
  by Auto/Cast clients, not the phone app itself.
- Do NOT implement voice search or complex content ratings for this pass —
  just a working browse + play.

**Verify** by running Android Auto on the phone in "Developer mode" (Settings →
Apps → Android Auto → triple-tap version → Developer settings → start head unit
server), then connecting via `adb forward tcp:5277 localabstract:/adb/preview/auto`
and opening `head-unit-one` or the desktop head unit emulator. Confirm the
browse tree appears and a queue starts.

---

## Constraints (from AGENTS.md)

- Judge all audio/UI changes on the **real Pixel 8 Pro over wireless ADB**, not
  the emulator. Drive the phone via screenshots + taps, not `uiautomator`.
- Keep the equalizer working across all playback paths.
- Follow the AGENTS.md git routine: commit each logical step with the agent
  trailer, push, and `git pull` the NAS checkout.
- Update `docs/spec.md` with an M4.11 entry once all three are verified.
- Don't claim anything is verified that you couldn't run on the real device.

---

## Context

- **HEAD `adc9478`** — playlist track removal (M4.10, verified).
- **`adc9478` ← `e374f66`** — genre mixes, offline prefetch, Bluetooth AVRCP
  duration fix (M4.9, verified on Pixel 8 Pro by Kaj).
- **Crossfade** fixed `ec381c9` — Emby `PlaySessionId` collision, not
  audio-HAL. Two-player architecture confirmed viable.
- **ContentKind** (`MUSIC` / `AUDIOBOOK` / `UNKNOWN`) is already stamped on
  every `PlaybackTrack` — use it to gate the speed button and auto-reset.
- **`SettingsRepository`** already persists EQ settings to DataStore — add
  audiobook speed alongside it.
- App is stable and in daily use. All prior batches shipped and verified.
