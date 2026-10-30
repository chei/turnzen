package dev.turnzen.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.turnzen.app.MainActivity
import dev.turnzen.app.R
import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.app.ui.theme.DarkColors
import dev.turnzen.app.ui.theme.LightColors
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.ServerId

/**
 * Where a notification takes the user: carried on the intent that opens [MainActivity], and
 * read back by it (see [fromIntent]).
 */
sealed interface OpenRequest {
    data class Game(
        val id: GameId,
    ) : OpenRequest

    data object YourTurn : OpenRequest

    data object Settings : OpenRequest

    companion object {
        private const val ACTION_GAME = "dev.turnzen.OPEN_GAME"
        private const val ACTION_YOUR_TURN = "dev.turnzen.OPEN_YOUR_TURN"
        private const val ACTION_SETTINGS = "dev.turnzen.OPEN_SETTINGS"
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_GAME = "game"

        fun fromIntent(intent: Intent?): OpenRequest? =
            when (intent?.action) {
                ACTION_GAME -> {
                    val server = intent.getStringExtra(EXTRA_SERVER)
                    val game = intent.getStringExtra(EXTRA_GAME)
                    if (server != null && game != null) Game(GameId(ServerId(server), game)) else null
                }

                ACTION_YOUR_TURN -> {
                    YourTurn
                }

                ACTION_SETTINGS -> {
                    Settings
                }

                else -> {
                    null
                }
            }

        fun intent(
            context: Context,
            request: OpenRequest,
        ): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                when (request) {
                    is Game -> {
                        action = ACTION_GAME
                        putExtra(EXTRA_SERVER, request.id.server.value)
                        putExtra(EXTRA_GAME, request.id.value)
                    }

                    YourTurn -> {
                        action = ACTION_YOUR_TURN
                    }

                    Settings -> {
                        action = ACTION_SETTINGS
                    }
                }
            }
    }
}

/**
 * Posts and takes down Turnzen's notifications. Says only what it is told: what to say is
 * [TurnWatch]'s decision.
 */
