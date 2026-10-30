package dev.turnzen.app.ui.messages

import dev.turnzen.app.ui.common.Formatting
import dev.turnzen.game.ResultReason
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.InboxMessage
import dev.turnzen.goserver.InvitationColor
import dev.turnzen.goserver.InvitationSettings
import dev.turnzen.goserver.MessageTopic

/** A message as Turnzen words it: one line that says what happened, one with the particulars. */
data class MessageText(
    val title: String,
    val detail: String?,
)

/**
 * Turnzen's own sentences for the messages a server writes. The server's wording is not used
 * for them: DGS writes a challenge in the challenger's language and damages its accented
 * letters, so the message is read for what it is about ([MessageTopic]) and said again here.
 * A player's message is theirs, and is shown as they wrote it.
 *
 * [game] looks a game up in the lists the app holds, for the opponent's name and a finished
 * game's result; a game it does not know is described without them.
 */
object MessageWording {
    fun describe(
        message: InboxMessage,
        game: (GameId) -> GameSummary?,
    ): MessageText =
        when (val topic = message.topic) {
            MessageTopic.Conversation -> {
                MessageText(
                    title = message.from?.handle ?: message.subject,
                    detail = conversationLine(message),
                )
            }

            is MessageTopic.LadderChallenge -> {
                val challenger =
                    topic.game
                        ?.let(game)
                        ?.opponent
                        ?.handle
                val ranks =
                    if (topic.yourRank != null && topic.challengerRank != null) {
                        "you #${topic.yourRank}, ${challenger ?: "challenger"} #${topic.challengerRank}"
                    } else {
                        null
                    }
                MessageText(
                    title = if (challenger != null) "$challenger challenged you" else "Ladder challenge",
                    detail = listOfNotNull("Ladder #${topic.tournamentId}", ranks).joinToString(" · "),
                )
            }

            is MessageTopic.GameEnded -> {
                gameEnded(game(topic.game)) ?: MessageText("A game has ended", firstLine(message.text))
            }

            is MessageTopic.TournamentInvitation -> {
                MessageText("Tournament invitation", "Tournament #${topic.tournamentId}, answered on the website")
            }

            is MessageTopic.GameInvitation -> {
                val who = message.from?.handle ?: "A player"
                MessageText(
                    title = "$who invites you to a game",
                    detail = topic.settings?.let(::settingsLine) ?: "No longer open",
                )
            }

            is MessageTopic.Notice -> {
                MessageText(message.subject.ifBlank { "Message from the server" }, firstLine(message.text))
            }
        }

    /** "13x13, rated, you play black, handicap 1, komi 0.5, F: 10d + 1d". */
    fun settingsLine(settings: InvitationSettings): String =
        listOfNotNull(
            "${settings.boardSize}x${settings.boardSize}",
            when (settings.rated) {
                true -> "rated"
                false -> "unrated"
                null -> null
            },
            when (settings.myColor) {
                InvitationColor.BLACK -> "you play black"
                InvitationColor.WHITE -> "you play white"
                InvitationColor.NIGIRI -> "colours by lot"
                InvitationColor.DOUBLE -> "two games, one each colour"
                InvitationColor.FAIR_KOMI -> "colours by komi bidding"
                null -> null
            },
            settings.handicap?.takeIf { it > 0 }?.let { "handicap $it" },
            settings.komi?.let { "komi ${Formatting.points(it)}" },
            settings.timeLimit,
        ).joinToString(", ")

    /** "You lost against tsuke_42" / "by resignation", from the finished game the lists hold. */
    private fun gameEnded(game: GameSummary?): MessageText? {
        if (game == null || !game.isFinished) return null
        val result = game.result
        val opponent = game.opponent.handle
        val title =
            when {
                result == null || result.by == ResultReason.NO_RESULT -> "No result against $opponent"
                result.winner == null -> "Draw against $opponent"
                result.winner == game.myColor -> "You won against $opponent"
                else -> "You lost against $opponent"
            }
        val detail =
            when (result?.by) {
                ResultReason.RESIGNATION -> "by resignation"
                ResultReason.TIMEOUT -> "on time"
                ResultReason.FORFEIT -> "by forfeit"
                ResultReason.POINTS -> result.margin?.let { "by ${Formatting.points(it)}" }
                else -> null
            }
        return MessageText(title, detail)
    }

    /** The subject, then as much of the text as fits; a "Re:" subject says nothing, so it goes. */
    private fun conversationLine(message: InboxMessage): String? {
        val subject = message.subject.trim().takeUnless { it.isEmpty() || it.startsWith("re:", ignoreCase = true) }
        return listOfNotNull(subject, firstLine(message.text)).joinToString(" · ").ifEmpty { null }
    }

    private fun firstLine(text: String): String? =
        text
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
}
