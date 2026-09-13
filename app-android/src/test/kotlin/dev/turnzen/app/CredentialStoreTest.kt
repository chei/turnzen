package dev.turnzen.app

import dev.turnzen.app.data.EncryptedCredentialStore
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.SignedInUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store is a mapping between the three stored objects and preference keys; these tests
 * run it over a plain in-memory [FakeSharedPreferences]. The encryption underneath is
 * androidx's and needs the Keystore, so it is out of scope here — what is tested is that
 * nothing is read back half-formed, and that signing out really does leave nothing behind.
 */
class CredentialStoreTest {
    @Test
    fun `an empty store has nothing in it`() {
        val store = EncryptedCredentialStore(FakeSharedPreferences())
        assertNull(store.credentials)
        assertNull(store.session)
        assertNull(store.user)
    }

    @Test
    fun `credentials, session and user round-trip`() {
        val prefs = FakeSharedPreferences()
        EncryptedCredentialStore(prefs).apply {
            credentials = Credentials("demo", "hunter2")
            session = "sessioncode=abc"
            user = SignedInUser("42", "demo", "Demo User", "8k")
        }

        // A second store over the same file: this is what the next app start sees.
        val reopened = EncryptedCredentialStore(prefs)
        assertEquals(Credentials("demo", "hunter2"), reopened.credentials)
        assertEquals("sessioncode=abc", reopened.session)
        assertEquals(SignedInUser("42", "demo", "Demo User", "8k"), reopened.user)
    }

    @Test
    fun `a user without a display name or rank keeps those null`() {
        val prefs = FakeSharedPreferences()
        EncryptedCredentialStore(prefs).user = SignedInUser("42", "demo", null, null)

        val read = EncryptedCredentialStore(prefs).user
        assertEquals(SignedInUser("42", "demo", null, null), read)
    }

    @Test
    fun `half-written credentials read back as nothing, not as a blank password`() {
        // However this happens — an interrupted write, a partially cleared file — the
        // result must not be a Credentials the app would try to sign in with.
        val onlyUsername = EncryptedCredentialStore(FakeSharedPreferences(mapOf("username" to "demo")))
        assertNull(onlyUsername.credentials)

        val onlyPassword = EncryptedCredentialStore(FakeSharedPreferences(mapOf("password" to "hunter2")))
        assertNull(onlyPassword.credentials)
    }

    @Test
    fun `a user record without a handle reads back as nothing`() {
        val store = EncryptedCredentialStore(FakeSharedPreferences(mapOf("user_id" to "42", "user_rank" to "8k")))
        assertNull(store.user)
    }

    @Test
    fun `clearing one field leaves the others alone`() {
        val prefs = FakeSharedPreferences()
        val store =
            EncryptedCredentialStore(prefs).apply {
                credentials = Credentials("demo", "hunter2")
                session = "sessioncode=abc"
                user = SignedInUser("42", "demo", "Demo User", "8k")
            }

        // The session dies but the password is still good: this is the re-login path.
        store.session = null

        assertNull(store.session)
        assertEquals(Credentials("demo", "hunter2"), store.credentials)
        assertEquals("demo", store.user?.handle)
    }

    @Test
    fun `clearing the credentials does not strand the username`() {
        val prefs = FakeSharedPreferences()
        val store = EncryptedCredentialStore(prefs).apply { credentials = Credentials("demo", "hunter2") }

        store.credentials = null

        assertNull(store.credentials)
        assertFalse(prefs.contains("username"))
        assertFalse(prefs.contains("password"))
    }

    @Test
    fun `forgetting the cached user keeps the stored password usable`() {
        val prefs = FakeSharedPreferences()
        val store =
            EncryptedCredentialStore(prefs).apply {
                credentials = Credentials("demo", "hunter2")
                user = SignedInUser("42", "demo", "Demo User", "8k")
            }

        store.user = null

        assertNull(store.user)
        assertFalse(prefs.contains("user_id"))
        assertFalse(prefs.contains("user_handle"))
        assertFalse(prefs.contains("user_name"))
        assertFalse(prefs.contains("user_rank"))
        assertEquals(Credentials("demo", "hunter2"), store.credentials)
    }

    @Test
    fun `signing out leaves no trace of the account`() {
        val prefs = FakeSharedPreferences()
        val store =
            EncryptedCredentialStore(prefs).apply {
                credentials = Credentials("demo", "hunter2")
                session = "sessioncode=abc"
                user = SignedInUser("42", "demo", "Demo User", "8k")
            }

        store.clear()

        assertNull(store.credentials)
        assertNull(store.session)
        assertNull(store.user)
        assertTrue(prefs.getAll().isEmpty(), "a cleared store must not keep stale keys: ${prefs.getAll().keys}")
    }
}
