package guru.liquid.embysonic.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.coordinator.dto.SonicStatus
import guru.liquid.embysonic.data.settings.SettingsRepository
import guru.liquid.embysonic.data.settings.ThemeChoice
import guru.liquid.embysonic.domain.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AnalysisStatusUiState {
    data object Loading : AnalysisStatusUiState
    data class Ready(val status: SonicStatus) : AnalysisStatusUiState
    data class Error(val message: String) : AnalysisStatusUiState
}

data class SettingsUiState(
    val serverUrl: String = "",
    val coordinatorUrl: String = "",
    val castServerUrl: String = "",
    val userName: String = "",
    val savedMessage: String? = null,
    val loggedOut: Boolean = false,
    val analysisStatus: AnalysisStatusUiState = AnalysisStatusUiState.Loading,
    val crossfadeEnabled: Boolean = false,
    val crossfadeSeconds: Int = 6,
    val generatedMixTracks: Int = 25,
    val themeChoice: ThemeChoice = ThemeChoice.DEFAULT,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val coordinatorApi: CoordinatorApi,
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
                castServerUrl = snap.castServerUrl.orEmpty(),
                userName = snap.userName.orEmpty(),
                crossfadeEnabled = snap.crossfadeEnabled,
                crossfadeSeconds = snap.crossfadeDurationMs / 1000,
                generatedMixTracks = snap.generatedMixTracks,
                themeChoice = snap.themeChoice,
            )
        }
        refreshAnalysisStatus()
    }

    fun setCrossfadeEnabled(value: Boolean) {
        _state.update { it.copy(crossfadeEnabled = value) }
        viewModelScope.launch { settings.setCrossfadeEnabled(value) }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        _state.update { it.copy(crossfadeSeconds = seconds) }
        viewModelScope.launch { settings.setCrossfadeDurationMs(seconds * 1000) }
    }

    fun setGeneratedMixTracks(count: Int) {
        _state.update { it.copy(generatedMixTracks = count) }
        viewModelScope.launch { settings.setGeneratedMixTracks(count) }
    }

    fun setThemeChoice(choice: ThemeChoice) {
        _state.update { it.copy(themeChoice = choice) }
        viewModelScope.launch { settings.setThemeChoice(choice) }
    }

    fun onCoordinatorUrlChange(value: String) =
        _state.update { it.copy(coordinatorUrl = value, savedMessage = null) }

    fun onCastServerUrlChange(value: String) =
        _state.update { it.copy(castServerUrl = value, savedMessage = null) }

    fun saveCastServerUrl() {
        val url = _state.value.castServerUrl.trim().trimEnd('/')
        viewModelScope.launch {
            settings.setCastServerUrl(url)
            _state.update { it.copy(castServerUrl = url, savedMessage = "Saved") }
        }
    }

    fun saveCoordinatorUrl() {
        val url = _state.value.coordinatorUrl.trim().trimEnd('/')
        if (url.isBlank()) return
        viewModelScope.launch {
            settings.saveCoordinatorUrl(url)
            _state.update { it.copy(coordinatorUrl = url, savedMessage = "Saved") }
            refreshAnalysisStatus()
        }
    }

    fun refreshAnalysisStatus() {
        _state.update { it.copy(analysisStatus = AnalysisStatusUiState.Loading) }
        viewModelScope.launch {
            runCatching { coordinatorApi.status() }.fold(
                onSuccess = { status ->
                    _state.update { it.copy(analysisStatus = AnalysisStatusUiState.Ready(status)) }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            analysisStatus = AnalysisStatusUiState.Error(
                                error.message ?: "Could not reach coordinator",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _state.update { it.copy(loggedOut = true) }
        }
    }
}
