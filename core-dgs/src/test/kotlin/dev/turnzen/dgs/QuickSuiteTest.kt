package dev.turnzen.dgs

import dev.turnzen.dgs.QuickSuite.int
import dev.turnzen.dgs.QuickSuite.string
import dev.turnzen.goserver.GoServerException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class QuickSuiteTest {
    @Test
    fun `finds the JSON object even with surrounding noise`() {
        val obj = QuickSuite.parse(Fixtures.text("envelope_with_noise.txt"))
        assertEquals(7, obj.int("id"))
        assertEquals("1.20.5:14", obj.string("version"))
    }

    @Test
    fun `empty error means success`() {
        val obj = QuickSuite.parse(Fixtures.text("move_ok.json"))
        assertEquals("", obj.string("error"))
    }

    @Test
    fun `not_logged_in becomes SessionExpired`() {
        assertFailsWith<GoServerException.SessionExpired> {
            QuickSuite.parse(
                Fixtures.text("quick_do_not_logged_in.json"),
            )
        }
    }

    @Test
    fun `already_played becomes StaleGame`() {
        assertFailsWith<GoServerException.StaleGame> { QuickSuite.parse(Fixtures.text("move_already_played.json")) }
    }

    @Test
    fun `move errors and unknown codes map to their exception types`() {
        assertIs<GoServerException.MoveRejected>(QuickSuite.exceptionFor("ko", null))
        assertIs<GoServerException.MoveRejected>(QuickSuite.exceptionFor("not_your_turn", null))
        assertIs<GoServerException.RateLimited>(QuickSuite.exceptionFor("fever_vault", null))
        assertIs<GoServerException.InvalidCredentials>(QuickSuite.exceptionFor("wrong_password", null))
        val other = QuickSuite.exceptionFor("mysql_query_failed", "boom")
        assertIs<GoServerException.ServerError>(other)
        assertEquals("mysql_query_failed", other.code)
    }

    @Test
    fun `non JSON body is a protocol error`() {
        assertFailsWith<GoServerException.Protocol> { QuickSuite.parse("<html>maintenance</html>") }
    }
}
