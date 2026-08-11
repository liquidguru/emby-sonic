# Cast volume keys investigation

**Status:** Confirmed Android bug, intentionally deferred while the first Google Play submission is under review.

**Observed:** 25 July 2026, casting from liquidWave on a Pixel 8 Pro to a Google speaker.

## Summary

The Cast volume slider inside liquidWave works, but the phone's physical volume
keys do not control the Cast receiver. Pressing a volume key shows a collapsed
system volume line with no usable range.

This is not a Google speaker configuration problem. The two controls currently
take different paths:

- The in-app slider calls `CastSession.setVolume()` directly and successfully
  changes the speaker volume.
- Physical volume keys are routed through liquidWave's Android `MediaSession`.
  That session advertises the active Cast output as fixed-volume with a maximum
  volume of zero, so Android has no adjustable range to display or control.

## Confirmed evidence

During an active Cast session to `Living Room speaker`:

- The in-app control displayed a valid volume of approximately 31–33% and was
  able to change the speaker volume.
- `adb shell dumpsys media_session` reported liquidWave's active session as:

  ```text
  volumeType=REMOTE
  controlType=FIXED
  max=0
  current=0
  ```

- The app currently uses Media3 `1.5.1` for `media3-exoplayer`,
  `media3-session`, `media3-ui`, and `media3-cast`.
- Inspection of the bundled Media3 1.5.1 `CastPlayer` confirmed that its device
  volume implementation reports zero and does not implement the device-volume
  adjustment methods. Consequently, the `MediaLibrarySession` cannot expose a
  working remote volume provider to Android.

## Relevant code paths

### Working in-app control

```text
NowPlayingScreen Cast volume Slider
  -> NowPlayingViewModel.setCastVolume()
  -> PlaybackController.setCastVolume()
  -> CastManager.setCastVolume()
  -> CastSession.setVolume()
```

Relevant files:

- `android/app/src/main/java/guru/liquid/embysonic/ui/playback/NowPlayingScreen.kt`
- `android/app/src/main/java/guru/liquid/embysonic/ui/playback/NowPlayingViewModel.kt`
- `android/app/src/main/java/guru/liquid/embysonic/playback/PlaybackController.kt`
- `android/app/src/main/java/guru/liquid/embysonic/cast/CastManager.kt`

### Broken physical-key control

```text
Phone volume key
  -> Android MediaSession
  -> SonicPlaybackService MediaLibrarySession
  -> AvrcpDurationPlayer
  -> Media3 1.5.1 CastPlayer device-volume API
  -> fixed volume / max 0
```

Relevant files:

- `android/app/src/main/java/guru/liquid/embysonic/playback/SonicPlaybackService.kt`
- `android/app/src/main/java/guru/liquid/embysonic/playback/AvrcpDurationPlayer.kt`
- `android/gradle/libs.versions.toml`

## Recommended future fix

First investigate upgrading all Media3 artifacts together from `1.5.1` to a
current compatible stable version and migrate the Cast player construction to
the current Media3 Cast API. Newer Media3 Cast implementations expose remote
device information and device-volume commands that the media session can
publish to Android.

Do this as a dedicated change after the current Play submission is settled.
Media3 is shared by local playback, Cast, the media notification, Android Auto,
and the playback service, so a version upgrade needs broader regression testing
than the apparent size of the volume bug suggests.

If a Media3 upgrade proves unsuitable, the fallback is a narrow session-player
adapter that:

1. Reports an adjustable remote `DeviceInfo` while casting.
2. Exposes the Media3 get, set, increase, decrease, and mute device-volume
   commands.
3. Converts between Media3's integer device-volume range and Cast's normalized
   `0.0..1.0` volume.
4. Forwards changes to the existing `CastSession`.
5. Publishes receiver-originated volume changes back through the player/session
   so the Android system UI and in-app slider remain synchronized.

The adapter approach requires careful event handling and creates more
app-owned volume code, so it should be the fallback rather than the first
choice.

## Do not use these partial fixes

- Do not alter the working Compose slider; it is not the source of this defect.
- Do not merely draw a different system slider or substitute the phone's local
  media volume. The keys must control the Cast receiver.
- Do not rely only on Activity key interception. Volume must also work from the
  lock screen, notification shade, background playback, and other system
  surfaces.
- Do not advertise an arbitrary adjustable range without forwarding commands
  and synchronizing changes from other Cast senders.

## Acceptance criteria

Test with at least a Google speaker and, if available, a speaker group and a
video Cast receiver.

- While liquidWave is in the foreground, phone volume keys show a usable Cast
  volume control and change the receiver volume.
- The same works with liquidWave backgrounded and from the lock screen.
- The in-app slider immediately reflects changes made with physical keys.
- Physical-key/system volume reflects changes made with the in-app slider,
  Google Home, or another Cast sender.
- Connecting, suspending, resuming, and disconnecting a Cast session do not
  leave the media session stuck in remote-volume mode.
- After disconnecting, volume keys control the phone's local media volume.
- Local playback, Cast queue handoff, notification controls, Android Auto,
  shuffle/repeat, seeking, and playback resumption still work.
- During an adjustable Cast session, `adb shell dumpsys media_session` no
  longer reports liquidWave as `controlType=FIXED, max=0`.
- `./gradlew :app:assembleDebug` and `./gradlew :app:lintDebug` pass from
  `android/`.

## References

- [Google Cast Android sender integration — volume control](https://developers.google.com/cast/docs/android_sender/integrate#volume_control)
- [Google Cast sender design checklist — sender volume controls](https://developers.google.com/cast/docs/design_checklist/sender#sender-volume-controls)
- [Media3 CastPlayer guide](https://developer.android.com/media/media3/cast/create-castplayer)

