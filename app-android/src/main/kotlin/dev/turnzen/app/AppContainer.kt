package dev.turnzen.app

import android.content.Context
import dev.turnzen.app.data.EncryptedCredentialStore
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.LadderStore
import dev.turnzen.app.data.SessionManager
import dev.turnzen.app.data.SettingsStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.dgs.DgsClient
import dev.turnzen.dgs.http.OkHttpTransport
import dev.turnzen.goserver.TurnBasedGoServerClient

/**
 * Composition root. The only place in the UI module that knows the concrete server
 * client; everything else sees [TurnBasedGoServerClient].
 *
 * (Backend analogy: this is the hand-written equivalent of a Spring context. A DI
 * framework would be overkill for four objects.)
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext

    val settings = SettingsStore(appContext)
    val credentials = EncryptedCredentialStore(appContext)

    private val transport = OkHttpTransport()

    private fun createClient(serverUrl: String): TurnBasedGoServerClient =
        if (BuildConfig.USE_FAKE_SERVER) {
            FakeTurnBasedGoServerClient()
        } else {
            DgsClient(transport, serverUrl, userAgent = "Turnzen/${BuildConfig.VERSION_NAME}")
        }

    val session = SessionManager(credentials, settings, ::createClient)
    val games = GamesStore(session)
    val ladders = LadderStore(appContext, session)
}
