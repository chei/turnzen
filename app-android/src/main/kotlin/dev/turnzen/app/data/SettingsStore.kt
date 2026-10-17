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

/** How often the background check asks the servers for games awaiting the user. */
enum class CheckInterval(
    val minutes: Long,
) {
    MIN_15(15),
    MIN_30(30),
    HOUR_1(60),
    HOUR_3(180),
}

/** Both switches start off: notifications are asked for, never assumed. */
data class NotificationSettings(
    val yourMove: Boolean = false,
    val lowTime: Boolean = false,
    val checkEvery: CheckInterval = CheckInterval.MIN_30,
) {
    /** Whether the background check has anything to do at all. */
    val anyOn: Boolean
        get() = yourMove || lowTime
}

/** Which colour scheme the app uses; SYSTEM follows the device setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val serverUrl: String = DgsClient.DEFAULT_BASE_URL,
    val board: BoardSettings = BoardSettings(),
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val notifications: NotificationSettings = NotificationSettings(),
    /** Opponents' icons in the games list: their picture on OGS, their coloured initial elsewhere. */
    val playerIcons: Boolean = true,
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
            .putBoolean(KEY_NOTIFY_YOUR_MOVE, next.notifications.yourMove)
            .putBoolean(KEY_NOTIFY_LOW_TIME, next.notifications.lowTime)
            .putString(KEY_CHECK_EVERY, next.notifications.checkEvery.name)
            .putBoolean(KEY_PLAYER_ICONS, next.playerIcons)
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
            notifications =
                NotificationSettings(
                    yourMove = prefs.getBoolean(KEY_NOTIFY_YOUR_MOVE, false),
                    lowTime = prefs.getBoolean(KEY_NOTIFY_LOW_TIME, false),
                    checkEvery =
                        runCatching { CheckInterval.valueOf(prefs.getString(KEY_CHECK_EVERY, null) ?: "") }
                            .getOrDefault(CheckInterval.MIN_30),
                ),
            playerIcons = prefs.getBoolean(KEY_PLAYER_ICONS, true),
        )

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_COORDINATES = "board_coordinates"
        const val KEY_LAST_MOVE = "board_last_move"
        const val KEY_TRIAL_NUMBERS = "board_trial_numbers"
        const val KEY_THEME = "theme_mode"
        const val KEY_NOTIFY_YOUR_MOVE = "notify_your_move"
        const val KEY_NOTIFY_LOW_TIME = "notify_low_time"
        const val KEY_CHECK_EVERY = "notify_check_every"
        const val KEY_PLAYER_ICONS = "player_icons"
    }
}
