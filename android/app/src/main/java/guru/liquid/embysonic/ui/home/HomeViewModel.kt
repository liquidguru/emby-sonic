package guru.liquid.embysonic.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.toLibraryItem
import guru.liquid.embysonic.data.coordinator.dto.SonicMixDto
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryItem
import guru.liquid.embysonic.data.emby.LibraryKind
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.resumeStartItem
import guru.liquid.embysonic.data.recent.RecentPlay
import guru.liquid.embysonic.data.recent.RecentPlaysRepository
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.playback.PlaybackController
import guru.liquid.embysonic.playback.PlaybackSource
import guru.liquid.embysonic.playback.playbackSourceFor
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val userName: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val compactCards: Boolean = false,
    val sectionPreferences: List<HomeSectionPreference> = HomeSectionKind.defaultPreferences(),
    val resumeAudiobooks: List<LibraryItem> = emptyList(),
    val recentPlays: List<LibraryItem> = emptyList(),
    val playlists: List<LibraryItem> = emptyList(),
    val sonicMixes: List<LibraryItem> = emptyList(),
    val recentAlbums: List<LibraryItem> = emptyList(),
    val artists: List<LibraryItem> = emptyList(),
)

enum class HomeSectionKind(val id: String, val label: String) {
    RESUME_AUDIOBOOKS("resume_audiobooks", "Resume audiobooks"),
    RECENT_PLAYS("recent_plays", "Recent plays"),
    PLAYLISTS("playlists", "Playlists"),
    SONIC_MIXES("sonic_mixes", "Sonic mixes"),
    RECENT_ALBUMS("recent_albums", "Recently added albums"),
    ARTISTS("artists", "Artists");

    companion object {
        val defaultOrder: List<HomeSectionKind> = listOf(
            RESUME_AUDIOBOOKS,
            RECENT_PLAYS,
            PLAYLISTS,
            SONIC_MIXES,
            RECENT_ALBUMS,
            ARTISTS,
        )

        fun fromId(id: String): HomeSectionKind? = entries.firstOrNull { it.id == id }

        fun ordered(sectionIds: List<String>): List<HomeSectionKind> {
            val configured = sectionIds.mapNotNull(::fromId)
            return (configured + defaultOrder).distinct()
        }

        fun defaultPreferences(): List<HomeSectionPreference> =
            defaultOrder.map { HomeSectionPreference(kind = it, visible = true) }
    }
}

data class HomeSectionPreference(
    val kind: HomeSectionKind,
    val visible: Boolean,
)

/** Tap-to-play radio stations on Home. Decade also needs a [HomeStation] decade. */
enum class HomeStation(val label: String) {
    LIBRARY("Library Radio"),
    RANDOM_ALBUM("Random Album Radio"),
    DECADE("Decade Radio"),
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val coordinator: CoordinatorApi,
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
    private val recentPlaysRepo: RecentPlaysRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _openNowPlaying = Channel<Unit>(Channel.BUFFERED)
    val openNowPlaying: Flow<Unit> = _openNowPlaying.receiveAsFlow()

    // Cached so station taps (which happen after load) can build music queues.
    @Volatile
    private var musicLibraryId: String? = null

    // The in-flight Home load. refresh() fires from init, the ON_RESUME
    // lifecycle hook, and the manual refresh button, so two can overlap; cancel
    // the previous so a slow earlier load can't land after and clobber a newer
    // one (refresh writes _state.value wholesale).
    private var refreshJob: Job? = null

    // Latest recorded sessions, kept so a Recent plays tap can resolve a tile's
    // stored track ids back into a playable queue.
    @Volatile
    private var recentPlayRecords: List<RecentPlay> = emptyList()

    init {
        val snap = settings.snapshot()
        _state.update { it.copy(userName = snap.userName) }
        observeHomePreferences()
        observeRecentPlays()
        refresh()
    }

    /** Recent plays is a live local history — update its row as new plays land. */
    private fun observeRecentPlays() {
        viewModelScope.launch {
            recentPlaysRepo.recentPlays.collect { records ->
                recentPlayRecords = records
                _state.update { it.copy(recentPlays = records.map { r -> r.toTile() }) }
            }
        }
    }

    private fun RecentPlay.toTile(): LibraryItem =
        LibraryItem(id = key, title = title, subtitle = subtitle, imageUrl = coverUrl)

