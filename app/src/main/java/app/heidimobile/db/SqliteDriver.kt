package app.heidimobile.db

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Port of TSQLiteConnection. Uses the SQLite that ships with Android.
 *
 * Deliberate differences from HeidiSQL, because Android 7 ships SQLite 3.9:
 *  - table-valued pragma functions (pragma_table_xinfo, pragma_database_list) need SQLite >= 3.16,
 *    so the classic "PRAGMA table_info(...)" / "PRAGMA database_list" statements are used instead.
 */
class SqliteDriver(file: File) : DbDriver {

    override val engine: Engine = Engine.SQLITE
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
    )

    init {
        try {
            // Reads the file header; fails right away for files that are not SQLite databases.
            DatabaseUtils.longForQuery(db, "PRAGMA schema_version", null)
        } catch (e: Exception) {
            db.close()
            throw e
        }
    }

    private fun q(s: String) = Dialect.quoteIdent(Engine.SQLITE, s)
    private fun lit(s: String) = Dialect.literal(Engine.SQLITE, s)

    private fun rows(sql: String): List<Map<String, String?>> =
        db.rawQuery(sql, null).use { c ->
            val names = c.columnNames.map { it.lowercase() }
            val out = ArrayList<Map<String, String?>>()
            while (c.moveToNext()) {
                val m = LinkedHashMap<String, String?>()
                names.forEachIndexed { i, n -> m[n] = if (c.isNull(i)) null else c.getString(i) }
                out.add(m)
            }
            out
        }

    override fun serverVersion(): String =
        "SQLite " + DatabaseUtils.stringForQuery(db, "SELECT sqlite_version()", null)

    override fun databases(): List<String> =
        rows("PRAGMA database_list").mapNotNull { it["name"] }

    override fun useDatabase(db: String) { /* schemas are addressed by qualified names */ }

    override fun objects(db: String): List<DbObject> =
        rows(
            "SELECT name, type FROM ${q(db)}.sqlite_master " +
                "WHERE type IN ('table','view','trigger') AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name"
        ).mapNotNull {
            val t = when (it["type"]) {
                "view" -> ObjType.VIEW
                "trigger" -> ObjType.TRIGGER
                else -> ObjType.TABLE
            }
            DbObject(db, it["name"] ?: return@mapNotNull null, t)
        }

    override fun columns(obj: DbObject): List<ColumnInfo> =
        rows("PRAGMA ${q(obj.db)}.table_info(${q(obj.name)})").map {
            val pk = (it["pk"]?.toIntOrNull() ?: 0) > 0
            ColumnInfo(
                name = it["name"] ?: "", type = it["type"] ?: "",
                nullable = it["notnull"] != "1", key = if (pk) "PRI" else "",
                default = it["dflt_value"], isPk = pk
            )
        }

    override fun indexes(obj: DbObject): List<IndexInfo> =
        rows("PRAGMA ${q(obj.db)}.index_list(${q(obj.name)})").map { r ->
            val name = r["name"] ?: ""
            val cols = rows("PRAGMA ${q(obj.db)}.index_info(${q(name)})").map { it["name"] ?: "(expr)" }
            val origin = when (r["origin"]) { "pk" -> "primary key"; "u" -> "unique constraint"; "c" -> "created"; else -> "" }
            IndexInfo(name, r["unique"] == "1", cols, origin)
        }

    override fun createCode(obj: DbObject): String {
        val where = if (obj.type == ObjType.TABLE)
            // the table itself first, then its indexes and triggers
            "tbl_name=${lit(obj.name)} AND sql IS NOT NULL ORDER BY (type='table') DESC, name"
        else
            "name=${lit(obj.name)} AND type=${lit(obj.type.name.lowercase())} AND sql IS NOT NULL"
        return rows("SELECT sql FROM ${q(obj.db)}.sqlite_master WHERE $where")
            .mapNotNull { it["sql"] }.joinToString(";\n\n") + ";"
    }

    override fun execute(sql: String, maxRows: Int): QueryResult {
        val t0 = System.nanoTime()
        if (Dialect.returnsRows(sql)) {
            db.rawQuery(sql, null).use { c ->
                val n = c.columnCount
                val cols = c.columnNames.toList()
                val data = ArrayList<List<String?>>()
                var truncated = false
                while (c.moveToNext()) {
                    if (data.size >= maxRows) { truncated = true; break }
                    data.add(List(n) { i ->
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_BLOB -> ValueFormat.bytes(c.getBlob(i))
                            else -> c.getString(i)
                        }
                    })
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                // PRAGMA statements without output produce no columns: report them as plain "OK".
                return QueryResult(cols, data, truncated = truncated, elapsedMs = ms)
            }
        }
        db.execSQL(sql)
        val affected = if (Dialect.isDml(sql)) DatabaseUtils.longForQuery(db, "SELECT changes()", null) else 0L
        return QueryResult(emptyList(), emptyList(), affected = affected, elapsedMs = (System.nanoTime() - t0) / 1_000_000)
    }

    override fun executeUpdate(sql: String, params: List<String?>): Long {
        val args = arrayOfNulls<Any>(params.size)
        params.forEachIndexed { i, v -> args[i] = v }
        db.execSQL(sql, args)
        return DatabaseUtils.longForQuery(db, "SELECT changes()", null)
    }

    override fun close() {
        try { db.close() } catch (_: Exception) {}
    }
}
