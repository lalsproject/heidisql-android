@file:OptIn(ExperimentalMaterial3Api::class)

package app.heidimobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heidimobile.AppViewModel
import app.heidimobile.TableState
import app.heidimobile.db.ObjType
import app.heidimobile.db.QueryResult

/** HeidiSQL's table editor / data tab for one object. */
@Composable
fun TableScreen(vm: AppViewModel, ts: TableState) {
    val obj = ts.obj
    val hasData = obj.type == ObjType.TABLE || obj.type == ObjType.VIEW
    val tabs = buildList {
        if (hasData) { add("Data"); add("Structure") }
        if (obj.type == ObjType.TABLE) add("Indexes")
        add("Create code")
    }
    var tab by rememberSaveable(obj.db, obj.name, obj.type.name) { mutableIntStateOf(0) }
    val current = tabs[tab.coerceIn(0, tabs.lastIndex)]

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("${obj.db}.${obj.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { vm.table = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.openObject(obj) }) { Icon(Icons.Default.Refresh, contentDescription = "Reload") }
                    }
                )
                TabRow(selectedTabIndex = tab.coerceIn(0, tabs.lastIndex)) {
                    tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (current) {
                "Data" -> DataTab(vm, ts)
                "Structure" -> {
                    val rows = ts.columns.map {
                        listOf<String?>(it.name, it.type, if (it.nullable) "YES" else "NO", it.key, it.default, it.extra, it.comment)
                    }
                    DataGrid(
                        QueryResult(listOf("Name", "Type", "Null", "Key", "Default", "Extra", "Comment"), rows),
                        Modifier.weight(1f).fillMaxWidth()
                    )
                }
                "Indexes" -> {
                    val rows = ts.indexes.map { listOf<String?>(it.name, it.type, if (it.unique) "YES" else "NO", it.columns.joinToString(", ")) }
                    DataGrid(QueryResult(listOf("Name", "Type", "Unique", "Columns"), rows), Modifier.weight(1f).fillMaxWidth())
                }
                else -> CodeTab(ts)
            }
            ts.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp), maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.DataTab(vm: AppViewModel, ts: TableState) {
    var filter by remember(ts) { mutableStateOf(ts.where) }
    var editIndex by remember(ts) { mutableStateOf<Int?>(null) }
    var inserting by remember(ts) { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val d = ts.data
        if (uri != null && d != null) vm.exportCsv(uri, d)
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            label = { Text("WHERE filter, e.g. id > 10") }, modifier = Modifier.weight(1f),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrect = false),
            keyboardActions = KeyboardActions(onSearch = { vm.applyFilter(ts, filter) })
        )
        IconButton(onClick = { vm.applyFilter(ts, filter) }) { Icon(Icons.Default.Search, contentDescription = "Apply filter") }
    }

    val data = ts.data
    if (data != null) {
        DataGrid(
            data, Modifier.weight(1f).fillMaxWidth(),
            sortColumn = ts.orderBy, sortDesc = ts.desc,
            onHeaderClick = { vm.setSort(ts, it) },
            onRowClick = { editIndex = it }
        )
    } else {
        Text(if (ts.loading) "Loading…" else "", modifier = Modifier.weight(1f).padding(16.dp))
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { vm.changePage(ts, -1) }, enabled = ts.page > 0) { Text("◀") }
        val from = ts.page * AppViewModel.PAGE_SIZE + 1
        Text(
            if (data == null || data.rows.isEmpty()) "0 rows" else "$from–${from + data.rows.size - 1}",
            style = MaterialTheme.typography.labelMedium
        )
        TextButton(onClick = { vm.changePage(ts, 1) }, enabled = ts.hasMore) { Text("▶") }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            if (ts.obj.type == ObjType.TABLE) {
                OutlinedButton(onClick = { inserting = true }, enabled = ts.columns.isNotEmpty()) { Text("Insert") }
            }
            TextButton(onClick = { export.launch(ts.obj.name + ".csv") }, enabled = data != null) { Text("CSV") }
        }
    }

    val idx = editIndex
    if (idx != null && data != null && idx < data.rows.size) {
        val row = data.rows[idx]
        RowEditorDialog(
            ts = ts, original = row, canEdit = ts.editable,
            onDismiss = { editIndex = null },
            onSave = { vm.saveRow(ts, row, it); editIndex = null },
            onDelete = if (ts.editable) ({ vm.deleteRow(ts, row); editIndex = null }) else null
        )
    }
    if (inserting) {
        RowEditorDialog(
            ts = ts, original = null, canEdit = true,
            onDismiss = { inserting = false },
            onSave = { vm.saveRow(ts, null, it); inserting = false },
            onDelete = null
        )
    }
}

@Composable
private fun RowEditorDialog(
    ts: TableState,
    original: List<String?>?,
    canEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<String?>) -> Unit,
    onDelete: (() -> Unit)?
) {
    val cols = ts.columns
    val values = remember { mutableStateListOf<String>().apply { cols.indices.forEach { add(original?.getOrNull(it) ?: "") } } }
    val nulls = remember { mutableStateListOf<Boolean>().apply { cols.indices.forEach { add(original != null && original.getOrNull(it) == null) } } }
    var armed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "Insert row" else "Edit row") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!canEdit) {
                    Text(
                        if (ts.obj.type == ObjType.VIEW) "Views are read-only." else "Read-only: this table has no primary key.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                    )
                } else if (original == null) {
                    Text("Empty fields use the column default.", style = MaterialTheme.typography.bodySmall)
                }
                cols.forEachIndexed { i, c ->
                    Text(
                        c.name + "  " + c.type + (if (c.isPk) "  [PK]" else ""),
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = if (nulls[i]) "" else values[i],
                            onValueChange = { values[i] = it; nulls[i] = false },
                            enabled = canEdit, modifier = Modifier.weight(1f),
                            placeholder = { if (nulls[i]) Text("NULL") },
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        )
                        if (canEdit && c.nullable) {
                            Checkbox(checked = nulls[i], onCheckedChange = { nulls[i] = it })
                            Text("NULL", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (canEdit) TextButton(onClick = { onSave(cols.indices.map { if (nulls[it]) null else values[it] }) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { if (armed) onDelete() else armed = true }) {
                        Text(if (armed) "Confirm delete" else "Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun CodeTab(ts: TableState) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(ts.createCode)) }) { Text("Copy") }
        SelectionContainer {
            Text(
                ts.createCode.ifBlank { if (ts.loading) "Loading…" else "(no code available)" },
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp).verticalScroll(rememberScrollState())
            )
        }
    }
}
