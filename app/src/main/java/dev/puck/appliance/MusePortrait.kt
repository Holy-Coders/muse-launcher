package dev.puck.appliance

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import dev.puck.MuseRuntime
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Original profile asset only, private to this phone. Never executes avatar-generated code. */
object MusePortrait {
    data class Profile(val name:String="Muse",val image:File?=null,val updated:Long=0,val animation:File?=null,val animationRows:Int=4)
    const val INSTRUCTION="""Sync your actual Muse identity to the user's personal Muse Launcher. Read only your own current profile/persona/avatar files. Do not invent a name or avatar, generate replacement artwork, edit your profile, or perform unrelated actions. Return exactly one JSON object, no markdown or extra prose. Use {"source":"muse_profile","avatar_kind":"custom","name":"your actual name","avatar_url":"an existing directly downloadable HTTPS URL for your actual avatar"}. If your profile uses the generic/default avatar, return {"source":"muse_profile","avatar_kind":"generic","name":"your actual name"} with no image. Classify only from your actual profile, not by guessing. Prefer a full-resolution original HTTPS URL for custom artwork. If no usable URL exists, you may instead include avatar_png_base64: the actual original avatar exported as a tiny 32x32 PNG (not redrawn), at most 2800 base64 characters. Never return credentials, local file paths or guessed URLs. If your avatar cannot be obtained, return {"source":"unavailable","name":"your actual known name or Muse","reason":"brief factual explanation"}. JSON must fit 4000 characters. User request follows:"""
    fun load(c:Context):Profile=runCatching {
        val meta=JSONObject(File(c.filesDir,"muse-portrait.json").readText())
        val original=File(c.filesDir,"muse-portrait.png").takeIf {it.exists() && meta.optString("avatar_kind")!="generic"}
        val atlas=File(c.filesDir,"muse-animation.png")
        val animation=runCatching {
            val link=JSONObject(File(c.filesDir,"muse-animation.json").readText())
            val hash=original?.readBytes()?.let {java.security.MessageDigest.getInstance("SHA-256").digest(it).joinToString(""){b->"%02x".format(b)}}
            atlas.takeIf {it.isFile && link.getString("name")==meta.getString("name") && link.getString("original_sha256")==hash}
        }.getOrNull()
        val rows=runCatching {JSONObject(File(c.filesDir,"muse-animation.json").readText()).optInt("rows",4).also {require(it in 4..5)}}.getOrDefault(4)
        Profile(meta.getString("name"),original,meta.getLong("updated"),animation,rows)
    }.getOrDefault(Profile())
    fun sync(c:Context,runtime:MuseRuntime,id:String=UUID.randomUUID().toString()):Profile {
        val diagnostics=c.getSharedPreferences("appliance",Context.MODE_PRIVATE)
        fun stage(value:String){diagnostics.edit().putString("portrait_stage",value).apply()}
        stage("requesting_profile")
        val reply=runtime.send(id,"Link Muse Launcher to your actual current Muse avatar and identity.","portrait")
        check(reply.optBoolean("ok")){"Muse profile unavailable"}
        stage("parsing_profile")
        val raw=reply.getString("text");require(raw.length<=4000)
        val data=JSONObject(raw)
        require(data.keys().asSequence().all {it in setOf("source","name","avatar_url","avatar_png_base64","reason","avatar_kind")})
        val name=data.getString("name").also {require(it.isNotBlank() && it.length<=80 && it.none {ch->ch.code<32})}
        if(data.optString("source")=="unavailable")stage("profile_has_no_exportable_image")
        check(data.optString("source")=="muse_profile"){"Muse could not provide its original avatar"}
        if(data.optString("avatar_kind")=="generic") {
            File(c.filesDir,"muse-portrait.json").writeText(JSONObject().put("name",name).put("updated",System.currentTimeMillis()).put("avatar_kind","generic").toString())
            for(file in listOf("muse-portrait.png","muse-animation.png","muse-animation.json"))File(c.filesDir,file).delete()
            stage("generic_avatar");return load(c)
        }
        require(data.optString("avatar_kind","custom") in setOf("custom",""))
        val url=data.optString("avatar_url")
        val encoded=data.optString("avatar_png_base64")
        require(url.isNotBlank() xor encoded.isNotBlank())
        val bytes=if(encoded.isNotBlank()) {
            stage("decoding_profile_image")
            require(encoded.length<=3500);Base64.decode(encoded,Base64.NO_WRAP)
        } else {stage("fetching_profile_image");download(url)}
        stage("validating_profile_image")
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
        require(bounds.outMimeType in setOf("image/png","image/jpeg","image/webp"))
        val target=File(c.filesDir,"muse-portrait.png")
        val temp=File(c.filesDir,"muse-portrait.tmp");temp.writeBytes(bytes);check(temp.renameTo(target))
        val updated=System.currentTimeMillis()
        File(c.filesDir,"muse-portrait.json").writeText(JSONObject().put("name",name).put("updated",updated).put("avatar_kind","custom").toString())
        stage("generating_private_motion")
        MuseAnimation.generate(c,name,target)
        stage("linked")
        return load(c)
    }
    private fun download(url:String):ByteArray {
        require(url.length<=2000)
        val uri=URI(url)
        require(uri.scheme=="https" && uri.host!=null && uri.userInfo==null && uri.fragment==null && uri.port in setOf(-1,443))
        val host=uri.host.lowercase()
        require(host!="localhost" && listOf(".local",".internal",".localhost",".lan").none(host::endsWith))
        require(InetAddress.getAllByName(host).all {address->
            !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress &&
                !(address.address.size==16 && address.address[0].toInt() and 0xfe==0xfc)
        })
        val connection=uri.toURL().openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects=false;connection.connectTimeout=10000;connection.readTimeout=15000
        try {
            check(connection.responseCode==200)
            return connection.inputStream.use {input->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=1_048_576);out.write(buffer,0,n)}
                out.toByteArray()
            }
        } finally {connection.disconnect()}
    }
}
