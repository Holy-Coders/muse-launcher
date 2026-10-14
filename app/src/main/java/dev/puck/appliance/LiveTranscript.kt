package dev.puck.appliance

import android.content.Context
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Display-only local captions. Never owns the microphone, sends a turn or delays release. */
class LiveTranscript(context:Context, private val text:(String)->Unit, private val state:(String)->Unit) {
    companion object {
        private val models=mutableMapOf<String,Model>()
        private data class Prepared(val name:String,val recognizer:Recognizer)
        private var idleRecognizer:Prepared?=null
        fun enhancedAvailable(context:Context)=context.assets.list("")?.contains("vosk-model-en-us-0.22-lgraph.zip")==true
        private fun modelName(context:Context)=if(enhancedAvailable(context) && context.getSharedPreferences("appliance",Context.MODE_PRIVATE).getBoolean("accurate_captions",false))"vosk-model-en-us-0.22-lgraph" else "vosk-model-small-en-us-0.15"
        @Synchronized fun warm(context:Context) {
            val name=modelName(context)
            if(idleRecognizer?.name!=name){idleRecognizer?.recognizer?.close();idleRecognizer=Prepared(name,Recognizer(prepare(context),16000f))}
        }
        @Synchronized private fun acquire(context:Context):Prepared {
            val name=modelName(context)
            val warmed=idleRecognizer
            if(warmed?.name==name){idleRecognizer=null;return warmed}
            return Prepared(name,Recognizer(prepare(context),16000f))
        }
        @Synchronized private fun recycle(prepared:Prepared) {
            runCatching {prepared.recognizer.reset()}.onFailure {prepared.recognizer.close();return}
            if(idleRecognizer==null)idleRecognizer=prepared else prepared.recognizer.close()
        }
        @Synchronized fun prepare(context:Context):Model {
            val name=modelName(context)
            models[name]?.let {return it}
            LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
            val root=File(context.filesDir,"caption-model")
            val target=File(root,name)
            val ready=File(root,"ready-v3-$name")
            if(!ready.exists()) {
                target.deleteRecursively();root.mkdirs()
                ZipInputStream(context.assets.open("$name.zip")).use {zip ->
                    var total=0L
                    while(true) {
                        val entry=zip.nextEntry?:break
                        val file=File(root,entry.name)
                        require(file.canonicalPath.startsWith(root.canonicalPath+File.separator))
                        if(entry.isDirectory)file.mkdirs() else {
                            file.parentFile?.mkdirs()
                            file.outputStream().use {out ->
                                val buffer=ByteArray(32768)
                                while(true) {val n=zip.read(buffer);if(n<0)break;total+=n;require(total<=256_000_000);out.write(buffer,0,n)}
                            }
                        }
                        zip.closeEntry()
                    }
                }
                check(File(target,"am/final.mdl").isFile)
                ready.writeText("1")
            }
            return Model(target.path).also {models[name]=it}
        }
    }
    private val queue=ArrayBlockingQueue<ByteArray>(256)
    @Volatile private var accepting=true
    @Volatile private var cancelled=false
    private val worker=Thread({
        try {
            if(!cancelled) {
                val prepared=acquire(context)
                val recognizer=prepared.recognizer
                try {
                var committed=""
                var previous=""
                state("Live captions · English · "+if(prepared.name.contains("small"))"fast" else "enhanced")
                fun publish(partial:String) {
                    if(cancelled)return
                    val next=(committed+" "+partial).trim().take(4000)
                    if(next!=previous) {previous=next;text(next)}
                }
                while(!cancelled && (accepting || queue.isNotEmpty())) {
                    val pcm=queue.poll(100,TimeUnit.MILLISECONDS)?:continue
                    if(recognizer.acceptWaveForm(pcm,pcm.size)) {
                        committed=(committed+" "+JSONObject(recognizer.result).optString("text")).trim().take(4000)
                        publish("")
                    } else publish(JSONObject(recognizer.partialResult).optString("partial"))
                }
                if(!cancelled) {publish(JSONObject(recognizer.finalResult).optString("text"));state("Your words · preview")}
                } finally {recycle(prepared)}
            }
        } catch(_:Exception) {if(!cancelled)state("Live captions unavailable; audio still sends")}
    },"puck-local-captions").apply {start()}
    fun accept(pcm:ByteArray,length:Int) {
        if(accepting && !cancelled && !queue.offer(pcm.copyOf(length))) {
            cancel();state("Live captions fell behind; audio still sends")
        }
    }
    fun finish() {accepting=false}
    fun cancel() {cancelled=true;accepting=false;queue.clear();worker.interrupt()}
}
