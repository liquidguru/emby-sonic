# Codex task — fix the music crossfade regression (incl. alternative methods)

**Fix the music crossfade regression in emby-sonic (liquidWave Android).** Read
`AGENTS.md` and `docs/spec.md` first, then **`docs/crossfade-investigation.md`** —
that doc is the full prior diagnosis and is the source of truth for this task.
HEAD at handoff was `be51664`, tree clean.

## The problem

On Kaj's real Pixel 8 Pro, music crossfade no longer blends: a track ends ~6
seconds early (≈ the crossfade duration), there's a short gap, then the next song
hard-ins at full volume with no fade. It was verified *excellent* on the same
phone on 2026-06-13; it broke after the 2026-06-14 equalizer work.

## What's already proven (build on it, don't re-litigate)

- The crossfade **engine logic is correct**. Instrumented logs show both
  `ExoPlayer`s ramping complementary volumes (primary 0.30→1.0, helper 1.0→0.0)
  with *both positions advancing* and `playing=true` through the whole ramp.
  Arm/fire/timing/readiness are all fine.
- The real defect is **below our code**: the two simultaneous `ExoPlayer`
  instances don't both reach the speaker — only one is audible, and which one
  wins **varies per transition**. Because the engine advances the primary ~6s
  early expecting the inaudible helper to cover the tail, the early-advance is
  heard as an early *end*.
- **Ruled out:** the equalizer's processing (reproduces with EQ off), our
  volume/position logic, and the loop-gating change (commit `9af8905`).
- **Key negative result:** a diagnostic build that restored the exact 2026-06-13
  audio path (primary on its own default session, EQ `attach()` +
  `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` broadcast both disabled) **did not**
  restore the blend. So the EQ/audio-session change is *not* the sole cause —
  something else in the two-player output path is involved, or the diagnostic
  didn't truly reproduce the 06-13 state.

## Your job

Diagnose to root cause, then fix — and **explicitly evaluate alternative
architectures** rather than only patching the current two-player approach.

1. **Confirm the floor:** write a minimal repro — two bare `ExoPlayer`s playing
   two streams from Emby simultaneously on the Pixel 8 Pro — and determine whether
   simultaneous audio output even works at all on this device/OS build right now,
   independent of our crossfade code. This decides whether the two-player
   approach is salvageable.
2. **Bisect the audio path** between the 06-13 "excellent" commit and HEAD,
   testing an *actual blend on the phone* at each step (the negative result above
   means the regression may sit beside, not in, the EQ change). Suspects to probe:
   the per-crossfade `fadePlayer` create/`release()` lifecycle; audio focus
   (`handleAudioFocus=true` on primary vs `false` on helper — try a single shared
   focus request, or disabling focus during a blend); the explicit
   `generateAudioSessionId()` on the primary; the effect-session broadcast.
3. **Seriously evaluate alternatives to two simultaneous players**, since
   two-`ExoPlayer` output contention may be a dead end on modern Android:
   - a **single `ExoPlayer` with a custom mixing `AudioProcessor` / `AudioSink`**
     that overlaps the tail of the outgoing decode with the head of the incoming;
   - two `MediaSource`s fed through one player with a crossfading mixer;
   - any Media3 mechanism that achieves an overlapping blend within one audio
     output path.
   Recommend the lowest-risk approach that actually blends on the device, with the
   tradeoffs.

## Constraints (from AGENTS.md)

- Crossfade is audio — **judge it on the real Pixel 8 Pro over wireless ADB, not
  the emulator** (emulator software codecs can't render two simultaneous
  decoders). Drive the phone via screenshots + taps, not `uiautomator`.
- Keep the equalizer working in whatever solution you land on (both normal
  playback and blends should be EQ'd, or document the tradeoff if the outgoing
  tail isn't).
- Follow the AGENTS.md git routine: commit each logical step with the agent
  trailer, push, and `git pull` the NAS checkout.
- Update `docs/crossfade-investigation.md` and `docs/spec.md` with findings + the
  final approach so the next agent can resume cold.
- Don't claim anything is verified that you couldn't run on the real device.
