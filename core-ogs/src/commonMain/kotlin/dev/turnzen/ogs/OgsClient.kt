package dev.turnzen.ogs

import dev.turnzen.game.Move
import dev.turnzen.game.Point
import dev.turnzen.game.sgf.SgfCoordinates
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameComment
import dev.turnzen.goserver.GameDetail
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.ScoreState
import dev.turnzen.goserver.ServerId
import dev.turnzen.goserver.SignInMethod
import dev.turnzen.goserver.SignedInUser
import dev.turnzen.goserver.TurnBasedGoServerClient
import dev.turnzen.ogs.net.OgsHttp
import dev.turnzen.ogs.net.OgsMethod
import dev.turnzen.ogs.net.OgsRequest
import dev.turnzen.ogs.net.OgsResponse
import dev.turnzen.ogs.net.OgsSocketConnector
import dev.turnzen.ogs.net.OgsTransportException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Online Go Server client, correspondence games only. All OGS specifics live here: the REST
 * API for reading, OAuth for signing in, and a short realtime connection for the actions
 * OGS offers nowhere else.
 *
 * Signs in in the browser ([SignInMethod.BROWSER]): OAuth 2 with PKCE as a public client —
 * [clientId] is public, there is no secret — and a loopback redirect on [redirectPort], so
 * Turnzen never sees an OGS password. See the brief, "Online Go Server (OGS) support".
 */
