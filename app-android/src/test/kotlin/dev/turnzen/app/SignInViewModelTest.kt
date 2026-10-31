package dev.turnzen.app

import dev.turnzen.app.data.AuthState
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.app.ui.signin.SignInViewModel
import dev.turnzen.goserver.CredentialProblem
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.SignedInUser
import dev.turnzen.goserver.TurnBasedGoServerClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest : MainDispatcherTest() {
    /** Records what was sent, and fails the sign-in with [failure] when one is set. */
    private class RecordingClient(
        private val fake: FakeTurnBasedGoServerClient = FakeTurnBasedGoServerClient(latencyMillis = 0),
        var failure: GoServerException? = null,
    ) : TurnBasedGoServerClient by fake {
        var attempts = 0
        var lastCredentials: Credentials? = null

        override suspend fun signIn(credentials: Credentials): SignedInUser {
            attempts++
            lastCredentials = credentials
            failure?.let { throw it }
            return fake.signIn(credentials)
        }
    }

    /** Fills the form in, submits, and returns the message the user is left looking at. */
    private fun TestScope.messageAfterFailing(
        failure: GoServerException,
        username: String = "demo",
    ): String {
        val vm = SignInViewModel(sessionFor(RecordingClient(failure = failure)))
        vm.onUsername(username)
        vm.onPassword("hunter2")
        vm.submit()
        advanceUntilIdle()
        return assertNotNull(vm.state.value.error, "a failed sign-in must say something")
    }

    @Test
    fun `the button stays disabled until both fields are filled`() =
        runTest(dispatcher) {
            val vm = SignInViewModel(sessionFor(RecordingClient()))
            assertFalse(vm.state.value.canSubmit)

            vm.onUsername("demo")
            assertFalse(vm.state.value.canSubmit, "a username alone is not enough")

            vm.onPassword("hunter2")
            assertTrue(vm.state.value.canSubmit)

            // Whitespace is not a username.
            vm.onUsername("   ")
            assertFalse(vm.state.value.canSubmit)
        }

    @Test
    fun `submitting an incomplete form does not reach the server`() =
        runTest(dispatcher) {
            val client = RecordingClient()
            val vm = SignInViewModel(sessionFor(client))
            vm.onUsername("demo")

            vm.submit()
            advanceUntilIdle()

            assertEquals(0, client.attempts)
        }

    @Test
    fun `a second tap while the first is in flight does not sign in twice`() =
        runTest(dispatcher) {
            val client = RecordingClient()
            val vm = SignInViewModel(sessionFor(client))
            vm.onUsername("demo")
            vm.onPassword("hunter2")

            vm.submit()
            assertTrue(vm.state.value.loading)
            assertFalse(vm.state.value.canSubmit, "the form is locked while a sign-in runs")
            vm.submit()
            advanceUntilIdle()

            assertEquals(1, client.attempts)
        }

    @Test
    fun `after a sign-in the form is ready again for when the server is signed out`() =
        runTest(dispatcher) {
            val vm = SignInViewModel(sessionFor(RecordingClient()))
            vm.onUsername("demo")
            vm.onPassword("hunter2")
            vm.submit()
            advanceUntilIdle()

            assertFalse(vm.state.value.loading, "a form left saying \"Signing in…\" reappears on sign-out")
            assertEquals("", vm.state.value.password)
            assertEquals("demo", vm.state.value.username)
        }

    @Test
    fun `a successful sign-in signs the session in, with the username trimmed`() =
        runTest(dispatcher) {
            val client = RecordingClient()
            val session = sessionFor(client)
            val vm = SignInViewModel(session)
            // Autocomplete and keyboards like to add a trailing space.
            vm.onUsername("  demo  ")
            vm.onPassword("hunter2")

            vm.submit()
            advanceUntilIdle()

            assertEquals(Credentials("demo", "hunter2"), client.lastCredentials)
            assertIs<AuthState.SignedIn>(session.state.value)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `the password is sent exactly as typed`() =
        runTest(dispatcher) {
            val client = RecordingClient()
            val vm = SignInViewModel(sessionFor(client))
            vm.onUsername("demo")
            // Leading and trailing spaces are legitimate characters in a password.
            vm.onPassword("  pass word  ")

            vm.submit()
            advanceUntilIdle()

            assertEquals("  pass word  ", client.lastCredentials?.password)
        }

    @Test
    fun `an unknown username is named, so the user checks the spelling`() =
        runTest(dispatcher) {
            val message =
                messageAfterFailing(
                    GoServerException.InvalidCredentials(CredentialProblem.UNKNOWN_USER),
                    username = "  demo  ",
                )

            assertTrue(message.contains("\"demo\""), message)
            assertTrue(message.contains("Demo server"), message)
        }

    @Test
    fun `a wrong password does not suggest the account is missing`() =
        runTest(dispatcher) {
            val message =
                messageAfterFailing(GoServerException.InvalidCredentials(CredentialProblem.WRONG_PASSWORD))

            assertTrue(message.contains("password"), message)
            assertFalse(message.contains("no account"), message)
        }

    @Test
    fun `an unactivated account is told to look at its email`() =
        runTest(dispatcher) {
            val message =
                messageAfterFailing(GoServerException.InvalidCredentials(CredentialProblem.NOT_ACTIVATED))

            assertTrue(message.contains("activated"), message)
        }

    @Test
    fun `a server that will not say why falls back to the vague message`() =
        runTest(dispatcher) {
            val message =
                messageAfterFailing(GoServerException.InvalidCredentials(CredentialProblem.UNSPECIFIED))

            assertEquals("Wrong username or password.", message)
        }

    @Test
    fun `being offline is not reported as a rejected password`() =
        runTest(dispatcher) {
            val message = messageAfterFailing(GoServerException.Unreachable("no route to host"))

            assertTrue(message.contains("Demo server"), message)
            assertFalse(message.contains("password", ignoreCase = true), message)
            // The raw network text is a diagnostic, not something to show the user.
            assertFalse(message.contains("no route to host"), message)
        }

    @Test
    fun `rate limiting asks the user to wait rather than to retype`() =
        runTest(dispatcher) {
            val message = messageAfterFailing(GoServerException.RateLimited("429"))

            assertTrue(message.contains("Demo server"), message)
            assertFalse(message.contains("password", ignoreCase = true), message)
        }

    @Test
    fun `a blocked account is not blamed on the password`() =
        runTest(dispatcher) {
            val message = messageAfterFailing(GoServerException.InvalidCredentials(CredentialProblem.BLOCKED))

            assertTrue(message.contains("Demo server"), message)
            assertFalse(message.contains("Wrong"), message)
        }

    @Test
    fun `a response the client cannot parse is reported as the server's fault`() =
        runTest(dispatcher) {
            val message = messageAfterFailing(GoServerException.Protocol("no user id in the reply"))

            assertTrue(message.contains("Demo server"), message)
            // Here the detail is deliberate: it is the only clue for a bug report.
            assertTrue(message.contains("no user id in the reply"), message)
        }

    @Test
    fun `a failure nobody anticipated still says something`() =
        runTest(dispatcher) {
            val message = messageAfterFailing(GoServerException.ServerError("500", "database is down"))

            assertEquals("Sign-in failed: database is down", message)
        }

    @Test
    fun `a failed sign-in unlocks the form so it can be retried`() =
        runTest(dispatcher) {
            val client =
                RecordingClient(failure = GoServerException.InvalidCredentials(CredentialProblem.WRONG_PASSWORD))
            val vm = SignInViewModel(sessionFor(client))
            vm.onUsername("demo")
            vm.onPassword("wrong")

            vm.submit()
            advanceUntilIdle()

            assertFalse(vm.state.value.loading)
            assertTrue(vm.state.value.canSubmit)

            client.failure = null
            vm.onPassword("hunter2")
            vm.submit()
            advanceUntilIdle()

            assertEquals(2, client.attempts)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `editing either field clears the stale error`() =
        runTest(dispatcher) {
            val client =
                RecordingClient(failure = GoServerException.InvalidCredentials(CredentialProblem.WRONG_PASSWORD))
            val vm = SignInViewModel(sessionFor(client))
            vm.onUsername("demo")
            vm.onPassword("wrong")
            vm.submit()
            advanceUntilIdle()
            assertNotNull(vm.state.value.error)

            vm.onPassword("hunter")
            assertNull(vm.state.value.error, "the message is about a password that is no longer in the field")

            vm.submit()
            advanceUntilIdle()
            assertNotNull(vm.state.value.error)

            vm.onUsername("demo2")
            assertNull(vm.state.value.error)
        }

    @Test
    fun `the reason the session dropped is shown when the screen opens`() =
        runTest(dispatcher) {
            val vm =
                SignInViewModel(sessionFor(RecordingClient()), initialError = "Your password seems to have changed.")

            assertEquals("Your password seems to have changed.", vm.state.value.error)
            assertFalse(vm.state.value.loading)
        }
}
