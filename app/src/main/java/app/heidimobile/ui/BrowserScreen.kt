@file:OptIn(ExperimentalMaterial3Api::class)

package app.heidimobile.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heidimobile.AppViewModel
import app.heidimobile.db.DbObject
import app.heidimobile.db.Engine
import app.heidimobile.db.ObjType

@Composable
fun BrowserScreen(vm: AppViewModel) {
    val ts = vm.table
    if (ts != null) {
        BackHandler { vm.table = null }
        TableScreen(vm, ts)
        return
    }

    val session = vm.active
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var askDisconnect by remember { mutableStateOf(false) }
    var createDb by remember { mutableStateOf(false) }
    val exportDb = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) vm.exportSqlite(uri) }

    BackHandler { askDisconnect = true }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(session?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(vm.serverVersion, style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { askDisconnect = true }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Disconnect")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.refreshAll() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (session?.engine != Engine.SQLITE) {
                                    DropdownMenuItem(
                                        text = { Text(if (session?.engine == Engine.POSTGRES) "Create schema…" else "Create database…") },
                                        onClick = { menu = false; createDb = true }
                                    )
                                } else {
                                    DropdownMenuItem(text = { Text("Export database file…") }, onClick = {
                                        menu = false
                                        exportDb.launch(session?.sqliteFile.orEmpty().ifBlank { "database.db" })
                                    })
                                }
                                DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; askDisconnect = true })
                            }
                        }
                    }
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Database") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Query") })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (tab == 0) DbTree(vm) else QueryTab(vm)
        }
    }

    if (askDisconnect) {
        ConfirmDialog("Disconnect", "Close the connection to \"${session?.name ?: ""}\"?", "Disconnect",
            onConfirm = { vm.disconnect() }, onDismiss = { askDisconnect = false })
    }
    if (createDb) {
        var dbName by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { createDb = false },
            title = { Text("New " + if (session?.engine == Engine.POSTGRES) "schema" else "database") },
            text = { Field("Name", dbName, { dbName = it }) },
            confirmButton = {
                TextButton(enabled = dbName.isNotBlank(), onClick = { vm.createDatabase(dbName.trim()); createDb = false }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { createDb = false }) { Text("Cancel") } }
        )
    }
}

/** HeidiSQL's left-hand database tree. */
@Composable
private fun DbTree(vm: AppViewModel) {
    var confirm by remember { mutableStateOf<Pair<String, DbObject>?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        for (db in vm.databases) {
            item(key = "db:$db") {
                val open = db in vm.expanded
                Row(
                    Modifier.fillMaxWidth().clickable { vm.toggleDb(db) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (open) "▾" else "▸", modifier = Modifier.width(24.dp))
                    Text(db, fontWeight = if (db == vm.currentDb) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
            if (db in vm.expanded) {
                val objs = vm.objects[db]
                if (objs == null) {
                    item(key = "loading:$db") { Text("Loading…", modifier = Modifier.padding(start = 40.dp, top = 8.dp, bottom = 8.dp)) }
                } else if (objs.isEmpty()) {
                    item(key = "empty:$db") { Text("(empty)", modifier = Modifier.padding(start = 40.dp, top = 8.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.outline) }
                } else {
                    items(objs, key = { "o:$db:${it.type}:${it.name}" }) { obj ->
                        ObjRow(vm, obj, onAsk = { kind -> confirm = kind to obj })
                    }
                }
            }
        }
    }
    confirm?.let { (kind, obj) ->
        if (kind == "drop") {
            ConfirmDialog("Drop ${obj.type.label.lowercase()}", "DROP ${obj.name}? This cannot be undone.", "Drop",
                onConfirm = { vm.dropObject(obj) }, onDismiss = { confirm = null })
        } else {
            ConfirmDialog("Empty table", "Delete ALL rows of ${obj.name}? This cannot be undone.", "Empty",
                onConfirm = { vm.truncateTable(obj) }, onDismiss = { confirm = null })
        }
    }
}

@Composable
private fun ObjRow(vm: AppViewModel, obj: DbObject, onAsk: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().clickable { vm.openObject(obj) }.padding(start = 32.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            obj.type.short, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp)
        )
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(obj.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val info = listOfNotNull(
                obj.rows?.let { "~$it rows" }, obj.sizeBytes?.takeIf { it > 0 }?.let { formatSize(it) }, obj.engineName
            ).joinToString(" · ")
            if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Actions") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; vm.openObject(obj) })
                DropdownMenuItem(text = { Text("Copy name") }, onClick = { menu = false; clipboard.setText(AnnotatedString(obj.name)) })
                if (obj.type == ObjType.TABLE) {
                    DropdownMenuItem(text = { Text("Empty table…") }, onClick = { menu = false; onAsk("truncate") })
                }
                DropdownMenuItem(text = { Text("Drop…") }, onClick = { menu = false; onAsk("drop") })
            }
        }
    }
}

/** HeidiSQL's "Query" tab. */
@Composable
private fun QueryTab(vm: AppViewModel) {
    val dark = isSystemInDarkTheme()
    val highlighter = remember(dark) { SqlHighlighter(dark) }
    var historyMenu by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val r = vm.queryResult
        if (uri != null && r != null) vm.exportCsv(uri, r)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Text(
            "Database: " + (vm.currentDb ?: "–"), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp)
        )
        OutlinedTextField(
            value = vm.sql, onValueChange = { vm.sql = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            visualTransformation = highlighter,
            placeholder = { Text("SELECT * FROM …;") }
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (vm.queryRunning) {
                OutlinedButton(onClick = { vm.cancelQuery() }) { Text("Cancel") }
            } else {
                Button(onClick = { vm.runQuery() }, enabled = vm.sql.isNotBlank()) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Run")
                }
            }
            Box {
                OutlinedButton(onClick = { historyMenu = true }, enabled = vm.history.isNotEmpty()) { Text("History") }
                DropdownMenu(expanded = historyMenu, onDismissRequest = { historyMenu = false }) {
                    vm.history.forEach { q ->
                        DropdownMenuItem(
                            text = { Text(q.replace('\n', ' ').take(60), maxLines = 1) },
                            onClick = { vm.sql = q; historyMenu = false }
                        )
                    }
                }
            }
            OutlinedButton(onClick = { export.launch("result.csv") }, enabled = vm.queryResult != null) { Text("CSV") }
        }
        if (vm.queryLog.isNotEmpty()) {
            val failed = vm.queryLog.any { it.contains("ERROR") }
            Text(
                vm.queryLog.joinToString("\n"), maxLines = 6, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        val r = vm.queryResult
        if (r != null) DataGrid(r, Modifier.weight(1f).fillMaxWidth())
        else Box(Modifier.weight(1f))
    }
}
