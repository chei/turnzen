package dev.turnzen.ogs

import kotlinx.serialization.json.JsonObject

internal object Fixtures {
    /** The test account's player id in every fixture (recorded games, scrubbed). */
    const val ME = 1001L

    fun text(name: String): String =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/ogs/$name")) { "Missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }

    fun obj(name: String): JsonObject = OgsJson.parseObject(text(name), name)
}
