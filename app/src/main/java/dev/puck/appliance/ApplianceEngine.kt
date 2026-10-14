package dev.puck.appliance

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.UUID
import android.util.Base64
import dev.puck.core.summary

class ApplianceEngine private constructor(val context:Context) {
    companion object {
        val BUSY_STATES=setOf("Sending","Muse thinking","Muse replying")
        @Volatile private var singleton:ApplianceEngine?=null
        fun get(c:Context)=singleton?:synchronized(this) { singleton?:ApplianceEngine(c.applicationContext).also { singleton=it } }
    }
    val store=ApplianceStore(context)
    val status=MutableStateFlow("Resting")
    val notifications=MuseNotificationInbox.get(context)
    fun notificationArrived() {
        handler.post {
            if(status.value in setOf("Resting","Reply ready","Update ready","Muse update"))status.value="Muse update"
            if(visible && capture==null && !sending && !videoRecording.value)CompanionHaptics.pulse(context,"reply")
        }
    }
    fun dismissNotification(id:String){val listener=MuseNotifications.live;if(listener!=null)listener.dismissPreview(id) else notifications.dismiss(id)}
    fun clearNotifications(){val listener=MuseNotifications.live;if(listener!=null)listener.clearPreviews() else notifications.clearAll()}
    fun presentNotification(){if(capture==null && !sending)status.value="Muse update"}
    val update=MutableStateFlow(store.prefs.getString("update","")!!)
    val photo=MutableStateFlow<File?>(null)
    val recordingVoice=MutableStateFlow(false)
    var avatarRecording=false;private set
    fun startAvatarVoice(){
        if(capture!=null)return
        if(!visible || sending || cameraActive || calibrating)return
        if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){status.value="Enable microphone in Settings to talk";return}
        onButtonPress?.invoke();checkInId?.let {dev.puck.MuseRuntime.get(context).cancel(it);checkInId=null}
        avatarRecording=true;apply(ButtonGesture.Effect.RECORD)
        if(capture==null)avatarRecording=false
    }
    fun endAvatarVoice(){if(avatarRecording && capture!=null){avatarRecording=false;apply(ButtonGesture.Effect.SEND)}}
    fun toggleAvatarVoice(){if(avatarRecording)endAvatarVoice() else startAvatarVoice()}
    fun cancelAvatarVoice(){if(avatarRecording){avatarRecording=false;interrupt()}}
    val transcript=MutableStateFlow("")
    val captionState=MutableStateFlow("Preparing live captions")
    val cameraGesture=MutableStateFlow(0L)
    val shutterGesture=MutableStateFlow(0L)
    data class VideoRequest(val id:Long=0,val held:Boolean=false,val cancelled:Boolean=false)
    val videoRequest=MutableStateFlow(VideoRequest())
    val videoRecording=MutableStateFlow(false)
    val video=MutableStateFlow(store.prefs.getString("last_video",null)?.let {File(store.dir,it)}?.takeIf {it.isFile && it.extension=="mp4"})
    val videoDismissed=MutableStateFlow(store.prefs.getBoolean("video_dismissed",false))
    val pendingVideo=MutableStateFlow(video.value?.takeIf {store.prefs.getBoolean("video_pending",false)})
    fun clearVideoAttachment(){pendingVideo.value=null;store.prefs.edit().putBoolean("video_pending",false).apply()}
    fun sendVideo(){
        val file=video.value?:return
        if(sending || capture!=null || cameraActive)return
        if(!usesMuse()){status.value="Pair Muse to send video";return}
        if(file.length() !in 24..8_000_000){status.value="Video too large — record a shorter clip";return}
        val journal=store.writeTurn(UUID.randomUUID().toString(),null,null,file)
        clearVideoAttachment();sending=true;status.value="Sending"
        CompanionHaptics.pulse(context,"sending")
        val epoch=foregroundEpoch
        scope.launch {send(journal,epoch)}
    }
    fun dismissVideo(){clearVideoAttachment();videoDismissed.value=true;store.prefs.edit().putBoolean("video_dismissed",true).apply();if(status.value=="Video saved on this phone")status.value="Resting"}
    fun restoreVideo(){if(video.value?.isFile==true){videoDismissed.value=false;store.prefs.edit().putBoolean("video_dismissed",false).apply()}}
    fun saveVideo(file:File){photo.value?.delete();photo.value=null;pendingVideo.value=file;store.prefs.edit().putBoolean("video_pending",true).apply();video.value=file;videoDismissed.value=false;store.prefs.edit().putString("last_video",file.name).putBoolean("video_dismissed",false).apply();status.value="Video saved on this phone"}
    var cameraActive=false
    var cameraReady=false
    val spokenReplies=MutableSharedFlow<String>(extraBufferCapacity=8)
    val mood=MutableStateFlow("idle")
    val portrait=MutableStateFlow(MusePortrait.load(context))
    val portraitStatus=MutableStateFlow(if(portrait.value.image!=null)"Muse avatar linked" else "Muse avatar not linked yet")
    @Volatile private var linkingPortrait=false
    @Volatile private var portraitRequestId:String?=null
    fun syncPortrait() {
        if(linkingPortrait || sending || capture!=null)return
        linkingPortrait=true;portraitStatus.value="Linking your Muse avatar…"
        val id=UUID.randomUUID().toString();portraitRequestId=id
        scope.launch {
            try {
                val runtime=dev.puck.MuseRuntime.get(context)
                withTimeout(60000){runtime.connection.first {it==dev.puck.core.ConnectionStatus.MuseConnected}}
                if(portraitRequestId!=id)return@launch
                val profile=MusePortrait.sync(context,runtime,id)
                withContext(Dispatchers.Main){if(portraitRequestId==id){portrait.value=profile;portraitStatus.value="Muse avatar linked"}}
            }
            catch(_:Exception){withContext(Dispatchers.Main){if(portraitRequestId==id)portraitStatus.value="Muse couldn't supply an accessible original avatar; current companion kept"}}
            finally {withContext(Dispatchers.Main){if(portraitRequestId==id){portraitRequestId=null;linkingPortrait=false}}}
        }
    }
    var onButtonPress:(()->Unit)?=null
    data class CallRequest(val id:String,val number:String?,val name:String?=null)
    val pendingCall=MutableStateFlow<CallRequest?>(null)
    data class PhoneRequest(val request:dev.puck.core.WorkspaceRequest.Confirmation,val autoOpen:Boolean)
    val pendingPhone=MutableStateFlow<PhoneRequest?>(null)
    fun claimPhone(id:String):dev.puck.core.DeviceAction? {
        val pending=pendingPhone.value?.takeIf {it.request.id==id}?:return null
        pendingPhone.value=null
        return pending.request.action
    }
    fun cancelPhone(){pendingPhone.value=null}
    fun selectPhoneContact(id:String,number:String) {
        val current=pendingPhone.value?.takeIf {it.request.id==id}?:return
        val message=current.request.action as? dev.puck.core.DeviceAction.Message?:return
        val action=message.copy(number=number)
        check(dev.puck.core.CommandValidator.validateAction(action)==null)
        pendingPhone.value=current.copy(request=current.request.copy(action=action),autoOpen=false)
    }
    fun selectCallContact(id:String,number:String,name:String?) {
        val current=pendingCall.value?:return
        if(current.id!=id)return
        check(dev.puck.core.CommandValidator.validateAction(dev.puck.core.DeviceAction.Dial(number))==null)
        pendingCall.value=current.copy(number=number,name=name?.take(120))
    }
    fun cancelCall(){pendingCall.value=null}
    fun callNotice(text:String){show(text)}
    val rawKey=MutableStateFlow<Int?>(null)
    val pairs=MutableStateFlow(0)
    val rootConnected=MutableStateFlow(false)
    var microphoneMode: ((Boolean)->Unit)?=null
    private var foregroundEpoch=0
    var visible=false
        set(value){if(field!=value)foregroundEpoch++;field=value}
    val calibrationActive=MutableStateFlow(false)
    var calibrating:Boolean
        get()=calibrationActive.value
        set(value){calibrationActive.value=value}
    private var buttonDown=false
    private var candidate:Int?=null
    private var downCandidate:Int?=null
    private val handler=Handler(Looper.getMainLooper())
    private val gesture=ButtonGesture()
    private var capture:PcmCapture?=null
    private var captions:LiveTranscript?=null
    private var captionGeneration=0
    private var id:String?=null
    private var sessionPhoto:File?=null
    private var sessionVideo:File?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var sending=false
    private var checkInId:String?=null
    private var activeTurn:String?=null
    private val threshold=Runnable { apply(gesture.tick(SystemClock.elapsedRealtime())) }
    private val deadline=Runnable { interrupt("Recording timed out; nothing sent") }
    init {
        scope.launch {val profile=portrait.value;if(profile.image!=null && profile.animation==null)runCatching {
            MuseAnimation.generate(context,profile.name,profile.image)
            withContext(Dispatchers.Main){portrait.value=MusePortrait.load(context)}
        }}
        scope.launch {notifications.entries.collect {entries->withContext(Dispatchers.Main){
            if(entries.isEmpty() && status.value=="Muse update")status.value="Resting"
        }}}
        scope.launch {runCatching {LiveTranscript.warm(context)}.onFailure {captionState.value="Live captions unavailable; audio still sends"}}
        val muse=dev.puck.MuseRuntime.get(context)
        scope.launch {muse.turnProgress.collect {(turn,phase)->withContext(Dispatchers.Main) {
            if(activeTurn==turn && status.value in BUSY_STATES)status.value=when(phase){"thinking"->"Muse thinking";"replying"->"Muse replying";else->"Sending"}
        }}}
        if(muse.vault.enabled)scope.launch {muse.connect()}
        scope.launch {muse.applianceUpdates.collect {request->withContext(Dispatchers.Main){show(request.text)}}}
        if(!store.prefs.contains("last_checkin"))store.prefs.edit().putLong("last_checkin",System.currentTimeMillis()).commit()
        scope.launch { while(isActive) {
            if(store.endpoint.isNotBlank())runCatching {sync()}
            withContext(Dispatchers.Main) {if(visible)checkIn()}
            delay(15000)
        } } }
    fun observe(key:Int,down:Boolean,repeat:Int):Boolean {
        if(calibrating) {
            if(repeat>0)return true
            if(key in setOf(3,4,24,25,26,82,187) || key<1)return false
            rawKey.value=key
            if(down) { downCandidate=key; if(candidate!=key) {candidate=key;pairs.value=0} }
            else if(downCandidate==key) {pairs.value++;downCandidate=null}
            return true
        }
        if(store.key!=key)return false
        if(repeat==0) edge(down)
        return true
    }
    fun verify() { check(calibrating && pairs.value>=3);store.key=candidate;calibrating=false;status.value="Button saved for this firmware" }
    fun edge(down:Boolean) {
        buttonDown=down
        if(down) {
            onButtonPress?.invoke()
            portraitRequestId?.let {dev.puck.MuseRuntime.get(context).cancel(it);portraitRequestId=null;linkingPortrait=false;portraitStatus.value="Avatar linking paused for talk"}
            checkInId?.let {dev.puck.MuseRuntime.get(context).cancel(it);checkInId=null;if(status.value=="Checking for updates")status.value="Resting"}
            val eligible=if(cameraActive)cameraReady && visible && !sending else !sending && capture==null && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED && (visible || AppliancePolicy.owner(context))
            apply(gesture.down(SystemClock.elapsedRealtime(),eligible,visible && !sending && capture==null && !calibrating && !cameraActive, cameraActive && cameraReady && visible,cameraActive))
            handler.postDelayed(threshold,450)
        } else { handler.removeCallbacks(threshold);apply(gesture.up(SystemClock.elapsedRealtime())) }
    }
    private fun apply(effect:ButtonGesture.Effect) {
        when(effect) {
            ButtonGesture.Effect.WAKE -> CompanionHaptics.pulse(context,"wake")
            ButtonGesture.Effect.RECORD -> {
                if(store.endpoint.isBlank() && !usesMuse()) { interrupt("Pair Muse or configure the voice server in Setup");return }
                id=UUID.randomUUID().toString();sessionPhoto=photo.value;sessionVideo=pendingVideo.value
                pendingCall.value=null
                pendingPhone.value=null
                try {
                    captions?.cancel();val token=++captionGeneration;transcript.value="";captionState.value="Listening for your words…"
                    captions=LiveTranscript(context,
                        {text->handler.post {if(token==captionGeneration)transcript.value=text}},
                        {state->handler.post {if(token==captionGeneration)captionState.value=state}})
                    microphoneMode?.invoke(true)
                    val preview=captions
                    capture=PcmCapture(File(store.dir,"$id.wav"),{pcm,n->preview?.accept(pcm,n)}).also {it.start()}
                    recordingVoice.value=true;status.value="Listening";handler.postDelayed(deadline,60000)
                    CompanionHaptics.pulse(context,"listening")
                }
                catch(_:Exception) {interrupt("Microphone unavailable; release and try again")}
            }
            ButtonGesture.Effect.SEND -> {
                val finishingAt=SystemClock.elapsedRealtime()
                handler.removeCallbacks(deadline)
                val audio=capture?.finish();capture=null;recordingVoice.value=false;avatarRecording=false;runCatching {microphoneMode?.invoke(false)}
                captions?.finish()
                store.prefs.edit().putLong("capture_finish_ms",SystemClock.elapsedRealtime()-finishingAt).apply()
                if(audio==null) {id?.let {File(store.dir,"$it.wav").delete()};++captionGeneration;captions?.cancel();status.value="Nothing recorded";CompanionHaptics.pulse(context,"error");return}
                val turnId=id?:return
                val journal=store.writeTurn(turnId,audio,sessionPhoto,sessionVideo)
                if(photo.value==sessionPhoto)photo.value=null
                if(pendingVideo.value==sessionVideo)clearVideoAttachment()
                status.value="Sending";sending=true
                CompanionHaptics.pulse(context,"sending")
                val epoch=foregroundEpoch
                scope.launch { send(journal,epoch) }
            }
            ButtonGesture.Effect.CANCEL -> {capture?.cancel();capture=null;recordingVoice.value=false;avatarRecording=false;runCatching {microphoneMode?.invoke(false)}}
            ButtonGesture.Effect.VIDEO_START -> {videoRequest.value=VideoRequest(videoRequest.value.id+1,true)}
            ButtonGesture.Effect.VIDEO_STOP -> {videoRequest.value=videoRequest.value.copy(held=false)}
            ButtonGesture.Effect.VIDEO_CANCEL -> {videoRequest.value=videoRequest.value.copy(held=false,cancelled=true)}
            ButtonGesture.Effect.PHOTO -> {shutterGesture.value++}
            ButtonGesture.Effect.CAMERA -> {CompanionHaptics.pulse(context,"camera");cameraGesture.value++}
            else -> Unit
        }
    }
    fun interrupt(reason:String="Stopped; nothing sent") {
        if(videoRequest.value.held || videoRecording.value)videoRequest.value=videoRequest.value.copy(held=false,cancelled=true)
        val active=buttonDown || capture!=null;buttonDown=false;recordingVoice.value=false;avatarRecording=false
        ++captionGeneration;captions?.cancel();captions=null;transcript.value=""
        handler.removeCallbacks(threshold);handler.removeCallbacks(deadline);apply(gesture.cancel());capture?.cancel();capture=null
        runCatching {microphoneMode?.invoke(false)}
        if(active || reason !in setOf("Stopped; nothing sent","Screen switched off; nothing sent"))status.value=reason
    }
    fun dismiss() {update.value="";store.prefs.edit().putString("update","").apply()}
    fun checkIn(force:Boolean=false,petInteraction:Boolean=false) {
        if(!usesMuse()){if(force)status.value="Link Muse in Setup for updates";return}
        if(sending || capture!=null || cameraActive || calibrating){if(force && capture==null && !sending)status.value="Finish button setup first";return}
        if(checkInId!=null){if(force)status.value="Checking for updates";return}
        if(!force && (!store.prefs.getBoolean("checkins",true) || System.currentTimeMillis()-store.prefs.getLong("last_checkin",0)<30*60*1000L))return
        val id=UUID.randomUUID().toString();checkInId=id;status.value="Checking for updates"
        val epoch=foregroundEpoch
        store.prefs.edit().putLong("last_checkin",System.currentTimeMillis()).commit()
        scope.launch {
            var result:String?=null
            fun stage(value:String){store.prefs.edit().putString("checkin_stage",value).apply()}
            try {
                stage("requesting")
                val prompt=(if(petInteraction)"I tapped your avatar for a status update. " else "Please check for a useful status update. ")+"Respond warmly as your actual Muse self, with your established personality. Give one useful, concise update from information you actually have, such as progress on our ongoing work or something that needs my attention. If there is nothing new, say so clearly. Do not invent news, use tools or perform actions."
                val runtime=dev.puck.MuseRuntime.get(context)
                if(runtime.connection.value!=dev.puck.core.ConnectionStatus.MuseConnected){runtime.connect();withTimeout(30000){runtime.connection.first {it==dev.puck.core.ConnectionStatus.MuseConnected}}}
                val reply=runtime.send(id,prompt,"checkin")
                if(!reply.optBoolean("ok")){stage("request_failed");store.prefs.edit().putString("checkin_error",reply.optString("error","Update request failed").take(200)).apply();error("Update request failed")}
                stage("parsing")
                val raw=reply.optString("text").trim()
                val decoded=runCatching {dev.puck.core.MuseReply.decode(id,raw,informationOnly=true)}.getOrNull()
                val card=decoded?.request as? dev.puck.core.WorkspaceRequest.ContentCard
                val text=card?.text ?: raw.takeIf {it.isNotBlank() && !it.startsWith("{") && !it.startsWith("```") && it.length<=4000}
                if(text.isNullOrBlank()){stage("invalid_reply");error("No displayable update")}
                withContext(Dispatchers.Main) {
                    if(checkInId==id && foregroundEpoch==epoch && visible){
                        decoded?.mood?.let {mood.value=it};show(text)
                        stage("delivered");status.value="Update ready";result="Update ready"
                        if(force){CompanionHaptics.pulse(context,"reply");spokenReplies.tryEmit(text)}
                    }
                }
            } catch(_:Exception) {
                withContext(Dispatchers.Main) {if(force && checkInId==id && foregroundEpoch==epoch && visible){status.value="Could not get an update · tap ${portrait.value.name} to retry";result=status.value}}
            } finally {withContext(Dispatchers.Main) {if(checkInId==id){checkInId=null;if(result==null && status.value=="Checking for updates")status.value="Resting"}}}
        }
    }
    private fun usesMuse()=dev.puck.MuseRuntime.get(context).vault.enabled && store.prefs.getBoolean("muse_voice",true)
    fun retry() {
        if(sending)return
        val queued=store.dir.listFiles()?.firstOrNull {it.extension=="json" && JSONObject(it.readText()).optString("state")=="queued"}?:return
        sending=true;status.value="Sending";scope.launch {send(queued)}
    }
    private suspend fun send(journal:File,autoEpoch:Int?=null) {
        val startedAt=SystemClock.elapsedRealtime()
        try {
            val row=JSONObject(journal.readText())
            withContext(Dispatchers.Main){activeTurn=row.getString("id")}
            val data=JSONObject().put("id",row.getString("id"))
            if(!row.isNull("audio"))data.put("audio",Base64.encodeToString(File(store.dir,row.getString("audio")).readBytes(),Base64.NO_WRAP))
            if(!row.isNull("video") && row.has("video")){
                val file=File(store.dir,row.getString("video"));require(file.length() in 24..8_000_000)
                data.put("video",Base64.encodeToString(file.readBytes(),Base64.NO_WRAP))
                if(!data.has("audio"))VideoAudio.wave(file)?.let {data.put("audio",Base64.encodeToString(it,Base64.NO_WRAP))}
            }
            if(!row.isNull("photo")) data.put("image",Base64.encodeToString(File(store.dir,row.getString("photo")).readBytes(),Base64.NO_WRAP))
            val useMuse=usesMuse()
            var reply:JSONObject
            if(useMuse) {
                // A lost Muse outcome cannot be replayed automatically.
                row.put("state","muse_pending");journal.writeText(row.toString())
                reply=dev.puck.MuseRuntime.get(context).send(row.getString("id"),
                    if(data.has("audio"))"Handle the request in the attached speech, using the photo or video if present. Ask for another recording if unclear." else "User input is the attached video.",
                    "appliance",data.optString("image",""),data.optString("audio",""),data.optString("video",""))
                check(reply.optBoolean("ok"))
                val raw=reply.optString("text")
                val decodedReply=runCatching {dev.puck.core.MuseReply.decodeAppliance(row.getString("id"),raw,dev.puck.MuseRuntime.get(context).replyApps())}.getOrNull()
                val decoded=decodedReply?.request
                decodedReply?.mood?.let {withContext(Dispatchers.Main){mood.value=it}}
                val text=when(decoded) {
                    is dev.puck.core.WorkspaceRequest.ContentCard -> decoded.text
                    is dev.puck.core.WorkspaceRequest.Confirmation -> {
                        val action=decoded.action
                        withContext(Dispatchers.Main) {
                            if(action is dev.puck.core.DeviceAction.Dial)pendingCall.value=CallRequest(row.getString("id"),action.number)
                            else pendingPhone.value=PhoneRequest(decoded,visible && autoEpoch==foregroundEpoch && (action==dev.puck.core.DeviceAction.Camera || !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) && dev.puck.core.ActionReview.mayOpenImmediately(action,true))
                        }
                        if(action is dev.puck.core.DeviceAction.Dial)"Choose or review the number below, then open Google Voice. You place the call in Voice."
                        else action.summary()
                    }
                    else -> if(raw.trimStart().startsWith("{") || raw.trimStart().startsWith("```")) "Muse returned an unsupported reply; no action was performed." else raw.take(4000)
                }
                reply=JSONObject().put("text",text)
            } else {check(!data.has("video")){"Video requires Muse"};reply=request("/turn",data)}
            withContext(Dispatchers.Main) {
                val text=reply.getString("text");show(text);status.value="Reply ready"
                if(visible)CompanionHaptics.pulse(context,"reply")
                if(visible && autoEpoch==foregroundEpoch)spokenReplies.tryEmit(text)
            }
            row.put("state","sent");journal.writeText(row.toString())
            if(!row.isNull("audio"))File(store.dir,row.getString("audio")).delete()
            if(!row.isNull("photo")) File(store.dir,row.getString("photo")).delete()
        } catch(_:Exception) {withContext(Dispatchers.Main) {status.value="Reply unavailable — check Setup";if(visible)CompanionHaptics.pulse(context,"error")}}
        finally {withContext(Dispatchers.Main){activeTurn=null};store.prefs.edit().putLong("voice_turn_ms",SystemClock.elapsedRealtime()-startedAt).apply();sending=false}
    }
    private fun show(text:String) { update.value=text.take(4000);store.prefs.edit().putString("update",update.value).apply() }
    private suspend fun sync() {
        val cursor=store.prefs.getLong("cursor",0)
        val response=request("/events?after=$cursor",null)
        val events=response.getJSONArray("events")
        for(i in 0 until events.length()) {
            val event=events.getJSONObject(i)
            withContext(Dispatchers.Main) {show(event.getString("text"))}
            check(store.prefs.edit().putLong("cursor",event.getLong("seq")).commit())
        }
    }
    private fun request(path:String,body:JSONObject?):JSONObject {
        val c=URL(store.endpoint+path).openConnection() as HttpsURLConnection
        c.connectTimeout=10000;c.readTimeout=90000;c.setRequestProperty("Authorization","Bearer "+store.token)
        c.instanceFollowRedirects=false
        try {
            if(body!=null) {c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use {it.write(body.toString().toByteArray())}}
            check(c.responseCode==200)
            val bytes=c.inputStream.use { input ->
                val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(4096)
                while(true) {val n=input.read(chunk);if(n<0)break;check(out.size()+n<=65536);out.write(chunk,0,n)}
                out.toByteArray()
            }
            return JSONObject(String(bytes))
        } finally {c.disconnect()}
    }
}
