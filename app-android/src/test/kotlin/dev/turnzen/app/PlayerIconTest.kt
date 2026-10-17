package dev.turnzen.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.turnzen.app.ui.common.PlayerColors
import dev.turnzen.app.ui.common.initial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlayerIconTest {
    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun `a player keeps their colours`() {
        assertEquals(PlayerColors.of("dgs:mkvtl"), PlayerColors.of("dgs:mkvtl"))
        assertNotEquals(PlayerColors.of("dgs:mkvtl"), PlayerColors.of("ogs:mkvtl"))
    }

    @Test
    fun `backgrounds are light and letters readable on them`() {
        repeat(2000) { n ->
            val tint = PlayerColors.of("dgs:player$n")
            assertTrue(tint.background.luminance() > 0.25f, "background of player$n")
            assertTrue(contrast(tint.background, tint.letter) >= 5f, "contrast of player$n")
        }
    }

    @Test
    fun `a handful of opponents mostly get different colours`() {
        val handles = listOf("mkvtl", "hana_go", "BerndW", "kaya_r", "ricardo_m", "kaya_go", "nori_88", "tsuke_42")
        val backgrounds = handles.map { PlayerColors.of("dgs:${it.lowercase()}").background }
        assertEquals(handles.size, backgrounds.toSet().size)
    }

    @Test
    fun `the initial skips leading punctuation`() {
        assertEquals("K", initial("_kaya"))
        assertEquals("M", initial("mkvtl"))
        assertEquals("8", initial("88go"))
        assertEquals("", initial(""))
    }
}
