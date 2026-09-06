package dev.turnzen.app.ui.settings

import android.os.Build
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
import java.net.URLEncoder

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

    /**
     * A pre-filled "new issue" link. The body carries the app version and device so a
     * report is actionable without a visible Version row on the screen; the user still
     * types the description above the divider.
     */
    val issuesUrl: String
        get() {
            val body =
                buildString {
                    append("\n\n\n---\n")
                    append("Turnzen ").append(BuildConfig.VERSION_NAME).append('\n')
                    append("Android ").append(Build.VERSION.RELEASE)
                    append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                }
            return BuildConfig.SOURCE_URL + "/issues/new?body=" + URLEncoder.encode(body, Charsets.UTF_8.name())
        }

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
