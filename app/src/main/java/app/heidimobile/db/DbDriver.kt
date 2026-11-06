package app.heidimobile.db

/**
 * Port of HeidiSQL's abstract TDBConnection (dbconnection.pas). One implementation per
 * engine family: [JdbcDriver] (TMySQLConnection + TPgConnection) and SqliteDriver (TSQLiteConnection).
 * Calls are blocking; the UI layer runs them on a single background thread.
 */
interface DbDriver : AutoCloseable {
    val engine: Engine
    fun serverVersion(): String

    /** "Databases" as shown in the tree. For PostgreSQL these are schemata, exactly like HeidiSQL. */
    fun databases(): List<String>
    fun useDatabase(db: String)
    fun objects(db: String): List<DbObject>
    fun columns(obj: DbObject): List<ColumnInfo>
    fun indexes(obj: DbObject): List<IndexInfo>
    fun createCode(obj: DbObject): String

    fun execute(sql: String, maxRows: Int): QueryResult
    /** Parameterised INSERT/UPDATE/DELETE; returns affected rows. */
    fun executeUpdate(sql: String, params: List<String?>): Long
    fun cancel() {}
}
