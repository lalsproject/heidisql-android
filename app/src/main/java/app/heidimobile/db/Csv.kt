package app.heidimobile.db

/** CSV export of a grid (HeidiSQL: exportgrid.pas, "CSV" format, RFC 4180 quoting). */
object Csv {
    fun format(result: QueryResult, nullText: String = "", separator: Char = ','): String {
        val sb = StringBuilder()
        fun cell(v: String?) {
            val s = v ?: nullText
            if (s.any { it == separator || it == '"' || it == '\n' || it == '\r' }) {
                sb.append('"').append(s.replace("\"", "\"\"")).append('"')
            } else sb.append(s)
        }
        result.columns.forEachIndexed { i, c -> if (i > 0) sb.append(separator); cell(c) }
        sb.append("\r\n")
        for (row in result.rows) {
            row.forEachIndexed { i, v -> if (i > 0) sb.append(separator); cell(v) }
            sb.append("\r\n")
        }
        return sb.toString()
    }
}
