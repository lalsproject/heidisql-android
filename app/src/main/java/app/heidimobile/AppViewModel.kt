package app.heidimobile

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.heidimobile.db.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

sealed interface Screen {
    data object Sessions : Screen
    data class Edit(val session: Session) : Screen
    data object Browser : Screen
}

/** State of the open table / view / routine tab (HeidiSQL: the Data, Structure and Code tabs). */
class TableState(val obj: DbObject) {
    var columns by mutableStateOf<List<ColumnInfo>>(emptyList())
    var indexes by mutableStateOf<List<IndexInfo>>(emptyList())
    var createCode by mutableStateOf("")
    var data by mutableStateOf<QueryResult?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var page by mutableStateOf(0)
    var orderBy by mutableStateOf<String?>(null)
    var desc by mutableStateOf(false)
    var where by mutableStateOf("")

    val pkColumns: List<String> get() = columns.filter { it.isPk }.map { it.name }
    val editable: Boolean get() = obj.type == ObjType.TABLE && pkColumns.isNotEmpty()
    val hasMore: Boolean get() = data?.truncated == true
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        const val PAGE_SIZE = 1000        // HeidiSQL's default "rows per step"
        const val MAX_QUERY_ROWS = 5000
    }

    private val store = SessionStore(app)
    // All database work is serialised on one thread, like HeidiSQL's single connection per session.
    private val dbThread = Executors.newSingleThreadExecutor { r -> Thread(r, "db").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    var sessions by mutableStateOf(store.load())
        private set
    var screen by mutableStateOf<Screen>(Screen.Sessions)
    var busy by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    var active by mutableStateOf<Session?>(null)
        private set
    var serverVersion by mutableStateOf("")
        private set
    var databases by mutableStateOf<List<String>>(emptyList())
        private set
    val objects = mutableStateMapOf<String, List<DbObject>>()
    var expanded by mutableStateOf<Set<String>>(emptySet())
        private set
    var currentDb by mutableStateOf<String?>(null)
        private set
    var table by mutableStateOf<TableState?>(null)

    var sql by mutableStateOf("")
    var queryResult by mutableStateOf<QueryResult?>(null)
        private set
    var queryLog by mutableStateOf<List<String>>(emptyList())
        private set
    var queryRunning by mutableStateOf(false)
        private set
    val history = mutableStateListOf<String>()

    private var driver: DbDriver? = null
    private var tunnel: SshTunnel? = null

    // ---- plumbing ---------------------------------------------------------------------------

    private suspend fun <T> db(block: () -> T): T = withContext(dbThread) { block() }

    private fun launchWork(
        showBusy: Boolean = true,
        onFail: (String) -> Unit = { error = it },
        body: suspend () -> Unit
    ) {
        viewModelScope.launch {
            if (showBusy) busy = true
            try {
                body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onFail(describe(e))
            } finally {
                if (showBusy) busy = false
            }
        }
    }

    private fun describe(e: Throwable): String {
        val msg = generateSequence(e) { it.cause }.mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }.firstOrNull()
        return (msg ?: e.toString()).take(800)
    }

    // ---- session manager --------------------------------------------------------------------

    fun newSession(): Session = Session(id = UUID.randomUUID().toString(), name = "Unnamed")

    fun saveSession(s: Session) {
        val list = sessions.toMutableList()
        val idx = list.indexOfFirst { it.id == s.id }
        if (idx >= 0) list[idx] = s else list.add(s)
        sessions = list
        store.save(list)
    }

    fun deleteSession(s: Session) {
        sessions = sessions.filter { it.id != s.id }
        store.save(sessions)
    }

    // ---- connection -------------------------------------------------------------------------

    private fun openConnection(s: Session): Pair<DbDriver, SshTunnel?> {
        if (s.engine == Engine.SQLITE) {
            require(s.sqliteFile.isNotBlank()) { "No database file set for this session." }
            return SqliteDriver(File(store.sqliteDir, s.sqliteFile)) to null
        }
        var host = s.host
        var port = s.port
        var t: SshTunnel? = null
        if (s.sshEnabled) {
            t = SshTunnel(s)
            port = t.open(s.host, s.port)
            host = "127.0.0.1"
        }
        try {
            return JdbcDriver(s, host, port) to t
        } catch (e: Throwable) {
            t?.close()
            throw e
        }
    }

    fun connect(s: Session) {
        launchWork {
            closeConnection()
            val (d, t) = db { openConnection(s) }
            driver = d
            tunnel = t
            if (t != null && s.sshHostKeyFp.isEmpty() && t.hostKeyFingerprint.isNotEmpty()) {
                saveSession(s.copy(sshHostKeyFp = t.hostKeyFingerprint)) // trust on first use
            }
            serverVersion = db { d.serverVersion() }
            databases = db { d.databases() }
            active = s
            objects.clear()
            expanded = emptySet()
            currentDb = null
            table = null
            queryResult = null
            queryLog = emptyList()
            screen = Screen.Browser
            val def = when (s.engine) {
                Engine.MYSQL -> s.database.takeIf { it in databases }
                Engine.POSTGRES -> "public".takeIf { it in databases }
                Engine.SQLITE -> "main".takeIf { it in databases }
            }
            if (def != null) toggleDb(def)
        }
    }

    fun disconnect() {
        screen = Screen.Sessions
        active = null
        table = null
        val d = driver
        val t = tunnel
        driver = null
        tunnel = null
        viewModelScope.launch(dbThread) {
            d?.close()
            t?.close()
        }
    }

    private suspend fun closeConnection() {
        val d = driver
        val t = tunnel
        driver = null
        tunnel = null
        if (d != null || t != null) db { d?.close(); t?.close() }
    }

    override fun onCleared() {
        // Closing sockets on the main thread throws NetworkOnMainThreadException on Android.
        val d = driver
        val t = tunnel
        Thread {
            try { d?.close(); t?.close() } catch (_: Exception) {}
        }.start()
    }

    // ---- database tree ----------------------------------------------------------------------

    fun toggleDb(name: String) {
        if (name in expanded) {
            expanded = expanded - name
            return
        }
        expanded = expanded + name
        currentDb = name
        val d = driver ?: return
        launchWork(showBusy = false, onFail = {}) { db { d.useDatabase(name) } }
        if (name !in objects) refreshObjects(name)
    }

    fun refreshObjects(name: String) {
        val d = driver ?: return
        launchWork { objects[name] = db { d.objects(name) } }
    }

    fun refreshAll() {
        val d = driver ?: return
        launchWork {
            databases = db { d.databases() }
            for (name in expanded) objects[name] = db { d.objects(name) }
        }
    }

    fun createDatabase(name: String) {
        val d = driver ?: return
        val stmt = Dialect.createDatabase(d.engine, name) ?: return
        launchWork {
            db { d.execute(stmt, 1) }
            databases = db { d.databases() }
            notice = "Created $name"
        }
    }

    fun dropObject(obj: DbObject) {
        val d = driver ?: return
        launchWork {
            db { d.execute(Dialect.drop(d.engine, obj), 1) }
            if (table?.obj == obj) table = null
            objects[obj.db] = db { d.objects(obj.db) }
            notice = "Dropped ${obj.name}"
        }
    }

    fun truncateTable(obj: DbObject) {
        val d = driver ?: return
        launchWork {
            db { d.execute(Dialect.truncate(d.engine, obj.db, obj.name), 1) }
            notice = "Emptied ${obj.name}"
        }
    }

    // ---- table / object tabs ----------------------------------------------------------------

    fun openObject(obj: DbObject) {
        val d = driver ?: return
        val ts = TableState(obj)
        table = ts
        currentDb = obj.db
        launchWork(showBusy = false, onFail = { ts.error = it }) {
            ts.loading = true
            try {
                db { d.useDatabase(obj.db) }
                if (obj.type == ObjType.TABLE || obj.type == ObjType.VIEW) {
                    ts.columns = db { d.columns(obj) }
                }
                if (obj.type == ObjType.TABLE) {
                    ts.indexes = try { db { d.indexes(obj) } } catch (e: Exception) { emptyList() }
                }
                ts.createCode = try { db { d.createCode(obj) } } catch (e: Exception) { "-- " + describe(e) }
            } finally {
                ts.loading = false
            }
            fetchData(ts, d)
        }
    }

    private suspend fun fetchData(ts: TableState, d: DbDriver) {
        if (ts.obj.type != ObjType.TABLE && ts.obj.type != ObjType.VIEW) return
        ts.loading = true
        try {
            // ask for one row more than a page, so "truncated" tells us a next page exists
            val sql = Dialect.selectPage(
                d.engine, ts.obj.db, ts.obj.name, ts.where, ts.orderBy, ts.desc,
                PAGE_SIZE + 1, ts.page * PAGE_SIZE
            )
            ts.data = db { d.execute(sql, PAGE_SIZE) }
            ts.error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ts.error = describe(e)
        } finally {
            ts.loading = false
        }
    }

    fun reloadData(ts: TableState) {
        val d = driver ?: return
        launchWork(showBusy = false) { fetchData(ts, d) }
    }

    fun setSort(ts: TableState, column: String) {
        when {
            ts.orderBy != column -> { ts.orderBy = column; ts.desc = false }
            !ts.desc -> ts.desc = true
            else -> { ts.orderBy = null; ts.desc = false }
        }
        ts.page = 0
        reloadData(ts)
    }

    fun applyFilter(ts: TableState, where: String) {
        ts.where = where
        ts.page = 0
        reloadData(ts)
    }

    fun changePage(ts: TableState, delta: Int) {
        val p = ts.page + delta
        if (p < 0) return
        ts.page = p
        reloadData(ts)
    }

    /** [original] == null inserts a new row, otherwise the row identified by its primary key is updated. */
    fun saveRow(ts: TableState, original: List<String?>?, values: List<String?>) {
        val d = driver ?: return
        launchWork {
            val cols = ts.columns
            val names = cols.map { it.name }
            val obj = ts.obj
            if (original == null) {
                // empty input = "use the column default", like leaving a field untouched in HeidiSQL
                val use = names.indices.filter { i ->
                    val c = cols[i]
                    val v = values[i]
                    !(v != null && v.isEmpty() && (c.nullable || c.default != null || c.extra.contains("auto_increment", true)))
                }
                val stmt = Dialect.insert(d.engine, obj.db, obj.name, use.map { names[it] })
                db { d.executeUpdate(stmt, use.map { values[it] }) }
                notice = "Row inserted"
            } else {
                val changed = names.indices.filter { original[it] != values[it] }
                if (changed.isEmpty()) return@launchWork
                val pk = cols.indices.filter { cols[it].isPk }
                val stmt = Dialect.update(d.engine, obj.db, obj.name, changed.map { names[it] }, pk.map { names[it] })
                val n = db { d.executeUpdate(stmt, changed.map { values[it] } + pk.map { original[it] }) }
                notice = if (n == 0L) "No row was updated (changed or deleted elsewhere?)" else "Row updated"
            }
            fetchData(ts, d)
        }
    }

    fun deleteRow(ts: TableState, original: List<String?>) {
        val d = driver ?: return
        launchWork {
            val cols = ts.columns
            val pk = cols.indices.filter { cols[it].isPk }
            val stmt = Dialect.delete(d.engine, ts.obj.db, ts.obj.name, pk.map { cols[it].name })
            val n = db { d.executeUpdate(stmt, pk.map { original[it] }) }
            notice = if (n == 0L) "No row was deleted" else "Row deleted"
            fetchData(ts, d)
        }
    }

    // ---- query tab --------------------------------------------------------------------------

    fun runQuery() {
        val d = driver ?: return
        val text = sql.trim()
        if (text.isEmpty() || queryRunning) return
        history.remove(text)
        history.add(0, text)
        while (history.size > 50) history.removeAt(history.lastIndex)
        queryRunning = true
        viewModelScope.launch {
            val log = ArrayList<String>()
            var last: QueryResult? = null
            var schemaChanged = false
            try {
                val statements = SqlSplitter.split(text, d.engine)
                if (statements.isEmpty()) log.add("Nothing to execute.")
                for ((i, stmt) in statements.withIndex()) {
                    try {
                        val r = db { d.execute(stmt, MAX_QUERY_ROWS) }
                        if (r.hasResultSet) {
                            last = r
                            log.add("[${i + 1}] ${r.rows.size} row(s)" +
                                (if (r.truncated) ", truncated at $MAX_QUERY_ROWS" else "") + ", ${r.elapsedMs} ms")
                        } else {
                            log.add("[${i + 1}] OK" + (if (r.affected > 0) ", ${r.affected} row(s) affected" else "") + ", ${r.elapsedMs} ms")
                        }
                        if (SqlSplitter.firstKeyword(stmt) in setOf("CREATE", "DROP", "ALTER", "RENAME")) schemaChanged = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log.add("[${i + 1}] ERROR: ${describe(e)}")
                        break
                    }
                }
            } finally {
                queryLog = log
                queryResult = last
                queryRunning = false
            }
            if (schemaChanged) refreshAll()
        }
    }

    fun cancelQuery() {
        driver?.cancel()
    }

    // ---- files (Storage Access Framework) ---------------------------------------------------

    private fun uniqueSqliteName(base: String): String {
        val clean = base.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "database.db" }
        val stem = clean.substringBeforeLast('.', clean)
        val ext = if (clean.contains('.')) "." + clean.substringAfterLast('.') else ""
        var name = clean
        var n = 1
        while (File(store.sqliteDir, name).exists()) name = "$stem-${n++}$ext"
        return name
    }

    /** Android cannot open arbitrary paths with SQLite (scoped storage), so picked files are copied in. */
    fun importSqlite(uri: Uri, onDone: (String) -> Unit) {
        launchWork {
            val app = getApplication<Application>()
            val name = withContext(Dispatchers.IO) {
                var display = "imported.db"
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) display = c.getString(0) ?: display
                }
                val target = uniqueSqliteName(display)
                val input = app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open the selected file")
                input.use { i -> File(store.sqliteDir, target).outputStream().use { o -> i.copyTo(o) } }
                target
            }
            onDone(name)
            notice = "Imported as $name"
        }
    }

    fun newSqliteName(wanted: String): String = uniqueSqliteName(wanted.ifBlank { "database.db" })

    fun exportSqlite(uri: Uri) {
        val s = active ?: return
        launchWork {
            val app = getApplication<Application>()
            withContext(Dispatchers.IO) {
                val out = app.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Cannot write the selected file")
                out.use { o -> File(store.sqliteDir, s.sqliteFile).inputStream().use { it.copyTo(o) } }
            }
            notice = "Database file exported"
        }
    }

    fun exportCsv(uri: Uri, result: QueryResult) {
        launchWork {
            val app = getApplication<Application>()
            withContext(Dispatchers.IO) {
                val out = app.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Cannot write the selected file")
                out.use { it.write(Csv.format(result).toByteArray(Charsets.UTF_8)) }
            }
            notice = "Exported ${result.rows.size} row(s)"
        }
    }
}
