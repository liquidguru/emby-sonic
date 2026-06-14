# Crossfade regression investigation (2026-06-14)

**Status: OPEN — paused for a high-effort model to resume.**

## Symptom (Pixel 8 Pro, real device)

Music crossfade no longer produces an audible blend. Instead a track effectively
**ends ~6 seconds early** (≈ the crossfade duration): the outgoing song goes
quiet / cuts off about 6s before the track timer reaches the end, there is a
short gap, then the next song starts at full volume with **no fade-in**. Which
side is audible (outgoing tail vs incoming head) **varies between transitions**
even though the logs are identical.

Crossfade was verified **"excellent" on the same phone on 2026-06-13** (spec
M4.5 / M4.4). It broke sometime after.

## What is PROVEN — the engine logic is correct (not the bug)

Instrumented `PlaybackController` logging of both players' volume + position
through a blend (6s, 119 ramp steps):

```
armed:        primaryVol=1.0 primaryPos=173551        ← primary at FULL vol at arm (no pre-fire fade)
fired:        helperVol=1.0 helperPos=185507 primaryPosAfterSeek=0
ramp step 1:   primaryVol=0.30 primaryPos=0     helperVol=1.0  helperPos=185559
ramp step 20:  primaryVol=0.67 primaryPos=883   helperVol=0.97 helperPos=186460   ← both positions advancing
ramp step 60:  primaryVol=0.92 primaryPos=2934  helperVol=0.70 helperPos=188524
ramp step 100: primaryVol=0.99 primaryPos=5022  helperVol=0.25 helperPos=190603
ramp step 119: primaryVol=1.0  primaryPos=6024  helperVol=0.0  helperPos=191526
```

- Both players have correct **complementary** volumes (primary 0.30→1.0, helper
  1.0→0.0), both **positions advance**, both report `playing=true` the whole ramp.
- `primaryVol=1.0` at arm proves the "volume going down ~19s before the end" the
  user heard was the **song's own fade-out ending**, not our code (it appeared on
  some tracks, not others).

So the crossfade state machine, volume ramp, timing, arm/fire, and incoming
readiness are all working. **The defect is below our code: the two simultaneous
`ExoPlayer` instances are not both reaching the speaker** — only one is audible,
and which one wins output varies per transition (classic audio-output
contention). Because the engine advances the primary to the next track 6s early
(expecting the inaudible helper to cover the tail), the early-advance is heard as
an early-END.

## What is RULED OUT

- **Equalizer processing** — reproduced identically with the EQ toggled OFF.
- **Our volume/position/timing logic** — see proven logs above.
- **Item-1 loop gating** (commit 9af8905, `playbackActive`/`collectLatest`) —
  crossfade arms/fires/ramps correctly in the logs; `playWhenReady` stays true
  through the fire seek so the blend is not cancelled. (Re-confirm if desired.)
- **Explicit-content-kind change** (commit dca7e2b) — does not change crossfade
  eligibility for normal <20min music.

## Key NEGATIVE result (the important open thread)

A diagnostic build restored the **exact 2026-06-13 audio path** — primary uses
its own default ExoPlayer session (no `generateAudioSessionId`), and the EQ
`attach()` + `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` broadcast were disabled —
and the blend **still did not become audible** (songs still ended ~6s early).

That means the 2026-06-14 EQ/audio-session infrastructure is **NOT the sole
cause**, despite being the obvious suspect (it was the change between the
"excellent" 06-13 verification and the breakage). Something else in the
two-player output path regressed, OR the 06-13 config differs from what the
diagnostic build reproduced.

### 06-14 changes that touched the audio path (suspects / bisect targets)
- Equalizer: primary pinned to `generateAudioSessionId()`; `Equalizer` attached;
  `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` broadcast (invites system/Wavelet
  effects onto the primary session — fires regardless of the EQ on/off toggle).
- Crossfade helper lifecycle: `fadePlayer` created per-crossfade and fully
  `release()`d (M4.5 phase 2, 2026-06-13 — same day as the good verification).
- Audio focus / wake mode / becoming-noisy added to the primary
  (`handleAudioFocus=true`); helper uses `handleAudioFocus=false`.

## Suggested next steps (for tomorrow's high-effort pass)

1. **git bisect the audio path** between the 06-13 "excellent" commit and HEAD,
   testing an actual blend on the phone at each step — the negative result above
   means the regression may predate or sit beside the EQ change.
2. **Minimal repro**: two bare `ExoPlayer`s playing two local/streamed tracks
   simultaneously on the Pixel 8 Pro — confirm whether simultaneous output even
   works at all on this device/OS build right now, independent of our code.
3. Investigate **audio focus**: the primary holds focus
   (`handleAudioFocus=true`); when the helper starts, or when the primary
   re-requests focus on the `seekToNextMediaItem`, the framework may duck/route
   one stream away. Try `handleAudioFocus=false` on the primary during a blend,
   or a single shared focus request.
4. Consider abandoning two players for blends: a **single `ExoPlayer` with two
   `MediaSource`s mixed via a custom `AudioProcessor`**, or `ExoPlayer`'s
   silence-skipping/gapless with a mixing pipeline — removes simultaneous-output
   contention entirely.

## Re-add this instrumentation to resume (was reverted to keep the tree clean)

In `PlaybackController`:
- `armCrossfade` log: append `primaryVol=${player.volume} primaryPos=${player.currentPosition}`.
- `fireCrossfade` log: append `helperVol=${helper.volume} helperPos=${helper.currentPosition} primaryPosAfterSeek=${player.currentPosition}`.
- Ramp loop: log `step/steps primaryVol primaryPos primaryPlaying helperVol helperPos` (throttle: `step==1 || step%20==0 || step==steps`).
- Pre-fire guard: if `crossfadeArmed && !crossfadeInProgress && player.volume < 0.99f` → `Log.w` ("primary already quiet before fire").

Capture with: `adb -s <phone> logcat -s PlaybackController:*`

## Current tree state

Working tree reverted to clean HEAD (commit 839f843). The diagnostic build on the
phone is NOT the committed code — rebuild from HEAD to get back to the shipped
(broken-blend) state, or re-apply the experiments above.
