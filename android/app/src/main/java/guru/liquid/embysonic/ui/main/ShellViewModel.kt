package guru.liquid.embysonic.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.emby.AudioLibrary
import guru.liquid.embysonic.data.emby.LibraryKind
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.data.emby.preferredLibrary
import guru.liquid.embysonic.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Discovers the user's audio libraries so the bottom nav can adapt: a user with no
 * audiobooks library never sees an Audiobooks tab.
 */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _libraries = MutableStateFlow<List<AudioLibrary>>(emptyList())
    val libraries: StateFlow<List<AudioLibrary>> = _libraries.asStateFlow()

    private val _selectedLibraryIds = MutableStateFlow<Map<LibraryKind, String>>(emptyMap())
    val selectedLibraryIds: StateFlow<Map<LibraryKind, String>> = _selectedLibraryIds.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { repository.audioLibraries() }
                .onSuccess { libraries ->
                    val music = libraries.preferredLibrary(
                        LibraryKind.MUSIC,
                        settings.selectedMusicLibraryId.first(),
                    )
                    val audiobooks = libraries.preferredLibrary(
                        LibraryKind.AUDIOBOOKS,
                        settings.selectedAudiobookLibraryId.first(),
                    )
                    _libraries.value = libraries
                    _selectedLibraryIds.value = buildMap {
                        music?.let { put(LibraryKind.MUSIC, it.id) }
                        audiobooks?.let { put(LibraryKind.AUDIOBOOKS, it.id) }
                    }
                }
            // On failure the nav simply shows Home + Mixes; user can retry by reopening.
        }
    }

    fun selectLibrary(library: AudioLibrary) {
        if (library !in _libraries.value) return
        _selectedLibraryIds.update { it + (library.kind to library.id) }
        viewModelScope.launch {
            when (library.kind) {
                LibraryKind.MUSIC -> settings.setSelectedMusicLibraryId(library.id)
                LibraryKind.AUDIOBOOKS -> settings.setSelectedAudiobookLibraryId(library.id)
            }
        }
    }
}
