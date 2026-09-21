package com.lomo.app

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import androidx.core.content.edit
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

internal fun trustedLaunchAsciiHmacKeyBytes(hexSecret: String): ByteArray = hexSecret.encodeToByteArray()

internal interface TrustedLaunchPlaintextInstallSecret {
    fun read(): String?

    fun clear()
}

internal interface TrustedLaunchHmacKeyEntry {
    fun contains(): Boolean

    fun importAsciiKey(asciiKeyBytes: ByteArray)

    fun sign(payload: ByteArray): ByteArray

    fun delete()
}

internal interface TrustedLaunchProcessLock {
    fun <T> withLock(block: () -> T): T
}

class TrustedLaunchSecretStore internal constructor(
    private val root: TrustedLaunchTrustRoot,
) {
    fun hmacSha256(payload: ByteArray): ByteArray = root.hmacSha256(payload)

    companion object {
        fun create(context: Context): TrustedLaunchSecretStore {
            val appContext = context.applicationContext
            return TrustedLaunchSecretStore(
                TrustedLaunchTrustRoot(
                    plaintext = SharedPreferencesInstallSecret(appContext),
                    hmacKey = AndroidKeystoreHmacKeyEntry(),
                    processLock =
                        FileChannelTrustedLaunchProcessLock(
                            File(appContext.noBackupFilesDir, TRUSTED_LAUNCH_LOCK_FILE),
                        ),
                ),
            )
        }
    }
}

internal class TrustedLaunchTrustRootUnavailable(
    val code: String,
    cause: Throwable? = null,
) : Exception(code, cause)

internal class TrustedLaunchTrustRoot(
    private val plaintext: TrustedLaunchPlaintextInstallSecret,
    private val hmacKey: TrustedLaunchHmacKeyEntry,
    private val processLock: TrustedLaunchProcessLock,
    private val randomBytes: () -> ByteArray = {
        ByteArray(INSTALL_SECRET_RANDOM_BYTES).also { SecureRandom().nextBytes(it) }
    },
) {
    fun hmacSha256(payload: ByteArray): ByteArray =
        processLock.withLock {
            when (val install = ensureInstalledLocked()) {
                TrustRootInstall.Ready -> hmacKey.sign(payload)
                is TrustRootInstall.Failed -> throw install.error
            }
        }

    private fun ensureInstalledLocked(): TrustRootInstall {
        if (hmacKey.contains()) {
            plaintext.clear()
            return TrustRootInstall.Ready
        }
        val existing = plaintext.read()?.takeIf(String::isNotBlank)
        val hex = existing ?: randomBytes().toTrustedLaunchHexString()
        val asciiKey = trustedLaunchAsciiHmacKeyBytes(hex)
        try {
            hmacKey.importAsciiKey(asciiKey)
            val expected = softwareAsciiHmac(asciiKey, TRUSTED_LAUNCH_MIGRATION_PROBE)
            val observed = hmacKey.sign(TRUSTED_LAUNCH_MIGRATION_PROBE)
            if (!MessageDigest.isEqual(expected, observed)) {
                hmacKey.delete()
                return TrustRootInstall.Failed(
                    TrustedLaunchTrustRootUnavailable("trusted_launch_hmac_probe_mismatch"),
                )
            }
        } catch (error: TrustedLaunchTrustRootUnavailable) {
            return TrustRootInstall.Failed(error)
        } catch (error: Exception) {
            hmacKey.delete()
            return TrustRootInstall.Failed(
                TrustedLaunchTrustRootUnavailable("trusted_launch_hmac_import_failed", error),
            )
        }
        plaintext.clear()
        return TrustRootInstall.Ready
    }
}

internal class SharedPreferencesInstallSecret(
    context: Context,
) : TrustedLaunchPlaintextInstallSecret {
    private val preferences =
        context.getSharedPreferences(TRUSTED_LAUNCH_PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(TRUSTED_LAUNCH_PREFS_KEY, null)

    override fun clear() {
        preferences.edit { remove(TRUSTED_LAUNCH_PREFS_KEY) }
    }
}

internal class AndroidKeystoreHmacKeyEntry(
    private val alias: String = TRUSTED_LAUNCH_HMAC_ALIAS,
) : TrustedLaunchHmacKeyEntry {
    override fun contains(): Boolean = keyStore().containsAlias(alias)

    override fun importAsciiKey(asciiKeyBytes: ByteArray) {
        require(asciiKeyBytes.isNotEmpty()) { "trusted launch HMAC key bytes must not be empty" }
        val protection =
            KeyProtection
                .Builder(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setRandomizedEncryptionRequired(false)
                .build()
        keyStore().setEntry(
            alias,
            KeyStore.SecretKeyEntry(SecretKeySpec(asciiKeyBytes, HMAC_SHA_256)),
            protection,
        )
    }

    override fun sign(payload: ByteArray): ByteArray {
        val key =
            keyStore().getKey(alias, null) as? SecretKey
                ?: throw TrustedLaunchTrustRootUnavailable("trusted_launch_hmac_missing")
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(key)
        return mac.doFinal(payload)
    }

    override fun delete() {
        val store = keyStore()
        if (store.containsAlias(alias)) {
            store.deleteEntry(alias)
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
}

internal class FileChannelTrustedLaunchProcessLock(
    private val lockFile: File,
) : TrustedLaunchProcessLock {
    override fun <T> withLock(block: () -> T): T {
        lockFile.parentFile?.mkdirs()
        return RandomAccessFile(lockFile, "rw").use { randomAccess ->
            randomAccess.channel.lock().use {
                block()
            }
        }
    }
}

internal fun softwareAsciiHmac(
    asciiKeyBytes: ByteArray,
    payload: ByteArray,
): ByteArray {
    val mac = Mac.getInstance(HMAC_SHA_256)
    mac.init(SecretKeySpec(asciiKeyBytes, HMAC_SHA_256))
    return mac.doFinal(payload)
}

internal fun ByteArray.toTrustedLaunchHexString(): String =
    joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }

internal const val HMAC_SHA_256 = "HmacSHA256"
internal const val INSTALL_SECRET_RANDOM_BYTES = 32
internal const val TRUSTED_LAUNCH_PREFS_NAME = "trusted_launch_intents"
internal const val TRUSTED_LAUNCH_PREFS_KEY = "install_secret"
internal const val TRUSTED_LAUNCH_HMAC_ALIAS = "com.lomo.app.trusted-launch-hmac"
internal const val TRUSTED_LAUNCH_LOCK_FILE = "trusted_launch.lock"
internal const val ANDROID_KEYSTORE = "AndroidKeyStore"
internal val TRUSTED_LAUNCH_MIGRATION_PROBE: ByteArray =
    "lomo.trusted-launch.migration-probe".encodeToByteArray()

private sealed interface TrustRootInstall {
    data object Ready : TrustRootInstall

    data class Failed(
        val error: TrustedLaunchTrustRootUnavailable,
    ) : TrustRootInstall
}