    /** Replay a recorded session: rebuild the exact queue from its stored track ids. */
    fun playRecent(item: LibraryItem) {
        val record = recentPlayRecords.firstOrNull { it.key == item.id } ?: return
        viewModelScope.launch {
            runCatching { repository.itemsByIds(record.trackIds) }.fold(
                onSuccess = { items ->
                    val first = items.firstOrNull()
                    if (first == null) {
                        _messages.send("Those tracks are no longer available")
                    } else {
                        playback.playQueue(
                            items,
                            first,
                            PlaybackSource(record.key, record.title, record.subtitle, record.coverUrl),
                        )
                        _openNowPlaying.send(Unit)
                    }
                },
                onFailure = { _messages.send("Couldn't start playback: ${it.message}") },
            )
        }
    }

    fun refresh(showLoading: Boolean = true) {
        _state.update {
            if (showLoading) {
                it.copy(loading = true, error = null)
            } else {
                it.copy(error = null)
            }
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // Each section loads independently and in parallel: a coordinator
            // outage must not blank the Emby-backed rows (and vice versa), and a
            // single failed Emby query degrades only its own row rather than the
            // whole screen.
            val libraries = runCatching { repository.audioLibraries() }.getOrElse { emptyList() }
            val musicLibrary = libraries.firstOrNull { it.kind == LibraryKind.MUSIC }
            val audiobookLibrary = libraries.firstOrNull { it.kind == LibraryKind.AUDIOBOOKS }
            musicLibraryId = musicLibrary?.id

            // Emby-backed rows are the fast, primary content — fetch in parallel
            // and render as soon as they're ready.
            val resumeJob = async {
                section { audiobookLibrary?.let { repository.resumeAudiobooks(it.id, HOME_SECTION_LIMIT) }.orEmpty() }
            }
            val playlistsJob = async { section { repository.playlists(HOME_SECTION_LIMIT) } }
            val albumsJob = async {
                section { musicLibrary?.let { repository.recentlyAddedAlbums(it.id, HOME_SECTION_LIMIT) }.orEmpty() }
            }
            val artistsJob = async {
                section { musicLibrary?.let { repository.artists(it.id, HOME_SECTION_LIMIT) }.orEmpty() }
            }

            val resumeAudiobooks = resumeJob.await()
            val playlists = playlistsJob.await()
            val albums = albumsJob.await()
            val artists = artistsJob.await()

            // Recent plays is a live local history (observeRecentPlays), not an
            // Emby fetch — preserve whatever the collector has already set.
            val recentPlays = _state.value.recentPlays
            val allEmpty = resumeAudiobooks.isEmpty() && recentPlays.isEmpty() &&
                playlists.isEmpty() && albums.isEmpty() && artists.isEmpty()
            _state.value = HomeUiState(
                userName = settings.snapshot().userName,
                loading = false,
                // Only surface a full-screen error when nothing at all was
                // reachable (Emby down); a single failed section just leaves
                // that row empty.
                error = if (libraries.isEmpty() && allEmpty) "Couldn't reach your server" else null,
                compactCards = _state.value.compactCards,
                sectionPreferences = _state.value.sectionPreferences,
                resumeAudiobooks = resumeAudiobooks,
                recentPlays = recentPlays,
                playlists = playlists,
                // Keep any previously loaded mixes until the coordinator answers.
                sonicMixes = _state.value.sonicMixes,
                recentAlbums = albums,
                artists = artists,
            )

            // Sonic mixes live on the coordinator (a separate host that may be
            // down). Load them after the Emby rows so a slow/failed coordinator
            // never gates or blanks the rest of Home. Each mix tile shows its
            // representative track's Emby cover.
            val mixes = section { coordinator.mixes().take(HOME_SECTION_LIMIT) }
            val mixArt = runCatching {
                repository.artworkByIds(mixes.mapNotNull { it.coverTrackId })
            }.getOrDefault(emptyMap())
            val sonicMixes = mixes.map { mix ->
                mix.toLibraryItem().copy(imageUrl = mix.coverTrackId?.let { mixArt[it] })
            }
            _state.update { it.copy(sonicMixes = sonicMixes) }
        }
    }

    /** Runs one Home section's fetch, swallowing failure to an empty list. */
    private suspend fun <T> section(block: suspend () -> List<T>): List<T> =
        runCatching { block() }.getOrElse { emptyList() }

    /** Build and play a station queue, then open Now Playing. */
    fun playStation(station: HomeStation, decadeStart: Int? = null) {
        val libId = musicLibraryId
        if (libId == null) {
            viewModelScope.launch { _messages.send("No music library found") }
            return
        }
        viewModelScope.launch {
            runCatching {
                when (station) {
                    HomeStation.LIBRARY -> repository.libraryRadio(libId)
                    HomeStation.RANDOM_ALBUM -> repository.randomAlbumRadio(libId)
                    HomeStation.DECADE -> repository.decadeRadio(libId, decadeStart ?: 2000)
                }
            }.fold(
                onSuccess = { items ->
                    val first = items.firstOrNull()
                    if (first == null) {
                        _messages.send("No tracks for ${station.label}")
                    } else {
                        playback.playQueue(
                            items,
                            first,
                            PlaybackSource("station:${station.name}", station.label, "Station", first.imageUrl),
                        )
                        _openNowPlaying.send(Unit)
                    }
                },
                onFailure = { _messages.send("Couldn't start ${station.label}: ${it.message}") },
            )
        }
    }

