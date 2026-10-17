package dev.turnzen.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.SignedInUser

/**
 * One server's secrets: username and password (a password server only), the server session
 * blob and the last confirmed user.
 */
interface CredentialStore {
    var credentials: Credentials?
    var session: String?

    /** Last user the server confirmed, so the app can start offline. */
    var user: SignedInUser?

    fun clear()
}

/**
 * [CredentialStore] encrypted at rest with a key in the Android Keystore. Nothing here
 * ever leaves the device.
 */
class EncryptedCredentialStore internal constructor(
    private val prefs: SharedPreferences,
) : CredentialStore {
    /** [fileName] keeps each server's secrets apart; DGS keeps the original file. */
    constructor(context: Context, fileName: String = "turnzen_secrets") : this(encryptedPrefs(context, fileName))

    override var credentials: Credentials?
        get() {
            val u = prefs.getString(KEY_USERNAME, null) ?: return null
            val p = prefs.getString(KEY_PASSWORD, null) ?: return null
            return Credentials(u, p)
        }
        set(value) {
            prefs
                .edit()
                .apply {
                    if (value == null) {
                        remove(KEY_USERNAME).remove(KEY_PASSWORD)
                    } else {
                        putString(KEY_USERNAME, value.username).putString(KEY_PASSWORD, value.password)
                    }
                }.apply()
        }

    override var session: String?
        get() = prefs.getString(KEY_SESSION, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_SESSION) else putString(KEY_SESSION, value) }.apply()
        }

    override var user: SignedInUser?
        get() {
            val id = prefs.getString(KEY_USER_ID, null) ?: return null
            val handle = prefs.getString(KEY_USER_HANDLE, null) ?: return null
            return SignedInUser(
                id,
                handle,
                prefs.getString(KEY_USER_NAME, null),
                prefs.getString(KEY_USER_RANK, null),
                prefs.getString(KEY_USER_ICON, null),
            )
        }
        set(value) {
            prefs
                .edit()
                .apply {
                    if (value == null) {
                        remove(KEY_USER_ID)
                            .remove(KEY_USER_HANDLE)
                            .remove(KEY_USER_NAME)
                            .remove(KEY_USER_RANK)
                            .remove(KEY_USER_ICON)
                    } else {
                        putString(KEY_USER_ID, value.id)
                        putString(KEY_USER_HANDLE, value.handle)
                        putString(KEY_USER_NAME, value.displayName)
                        putString(KEY_USER_RANK, value.rank)
                        putString(KEY_USER_ICON, value.iconUrl)
                    }
                }.apply()
        }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_SESSION = "session"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_HANDLE = "user_handle"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_RANK = "user_rank"
        const val KEY_USER_ICON = "user_icon"
    }
}

/** The encrypted file [EncryptedCredentialStore] normally sits on. */
@Suppress("DEPRECATION")
private fun encryptedPrefs(
    context: Context,
    fileName: String,
): SharedPreferences =
    EncryptedSharedPreferences.create(
        context,
        fileName,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
