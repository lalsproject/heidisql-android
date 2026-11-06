package app.heidimobile.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.util.Properties

/**
 * MySQL/MariaDB (via MariaDB Connector/J, which also talks to MySQL servers) and PostgreSQL.
 * The metadata queries are the ones HeidiSQL itself uses in dbconnection.pas
 * (SHOW DATABASES, SHOW FULL COLUMNS, SHOW KEYS, SHOW CREATE ..., pg_namespace, ...).
 */
class JdbcDriver(
    private val session: Session,
    host: String,
    port: Int
) : DbDriver {

    override val engine: Engine = session.engine
    private val conn: Connection
    @Volatile private var running: Statement? = null

    init {
        require(engine != Engine.SQLITE) { "JdbcDriver does not handle SQLite" }
        val props = Properties()
        props["user"] = session.user
        props["password"] = session.password
        val url: String
        if (engine == Engine.MYSQL) {
            Class.forName("org.mariadb.jdbc.Driver")
            url = "jdbc:mariadb://$host:$port/${session.database}"
            props["connectTimeout"] = "10000"
            if (session.useSsl) {
                props["useSsl"] = "true"
                props["trustServerCertificate"] = "true"
            }
        } else {
            Class.forName("org.postgresql.Driver")
            url = "jdbc:postgresql://$host:$port/${session.database.ifBlank { "postgres" }}"
            props["connectTimeout"] = "10"
            // Let the server infer parameter types, so editing int/date columns from text works.
            props["stringtype"] = "unspecified"
            if (session.useSsl) {
                props["ssl"] = "true"
                props["sslfactory"] = "org.postgresql.ssl.NonValidatingFactory"
            }
        }
        DriverManager.setLoginTimeout(15)
        conn = DriverManager.getConnection(url, props)
    }

    private val mysql get() = engine == Engine.MYSQL
    private fun q(s: String) = Dialect.quoteIdent(engine, s)
    private fun lit(s: String) = Dialect.literal(engine, s)

    // ---- generic helpers --------------------------------------------------------------------

    /** Rows as maps with lower-cased column labels, so MySQL 5/8 label casing does not matter. */
    private fun rows(sql: String): List<Map<String, String?>> {
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val md = rs.metaData
                val labels = (1..md.columnCount).map { md.getColumnLabel(it).lowercase() }
                val out = ArrayList<Map<String, String?>>()
                while (rs.next()) {
                    val m = LinkedHashMap<String, String?>()
                    labels.forEachIndexed { i, l -> m[l] = rs.getString(i + 1) }
                    out.add(m)
                }
                return out
            }
        }
    }

    private fun firstCol(sql: String): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val l = ArrayList<String>()
                while (rs.next()) rs.getString(1)?.let { l.add(it) }
                l
            }
        }

    // ---- DbDriver ---------------------------------------------------------------------------

    override fun serverVersion(): String = conn.metaData.databaseProductName + " " + conn.metaData.databaseProductVersion

    override fun databases(): List<String> {
        if (mysql) {
            return try {
                firstCol("SHOW DATABASES")
            } catch (e: SQLException) {
                try {
                    firstCol("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME")
                } catch (e2: SQLException) { emptyList() }
            }
        }
        // PostgreSQL: HeidiSQL lists schemata, not databases (TPgConnection.GetAllDatabases)
        return firstCol(
            "SELECT nspname FROM pg_catalog.pg_namespace " +
                "WHERE nspname NOT LIKE 'pg\\_toast%' AND nspname NOT LIKE 'pg\\_temp%' ORDER BY nspname"
        )
    }

    override fun useDatabase(db: String) {
        if (mysql) conn.createStatement().use { it.execute("USE " + q(db)) }
        else conn.createStatement().use { it.execute("SET search_path TO " + q(db)) }
    }

    override fun objects(db: String): List<DbObject> = if (mysql) mysqlObjects(db) else pgObjects(db)

    private fun mysqlObjects(db: String): List<DbObject> {
        val list = ArrayList<DbObject>()
        try {
            for (r in rows(
                "SELECT TABLE_NAME, TABLE_TYPE, ENGINE, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, TABLE_COMMENT " +
                    "FROM information_schema.TABLES WHERE TABLE_SCHEMA=${lit(db)} ORDER BY TABLE_NAME"
            )) {
                val isView = r["table_type"]?.contains("VIEW") == true
                list.add(
                    DbObject(
                        db, r["table_name"] ?: continue, if (isView) ObjType.VIEW else ObjType.TABLE,
                        rows = r["table_rows"]?.toLongOrNull(),
                        sizeBytes = (r["data_length"]?.toLongOrNull() ?: 0L) + (r["index_length"]?.toLongOrNull() ?: 0L),
                        engineName = r["engine"],
                        comment = r["table_comment"]?.takeIf { it.isNotBlank() && it != "VIEW" }
                    )
                )
            }
        } catch (e: SQLException) {
            // Fallback used by HeidiSQL for old servers / restricted privileges
            for (r in rows("SHOW FULL TABLES FROM ${q(db)}")) {
                val name = r.values.firstOrNull() ?: continue
                val isView = r["table_type"]?.contains("VIEW") == true
                list.add(DbObject(db, name, if (isView) ObjType.VIEW else ObjType.TABLE))
            }
        }
        runCatching {
            for (r in rows("SELECT ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=${lit(db)} ORDER BY ROUTINE_NAME")) {
                val t = if (r["routine_type"].equals("PROCEDURE", true)) ObjType.PROCEDURE else ObjType.FUNCTION
                list.add(DbObject(db, r["routine_name"] ?: continue, t))
            }
        }
        runCatching {
            for (r in rows("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=${lit(db)} ORDER BY TRIGGER_NAME")) {
                list.add(DbObject(db, r["trigger_name"] ?: continue, ObjType.TRIGGER))
            }
        }
        return list
    }

    private fun pgObjects(db: String): List<DbObject> {
        val list = ArrayList<DbObject>()
        for (r in rows(
            "SELECT c.relname, c.relkind, c.reltuples::bigint AS est_rows, pg_total_relation_size(c.oid) AS total_size, " +
                "obj_description(c.oid, 'pg_class') AS cmt " +
                "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname=${lit(db)} AND c.relkind IN ('r','p','v','m','f') ORDER BY c.relname"
        )) {
            val view = r["relkind"] == "v" || r["relkind"] == "m"
            list.add(
                DbObject(
                    db, r["relname"] ?: continue, if (view) ObjType.VIEW else ObjType.TABLE,
                    rows = r["est_rows"]?.toLongOrNull()?.takeIf { it >= 0 },
                    sizeBytes = r["total_size"]?.toLongOrNull(),
                    comment = r["cmt"]
                )
            )
        }
        runCatching {
            // prokind exists since PostgreSQL 11 ('f' function, 'p' procedure)
            for (r in rows("SELECT DISTINCT p.proname, p.prokind FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=${lit(db)} AND p.prokind IN ('f','p') ORDER BY p.proname")) {
                val t = if (r["prokind"] == "p") ObjType.PROCEDURE else ObjType.FUNCTION
                list.add(DbObject(db, r["proname"] ?: continue, t))
            }
        }.onFailure {
            runCatching {
                for (r in rows("SELECT DISTINCT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=${lit(db)} ORDER BY p.proname")) {
                    list.add(DbObject(db, r["proname"] ?: continue, ObjType.FUNCTION))
                }
            }
        }
        runCatching {
            for (r in rows(
                "SELECT DISTINCT t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid " +
                    "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE NOT t.tgisinternal AND n.nspname=${lit(db)} ORDER BY t.tgname"
            )) list.add(DbObject(db, r["tgname"] ?: continue, ObjType.TRIGGER))
        }
        return list
    }

    override fun columns(obj: DbObject): List<ColumnInfo> {
        if (mysql) {
            return rows("SHOW FULL COLUMNS FROM ${Dialect.qualified(engine, obj.db, obj.name)}").map {
                ColumnInfo(
                    name = it["field"] ?: "", type = it["type"] ?: "",
                    nullable = it["null"] == "YES", key = it["key"] ?: "",
                    default = it["default"], extra = it["extra"] ?: "",
                    comment = it["comment"] ?: "", isPk = it["key"] == "PRI"
                )
            }
        }
        return rows(
            "SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS typ, " +
                "CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END AS nullable, " +
                "pg_get_expr(d.adbin, d.adrelid) AS def, col_description(a.attrelid, a.attnum) AS cmt, " +
                "CASE WHEN i.indisprimary IS TRUE THEN 'PRI' ELSE '' END AS keycol " +
                "FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace " +
                "LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum " +
                "LEFT JOIN pg_index i ON i.indrelid=a.attrelid AND i.indisprimary AND a.attnum = ANY(i.indkey) " +
                "WHERE n.nspname=${lit(obj.db)} AND c.relname=${lit(obj.name)} AND a.attnum > 0 AND NOT a.attisdropped " +
                "ORDER BY a.attnum"
        ).map {
            ColumnInfo(
                name = it["attname"] ?: "", type = it["typ"] ?: "", nullable = it["nullable"] == "YES",
                key = it["keycol"] ?: "", default = it["def"],
                extra = if (it["def"]?.startsWith("nextval(") == true) "auto_increment" else "",
                comment = it["cmt"] ?: "", isPk = it["keycol"] == "PRI"
            )
        }
    }

    override fun indexes(obj: DbObject): List<IndexInfo> {
        if (mysql) {
            val groups = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
            val unique = HashMap<String, Boolean>()
            val type = HashMap<String, String>()
            for (r in rows("SHOW KEYS FROM ${q(obj.name)} FROM ${q(obj.db)}")) {
                val n = r["key_name"] ?: continue
                groups.getOrPut(n) { ArrayList() }.add((r["seq_in_index"]?.toIntOrNull() ?: 0) to (r["column_name"] ?: r["expression"] ?: "(expr)"))
                unique[n] = r["non_unique"] == "0"
                type[n] = r["index_type"] ?: ""
            }
            return groups.map { (n, cols) ->
                IndexInfo(n, unique[n] == true, cols.sortedBy { it.first }.map { it.second }, type[n] ?: "")
            }
        }
        val groups = LinkedHashMap<String, MutableList<String>>()
        val unique = HashMap<String, Boolean>()
        val type = HashMap<String, String>()
        for (r in rows(
            "SELECT i.relname AS iname, CASE WHEN ix.indisunique THEN '1' ELSE '0' END AS uniq, " +
                "CASE WHEN ix.indisprimary THEN ' (primary)' ELSE '' END AS prim, am.amname, a.attname, k.ord " +
                "FROM pg_index ix JOIN pg_class t ON t.oid=ix.indrelid JOIN pg_class i ON i.oid=ix.indexrelid " +
                "JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_am am ON am.oid=i.relam " +
                "CROSS JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord) " +
                "LEFT JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum " +
                "WHERE n.nspname=${lit(obj.db)} AND t.relname=${lit(obj.name)} ORDER BY i.relname, k.ord"
        )) {
            val n = r["iname"] ?: continue
            groups.getOrPut(n) { ArrayList() }.add(r["attname"] ?: "(expr)")
            unique[n] = r["uniq"] == "1"
            type[n] = (r["amname"] ?: "") + (r["prim"] ?: "")
        }
        return groups.map { (n, cols) -> IndexInfo(n, unique[n] == true, cols, type[n] ?: "") }
    }

    override fun createCode(obj: DbObject): String {
        if (mysql) {
            val kind = obj.type.name
            val r = rows("SHOW CREATE $kind ${Dialect.qualified(engine, obj.db, obj.name)}").firstOrNull()
                ?: return ""
            // The column holding the code is called "Create Table", "Create View", "Create Procedure",
            // "Create Function" or "SQL Original Statement" (triggers).
            return r.entries.firstOrNull { it.key.startsWith("create ") || it.key == "sql original statement" }?.value ?: ""
        }
        return when (obj.type) {
            ObjType.VIEW -> "CREATE OR REPLACE VIEW ${Dialect.qualified(engine, obj.db, obj.name)} AS\n" +
                (rows("SELECT pg_get_viewdef(c.oid, true) AS d FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace " +
                    "WHERE n.nspname=${lit(obj.db)} AND c.relname=${lit(obj.name)}").firstOrNull()?.get("d") ?: "")
            ObjType.FUNCTION, ObjType.PROCEDURE ->
                rows("SELECT pg_get_functiondef(p.oid) AS d FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace " +
                    "WHERE n.nspname=${lit(obj.db)} AND p.proname=${lit(obj.name)} LIMIT 1").firstOrNull()?.get("d") ?: ""
            ObjType.TRIGGER ->
                rows("SELECT pg_get_triggerdef(t.oid) AS d FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid " +
                    "JOIN pg_namespace n ON n.oid=c.relnamespace WHERE NOT t.tgisinternal AND n.nspname=${lit(obj.db)} " +
                    "AND t.tgname=${lit(obj.name)} LIMIT 1").firstOrNull()?.get("d") ?: ""
            ObjType.TABLE -> pgTableCode(obj)
        }
    }

    /** PostgreSQL has no SHOW CREATE TABLE; HeidiSQL assembles it, so do we (simplified). */
    private fun pgTableCode(obj: DbObject): String {
        val cols = columns(obj)
        val sb = StringBuilder("CREATE TABLE ${Dialect.qualified(engine, obj.db, obj.name)} (\n")
        val parts = ArrayList<String>()
        for (c in cols) {
            val b = StringBuilder("  ${q(c.name)} ${c.type}")
            if (!c.nullable) b.append(" NOT NULL")
            if (c.default != null) b.append(" DEFAULT ").append(c.default)
            parts.add(b.toString())
        }
        val pk = cols.filter { it.isPk }.map { q(it.name) }
        if (pk.isNotEmpty()) parts.add("  PRIMARY KEY (${pk.joinToString(", ")})")
        sb.append(parts.joinToString(",\n")).append("\n);")
        for (r in rows(
            "SELECT indexdef FROM pg_indexes WHERE schemaname=${lit(obj.db)} AND tablename=${lit(obj.name)} " +
                "AND indexname NOT IN (SELECT conname FROM pg_constraint WHERE contype IN ('p','u')) ORDER BY indexname"
        )) sb.append("\n").append(r["indexdef"]).append(";")
        return sb.toString()
    }

    override fun execute(sql: String, maxRows: Int): QueryResult {
        val t0 = System.nanoTime()
        conn.createStatement().use { st ->
            running = st
            try {
                st.maxRows = maxRows + 1
                val hasRs = st.execute(sql)
                val ms = (System.nanoTime() - t0) / 1_000_000
                return if (hasRs) readResult(st.resultSet, maxRows, ms)
                else QueryResult(emptyList(), emptyList(), affected = st.updateCount.toLong(), elapsedMs = ms)
            } finally {
                running = null
            }
        }
    }

    private fun readResult(rs: ResultSet, maxRows: Int, ms: Long): QueryResult {
        rs.use {
            val md = rs.metaData
            val n = md.columnCount
            val cols = (1..n).map { md.getColumnLabel(it) }
            val binary = BooleanArray(n) {
                md.getColumnType(it + 1) in intArrayOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB)
            }
            val data = ArrayList<List<String?>>()
            var truncated = false
            while (rs.next()) {
                if (data.size >= maxRows) { truncated = true; break }
                data.add(List(n) { i ->
                    if (binary[i]) ValueFormat.bytes(rs.getBytes(i + 1)) else rs.getString(i + 1)
                })
            }
            return QueryResult(cols, data, truncated = truncated, elapsedMs = ms)
        }
    }

    override fun executeUpdate(sql: String, params: List<String?>): Long =
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, v -> if (v == null) ps.setNull(i + 1, Types.NULL) else ps.setString(i + 1, v) }
            ps.executeUpdate().toLong()
        }

    override fun cancel() {
        try { running?.cancel() } catch (_: SQLException) {}
    }

    override fun close() {
        try { conn.close() } catch (_: SQLException) {}
    }
}
