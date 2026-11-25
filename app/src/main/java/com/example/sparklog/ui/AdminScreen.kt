package com.example.sparklog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sparklog.admin.ServerUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Server connection (URL, token, sync) and the SSH "Update server" button. */
@Composable
fun AdminScreen(vm: MainVm) {
    val scope = rememberCoroutineScope()
    val prefs = vm.prefs

    var url by remember { mutableStateOf(prefs.serverUrl) }
    var token by remember { mutableStateOf(prefs.token) }
    var showToken by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var askLogin by remember { mutableStateOf(false) }
    var askReset by remember { mutableStateOf(false) }
    var resetText by remember { mutableStateOf("") }
    var resetting by remember { mutableStateOf(false) }
    var resetStatus by remember { mutableStateOf("") }
    var hostKeySaved by remember { mutableStateOf(prefs.sshHostKey.isNotEmpty()) }

    // update run
    var running by remember { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }

    fun save() {
        prefs.serverUrl = url
        prefs.token = token
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Server sync", style = MaterialTheme.typography.titleMedium)
        Text("Your own server, for example http://192.168.1.50:8787", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(
            token, { token = it }, label = { Text("Token") }, singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            Button(onClick = { save(); status = "Saved" }) { Text("Save") }
            OutlinedButton(onClick = {
                save()
                status = "Syncing..."
                scope.launch { status = vm.syncNow() ?: "Sync OK" }
            }) { Text("Sync now") }
        }
        if (status.isNotEmpty()) Text(status, Modifier.padding(top = 8.dp))

        Spacer(Modifier.height(24.dp))
        Text("Update server", style = MaterialTheme.typography.titleMedium)
        Text(
            "Logs in to the server over SSH and runs the installer, which downloads the latest server files and restarts the service. " +
                "Your data and token are kept. Needs SSH enabled on the server and a user that is root or can use sudo.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                if (ServerUpdater.hostOf(url) == null) status = "Enter a valid server URL first" else { save(); askLogin = true }
            },
            enabled = !running,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(if (running) "Updating..." else "Update server over SSH") }
        if (hostKeySaved) {
            TextButton(onClick = { prefs.sshHostKey = ""; hostKeySaved = false }) { Text("Forget saved SSH key") }
        }

        Spacer(Modifier.height(24.dp))
        Text("Development", style = MaterialTheme.typography.titleMedium)
        Text(
            "Deletes ALL data (customers, jobs, hours, driving, materials, to-dos, notes) on the server and on this phone. " +
                "Job numbers start again at 1001. The server saves a backup copy of its database first.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { resetText = ""; askReset = true },
            enabled = !resetting,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(if (resetting) "Resetting..." else "Reset all data", color = MaterialTheme.colorScheme.error) }
        if (resetStatus.isNotEmpty()) Text(resetStatus, Modifier.padding(top = 8.dp))
    }

    if (askReset) {
        AlertDialog(
            onDismissRequest = { askReset = false },
            title = { Text("Reset all data?") },
            text = {
                Column {
                    Text("This cannot be undone from the app. Type RESET to confirm.")
                    OutlinedTextField(resetText, { resetText = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = resetText == "RESET",
                    onClick = {
                        askReset = false
                        resetting = true
                        resetStatus = ""
                        scope.launch {
                            save()
                            resetStatus = vm.resetEverything() ?: "Everything was reset."
                            resetting = false
                        }
                    },
                ) { Text("Delete everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askReset = false }) { Text("Cancel") } },
        )
    }

    if (askLogin) {
        var user by remember { mutableStateOf("root") }
        var password by remember { mutableStateOf("") }
        val host = ServerUpdater.hostOf(url).orEmpty()
        AlertDialog(
            onDismissRequest = { askLogin = false },
            title = { Text("Update server") },
            text = {
                Column {
                    Text("SSH login on $host. The password is used for this update only and is not saved.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(user, { user = it }, label = { Text("User") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(
                        password, { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = user.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        val u = user.trim()
                        val p = password
                        askLogin = false
                        output = ""
                        result = null
                        running = true
                        showOutput = true
                        scope.launch {
                            val error = withContext(Dispatchers.IO) {
                                ServerUpdater.update(prefs, host, u, p) { line -> output += line + "\n" }
                            }
                            hostKeySaved = prefs.sshHostKey.isNotEmpty()
                            if (error == null) {
                                output += "\nChecking the connection from the app...\n"
                                delay(2000) // give the service a moment to restart
                                val sync = vm.syncNow()
                                result = if (sync == null) "Server updated and sync works." else "Server updated. Sync says: $sync"
                            } else {
                                result = error
                            }
                            running = false
                        }
                    },
                ) { Text("Update") }
            },
            dismissButton = { TextButton(onClick = { askLogin = false }) { Text("Cancel") } },
        )
    }

    if (showOutput) {
        val scroll = rememberScrollState()
        LaunchedEffect(output) { scroll.scrollTo(scroll.maxValue) }
        AlertDialog(
            onDismissRequest = { if (!running) showOutput = false },
            title = { Text(if (running) "Updating server..." else "Update finished") },
            text = {
                Column {
                    result?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp)) }
                    Text(
                        output.ifBlank { "Connecting..." },
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.height(260.dp).verticalScroll(scroll),
                    )
                }
            },
            confirmButton = { TextButton(enabled = !running, onClick = { showOutput = false }) { Text("Close") } },
        )
    }
}
