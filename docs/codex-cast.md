# Codex task — Casting (Chromecast / Google Cast)

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
   Receiver can't reliably decode FLAC). See `CastManager.castStreamUrl()`.

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

## Phase 1 — Active-player switching (the big one)
> **User-visible priority:** in Phase 0 the cast is a *separate* session the app
> doesn't own, so the in-app mini-player/notification don't control it and there's
> no in-app stop (control is via the system cast tile only). Swapping the
> MediaSession to the CastPlayer (below) is what restores in-app control + a stop
> button while casting.

- Add a `CastPlayer` (media3-cast) backed by the shared `CastContext`.
- Introduce an `activePlayer: Player` concept in `PlaybackController` (local
  ExoPlayer by default; CastPlayer when a cast session is connected). Route all
  transport and `publishState()` reads through it.
- Use `SessionAvailabilityListener` (or the existing `SessionManagerListener`) to
  flip the active player on connect/disconnect.
- Swap `MediaLibrarySession.player` (in `SonicPlaybackService`) to the active
  player so the notification, lock screen, Android Auto and the home-screen
  widget all follow the cast.
- Hand off the **queue + current index + position** local→cast on connect and
  cast→local on disconnect (resume locally where casting left off).
- Replace the Phase 0 single-track `CastManager.castCurrentTrack` shortcut with
  the real handoff once switching works.

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

## Phase 2 — Queue, metadata, reporting on cast
- Build cast `MediaQueueItem`s / `MediaInfo` for the whole queue using
  `castStreamUrl()` + metadata (title/artist/album/artwork, mime `audio/mpeg`).
- Drive Emby progress reporting (`reportPlaybackStarted/Progress/Stopped`) from
  the cast position; keep `PlaySessionId` handling consistent.
- Route volume to the cast device (Cast volume, not local stream volume).

## Phase 3 — Feature gating + UI
- While casting: disable/grey the equalizer, crossfade, and offline prefetch
  (and stop the local crossfade/prefetch loops). Guest DJ + mixes operate on the
  queue and should keep working.
- Add the Cast button to the mini-player (`ui/main/MiniPlayerBar.kt`).
- Show a "Casting to <device>" indicator on Now Playing.
- Confirm the notification/widget reflect cast state (they should once the
  session player is swapped in Phase 1).

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