class OgsClient(
    private val http: OgsHttp,
    sockets: OgsSocketConnector,
    private val clientId: String,
    /** Where the browser is redirected once the sign-in is done; null to show a page instead. */
    private val returnLink: String? = null,
    clientVersion: String = "dev",
    private val redirectPort: Int = DEFAULT_REDIRECT_PORT,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val now: () -> Instant = { Clock.System.now() },
) : TurnBasedGoServerClient {
    companion object {
        const val DEFAULT_BASE_URL = "https://online-go.com"

        /**
         * The loopback port OGS redirects to after sign-in. Fixed, because the redirect URI is
         * registered with the OAuth application and must match it exactly.
         */
        const val DEFAULT_REDIRECT_PORT = 8735
        const val REDIRECT_PATH = "/ogs/signed-in"

        /** OGS's [ServerId], carried by every [GameId] this client hands out. */
        val SERVER_ID = ServerId("ogs")

        /** Renew a token this long before it runs out, rather than fail a call on it. */
        private const val RENEW_MARGIN_MILLIS = 5 * 60 * 1000L

        /** The overview answers two lists at once; the games screen asks for both together. */
        private const val OVERVIEW_REUSE_MILLIS = 5_000L

        /** Finished games are filtered to correspondence, so ask for more than will be shown. */
        private const val FINISHED_PAGE = 50

        /** Running games read for their opponents' icons; more than anyone plays at once. */
        private const val ICON_PAGE = 100

        /** The longest a chat history replay is waited for; it normally ends well within a second. */
        private const val CHAT_REPLAY_MILLIS = 8_000L

        /** How long a comment sent after a move is given to come back from the server. */
        private const val CHAT_ECHO_MILLIS = 10_000L
    }

    @Serializable
    private data class Tokens(
        val access: String,
        val refresh: String,
        /** Epoch milliseconds. */
        val expiresAt: Long,
        val userId: Long? = null,
    )

    private val realtime = OgsRealtime(sockets, baseUrl.replaceFirst("http", "ws"), clientVersion)
    private var tokens: Tokens? = null
    private val renewLock = Mutex()
    private val overviewLock = Mutex()

    /** Opponents' icons found by [withIcons], by player id; a null value means they have none. */
    private val knownIcons = mutableMapOf<Long, String?>()
    private val iconLock = Mutex()
    private var overview: Pair<Long, JsonObject>? = null

    override val serverId: ServerId = SERVER_ID
    override val serverName: String = "Online Go Server"
    override val registrationUrl: String = "$baseUrl/register"
    override val signInMethod: SignInMethod = SignInMethod.BROWSER

    /**
     * The marks of each game in the scoring phase as [scoreState] last read them: what the user
     * was shown. Sending or accepting marks refuses to act on marks that have changed since, as
     * a move refuses to act on a position that has.
     */
    private val shownMarks = HashMap<String, String>()

    override suspend fun signIn(credentials: Credentials): SignedInUser =
        throw UnsupportedOperationException("$serverName signs in in the browser")

    override suspend fun signInInBrowser(openPage: (url: String) -> Unit): SignedInUser {
        val pkce = Pkce.create()
        val loopback = redirectReceiver(redirectPort, REDIRECT_PATH, returnLink)
        val authorize =
            "$baseUrl/oauth2/authorize/?" +
                listOf(
                    "response_type" to "code",
                    "client_id" to clientId,
                    "redirect_uri" to loopback.redirectUri,
                    "scope" to "read write",
                    "state" to pkce.state,
                    "code_challenge" to pkce.challenge,
                    "code_challenge_method" to "S256",
                ).joinToString("&") { (k, v) -> "$k=${OgsUrls.encode(v)}" }
        val redirect =
            try {
                loopback.await { openPage(authorize) }
            } catch (e: OgsTransportException) {
                throw GoServerException.Unreachable("Couldn't wait for $serverName's answer: ${e.message}", e)
            }
        val code =
            when (redirect) {
                is Redirect.Denied -> {
                    throw GoServerException.InvalidCredentials(
                        message = "$serverName did not sign you in (${redirect.error})",
                    )
                }

                is Redirect.Code -> {
                    if (redirect.state != pkce.state) {
                        throw GoServerException.Protocol("$serverName answered a different sign-in")
                    }
                    redirect.code
                }
            }
        tokens =
            tokenRequest(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to loopback.redirectUri,
                    "client_id" to clientId,
                    "code_verifier" to pkce.verifier,
                ),
            ) ?: throw GoServerException.InvalidCredentials(message = "$serverName did not accept the sign-in")
        return currentUser()
    }

    override suspend fun signOut() {
        val held = tokens
        tokens = null
        overview = null
        if (held != null) {
            // Best effort: an unrevoked token simply expires on its own.
            runCatching {
                http.execute(
                    OgsRequest(
                        OgsMethod.POST,
                        "$baseUrl/oauth2/revoke_token/",
                        form = mapOf("token" to held.access, "client_id" to clientId),
                    ),
                )
            }
        }
    }

    override suspend fun currentUser(): SignedInUser {
        val config = api("/api/v1/ui/config/")
        val user = OgsUser.parse(config.obj("user"))
        tokens = tokens?.copy(userId = user.id.toLong())
        return if (user.iconUrl != null) user else user.copy(iconUrl = ownIcon(user.id.toLong()))
    }

    /**
     * The user's icon when the config left it out: from their own game list, whose player
     * objects carry icons (see [withIcons]); one game names them. Best effort — the account
     * panel shows the generated initial instead.
     */
    private suspend fun ownIcon(me: Long): String? {
        iconLock.withLock { knownIcons[me] }?.let { return it }
        return try {
            val page = api("/api/v1/players/$me/games/?page_size=1")
            iconLock.withLock {
                rememberIcons(page.arr("results").orEmpty().mapNotNull { it as? JsonObject })
                knownIcons[me]
            }
        } catch (e: GoServerException) {
            null
        }
    }

    override suspend fun gamesAwaitingMe(): List<GameSummary> = myRunningGames().filter { it.isMyTurn }

    override suspend fun myRunningGames(): List<GameSummary> {
        val me = myId()
        val games = overview().arr("active_games") ?: return emptyList()
        val running =
            games.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val gamedata = entry.obj("json") ?: entry.obj("gamedata") ?: return@mapNotNull null
                if (!OgsGameData.isCorrespondence(gamedata.obj("time_control"))) return@mapNotNull null
                OgsGameData.summary(gamedata, entry.obj("black"), entry.obj("white"), me, now()) to
                    OgsGameData.opponentId(gamedata, me)
            }
        return withIcons(me, running)
    }

    /**
     * Fills in the opponents' icons the overview leaves out: its player objects come without
     * one. The player's own game list carries them (the Finished tab is built from it), so
     * when an opponent's icon is not known yet, one page of the user's running games is read
     * from there, and every icon on it is remembered for as long as this client lives. An
     * opponent that page does not name is remembered as having none, so a refresh costs a
     * request only when a new opponent turns up. Best effort: a failed read leaves the rows
     * without pictures and is tried again on the next refresh.
     */
    private suspend fun withIcons(
        me: Long,
        games: List<Pair<GameSummary, Long?>>,
    ): List<GameSummary> {
        val missing = games.filter { (summary, _) -> summary.opponent.iconUrl == null }.mapNotNull { it.second }
        val icons =
            iconLock.withLock {
                val unknown = missing.filter { it !in knownIcons }
                if (unknown.isNotEmpty()) {
                    try {
                        val page = api("/api/v1/players/$me/games/?ended__isnull=true&page_size=$ICON_PAGE")
                        rememberIcons(page.arr("results").orEmpty().mapNotNull { it as? JsonObject })
                        unknown.forEach { if (it !in knownIcons) knownIcons[it] = null }
                    } catch (e: GoServerException) {
                        // Pictures are decoration; the list itself has already loaded.
                    }
                }
                knownIcons.toMap()
            }
        return games.map { (summary, opponentId) ->
            val icon = summary.opponent.iconUrl ?: opponentId?.let { icons[it] } ?: return@map summary
            summary.copy(opponent = summary.opponent.copy(iconUrl = icon))
        }
    }

    /** Keeps the icon of every player in [entries], game-list entries with a `players` object. */
    private fun rememberIcons(entries: List<JsonObject>) {
        for (entry in entries) {
            val players = entry.obj("players") ?: continue
            for (player in listOfNotNull(players.obj("black"), players.obj("white"))) {
                val id = player.long("id") ?: continue
                OgsIcons.url(player)?.let { knownIcons[id] = it }
            }
        }
    }

    override suspend fun myFinishedGames(limit: Int): List<GameSummary> {
        val me = myId()
        val page =
            api(
                "/api/v1/players/$me/games/?ended__isnull=false&ordering=-ended&page_size=$FINISHED_PAGE",
            )
        val entries =
            page
                .arr("results")
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { OgsFinishedGames.isCorrespondence(it) }
                .take(limit)
        // Free icons for the running lists: an opponent met again is already known.
        iconLock.withLock { rememberIcons(entries) }
        val changes = ratingChanges(me)
        return entries.mapNotNull { entry ->
            val id = entry.long("id")?.toString()
            OgsFinishedGames.summary(entry, me, changes[id])
        }
    }

    override suspend fun game(id: GameId): GameDetail {
        val me = myId()
        val game = api("/api/v1/games/${id.value}")
        val gamedata =
            game.obj("gamedata") ?: throw GoServerException.Protocol("OGS game ${id.value} without game data")
        val players = game.obj("players")
        val black = players?.obj("black")
        val white = players?.obj("white")
        val summary =
            OgsGameData.summary(gamedata, black, white, me, now()).let { summary ->
                // The game endpoint states the outcome plainly; game data only names a winner.
                if (summary.isFinished) {
                    summary.copy(
                        result =
                            OgsOutcome.result(
                                game.str("outcome"),
                                game.bool("black_lost"),
                                game.bool("white_lost"),
                            ) ?: summary.result,
                        lastMoveAt =
                            game.str("ended")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                                ?: summary.lastMoveAt,
                    )
                } else {
                    summary
                }
            }
        val record = OgsGameData.record(gamedata, black?.str("username"), white?.str("username"))
        val info = OgsGameData.info(gamedata, black, white, now())
        return GameDetail(summary, record, info)
    }

    override suspend fun sendMove(
        id: GameId,
        token: MoveToken,
        move: Move,
        comment: String?,
    ) {
        val encoded =
            when (move) {
                Move.Pass -> ".."
                is Move.Play -> SgfCoordinates.encode(move.point)
            }
        val message = comment?.trim().orEmpty()
        val me = if (message.isEmpty()) null else myId()
        // Chat on only when there is something to say: it is what lets the comment's own
        // echo be seen, and otherwise just brings the whole history along for nothing.
        inGame(id, token, chat = me != null) { gamedata ->
            send(
                "game/move",
                buildJsonObject {
                    put("game_id", JsonPrimitive(gameId))
                    put("move", JsonPrimitive(encoded))
                },
            )
            awaitEvent("move")
            if (me != null) {
                val played = (gamedata.arr("moves")?.size ?: token.value.toIntOrNull() ?: 0) + 1
                say(message, played, me)
            }
        }
        overview = null
    }

    /**
     * Posts [message] to the game chat, pinned to [moveNumber], and waits for it to come back.
     * OGS has no message that belongs to a move, so this is the nearest thing: the players'
     * chat line, written at that move.
     *
     * A comment is never worth failing the move or resignation it goes with, so nothing here
     * throws. A chat line that does not come back in time is left to the server; the socket
     * carried it either way.
     */
    private suspend fun OgsRealtime.GameConnection.say(
        message: String,
        moveNumber: Int,
        me: Long,
    ) {
        send(
            "game/chat",
            buildJsonObject {
                put("game_id", JsonPrimitive(gameId))
                put("type", JsonPrimitive("main"))
                put("move_number", JsonPrimitive(moveNumber))
                put("body", JsonPrimitive(message))
            },
        )
        try {
            withTimeoutOrNull(CHAT_ECHO_MILLIS) {
                while (true) {
                    val line = (awaitEvent("chat") as? JsonObject)?.obj("line") ?: continue
                    if (line.long("player_id") == me && line.str("body") == message) break
                }
            }
        } catch (e: GoServerException) {
            // Best effort, as above.
        }
    }

    /**
     * The game chat, read by joining the game with chat on and letting the server replay its
     * history. OGS has no REST call for one game's chat, so this costs a realtime connection
     * of a second or two — which is why [game] leaves it out and the caller asks separately.
     */
    override suspend fun comments(id: GameId): List<GameComment> =
        inGame(id, token = null, chat = true) { gamedata ->
            val events = settleChat(CHAT_REPLAY_MILLIS)
            OgsChat.comments(events, gamedata, moveCount = gamedata.arr("moves")?.size ?: 0)
        }

    override suspend fun resign(
        id: GameId,
        token: MoveToken,
        comment: String?,
    ) {
        val message = comment?.trim().orEmpty()
        val me = if (message.isEmpty()) null else myId()
        inGame(id, token, chat = me != null) { gamedata ->
            // Said first, at the position being given up: once the game has finished, the
            // players' channel is no longer the one the opponent is reading.
            if (me != null) {
                val moves = gamedata.arr("moves")?.size ?: token.value.toIntOrNull() ?: 0
                say(message, moves, me)
            }
            send("game/resign", buildJsonObject { put("game_id", JsonPrimitive(gameId)) })
            while (true) {
                val (name, data) = awaitAnyEvent("phase", "gamedata")
                val phase =
                    if (name ==
                        "phase"
                    ) {
                        (data as? JsonPrimitive)?.content
                    } else {
                        (data as? JsonObject)?.str("phase")
                    }
                if (phase == "finished") break
            }
        }
        overview = null
    }

    /**
     * The marks as they stand, read over the realtime connection: OGS keeps the stone removal
     * phase there. The count is this device's; OGS counts only once both players have accepted.
     */
    override suspend fun scoreState(
        id: GameId,
        token: MoveToken,
        toggle: List<Point>,
    ): ScoreState =
        inGame(id, token) { gamedata ->
            val marks = OgsStoneRemoval.read(gamedata)
            shownMarks[id.value] = marks.encoded
            OgsStoneRemoval.state(marks, toggle)
        }

    /**
     * Marks differently, and accepts the new marks in the same go: on OGS both players mark
     * the one shared set and the game ends when both have accepted it, so proposing marks one
     * would not accept means nothing. The opponent sees them and accepts, or marks again.
     *
     * The taps are replayed here, on the marks the user was shown, and the server is told the
     * stones that changed, which it sets as given.
     */
    override suspend fun disputeScore(
        id: GameId,
        token: MoveToken,
        toggle: List<Point>,
    ) {
        val me = myId()
        inGame(id, token) { gamedata ->
            val marks = shownMarks(id, gamedata)
            val dead = OgsStoneRemoval.state(marks, toggle).deadStones
            val wanted = marks.marked - (marks.dead - dead) + (dead - marks.dead)
            var held = marks.marked
            for ((removed, stones) in listOf(false to marks.dead - dead, true to dead - marks.dead)) {
                if (stones.isEmpty()) continue
                send(
                    "game/removed_stones/set",
                    buildJsonObject {
                        put("game_id", JsonPrimitive(gameId))
                        put("removed", JsonPrimitive(removed))
                        put("stones", JsonPrimitive(OgsStoneRemoval.encode(stones)))
                    },
                )
                val echo = awaitEvent("removed_stones") as? JsonObject
                val all = echo?.get("all_removed")
                held =
                    when {
                        all != null -> OgsStoneRemoval.points(all, marks.position.board.size)
                        removed -> held + stones
                        else -> held - stones
                    }
            }
            // Never accept marks the user did not see: if the server holds something else (the
            // opponent marked at the same moment), leave the acceptance for a fresh look.
            if (held != wanted) throw GoServerException.StaleGame()
            acceptMarks(OgsStoneRemoval.encode(wanted), me)
        }
        shownMarks.remove(id.value)
        overview = null
    }

    /** Accepts the marks as the user was shown them. Once both players have, the game is over. */
    override suspend fun acceptScore(
        id: GameId,
        token: MoveToken,
    ) {
        val me = myId()
        inGame(id, token) { gamedata ->
            acceptMarks(shownMarks(id, gamedata).encoded, me)
        }
        shownMarks.remove(id.value)
        overview = null
    }

    /** The marks [gamedata] holds, provided they are the ones [scoreState] showed the user. */
    private fun shownMarks(
        id: GameId,
        gamedata: JsonObject,
    ): OgsStoneRemoval.Marks {
        val marks = OgsStoneRemoval.read(gamedata)
        if (shownMarks[id.value] != marks.encoded) throw GoServerException.StaleGame()
        return marks
    }

    /** Accepts [stones] and waits for the server to record it, or for the game to end on it. */
    private suspend fun OgsRealtime.GameConnection.acceptMarks(
        stones: String,
        me: Long,
    ) {
        send(
            "game/removed_stones/accept",
            buildJsonObject {
                put("game_id", JsonPrimitive(gameId))
                put("stones", JsonPrimitive(stones))
                // Japanese strict seki mode, which the official client no longer offers.
                put("strict_seki_mode", JsonPrimitive(false))
            },
        )
        while (true) {
            val (name, data) = awaitAnyEvent("removed_stones_accepted", "phase", "gamedata")
            val done =
                when (name) {
                    "removed_stones_accepted" -> {
                        val event = data as? JsonObject
                        event?.long("player_id") == me || event?.str("phase") == "finished"
                    }

                    "phase" -> {
                        (data as? JsonPrimitive)?.content == "finished"
                    }

                    else -> {
                        (data as? JsonObject)?.str("phase") == "finished"
                    }
                }
            if (done) break
        }
    }

    override fun gameUrl(id: GameId): String = "$baseUrl/game/${id.value}"

    override fun accountUrl(user: SignedInUser): String = "$baseUrl/user/view/${user.id}"

    override fun exportSession(): String? = tokens?.let { OgsJson.json.encodeToString(Tokens.serializer(), it) }

    override fun restoreSession(session: String) {
        tokens =
            try {
                OgsJson.json.decodeFromString(Tokens.serializer(), session)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
    }

    /**
     * Joins the game on the realtime connection, and refuses to act when the game is no
     * longer the one the user decided on: [token] is the move count they saw. Reading needs
     * no such check, and passes no token.
     */
    private suspend fun <T> inGame(
        id: GameId,
        token: MoveToken?,
        chat: Boolean = false,
        action: suspend OgsRealtime.GameConnection.(gamedata: JsonObject) -> T,
    ): T {
        val jwt =
            api("/api/v1/ui/config/").str("user_jwt") ?: throw GoServerException.Protocol("OGS sent no realtime token")
        val gameId = id.value.toLongOrNull() ?: throw GoServerException.Protocol("Not an OGS game id: ${id.value}")
        return realtime.inGame(jwt, gameId, chat) { gamedata ->
            val moves = gamedata.arr("moves")?.size
            if (token != null && moves != null && token.value.isNotEmpty() && moves.toString() != token.value) {
                throw GoServerException.StaleGame()
            }
            action(gamedata)
        }
    }

    private suspend fun myId(): Long {
        tokens?.userId?.let { return it }
        return currentUser().id.toLong()
    }

    private suspend fun overview(): JsonObject =
        overviewLock.withLock {
            val held = overview
            val at = now().toEpochMilliseconds()
            if (held != null && at - held.first < OVERVIEW_REUSE_MILLIS) return held.second
            api("/api/v1/ui/overview/").also { overview = at to it }
        }

    /** Best effort: the history is undocumented, so any trouble with it just leaves the numbers out. */
    private suspend fun ratingChanges(me: Long): Map<String, Double> =
        try {
            val response =
                authorized(
                    OgsRequest(
                        OgsMethod.GET,
                        "$baseUrl/termination-api/player/$me/v5-rating-history?speed=overall&size=0",
                    ),
                )
            if (response.status == 200) OgsRatingHistory.changes(response.body) else emptyMap()
        } catch (e: GoServerException) {
            emptyMap()
        }

    private suspend fun api(path: String): JsonObject {
        val response = authorized(OgsRequest(OgsMethod.GET, baseUrl + path))
        check(response)
        return OgsJson.parseObject(response.body, "an answer")
    }

    private fun check(response: OgsResponse) {
        when {
            response.status == 429 -> throw GoServerException.RateLimited("$serverName is limiting requests")

            response.status >= 500 -> throw GoServerException.Unavailable(
                "$serverName answered with HTTP ${response.status}",
            )

            response.status == 401 || response.status == 403 -> throw GoServerException.SessionExpired(
                "$serverName signed you out",
            )

            response.status >= 400 -> throw GoServerException.Protocol(
                "$serverName answered with HTTP ${response.status}",
            )
        }
    }

    /** Sends [request] with the access token, renewing it first when it is about to expire, or once after a 401. */
    private suspend fun authorized(request: OgsRequest): OgsResponse {
        val current = freshTokens()
        val response = send(request.withToken(current.access))
        if (response.status != 401) return response
        val renewed = renew(current) ?: throw GoServerException.SessionExpired("$serverName signed you out")
        return send(request.withToken(renewed.access))
    }

    private fun OgsRequest.withToken(access: String) = copy(headers = headers + ("Authorization" to "Bearer $access"))

    private suspend fun freshTokens(): Tokens {
        val held = tokens ?: throw GoServerException.SessionExpired("Not signed in to $serverName")
        if (held.expiresAt - now().toEpochMilliseconds() > RENEW_MARGIN_MILLIS) return held
        return renew(held) ?: throw GoServerException.SessionExpired("$serverName signed you out")
    }

    /** Swaps the refresh token for a new pair; null when OGS will not (it has expired, or was revoked). */
    private suspend fun renew(stale: Tokens): Tokens? =
        renewLock.withLock {
            // Someone else renewed while we waited: use theirs.
            tokens?.let { if (it.access != stale.access) return it }
            val renewed =
                tokenRequest(
                    mapOf(
                        "grant_type" to "refresh_token",
                        "refresh_token" to stale.refresh,
                        "client_id" to clientId,
                    ),
                )?.copy(userId = stale.userId)
            tokens = renewed
            renewed
        }

    /** One call to the token endpoint. Null when OGS refuses the grant. */
    private suspend fun tokenRequest(form: Map<String, String>): Tokens? {
        val response = send(OgsRequest(OgsMethod.POST, "$baseUrl/oauth2/token/", form = form))
        if (response.status == 400 || response.status == 401) return null
        check(response)
        val body = OgsJson.parseObject(response.body, "a token answer")
        val access = body.str("access_token") ?: throw GoServerException.Protocol("$serverName sent no access token")
        val refresh = body.str("refresh_token") ?: throw GoServerException.Protocol("$serverName sent no refresh token")
        val lifetime = body.long("expires_in") ?: 3600
        return Tokens(access, refresh, now().toEpochMilliseconds() + lifetime * 1000, userId = tokens?.userId)
    }

    private suspend fun send(request: OgsRequest): OgsResponse =
        try {
            http.execute(request.copy(headers = request.headers + ("Accept" to "application/json")))
        } catch (e: OgsTransportException) {
            throw GoServerException.Unreachable("Couldn't reach $serverName", e)
        }
}
