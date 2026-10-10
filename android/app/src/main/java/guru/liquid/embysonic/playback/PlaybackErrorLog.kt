package guru.liquid.embysonic.playback

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A small on-disk record of playback failures, kept because logcat cannot catch
 * them in the field. On a busy phone the default 256 KiB log buffer holds about a
 * minute; a failure on the bike was gone within twenty. This file survives the
 * ride, the buffer and a reboot.
 *
 * It lives in the app's external files dir so it can be pulled without root,
 * from a release build that run-as can't reach:
 *
 *   adb pull /sdcard/Android/data/guru.liquid.embysonic/files/playback-errors.log
 *
 * Each entry records the error code, where the audio came from (prefetch cache,
 * download, or stream), and the full cause chain — the cause line is the part
 * that was missing, and is what tells a corrupt file from a decoder that
 * wouldn't start.
 */
@Singleton
class PlaybackErrorLog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File? by lazy {
        context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }
    }

    fun record(event: String, details: List<String>) {
        val target = file ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            append("[").append(stamp).append("] ").append(event).append('\n')
            details.forEach { append("    ").append(it).append('\n') }
        }
        runCatching {
            synchronized(this) {
                target.appendText(entry)
                trim(target)
            }
        }.onFailure { Log.w(TAG, "Could not write playback error log", it) }
    }

    /** Cause chain as lines, outermost first, so the root cause is never cut off. */
    fun causeLines(error: Throwable): List<String> {
        val lines = mutableListOf<String>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            lines += "${if (depth == 0) "error" else "cause"}: ${current.javaClass.name}: ${current.message}"
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return lines
    }

    // Keep the newest half once it grows past the cap — enough history for a
    // few weeks of rides without the file growing without bound.
    private fun trim(target: File) {
        if (target.length() <= MAX_BYTES) return
        val text = target.readText()
        val keepFrom = text.indexOf('[', text.length / 2).takeIf { it >= 0 } ?: return
        target.writeText(text.substring(keepFrom))
    }

    private companion object {
        const val TAG = "PlaybackErrorLog"
        const val FILE_NAME = "playback-errors.log"
        const val MAX_BYTES = 256L * 1024L
        const val MAX_CAUSE_DEPTH = 6
    }
}
