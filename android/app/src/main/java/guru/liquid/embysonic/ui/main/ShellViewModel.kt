package guru.liquid.embysonic.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.emby.AudioLibrary
import guru.liquid.embysonic.data.emby.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Discovers the user's audio libraries so the bottom nav can adapt: a user with no
 * audiobooks library never sees an Audiobooks tab.
 */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val repository: LibraryRepository,
) : ViewModel() {

    private val _libraries = MutableStateFlow<List<AudioLibrary>>(emptyList())
    val libraries: StateFlow<List<AudioLibrary>> = _libraries.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { repository.audioLibraries() }
                .onSuccess { _libraries.value = it }
            // On failure the nav simply shows Home + Mixes; user can retry by reopening.
        }
    }
}
