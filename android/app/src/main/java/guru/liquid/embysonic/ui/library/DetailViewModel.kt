package guru.liquid.embysonic.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.emby.DetailKind
import guru.liquid.embysonic.data.emby.LibraryRepository
import guru.liquid.embysonic.ui.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs a single drill-down level: an artist's albums, an album's tracks, an
 * author's books, or a book's chapters — selected by [DetailKind].
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: LibraryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>(Routes.ARG_ITEM_ID).orEmpty()
    val kind: DetailKind = runCatching {
        DetailKind.valueOf(savedStateHandle.get<String>(Routes.ARG_DETAIL_KIND).orEmpty())
    }.getOrDefault(DetailKind.ALBUM_TRACKS)
    val title: String = savedStateHandle.get<String>(Routes.ARG_TITLE).orEmpty()

    private val _state = MutableStateFlow<TabState>(TabState.Loading)
    val state: StateFlow<TabState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = TabState.Loading
        viewModelScope.launch {
            runCatching { repository.childItems(itemId, kind) }.fold(
                onSuccess = { _state.value = TabState.Data(it) },
                onFailure = { _state.value = TabState.Error(it.message ?: "Failed to load") },
            )
        }
    }
}
