package guru.liquid.embysonic.playback

import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.AndroidEntryPoint
import guru.liquid.embysonic.BuildConfig
import guru.liquid.embysonic.data.emby.ContentKind
import guru.liquid.embysonic.data.emby.EmbyApi
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.ui.theme.EmbySonicTheme
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Debug-only floor test for Android audio output. It deliberately bypasses
 * PlaybackController, MediaSession, crossfade timing, and audio effects.
 */
@AndroidEntryPoint
class AudioOutputDiagnosticActivity : ComponentActivity() {

    @Inject lateinit var embyApi: EmbyApi
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var playbackController: PlaybackController

    private var firstPlayer: ExoPlayer? = null
    private var secondPlayer: ExoPlayer? = null
    private var status by mutableStateOf("Starting...")
    private var engineDiagnostic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EmbySonicTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Two-player audio floor", style = MaterialTheme.typography.headlineSmall)
                        Text(status)
                        Button(onClick = { startDiagnostic() }) { Text("Restart") }
                        Button(onClick = { releasePlayers() }) { Text("Stop") }
                    }
                }
            }
        }
        if (intent.getStringExtra(EXTRA_MODE) == ENGINE_MODE) {
            startEngineDiagnostic()
        } else {
            startDiagnostic()
        }
    }

    override fun onDestroy() {
        if (!engineDiagnostic) releasePlayers()
        super.onDestroy()
    }

    private fun startEngineDiagnostic() {
        engineDiagnostic = true
        val ids = listOfNotNull(
            intent.getStringExtra(EXTRA_FIRST_ITEM_ID),
            intent.getStringExtra(EXTRA_SECOND_ITEM_ID),
        )
        if (ids.size != 2) {
            status = "Engine mode needs two item ids"
            return
        }
        status = "Starting production crossfade engine..."
        lifecycleScope.launch {
            settings.setCrossfadeEnabled(true)
            settings.setCrossfadeDurationMs(6_000)
            val items = ids.mapIndexed { index, id ->
                LibraryItem(
                    id = id,
                    title = "Engine test ${index + 1}",
                    subtitle = "Crossfade diagnostic",
                    imageUrl = null,
                    contentKind = ContentKind.MUSIC,
                )
            }
            playbackController.playQueue(items, items.first())
            var waitedMs = 0L
            while (
                (playbackController.player.playbackState != Player.STATE_READY ||
                    playbackController.player.duration <= ENGINE_SEEK_REMAINING_MS) &&
                waitedMs < ENGINE_READY_TIMEOUT_MS
            ) {
                kotlinx.coroutines.delay(100)
                waitedMs += 100
            }
            val duration = playbackController.player.duration
            if (playbackController.player.playbackState != Player.STATE_READY ||
                duration <= ENGINE_SEEK_REMAINING_MS
            ) {
                status = "Production engine did not become seekable"
                Log.e(TAG, "Engine diagnostic readiness timeout state=${playbackController.player.playbackState} duration=$duration")
                return@launch
            }
            if (intent.getBooleanExtra(EXTRA_PLAY_NATURALLY, false)) {
                status = "Production engine playing naturally; waiting for crossfade"
                Log.i(TAG, "Engine diagnostic playing naturally duration=$duration")
                return@launch
            }
            playbackController.seekTo(duration - ENGINE_SEEK_REMAINING_MS)
            status = "Production engine armed; crossfade in about 14 seconds"
            Log.i(TAG, "Engine diagnostic seeked duration=$duration remaining=$ENGINE_SEEK_REMAINING_MS")
        }
    }

    private fun startDiagnostic() {
        releasePlayers()
        val mode = intent.getStringExtra(EXTRA_MODE)?.let(DiagnosticMode::fromValue)
            ?: DiagnosticMode.SEPARATE_SESSIONS
        status = "Loading two Emby streams ($mode)..."
        lifecycleScope.launch {
            runCatching {
                val requestedIds = listOfNotNull(
                    intent.getStringExtra(EXTRA_FIRST_ITEM_ID),
                    intent.getStringExtra(EXTRA_SECOND_ITEM_ID),
                )
                val tracks = if (requestedIds.size == 2) {
                    requestedIds.map { DiagnosticTrack(id = it, name = it) }
                } else {
                    val snap = settings.snapshot()
                    val userId = snap.userId?.takeIf(String::isNotBlank)
                        ?: error("Not signed in")
                    val musicLibrary = embyApi.getViews(userId).items.firstOrNull {
                        it.collectionType == "music"
                    } ?: error("No music library found")
                    val libraryId = musicLibrary.id ?: error("Music library has no id")
                    embyApi.getItems(
                        userId = userId,
                        includeItemTypes = "Audio",
                        parentId = libraryId,
                        limit = 2,
                    ).items.mapNotNull { item ->
                        item.id?.let { DiagnosticTrack(id = it, name = item.name.orEmpty()) }
                    }
                }
                require(tracks.size >= 2) { "Need two music tracks" }
                val first = tracks[0]
                val second = tracks[1]
                val factory = dataSourceFactory()
                val sharedSessionId = if (mode.sharedSession) {
                    (getSystemService(AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
                } else {
                    C.AUDIO_SESSION_ID_UNSET
                }
                firstPlayer = buildPlayer(
                    label = "first",
                    factory = factory,
                    handleAudioFocus = mode.primaryHandlesFocus,
                    audioSessionId = sharedSessionId,
                )
                secondPlayer = buildPlayer(
                    label = "second",
                    factory = factory,
                    handleAudioFocus = false,
                    audioSessionId = sharedSessionId,
                )
                firstPlayer?.apply {
                    volume = DIAGNOSTIC_VOLUME
                    setMediaItem(MediaItem.fromUri(streamUrl(first.id)))
                    prepare()
                    play()
                }
                secondPlayer?.apply {
                    volume = DIAGNOSTIC_VOLUME
                    setMediaItem(MediaItem.fromUri(streamUrl(second.id)))
                    prepare()
                    play()
                }
                status = "$mode\nA: ${first.name}\nB: ${second.name}\nBoth at 65% volume"
                Log.i(TAG, "Started mode=$mode first=${first.id} second=${second.id}")
            }.onFailure { error ->
                status = "Failed: ${error.message}"
                Log.e(TAG, "Diagnostic failed", error)
                releasePlayers()
            }
        }
    }

    private fun buildPlayer(
        label: String,
        factory: DefaultHttpDataSource.Factory,
        handleAudioFocus: Boolean,
        audioSessionId: Int,
    ): ExoPlayer {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(factory))
            .setAudioAttributes(attributes, handleAudioFocus)
            .build()
            .also { player ->
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    player.audioSessionId = audioSessionId
                }
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.i(
                            TAG,
                            "$label state=$playbackState playing=${player.isPlaying} " +
                                "session=${player.audioSessionId} position=${player.currentPosition}",
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "$label failed", error)
                    }
                })
            }
    }

    private fun dataSourceFactory(): DefaultHttpDataSource.Factory {
        val snap = settings.snapshot()
        val headers = linkedMapOf(
            "X-Emby-Authorization" to buildString {
                append("MediaBrowser ")
                append("Client=\"liquidWave diagnostic\", ")
                append("Device=\"${Build.MODEL}\", ")
                append("DeviceId=\"${snap.deviceId}\", ")
                append("Version=\"${BuildConfig.VERSION_NAME}\"")
            },
        )
        snap.accessToken?.takeIf(String::isNotBlank)?.let { headers["X-Emby-Token"] = it }
        return DefaultHttpDataSource.Factory()
            .setUserAgent("liquidWave-diagnostic/${BuildConfig.VERSION_NAME}")
            .setDefaultRequestProperties(headers)
    }

    private fun streamUrl(itemId: String): String {
        val snap = settings.snapshot()
        val base = intent.getStringExtra(EXTRA_SERVER_URL)?.trimEnd('/')
            ?: snap.serverUrl?.trimEnd('/')
            ?: error("No Emby server configured")
        val userId = snap.userId?.takeIf(String::isNotBlank) ?: error("Not signed in")
        return Uri.parse("$base/Audio/${Uri.encode(itemId)}/universal")
            .buildUpon()
            .appendQueryParameter("UserId", userId)
            .appendQueryParameter("MaxStreamingBitrate", "140000000")
            .appendQueryParameter("Container", "mp3,aac,m4a,mp4,m4b,flac,webma,webm,wav,ogg")
            .appendQueryParameter("AudioCodec", "mp3,aac,flac,vorbis,opus")
            .appendQueryParameter("TranscodingContainer", "mp3")
            .appendQueryParameter("TranscodingProtocol", "http")
            .appendQueryParameter("PlaySessionId", UUID.randomUUID().toString())
            .build()
            .toString()
    }

    private fun releasePlayers() {
        firstPlayer?.release()
        secondPlayer?.release()
        firstPlayer = null
        secondPlayer = null
        status = "Stopped"
    }

    private enum class DiagnosticMode(
        val value: String,
        val primaryHandlesFocus: Boolean,
        val sharedSession: Boolean,
    ) {
        SEPARATE_SESSIONS("separate", false, false),
        PRIMARY_FOCUS("primary_focus", true, false),
        SHARED_SESSION("shared_session", false, true);

        override fun toString(): String = value

        companion object {
            fun fromValue(value: String): DiagnosticMode? = entries.firstOrNull { it.value == value }
        }
    }

    private companion object {
        const val TAG = "AudioOutputFloor"
        const val EXTRA_MODE = "mode"
        const val EXTRA_FIRST_ITEM_ID = "first_item_id"
        const val EXTRA_SECOND_ITEM_ID = "second_item_id"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_PLAY_NATURALLY = "play_naturally"
        const val ENGINE_MODE = "engine"
        const val DIAGNOSTIC_VOLUME = 0.65f
        const val ENGINE_SEEK_REMAINING_MS = 20_000L
        const val ENGINE_READY_TIMEOUT_MS = 20_000L
    }
}

private data class DiagnosticTrack(val id: String, val name: String)
