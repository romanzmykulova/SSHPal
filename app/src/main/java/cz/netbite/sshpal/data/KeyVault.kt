package cz.netbite.sshpal.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class KeyVault(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "sshpal-keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun savePrivateKey(workspaceId: Long, pem: String, passphrase: String?) {
        prefs.edit()
            .putString(keyOf(workspaceId), pem)
            .apply {
                if (passphrase.isNullOrEmpty()) remove(passphraseOf(workspaceId))
                else putString(passphraseOf(workspaceId), passphrase)
            }
            .apply()
    }

    fun savePublicKey(workspaceId: Long, publicSshLine: String) {
        prefs.edit().putString(pubOf(workspaceId), publicSshLine).apply()
    }

    fun loadPrivateKey(workspaceId: Long): String? = prefs.getString(keyOf(workspaceId), null)

    fun loadPassphrase(workspaceId: Long): String? = prefs.getString(passphraseOf(workspaceId), null)

    fun loadPublicKey(workspaceId: Long): String? = prefs.getString(pubOf(workspaceId), null)

    fun hasKey(workspaceId: Long): Boolean = prefs.contains(keyOf(workspaceId))

    fun clear(workspaceId: Long) {
        prefs.edit()
            .remove(keyOf(workspaceId))
            .remove(passphraseOf(workspaceId))
            .remove(pubOf(workspaceId))
            .apply()
    }

    private fun keyOf(id: Long) = "key:$id"
    private fun passphraseOf(id: Long) = "pass:$id"
    private fun pubOf(id: Long) = "pub:$id"
}
