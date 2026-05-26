package cz.netbite.sshpal.ui.workspaces

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cz.netbite.sshpal.data.WorkspaceEntity
import cz.netbite.sshpal.ssh.KeyPairGenerator
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceEditorSheet(
    initial: WorkspaceEntity?,
    askForKey: Boolean,
    existingPublicKey: String?,
    onDismiss: () -> Unit,
    onSave: (WorkspaceEntity, privateKeyPem: String?, passphrase: String?, publicSshLine: String?) -> Unit,
    onDelete: (WorkspaceEntity) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var cwd by remember { mutableStateOf(initial?.defaultCwd ?: "/") }
    var key by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var pendingPublicKey by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importError = null
        runCatching {
            context.contentResolver.openInputStream(uri).use { stream ->
                stream?.readBytes()?.toString(StandardCharsets.UTF_8) ?: ""
            }
        }.fold(
            onSuccess = { content ->
                if (content.isBlank()) {
                    importError = "Selected file is empty."
                } else {
                    key = content
                    pendingPublicKey = null
                }
            },
            onFailure = { importError = "Read failed: ${it.message ?: it::class.java.simpleName}" },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (initial == null) "New workspace" else "Edit workspace",
                style = MaterialTheme.typography.titleLarge,
            )
            if (askForKey) {
                Text(
                    text = "This workspace has no key yet. Paste a PEM, pick a file from your device, or generate a fresh Ed25519 keypair below.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(host, { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(cwd, { cwd = it }, label = { Text("Default cwd") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

            Text("Private key", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val generated = KeyPairGenerator.generateEd25519(
                        comment = "sshpal-${name.ifBlank { host.ifBlank { "key" } }}",
                    )
                    key = generated.privatePem
                    pendingPublicKey = generated.publicSshLine
                    importError = null
                }) { Text("Generate Ed25519") }
                OutlinedButton(onClick = {
                    importError = null
                    pickFile.launch(arrayOf("*/*"))
                }) { Text("Pick file") }
            }
            importError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; pendingPublicKey = null },
                label = { Text(if (askForKey || initial == null) "Private key (PEM)" else "Replace private key (leave blank to keep)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 10,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Key passphrase (optional)") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )

            val publicToShow = pendingPublicKey ?: existingPublicKey
            val isNewPublic = pendingPublicKey != null
            publicToShow?.let { line ->
                PublicKeyPanel(
                    publicSshLine = line,
                    isNew = isNewPublic,
                    onCopy = { copyToClipboard(context, "SSHPal public key", line) },
                    onShare = { shareText(context, line) },
                )
            }

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
                        onSave(
                            entity,
                            key.takeIf { it.isNotBlank() },
                            passphrase.takeIf { it.isNotBlank() },
                            pendingPublicKey,
                        )
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

@Composable
private fun PublicKeyPanel(
    publicSshLine: String,
    isNew: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (isNew) "New public key — add to ~/.ssh/authorized_keys on the server"
            else "Stored public key (for re-deploying to a server)",
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedTextField(
            value = publicSshLine,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 2,
            maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCopy) { Text("Copy") }
            TextButton(onClick = onShare) { Text("Share") }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, "Share public key").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
