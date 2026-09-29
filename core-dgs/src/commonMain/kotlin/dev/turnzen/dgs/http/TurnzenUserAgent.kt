package dev.turnzen.dgs.http

/**
 * The one User-Agent string the app sends, in the form
 * `Turnzen/0.1.0 (Android 36; +https://github.com/chei/turnzen)`.
 *
 * Server-neutral on purpose: every server client shares the transport, so DGS, and
 * later OGS, identify themselves the same way. The values come from the Android module
 * (the app's version and the running device's API level); nothing here is per-account,
 * and nothing about the signed-in user may ever be added.
 */
object TurnzenUserAgent {
    fun format(
        versionName: String,
        androidSdkInt: Int,
        sourceUrl: String,
    ): String = "Turnzen/$versionName (Android $androidSdkInt; +$sourceUrl)"
}
