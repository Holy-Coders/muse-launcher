package dev.puck
import android.content.SharedPreferences
import android.security.keystore.*
import android.util.Base64
import java.security.KeyStore
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
/** Separate appliance credential; existing Muse secrets are never exported. */
object MuseVaultToken {
    private fun key():javax.crypto.SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("puck.appliance",null) as? javax.crypto.SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("puck.appliance",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(p:SharedPreferences,v:String) {
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key())
        check(p.edit().putString("token",Base64.encodeToString(c.iv+c.doFinal(v.toByteArray()),Base64.NO_WRAP)).commit())
    }
    fun read(p:SharedPreferences):String {
        val encoded=p.getString("token",null)?:return ""
        val b=Base64.decode(encoded,Base64.NO_WRAP);val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,b.copyOfRange(0,12)));return String(c.doFinal(b.copyOfRange(12,b.size)))
    }
}
