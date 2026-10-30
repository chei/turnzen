package dev.turnzen.app

import android.content.Context
import android.os.Build
import dev.turnzen.app.data.Accounts
import dev.turnzen.app.data.EncryptedCredentialStore
import dev.turnzen.app.data.FileRecordCache
import dev.turnzen.app.data.FixedServer
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.LadderStore
import dev.turnzen.app.data.MessagesStore
import dev.turnzen.app.data.MoveOutbox
import dev.turnzen.app.data.PrefsGamesCache
import dev.turnzen.app.data.SessionManager
import dev.turnzen.app.data.SettingsStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.app.notify.CheckSchedule
import dev.turnzen.app.notify.NotificationIcons
import dev.turnzen.app.notify.Notifier
import dev.turnzen.app.notify.OpenRequest
import dev.turnzen.app.notify.TurnCheck
import dev.turnzen.app.notify.TurnWatch
import dev.turnzen.app.notify.WatchStateStore
import dev.turnzen.app.signin.ServiceKeepAwake
import dev.turnzen.dgs.DgsClient
import dev.turnzen.dgs.http.OkHttpTransport
import dev.turnzen.dgs.http.TurnzenUserAgent
import dev.turnzen.goserver.TurnBasedGoServerClient
import dev.turnzen.ogs.OgsClient
import dev.turnzen.ogs.net.OkHttpOgs
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow

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
     * Where the browser is sent once an OGS sign-in has come back to the app: the app's own
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

    val notifier = Notifier(appContext)
    private val notificationIcons = NotificationIcons(appContext)
    val checkSchedule = CheckSchedule(appContext)
    private val watchState = WatchStateStore(appContext)

    val games =
        GamesStore(
            accounts,
            cache = PrefsGamesCache(appContext),
            records = FileRecordCache(appContext),
        ) { seen -> watchState.update { TurnWatch.seen(it, seen) } }

    /** The new messages on every server, for the block above "Your turn". */
    val messages = MessagesStore(accounts)

    /** Sent moves on their way to the server; app-wide, as the board they left is gone. */
    val outbox = MoveOutbox(accounts, games, MainScope())

    val turnCheck =
        TurnCheck(
            accounts = accounts,
            settings = { settings.settings.value.notifications },
            store = watchState,
            notifier = notifier,
            schedule = checkSchedule,
            inForeground = { inForeground },
            icon = { game -> if (settings.settings.value.playerIcons) notificationIcons.of(game) else null },
        )

    /** Whether the app is on screen; kept by [TurnzenApplication]. */
    @Volatile
    var inForeground = false

    /** The app has come to the foreground: its notifications have been seen. */
    fun onForeground() {
        inForeground = true
        notifier.cancelAll()
        watchState.update { TurnWatch.seen(it) }
    }

    /** Where a tapped notification asked to go; consumed by the screen that goes there. */
    val openRequests = MutableStateFlow<OpenRequest?>(null)

    // Ladder standings are a DGS feature.
    val ladders = LadderStore(appContext, dgs)

    /** Keeps the app out of Android's freezer while an OGS sign-in waits for the browser. */
    val keepAwake = ServiceKeepAwake(appContext)

    /** True when this build can sign in to OGS at all: it needs the registered client id. */
    val ogsAvailable: Boolean
        get() = BuildConfig.USE_FAKE_SERVER || BuildConfig.OGS_CLIENT_ID.isNotEmpty()
}
