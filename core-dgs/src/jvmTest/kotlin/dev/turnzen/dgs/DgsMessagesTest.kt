package dev.turnzen.dgs

import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.InvitationColor
import dev.turnzen.goserver.MessageId
import dev.turnzen.goserver.MessageTopic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DgsMessagesTest {
    private fun game(id: String) = GameId(DgsClient.SERVER_ID, id)

    // Recorded from the live server (October 2026) and scrubbed; see the brief, "Messages".
    private val list = DgsMessages.parseList(QuickSuite.parse(Fixtures.text("message_list.json")))

    @Test
    fun `every message is read, newest first, with its id`() {
        assertEquals(8, list.size)
        assertEquals(MessageId(DgsClient.SERVER_ID, "9000008"), list.first().id)
        assertEquals((8 downTo 1).map { "900000$it" }, list.map { it.id.value })
    }

    @Test
    fun `a ladder challenge is read from its tags and ranks, not its wording`() {
        val challenge = list[0]
        assertNull(challenge.from)
        assertFalse(challenge.canReply)
        val topic = assertIs<MessageTopic.LadderChallenge>(challenge.topic)
        assertEquals("119", topic.tournamentId)
        // DGS leaves game_id at 0 on a challenge; the game is only in the text's tag.
        assertEquals(game("1500101"), topic.game)
        assertEquals(6, topic.challengerRank)
        assertEquals(3, topic.yourRank)
    }

    @Test
    fun `a challenge in the challenger's language is still a challenge`() {
        val german = list.single { it.id.value == "9000005" }
        val topic = assertIs<MessageTopic.LadderChallenge>(german.topic)
        assertEquals(game("1500104"), topic.game)
        assertEquals(5, topic.challengerRank)
        assertEquals(3, topic.yourRank)
    }

    @Test
    fun `the code units DGS writes for lost letters are read back`() {
        val german = list.single { it.id.value == "9000005" }
        assertEquals("Herausforderung f?r Turnier #119 gestartet", german.subject)
        val other = list.single { it.id.value == "9000007" }
        assertTrue(other.text.endsWith("??:"), other.text)
        // Ordinary text after a question mark is left alone.
        assertEquals("Why? 0020 is fine", DgsMessages.repairDamage("Why? 0020 is fine"))
    }

    @Test
    fun `a game result names its game`() {
        val result = list.single { it.id.value == "9000006" }
        assertEquals(MessageTopic.GameEnded(game("1500103")), result.topic)
        // The HTML is gone from the fallback text.
        assertFalse(result.text.contains("<"), result.text)
        assertTrue(result.text.contains("White wins by 30.0"), result.text)
    }

    @Test
    fun `a tournament invitation points at its tournament`() {
        val invitation = list.single { it.id.value == "9000004" }
        assertEquals(MessageTopic.TournamentInvitation("382"), invitation.topic)
    }

    @Test
    fun `a player's message keeps their words and the thread it belongs to`() {
        val reply = list.single { it.id.value == "9000002" }
        assertEquals(MessageTopic.Conversation, reply.topic)
        val from = assertNotNull(reply.from)
        assertEquals("opp_b", from.handle)
        assertEquals("Opponent B", from.displayName)
        assertEquals("6k", from.rank)
        assertTrue(reply.canReply)
        assertEquals(MessageId(DgsClient.SERVER_ID, "9000000"), reply.inReplyTo)
        assertEquals("Good game! Want a rematch?", reply.text)

        val first = list.single { it.id.value == "9000003" }
        assertNull(first.inReplyTo)
        assertNotNull(first.sentAt)
    }

    @Test
    fun `an open invitation awaits an answer and carries its settings`() {
        val invitations = DgsMessages.parseList(QuickSuite.parse(Fixtures.text("message_list_invitation.json")))
        val open = invitations[0]
        assertTrue(open.awaitsAnswer)
        val topic = assertIs<MessageTopic.GameInvitation>(open.topic)
        assertEquals(game("1500201"), topic.game)
        val settings = assertNotNull(topic.settings)
        assertEquals(13, settings.boardSize)
        assertEquals("Japanese", settings.ruleset)
        assertEquals(true, settings.rated)
        assertEquals("F: 10d + 1d", settings.timeLimit)
        assertEquals(3, settings.handicap)
        assertEquals(0.5, settings.komi)
        assertEquals(InvitationColor.BLACK, settings.myColor)

        // Declined: game_status is empty and the settings are gone.
        val declined = invitations[1]
        assertFalse(declined.awaitsAnswer)
        assertNull(assertIs<MessageTopic.GameInvitation>(declined.topic).settings)
    }
}
