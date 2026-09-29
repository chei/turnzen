package dev.turnzen.ogs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { input ->
        digest.usePinned { output ->
            CC_SHA256(if (bytes.isEmpty()) null else input.addressOf(0), bytes.size.convert(), output.addressOf(0))
        }
    }
    return digest.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    if (count == 0) return bytes
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) }
    check(status == errSecSuccess) { "No secure random bytes (status $status)" }
    return bytes
}

/**
 * Not built yet. iOS apps cannot keep a loopback listener open while the browser is in front,
 * so the iOS app will sign in through `ASWebAuthenticationSession` instead, and that needs a
 * redirect OGS accepts (see the brief, "Implementation decisions").
 */
internal actual fun redirectReceiver(
    port: Int,
    path: String,
    returnLink: String?,
): RedirectReceiver = throw UnsupportedOperationException("Signing in to OGS is not available on iOS yet")
