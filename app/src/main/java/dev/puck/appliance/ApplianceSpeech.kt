package dev.puck.appliance

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.UUID

/** Installed Android voices; online voices require the explicit Setup toggle. Barge-in, leaving Puck and audio-focus loss stop playback. */
class ApplianceSpeech(context:Context) {
    data class VoiceChoice(val id:String,val label:String)
    val voices=MutableStateFlow<List<VoiceChoice>>(emptyList())
    val selectedVoice=MutableStateFlow("")
    private val prefs=context.getSharedPreferences("appliance",Context.MODE_PRIVATE)
    private var available=emptyList<android.speech.tts.Voice>()
    private var installed=emptyList<android.speech.tts.Voice>()
    val allowOnline=MutableStateFlow(prefs.getBoolean("read_voice_online",false))
    val speaking=MutableStateFlow(false)
    val ready=MutableStateFlow(false)
    val message=MutableStateFlow("")
    private val audio=context.getSystemService(AudioManager::class.java)
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener {if(it<0)stop()}.build()
    @Volatile private var current:String?=null
    private var tts:TextToSpeech?=null
    init {
        tts=TextToSpeech(context) {status ->
            if(status==TextToSpeech.SUCCESS) {
                installed=tts?.voices?.filter {it.locale.language==Locale.getDefault().language && "notInstalled" !in it.features.orEmpty()}.orEmpty()
                refreshVoices()
                tts?.setSpeechRate(.95f)
                if(!ready.value)message.value="No usable installed voice; replies stay visible"
            } else message.value="Read-aloud voice unavailable; replies stay visible"
        }
        tts?.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
            override fun onStart(id:String?) {if(id==current)speaking.value=true}
            override fun onDone(id:String?) {if(id==current){speaking.value=false;audio.abandonAudioFocusRequest(focus)}}
            override fun onStop(id:String?,interrupted:Boolean)=onDone(id)
            @Deprecated("Legacy callback") override fun onError(id:String?) {if(id==current){speaking.value=false;message.value="Could not read this reply";audio.abandonAudioFocusRequest(focus)}}
        })
    }
    private fun refreshVoices() {
        val locale=Locale.getDefault()
        available=installed.filter {allowOnline.value || !it.isNetworkConnectionRequired}
            .sortedWith(compareByDescending<android.speech.tts.Voice> {it.quality}.thenByDescending {it.locale.country==locale.country}
                .thenBy {it.isNetworkConnectionRequired}.thenBy {it.latency}.thenBy {it.name})
        voices.value=available.mapIndexed {index,voice->VoiceChoice(voice.name,voice.locale.getDisplayName(locale)+" · Voice ${index+1}"+
            (if(voice.quality>=android.speech.tts.Voice.QUALITY_HIGH)" · high quality" else "")+(if(voice.isNetworkConnectionRequired)" · online" else " · offline"))}
        val saved=prefs.getString("read_voice","")
        val selected=available.firstOrNull {it.name==saved}?:available.firstOrNull()
        ready.value=selected!=null && tts?.setVoice(selected)==TextToSpeech.SUCCESS
        selectedVoice.value=selected?.name.orEmpty()
    }
    fun setOnline(enabled:Boolean) {
        stop();allowOnline.value=enabled;prefs.edit().putBoolean("read_voice_online",enabled).apply();refreshVoices()
    }
    fun choose(id:String) {
        val voice=available.firstOrNull {it.name==id}?:return
        stop()
        if(tts?.setVoice(voice)==TextToSpeech.SUCCESS){selectedVoice.value=id;prefs.edit().putString("read_voice",id).apply();message.value=""}
    }
    fun read(text:String) {
        if(text.isBlank())return
        if(!ready.value){message.value="Read-back voice is not ready yet";return}
        if(audio.mode!=AudioManager.MODE_NORMAL){message.value="Read aloud is paused during a call";return}
        stop()
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){message.value="Audio is busy; try Read aloud again";return}
        current=UUID.randomUUID().toString();message.value=""
        if(tts?.speak(text.take(4000),TextToSpeech.QUEUE_FLUSH,null,current)!=TextToSpeech.SUCCESS){message.value="Could not start read aloud";stop()}
    }
    fun stop(){current=null;tts?.stop();speaking.value=false;audio.abandonAudioFocusRequest(focus)}
    fun close(){stop();tts?.shutdown();tts=null}
}
