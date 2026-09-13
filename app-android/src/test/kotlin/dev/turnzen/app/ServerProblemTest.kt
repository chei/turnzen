package dev.turnzen.app

import dev.turnzen.app.ui.common.problemFor
import dev.turnzen.goserver.GoServerException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerProblemTest {
    private val server = "Dragon Go Server"

    @Test
    fun `failures the user waits out are transient, the rest are errors`() {
        assertTrue(problemFor(GoServerException.Unreachable("offline"), server).transient)
        assertTrue(problemFor(GoServerException.RateLimited("slow down"), server).transient)

        assertFalse(problemFor(GoServerException.SessionExpired(), server).transient)
        assertFalse(problemFor(GoServerException.MoveRejected("ko", "ko"), server).transient)
        assertFalse(problemFor(GoServerException.ServerError("500", "boom"), server).transient)
    }

    @Test
    fun `the copy names the server the user chose`() {
        val problem = problemFor(GoServerException.Unreachable("offline"), "Fake server")
        assertTrue(problem.title.contains("Fake server"))
    }
}
