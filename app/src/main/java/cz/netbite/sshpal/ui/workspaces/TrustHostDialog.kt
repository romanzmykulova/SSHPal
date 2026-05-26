package cz.netbite.sshpal.ui.workspaces

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

@Composable
fun TrustHostDialog(
    fingerprint: String,
    onTrust: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Unknown host key") },
        text = {
            Text(
                text = "First time connecting to this host.\n\nFingerprint:\n$fingerprint\n\nVerify on the server with:\nssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        },
        confirmButton = { Button(onClick = onTrust) { Text("Trust") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Cancel") } },
    )
}
