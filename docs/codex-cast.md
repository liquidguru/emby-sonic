# Codex task — Casting (Chromecast / Google Cast)

> **NEXT: Phase 2 — Emby progress reporting.** Phase 0 and Phase 1 are done.
> Phase 2.1 Cast volume polish is also done: Now Playing has an app-side Cast
> volume slider with optimistic updates, verified by Kaj on Pixel 8 Pro + SHIELD.

liquidWave (emby-sonic Android) should cast music to Chromecast / Google TV /
cast-enabled speakers. Read `AGENTS.md` and `docs/spec.md` first. Decisions are
locked (see below); follow the phased plan. Judge audio behaviour on the real
Pixel 8 Pro **and** a real cast target on the LAN — not the emulator.

## Locked decisions
- **Receiver:** Default Media Receiver (app id `CC1AD845`). No Cast console
  registration. A Styled/branded receiver can be swapped in later by changing
  only `CastOptionsProvider`.
- **v1 scope:** music only. Audiobooks (resume points, chapter nav, server-side
  seek on cast) are a deliberate follow-up phase — do not block v1 on them.
- **Cast button:** Now Playing top bar (done) + mini-player (Phase 3).
- Dark-first; local-only features (equalizer, crossfade, offline prefetch) are
  unavailable while casting — that is expected and accepted.

## The core architectural problem
Everything currently runs through one local `ExoPlayer` in `PlaybackController`,
which drives crossfade (two players), the equalizer (a local audio effect),
offline prefetch, and server-side seeks directly. Casting requires:
1. An **active-player indirection**: transport (play/pause/next/prev/seek) routes
   to either the local `ExoPlayer` or a remote `CastPlayer`, and local-only
   features are suppressed when remote.
