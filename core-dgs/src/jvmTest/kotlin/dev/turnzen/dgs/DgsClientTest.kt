package dev.turnzen.dgs

import dev.turnzen.dgs.http.HttpMethod
import dev.turnzen.dgs.http.HttpRequest
import dev.turnzen.dgs.http.HttpResponse
import dev.turnzen.dgs.http.HttpTransport
import dev.turnzen.dgs.http.HttpTransportException
import dev.turnzen.game.Move
import dev.turnzen.game.Point
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.MoveToken
import dev.turnzen.goserver.TournamentRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DgsClientTest {
    private class FakeTransport : HttpTransport {
        val requests = ArrayList<HttpRequest>()
        val routes = ArrayList<Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpResponse>>()

        fun on(
            match: (HttpRequest) -> Boolean,
            respond: (HttpRequest) -> HttpResponse,
        ) {
            routes += match to respond
        }

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            val route =
                routes.firstOrNull { it.first(request) } ?: error("Unexpected request ${request.method} ${request.url}")
            return route.second(request)
        }
    }

    private fun ok(
        body: String,
        setCookie: List<String> = emptyList(),
    ) = HttpResponse(200, if (setCookie.isEmpty()) emptyMap() else mapOf("Set-Cookie" to setCookie), body)

    private fun HttpRequest.has(param: String): Boolean =
        url.contains("$param&") || url.endsWith(param) || form.entries.any { "${it.key}=${it.value}" == param }

    private fun transportWithHappyPath(): FakeTransport {
        val t = FakeTransport()
        t.on({ it.url.endsWith("login.php") && it.method == HttpMethod.POST }) {
            if (it.form["passwd"] == "secret") {
                ok(
                    Fixtures.text("login_ok.txt"),
                    listOf(
                        "cookie_login=fakeuser; path=/; secure; HttpOnly",
                        "cookie_sessioncode=abc123; path=/; secure",
                    ),
                )
            } else {
                ok(Fixtures.text("login_error_wrong_password.txt"))
            }
        }
        t.on({ it.url.contains("quick_do.php") && it.has("obj=user") && it.has("cmd=info") }) {
            if (it.headers["Cookie"]?.contains("cookie_sessioncode=abc123") == true) {
                ok(Fixtures.text("user_info.json"))
            } else {
                ok(Fixtures.text("quick_do_not_logged_in.json"))
            }
        }
        // Registered before the catch-all list route, which routes are matched in order.
        t.on({
            it.url.contains("quick_do.php") && it.has("view=finished")
        }) { ok(Fixtures.text("game_list_finished.json")) }
        t.on({ it.url.contains("quick_do.php") && it.has("cmd=list") }) { ok(Fixtures.text("game_list_status.json")) }
        t.on({
            it.url.contains("quick_do.php") && it.has("obj=game") && it.has("cmd=info")
        }) { ok(Fixtures.text("game_info.json")) }
        t.on({ it.url.contains("sgf.php") }) { ok(Fixtures.text("game_1000000.sgf")) }
        t.on({
            it.url.contains("quick_do.php") && it.has("cmd=status_score")
        }) { ok(Fixtures.text("status_score.json")) }
        t.on({ it.url.endsWith("quick_do.php") && it.method == HttpMethod.POST && it.form["cmd"] == "score" }) {
            ok(Fixtures.text("status_score.json"))
        }
        t.on({ it.url.endsWith("quick_do.php") && it.method == HttpMethod.POST && it.form["cmd"] == "move" }) {
            if (it.form["move_id"] ==
                "264"
            ) {
                ok(Fixtures.text("move_ok.json"))
            } else {
                ok(Fixtures.text("move_already_played.json"))
            }
        }
        return t
    }

    @Test
    fun `password managers look the password up under the website's domain`() {
        assertEquals("www.dragongoserver.net", DgsClient(FakeTransport()).passwordDomain)
        assertEquals("dgs.example", DgsClient(FakeTransport(), "https://dgs.example/sub").passwordDomain)
    }

    @Test
    fun `sign in posts the form, stores cookies and resolves the user`() =
        runTest {
            val transport = transportWithHappyPath()
            val client = DgsClient(transport, "https://dgs.example.test")
            val user = client.signIn(Credentials("fakeuser", "secret"))
            assertEquals("fakeuser", user.handle)
            assertEquals("123456", user.id)

            val login = transport.requests[0]
            assertEquals(HttpMethod.POST, login.method)
            assertEquals("https://dgs.example.test/login.php", login.url)
            assertEquals("1", login.form["quick_mode"])
            assertEquals("secret", login.form["passwd"])
            assertTrue(login.url.contains("passwd").not(), "password must not be in the URL")

            val userInfo = transport.requests[1]
            assertEquals("cookie_login=fakeuser; cookie_sessioncode=abc123", userInfo.headers["Cookie"])
        }

    @Test
    fun `wrong password is InvalidCredentials and leaves no session`() =
        runTest {
            val transport = transportWithHappyPath()
            val client = DgsClient(transport, "https://dgs.example.test")
            assertFailsWith<GoServerException.InvalidCredentials> { client.signIn(Credentials("fakeuser", "nope")) }
            assertNull(client.exportSession())
        }

    @Test
    fun `game list uses my id to pick colors and opponents`() =
        runTest {
            val transport = transportWithHappyPath()
            val client = DgsClient(transport, "https://dgs.example.test")
            client.signIn(Credentials("fakeuser", "secret"))
            val games = client.gamesAwaitingMe()
            assertEquals(listOf("1500001", "1500002"), games.map { it.id.value })
            assertEquals(listOf(StoneColor.BLACK, StoneColor.WHITE), games.map { it.myColor })
            val list = transport.requests.last()
            assertTrue(list.has("view=status"))
            assertTrue(list.has("lstyle=json"))
            assertTrue(list.has("limit=all"))
        }

    @Test
    fun `the archive is asked for by the tail, newest first, and never for more than DGS allows`() =
        runTest {
            val transport = transportWithHappyPath()
            val client = DgsClient(transport, "https://dgs.example.test")
            client.signIn(Credentials("fakeuser", "secret"))

            val games = client.myFinishedGames(12)
            assertEquals(listOf("1600001", "1600002", "1600003"), games.map { it.id.value })
            assertTrue(games.all { it.isFinished })
            assertTrue(games.none { it.isMyTurn })
            val list = transport.requests.last()
            assertTrue(list.has("view=finished"))
            assertTrue(list.has("uid=mine"))
            assertTrue(list.has("limit=12"))

            // DGS caps the page at 100 and quietly falls back to 10 for anything outside
            // 1..100, which would make a larger ask return *fewer* games.
            client.myFinishedGames(500)
            assertTrue(transport.requests.last().has("limit=100"))
        }

    @Test
    fun `game detail combines info and SGF`() =
        runTest {
            val transport = transportWithHappyPath()
            val client = DgsClient(transport, "https://dgs.example.test")
            client.signIn(Credentials("fakeuser", "secret"))
            val detail = client.game(GameId(DgsClient.SERVER_ID, "1000000"))
            assertEquals("264", detail.summary.moveToken.value)
            assertEquals(19, detail.record.boardSize)
            assertEquals(5, detail.record.setupBlack.size)
            val sgfRequest = transport.requests.last()
            assertTrue(sgfRequest.url.contains("sgf.php?gid=1000000"))
            // As a player, not an observer: only then does DGS include the players' messages.
            assertTrue(sgfRequest.has("owned_comments=1"))
            // The fixture is a finished game with no messages, only the server's count.
            assertEquals(emptyList(), detail.comments)
        }

    @Test
    fun `game detail carries the players' messages`() =
        runTest {
            val transport = transportWithHappyPath()
            transport.routes.add(
                0,
                { it: HttpRequest -> it.url.contains("sgf.php") } to
                    { _: HttpRequest -> ok(Fixtures.text("game_1000001_comments.sgf")) },
            )
            val client = DgsClient(transport, "https://dgs.example.test")
            client.signIn(Credentials("fakeuser", "secret"))
            val comments = client.game(GameId(DgsClient.SERVER_ID, "1000001")).comments.orEmpty()
            assertEquals("Onegaishimasu!", comments.first().text)
            assertTrue(comments.none { "ko at e3" in it.text })
        }

    @Test
    fun `a ladder game takes its name and round from the SGF header`() =
        runTest {
            val transport = transportWithHappyPath()
            // Only the id is in the game object; the event line is in the SGF, which the
            // client downloads for the moves anyway.
            val info = Fixtures.text("game_info.json").replace("\"tournament_id\":0", "\"tournament_id\":3")
            val sgf =
                Fixtures.text("game_1000000.sgf").replace(
                    "PB[",
                    "EV[(Dragon Ladder) Tournament #3: DGS Ladder (19x19)]\nRO[Round 1 of 1]\nPB[",
                )
            transport.routes.add(0, { it: HttpRequest -> it.url.contains("sgf.php") } to { _: HttpRequest -> ok(sgf) })
            transport.routes.add(
                0,
                { it: HttpRequest -> it.url.contains("quick_do.php") && it.has("cmd=info") && it.has("obj=game") }
                    to { _: HttpRequest -> ok(info) },
            )
            val client = DgsClient(transport, "https://dgs.example.test")
            client.signIn(Credentials("fakeuser", "secret"))
            val tournament = client.game(GameId(DgsClient.SERVER_ID, "1000000")).info?.tournament
            assertEquals("3", tournament?.id)
            assertEquals("DGS Ladder (19x19)", tournament?.name)
            assertEquals(true, tournament?.isLadder)
            assertEquals(1, tournament?.roundCount)
        }

    @Test
    fun `ladder standings are read from the ladder page`() =
        runTest {
            val transport = transportWithHappyPath()
            transport.routes.add(
                0,
                { it: HttpRequest -> it.url.contains("tournaments/ladder/view.php") }
                    to { _: HttpRequest -> ok(Fixtures.text("ladder_view_tid3.html")) },
            )
            val client = DgsClient(transport, "https://dgs.example.test")
            client.signIn(Credentials("fakeuser", "secret"))

            val standings = client.ladderStandings(TournamentRef(id = "3", isLadder = true))

            assertEquals(4, standings.size)
            assertEquals(108, standings.getValue(GameId(DgsClient.SERVER_ID, "1503981")).opponentRank)
            assertTrue(standings.getValue(GameId(DgsClient.SERVER_ID, "1503981")).youAreChallenger)
            assertTrue(
                transport.requests
                    .last()
                    .url
                    .contains("tid=3"),
            )
        }

    @Test
    fun `a tournament that is not a ladder is never asked about`() =
        runTest {
            // Asking answers err=bad_tournament, and DGS writes that to its error log. The
            // flag is the gate precisely so the client does not go fishing.
            val transport = transportWithHappyPath()
            val client = DgsClient(transport, "https://dgs.example.test")
            client.signIn(Credentials("fakeuser", "secret"))
            val before = transport.requests.size

            assertEquals(emptyMap(), client.ladderStandings(TournamentRef(id = "365", isLadder = false)))
            assertEquals(before, transport.requests.size)
        }

    @Test
    fun `a redirect off the ladder page is read, not followed`() =
        runTest {
            // The page answers in three ways and only the redirect tells them apart.
            suspend fun standingsGiven(location: String): Map<GameId, dev.turnzen.goserver.LadderStanding> {
                val transport = transportWithHappyPath()
                transport.routes.add(
                    0,
                    { it: HttpRequest -> it.url.contains("tournaments/ladder/view.php") }
                        to { _: HttpRequest -> HttpResponse(302, mapOf("Location" to listOf(location)), "") },
                )
                val client = DgsClient(transport, "https://dgs.example.test")
                client.signIn(Credentials("fakeuser", "secret"))
                return client.ladderStandings(TournamentRef(id = "3", isLadder = true))
            }

            // Not a ladder after all: quietly nothing, because this is decoration.
            assertEquals(emptyMap(), standingsGiven("error.php?err=bad_tournament&eid=1"))
            // Signed out: that is worth telling the app about.
            assertFailsWith<GoServerException.SessionExpired> {
                standingsGiven("error.php?err=login_if_not_logged_in&page=x")
            }
        }

    @Test
    fun `send move posts SGF coordinates or pass with the move token`() =
        runTest {
            val transport = transportWithHappyPath()
            val client = DgsClient(transport, "https://dgs.example.test")
            client.signIn(Credentials("fakeuser", "secret"))

            client.sendMove(GameId(DgsClient.SERVER_ID, "1000000"), MoveToken("264"), Move.Play(Point(16, 3)))
            val play = transport.requests.last()
            assertEquals(HttpMethod.POST, play.method)
            assertEquals("qd", play.form["move"])
            assertEquals("1000000", play.form["gid"])
            assertEquals("264", play.form["move_id"])
            assertEquals("move", play.form["cmd"])

            assertNull(play.form["msg"])

            client.sendMove(GameId(DgsClient.SERVER_ID, "1000000"), MoveToken("264"), Move.Pass)
            assertEquals("pass", transport.requests.last().form["move"])
        }

    @Test
    fun `a comment travels with the move as its message`() =
        runTest {
            val transport = transportWithHappyPath()
            val client = DgsClient(transport, "https://dgs.example.test")
            client.signIn(Credentials("fakeuser", "secret"))

            client.sendMove(
                GameId(DgsClient.SERVER_ID, "1000000"),
                MoveToken("264"),
                Move.Play(Point(16, 3)),
                comment = "  Nice game so far  ",
            )
            val play = transport.requests.last()
            assertEquals("Nice game so far", play.form["msg"])
            assertEquals("qd", play.form["move"])

            // A blank comment is no comment.
            client.sendMove(GameId(DgsClient.SERVER_ID, "1000000"), MoveToken("264"), Move.Pass, comment = " ")
            assertNull(transport.requests.last().form["msg"])
        }

    @Test
    fun `scoring reads the state, proposes marks and agrees explicitly`() =
        runTest {
            val transport = transportWithHappyPath()
            val client = DgsClient(transport, "https://dgs.example.test")
            client.signIn(Credentials("fakeuser", "secret"))
            val game = GameId(DgsClient.SERVER_ID, "1000000")
            val token = MoveToken("264")
            val group = Point(2, 3) // "cd"

            val state = client.scoreState(game, token)
            assertEquals(setOf(group), state.deadStones)
            val read = transport.requests.last()
            assertEquals(HttpMethod.GET, read.method)
            assertTrue(read.url.contains("cmd=status_score"), read.url)
            // uniq so a group named once flips once; comma-separated SGF so the answer parses.
            assertTrue(read.url.contains("toggle=uniq"), read.url)
            assertTrue(read.url.contains("move_id=264"), read.url)

            // A preview of what marking a group would be worth: still a read, never a commit.
            client.scoreState(game, token, setOf(group))
            assertTrue(
                transport.requests
                    .last()
                    .url
                    .contains("move=cd"),
                transport.requests.last().url,
            )

            client.disputeScore(game, token, setOf(group))
            val dispute = transport.requests.last()
            assertEquals(HttpMethod.POST, dispute.method)
            assertEquals("score", dispute.form["cmd"])
            assertEquals("cd", dispute.form["move"])
            assertNull(dispute.form["agree"])

            client.acceptScore(game, token)
            val accept = transport.requests.last()
            assertEquals("score", accept.form["cmd"])
            assertEquals("", accept.form["move"])
            assertEquals("1", accept.form["agree"])
        }

    @Test
    fun `stale token is reported as StaleGame`() =
        runTest {
            val transport = transportWithHappyPath()
            val client = DgsClient(transport, "https://dgs.example.test")
            client.signIn(Credentials("fakeuser", "secret"))
            assertFailsWith<GoServerException.StaleGame> {
                client.sendMove(GameId(DgsClient.SERVER_ID, "1000000"), MoveToken("263"), Move.Play(Point(0, 0)))
            }
        }

    @Test
    fun `session survives export and restore`() =
        runTest {
            val transport = transportWithHappyPath()
            val client = DgsClient(transport, "https://dgs.example.test")
            client.signIn(Credentials("fakeuser", "secret"))
            val session = client.exportSession()!!

            val restored = DgsClient(transport, "https://dgs.example.test")
            restored.restoreSession(session)
            assertEquals("fakeuser", restored.currentUser().handle)
        }

    @Test
    fun `expired session is SessionExpired`() =
        runTest {
            val transport = transportWithHappyPath()
            val client = DgsClient(transport, "https://dgs.example.test")
            client.restoreSession("123456\ncookie_login=fakeuser; cookie_sessioncode=expired")
            assertFailsWith<GoServerException.SessionExpired> { client.currentUser() }
        }

    @Test
    fun `transport failures are Unreachable`() =
        runTest {
            val transport = FakeTransport()
            transport.on({ true }) { throw HttpTransportException("dns") }
            val client = DgsClient(transport)
            assertFailsWith<GoServerException.Unreachable> { client.gamesAwaitingMe() }
        }

    @Test
    fun `sign out clears the session even if the server call fails`() =
        runTest {
            val transport = transportWithHappyPath()
            transport.on({ it.url.contains("logout=1") }) { throw HttpTransportException("offline") }
            val client = DgsClient(transport, "https://dgs.example.test")
            client.signIn(Credentials("fakeuser", "secret"))
            assertFailsWith<GoServerException.Unreachable> { client.signOut() }
            assertNull(client.exportSession())
        }
}
