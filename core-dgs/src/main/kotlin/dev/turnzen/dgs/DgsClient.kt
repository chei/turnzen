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
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.ScoreState
import dev.turnzen.goserver.SignedInUser
import dev.turnzen.goserver.TurnBasedGoServerClient
import java.net.URLEncoder

/**
 * Dragon Go Server client over the QuickSuite (`quick_do.php`) plus `login.php` and
 * `sgf.php`. All DGS specifics (URLs, cookies, response quirks) live here.
 */
class DgsClient(
    private val transport: HttpTransport,
    baseUrl: String = DEFAULT_BASE_URL,
    private val userAgent: String = "Turnzen",
) : TurnBasedGoServerClient {
    companion object {
        const val DEFAULT_BASE_URL = "https://www.dragongoserver.net/"
    }

    private val baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private var cookies = SessionCookies()
    private var userId: String? = null

    override val serverName: String = "Dragon Go Server"
    override val registrationUrl: String = this.baseUrl + "register.php"

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
                "lstyle" to "json",
                "with" to "user_id",
            )
        return GameObjectParser.parseList(envelope, userId)
    }

    override suspend fun game(id: GameId): GameDetail {
        val game =
            quickDo(
                "obj" to "game",
                "cmd" to "info",
                "gid" to id.value,
                "with" to "user_id",
            )
        val summary = GameObjectParser.parse(game, userId)
        // Same payload, read twice: the summary is what every screen needs, the info is the
        // rest of what DGS already told us (ruleset, komi, both clocks, captures, tournament).
        val info = GameInfoParser.parse(game)
        val sgf =
            send(
                HttpRequest(
                    HttpMethod.GET,
                    url("sgf.php", "gid" to id.value, "owned_comments" to "N", "quick_mode" to "1", "no_cache" to "1"),
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
        return GameDetail(summary, sgfGame.record, completed)
    }

    override suspend fun sendMove(
        id: GameId,
        token: MoveToken,
        move: Move,
    ) {
        val encoded =
            when (move) {
                Move.Pass -> "pass"
                is Move.Play -> SgfCoordinates.encode(move.point)
            }
        quickDo(
            method = HttpMethod.POST,
            "obj" to "game",
            "cmd" to "move",
            "gid" to id.value,
            "move_id" to token.value,
            "move" to encoded,
        )
    }

    override suspend fun resign(
        id: GameId,
        token: MoveToken,
    ) {
        quickDo(
            method = HttpMethod.POST,
            "obj" to "game",
            "cmd" to "resign",
            "gid" to id.value,
            "move_id" to token.value,
        )
    }

    override suspend fun scoreState(
        id: GameId,
        token: MoveToken,
        toggle: Set<Point>,
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
        toggle: Set<Point>,
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
     * The points whose life is being flipped, in SGF letters. `toggle=uniq` makes each named
     * group flip exactly once — the client sends one point per group, and the default "all"
     * mode would undo a group named twice.
     */
    private fun scoreMoves(toggle: Set<Point>): Array<Pair<String, String>> =
        arrayOf(
            "toggle" to "uniq",
            "fmt" to ",",
            "move" to ScoreStateParser.encode(toggle),
        )

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
        val headers = HashMap(request.headers)
        headers["User-Agent"] = userAgent
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

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
