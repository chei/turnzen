package dev.turnzen.dgs

import dev.turnzen.dgs.QuickSuite.array
import dev.turnzen.dgs.QuickSuite.int
import dev.turnzen.dgs.QuickSuite.obj
import dev.turnzen.dgs.QuickSuite.string
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.InboxMessage
import dev.turnzen.goserver.InvitationColor
import dev.turnzen.goserver.InvitationSettings
import dev.turnzen.goserver.MessageAuthor
import dev.turnzen.goserver.MessageId
import dev.turnzen.goserver.MessageTopic
import kotlinx.serialization.json.JsonObject

/**
 * The QuickSuite message list (`obj=message&cmd=list&lstyle=json`), read into [InboxMessage]s.
 *
 * What a server message is about is read from its structure — the sender (0 is the server),
 * `type`, `game_id`, and the reference tags DGS writes into the text (`<game 1510946>`,
 * `<tourney 119>`) — never from its wording, which comes in the *challenger's* language
 * rather than the reader's, and with its accented letters damaged by the server (see
 * [repairDamage]).
 */
internal object DgsMessages {
    /** `<game 1510946>`, also `<game 1510946,23>` for a move in it. */
    private val gameTag = Regex("""<game\s+(\d+)""")
    private val tourneyTag = Regex("""<tourney\s+(\d+)>""")

    /**
     * "on ladder-rank #6 has challenged you. Your current ladder-rank is #3." The body of a
     * challenge stayed in English even under a German subject in every sample, but this is
     * still wording: a challenge it does not match is shown as a plain notice, not lost.
     */
    private val ladderRank = Regex("""ladder-rank\D{0,20}#(\d+)""")

    /** The link a tournament invitation carries to the page where it is answered. */
    private val tournamentRegistration = Regex("""tournaments/register\.php\?tid=(\d+)""")

    fun parseList(envelope: JsonObject): List<InboxMessage> {
        val items = envelope.array("list_result") ?: return emptyList()
        return items.map { element ->
            val message =
                element as? JsonObject
                    ?: throw GoServerException.Protocol("Message list entry is not an object (use lstyle=json)")
            parse(message)
        }
    }

    fun parse(message: JsonObject): InboxMessage {
        val id = message.string("id") ?: throw GoServerException.Protocol("Message without id")
        val sender = message.obj("user_from")
        val from =
            sender
                ?.string("id")
                ?.takeUnless { it.isBlank() || it == "0" }
                ?.let { senderId ->
                    MessageAuthor(
                        id = senderId,
                        handle = sender.string("handle") ?: "?",
                        displayName = sender.string("name")?.ifBlank { null },
                        rank = DgsRatings.rank(sender.string("rating")),
                    )
                }
        val subject = message.string("subject").orEmpty()
        val text = message.string("text").orEmpty()
        val previous = message.string("message_prev")?.takeUnless { it.isBlank() || it == "0" }
        return InboxMessage(
            id = MessageId(DgsClient.SERVER_ID, id),
            from = from,
            sentAt = DgsDates.parse(message.string("created_at")),
            subject = if (from == null) repairDamage(subject) else subject,
            text = if (from == null) plainText(repairDamage(text)) else text.replace("\r\n", "\n"),
            topic = if (from == null) serverTopic(message, text) else playerTopic(message),
            canReply = message.int("can_reply") == 1,
            // 2 = needs a reply, 1 = has been replied to, 0 = none needed.
            awaitsAnswer = message.int("need_reply") == 2,
            inReplyTo = previous?.let { MessageId(DgsClient.SERVER_ID, it) },
        )
    }

    private fun playerTopic(message: JsonObject): MessageTopic =
        when (message.string("type")?.uppercase()) {
            "INVITATION", "DISPUTED" -> {
                // Settings are there only while the invitation is open (game_status INVITED).
                val open = message.string("game_status").equals("INVITED", ignoreCase = true)
                MessageTopic.GameInvitation(
                    game = gameId(message.string("game_id")),
                    settings = if (open) message.obj("game_settings")?.let(::invitationSettings) else null,
                )
            }

            else -> {
                MessageTopic.Conversation
            }
        }

    private fun serverTopic(
        message: JsonObject,
        text: String,
    ): MessageTopic {
        val game = gameId(message.string("game_id")) ?: gameId(gameTag.find(text)?.groupValues?.get(1))
        if (message.string("type").equals("RESULT", ignoreCase = true) && game != null) {
            return MessageTopic.GameEnded(game)
        }
        tournamentRegistration.find(text)?.let { return MessageTopic.TournamentInvitation(it.groupValues[1]) }
        val tournament = tourneyTag.find(text)?.groupValues?.get(1)
        val ranks = ladderRank.findAll(text).map { it.groupValues[1].toInt() }.toList()
        if (tournament != null && game != null && ranks.size == 2) {
            return MessageTopic.LadderChallenge(tournament, game, challengerRank = ranks[0], yourRank = ranks[1])
        }
        return MessageTopic.Notice(tournament, game)
    }

    private fun invitationSettings(settings: JsonObject): InvitationSettings? {
        val size = settings.int("size") ?: return null
        return InvitationSettings(
            boardSize = size,
            rated = settings.int("rated")?.let { it != 0 },
            ruleset =
                settings
                    .string("ruleset")
                    ?.trim()
                    ?.ifEmpty { null }
                    ?.lowercase()
                    ?.replaceFirstChar { it.uppercase() },
            timeLimit = settings.string("time_limit")?.ifBlank { null },
            handicap = settings.int("calc_handicap"),
            komi = settings.string("calc_komi")?.trim()?.toDoubleOrNull(),
            myColor =
                when (settings.string("calc_color")?.trim()?.lowercase()) {
                    "black" -> InvitationColor.BLACK
                    "white" -> InvitationColor.WHITE
                    "nigiri" -> InvitationColor.NIGIRI
                    "double" -> InvitationColor.DOUBLE
                    "fairkomi" -> InvitationColor.FAIR_KOMI
                    else -> null
                },
        )
    }

    private fun gameId(value: String?): GameId? =
        value?.trim()?.takeUnless { it.isEmpty() || it == "0" }?.let { GameId(DgsClient.SERVER_ID, it) }

    /**
     * DGS's JSON loses the non-ASCII letters of its own messages: "für Turnier" arrives as
     * `f?00720020005400750072nier` — the letter replaced by "?", and the characters after it
     * written out as four-digit code units. The letter itself is gone; the code units can be
     * read back, which leaves "f?r Turnier". Only ASCII code units are decoded, so ordinary
     * text after a question mark is left alone.
     */
    fun repairDamage(text: String): String =
        damagedRun.replace(text) { match ->
            "?" + match.groupValues[1].chunked(4).joinToString("") { it.toInt(16).toChar().toString() }
        }

    private val damagedRun = Regex("""\?((?:00[2-7][0-9a-fA-F]|000[aA])+)""")

    /**
     * A server message as plain text, for the rare one shown as it came: HTML line breaks
     * kept as lines, every other tag (HTML and DGS's own references alike) dropped.
     */
    fun plainText(text: String): String =
        text
            .replace(Regex("""(?i)<br\s*/?>|</center>|<p>"""), "\n")
            .replace(Regex("""<[^>]*>"""), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
}
