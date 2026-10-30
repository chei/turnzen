package dev.turnzen.app.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import dev.turnzen.app.R
import dev.turnzen.app.ui.common.PlayerColors
import dev.turnzen.app.ui.common.PlayerIcons
import dev.turnzen.app.ui.common.iconIsSquare
import dev.turnzen.app.ui.common.initial
import dev.turnzen.app.ui.common.playerColorKey
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.ServerId
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * An opponent's icon as a notification's large icon: the same mark the games list shows —
 * their picture where the server has one, else their initial on their own colours — so a
 * notification is recognised before it is read.
 */
class NotificationIcons(
    context: Context,
) {
    private val context = context.applicationContext

    /** The size Android draws a large icon at; a bitmap of it is not scaled again. */
    private val sizePx =
        context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)

    suspend fun of(game: GameSummary): Bitmap {
        val opponent = game.opponent
        // From the cache almost always: the list has shown it. A fetch that hangs must not
        // hold up the notification, which then carries the initial instead.
        val picture =
            opponent.iconUrl?.let { url ->
                withTimeoutOrNull(PICTURE_WAIT) { PlayerIcons.load(url, context.cacheDir) }
            }
        return picture?.asAndroidBitmap()
            ?: initialIcon(opponent.handle, game.id.server)
    }

    /** What PlayerIcon draws without a picture, drawn on a canvas instead of composed. */
    private fun initialIcon(
        name: String,
        server: ServerId,
    ): Bitmap {
        val tint = PlayerColors.of(playerColorKey(server, name))
        val size = sizePx.toFloat()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val corner = size * CORNER_RATIO
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint.background.toArgb() }
        if (iconIsSquare(server)) {
            canvas.drawRoundRect(RectF(0f, 0f, size, size), corner, corner, background)
        } else {
            canvas.drawCircle(size / 2, size / 2, size / 2, background)
        }
        val letter =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tint.letter.toArgb()
                textAlign = Paint.Align.CENTER
                textSize = size * LETTER_RATIO
                typeface = ResourcesCompat.getFont(context, R.font.ibm_plex_sans_semibold)
            }
        val metrics = letter.fontMetrics
        canvas.drawText(initial(name), size / 2, size / 2 - (metrics.ascent + metrics.descent) / 2, letter)
        return bitmap
    }

    private companion object {
        val PICTURE_WAIT = 5.seconds

        /** As in the list: 4dp corners on a 36dp square, a letter half its height. */
        const val CORNER_RATIO = 4f / 36f
        const val LETTER_RATIO = 0.5f
    }
}
