package guru.liquid.embysonic.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import guru.liquid.embysonic.data.download.DownloadedPlaylist
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onPlayed: () -> Unit = {},
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val index by viewModel.state.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    var removeTarget by remember { mutableStateOf<DownloadedPlaylist?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.openNowPlaying.collect { onPlayed() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                headlineContent = { Text("Download over Wi-Fi only") },
                supportingContent = {
                    Text("Don't use mobile data for downloads (playback always works offline).")
                },
                trailingContent = {
                    Switch(checked = wifiOnly, onCheckedChange = viewModel::setWifiOnly)
                },
            )
            HorizontalDivider()

            if (index.playlists.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No downloads yet.\nOpen a playlist and tap the download icon to make it available offline.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(
                    "${index.playlists.size} playlist${if (index.playlists.size == 1) "" else "s"} • ${formatBytes(index.totalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                HorizontalDivider()
                val music = index.playlists.filterNot { it.isAudiobook }
                val books = index.playlists.filter { it.isAudiobook }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    downloadGroup("Playlists", music, viewModel, onRemove = { removeTarget = it })
                    downloadGroup("Audiobooks", books, viewModel, onRemove = { removeTarget = it })
                }
            }
        }
    }

    removeTarget?.let { target ->
        val downloading = target.isDownloading
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(if (downloading) "Stop download" else "Remove download") },
            text = {
                Text(
                    if (downloading) {
                        "Stop downloading \"${target.name}\" and delete the files saved so far?"
                    } else {
                        "Delete the downloaded files for \"${target.name}\" from this device? The playlist stays in your library."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.remove(target.playlistId)
                        removeTarget = null
                    },
                ) {
                    Text(if (downloading) "Stop" else "Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            },
        )
    }
}

/** A labeled group (Playlists / Audiobooks); renders nothing when empty. */
private fun LazyListScope.downloadGroup(
    title: String,
    playlists: List<DownloadedPlaylist>,
    viewModel: DownloadsViewModel,
    onRemove: (DownloadedPlaylist) -> Unit,
) {
    if (playlists.isEmpty()) return
    item(key = "header_$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
    items(playlists, key = { it.playlistId }) { playlist ->
        DownloadedPlaylistRow(
            playlist = playlist,
            coverModel = viewModel.coverModel(playlist),
            onPlay = { viewModel.play(playlist) },
            onRemove = { onRemove(playlist) },
        )
        HorizontalDivider()
    }
}

@Composable
private fun DownloadedPlaylistRow(
    playlist: DownloadedPlaylist,
    coverModel: String?,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    val unit = if (playlist.isAudiobook) "chapter" else "track"
    val subtitle = when {
        playlist.isDownloading ->
            "Downloading ${playlist.completeCount}/${playlist.tracks.size}"
        playlist.failedCount > 0 ->
            "${playlist.completeCount}/${playlist.tracks.size} ${unit}s • ${playlist.failedCount} failed • ${formatBytes(playlist.totalBytes)}"
        else ->
            "${playlist.tracks.size} $unit${if (playlist.tracks.size == 1) "" else "s"} • ${formatBytes(playlist.totalBytes)}"
    }
    ListItem(
        modifier = Modifier.clickable(enabled = playlist.completeCount > 0, onClick = onPlay),
        headlineContent = { Text(playlist.name) },
        supportingContent = {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = {
            when {
                playlist.isDownloading -> CircularProgressIndicator(
                    progress = {
                        if (playlist.tracks.isEmpty()) 0f
                        else playlist.completeCount.toFloat() / playlist.tracks.size
                    },
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 2.dp,
                )
                coverModel != null -> AsyncImage(
                    model = coverModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                )
                else -> Icon(
                    Icons.Default.DownloadForOffline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove download")
            }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}
