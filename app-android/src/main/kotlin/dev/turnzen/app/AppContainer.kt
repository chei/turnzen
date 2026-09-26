package dev.turnzen.app

import android.content.Context
import android.os.Build
import dev.turnzen.app.data.Accounts
import dev.turnzen.app.data.EncryptedCredentialStore
import dev.turnzen.app.data.FixedServer
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.LadderStore
import dev.turnzen.app.data.SessionManager
import dev.turnzen.app.data.SettingsStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.app.signin.ServiceKeepAwake
import dev.turnzen.dgs.DgsClient
import dev.turnzen.dgs.http.OkHttpTransport
import dev.turnzen.dgs.http.TurnzenUserAgent
import dev.turnzen.goserver.TurnBasedGoServerClient
import dev.turnzen.ogs.OgsClient
import dev.turnzen.ogs.net.OkHttpOgs

/**
 * Composition root. The only place in the UI module that knows the concrete server
 * clients; everything else sees [TurnBasedGoServerClient].
 *
 * (Backend analogy: this is the hand-written equivalent of a Spring context. A DI
 * framework would be overkill for a handful of objects.)
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext

    val settings = SettingsStore(appContext)

    // Named traffic: the version is the one this APK was built with, the API level the one
    // the phone is running. The same agent for every server.
    private val userAgent =
        TurnzenUserAgent.format(
            versionName = BuildConfig.VERSION_NAME,
            androidSdkInt = Build.VERSION.SDK_INT,
            sourceUrl = BuildConfig.SOURCE_URL,
        )
    private val dgsTransport = OkHttpTransport(userAgent)
    private val ogsTransport = OkHttpOgs(userAgent)

    /**
     * Where the "Back to Turnzen" link on the page after an OGS sign-in leads: the app's own
     * `turnzen://signed-in` (see the manifest), as an Android intent URL naming the package so
     * no other app can answer it. A launcher-style MAIN intent does not work here: browsers
     * only open activities that declare BROWSABLE, and fall back to the Play Store otherwise.
     */
    private val returnLink = "intent://signed-in#Intent;scheme=turnzen;package=${BuildConfig.APPLICATION_ID};end"

    private fun dgsClient(serverUrl: String): TurnBasedGoServerClient =
        if (BuildConfig.USE_FAKE_SERVER) {
            FakeTurnBasedGoServerClient(
                serverId = DgsClient.SERVER_ID,
                serverName = "Dragon Go Server",
                shareGames = true,
            )
        } else {
            DgsClient(dgsTransport, serverUrl)
        }

    private fun ogsClient(): TurnBasedGoServerClient =
        if (BuildConfig.USE_FAKE_SERVER) {
            FakeTurnBasedGoServerClient(
                serverId = OgsClient.SERVER_ID,
                serverName = "Online Go Server",
                browserSignIn = true,
                shareGames = true,
            )
        } else {
            OgsClient(
                http = ogsTransport,
                sockets = ogsTransport,
                clientId = BuildConfig.OGS_CLIENT_ID,
                returnLink = returnLink,
                clientVersion = BuildConfig.VERSION_NAME,
            )
        }

    /** The DGS session keeps the original secrets file, so an existing sign-in survives the update. */
    val dgs = SessionManager(EncryptedCredentialStore(appContext), settings, ::dgsClient)
    val ogs =
        SessionManager(
            EncryptedCredentialStore(appContext, "turnzen_secrets_ogs"),
            FixedServer(OgsClient.DEFAULT_BASE_URL),
        ) { ogsClient() }

    val accounts = Accounts(listOf(ogs, dgs))
    val games = GamesStore(accounts)

    // Ladder standings are a DGS feature.
    val ladders = LadderStore(appContext, dgs)

    /** Keeps the app out of Android's freezer while an OGS sign-in waits for the browser. */
    val keepAwake = ServiceKeepAwake(appContext)

    /** True when this build can sign in to OGS at all: it needs the registered client id. */
    val ogsAvailable: Boolean
        get() = BuildConfig.USE_FAKE_SERVER || BuildConfig.OGS_CLIENT_ID.isNotEmpty()
}