    fun playPlaylist(item: LibraryItem) = playCollection(item, DetailKind.PLAYLIST_TRACKS)

    fun playSonicMix(item: LibraryItem) {
        viewModelScope.launch {
            runCatching { coordinator.mixDetail(item.id).tracks.map { it.toLibraryItem() }.withArtwork() }.fold(
                onSuccess = { tracks ->
                    tracks.firstOrNull()?.let {
                        playback.playQueue(
                            tracks,
                            it,
                            PlaybackSource("mix:${item.id}", item.title, "Sonic mix", item.imageUrl),
                        )
                        _openNowPlaying.send(Unit)
                    } ?: _messages.send("Nothing playable in \"${item.title}\"")
                },
                onFailure = { _messages.send("Couldn't start playback: ${it.message}") },
            )
        }
    }

    /** Resolve Emby cover art for coordinator track ids (mixes carry none). */
    private suspend fun List<LibraryItem>.withArtwork(): List<LibraryItem> {
        val art = runCatching { repository.artworkByIds(map { it.id }) }.getOrDefault(emptyMap())
        return map { item -> art[item.id]?.let { item.copy(imageUrl = it) } ?: item }
    }

    fun playAlbum(item: LibraryItem) = playCollection(item, DetailKind.ALBUM_TRACKS)

    fun playArtist(item: LibraryItem) = playCollection(item, DetailKind.ARTIST_ALBUMS)

    fun playResumeAudiobook(item: LibraryItem) = playCollection(item, DetailKind.BOOK_CHAPTERS)

    fun setCompactCards(value: Boolean) {
        viewModelScope.launch { settings.setHomeCompactCards(value) }
    }

    fun setSectionVisible(kind: HomeSectionKind, visible: Boolean) {
        viewModelScope.launch { settings.setHomeSectionVisible(kind.id, visible) }
    }

    fun moveSection(kind: HomeSectionKind, direction: Int) {
        val current = _state.value.sectionPreferences.map { it.kind }.toMutableList()
        val index = current.indexOf(kind)
        val nextIndex = (index + direction).coerceIn(current.indices)
        if (index == -1 || index == nextIndex) return
        current.removeAt(index)
        current.add(nextIndex, kind)
        viewModelScope.launch { settings.setHomeSectionOrder(current.map { it.id }) }
    }

    private fun playCollection(item: LibraryItem, detailKind: DetailKind) {
        viewModelScope.launch {
            runCatching { repository.playableItems(item.id, detailKind) }.fold(
                onSuccess = { items ->
                    val first = if (detailKind == DetailKind.BOOK_CHAPTERS) {
                        items.resumeStartItem()
                    } else {
                        items.firstOrNull()
                    }
                    if (first == null) {
                        _messages.send("Nothing playable in \"${item.title}\"")
                    } else {
                        playback.playQueue(items, first, playbackSourceFor(detailKind, item))
                        _openNowPlaying.send(Unit)
                    }
                },
                onFailure = { _messages.send("Couldn't start playback: ${it.message}") },
            )
        }
    }

    private fun SonicMixDto.toLibraryItem(): LibraryItem = LibraryItem(
        id = id,
        title = displayTitle(),
        subtitle = displayMeta(),
        imageUrl = null,
    )

    private fun SonicMixDto.displayTitle(): String {
        val base = name?.takeIf { it.isNotBlank() } ?: "Sonic mix"
        return base.replace(""" \(\d+\)$""".toRegex(), "")
    }

    private fun SonicMixDto.displayMeta(): String {
        val mixNumber = clusterId?.let { "Mix ${it + 1}" }
        val count = "$trackCount tracks"
        return listOfNotNull(mixNumber, count).joinToString(" • ")
    }

    private fun observeHomePreferences() {
        viewModelScope.launch {
            combine(
                settings.homeCompactCards,
                settings.homeSectionOrder,
                settings.homeHiddenSections,
            ) { compact, order, hidden ->
                val preferences = HomeSectionKind.ordered(order)
                    .map { HomeSectionPreference(kind = it, visible = it.id !in hidden) }
                compact to preferences
            }.collect { (compact, preferences) ->
                _state.update {
                    it.copy(
                        compactCards = compact,
                        sectionPreferences = preferences,
                    )
                }
            }
        }
    }

    private companion object {
        const val HOME_SECTION_LIMIT = 12
    }
}
