package dev.turnzen.app

import dev.turnzen.app.data.CredentialStore
import dev.turnzen.app.data.ServerSelection
import dev.turnzen.app.data.SessionManager
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
