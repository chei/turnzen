package dev.turnzen.dgs

import dev.turnzen.dgs.http.HttpMethod
import dev.turnzen.dgs.http.HttpRequest
import dev.turnzen.dgs.http.HttpResponse
import dev.turnzen.dgs.http.HttpTransport
import dev.turnzen.dgs.http.HttpTransportException
import dev.turnzen.game.Move
import dev.turnzen.game.Point
import dev.turnzen.game.sgf.SgfCoordinates
import dev.turnzen.game.sgf.SgfGameRecords
import dev.turnzen.game.sgf.SgfParseException
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameDetail
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.LadderStanding
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.ScoreState
import dev.turnzen.goserver.ServerId
import dev.turnzen.goserver.SignedInUser
import dev.turnzen.goserver.TournamentRef
import dev.turnzen.goserver.TurnBasedGoServerClient

/**
 * Dragon Go Server client over the QuickSuite (`quick_do.php`) plus `login.php` and
 * `sgf.php`. All DGS specifics (URLs, cookies, response quirks) live here.
 */
class DgsClient(
    private val transport: HttpTransport,
    baseUrl: String = DEFAULT_BASE_URL,
) : TurnBasedGoServerClient {
    companion object {
        const val DEFAULT_BASE_URL = "https://www.dragongoserver.net/"

        /** DGS's [ServerId], carried by every [GameId] this client hands out. */
        val SERVER_ID = ServerId("dgs")

        /** The largest page DGS returns from a game list; it falls back to 10 outside 1..100. */
        private const val RUNNING_LIMIT = 100
    }

    private val baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private var cookies = SessionCookies()
    private var userId: String? = null

    override val serverId: ServerId = SERVER_ID
    override val serverName: String = "Dragon Go Server"
    override val registrationUrl: String = this.baseUrl + "register.php"
    override val passwordDomain: String? = Urls.host(this.baseUrl)

    override suspend fun signIn(credentials: Credentials): SignedInUser {
        cookies = SessionCookies()
        userId = null
        val response =
            send(
                HttpRequest(
                    HttpMethod.POST,
                    url("login.php"),
                    form =
                        mapOf(
                            "quick_mode" to "1",
                            "userid" to credentials.username,
                            "passwd" to credentials.password,
                        ),
                ),
            )
        LoginResponse.failure(response.body)?.let { throw it }
        if (cookies.isEmpty) throw GoServerException.Protocol("DGS accepted the login but set no session cookie")
        return currentUser()
    }

    override suspend fun signOut() {
        try {
            if (!cookies.isEmpty) {
                send(
                    HttpRequest(HttpMethod.GET, url("login.php", "quick_mode" to "1", "logout" to "1")),
                )
            }
        } finally {
            cookies = SessionCookies()
            userId = null
        }
    }

    override suspend fun currentUser(): SignedInUser {
        val user = UserInfoParser.parse(quickDo("obj" to "user", "cmd" to "info"))
        userId = user.id
        return user
    }

    override suspend fun gamesAwaitingMe(): List<GameSummary> {
        val envelope =
            quickDo(
                "obj" to "game",
                "cmd" to "list",
                "view" to "status",
                "limit" to "all",
                "lstyle" to "json",
                "with" to "user_id",
            )
        return GameObjectParser.parseList(envelope, userId)
    }

    override suspend fun myRunningGames(): List<GameSummary> {
        val envelope =
            quickDo(
                "obj" to "game",
                "cmd" to "list",
                "view" to "running",
                "uid" to "mine",
                // Without a limit DGS returns only 10 games, which hid every running game past
                // the tenth. 100 is the most it honours (see myFinishedGames).
                "limit" to RUNNING_LIMIT.toString(),
                "lstyle" to "json",
                "with" to "user_id",
            )
        return GameObjectParser.parseList(envelope, userId)
    }

    /**
     * DGS returns its game lists newest move first (`list_order=time_lastmove-`), which for
     * finished games means most recently finished first, so [limit] takes the tail of the
     * archive rather than its beginning. The list is sorted again by the caller anyway.
     */
    override suspend fun myFinishedGames(limit: Int): List<GameSummary> {
        val envelope =
            quickDo(
                "obj" to "game",
                "cmd" to "list",
                "view" to "finished",
                "uid" to "mine",
                // DGS accepts 1..100 and silently defaults to 10 for anything else.
                "limit" to limit.coerceIn(1, 100).toString(),
                "lstyle" to "json",
                // ratingdiff is what fills rating_end/rating_diff, and DGS offers it on the
                // finished view only — which is the only view that could use it.
                "with" to "user_id,ratingdiff",
            )
        return GameObjectParser.parseList(envelope, userId)
    }

    override suspend fun game(id: GameId): GameDetail {
        val game =
            quickDo(
                "obj" to "game",
                "cmd" to "info",
                "gid" to id.value,
                // ratingdiff costs nothing on a running game (DGS fills it for finished ones
                // only) and is what lets the info sheet show what the game did to both ranks.
                "with" to "user_id,ratingdiff",
            )
        val summary = GameObjectParser.parse(game, userId)
        // Same payload, read twice: the summary is what every screen needs, the info is the
        // rest of what DGS already told us (ruleset, komi, both clocks, captures, tournament).
        val info = GameInfoParser.parse(game)
        // owned_comments=1 is what brings the players' own messages: without it DGS writes
        // the SGF for an observer, who gets only the public parts. It also appends the user's
        // private game notes, which DgsComments cuts off again.
        val sgf =
            send(
                HttpRequest(
                    HttpMethod.GET,
                    url("sgf.php", "gid" to id.value, "owned_comments" to "1", "quick_mode" to "1", "no_cache" to "1"),
                ),
            )
        val body = sgf.body
        if (!body.trimStart().startsWith("(")) {
            LoginResponse.failure(body)?.let { throw it }
            throw GoServerException.Protocol("Expected SGF for game ${id.value}, got: ${body.take(80)}")
        }
        val sgfGame =
            try {
                SgfGameRecords.read(body)
            } catch (e: SgfParseException) {
                throw GoServerException.Protocol("Could not read SGF for game ${id.value}", e)
            }
        // The tournament is the one thing the game object is nearly silent about — it gives
        // only the id — while the SGF header names the event and its round. Both describe the
        // same game, so the info is completed here rather than in either parser alone.
        val completed =
            info.copy(tournament = DgsTournament.merge(info.tournament, sgfGame.rootProperties))
        return GameDetail(summary, sgfGame.record, completed, DgsComments.parse(sgfGame, summary.myColor))
    }

    override suspend fun sendMove(
        id: GameId,
        token: MoveToken,
        move: Move,
        comment: String?,
    ) {
        val encoded =
            when (move) {
                Move.Pass -> "pass"
                is Move.Play -> SgfCoordinates.encode(move.point)
            }
        // A DGS message is part of the move: it goes in the same request and is stored with it.
        val message = comment?.trim().orEmpty()
        quickDo(
            method = HttpMethod.POST,
            "obj" to "game",
            "cmd" to "move",
            "gid" to id.value,
            "move_id" to token.value,
            "move" to encoded,
            *(if (message.isEmpty()) emptyArray() else arrayOf("msg" to message)),
        )
    }

    override suspend fun resign(
        id: GameId,
        token: MoveToken,
        comment: String?,
    ) {
        val message = comment?.trim().orEmpty()
        quickDo(
            method = HttpMethod.POST,
            "obj" to "game",
            "cmd" to "resign",
            "gid" to id.value,
            "move_id" to token.value,
            *(if (message.isEmpty()) emptyArray() else arrayOf("msg" to message)),
        )
    }

    override suspend fun scoreState(
        id: GameId,
        token: MoveToken,
        toggle: List<Point>,
    ): ScoreState =
        ScoreStateParser.parse(
            quickDo(
                "obj" to "game",
                "cmd" to "status_score",
                "gid" to id.value,
                "move_id" to token.value,
                *scoreMoves(toggle),
            ),
        )

    override suspend fun disputeScore(
        id: GameId,
        token: MoveToken,
        toggle: List<Point>,
    ) {
        quickDo(
            method = HttpMethod.POST,
            "obj" to "game",
            "cmd" to "score",
            "gid" to id.value,
            "move_id" to token.value,
            *scoreMoves(toggle),
        )
    }

    override suspend fun acceptScore(
        id: GameId,
        token: MoveToken,
    ) {
        // An empty move list plus agree=1: the spec asks for the agreement to be explicit
        // rather than implied by sending nothing, so a client cannot agree by slip.
        quickDo(
            method = HttpMethod.POST,
            "obj" to "game",
            "cmd" to "score",
            "gid" to id.value,
            "move_id" to token.value,
            "move" to "",
            "agree" to "1",
        )
    }

    /**
     * The user's taps, in order, in SGF letters. DGS flips the same area for a coordinate that
     * `DeadStones.toggle` does for a tap, and `toggle=all` makes it apply them one after the
     * other on the current marks — a replay of exactly what the user saw. (`uniq` would flip
     * each point at most once, so a tap that undid an earlier one would be lost.)
     */
    private fun scoreMoves(toggle: List<Point>): Array<Pair<String, String>> =
        arrayOf(
            "toggle" to "all",
            "fmt" to ",",
            "move" to ScoreStateParser.encode(toggle),
        )

    /**
     * Reads the ladder's standings page. Only for ladders: asking for a tournament that is not
     * one answers `err=bad_tournament`, and DGS *writes that to its error log*, so a client
     * must not go fishing. [TournamentRef.isLadder] is the gate, and it is a reliable one —
     * no DGS language translates the word, see `docs/dgs-ladder-standings.md`.
     */
    override suspend fun ladderStandings(tournament: TournamentRef): Map<GameId, LadderStanding> {
        if (!tournament.isLadder) return emptyMap()
        val response =
            send(HttpRequest(HttpMethod.GET, url("tournaments/ladder/view.php", "tid" to tournament.id)))
        // The page answers in three ways and the redirect tells them apart before any body is
        // read. Redirects are visible because the transport does not follow them.
        if (response.status in 300..399) {
            val location = response.header("Location").firstOrNull().orEmpty()
            if (location.contains("err=login_if_not_logged_in")) {
                throw GoServerException.SessionExpired("$serverName signed the session out")
            }
            // err=bad_tournament (not a ladder after all), or anything else we did not expect:
            // this is decoration, so say nothing rather than fail the sheet.
            return emptyMap()
        }
        return DgsLadderPage.standings(response.body)
    }

    override fun gameUrl(id: GameId): String = url("game.php", "gid" to id.value)

    override fun exportSession(): String? {
        if (cookies.isEmpty) return null
        return (userId ?: "") + "\n" + cookies.serialize()
    }

    override fun restoreSession(session: String) {
        val newline = session.indexOf('\n')
        if (newline < 0) {
            cookies = SessionCookies.deserialize(session)
            userId = null
        } else {
            userId = session.substring(0, newline).ifEmpty { null }
            cookies = SessionCookies.deserialize(session.substring(newline + 1))
        }
    }

    private suspend fun quickDo(
        vararg params: Pair<String, String>,
        method: HttpMethod = HttpMethod.GET,
    ) = quickDo(method, *params)

    private suspend fun quickDo(
        method: HttpMethod,
        vararg params: Pair<String, String>,
    ) = QuickSuite.parse(
        send(
            if (method == HttpMethod.GET) {
                HttpRequest(HttpMethod.GET, url("quick_do.php", *params))
            } else {
                HttpRequest(HttpMethod.POST, url("quick_do.php"), form = params.toMap())
            },
        ).body,
    )

    private suspend fun send(request: HttpRequest): HttpResponse {
        // The User-Agent is stamped by the transport, for every server client alike.
        val headers = HashMap(request.headers)
        cookies.headerValue()?.let { headers["Cookie"] = it }
        val response =
            try {
                transport.execute(request.copy(headers = headers))
            } catch (e: HttpTransportException) {
                throw GoServerException.Unreachable("Couldn't reach $serverName", e)
            }
        cookies.absorb(response.header("Set-Cookie"))
        if (response.status >=
            500
        ) {
            throw GoServerException.Unreachable("$serverName answered with HTTP ${response.status}")
        }
        if (response.status >=
            400
        ) {
            throw GoServerException.Protocol("$serverName answered with HTTP ${response.status}")
        }
        return response
    }

    private fun url(
        path: String,
        vararg params: Pair<String, String>,
    ): String {
        if (params.isEmpty()) return baseUrl + path
        val query = params.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }
        return "$baseUrl$path?$query"
    }

    private fun encode(value: String): String = Urls.formEncode(value)
}
