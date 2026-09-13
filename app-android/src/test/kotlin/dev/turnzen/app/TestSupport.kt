package dev.turnzen.app

import android.content.SharedPreferences
import dev.turnzen.app.data.CredentialStore
import dev.turnzen.app.data.EncryptedCredentialStore
import dev.turnzen.app.data.ServerSelection
import dev.turnzen.app.data.SessionManager
import dev.turnzen.app.data.SettingsStore
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.SignedInUser
import dev.turnzen.goserver.TurnBasedGoServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

class InMemoryCredentialStore : CredentialStore {
    override var credentials: Credentials? = null
    override var session: String? = null
    override var user: SignedInUser? = null

    override fun clear() {
        credentials = null
        session = null
        user = null
    }
}

/** ViewModels launch on Dispatchers.Main; tests swap it for a controllable dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class MainDispatcherTest {
    protected val dispatcher: TestDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setMain() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()
}

class InMemoryServerSelection(
    override var serverUrl: String = "https://dgs.example.test/",
) : ServerSelection {
    override fun selectServer(url: String) {
        serverUrl = url
    }
}

fun sessionFor(
    client: TurnBasedGoServerClient,
    store: CredentialStore = InMemoryCredentialStore(),
): SessionManager = SessionManager(store, InMemoryServerSelection()) { client }

/**
 * A [SharedPreferences] that keeps everything in a map, so the real [SettingsStore] and
 * [EncryptedCredentialStore] can be exercised on the JVM without an emulator. It only has
 * to be faithful about what those two use: strings, booleans, remove, and clear.
 *
 * Editing follows the real contract — the edits of one [Editor] land together on apply(),
 * and a clear() in the same batch is applied before the puts.
 */
class FakeSharedPreferences(
    initial: Map<String, Any?> = emptyMap(),
) : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>(initial)

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = values[key] as? String ?: defValue

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return values[key] as? MutableSet<String> ?: defValues
    }

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearFirst = false

        override fun putString(
            key: String,
            value: String?,
        ) = also { pending[key] = value }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ) = also { pending[key] = values }

        override fun putInt(
            key: String,
            value: Int,
        ) = also { pending[key] = value }

        override fun putLong(
            key: String,
            value: Long,
        ) = also { pending[key] = value }

        override fun putFloat(
            key: String,
            value: Float,
        ) = also { pending[key] = value }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) = also { pending[key] = value }

        override fun remove(key: String) = also { removed += key }

        override fun clear() = also { clearFirst = true }

        override fun commit(): Boolean {
            write()
            return true
        }

        override fun apply() = write()

        private fun write() {
            if (clearFirst) values.clear()
            removed.forEach { values.remove(it) }
            // A null string put is how SharedPreferences itself spells "remove".
            pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
        }
    }
}
