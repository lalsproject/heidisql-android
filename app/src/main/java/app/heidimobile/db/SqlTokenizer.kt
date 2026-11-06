package app.heidimobile.db

/**
 * Single-pass, non-recursive SQL tokenizer used for syntax highlighting.
 * (A regex version overflowed the stack on long string literals.)
 */
object SqlTokenizer {
    enum class Kind { COMMENT, STRING, NUMBER, WORD }

    data class Token(val start: Int, val end: Int, val kind: Kind)

    fun tokenize(s: String): List<Token> {
        val out = ArrayList<Token>()
        val n = s.length
        var i = 0
        while (i < n) {
            val c = s[i]
            when {
                (c == '-' && i + 1 < n && s[i + 1] == '-') || c == '#' -> {
                    val eol = s.indexOf('\n', i).let { if (it < 0) n else it }
                    out.add(Token(i, eol, Kind.COMMENT)); i = eol
                }
                c == '/' && i + 1 < n && s[i + 1] == '*' -> {
                    val close = s.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
                    out.add(Token(i, end, Kind.COMMENT)); i = end
                }
                c == '\'' || c == '"' || c == '`' -> {
                    var j = i + 1
                    while (j < n) {
                        val ch = s[j]
                        if (ch == '\\' && c != '`') { j += 2; continue }
                        if (ch == c) {
                            if (j + 1 < n && s[j + 1] == c) { j += 2; continue }
                            j++
                            break
                        }
                        j++
                    }
                    val end = minOf(j, n)
                    out.add(Token(i, end, Kind.STRING)); i = end
                }
                c.isDigit() -> {
                    var j = i + 1
                    while (j < n && s[j].isDigit()) j++
                    if (j + 1 < n && s[j] == '.' && s[j + 1].isDigit()) {
                        j++
                        while (j < n && s[j].isDigit()) j++
                    }
                    out.add(Token(i, j, Kind.NUMBER)); i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i + 1
                    while (j < n && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                    out.add(Token(i, j, Kind.WORD)); i = j
                }
                else -> i++
            }
        }
        return out
    }
}
