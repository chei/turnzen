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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * A player's icon: a square with softened corners — the way OGS shows its pictures, and
 * unlike a stone, the one round thing on these screens. The picture they chose where the
 * server has one ([url]); otherwise, and while it loads, their initial in colours made from
 * their name ([PlayerColors]), so every opponent is told apart at a glance on either server.
 * Decorative: the name next to it is what TalkBack reads.
 */
@Composable
fun PlayerIcon(
    url: String?,
    name: String,
    /** What the colours are made from: the same for the same player, whichever list shows them. */
    colorKey: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val cacheDir = LocalContext.current.cacheDir
    // Starts from the cache, so a row scrolled back into view does not flash its initial.
    val image by produceState(url?.let(PlayerIcons::cached), url) {
        if (value == null && url != null) value = PlayerIcons.load(url, cacheDir)
    }
    val tint = remember(colorKey) { PlayerColors.of(colorKey) }
    // Sized from the icon rather than the font scale, so a large system font cannot push the
    // letter out of its square; the name beside it scales as usual.
    val letterSize = with(LocalDensity.current) { (size * LETTER_RATIO).toSp() }
    val loaded = image
    // The tint only behind the letter. Under a picture it showed as a coloured hairline
    // where the rounded clip's anti-aliased edge blends the two, and through any
    // transparent margin the picture has; on OGS a picture sits on the page, so here too.
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(CORNER))
            .then(if (loaded == null) Modifier.background(tint.background) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (loaded != null) {
            Image(loaded, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                initial(name),
                color = tint.letter,
                fontSize = letterSize,
                fontWeight = FontWeight.SemiBold,
                lineHeight = letterSize,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** The first letter or digit of [name], so "_kaya" shows K rather than an underscore. */
internal fun initial(name: String): String =
    (name.firstOrNull { it.isLetterOrDigit() } ?: name.firstOrNull())?.uppercase().orEmpty()

/** The letter's height against the square's: big enough to read as a mark, not as text. */
private const val LETTER_RATIO = 0.5f

/**
 * A light background and a dark letter for a player, both drawn from a hash of their key, so
 * the same opponent always gets the same pair. The same in light and dark theme: the icon is
 * the player's mark, not part of the page, and a light square reads on either.
 *
 * The hue carries most of the difference; lightness and saturation take a few steps from
 * other bits of the hash, so two players whose hues land close still differ. The letter is
 * the background's own hue, dark — related, so the pair reads as one mark, and at least
 * 5:1 against it at every step (WCAG asks 4.5:1 of body text).
 */
internal object PlayerColors {
    data class Tint(
        val background: Color,
        val letter: Color,
    )

    private val backgroundLightness = floatArrayOf(0.74f, 0.8f, 0.86f)
    private val backgroundSaturation = floatArrayOf(0.6f, 0.75f, 0.9f)
    private val letterLightness = floatArrayOf(0.15f, 0.2f)

    fun of(key: String): Tint {
        val hash = fnv1a(key)
        val hue = (hash % 360u).toFloat()
        val bits = hash / 360u
        val saturation = backgroundSaturation[(bits % 3u).toInt()]
        val lightness = backgroundLightness[(bits / 3u % 3u).toInt()]
        val letter = letterLightness[(bits / 9u % 2u).toInt()]
        return Tint(
            background = Color.hsl(hue, saturation, lightness),
            letter = Color.hsl(hue, 0.6f, letter),
        )
    }

    /** FNV-1a over the UTF-8 bytes: the same on every device and run, and well spread over short names. */
    private fun fnv1a(key: String): UInt {
        var hash = 2166136261u
        for (byte in key.encodeToByteArray()) {
            hash = (hash xor byte.toUByte().toUInt()) * 16777619u
        }
        return hash
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
