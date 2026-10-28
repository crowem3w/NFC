@file:OptIn(ExperimentalMaterial3Api::class)

package org.example.test.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.test.R
import org.example.test.nfc.DraftRecord
import org.example.test.nfc.NfcAvailability
import org.example.test.nfc.NfcViewModel
import org.example.test.nfc.PendingAction
import org.example.test.nfc.ScanResult
import java.text.DateFormat
import java.util.Date

/** Typical usable NDEF capacity of an NTAG215 (504 bytes minus TLV/CC overhead). */
private const val NTAG215_NDEF_BYTES = 492

@Composable
fun NfcApp(vm: NfcViewModel, onOpenNfcSettings: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.status) {
        vm.status?.let { snackbar.showSnackbar(it.text) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    label = { Text("Read") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    label = { Text("Write") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            AvailabilityBanner(vm.availability, onOpenNfcSettings)
            when (tab) {
                0 -> ReadScreen(vm)
                else -> WriteScreen(vm)
            }
        }
    }

    if (vm.pending != PendingAction.None) {
        AlertDialog(
            onDismissRequest = vm::cancelPending,
            title = {
                Text(if (vm.pending == PendingAction.Write) "Ready to write" else "Ready to erase")
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                    Text(
                        "Hold a tag against the back of your phone and keep it still.",
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = vm::cancelPending) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AvailabilityBanner(state: NfcAvailability, onOpenSettings: () -> Unit) {
    when (state) {
        NfcAvailability.Unsupported -> BannerCard("This device doesn't have NFC.", null)
        NfcAvailability.Disabled -> BannerCard("NFC is turned off.", "Turn on" to onOpenSettings)
        else -> Unit
    }
}

@Composable
private fun BannerCard(text: String, action: Pair<String, () -> Unit>?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f))
            action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
        }
    }
}

// ------------------------------------------------------------------ Read

@Composable
private fun ReadScreen(vm: NfcViewModel) {
    val latest = vm.latest
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (latest == null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Ready to scan", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Hold an NFC tag or card against the back of your phone.",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        } else {
            item { ScanCard(latest) }
            if (latest.error != null) {
                item { Text(latest.error, color = MaterialTheme.colorScheme.error) }
            }
            if (latest.blank) {
                item { Text("This tag is blank. Use the Write tab to put something on it.") }
            }
            items(latest.records) { RecordCard(it.kind, it.content) }
        }

        if (vm.history.size > 1) {
            item { Text("Recent scans", style = MaterialTheme.typography.titleMedium) }
            items(vm.history.drop(1)) { scan ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.show(scan) }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(scan.chip)
                        Text(scan.uid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(DateFormat.getTimeInstance().format(Date(scan.time)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ScanCard(scan: ScanResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(scan.chip, style = MaterialTheme.typography.titleLarge)
            LabelValue("UID", scan.uid, mono = true)
            LabelValue("Technologies", scan.technologies.joinToString(", "))
            scan.ndefType?.let { LabelValue("NDEF type", it.substringAfterLast('.')) }
            scan.writable?.let { LabelValue("Writable", if (it) "Yes" else "No (locked)") }
            val max = scan.maxBytes
            if (max != null && max > 0) {
                val used = scan.usedBytes ?: 0
                LabelValue("NDEF memory", "$used / $max bytes")
                LinearProgressIndicator(
                    progress = { (used.toFloat() / max).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

@Composable
private fun RecordCard(kind: String, content: String) {
    val clipboard = LocalClipboardManager.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(kind, style = MaterialTheme.typography.labelLarge)
                Text(content)
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(content)) }) { Text("Copy") }
        }
    }
}

// ------------------------------------------------------------------ Write

@Composable
private fun WriteScreen(vm: NfcViewModel) {
    var type by rememberSaveable { mutableIntStateOf(0) }
    var url by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var org by rememberSaveable { mutableStateOf("") }

    val candidate: DraftRecord? = when (type) {
        0 -> url.takeIf { it.isNotBlank() }?.let { DraftRecord.Url(it) }
        1 -> text.takeIf { it.isNotBlank() }?.let { DraftRecord.Text(it) }
        else -> name.takeIf { it.isNotBlank() }?.let { DraftRecord.Contact(it, phone, email, org) }
    }

    val bytes = vm.draftBytes()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("URL", "Text", "Contact").forEachIndexed { i, label ->
                    FilterChip(selected = type == i, onClick = { type = i }, label = { Text(label) })
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (type) {
                    0 -> Field("Website", url, { url = it }, KeyboardType.Uri)
                    1 -> Field("Text", text, { text = it }, KeyboardType.Text, singleLine = false)
                    else -> {
                        Field("Full name", name, { name = it }, KeyboardType.Text)
                        Field("Phone", phone, { phone = it }, KeyboardType.Phone)
                        Field("Email", email, { email = it }, KeyboardType.Email)
                        Field("Company", org, { org = it }, KeyboardType.Text)
                    }
                }
                OutlinedButton(
                    enabled = candidate != null,
                    onClick = {
                        candidate?.let { vm.draft.add(it) }
                        url = ""; text = ""; name = ""; phone = ""; email = ""; org = ""
                    },
                ) { Text("Add record") }
            }
        }

        item { Text("Message on tag", style = MaterialTheme.typography.titleMedium) }

        if (vm.draft.isEmpty()) {
            item { Text("Nothing yet. Add one or more records above.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        items(vm.draft.toList()) { record ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(record.label, style = MaterialTheme.typography.labelLarge)
                        Text(record.preview)
                    }
                    IconButton(onClick = { vm.draft.remove(record) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove")
                    }
                }
            }
        }

        if (vm.draft.isNotEmpty()) {
            item {
                val over = bytes > NTAG215_NDEF_BYTES
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "$bytes / $NTAG215_NDEF_BYTES bytes (NTAG215)",
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    LinearProgressIndicator(
                        progress = { (bytes.toFloat() / NTAG215_NDEF_BYTES).coerceIn(0f, 1f) },
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (over) Text("Too large for an NTAG215. Remove something.", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = vm.draft.isNotEmpty() && vm.availability == NfcAvailability.Ready,
                    onClick = vm::armWrite,
                ) { Text("Write to tag") }
                OutlinedButton(
                    enabled = vm.availability == NfcAvailability.Ready,
                    onClick = vm::armErase,
                ) { Text("Erase tag") }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
