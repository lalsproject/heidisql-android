@file:OptIn(ExperimentalMaterial3Api::class)

package app.heidimobile.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.heidimobile.AppViewModel
import app.heidimobile.Screen
import app.heidimobile.db.Engine
import app.heidimobile.db.Session

/** HeidiSQL's "Session manager" (connections.pas). */
@Composable
fun SessionsScreen(vm: AppViewModel) {
    var toDelete by remember { mutableStateOf<Session?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Sessions") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.screen = Screen.Edit(vm.newSession()) }) {
                Icon(Icons.Default.Add, contentDescription = "New session")
            }
        }
    ) { pad ->
        if (vm.sessions.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No sessions yet.\nTap + to add a MySQL, MariaDB, PostgreSQL or SQLite connection.",
                    textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                items(vm.sessions, key = { it.id }) { s ->
                    ListItem(
                        modifier = Modifier.clickable { vm.connect(s) },
                        headlineContent = { Text(s.name.ifBlank { "Unnamed" }) },
                        supportingContent = { Text(summary(s)) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { vm.screen = Screen.Edit(s) }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                                }
                                IconButton(onClick = { toDelete = s }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    toDelete?.let { s ->
        ConfirmDialog("Delete session", "Delete \"${s.name}\"? Saved credentials are removed too.", "Delete",
            onConfirm = { vm.deleteSession(s) }, onDismiss = { toDelete = null })
    }
}

private fun summary(s: Session): String = when (s.engine) {
    Engine.SQLITE -> "SQLite · ${s.sqliteFile}"
    else -> s.engine.label + " · " + (if (s.user.isNotEmpty()) s.user + "@" else "") + s.host + ":" + s.port +
        (if (s.sshEnabled) " · via SSH" else "")
}

@Composable
fun EditScreen(vm: AppViewModel, initial: Session) {
    var name by remember { mutableStateOf(initial.name) }
    var engine by remember { mutableStateOf(initial.engine) }
    var host by remember { mutableStateOf(initial.host) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf(initial.password) }
    var database by remember { mutableStateOf(initial.database) }
    var useSsl by remember { mutableStateOf(initial.useSsl) }
    var sqliteFile by remember { mutableStateOf(initial.sqliteFile) }
    var sshEnabled by remember { mutableStateOf(initial.sshEnabled) }
    var sshHost by remember { mutableStateOf(initial.sshHost) }
    var sshPortText by remember { mutableStateOf(initial.sshPort.toString()) }
    var sshUser by remember { mutableStateOf(initial.sshUser) }
    var sshPassword by remember { mutableStateOf(initial.sshPassword) }
    var sshKey by remember { mutableStateOf(initial.sshKey) }
    var engineMenu by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSqlite(uri) { sqliteFile = it }
    }
    BackHandler { vm.screen = Screen.Sessions }

    fun build(): Session? {
        val file = sqliteFile.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (engine == Engine.SQLITE && file.isEmpty()) {
            vm.error = "Enter a file name for a new database, or import an existing file."
            return null
        }
        val sshPort = sshPortText.toIntOrNull() ?: 22
        // A different SSH server invalidates the pinned host key.
        val fp = if (sshHost == initial.sshHost && sshPort == initial.sshPort) initial.sshHostKeyFp else ""
        return initial.copy(
            name = name.trim().ifEmpty { "Unnamed" }, engine = engine, host = host.trim().ifEmpty { "127.0.0.1" },
            port = portText.toIntOrNull() ?: engine.defaultPort, user = user, password = password,
            database = database.trim(), useSsl = useSsl, sqliteFile = file,
            sshEnabled = sshEnabled && engine != Engine.SQLITE, sshHost = sshHost.trim(), sshPort = sshPort,
            sshUser = sshUser, sshPassword = sshPassword, sshKey = sshKey, sshHostKeyFp = fp
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.sessions.any { it.id == initial.id }) "Edit session" else "New session") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Sessions }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Field("Session name", name, { name = it })

            Box {
                OutlinedButton(onClick = { engineMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Type: " + engine.label)
                }
                DropdownMenu(expanded = engineMenu, onDismissRequest = { engineMenu = false }) {
                    Engine.values().forEach { e ->
                        DropdownMenuItem(text = { Text(e.label) }, onClick = {
                            if (portText == engine.defaultPort.toString()) portText = e.defaultPort.toString()
                            engine = e
                            engineMenu = false
                        })
                    }
                }
            }

            if (engine == Engine.SQLITE) {
                Field("Database file name (created if missing)", sqliteFile, { sqliteFile = it })
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Import an existing database file…")
                }
                Text(
                    "Android apps cannot open SQLite files in shared storage in place, so the picked file is copied " +
                        "into the app. Use \"Export database file\" in the connection menu to get a copy back.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Field("Hostname / IP", host, { host = it })
                Field("Port", portText, { portText = it.filter(Char::isDigit) }, number = true)
                Field("User", user, { user = it })
                Field("Password", password, { password = it }, password = true)
                Field(
                    if (engine == Engine.POSTGRES) "Database (default: postgres)" else "Database (optional)",
                    database, { database = it }
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Use SSL (server certificate not verified)")
                    Switch(checked = useSsl, onCheckedChange = { useSsl = it })
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("SSH tunnel (experimental)")
                    Switch(checked = sshEnabled, onCheckedChange = { sshEnabled = it })
                }
                if (sshEnabled) {
                    Field("SSH host", sshHost, { sshHost = it })
                    Field("SSH port", sshPortText, { sshPortText = it.filter(Char::isDigit) }, number = true)
                    Field("SSH user", sshUser, { sshUser = it })
                    Field("SSH password (or key passphrase)", sshPassword, { sshPassword = it }, password = true)
                    Field("Private key, PEM (optional)", sshKey, { sshKey = it }, minLines = 4, mono = true)
                    Text(
                        "Hostname / port above are resolved from the SSH server's point of view. " +
                            "The server's host key is pinned on the first successful connection.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { build()?.let { vm.saveSession(it); vm.screen = Screen.Sessions } },
                    modifier = Modifier.weight(1f)) { Text("Save") }
                Button(onClick = { build()?.let { vm.saveSession(it); vm.connect(it) } },
                    modifier = Modifier.weight(1f)) { Text("Save & open") }
            }
        }
    }
}
