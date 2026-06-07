package guru.liquid.embysonic.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.domain.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val coordinatorUrl: String = "",
    val userName: String = "",
    val savedMessage: String? = null,
    val loggedOut: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        val snap = settings.snapshot()
        _state.update {
            it.copy(
                serverUrl = snap.serverUrl.orEmpty(),
                coordinatorUrl = snap.coordinatorUrl.orEmpty(),
                userName = snap.userName.orEmpty(),
            )
        }
    }

    fun onCoordinatorUrlChange(value: String) =
        _state.update { it.copy(coordinatorUrl = value, savedMessage = null) }

    fun saveCoordinatorUrl() {
        val url = _state.value.coordinatorUrl.trim().trimEnd('/')
        if (url.isBlank()) return
        viewModelScope.launch {
            settings.saveCoordinatorUrl(url)
            _state.update { it.copy(coordinatorUrl = url, savedMessage = "Saved") }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _state.update { it.copy(loggedOut = true) }
        }
    }
}
