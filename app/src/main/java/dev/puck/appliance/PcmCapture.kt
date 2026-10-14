package dev.puck.appliance

import android.media.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Recording belongs to the button session, never to recognition/VAD callbacks. */
class PcmCapture(private val file:File, private val onPcm:((ByteArray,Int)->Unit)?=null) {
    @Volatile private var active=false
    private var recorder:AudioRecord?=null
    private var worker:Thread?=null
    @Volatile var failed=false; private set
    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        val size=maxOf(4096,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT))
        val audio=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size)
        check(audio.state==AudioRecord.STATE_INITIALIZED)
        recorder=audio
        try { audio.startRecording(); check(audio.recordingState==AudioRecord.RECORDSTATE_RECORDING) }
        catch(e:Exception) { audio.release();recorder=null;throw e }
        active=true
        worker=Thread {
            try {
                RandomAccessFile(file,"rw").use { out ->
                    out.setLength(0);out.write(ByteArray(44));val buffer=ByteArray(size)
                    while(active && out.length()<1_920_044) {
                        val n=audio.read(buffer,0,size);check(n>=0)
                        if(n>0) {out.write(buffer,0,n);runCatching {onPcm?.invoke(buffer,n)}}
                    }
                    if(active) failed=true // Maximum length is cancelled, never auto-sent.
                    val bytes=(out.length()-44).toInt()
                    val h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                    h.put("RIFF".toByteArray()).putInt(bytes+36).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                    h.putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes)
                    out.seek(0);out.write(h.array())
                }
            } catch(_:Exception) { failed=true }
        }.apply { start() }
    }
    fun finish():File? {
        active=false;runCatching { recorder?.stop() };worker?.join(2500);recorder?.release();recorder=null
        return file.takeIf { !failed && worker?.isAlive!=true && it.length()>3244 }
    }
    fun cancel() { finish();file.delete() }
}
