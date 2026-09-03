package guru.liquid.embysonic.playback

import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Makes the phone's hardware volume keys work while casting.
 *
 * Android routes the volume keys to the active media session's *volume provider*
 * whenever that session declares remote playback — which is what makes the keys
 * adjust a Cast receiver rather than the phone's own speaker, screen off or on.
 * Media3 builds that provider in `PlayerWrapper.createVolumeProviderCompat()`
 * from three things on the session player: `getDeviceInfo().playbackType`,
 * `getDeviceInfo().maxVolume`, and whether the device-volume commands are in
 * `getAvailableCommands()`.
 *
 * Media3 1.5.1's `CastPlayer` reports `PLAYBACK_TYPE_REMOTE` but supplies none of
 * the rest: `maxVolume` is 0, no device-volume command is advertised, and
 * `setDeviceVolume` / `increaseDeviceVolume` / `decreaseDeviceVolume` are empty
 * method bodies. So the session published a *fixed*-volume remote provider, and
 * the keys did nothing — not a missing route, but a route that correctly obeyed
 * a player saying "this volume cannot be changed". Worse than a no-op, it also
 * took the keys away from the phone's own stream for the duration of the cast.
 *
 * This wrapper supplies what CastPlayer doesn't, and maps it onto the Cast
 * session volume that the in-app slider already drives. It is applied only while
 * casting; local playback keeps ExoPlayer's own (local) device info untouched.
 *
 * [notifyVolumeChanged] must be called when the receiver's volume changes, so the
 * provider's current value tracks changes made from the app slider or from
 * another remote. CastPlayer never emits `onDeviceVolumeChanged` itself.
 *
 * The wrapper deliberately does *not* pass through CastPlayer's
 * `routingControllerId`. Media3 forwards that to the platform as the provider's
 * volume control id, which hands volume handling to the system's own routing
 * controller — the path that is already not working here. Owning the whole
 * behaviour keeps it predictable.
 *
 * **Known limitation.** Media3's session takes the command set from the *argument*
 * of `onAvailableCommandsChanged` rather than re-reading the player, so whenever
 * CastPlayer emits that event the session's advertised commands revert to its
 * unpatched set and lose the three added here. That is cosmetic: it only changes
 * what `MediaController` clients are told. The volume provider is built and rebuilt
 * from `PlayerWrapper.createVolumeProviderCompat()` and its key presses are gated by
 * `PlayerWrapper.isCommandAvailable()`, both of which call straight through to the
 * overrides below, so the keys keep working either way. Patching the event would
 * mean wrapping every registered listener — and `Player.Listener` is a Java
 * interface of ~30 default methods, which Kotlin's `by` delegation does **not**
 * generate forwarders for. A wrapper written that way compiles, and silently drops
 * every callback it doesn't name. Not worth it for an advertising detail.
 */
@OptIn(UnstableApi::class)
// The no-flags device-volume methods are deprecated on Player. Media3 calls the
// _WITH_FLAGS variants given the commands advertised below, so these should never
// run — but leaving them out would forward them to CastPlayer's empty stubs, which
// is the bug this class exists to fix. Overriding both keeps that impossible.
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class RemoteVolumePlayer(
    player: Player,
    private val currentVolume: () -> Float,
    private val currentMuted: () -> Boolean,
    private val onSetVolume: (Float) -> Unit,
    private val onSetMuted: (Boolean) -> Unit,
) : ForwardingPlayer(player) {

    private val listeners = ConcurrentHashMap<Player.Listener, Boolean>()

    private val remoteDeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        .setMaxVolume(VOLUME_STEPS)
        .build()

    override fun getDeviceInfo(): DeviceInfo = remoteDeviceInfo

    override fun getDeviceVolume(): Int = toSteps(currentVolume())

    override fun isDeviceMuted(): Boolean = currentMuted()

    override fun setDeviceVolume(volume: Int) = applyVolume(volume)

    override fun setDeviceVolume(volume: Int, flags: Int) = applyVolume(volume)

    override fun increaseDeviceVolume() = applyVolume(deviceVolume + 1)

    override fun increaseDeviceVolume(flags: Int) = applyVolume(deviceVolume + 1)

    override fun decreaseDeviceVolume() = applyVolume(deviceVolume - 1)

    override fun decreaseDeviceVolume(flags: Int) = applyVolume(deviceVolume - 1)

    override fun setDeviceMuted(muted: Boolean) = onSetMuted(muted)

    override fun setDeviceMuted(muted: Boolean, flags: Int) = onSetMuted(muted)

    override fun getAvailableCommands(): Player.Commands = augment(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean =
        VOLUME_COMMANDS.contains(command) || super.isCommandAvailable(command)

    // Listeners are tracked only so [notifyVolumeChanged] has somewhere to send a
    // volume change to; registration itself is left entirely to the superclass.
    override fun addListener(listener: Player.Listener) {
        listeners[listener] = true
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        super.removeListener(listener)
    }

    /** Push the receiver's current volume out to the session's volume provider. */
    fun notifyVolumeChanged() {
        val volume = deviceVolume
        val muted = isDeviceMuted
        listeners.keys.forEach { it.onDeviceVolumeChanged(volume, muted) }
    }

    private fun applyVolume(steps: Int) {
        // Reaching for the volume keys while muted means "I want to hear this", so
        // unmute rather than silently changing a level that nothing is playing at.
        if (currentMuted()) onSetMuted(false)
        onSetVolume(steps.coerceIn(0, VOLUME_STEPS).toFloat() / VOLUME_STEPS)
    }

    private fun toSteps(volume: Float): Int = (volume.coerceIn(0f, 1f) * VOLUME_STEPS).roundToInt()

    private fun augment(commands: Player.Commands): Player.Commands =
        commands.buildUpon().addAll(*VOLUME_COMMANDS).build()

    private companion object {
        /**
         * Steps across the receiver's 0..1 range, so one key press moves it 5%.
         * Slightly finer than the phone's own music stream (15 steps on most
         * devices), which suits a speaker across the room.
         */
        const val VOLUME_STEPS = 20

        /**
         * The `_WITH_FLAGS` variants are the non-deprecated ones; Media3 checks for
         * either. [Player.COMMAND_GET_DEVICE_VOLUME] is needed too, or the provider
         * is built reporting volume 0 regardless of what the receiver is at.
         */
        val VOLUME_COMMANDS = intArrayOf(
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )
    }
}
