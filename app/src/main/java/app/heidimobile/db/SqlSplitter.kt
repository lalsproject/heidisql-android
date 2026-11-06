package app.heidimobile.db

/**
 * Splits a script into single statements, respecting quotes, comments, MySQL's DELIMITER
 * command and PostgreSQL dollar quoting. (HeidiSQL: TDBConnection.SplitSqlBatch / apphelpers.)
 */
object SqlSplitter {

    fun split(sql: String, engine: Engine): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var hasContent = false
        var delimiter = ";"
        val mysql = engine == Engine.MYSQL
        val n = sql.length
        var i = 0

        fun flush() {
            if (hasContent) {
                val s = cur.toString().trim()
                if (s.isNotEmpty()) out.add(s)
            }
            cur.setLength(0)
            hasContent = false
        }

        while (i < n) {
            val c = sql[i]

            // DELIMITER command (MySQL client convention, also understood by HeidiSQL)
            if (mysql && !hasContent && (c == 'D' || c == 'd') &&
                sql.regionMatches(i, "DELIMITER", 0, 9, ignoreCase = true) &&
                i + 9 < n && (sql[i + 9] == ' ' || sql[i + 9] == '\t')
            ) {
                var eol = sql.indexOf('\n', i)
                if (eol < 0) eol = n
                val token = sql.substring(i + 9, eol).trim()
                if (token.isNotEmpty()) delimiter = token
                cur.setLength(0)
                i = eol
                continue
            }

            if (sql.startsWith(delimiter, i)) {
                flush()
                i += delimiter.length
                continue
            }

            when {
                c == '\'' || c == '"' || c == '`' -> {
                    hasContent = true
                    cur.append(c); i++
                    while (i < n) {
                        val ch = sql[i]
                        cur.append(ch); i++
                        if (ch == '\\' && mysql && c != '`' && i < n) {
                            cur.append(sql[i]); i++
                        } else if (ch == c) {
                            if (i < n && sql[i] == c) { cur.append(c); i++ } else break
                        }
                    }
                }
                c == '-' && i + 1 < n && sql[i + 1] == '-' &&
                    (!mysql || i + 2 >= n || sql[i + 2].isWhitespace()) -> {
                    val eol = sql.indexOf('\n', i).let { if (it < 0) n else it }
                    cur.append(sql, i, eol); i = eol
                }
                c == '#' && mysql -> {
                    val eol = sql.indexOf('\n', i).let { if (it < 0) n else it }
                    cur.append(sql, i, eol); i = eol
                }
                c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                    val close = sql.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
                    // MySQL "executable comments" (/*!40101 ... */) carry real statements
                    if (i + 2 < n && sql[i + 2] == '!') hasContent = true
                    cur.append(sql, i, end); i = end
                }
                c == '$' && engine == Engine.POSTGRES -> {
                    val tagEnd = dollarTagEnd(sql, i)
                    if (tagEnd < 0) {
                        hasContent = true; cur.append(c); i++
                    } else {
                        val tag = sql.substring(i, tagEnd)
                        val close = sql.indexOf(tag, tagEnd)
                        val end = if (close < 0) n else close + tag.length
                        hasContent = true
                        cur.append(sql, i, end); i = end
                    }
                }
                else -> {
                    if (!c.isWhitespace()) hasContent = true
                    cur.append(c); i++
                }
            }
        }
        flush()
        return out
    }

    /** Index right after a `$tag$` opener starting at [start], or -1 when it is not one. */
    private fun dollarTagEnd(s: String, start: Int): Int {
        var j = start + 1
        while (j < s.length && (s[j].isLetter() || s[j] == '_' || (j > start + 1 && s[j].isDigit()))) j++
        return if (j < s.length && s[j] == '$') j + 1 else -1
    }

    /** First SQL keyword of a statement, upper-cased, skipping whitespace and comments. */
    fun firstKeyword(sql: String): String {
        var i = 0
        val n = sql.length
        while (i < n) {
            val c = sql[i]
            when {
                c.isWhitespace() || c == '(' -> i++
                c == '-' && i + 1 < n && sql[i + 1] == '-' -> i = sql.indexOf('\n', i).let { if (it < 0) n else it }
                c == '#' -> i = sql.indexOf('\n', i).let { if (it < 0) n else it }
                c == '/' && i + 1 < n && sql[i + 1] == '*' -> i = sql.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                else -> {
                    var j = i
                    while (j < n && sql[j].isLetter()) j++
                    return sql.substring(i, j).uppercase()
                }
            }
        }
        return ""
    }
}
