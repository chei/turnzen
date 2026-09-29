package dev.turnzen.dgs

import dev.turnzen.game.StoneColor
import dev.turnzen.game.sgf.SgfGameRecords
import dev.turnzen.goserver.CommentVisibility
import dev.turnzen.goserver.GameComment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DgsCommentsTest {
    private val running = SgfGameRecords.read(Fixtures.text("game_1000001_comments.sgf"))

    @Test
    fun `messages are read per move, with their audiences apart`() {
        val comments = DgsComments.parse(running, StoneColor.BLACK)
        assertEquals(
            listOf(
                // The handicap setup's message sits on the root, and only black sends one.
                GameComment(0, StoneColor.BLACK, "Onegaishimasu!"),
                GameComment(1, StoneColor.WHITE, "Hello from the white side.\nGood luck"),
                GameComment(1, StoneColor.WHITE, "Nice opening everyone", CommentVisibility.EVERYONE),
                GameComment(2, StoneColor.BLACK, "I think this is joseki", CommentVisibility.HIDDEN_UNTIL_END),
                GameComment(4, StoneColor.BLACK, "thanks"),
                GameComment(4, StoneColor.BLACK, "she will not see this yet", CommentVisibility.SECRET_UNTIL_END),
                GameComment(4, StoneColor.BLACK, "for the game"),
                GameComment(5, StoneColor.WHITE, "looks close", CommentVisibility.EVERYONE),
            ),
            comments,
        )
    }

    @Test
    fun `the viewer's own game notes are not a message`() {
        val comments = DgsComments.parse(running, StoneColor.BLACK)
        assertTrue(comments.none { "ko at e3" in it.text || "Notes" in it.text })
    }

    @Test
    fun `notes are cut only under the viewer's own name`() {
        // White's notes would never be sent to black; a message that merely looks like
        // someone else's notes header is left alone.
        val comments = DgsComments.parse(running, StoneColor.WHITE)
        assertTrue(comments.any { "Notes - Fake User (fakeuser):" in it.text })
    }

    @Test
    fun `the result node of a finished game is the server talking`() {
        val finished = SgfGameRecords.read(Fixtures.text("game_1000000.sgf"))
        assertEquals(emptyList(), DgsComments.parse(finished, StoneColor.BLACK))
    }

    @Test
    fun `a message after the result is only kept when it is not the server's count`() {
        val text =
            "(;SZ[9]RE[W+R]PB[B (b)]PW[W (w)];B[aa];W[bb]C[good game];B[]C[Result: W+R\n\n" +
                "Notes - B (b):\nlost it])"
        val comments = DgsComments.parse(SgfGameRecords.read(text), StoneColor.BLACK)
        assertEquals(listOf(GameComment(2, StoneColor.WHITE, "good game")), comments)
    }

    @Test
    fun `root comments of an even game describe the game, not a player`() {
        val text = "(;SZ[9]C[Shape-Game Setup#sgf: 12];B[aa]C[hi])"
        val comments = DgsComments.parse(SgfGameRecords.read(text), StoneColor.WHITE)
        assertEquals(listOf(GameComment(1, StoneColor.BLACK, "hi")), comments)
    }

    @Test
    fun `tags take either spelling and any case`() {
        assertEquals(
            listOf(
                CommentVisibility.EVERYONE to "a",
                CommentVisibility.HIDDEN_UNTIL_END to "b",
                CommentVisibility.SECRET_UNTIL_END to "c",
                CommentVisibility.PLAYERS to "d",
            ),
            DgsComments.parts("<Comment>a</comment> <HIDDEN >b</h> <mysecret>c</mysecret> d"),
        )
    }
}
