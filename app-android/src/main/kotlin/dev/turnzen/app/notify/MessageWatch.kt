package dev.turnzen.app.notify

import dev.turnzen.goserver.InboxMessage
import dev.turnzen.goserver.MessageId
import dev.turnzen.goserver.ServerId

/** What the messages notification remembers between checks. */
data class MessageWatchState(
    /** Messages already accounted for: notified about, or seen in the app. */
    val known: Set<MessageId> = emptySet(),
    /** The notified messages the user has not yet seen; the ones the notification names. */
    val flagged: Set<MessageId> = emptySet(),
)

sealed interface MessagesAction {
    data object Keep : MessagesAction

    data object Cancel : MessagesAction

    /** Post or replace it with [messages], newest first; [alert] says whether it sounds. */
    data class Post(
        val messages: List<InboxMessage>,
        val alert: Boolean,
    ) : MessagesAction
}

data class MessagesOutcome(
    val state: MessageWatchState,
    val action: MessagesAction,
)

/**
 * Decides what the new-messages notification says after a check, as [TurnWatch] does for the
 * games. One notification for every new message on every server; it sounds only when a
 * message has arrived since the last check, and names only the messages the user has not
 * seen in the app. A message dealt with elsewhere (on the website) leaves it silently.
 */
object MessageWatch {
    fun check(
        state: MessageWatchState,
        /** The new messages of every server that answered. */
        answered: Map<ServerId, List<InboxMessage>>,
        on: Boolean,
        inForeground: Boolean,
    ): MessagesOutcome {
        val current = answered.values.flatten()
        val currentIds = current.mapTo(HashSet()) { it.id }
        val fresh = current.filter { it.id !in state.known }
        val known = keepOthers(state.known, answered.keys) + currentIds
        if (inForeground || !on) {
            // On screen the block shows them all; with the switch off nothing is said. (Off,
            // no server is asked, so nothing becomes known here: what the app itself fetches
            // does, through [seen], which is what keeps a switch turned on in Settings quiet.)
            return MessagesOutcome(MessageWatchState(known, emptySet()), MessagesAction.Cancel)
        }
        // A flagged message of a server that did not answer stays flagged, as it may still be new.
        val flagged =
            state.flagged.filter { it.server !in answered.keys || it in currentIds }.toSet() + fresh.map { it.id }
        val shown =
            current
                .filter { it.id in flagged }
                .sortedByDescending { it.sentAt }
        val action =
            when {
                fresh.isNotEmpty() -> MessagesAction.Post(shown, alert = true)
                flagged.isEmpty() -> MessagesAction.Cancel
                shown.size < flagged.size -> MessagesAction.Keep
                else -> MessagesAction.Post(shown, alert = false)
            }
        return MessagesOutcome(MessageWatchState(known, flagged), action)
    }

    /**
     * The user has seen the messages: the app is open, or it fetched them itself ([answered],
     * per server, when it has). Nothing stays flagged.
     */
    fun seen(
        state: MessageWatchState,
        answered: Map<ServerId, List<InboxMessage>> = emptyMap(),
    ): MessageWatchState =
        MessageWatchState(
            known = keepOthers(state.known, answered.keys) + answered.values.flatten().map { it.id },
            flagged = emptySet(),
        )

    private fun keepOthers(
        ids: Set<MessageId>,
        servers: Set<ServerId>,
    ): Set<MessageId> = ids.filterTo(HashSet()) { it.server !in servers }
}
