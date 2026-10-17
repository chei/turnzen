package dev.turnzen.app.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.turnzen.app.ui.theme.colors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/**
 * The picture a player chose to be shown by, as a square with softened corners — the way OGS
 * shows it, and unlike a stone, which is the one round thing on these screens; their initial on a quiet disc while
 * it loads, and for good when there is none or it cannot be fetched. Decorative: the name next
 * to it is what TalkBack reads.
 */
@Composable
fun PlayerIcon(
    url: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val cacheDir = LocalContext.current.cacheDir
    // Starts from the cache, so a row scrolled back into view does not flash its initial.
    val image by produceState(url?.let(PlayerIcons::cached), url) {
        if (value == null && url != null) value = PlayerIcons.load(url, cacheDir)
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(CORNER)).background(colors.line),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = image
        if (loaded != null) {
            Image(loaded, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                name.firstOrNull()?.uppercase().orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
            )
        }
    }
}

private val CORNER = 4.dp

/**
 * Fetches and keeps player icons. Decoded pictures stay in memory for as long as the process
 * lives — a handful of opponents, a few kilobytes each, so there is no eviction — and a failed
 * fetch is remembered there too, until the next start.
 *
 * The downloaded files are also kept in the app's cache directory, so a cold start draws the
 * list with its pictures and without a request per opponent. A file is trusted for [MAX_AGE],
 * after which the picture is fetched again (players do change theirs); if that fails, the
 * old file still serves. Android may empty the directory when storage runs low, which costs
 * nothing but a download.
 *
 * Plain [HttpURLConnection] rather than an image library: one small picture per opponent
 * does not earn a dependency.
 */
private object PlayerIcons {
    /** Largest edge kept in memory; a list row draws the icon well under this. */
    private const val MAX_EDGE = 128

    /** Anything bigger is not an icon, and is not downloaded. */
    private const val MAX_BYTES = 512 * 1024

    private const val TIMEOUT_MILLIS = 15_000

    private val MAX_AGE = 7.days

    private const val DIR = "player-icons"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val icons = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun cached(url: String): ImageBitmap? = icons[url]?.takeIf { it.isCompleted }?.getCompleted()

    suspend fun load(
        url: String,
        cacheDir: File,
    ): ImageBitmap? = icons.computeIfAbsent(url) { scope.async { fromDiskOrNetwork(it, cacheDir) } }.await()

    private fun fromDiskOrNetwork(
        url: String,
        cacheDir: File,
    ): ImageBitmap? {
        val file = File(File(cacheDir, DIR), fileName(url))
        val age = (System.currentTimeMillis() - file.lastModified()).milliseconds
        val stored = if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
        if (stored != null && age < MAX_AGE) decode(stored)?.let { return it }
        val fetched = fetch(url)
        val image = fetched?.let(::decode)
        if (image != null) store(file, fetched) else return stored?.let(::decode)
        return image
    }

    /** Written beside the target and renamed into place, so a reader never sees half a file. */
    private fun store(
        file: File,
        bytes: ByteArray,
    ) {
        runCatching {
            file.parentFile?.mkdirs()
            val partial = File(file.parentFile, "${file.name}.part")
            partial.writeBytes(bytes)
            if (!partial.renameTo(file)) partial.delete()
        }
    }

    /** The URL hashed: it names the file without its characters having to be legal in one. */
    private fun fileName(url: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun fetch(url: String): ByteArray? =
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                connection.inputStream.use(::readSmall)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    /** The whole stream, or null once it grows past [MAX_BYTES]. */
    private fun readSmall(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) return null
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}
