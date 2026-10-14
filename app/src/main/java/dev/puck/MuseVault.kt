package dev.puck

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Runtime secrets only: Android Keystore AES-GCM, no backups, no APK/build credentials. */
class MuseVault(context: Context) {
    private val prefs = context.getSharedPreferences("muse_private", Context.MODE_PRIVATE)
    private val alias = "puck.muse.v1"
    val mac: String = prefs.getString("identity", null) ?: UUID.randomUUID().toString().replace("-", "").take(12)
        .chunked(2).joinToString(":").let { value ->
            val first = ((value.take(2).toInt(16) and 0xfc) or 2).toString(16).padStart(2, '0')
            (first + value.drop(2)).also { check(prefs.edit().putString("identity", it).commit()) }
        }
    val bleName get() = "MuseGadget" + mac.replace(":", "").takeLast(6).uppercase()
    // Start one dedicated conversation, separate from obsolete prototype chat IDs.
    // Keep it across updates, reconnects and process restarts; never rotate per turn.
    val chatId: String = prefs.getString("puck_side_chat_v1", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("puck_side_chat_v1", it).commit())
    }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        val data = Base64.decode(stored, Base64.NO_WRAP)
        require(data.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(name.toByteArray())
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }
    @Synchronized fun save(name: String, text: String): Boolean {
        require(text.length <= 32000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        return prefs.edit().putString(name, Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).commit()
    }
    @Synchronized fun forgetCredentials() = prefs.edit().remove("credentials").commit()
    @Synchronized fun forgetSecrets() = prefs.edit().remove("credentials").remove("sdk").remove("enabled").commit()
    fun hasSdkToken() = prefs.contains("sdk")
    fun hasCredentials() = prefs.contains("credentials")
    fun rememberBluetoothName(name: String) { prefs.edit().putString("original_bluetooth_name", name).commit() }
    fun originalBluetoothName() = prefs.getString("original_bluetooth_name", null)
    fun clearedBluetoothName() { prefs.edit().remove("original_bluetooth_name").commit() }
}
