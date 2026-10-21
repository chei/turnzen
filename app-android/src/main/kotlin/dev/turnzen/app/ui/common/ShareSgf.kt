package dev.turnzen.app.ui.common

import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.chooser.ChooserAction
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import dev.turnzen.app.MainActivity
import dev.turnzen.app.R
import dev.turnzen.game.PlayerInfo
import dev.turnzen.game.sgf.SgfGameRecords
import dev.turnzen.goserver.GameDetail
import java.io.File
import java.io.IOException

/** A game as an SGF file: the name it is offered under and its text. */
data class SgfFile(
    val name: String,
    val text: String,
)

/**
 * [detail] as SGF: the moves and setup, the players with their ranks, komi, handicap and the
 * result once there is one. The players come from the game's own details where the record
 * lacks them, so the file names who played. No comments: some are private to their writer.
 *
 * Named after the players and the game ("alice-bob-1503981.sgf"), reduced to characters any
 * file system and any receiving app accepts.
 */
fun sgfFile(detail: GameDetail): SgfFile {
    val record = detail.record
    val info = detail.info
    val black = record.black.withFallback(info?.black?.handle, info?.black?.rank)
    val white = record.white.withFallback(info?.white?.handle, info?.white?.rank)
    val text = SgfGameRecords.write(record.copy(black = black, white = white))
    val name =
        listOf(black.name ?: "black", white.name ?: "white", detail.summary.id.value)
            .joinToString("-") { it.replace(UNSAFE, "_") }
    return SgfFile("$name.sgf", text)
}

private val UNSAFE = Regex("[^A-Za-z0-9_.-]")

private fun PlayerInfo.withFallback(
    handle: String?,
    rank: String?,
) = PlayerInfo(name ?: handle, this.rank ?: rank)

/**
 * Hands [sgf] to Android's share sheet, which offers whatever the phone can do with a file:
 * mail it, open it in a Go app, put it on Drive. Written to the app's cache first, where only
 * the one file being shared is kept, and passed on through a content link that grants the
 * receiving app read access to that file alone — no storage permission on either side.
 *
 * Saving it to the phone is not among those on a stock phone, so from Android 14 the sheet
 * gets a "Save to device" action of its own ([saveInShareSheet]). The action cannot call back
 * into the screen that opened the sheet; it reopens [MainActivity] with [ACTION_SAVE_SGF],
 * which offers the file still in the cache to Android's "Save as" picker.
 */
fun shareSgf(
    context: Context,
    sgf: SgfFile,
) {
    val dir = File(context.cacheDir, "shared")
    dir.deleteRecursively()
    dir.mkdirs()
    val file = File(dir, sgf.name).apply { writeText(sgf.text) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = SGF_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            // The clip carries the grant to the share sheet itself, so it can preview the file.
            clipData = ClipData.newRawUri(sgf.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    val chooser = Intent.createChooser(send, null)
    if (saveInShareSheet) {
        chooser.putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, arrayOf(saveAction(context)))
    }
    context.startActivity(chooser)
}

/** Whether the share sheet carries "Save to device" (Android 14 on); before that, a row of its own does. */
val saveInShareSheet: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun saveAction(context: Context): ChooserAction {
    // Carries nothing: the file to save is the one in the cache, the only one kept there. So
    // another app sending this action can at most offer that file to a picker the user sees.
    val open = Intent(context, MainActivity::class.java).setAction(ACTION_SAVE_SGF)
    val pending = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE)
    return ChooserAction
        .Builder(
            Icon.createWithResource(context, R.drawable.ic_save),
            context.getString(R.string.share_save_to_device),
            pending,
        ).build()
}

/** What the share sheet's "Save to device" asks [MainActivity] to do. */
const val ACTION_SAVE_SGF = "dev.turnzen.action.SAVE_SGF"

/** The file last handed to the share sheet, while the cache still has it. */
fun lastSharedSgf(context: Context): SgfFile? {
    val file = File(context.cacheDir, "shared").listFiles()?.singleOrNull { it.isFile } ?: return null
    return try {
        SgfFile(file.name, file.readText())
    } catch (e: IOException) {
        null
    }
}

/**
 * Writes [sgf] to [target], a file the user just created in Android's own "Save as" picker —
 * Downloads, a Drive folder, anywhere a document provider offers. Nothing but that one file
 * is reachable, so no storage permission is needed. Says so when the write fails; success
 * needs no word, the picker closing on it is the answer.
 */
fun saveSgf(
    context: Context,
    target: Uri,
    sgf: SgfFile,
) {
    val written =
        try {
            context.contentResolver.openOutputStream(target, "wt")?.use { it.write(sgf.text.toByteArray()) } != null
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    if (!written) Toast.makeText(context, R.string.info_save_sgf_failed, Toast.LENGTH_LONG).show()
}

/** The type Go programs register for SGF files. */
const val SGF_MIME_TYPE = "application/x-go-sgf"
