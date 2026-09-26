package dev.turnzen.app.data

import android.content.Context
import android.content.SharedPreferences
import dev.turnzen.dgs.DgsClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BoardSettings(
    val showCoordinates: Boolean = true,
    val markLastMove: Boolean = true,
    val numberTrialStones: Boolean = true,
)

/** Which colour scheme the app uses; SYSTEM follows the device setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val serverUrl: String = DgsClient.DEFAULT_BASE_URL,
    val board: BoardSettings = BoardSettings(),
    val theme: ThemeMode = ThemeMode.SYSTEM,
)

/** The one setting the session layer needs; split out so it can be faked in tests. */
interface ServerSelection {
    val serverUrl: String

    fun selectServer(url: String)
}

/** A server whose address is not a setting (OGS): the selection is fixed. */
class FixedServer(
    override val serverUrl: String,
) : ServerSelection {
    override fun selectServer(url: String) = Unit
}

/** Non-secret preferences, exposed as a StateFlow so screens recompose on change. */
class SettingsStore internal constructor(
    private val prefs: SharedPreferences,
) : ServerSelection {
    constructor(context: Context) : this(context.getSharedPreferences("turnzen_settings", Context.MODE_PRIVATE))

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override val serverUrl: String
        get() = _settings.value.serverUrl

    override fun selectServer(url: String) = update { it.copy(serverUrl = url) }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs
            .edit()
            .putString(KEY_SERVER_URL, next.serverUrl)
            .putBoolean(KEY_COORDINATES, next.board.showCoordinates)
            .putBoolean(KEY_LAST_MOVE, next.board.markLastMove)
            .putBoolean(KEY_TRIAL_NUMBERS, next.board.numberTrialStones)
            .putString(KEY_THEME, next.theme.name)
            .apply()
        _settings.value = next
    }

    private fun read(): AppSettings =
        AppSettings(
            serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: DgsClient.DEFAULT_BASE_URL,
            board =
                BoardSettings(
                    showCoordinates = prefs.getBoolean(KEY_COORDINATES, true),
                    markLastMove = prefs.getBoolean(KEY_LAST_MOVE, true),
                    numberTrialStones = prefs.getBoolean(KEY_TRIAL_NUMBERS, true),
                ),
            theme =
                runCatching {
                    ThemeMode.valueOf(
                        prefs.getString(KEY_THEME, null) ?: "",
                    )
                }.getOrDefault(ThemeMode.SYSTEM),
        )

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_COORDINATES = "board_coordinates"
        const val KEY_LAST_MOVE = "board_last_move"
        const val KEY_TRIAL_NUMBERS = "board_trial_numbers"
        const val KEY_THEME = "theme_mode"
    }
}
