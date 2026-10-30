package dev.turnzen.app

import dev.turnzen.app.notify.MessageWatch
import dev.turnzen.app.notify.MessageWatchState
import dev.turnzen.app.notify.MessagesAction
import dev.turnzen.goserver.InboxMessage
import dev.turnzen.goserver.MessageId
import dev.turnzen.goserver.MessageTopic
import dev.turnzen.goserver.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class MessageWatchTest {
    private val dgs = ServerId("dgs")

    private fun message(
        id: String,
        minute: Int = 0,
    ) = InboxMessage(
        MessageId(dgs, id),
        from = null,
        sentAt = Instant.parse("2026-10-30T12:00:00Z").plus(kotlin.time.Duration.parse("${minute}m")),
        subject = "s",
        text = "",
        topic = MessageTopic.Notice(),
        canReply = false,
    )

    @Test
    fun `a new message sounds, and an old one does not again`() {
        val a = message("a")
        val first = MessageWatch.check(MessageWatchState(), mapOf(dgs to listOf(a)), on = true, inForeground = false)
        val post = assertIs<MessagesAction.Post>(first.action)
        assertEquals(true, post.alert)
        assertEquals(listOf(a), post.messages)

        val again = MessageWatch.check(first.state, mapOf(dgs to listOf(a)), on = true, inForeground = false)
        assertEquals(false, assertIs<MessagesAction.Post>(again.action).alert)
    }

    @Test
    fun `the notification names only what came since the app was open, newest first`() {
        val a = message("a", minute = 0)
        val b = message("b", minute = 5)
        val seen = MessageWatch.seen(MessageWatchState(), mapOf(dgs to listOf(a)))
        val out = MessageWatch.check(seen, mapOf(dgs to listOf(a, b)), on = true, inForeground = false)
        assertEquals(listOf(b), assertIs<MessagesAction.Post>(out.action).messages)

        val c = message("c", minute = 9)
        val more = MessageWatch.check(out.state, mapOf(dgs to listOf(a, b, c)), on = true, inForeground = false)
        assertEquals(listOf(c, b), assertIs<MessagesAction.Post>(more.action).messages)
    }

    @Test
    fun `a message dealt with on the website takes the notification down silently`() {
        val a = message("a")
        val first = MessageWatch.check(MessageWatchState(), mapOf(dgs to listOf(a)), on = true, inForeground = false)
        val gone = MessageWatch.check(first.state, mapOf(dgs to emptyList()), on = true, inForeground = false)
        assertEquals(MessagesAction.Cancel, gone.action)
    }

    @Test
    fun `on screen or switched off, nothing is said`() {
        val a = message("a")
        assertEquals(
            MessagesAction.Cancel,
            MessageWatch.check(MessageWatchState(), mapOf(dgs to listOf(a)), on = true, inForeground = true).action,
        )
        assertEquals(
            MessagesAction.Cancel,
            MessageWatch.check(MessageWatchState(), emptyMap(), on = false, inForeground = false).action,
        )
    }

    @Test
    fun `a server that did not answer keeps its messages known`() {
        val a = message("a")
        val first = MessageWatch.check(MessageWatchState(), mapOf(dgs to listOf(a)), on = true, inForeground = false)
        val silent = MessageWatch.check(first.state, emptyMap(), on = true, inForeground = false)
        assertEquals(MessagesAction.Keep, silent.action)
        val back = MessageWatch.check(silent.state, mapOf(dgs to listOf(a)), on = true, inForeground = false)
        assertEquals(false, assertIs<MessagesAction.Post>(back.action).alert)
    }
}
