# Crossfade regression investigation (2026-06-14 to 2026-06-15)

**Status: RESOLVED and verified on Kaj's Pixel 8 Pro.**

## Final root cause

Android audio output was not the defect. The primary queue and crossfade helper
all reused one Emby `PlaySessionId`. Emby keys transcode jobs by that id. A
concurrent helper request, or simply advancing between mixed direct/transcoded
items, could therefore reuse or replace the wrong server-side stream context.
The primary then received bytes for the wrong stream and failed with
`UnrecognizedInputFormatException`, or a helper range seek failed in
`DefaultHttpDataSource.skipFully`.

During a crossfade this produced the reported shape: the engine advanced the
primary about six seconds early, the incoming item failed or stalled, and the
expected helper coverage was absent or unreliable. The volume/timing logs were
correct because the failure was in Emby stream-session ownership, before the
decoded streams reached the otherwise-working Android mixer.

The 2026-06-14 EQ work did not introduce an Android two-output limitation. It
coincided with the regression report, but the reproducible fault was a latent
queue-level `PlaySessionId` bug exposed by content requiring Emby transcoding.

## Fix

- Every primary queue item receives its own `PlaySessionId` when the queue is
  built. Playback-start/progress/stopped reports use that item's id.
- Replacing an item for a queue jump or server-side seek mints a fresh id.
- The crossfade helper always receives a separate fresh id because it is a
  concurrent Emby playback request.
- The Android audio design is unchanged: the primary handles focus, the helper
  does not, both share the generated Android audio-session id, and one
  `Equalizer` therefore processes normal playback and both sides of a blend.

## Device evidence

All audio judgments below were made on the real Pixel 8 Pro over wireless ADB
(Android API 37 build `CP31.260522.006`), not the emulator.

### Two-player floor

A debug-only `AudioOutputDiagnosticActivity` played two bare Emby streams with
no `PlaybackController`, MediaSession, crossfade timing, or effects. Both tracks
were simultaneously audible and AudioFlinger showed two active tracks for:

- separate Android audio sessions, no player-managed focus;
- primary `handleAudioFocus=true`, helper `false`;
- one shared generated Android audio session.

This disproves the earlier theory that only one ExoPlayer could reach the Pixel
speaker. The two-player architecture is viable on this device/OS build.

### Production engine

- Current HEAD before the fix, MP3 `119766` -> MP3 `2601686`: full six-second
  blend sounded excellent; both AudioFlinger tracks were active with
  complementary gains. The same test also sounded correct with the screen
  locked. This cleared audio focus, lifecycle, shared Android session, EQ
  attachment, and effect-session broadcast as causes.
- Before the fix, WMA/ASF `2595215` -> MP3 `2601686` reproduced the failure.
  The helper became ready and crossfade fired, then the primary entered idle
  with `UnrecognizedInputFormatException` instead of reaching incoming READY.
- After the fix, the same WMA -> MP3 transition reached incoming READY in 20 ms,
  ran the full six-second ramp, raised no source error, and sounded correct to
  Kaj. This also proves transcoded outgoing tracks can blend when their helper
  owns an independent Emby session and can seek its transcode.
- Final MP3 -> MP3 regression test after the fix also reached incoming READY in
  20 ms, completed the ramp without error, and sounded correct to Kaj.

One attempted final diagnostic launch was rejected by Android with
`BackgroundServiceStartNotAllowedException` while the debug activity was
backgrounded/locked. Re-running awake passed; this was a harness launch issue,
not a playback failure.

## History/bisect findings

The known-good device verification was commit `0e9d0df` on 2026-06-13. The
2026-06-14 audio-path change was EQ commit `5745cdb`; subsequent relevant
commits changed reporting, content kind, service startup, and loop gating. The
helper lifecycle and primary/helper focus split already existed in the
known-good path. Restoring the pre-EQ Android session/effect path had previously
failed to restore the reported blend, and current production playback with the
EQ/shared session now blends correctly. The content-matrix reproduction above
was therefore more discriminating than an Android-path commit bisect.

## Architecture decision

Keep the two-ExoPlayer design. It is the lowest-risk option that actually
blends on the target device and keeps EQ on both sides:

- A normal custom Media3 `AudioProcessor` sees PCM from one renderer; it cannot
  independently decode and overlap the next media item by itself.
- A custom mixing `AudioSink` would require coordinating two decoders/renderers,
  timestamps, seeking, buffering, focus, and lifecycle. That is effectively a
  new playback engine with a much larger regression surface.
- A layered Media3 composition/mixer is aimed at composition/editing workflows,
  not a MediaSession-backed interactive queue with shuffle, repeat, seeking,
  reporting, and audiobook resume. Adopting it would be a major architecture
  change and would still need proof for effects and background playback.

Revisit a single-output mixer only if a future Android release demonstrates a
repeatable two-output failure in the bare floor test. Do not infer one from an
Emby source/session error.

## Repeatable diagnostic

The minimal repro is debug-only (`android/app/src/debug`). It accepts two item
ids and supports bare modes `separate`, `primary_focus`, `shared_session`, plus
`engine` for the production crossfade path. `play_naturally=true` avoids an
artificial seek when testing short transcodes. Drive it with `adb shell am
start`; keep the phone awake when launching so Android permits the playback
service start.
