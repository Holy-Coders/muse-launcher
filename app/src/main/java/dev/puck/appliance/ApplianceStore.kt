package dev.puck.appliance

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ApplianceStore(c:Context) {
    val prefs=c.getSharedPreferences("appliance",Context.MODE_PRIVATE)
    val dir=File(c.filesDir,"appliance").apply { mkdirs() }
    var key: Int?
        get()=prefs.getInt("key",0).takeIf { it>0 && prefs.getString("firmware","")==Build.FINGERPRINT }
        set(value) { check(prefs.edit().putInt("key",value?:0).putString("firmware",Build.FINGERPRINT).commit()) }
    var endpoint: String
        get()=prefs.getString("endpoint","")!!
        set(v) { require(v.isEmpty() || android.net.Uri.parse(v).let { it.scheme=="https" && it.host!=null && it.userInfo==null && it.query==null && it.fragment==null }); check(prefs.edit().putString("endpoint",v.trimEnd('/')).commit()) }
    var token: String
        get()=dev.puck.MuseVaultToken.read(prefs)
        set(v) { dev.puck.MuseVaultToken.save(prefs,v) }
    fun setMaintenancePin(pin:String) {
        require(pin.length>=8)
        val salt=java.util.UUID.randomUUID().toString()
        check(prefs.edit().putString("pin_salt",salt).putString("pin_hash",derive(pin,salt)).commit())
    }
    fun hasPin()=prefs.contains("pin_hash")
    fun authenticates(pin:String):Boolean {
        if(!hasPin() || System.currentTimeMillis()<prefs.getLong("pin_retry",0))return false
        prefs.edit().putLong("pin_retry",System.currentTimeMillis()+2000).commit()
        return MessageDigest.isEqual(derive(pin,prefs.getString("pin_salt","")!!).toByteArray(),prefs.getString("pin_hash","")!!.toByteArray())
    }
    private fun derive(pin:String,salt:String)=javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(javax.crypto.spec.PBEKeySpec(pin.toCharArray(),salt.toByteArray(),120000,256)).encoded.joinToString("") { "%02x".format(it) }
    private fun hash(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString("") { "%02x".format(it) }
    fun writeTurn(id:String,audio:File?,photo:File?,video:File?=null):File {
        val target=File(dir,"$id.json")
        val value=JSONObject().put("id",id).put("audio",audio?.name?:JSONObject.NULL).put("photo",photo?.name?:JSONObject.NULL).put("video",video?.name?:JSONObject.NULL).put("state","queued")
        val tmp=File(dir,"$id.tmp"); tmp.writeText(value.toString()); check(tmp.renameTo(target)); return target
    }
}
