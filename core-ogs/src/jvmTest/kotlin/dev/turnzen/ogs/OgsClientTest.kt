package dev.turnzen.ogs

import dev.turnzen.game.Move
import dev.turnzen.game.Point
import dev.turnzen.game.StoneColor
import dev.turnzen.goserver.CommentVisibility
import dev.turnzen.goserver.GameComment
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.MoveToken
import dev.turnzen.ogs.net.OgsHttp
import dev.turnzen.ogs.net.OgsMethod
import dev.turnzen.ogs.net.OgsRequest
import dev.turnzen.ogs.net.OgsResponse
import dev.turnzen.ogs.net.OgsSocket
import dev.turnzen.ogs.net.OgsSocketConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class OgsClientTest {
    private val config =
        """{"user_jwt":"jwt-1",
           "user":{"id":1001,"username":"turnzen-test","ranking":24.3,"ui_class":"provisional"}}"""

    /** Answers by URL path; records every request. */
    private class FakeHttp(
        val routes: MutableMap<String, (OgsRequest) -> OgsResponse>,
    ) : OgsHttp {
        val requests = mutableListOf<OgsRequest>()

        override suspend fun execute(request: OgsRequest): OgsResponse {
            requests += request
            val path = URI(request.url).path
            val route = routes[path] ?: return OgsResponse(404, "{}")
            return route(request)
        }
    }

    /** A socket that answers like the OGS realtime server would, as scripted by [respond]. */
    private class FakeSocket(
        val respond: (command: String, data: JsonObject, id: Int?) -> List<String>,
    ) : OgsSocket,
        OgsSocketConnector {
        /** A fresh line per connection, so a client may connect again after closing one. */
        private var frames = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        override val incoming: ReceiveChannel<String>
            get() = frames

        override fun send(frame: String) {
            sent += frame
            val array = OgsJson.json.parseToJsonElement(frame) as JsonArray
            val command = (array[0] as JsonPrimitive).content
            val id = (array.getOrNull(2) as? JsonPrimitive)?.content?.toInt()
            respond(command, array[1] as JsonObject, id).forEach { frames.trySend(it) }
        }

        override fun close() {
            frames.close()
        }

        override suspend fun connect(url: String): OgsSocket {
            if (frames.isClosedForSend) frames = Channel(Channel.UNLIMITED)
            return this
        }
    }

    private fun signedIn(
        http: OgsHttp,
        socket: OgsSocketConnector = OgsSocketConnector { error("no socket expected") },
        now: Instant = Instant.fromEpochMilliseconds(1790500000000),
    ) = OgsClient(http, socket, clientId = "test-client", now = { now }).apply {
        val expiresAt = now.toEpochMilliseconds() + 3_600_000
        restoreSession("""{"access":"a1","refresh":"r1","expiresAt":$expiresAt,"userId":1001}""")
    }

    @Test
    fun `running games leave out live play and flag boards Turnzen cannot draw`() =
        runTest {
            val http =
                FakeHttp(
                    mutableMapOf("/api/v1/ui/overview/" to { OgsResponse(200, Fixtures.text("overview.json")) }),
                )
            val client = signedIn(http)
            val games = client.myRunningGames()
            assertEquals(listOf("7000001", "7000003", "7999998"), games.map { it.id.value })
            assertTrue(games.single { it.id.value == "7999998" }.onlyOnWeb)
            assertEquals("Bearer a1", http.requests.first().headers["Authorization"])
        }

    @Test
    fun `icons the overview leaves out come from the player's game list, once`() =
        runTest {
            val overview = Fixtures.text("overview.json").replace(Regex(""",\s*"icon": "[^"]*""""), "")
            val gameList =
                """{"results": [{"id": 7000001, "players": {
                     "black": {"id": 2008, "username": "opponent8", "icon": "https://secure.gravatar.com/avatar/ab?s=32"},
                     "white": {"id": 1001, "username": "turnzen-test"}}}]}"""
            val http =
                FakeHttp(
                    mutableMapOf(
                        "/api/v1/ui/overview/" to { OgsResponse(200, overview) },
                        "/api/v1/players/1001/games/" to { OgsResponse(200, gameList) },
                    ),
                )
            val client = signedIn(http)
            val icons = client.myRunningGames().associate { it.opponent.handle to it.opponent.iconUrl }
            assertEquals("https://secure.gravatar.com/avatar/ab?s=128", icons["opponent8"])
            // opponent7 is not on that page: remembered as having none, not asked about again.
            assertEquals(null, icons["opponent7"])
            val listReads = http.requests.count { "/games/" in it.url }
            assertEquals(1, listReads)
            assertTrue(
                http.requests
                    .single { "/games/" in it.url }
                    .url
                    .contains("ended__isnull=true"),
            )
            client.myRunningGames()
            assertEquals(listReads, http.requests.count { "/games/" in it.url })
        }

    @Test
    fun `the user's own icon comes from the config, or else from their game list`() =
        runTest {
            val withIcon =
                """{"user_jwt":"jwt-1", "user":{"id":1001,"username":"turnzen-test",
                   "icon":"https://secure.gravatar.com/avatar/ab?s=32"}}"""
            val fromConfig = FakeHttp(mutableMapOf("/api/v1/ui/config/" to { OgsResponse(200, withIcon) }))
            assertEquals("https://secure.gravatar.com/avatar/ab?s=128", signedIn(fromConfig).currentUser().iconUrl)
            assertEquals(1, fromConfig.requests.size)

            val gameList =
                """{"results": [{"id": 7000001, "players": {
                     "black": {"id": 2008, "username": "opponent8"},
                     "white": {"id": 1001, "username": "turnzen-test", "icon": "https://example.invalid/1001-64.png"}}}]}"""
            val fromList =
                FakeHttp(
                    mutableMapOf(
                        "/api/v1/ui/config/" to { OgsResponse(200, config) },
                        "/api/v1/players/1001/games/" to { OgsResponse(200, gameList) },
                    ),
                )
            assertEquals("https://example.invalid/1001-64.png", signedIn(fromList).currentUser().iconUrl)

            // Nothing to be found: still signed in, just without a picture.
            val nowhere = FakeHttp(mutableMapOf("/api/v1/ui/config/" to { OgsResponse(200, config) }))
            assertEquals(null, signedIn(nowhere).currentUser().iconUrl)
        }

    @Test
    fun `the two lists share one overview`() =
        runTest {
            val http =
                FakeHttp(
                    mutableMapOf("/api/v1/ui/overview/" to { OgsResponse(200, Fixtures.text("overview.json")) }),
                )
            val client = signedIn(http)
            client.myRunningGames()
            // The test account's opponents are to move in every running game.
            assertEquals(emptyList(), client.gamesAwaitingMe())
            assertEquals(1, http.requests.size)
        }

    @Test
    fun `an expired access token is renewed once and the new pair kept`() =
        runTest {
            var overviewCalls = 0
            val http =
                FakeHttp(
                    mutableMapOf(
                        "/api/v1/ui/overview/" to { request ->
                            overviewCalls++
                            if (request.headers["Authorization"] == "Bearer a2") {
                                OgsResponse(200, Fixtures.text("overview.json"))
                            } else {
                                OgsResponse(401, "{}")
                            }
                        },
                        "/oauth2/token/" to { request ->
                            assertEquals("refresh_token", request.form["grant_type"])
                            assertEquals("r1", request.form["refresh_token"])
                            OgsResponse(200, """{"access_token":"a2","refresh_token":"r2","expires_in":2592000}""")
                        },
                    ),
                )
            val client = signedIn(http)
            client.myRunningGames()
            assertEquals(2, overviewCalls)
            assertTrue(client.exportSession()!!.contains("\"refresh\":\"r2\""))
        }

    @Test
    fun `a server error is Unavailable, not Unreachable`() =
        runTest {
            val http = FakeHttp(mutableMapOf("/api/v1/ui/overview/" to { OgsResponse(503, "") }))
            val client = signedIn(http)
            assertFailsWith<GoServerException.Unavailable> { client.myRunningGames() }
        }

    @Test
    fun `a refused renewal means the user has to sign in again`() =
        runTest {
            val http =
                FakeHttp(
                    mutableMapOf(
                        "/api/v1/ui/overview/" to { OgsResponse(401, "{}") },
                        "/oauth2/token/" to { OgsResponse(400, """{"error":"invalid_grant"}""") },
                    ),
                )
            val client = signedIn(http)
            assertFailsWith<GoServerException.SessionExpired> { client.myRunningGames() }
        }

    @Test
    fun `a game opens with its record and info`() =
        runTest {
            val http =
                FakeHttp(
                    mutableMapOf(
                        "/api/v1/games/7000001" to { OgsResponse(200, Fixtures.text("game_running_paused.json")) },
                    ),
                )
            val detail = signedIn(http).game(GameId(OgsClient.SERVER_ID, "7000001"))
            assertEquals(72, detail.record.moves.size)
            assertEquals("opponent8", detail.record.black.name)
            assertEquals("Japanese", detail.info?.ruleset)
        }

    @Test
    fun `a finished game reads its result from the game`() =
        runTest {
            val http =
                FakeHttp(
                    mutableMapOf(
                        "/api/v1/games/7000002" to { OgsResponse(200, Fixtures.text("game_finished_timeout.json")) },
                    ),
                )
            val summary = signedIn(http).game(GameId(OgsClient.SERVER_ID, "7000002")).summary
            assertTrue(summary.isFinished)
            assertEquals(dev.turnzen.game.StoneColor.WHITE, summary.result?.winner)
            assertEquals(dev.turnzen.game.ResultReason.TIMEOUT, summary.result?.by)
        }

    private fun realtimeHttp() = FakeHttp(mutableMapOf("/api/v1/ui/config/" to { OgsResponse(200, config) }))

    private fun gameServer(
        moves: Int,
        /** The chat history, replayed when a connection joins with chat on. */
        history: List<String> = emptyList(),
        onChat: (JsonObject) -> List<String> = { emptyList() },
        onMove: (
            JsonObject,
        ) -> List<String> = {
            listOf(
                """["game/7000001/move",{"game_id":7000001,"move_number":$moves,"move":[3,15]}]""",
            )
        },
    ) = FakeSocket { command, data, id ->
        when (command) {
            "authenticate" -> {
                listOf("""[$id,{"id":1001,"username":"turnzen-test"}]""")
            }

            "game/connect" -> {
                val list = (0 until moves).joinToString(",") { "[0,0,1]" }
                val gamedata =
                    """["game/7000001/gamedata",{"game_id":7000001,"phase":"play","moves":[$list],""" +
                        """"black_player_id":1001,"white_player_id":2007}]"""
                // Part of the history ahead of the game data and part after it: nothing says
                // which comes first, so the client must not depend on it.
                val chat = if (data["chat"].toString() == "true") history else emptyList()
                chat.take(1) + gamedata + chat.drop(1)
            }

            "game/move", "game/resign" -> {
                onMove(data)
            }

            "game/chat" -> {
                onChat(data)
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun chatLine(
        id: String,
        player: Long,
        moveNumber: Int,
        body: String,
        channel: String = "main",
    ) = """["game/7000001/chat",{"channel":"$channel","line":{"chat_id":"$id","body":$body,""" +
        """"date":${1790500000 + moveNumber},"move_number":$moveNumber,"player_id":$player,"username":"u$player"}}]"""

    @Test
    fun `comments are the players' chat, replayed when joining with chat on`() =
        runTest {
            val socket =
                gameServer(
                    moves = 72,
                    history =
                        listOf(
                            chatLine("c1", 2007, 0, "\"Hi, have a good game\""),
                            chatLine("c2", 1001, 1, "\"you too\""),
                            // Replayed twice: kept once.
                            chatLine("c2", 1001, 1, "\"you too\""),
                            chatLine("c3", 3333, 40, "\"an observer in the main channel\""),
                            chatLine("c4", 3333, 41, "\"observers talking\"", channel = "spectator"),
                            chatLine("c5", 1001, 41, "\"I think I'm ahead\"", channel = "malkovich"),
                            chatLine("c6", 1001, 42, "\"watch the ko\"", channel = "personal"),
                            chatLine("c7", 2007, 50, """{"type":"analysis","name":"a variation","from":49}"""),
                            chatLine("c8", 2007, 72, """{"type":"translated","en":"Thanks!","fr":"Merci !"}"""),
                            // A number past the end of the game still belongs to it.
                            chatLine("c9", 2007, 99, "\"late\""),
                        ),
                )
            val comments = signedIn(realtimeHttp(), socket).comments(GameId(OgsClient.SERVER_ID, "7000001"))
            assertEquals(
                listOf(
                    GameComment(0, StoneColor.WHITE, "Hi, have a good game", CommentVisibility.EVERYONE),
                    GameComment(1, StoneColor.BLACK, "you too", CommentVisibility.EVERYONE),
                    GameComment(41, StoneColor.BLACK, "I think I'm ahead", CommentVisibility.HIDDEN_UNTIL_END),
                    GameComment(42, StoneColor.BLACK, "watch the ko", CommentVisibility.WRITER_ONLY),
                    GameComment(72, StoneColor.WHITE, "Thanks!", CommentVisibility.EVERYONE),
                    GameComment(72, StoneColor.WHITE, "late", CommentVisibility.EVERYONE),
                ),
                comments,
            )
            assertTrue(socket.sent[1].contains("\"chat\":true"))
            assertTrue(socket.sent.last().contains("game/disconnect"))
        }

    @Test
    fun `a history the server starts over replaces what came before`() =
        runTest {
            val socket =
                gameServer(
                    moves = 10,
                    history =
                        listOf(
                            chatLine("c1", 2007, 3, "\"first go\""),
                            """["game/7000001/reset-chats",{}]""",
                            chatLine("c2", 2007, 4, "\"second go\""),
                        ),
                )
            val comments = signedIn(realtimeHttp(), socket).comments(GameId(OgsClient.SERVER_ID, "7000001"))
            assertEquals(listOf("second go"), comments.map { it.text })
        }

    @Test
    fun `a comment follows the move into the chat, pinned to that move`() =
        runTest {
            val socket =
                gameServer(moves = 72, onChat = { data ->
                    val body = data["body"].toString()
                    listOf(
                        """["game/7000001/chat",{"channel":"main","line":{"chat_id":"new","body":$body,""" +
                            """"date":1790500100,"move_number":73,"player_id":1001,"username":"turnzen-test"}}]""",
                    )
                })
            signedIn(realtimeHttp(), socket).sendMove(
                GameId(OgsClient.SERVER_ID, "7000001"),
                MoveToken("72"),
                Move.Play(Point(3, 15)),
                comment = " Nice game so far ",
            )
            val commands = socket.sent.map { (OgsJson.json.parseToJsonElement(it) as JsonArray)[0].toString() }
            assertEquals(
                listOf(
                    "\"authenticate\"",
                    "\"game/connect\"",
                    "\"game/move\"",
                    "\"game/chat\"",
                    "\"game/disconnect\"",
                ),
                commands,
            )
            val chat = OgsJson.json.parseToJsonElement(socket.sent[3]) as JsonArray
            val data = chat[1] as JsonObject
            assertEquals("\"Nice game so far\"", data["body"].toString())
            assertEquals("73", data["move_number"].toString())
            assertEquals("\"main\"", data["type"].toString())
            assertTrue(socket.sent[1].contains("\"chat\":true"))
        }

    @Test
    fun `a comment that never comes back does not undo the move`() =
        runTest {
            val socket = gameServer(moves = 72)
            signedIn(realtimeHttp(), socket).sendMove(
                GameId(OgsClient.SERVER_ID, "7000001"),
                MoveToken("72"),
                Move.Pass,
                comment = "gg",
            )
            assertTrue(socket.sent.any { it.contains("game/chat") })
        }

    @Test
    fun `a move without a comment joins without chat and says nothing`() =
        runTest {
            val socket = gameServer(moves = 72)
            signedIn(realtimeHttp(), socket).sendMove(
                GameId(OgsClient.SERVER_ID, "7000001"),
                MoveToken("72"),
                Move.Pass,
                comment = "  ",
            )
            assertTrue(socket.sent[1].contains("\"chat\":false"))
            assertTrue(socket.sent.none { it.contains("game/chat") })
        }

    @Test
    fun `a move goes over the realtime connection and waits for the server to echo it`() =
        runTest {
            val socket = gameServer(moves = 72)
            signedIn(realtimeHttp(), socket).sendMove(
                GameId(OgsClient.SERVER_ID, "7000001"),
                MoveToken("72"),
                Move.Play(Point(3, 15)),
            )
            val commands = socket.sent.map { (OgsJson.json.parseToJsonElement(it) as JsonArray)[0].toString() }
            assertEquals(
                listOf("\"authenticate\"", "\"game/connect\"", "\"game/move\"", "\"game/disconnect\""),
                commands,
            )
            assertTrue(socket.sent[2].contains("\"move\":\"dp\""))
            assertTrue(socket.sent[0].contains("\"jwt\":\"jwt-1\""))
        }

    @Test
    fun `a move decided on an old position is refused before it is sent`() =
        runTest {
            val socket = gameServer(moves = 73)
            assertFailsWith<GoServerException.StaleGame> {
                signedIn(
                    realtimeHttp(),
                    socket,
                ).sendMove(GameId(OgsClient.SERVER_ID, "7000001"), MoveToken("72"), Move.Pass)
            }
            assertTrue(socket.sent.none { it.contains("game/move") })
        }

    @Test
    fun `a move the server rejects says why`() =
        runTest {
            val socket = gameServer(moves = 72) { listOf("""["game/7000001/error","Illegal Ko Capture"]""") }
            val e =
                assertFailsWith<GoServerException.MoveRejected> {
                    signedIn(
                        realtimeHttp(),
                        socket,
                    ).sendMove(GameId(OgsClient.SERVER_ID, "7000001"), MoveToken("72"), Move.Pass)
                }
            assertEquals("Illegal Ko Capture", e.message)
        }

    @Test
    fun `resigning waits for the game to finish`() =
        runTest {
            val socket = gameServer(moves = 72) { listOf("""["game/7000001/phase","finished"]""") }
            signedIn(realtimeHttp(), socket).resign(GameId(OgsClient.SERVER_ID, "7000001"), MoveToken("72"))
            assertTrue(socket.sent.any { it.contains("game/resign") })
        }

    @Test
    fun `a comment goes into the chat before the resignation`() =
        runTest {
            val socket =
                gameServer(
                    moves = 72,
                    onChat = { data ->
                        listOf(chatLine("c1", 1001, data["move_number"].toString().toInt(), data["body"].toString()))
                    },
                ) { listOf("""["game/7000001/phase","finished"]""") }
            signedIn(realtimeHttp(), socket).resign(
                GameId(OgsClient.SERVER_ID, "7000001"),
                MoveToken("72"),
                comment = " Well played ",
            )
            val commands = socket.sent.map { (OgsJson.json.parseToJsonElement(it) as JsonArray)[0].toString() }
            assertEquals(
                listOf(
                    "\"authenticate\"",
                    "\"game/connect\"",
                    "\"game/chat\"",
                    "\"game/resign\"",
                    "\"game/disconnect\"",
                ),
                commands,
            )
            val chat = (OgsJson.json.parseToJsonElement(socket.sent[2]) as JsonArray)[1] as JsonObject
            assertEquals("\"Well played\"", chat["body"].toString())
            assertEquals("72", chat["move_number"].toString())
        }

    /**
     * The OGS realtime server for [StoneRemovalGame], holding [removed] as its marks. It sets
     * marks as told and echoes the whole new state, and records an acceptance.
     */
    private fun removalServer(
        removed: String,
        /** The marks game data reports on each new connection; [removed] unless changed. */
        marksOnJoin: () -> String = { removed },
    ): FakeSocket {
        var held = OgsStoneRemoval.points(JsonPrimitive(removed), 9)
        val game = StoneRemovalGame.ID
        return FakeSocket { command, data, id ->
            when (command) {
                "authenticate" -> {
                    listOf("""[$id,{"id":1001,"username":"turnzen-test"}]""")
                }

                "game/connect" -> {
                    val marks = marksOnJoin()
                    held = OgsStoneRemoval.points(JsonPrimitive(marks), 9)
                    listOf("""["game/$game/gamedata",${StoneRemovalGame.gamedata(removed = marks)}]""")
                }

                "game/removed_stones/set" -> {
                    val stones = OgsStoneRemoval.points(data["stones"], 9)
                    held = if (data["removed"].toString() == "true") held + stones else held - stones
                    val all = OgsStoneRemoval.encode(held)
                    listOf(
                        """["game/$game/removed_stones",""" +
                            """{"removed":${data["removed"]},"stones":${data["stones"]},"all_removed":"$all"}]""",
                    )
                }

                "game/removed_stones/accept" -> {
                    listOf(
                        """["game/$game/removed_stones_accepted",""" +
                            """{"player_id":1001,"stones":${data["stones"]},"phase":"stone removal"}]""",
                    )
                }

                else -> {
                    emptyList()
                }
            }
        }
    }

    private val removalGame = GameId(OgsClient.SERVER_ID, StoneRemovalGame.ID.toString())
    private val removalToken = MoveToken(StoneRemovalGame.MOVES.toString())

    private fun FakeSocket.commands() =
        sent.map { (OgsJson.json.parseToJsonElement(it) as JsonArray)[0].toString().trim('"') }

    private fun FakeSocket.sentData(command: String) =
        sent
            .map { OgsJson.json.parseToJsonElement(it) as JsonArray }
            .filter { it[0].toString().trim('"') == command }
            .map { it[1] as JsonObject }

    @Test
    fun `the scoring state is the server's marks, read over the realtime connection`() =
        runTest {
            val socket = removalServer("hhbb")
            val state = signedIn(realtimeHttp(), socket).scoreState(removalGame, removalToken)
            assertEquals(setOf(Point(1, 1), Point(7, 7)), state.deadStones)
            assertEquals(StoneColor.BLACK, state.result?.winner)
            assertEquals(listOf("authenticate", "game/connect", "game/disconnect"), socket.commands())
        }

    @Test
    fun `accepting sends back the marks the user was shown`() =
        runTest {
            val socket = removalServer("hhbb")
            val client = signedIn(realtimeHttp(), socket)
            client.scoreState(removalGame, removalToken)
            client.acceptScore(removalGame, removalToken)
            val accept = socket.sentData("game/removed_stones/accept").single()
            assertEquals("\"bbhh\"", accept["stones"].toString())
            assertEquals("false", accept["strict_seki_mode"].toString())
            assertTrue(socket.sentData("game/removed_stones/set").isEmpty())
        }

    @Test
    fun `marks that changed since the user looked are not accepted`() =
        runTest {
            var marks = "bb"
            val socket = removalServer("bb") { marks }
            val client = signedIn(realtimeHttp(), socket)
            client.scoreState(removalGame, removalToken)
            marks = "bbhh"
            assertFailsWith<GoServerException.StaleGame> { client.acceptScore(removalGame, removalToken) }
            assertTrue(socket.sentData("game/removed_stones/accept").isEmpty())
        }

    @Test
    fun `nothing is accepted before the marks have been looked at`() =
        runTest {
            val socket = removalServer("bb")
            assertFailsWith<GoServerException.StaleGame> {
                signedIn(realtimeHttp(), socket).acceptScore(removalGame, removalToken)
            }
            assertTrue(socket.sentData("game/removed_stones/accept").isEmpty())
        }

    @Test
    fun `different marks are set stone by stone and then accepted`() =
        runTest {
            // The server holds white's stone at B8 dead; the user kills black's at H2 and
            // brings B8 back to life.
            val socket = removalServer("bb")
            val client = signedIn(realtimeHttp(), socket)
            client.scoreState(removalGame, removalToken)
            client.disputeScore(removalGame, removalToken, listOf(Point(7, 7), Point(1, 1)))
            assertEquals(
                listOf(
                    "authenticate",
                    "game/connect",
                    "game/disconnect",
                    "authenticate",
                    "game/connect",
                    "game/removed_stones/set",
                    "game/removed_stones/set",
                    "game/removed_stones/accept",
                    "game/disconnect",
                ),
                socket.commands(),
            )
            val (revive, kill) = socket.sentData("game/removed_stones/set")
            assertEquals("false" to "\"bb\"", revive["removed"].toString() to revive["stones"].toString())
            assertEquals("true" to "\"hh\"", kill["removed"].toString() to kill["stones"].toString())
            val accept = socket.sentData("game/removed_stones/accept").single()
            assertEquals("\"hh\"", accept["stones"].toString())
        }

    @Test
    fun `marks the server ends up holding differently are left unaccepted`() =
        runTest {
            val game = StoneRemovalGame.ID
            val socket =
                FakeSocket { command, _, id ->
                    when (command) {
                        "authenticate" -> {
                            listOf("""[$id,{"id":1001}]""")
                        }

                        "game/connect" -> {
                            listOf("""["game/$game/gamedata",${StoneRemovalGame.gamedata(removed = "")}]""")
                        }

                        // The opponent marked a stone of their own at the same moment.
                        "game/removed_stones/set" -> {
                            listOf(
                                """["game/$game/removed_stones",""" +
                                    """{"removed":true,"stones":"hh","all_removed":"bbhh"}]""",
                            )
                        }

                        else -> {
                            emptyList()
                        }
                    }
                }
            val client = signedIn(realtimeHttp(), socket)
            client.scoreState(removalGame, removalToken)
            assertFailsWith<GoServerException.StaleGame> {
                client.disputeScore(removalGame, removalToken, listOf(Point(7, 7)))
            }
            assertTrue(socket.sentData("game/removed_stones/accept").isEmpty())
        }

    @Test
    fun `sign-in runs through the browser and the loopback redirect`() =
        runBlocking {
            val port = 18735
            val http =
                FakeHttp(
                    mutableMapOf(
                        "/oauth2/token/" to { request ->
                            assertEquals("authorization_code", request.form["grant_type"])
                            assertEquals("the-code", request.form["code"])
                            assertEquals("http://127.0.0.1:$port/ogs/signed-in", request.form["redirect_uri"])
                            assertTrue(request.form["code_verifier"]!!.length >= 43)
                            OgsResponse(200, """{"access_token":"a1","refresh_token":"r1","expires_in":2592000}""")
                        },
                        "/api/v1/ui/config/" to { OgsResponse(200, config) },
                    ),
                )
            val client =
                OgsClient(
                    http,
                    { error("no socket") },
                    clientId = "test-client",
                    returnLink = "intent://x",
                    redirectPort = port,
                )
            var opened: String? = null
            val signIn =
                async(Dispatchers.IO) {
                    client.signInInBrowser { url -> opened = url }
                }
            // Play the browser: wait for the page to be opened, then follow the redirect.
            while (opened == null) kotlinx.coroutines.delay(10)
            val query = OgsUrls.parseQuery(URI(opened!!).rawQuery)
            assertEquals("S256", query["code_challenge_method"])
            val connection =
                withContext(Dispatchers.IO) {
                    val connection =
                        URI("http://127.0.0.1:$port/ogs/signed-in?code=the-code&state=${query["state"]}")
                            .toURL()
                            .openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.also { it.responseCode }
                }
            // Straight back to the app, no page in between.
            assertEquals(302, connection.responseCode)
            assertEquals("intent://x", connection.getHeaderField("Location"))
            assertEquals("turnzen-test", signIn.await().handle)
            assertTrue(client.exportSession()!!.contains("\"userId\":1001"))
            assertEquals(OgsMethod.POST, http.requests.first().method)
        }
}
