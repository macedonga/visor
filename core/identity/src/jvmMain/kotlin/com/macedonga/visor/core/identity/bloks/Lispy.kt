package com.macedonga.visor.core.identity.bloks

/** A Bloks script: `(e24 (e1w "a" 1))`, where each head is a function id in base 36. */
sealed interface Expr

/** A call to function [fn], or to the payload's own script [local] when the head is `#id`. */
class Call(val fn: Int, val args: List<Expr>, val local: String? = null) : Expr {
    override fun toString() = "(${local?.let { "#$it" } ?: Functions.name(fn)} ${args.joinToString(" ")})"
}

/** A string, an Int, a Long, a Double, a Boolean or null. */
class Lit(val value: Any?) : Expr {
    override fun toString() = if (value is String) "\"$value\"" else value.toString()
}

object Lispy {
    /** A string attribute holds a script when it starts with a tab and an opening parenthesis. */
    fun isScript(text: String): Boolean = text.startsWith("\t(")

    fun parse(source: String): Expr = Reader(source).run {
        skipSpace()
        val expr = read()
        skipSpace()
        require(at >= text.length) { "trailing text at $at" }
        expr
    }

    private class Reader(val text: String) {
        var at = 0

        private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c == '\u000c' || c == '\uFEFF'

        fun skipSpace() {
            while (at < text.length && isSpace(text[at])) at++
        }

        fun read(): Expr {
            skipSpace()
            require(at < text.length) { "unexpected end" }
            return when (text[at]) {
                '(' -> readCall()
                '"', '\'' -> Lit(readString(text[at]))
                else -> atom(readAtom())
            }
        }

        private fun readCall(): Call {
            at++
            skipSpace()
            val head = readAtom()
            val args = ArrayList<Expr>()
            while (true) {
                skipSpace()
                require(at < text.length) { "unclosed call to $head" }
                if (text[at] == ')') {
                    at++
                    return when {
                        head.startsWith("#") -> Call(-1, args, head.substring(1))
                        head == "null" || '.' in head -> Call(Functions.id(head), args)
                        else -> Call(head.toInt(36), args)
                    }
                }
                args += read()
            }
        }

        private fun readAtom(): String {
            val start = at
            while (at < text.length && !isSpace(text[at]) && text[at] != '(' && text[at] != ')') at++
            require(at > start) { "empty atom at $start" }
            return text.substring(start, at)
        }

        private fun readString(quote: Char): String {
            val out = StringBuilder()
            at++
            while (true) {
                require(at < text.length) { "unclosed string" }
                val c = text[at++]
                when {
                    c == quote -> return out.toString()
                    c == '\\' -> when (val e = text[at++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'u' -> {
                            out.append(text.substring(at, at + 4).toInt(16).toChar())
                            at += 4
                        }
                        else -> out.append(e)
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun atom(word: String): Lit = Lit(
            when {
                word == "null" -> null
                word == "true" -> true
                word == "false" -> false
                '.' in word || 'e' in word || 'E' in word -> word.toDoubleOrNull() ?: word
                else -> word.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: word
            },
        )
    }
}

/** Function ids and names of the Meta AI 294 runtime. */
object Functions {
    private val byId: Map<Int, String>
    private val byName: Map<String, Int>

    init {
        val lines = Functions::class.java.getResourceAsStream("/bloks/functions.txt")!!.bufferedReader().readLines()
        byId = lines.filter { it.isNotBlank() }.associate { line -> line.substringBefore(' ').toInt() to line.substringAfter(' ') }
        byName = byId.entries.associate { (id, name) -> name to id }
    }

    fun name(id: Int): String = byId[id] ?: "#$id"

    fun id(name: String): Int = byName[name] ?: throw IllegalArgumentException("unknown function $name")
}