2. A **cast-safe stream URL**: the receiver fetches Emby itself, so it cannot use
   the app's `X-Emby-Token` header. Use a self-contained URL — LAN `http` base,
   token as the `api_key` query param, transcoded to mp3 (the Default Media
   Receiver can't reliably decode FLAC). See `PlaybackController.castStreamUrl()`.

## Phase 0 — DONE (this is already on master)
Plumbing + a working one-track spike that proves the URL/auth/format:
- Deps: `androidx.media3:media3-cast`, `com.google.android.gms:play-services-cast-framework`.
- `cast/CastOptionsProvider.kt` (Default Receiver) + manifest `OPTIONS_PROVIDER_CLASS_NAME`.
- `Theme.EmbySonic.Cast` (AppCompat) — the MediaRouteButton + its dialogs need a
  Theme.AppCompat context; the rest of the app is Compose on a plain Material theme.
- `ui/cast/CastButton.kt` — MediaRouteButton in an AppCompat-themed `ContextThemeWrapper`.
- `cast/CastManager.kt` — registers a `SessionManagerListener`; on session connect
  it pauses local playback and loads the **current track** onto the cast device via
  `RemoteMediaClient.load(...)`. Init'd from `MainActivity` (guarded by Play Services).
- `PlaybackController.pause()` added (clean local pause for handoff).

Phase 0 does NOT do: queue handoff, transport routing to the cast device,
progress reporting from cast, disconnect handover, or feature gating.

## Phase 1 — DONE (2026-06-19, Pixel 8 Pro + SHIELD verified)
Active-player switching shipped:
- `CastManager` now creates a Media3 `CastPlayer` from the shared `CastContext`
  and uses both the Cast session listener and `SessionAvailabilityListener` to
  tell `PlaybackController` when remote playback is available.
- `PlaybackController` now has an active `Player` indirection. Local ExoPlayer is
  the default; the active player flips to `CastPlayer` on a music Cast session.
  Play/pause, next/previous, seek, queue index jumps, repeat, Guest DJ appends,
  and `publishState()` all route through the active player.
- `SonicPlaybackService` swaps `MediaLibrarySession.player` to the active player
  so the mini-player, media notification, lock screen, Android Auto, and widget
  control the same cast-backed session.
- The Phase 0 one-track `RemoteMediaClient.load(...)` spike is gone. Cast uses
  full-queue `MediaItem`s built from the configured LAN Cast server base,
  `api_key` query auth, forced mp3 transcoding, and rebased authenticated artwork.
- Queue handoff preserves current index and position in both directions. On Stop
  Casting/disconnect, local ExoPlayer resumes at the last live CastPlayer
  position instead of restarting the current song.
- Local-only processing is gated while casting: equalizer is suppressed, crossfade
  polling stops, and offline prefetch is cancelled. Guest DJ/mixes still operate
  on the queue. Audiobook casting remains deliberately out of scope.

Verification notes:
- User-driven first run on SHIELD: in-app cast control worked as intended.
- A later Stop Casting test initially resumed locally at the start of the current
  song; fixed by snapshotting the last active cast index/position during
  `publishState()` and preferring that snapshot during remote->local handoff.
- Final ADB/logcat verification on Pixel 8 Pro + SHIELD: `Bamboleo` handed off
  local->remote around 9.9s, Stop Casting handed back remote->local at 63.7s, and
  Now Playing resumed locally around 1:09 rather than 0:00.

## Phase 0 spike results (2026-06-19, on-device)
- **Audio-only speaker: WORKS.** Casting a music track plays on the speaker,
  pauses local playback, and pause/play from the system shade works. (Proves the
  cast-safe URL, `api_key` auth, and mp3 transcode.) The cast shows as a separate
  session you can't drive from the in-app player — expected; that's Phase 1.
- **NVIDIA SHIELD (Android TV): FIXED.** Initially failed (`idleReason=4` ERROR):
  the app's `serverUrl` is the remote `https://tv.liquid.guru` route, which for
  **LAN clients** resolves (local DNS rewrite) to `192.168.1.100:443` = Synology
  **DSM** (serving the `liquidguru.synology.me` cert), NOT NPM. NPM actually
  listens on `192.168.1.100:4430` with a valid `*.liquid.guru` cert and proxies
  `tv.liquid.guru` → `http://192.168.1.9:8096` (Emby on liquidBee). The phone app
  works because it doesn't enforce hostname verification; the SHIELD does, so TLS
  failed. **Fix:** cast against the **direct LAN Emby URL** — added a
  `castServerUrl` setting (Settings → "Cast server URL (LAN)", e.g.
  `http://192.168.1.9:8096`); `CastManager` uses it (falling back to `serverUrl`)
  for the stream and rebases artwork onto it too. Cast receivers accept cleartext
  http on the LAN. Verified on the SHIELD: plays with album art.
- **Artwork:** `CastManager.castImageUrl()` appends `api_key` and rebases onto
  the cast base. Working.

## Phase 2 — Reporting and volume polish
- Confirm Emby progress reporting (`reportPlaybackStarted/Progress/Stopped`) from
  the active cast position; keep `PlaySessionId` handling consistent and verify
  Emby "now playing" while remote playback is active.
- **DONE 2026-06-19:** Route volume to the cast device from Now Playing.
  `CastManager` listens to `CastSession` device-volume changes and sends app
  slider updates with `CastSession.setVolume(...)`; `PlaybackController` exposes
  optimistic `CastVolumeState`, debounces receiver writes, and ignores stale Cast
  volume echoes during the short pending window. Kaj verified the second build on
  Pixel 8 Pro + SHIELD: the in-app volume slider works much better than the first
  pass. Phone volume buttons / the system Cast card still depend on Cast
  framework overlay timing.
- Regression-test skip previous/next, seek, repeat modes, and Guest DJ appends
  across a longer cast queue.

## Phase 3 — UI polish
- While casting: visibly disable/grey equalizer, crossfade, and offline prefetch
  settings/entry points. Runtime suppression is already in place from Phase 1.
- Add the Cast button to the mini-player (`ui/main/MiniPlayerBar.kt`).
- Show a "Casting to <device>" indicator on Now Playing.
- Confirm notification/widget polish after the Phase 1 session-player swap.

## Phase 4 — Verify
On a real cast target on the LAN: start a music queue, cast, then play/pause,
skip both directions, seek, adjust volume, and confirm Emby "now playing" /
progress updates. Disconnect and confirm playback resumes locally at the right
spot. Test with both transcoded (e.g. FLAC source) and native-mp3 tracks.

## Known risks / notes
- **Format:** force mp3 for cast; do not pass FLAC to the Default Receiver.
- **Auth/URL:** `api_key` query param; LAN `http` base (not the self-signed
  https reverse-proxy). Artwork URL must be reachable by the cast device (LAN
  Emby image URL) — if Emby image endpoints need auth, append `api_key` there too.
- **Play Services:** required for Cast; everything is guarded so the button just
  doesn't appear where unavailable.
- Update `docs/spec.md` with an M-entry per shipped phase, and follow the
  AGENTS.md git routine (commit per step, push, pull the NAS checkout).
