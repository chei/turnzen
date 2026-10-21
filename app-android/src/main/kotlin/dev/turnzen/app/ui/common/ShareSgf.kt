package dev.turnzen.app.ui.common

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.turnzen.game.PlayerInfo
import dev.turnzen.game.sgf.SgfGameRecords
import dev.turnzen.goserver.GameDetail
import java.io.File

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
 * save it, mail it, open it in a Go app. Written to the app's cache first, where only the one
 * file being shared is kept, and passed on through a content link that grants the receiving
 * app read access to that file alone — no storage permission on either side.
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
    context.startActivity(Intent.createChooser(send, null))
}

/** The type Go programs register for SGF files. */
private const val SGF_MIME_TYPE = "application/x-go-sgf"
