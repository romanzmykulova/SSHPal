package cz.netbite.sshpal.ui.workspaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import cz.netbite.sshpal.data.WorkspaceEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceEditorSheet(
    initial: WorkspaceEntity?,
    askForKey: Boolean,
    onDismiss: () -> Unit,
    onSave: (WorkspaceEntity, privateKeyPem: String?, passphrase: String?) -> Unit,
    onDelete: (WorkspaceEntity) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var cwd by remember { mutableStateOf(initial?.defaultCwd ?: "/") }
    var key by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (initial == null) "New workspace" else "Edit workspace",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            )
            if (askForKey) {
                Text(
                    text = "This workspace has no key yet. Paste a private key (PEM) below.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
            }

            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(host, { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cwd, { cwd = it }, label = { Text("Default cwd") }, modifier = Modifier.fillMaxWidth())

            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(if (askForKey || initial == null) "Private key (PEM)" else "Replace private key (leave blank to keep)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 10,
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Key passphrase (optional)") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        val entity = WorkspaceEntity(
                            id = initial?.id ?: 0L,
                            name = name.ifBlank { "$username@$host" },
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 22,
                            username = username.trim(),
                            defaultCwd = cwd.ifBlank { "/" },
                            knownHostKeyFingerprint = initial?.knownHostKeyFingerprint,
                        )
                        val keyToSave = key.takeIf { it.isNotBlank() }
                        val passToSave = passphrase.takeIf { it.isNotBlank() }
                        onSave(entity, keyToSave, passToSave)
                    },
                    enabled = host.isNotBlank() && username.isNotBlank(),
                ) { Text("Save") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                if (initial != null) {
                    OutlinedButton(onClick = { onDelete(initial) }) { Text("Delete") }
                }
            }
        }
    }
}
