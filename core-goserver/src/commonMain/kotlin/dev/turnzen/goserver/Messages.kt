package dev.turnzen.goserver

import kotlin.time.Instant

/** Identifies a message, like [GameId] does a game: the server, and that server's own id. */
data class MessageId(
    val server: ServerId,
    val value: String,
)

/** Who wrote a message, when a player did. */
data class MessageAuthor(
    val id: String,
    val handle: String,
    val displayName: String? = null,
    val rank: String? = null,
)

/**
 * A message in the user's mailbox on the server: a player writing to them, or the server
 * itself telling them something (a ladder challenge, a game result).
 *
 * The server's own messages are worded by the server, often not in the reader's language and
 * sometimes damaged on the way (DGS loses accented letters), so [topic] carries what such a
 * message is *about* as data, and a screen should build its own sentence from that. [subject]
 * and [text] are the message as sent: a player's words verbatim, or for a server message only
 * a fallback for a [MessageTopic.Notice] nothing better could be read out of.
 */
data class InboxMessage(
    val id: MessageId,
    /** Null for a message from the server itself. */
    val from: MessageAuthor?,
    val sentAt: Instant?,
    val subject: String,
    val text: String,
    val topic: MessageTopic,
    /** True when [TurnBasedGoServerClient.replyToMessage] is allowed for it. */
    val canReply: Boolean,
    /**
     * True while the message waits for an answer — an invitation not yet accepted or
     * declined. The server will not let it be dismissed until then.
     */
    val awaitsAnswer: Boolean = false,
    /** The message this one answers, when it is a reply. */
    val inReplyTo: MessageId? = null,
)

/** What a message is about, as far as the client could tell. */
sealed interface MessageTopic {
    /** A player's own message; [InboxMessage.subject] and [InboxMessage.text] are what they wrote. */
    data object Conversation : MessageTopic

    /**
     * Someone challenged the user on a ladder, which started [game]. The ranks are where the
     * two stood when the challenge was made, when the server said so.
     */
    data class LadderChallenge(
        val tournamentId: String,
        val game: GameId?,
        val challengerRank: Int? = null,
        val yourRank: Int? = null,
    ) : MessageTopic

    /** [game] has ended; the result is the game's own, read from the game. */
    data class GameEnded(
        val game: GameId,
    ) : MessageTopic

    /** A tournament director invited the user into a tournament; answered on the website. */
    data class TournamentInvitation(
        val tournamentId: String,
    ) : MessageTopic

    /**
     * A player invited the user to a game, answered with
     * [TurnBasedGoServerClient.answerInvitation]. [settings] is null once the invitation is
     * no longer open (accepted, declined, or the server did not say).
     */
    data class GameInvitation(
        val game: GameId?,
        val settings: InvitationSettings?,
    ) : MessageTopic

    /** Any other message from the server, about [tournamentId] and [game] where it named them. */
    data class Notice(
        val tournamentId: String? = null,
        val game: GameId? = null,
    ) : MessageTopic
}

/** The game an open invitation proposes, as far as the server describes it. */
data class InvitationSettings(
    val boardSize: Int,
    val rated: Boolean? = null,
    /** The server's own name for the rules, in sentence case ("Japanese"). */
    val ruleset: String? = null,
    /** The time system, formatted by the server. */
    val timeLimit: String? = null,
    /**
     * The handicap and komi the game would most likely start with, and the user's colour,
     * where the server worked them out; they can depend on ranks at the time of acceptance.
     */
    val handicap: Int? = null,
    val komi: Double? = null,
    val myColor: InvitationColor? = null,
)

/** The user's side in an invited game. */
enum class InvitationColor {
    BLACK,
    WHITE,

    /** Decided by lot when the game starts. */
    NIGIRI,

    /** Two games, one with each colour. */
    DOUBLE,

    /** Decided by bidding for komi when the game starts. */
    FAIR_KOMI,
}
