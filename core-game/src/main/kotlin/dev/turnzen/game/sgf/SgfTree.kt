package dev.turnzen.game.sgf

class SgfParseException(
    message: String,
) : RuntimeException(message)

data class SgfNode(
    val properties: Map<String, List<String>>,
) {
    fun first(id: String): String? = properties[id]?.firstOrNull()

    fun values(id: String): List<String> = properties[id] ?: emptyList()

    operator fun contains(id: String): Boolean = id in properties
}

data class SgfGameTree(
    val sequence: List<SgfNode>,
    val variations: List<SgfGameTree>,
) {
    /** The nodes along the first variation at every branch. */
    val mainLine: List<SgfNode>
        get() = sequence + (variations.firstOrNull()?.mainLine ?: emptyList())
}
