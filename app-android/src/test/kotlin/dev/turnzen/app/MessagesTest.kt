package dev.turnzen.app

import dev.turnzen.app.data.Accounts
import dev.turnzen.app.data.GamesStore
import dev.turnzen.app.data.MessagesStore
import dev.turnzen.app.fake.FakeTurnBasedGoServerClient
import dev.turnzen.app.ui.games.GamesViewModel
import dev.turnzen.app.ui.messages.MessageWording
import dev.turnzen.goserver.Credentials
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.InboxMessage
import dev.turnzen.goserver.InvitationColor
import dev.turnzen.goserver.InvitationSettings
import dev.turnzen.goserver.MessageAuthor
import dev.turnzen.goserver.MessageId
import dev.turnzen.goserver.MessageTopic
import dev.turnzen.goserver.ServerId
import dev.turnzen.goserver.TurnBasedGoServerClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesTest : MainDispatcherTest() {
    private val dgs = ServerId("dgs")

    /** The demo build's DGS: its games, its archive and one message of each kind. */
    private fun demo() = FakeTurnBasedGoServerClient(latencyMillis = 0, serverId = dgs, shareGames = true)

    /** Refuses every act on a message while [refusing]. */
    private class Refusing(
        private val fake: FakeTurnBasedGoServerClient,
        var refusing: Boolean = true,
    ) : TurnBasedGoServerClient by fake {
        override suspend fun dismissMessage(id: MessageId) {
            if (refusing) throw GoServerException.Unreachable("no network")
            fake.dismissMessage(id)
        }
    }

    private fun message(
        topic: MessageTopic,
        from: MessageAuthor? = null,
        subject: String = "Subject",
        text: String = "",
    ) = InboxMessage(MessageId(dgs, "1"), from, null, subject, text, topic, canReply = from != null)

    @Test
    fun `server messages are worded from what they are about, players' as they wrote them`() =
        runTest(dispatcher) {
            val session = sessionFor(demo()).also { it.signIn(Credentials("demo", "pw")) }
            val store = GamesStore(Accounts(listOf(session)))
            store.refresh()
            store.refreshFinished()

            val challenge =
                MessageWording.describe(
                    message(
                        MessageTopic.LadderChallenge("119", GameId(dgs, "demo-13"), challengerRank = 12, yourRank = 7),
                    ),
                    store::summary,
                )
            assertEquals("hana_go challenged you", challenge.title)
            assertEquals("Ladder #119 · you #7, hana_go #12", challenge.detail)

            // A game the lists do not hold is still a challenge, just without the name.
            val unknown =
                MessageWording.describe(
                    message(
                        MessageTopic.LadderChallenge("119", GameId(dgs, "nope"), challengerRank = 12, yourRank = 7),
                    ),
                    store::summary,
                )
            assertEquals("Ladder challenge", unknown.title)

            val ended =
                MessageWording.describe(
                    message(MessageTopic.GameEnded(GameId(dgs, "demo-done-loss"))),
                    store::summary,
                )
            assertEquals("You lost against tsuke_42", ended.title)
            assertEquals("by resignation", ended.detail)

            val conversation =
                MessageWording.describe(
                    message(MessageTopic.Conversation, MessageAuthor("2", "mkvtl"), "Re: hi", "\n  See you\nlater"),
                    store::summary,
                )
            assertEquals("mkvtl", conversation.title)
            // A "Re:" subject says nothing, so the text leads.
            assertEquals("See you", conversation.detail)

            val settings =
                InvitationSettings(
                    13,
                    rated = true,
                    timeLimit = "F: 10d + 1d",
                    handicap = 1,
                    komi = 0.5,
                    myColor = InvitationColor.BLACK,
                )
            assertEquals(
                "13x13, rated, you play black, handicap 1, komi 0.5, F: 10d + 1d",
                MessageWording.settingsLine(settings),
            )
        }

    @Test
    fun `a dismissed message leaves at once, and comes back if the server refused`() =
        runTest(dispatcher) {
            val client = Refusing(demo())
            val session = sessionFor(client).also { it.signIn(Credentials("demo", "pw")) }
            val inbox = MessagesStore(Accounts(listOf(session)))
            inbox.refresh()
            val first = inbox.messages.value.first()
            val count = inbox.messages.value.size

            assertFailsWith<GoServerException.Unreachable> { inbox.dismiss(first) }
            assertEquals(count, inbox.messages.value.size)

            client.refusing = false
            inbox.dismiss(first)
            assertEquals(count - 1, inbox.messages.value.size)
            assertNull(inbox.message(first.id))
            // The server agrees: the next refresh does not bring it back.
            inbox.refresh()
            assertNull(inbox.message(first.id))
        }

    @Test
    fun `the messages come with the games, and replying takes one off the list`() =
        runTest(dispatcher) {
            val session = sessionFor(demo()).also { it.signIn(Credentials("demo", "pw")) }
            val accounts = Accounts(listOf(session))
            val vm = GamesViewModel(accounts, GamesStore(accounts), MessagesStore(accounts))
            backgroundScope.launch { vm.state.collect {} }

            vm.refresh()
            advanceUntilIdle()
            val rows = vm.state.value.messages
            assertEquals(4, rows.size)
            // Newest first, and the challenge leads to its game.
            assertEquals("hana_go challenged you", rows.first().text.title)
            assertEquals(GameId(dgs, "demo-13"), rows.first().game)
            // The result message fetched the archive by itself, so it can say how it went.
            assertEquals("You lost against tsuke_42", rows.last().text.title)

            val conversation = rows.single { it.message.topic == MessageTopic.Conversation }
            vm.openMessage(conversation.message.id)
            advanceUntilIdle()
            assertNotNull(vm.state.value.openRow)
            vm.reply(conversation.message.id, "Safe travels!")
            advanceUntilIdle()
            assertNull(vm.state.value.openRow)
            assertNull(vm.state.value.openMessage)
            assertEquals(3, vm.state.value.messages.size)
            assertTrue(
                vm.state.value.messages
                    .none { it.message.id == conversation.message.id },
            )
        }
}
