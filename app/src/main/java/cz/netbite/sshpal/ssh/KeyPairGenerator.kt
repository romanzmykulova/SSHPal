package cz.netbite.sshpal.ssh

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.security.SecureRandom
import java.util.Base64

data class GeneratedKey(
    /** OpenSSH PEM-wrapped private key, suitable for sshj's loadKeys() and for ~/.ssh/id_ed25519. */
    val privatePem: String,
    /** Single-line OpenSSH public key, suitable for ~/.ssh/authorized_keys. */
    val publicSshLine: String,
)

object KeyPairGenerator {

    /**
     * Generate a fresh Ed25519 keypair using BouncyCastle's lightweight API.
     *
     * Private key is emitted in unencrypted OpenSSH PEM format (the same shape
     * `ssh-keygen -t ed25519` produces without a passphrase). Public key is the
     * one-line `ssh-ed25519 <base64> <comment>` form.
     */
    fun generateEd25519(comment: String): GeneratedKey {
        val random = SecureRandom()
        val gen = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(random))
        }
        val pair = gen.generateKeyPair()
        val priv = pair.private as Ed25519PrivateKeyParameters
        val pub = pair.public as Ed25519PublicKeyParameters

        val privateRaw = OpenSSHPrivateKeyUtil.encodePrivateKey(priv)
        val publicRaw = OpenSSHPublicKeyUtil.encodePublicKey(pub)

        return GeneratedKey(
            privatePem = wrapPem("OPENSSH PRIVATE KEY", privateRaw),
            publicSshLine = "ssh-ed25519 ${Base64.getEncoder().encodeToString(publicRaw)} ${sanitizeComment(comment)}",
        )
    }

    private fun wrapPem(label: String, bytes: ByteArray): String {
        val body = Base64.getEncoder().encodeToString(bytes)
        val wrapped = body.chunked(70).joinToString("\n")
        return buildString {
            append("-----BEGIN ").append(label).append("-----\n")
            append(wrapped)
            if (!wrapped.endsWith("\n")) append('\n')
            append("-----END ").append(label).append("-----\n")
        }
    }

    private fun sanitizeComment(comment: String): String =
        comment.replace(Regex("[\\r\\n\\t ]+"), "-").ifBlank { "sshpal" }
}
