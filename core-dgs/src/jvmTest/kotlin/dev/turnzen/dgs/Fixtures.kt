package dev.turnzen.dgs

internal object Fixtures {
    fun text(name: String): String =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/dgs/$name")) { "Missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }
}
