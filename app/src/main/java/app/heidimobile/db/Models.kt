package app.heidimobile.db

/**
 * Data model of the port. Mirrors the concepts of HeidiSQL's source/dbconnection.pas:
 *   TNetType/TNetTypeGroup -> [Engine]      TConnectionParameters -> [Session]
 *   TDBObject              -> [DbObject]    TTableColumn / TTableKey -> [ColumnInfo] / [IndexInfo]
 *   TDBQuery               -> [QueryResult]
 */
enum class Engine(val label: String, val defaultPort: Int) {
    MYSQL("MySQL / MariaDB", 3306),
    POSTGRES("PostgreSQL", 5432),
    SQLITE("SQLite (local file)", 0)
}

data class Session(
    val id: String,
    val name: String,
    val engine: Engine = Engine.MYSQL,
    val host: String = "127.0.0.1",
    val port: Int = engine.defaultPort,
    val user: String = "",
    val password: String = "",
    val database: String = "",
    val useSsl: Boolean = false,
    /** SQLite only: file name inside the app's private "sqlite" directory. */
    val sqliteFile: String = "",
    val sshEnabled: Boolean = false,
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "",
    val sshPassword: String = "",
    /** PEM encoded private key (optional). */
    val sshKey: String = "",
    /** Host-key fingerprint pinned on first successful connect (trust on first use). */
    val sshHostKeyFp: String = ""
)

enum class ObjType(val label: String, val short: String) {
    TABLE("Table", "T"),
    VIEW("View", "V"),
    PROCEDURE("Procedure", "P"),
    FUNCTION("Function", "F"),
    TRIGGER("Trigger", "G")
}

data class DbObject(
    val db: String,
    val name: String,
    val type: ObjType,
    val rows: Long? = null,
    val sizeBytes: Long? = null,
    val engineName: String? = null,
    val comment: String? = null
)

data class ColumnInfo(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val key: String = "",
    val default: String? = null,
    val extra: String = "",
    val comment: String = "",
    val isPk: Boolean = false
)

data class IndexInfo(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
    val type: String = ""
)

data class QueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    /** Rows affected for statements without a result set, -1 otherwise. */
    val affected: Long = -1,
    val truncated: Boolean = false,
    val elapsedMs: Long = 0
) {
    val hasResultSet: Boolean get() = columns.isNotEmpty()
}
