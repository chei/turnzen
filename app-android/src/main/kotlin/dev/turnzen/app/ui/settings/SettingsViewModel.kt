package dev.turnzen.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.turnzen.app.BuildConfig
import dev.turnzen.app.data.AppSettings
import dev.turnzen.app.data.BoardSettings
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.SessionManager
import dev.turnzen.app.data.SettingsStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val session: SessionManager,
    private val settingsStore: SettingsStore,
    private val games: GamesStore,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsStore.settings

    val userHandle: String
        get() = session.signedInUser?.handle ?: "–"

    val serverName: String
        get() = session.serverName

    val sourceUrl: String = BuildConfig.SOURCE_URL
    val issuesUrl: String = BuildConfig.SOURCE_URL + "/issues"

    fun updateBoard(transform: (BoardSettings) -> BoardSettings) =
        settingsStore.update {
            it.copy(board = transform(it.board))
        }

    fun signOut() {
        viewModelScope.launch {
            games.clear()
            session.signOut()
        }
    }
}
