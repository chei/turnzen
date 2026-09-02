package dev.turnzen.game.sgf

/**
 * Minimal SGF FF[4] reader: collections, game trees, nodes, property values with
 * escapes. It keeps every property verbatim; interpretation lives in [SgfGameRecords].
 */
object SgfParser {
    fun parse(text: String): List<SgfGameTree> {
        val p = Cursor(text)
        val games = ArrayList<SgfGameTree>()
        p.skipWhitespace()
        while (!p.atEnd) {
            if (p.peek() != '(') throw SgfParseException("Expected '(' at ${p.index}")
            games += p.gameTree()
            p.skipWhitespace()
        }
        if (games.isEmpty()) throw SgfParseException("No game tree found")
        return games
    }

    fun parseFirst(text: String): SgfGameTree = parse(text).first()

    private class Cursor(
        val text: String,
    ) {
        var index = 0

        val atEnd: Boolean
            get() = index >= text.length

        fun peek(): Char = text[index]

        fun skipWhitespace() {
            while (!atEnd && text[index].isWhitespace()) index++
        }

        fun gameTree(): SgfGameTree {
            expect('(')
            val sequence = ArrayList<SgfNode>()
            skipWhitespace()
            while (!atEnd && peek() == ';') {
                sequence += node()
                skipWhitespace()
            }
            if (sequence.isEmpty()) throw SgfParseException("Game tree without nodes at $index")
            val variations = ArrayList<SgfGameTree>()
            while (!atEnd && peek() == '(') {
                variations += gameTree()
                skipWhitespace()
            }
            expect(')')
            return SgfGameTree(sequence, variations)
        }

        private fun node(): SgfNode {
            expect(';')
            val properties = LinkedHashMap<String, MutableList<String>>()
            skipWhitespace()
            while (!atEnd && peek().isLetter()) {
                val id = identifier()
                skipWhitespace()
                if (atEnd || peek() != '[') throw SgfParseException("Property $id without value at $index")
                val values = properties.getOrPut(id) { ArrayList() }
                while (!atEnd && peek() == '[') {
                    values += value()
                    skipWhitespace()
                }
            }
            return SgfNode(properties)
        }

        private fun identifier(): String {
            val start = index
            while (!atEnd && peek().isLetter()) index++
            // FF[3] allowed lowercase letters in identifiers; FF[4] says to drop them.
            return text.substring(start, index).filter { it.isUpperCase() }
        }

        private fun value(): String {
            expect('[')
            val sb = StringBuilder()
            while (true) {
                if (atEnd) throw SgfParseException("Unterminated property value")
                val c = text[index++]
                when (c) {
                    '\\' -> {
                        if (atEnd) throw SgfParseException("Dangling escape")
                        val escaped = text[index++]
                        when {
                            escaped == '\n' -> Unit
                            escaped == '\r' -> if (!atEnd && text[index] == '\n') index++
                            else -> sb.append(escaped)
                        }
                    }

                    ']' -> {
                        return sb.toString()
                    }

                    else -> {
                        sb.append(c)
                    }
                }
            }
        }

        private fun expect(c: Char) {
            skipWhitespace()
            if (atEnd || text[index] != c) {
                throw SgfParseException(
                    "Expected '$c' at $index" + if (atEnd) " (end of input)" else " but found '${text[index]}'",
                )
            }
            index++
        }
    }
}
