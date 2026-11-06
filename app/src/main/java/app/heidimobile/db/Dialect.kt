package app.heidimobile.db

/**
 * SQL text builders per engine. In HeidiSQL this knowledge lives in
 * TDBConnection.QuoteIdent / EscapeString / ApplyLimitClause (dbconnection.pas)
 * and the dbstructures.*.pas units.
 */
object Dialect {

    fun quoteIdent(engine: Engine, name: String): String = when (engine) {
        Engine.MYSQL -> "`" + name.replace("`", "``") + "`"
        else -> "\"" + name.replace("\"", "\"\"") + "\""
    }

    /** Quoted string literal, for the metadata queries (HeidiSQL's EscapeString). */
    fun literal(engine: Engine, s: String): String {
        var v = s.replace("'", "''")
        // MySQL treats backslash as escape character by default, PostgreSQL/SQLite do not.
        if (engine == Engine.MYSQL) v = v.replace("\\", "\\\\")
        return "'$v'"
    }

    fun qualified(engine: Engine, db: String, name: String): String =
        quoteIdent(engine, db) + "." + quoteIdent(engine, name)

    fun selectPage(
        engine: Engine, db: String, table: String, where: String,
        orderBy: String?, desc: Boolean, limit: Int, offset: Int
    ): String {
        val sb = StringBuilder("SELECT * FROM ").append(qualified(engine, db, table))
        if (where.isNotBlank()) sb.append(" WHERE ").append(where.trim().trimEnd(';'))
        if (orderBy != null) sb.append(" ORDER BY ").append(quoteIdent(engine, orderBy))
            .append(if (desc) " DESC" else " ASC")
        sb.append(" LIMIT ").append(limit).append(" OFFSET ").append(offset)
        return sb.toString()
    }

    fun update(engine: Engine, db: String, table: String, setCols: List<String>, pkCols: List<String>): String =
        "UPDATE " + qualified(engine, db, table) +
            " SET " + setCols.joinToString(", ") { quoteIdent(engine, it) + "=?" } +
            " WHERE " + pkCols.joinToString(" AND ") { quoteIdent(engine, it) + "=?" }

    fun delete(engine: Engine, db: String, table: String, pkCols: List<String>): String =
        "DELETE FROM " + qualified(engine, db, table) +
            " WHERE " + pkCols.joinToString(" AND ") { quoteIdent(engine, it) + "=?" }

    fun insert(engine: Engine, db: String, table: String, cols: List<String>): String {
        val q = qualified(engine, db, table)
        if (cols.isEmpty()) {
            return if (engine == Engine.MYSQL) "INSERT INTO $q () VALUES ()" else "INSERT INTO $q DEFAULT VALUES"
        }
        return "INSERT INTO $q (" + cols.joinToString(", ") { quoteIdent(engine, it) } +
            ") VALUES (" + cols.joinToString(", ") { "?" } + ")"
    }

    fun truncate(engine: Engine, db: String, table: String): String = when (engine) {
        Engine.SQLITE -> "DELETE FROM " + qualified(engine, db, table)
        else -> "TRUNCATE TABLE " + qualified(engine, db, table)
    }

    fun drop(engine: Engine, obj: DbObject): String {
        val kind = when (obj.type) {
            ObjType.TABLE -> "TABLE"; ObjType.VIEW -> "VIEW"; ObjType.PROCEDURE -> "PROCEDURE"
            ObjType.FUNCTION -> "FUNCTION"; ObjType.TRIGGER -> "TRIGGER"
        }
        return "DROP $kind " + qualified(engine, obj.db, obj.name)
    }

    fun createDatabase(engine: Engine, name: String): String? = when (engine) {
        Engine.MYSQL -> "CREATE DATABASE " + quoteIdent(engine, name)
        Engine.POSTGRES -> "CREATE SCHEMA " + quoteIdent(engine, name)
        Engine.SQLITE -> null
    }

    /** True when the statement returns rows (needed for SQLite where the API differs). */
    fun returnsRows(sql: String): Boolean = when (SqlSplitter.firstKeyword(sql)) {
        "SELECT", "PRAGMA", "WITH", "EXPLAIN", "VALUES", "SHOW", "DESCRIBE", "DESC" -> true
        else -> false
    }

    fun isDml(sql: String): Boolean = SqlSplitter.firstKeyword(sql) in setOf("INSERT", "UPDATE", "DELETE", "REPLACE")
}
