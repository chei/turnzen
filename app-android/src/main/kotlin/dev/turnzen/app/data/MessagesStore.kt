package dev.turnzen.app.data

import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.InboxMessage
import dev.turnzen.goserver.MessageId
import dev.turnzen.goserver.ServerId
import dev.turnzen.goserver.TurnBasedGoServerClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The messages the user has not dealt with yet, on every server that has a mailbox: what the
 * new-messages block at the top of "Your turn" shows. Newest first, across servers.
 *
 * Fetched with the games (see [refresh]), and changed only by the user's own acts: dismissing
 * a message, replying to one, answering an invitation. Each of those takes the message off
 * the list at once and puts it back if the server refused.
 */
class MessagesStore(
    private val accounts: Accounts,
    /**
     * Told what each answering server said is new, after every [refresh]: the user has now
     * seen those messages, so the notifications need not announce them.
     */
    private val onSeen: (Map<ServerId, List<InboxMessage>>) -> Unit = {},
) {
    private val _messages = MutableStateFlow<List<InboxMessage>>(emptyList())
    val messages: StateFlow<List<InboxMessage>> = _messages.asStateFlow()

    /** Messages on their way off the list: hidden even if a refresh still lists them. */
    private var leaving: Set<MessageId> = emptySet()

    /** The server's list, before [leaving] is applied. */
    private var listed: List<InboxMessage> = emptyList()

    /**
     * Asks every signed-in server for its new messages, all at once. A server that does not
     * answer keeps the messages it had; failures are returned, never thrown, and are not the
     * screen's to show — the games' refresh, made alongside, already names a server that is down.
     */
    suspend fun refresh(): Map<ServerId, GoServerException> {
        val sessions = accounts.signedIn
        val answers =
            coroutineScope {
                sessions
                    .map { session ->
                        async {
                            session.serverId to
                                try {
                                    Result.success(session.call { it.newMessages() })
                                } catch (e: GoServerException) {
                                    Result.failure(e)
                                }
                        }
                    }.awaitAll()
            }
        val failures =
            answers
                .mapNotNull { (server, answer) ->
                    answer.exceptionOrNull()?.let {
                        server to
                            (it as GoServerException)
                    }
                }.toMap()
        val fresh = answers.mapNotNull { (server, answer) -> answer.getOrNull()?.let { server to it } }.toMap()
        onSeen(fresh)
        val signedIn = sessions.mapTo(HashSet()) { it.serverId }
        listed =
            (listed.filter { it.id.server in failures.keys && it.id.server in signedIn } + fresh.values.flatten())
                .sortedWith(
                    compareByDescending<InboxMessage> { it.sentAt }.thenByDescending { it.id.value.toLongOrNull() },
                )
        publish()
        return failures
    }

    fun message(id: MessageId): InboxMessage? = listed.firstOrNull { it.id == id }

    /** Takes [message] off the new pile, here and on its server. */
    suspend fun dismiss(message: InboxMessage) = act(message) { it.dismissMessage(message.id) }

    /** Answers [message]; the server takes it off the new pile as it does. */
    suspend fun reply(
        message: InboxMessage,
        text: String,
    ) = act(message) { it.replyToMessage(message, text) }

    suspend fun answerInvitation(
        message: InboxMessage,
        accept: Boolean,
        text: String?,
    ) = act(message) { it.answerInvitation(message, accept, text) }

    /**
     * Hides [message] while [block] runs on its server, and for good once it has worked. A
     * failure brings it back and is thrown on, for the screen to say what went wrong.
     */
    private suspend fun act(
        message: InboxMessage,
        block: suspend (TurnBasedGoServerClient) -> Unit,
    ) {
        leaving = leaving + message.id
        publish()
        try {
            accounts.session(message.id.server).call(block)
            listed = listed.filterNot { it.id == message.id }
        } finally {
            leaving = leaving - message.id
            publish()
        }
    }

    private fun publish() {
        _messages.update { listed.filterNot { it.id in leaving } }
    }
}