class Notifier(
    context: Context,
) {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /** Android allows posting: the permission is granted and the app's notifications are not blocked. */
    val allowed: Boolean
        get() =
            manager.areNotificationsEnabled() &&
                (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                )

    /** Idempotent; called at every start so the channels exist before the first post. */
    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_YOUR_MOVE,
                    context.getString(R.string.notify_channel_your_move),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                NotificationChannel(
                    CHANNEL_LOW_TIME,
                    context.getString(R.string.notify_channel_low_time),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
                NotificationChannel(
                    CHANNEL_SIGNED_OUT,
                    context.getString(R.string.notify_channel_signed_out),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            ),
        )
    }

    val summaryShown: Boolean
        get() =
            context
                .getSystemService(NotificationManager::class.java)
                .activeNotifications
                .any { it.id == ID_SUMMARY && it.tag == null }

    /**
     * The news leads: the title names the games that just came round, and the line under it
     * counts the rest ("Your move against Joe" / "3 games in all"). With nothing new — a game
     * left the list — it falls back to the count.
     *
     * [largeIcon]: the opponent's icon, for a summary about one game ([SummaryAction.Post.lead]);
     * null to show none.
     */
    fun postSummary(
        games: List<GameSummary>,
        flagged: Set<GameId>,
        alert: Boolean,
        largeIcon: Bitmap? = null,
    ) {
        if (games.isEmpty()) return cancelSummary()
        val newGames = games.filter { it.id in flagged }
        val lead = SummaryAction.lead(games, flagged)
        val title =
            if (newGames.isNotEmpty() || lead != null) {
                val named = newGames.ifEmpty { games }
                context.getString(R.string.notify_your_move_one, named.joinToString(", ") { it.opponent.handle })
            } else {
                context.getString(R.string.notify_your_move_many, games.size)
            }
        // Under the news: the lead game's low clock, and how many games wait in all when the
        // title did not name every one of them.
        val total = games.size.takeIf { newGames.isNotEmpty() && it > newGames.size }
        val text =
            listOfNotNull(
                lead?.let(::timeLeft),
                total?.let { context.getString(R.string.notify_games_in_all, it) },
            ).joinToString(" · ").ifEmpty { null }
        // Expandable only when a line would say more than the names already do: a low clock.
        val style =
            if (newGames.size > 1 && newGames.any(TurnWatch::isLow)) {
                NotificationCompat.InboxStyle().setBigContentTitle(title).also { style ->
                    newGames.forEach { style.addLine(line(it)) }
                    total?.let { style.setSummaryText(context.getString(R.string.notify_games_in_all, it)) }
                }
            } else {
                null
            }
        val open = if (lead != null) OpenRequest.Game(lead.id) else OpenRequest.YourTurn
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_YOUR_MOVE)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(accent())
                .setContentTitle(title)
                .setContentText(text)
                .setLargeIcon(largeIcon?.takeIf { lead != null })
                .setStyle(style)
                .setNumber(games.size)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pending(open, 0))
                .setAutoCancel(true)
                .setOnlyAlertOnce(!alert)
                .setSilent(!alert)
                .build()
        post(null, ID_SUMMARY, notification)
    }

    fun cancelSummary() = manager.cancel(ID_SUMMARY)

    fun postLowTime(
        game: GameSummary,
        largeIcon: Bitmap? = null,
    ) {
        val title =
            context.getString(
                R.string.notify_low_time,
                game.opponent.handle,
                Formatting.remaining(game.remainingTime),
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_LOW_TIME)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(accent())
                .setContentTitle(title)
                .setLargeIcon(largeIcon)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending(OpenRequest.Game(game.id), game.id.hashCode()))
                .setAutoCancel(true)
                .build()
        post(lowTimeTag(game.id), ID_LOW_TIME, notification)
    }

    fun cancelLowTime(id: GameId) = manager.cancel(lowTimeTag(id), ID_LOW_TIME)

    fun postSignedOut(
        server: ServerId,
        serverName: String,
    ) {
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_SIGNED_OUT)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(accent())
                .setContentTitle(context.getString(R.string.notify_signed_out, serverName))
                .setContentText(context.getString(R.string.notify_signed_out_text))
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setContentIntent(pending(OpenRequest.Settings, server.hashCode()))
                .setAutoCancel(true)
                .build()
        post(SIGNED_OUT_TAG + server.value, ID_SIGNED_OUT, notification)
    }

    /**
     * The app is on screen: everything these would say, the user can now see for themselves.
     * Only this class's own; the sign-in's keep-awake notice belongs to its service.
     */
    fun cancelAll() {
        context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .filter { it.id == ID_SUMMARY || it.id == ID_LOW_TIME || it.id == ID_SIGNED_OUT }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun post(
        tag: String?,
        id: Int,
        notification: android.app.Notification,
    ) {
        if (!allowed) return
        try {
            manager.notify(tag, id, notification)
        } catch (e: SecurityException) {
            // The permission went away between the check and the post: nothing to show.
        }
    }

    /** "20 h left" when [TurnWatch.isLow], else nothing. */
    private fun timeLeft(game: GameSummary): String? =
        if (TurnWatch.isLow(game)) {
            context.getString(R.string.notify_time_left, Formatting.remaining(game.remainingTime))
        } else {
            null
        }

    /** "opponent · 20 h left". No server: tapping shows it, and the opponent is what the user goes by. */
    private fun line(game: GameSummary): String =
        listOfNotNull(game.opponent.handle, timeLeft(game)).joinToString(" · ")

    private fun pending(
        request: OpenRequest,
        code: Int,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            code,
            OpenRequest.intent(context, request),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private val dark: Boolean
        get() =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    private fun accent(): Int = (if (dark) DarkColors else LightColors).accent.toArgb()

    private fun lowTimeTag(id: GameId) = LOW_TIME_TAG + id.server.value + ":" + id.value

    private companion object {
        const val CHANNEL_YOUR_MOVE = "your-move"
        const val CHANNEL_LOW_TIME = "low-time"
        const val CHANNEL_SIGNED_OUT = "signed-out"

        // 1 is the sign-in keep-awake service's.
        const val ID_SUMMARY = 100
        const val ID_LOW_TIME = 101
        const val ID_SIGNED_OUT = 102
        const val LOW_TIME_TAG = "low-time:"
        const val SIGNED_OUT_TAG = "signed-out:"
    }
}
