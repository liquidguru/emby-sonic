package guru.liquid.embysonic.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.dto.SonicStatus
import guru.liquid.embysonic.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface StatusUiState {
    data object Loading : StatusUiState
    data class Ready(val status: SonicStatus) : StatusUiState
    data class Error(val message: String) : StatusUiState
}

data class HomeUiState(
    val userName: String? = null,
    val coordinatorUrl: String? = null,
    val status: StatusUiState = StatusUiState.Loading,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val coordinatorApi: CoordinatorApi,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        val snap = settings.snapshot()
        _state.update { it.copy(userName = snap.userName, coordinatorUrl = snap.coordinatorUrl) }
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(status = StatusUiState.Loading) }
        viewModelScope.launch {
            runCatching { coordinatorApi.status() }.fold(
                onSuccess = { s -> _state.update { it.copy(status = StatusUiState.Ready(s)) } },
                onFailure = { e ->
                    _state.update {
                        it.copy(status = StatusUiState.Error(e.message ?: "Could not reach coordinator"))
                    }
                },
            )
        }
    }
}
