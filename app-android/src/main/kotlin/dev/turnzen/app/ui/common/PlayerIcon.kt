package dev.turnzen.app.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.Dp
import dev.turnzen.app.ui.theme.colors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * The picture a player chose to be shown by, in a circle; their initial on a quiet disc while
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
    // Starts from the cache, so a row scrolled back into view does not flash its initial.
    val image by produceState(url?.let(PlayerIcons::cached), url) {
        if (value == null && url != null) value = PlayerIcons.load(url)
    }
    Box(
        modifier.size(size).clip(CircleShape).background(colors.line),
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

/**
 * Fetches and keeps player icons for as long as the process lives. A handful of opponents,
 * each a picture of a few kilobytes, so there is no eviction and no disk cache: a failed
 * fetch is remembered too, and tried again on the next start.
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val icons = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun cached(url: String): ImageBitmap? = icons[url]?.takeIf { it.isCompleted }?.getCompleted()

    suspend fun load(url: String): ImageBitmap? = icons.computeIfAbsent(url) { scope.async { fetch(it) } }.await()

    private fun fetch(url: String): ImageBitmap? =
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                connection.inputStream.use(::readSmall)?.let(::decode)
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
