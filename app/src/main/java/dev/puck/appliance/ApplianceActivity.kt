package dev.puck.appliance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.draw.clipToBounds
import androidx.camera.view.PreviewView
import androidx.camera.video.*
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.UUID
import dev.puck.core.summary
import dev.puck.core.note

class ApplianceActivity:ComponentActivity() {
    private lateinit var engine:ApplianceEngine
    private lateinit var speech:ApplianceSpeech
    private val stopVoiceForButton:()->Unit={if(::speech.isInitialized)speech.stop();videoPlayer?.stopPlayback();playingVideo=false}
    private var seenCameraGesture=0L
    private var welcome by mutableStateOf(false)
    private val pairingPermissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {grants->if(grants.values.all {it})beginPairing()}
    private val bluetoothEnable=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {if(it.resultCode==RESULT_OK)dev.puck.MuseRuntime.get(this).startPairing()}
    private var setup by mutableStateOf(false)
    private var camera by mutableStateOf(false)
    private var playingVideo by mutableStateOf(false)
    private var videoPlayer:android.widget.VideoView?=null
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private var pickingCallId:String?=null
    private var pickingPhoneId:String?=null
    private val callContact=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {result ->
        val id=pickingCallId;pickingCallId=null
        val phoneId=pickingPhoneId;pickingPhoneId=null
        val uri=if(result.resultCode==RESULT_OK)result.data?.data else null
        if((id!=null || phoneId!=null) && uri?.scheme=="content" && uri.authority==android.provider.ContactsContract.AUTHORITY) {
            runCatching {
                contentResolver.query(uri,arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),null,null,null)?.use {cursor ->
                    if(cursor.moveToFirst()) {
                        if(id!=null)engine.selectCallContact(id,cursor.getString(0),cursor.getString(1))
                        if(phoneId!=null)engine.selectPhoneContact(phoneId,cursor.getString(0))
                    }
                }
            }.onFailure {engine.callNotice("Could not read that contact's phone number. Choose another contact.")}
        }
    }
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);dev.puck.DebugScreen.apply(this);engine=ApplianceEngine.get(this)
        welcome=!engine.store.prefs.getBoolean("onboarding_done",false) && !dev.puck.MuseRuntime.get(this).vault.hasCredentials()
        speech=ApplianceSpeech(this);engine.onButtonPress=stopVoiceForButton;seenCameraGesture=engine.cameraGesture.value
        lifecycleScope.launch {engine.spokenReplies.collect {if(engine.visible && engine.store.prefs.getBoolean("auto_read",false))speech.read(it)}}
        AppliancePolicy.companionEntry(this,engine.store.prefs.getBoolean("companion_entry",true))
        setShowWhenLocked(true);setTurnScreenOn(true)
        val native=window.callback
        window.callback=object:android.view.Window.Callback by native {
            override fun dispatchKeyEvent(event:KeyEvent):Boolean {
                if(!event.isPrintingKey && !engine.rootConnected.value && event.action in setOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP) && engine.observe(event.keyCode,event.action==KeyEvent.ACTION_DOWN,event.repeatCount))return true
                return native.dispatchKeyEvent(event)
            }
        }
        setContent {
            androidx.activity.compose.BackHandler { camera=false;playingVideo=false;setup=false;engine.calibrating=false }
            val status by engine.status.collectAsState()
            val update by engine.update.collectAsState()
            val notifications by engine.notifications.entries.collectAsState()
            var notificationIndex by remember {mutableIntStateOf(0)}
            LaunchedEffect(notifications.firstOrNull()?.id,notifications.firstOrNull()?.posted){notificationIndex=0}
            val notification=notifications.getOrNull(notificationIndex.coerceIn(0,(notifications.size-1).coerceAtLeast(0)))
            val unread=notifications.count {it.unread}
            val photo by engine.photo.collectAsState()
            val video by engine.video.collectAsState()
            val videoDismissed by engine.videoDismissed.collectAsState()
            val pendingVideo by engine.pendingVideo.collectAsState()
            val transcript by engine.transcript.collectAsState()
            val captionState by engine.captionState.collectAsState()
            val pendingCall by engine.pendingCall.collectAsState()
            val pendingPhone by engine.pendingPhone.collectAsState()
            val portrait by engine.portrait.collectAsState()
            val mood by engine.mood.collectAsState()
            val speaking by speech.speaking.collectAsState()
            val speechMessage by speech.message.collectAsState()
            val cameraGesture by engine.cameraGesture.collectAsState()
            val raw by engine.rawKey.collectAsState()
            val pairs by engine.pairs.collectAsState()
            val observing by engine.calibrationActive.collectAsState()
            val pageScroll=rememberScrollState()
            val transcriptScroll=rememberScrollState()
            var followTranscript by remember {mutableStateOf(true)}
            LaunchedEffect(setup,camera) {pageScroll.scrollTo(0)}
            LaunchedEffect(transcriptScroll) {snapshotFlow {transcriptScroll.isScrollInProgress to (transcriptScroll.maxValue-transcriptScroll.value)}.collect {(dragging,distance)->if(dragging)followTranscript=distance<32}}
            LaunchedEffect(transcript,followTranscript) {if(followTranscript){withFrameNanos {};transcriptScroll.scrollTo(transcriptScroll.maxValue)}}
            LaunchedEffect(status){if(status=="Listening"){camera=false;playingVideo=false}}
            LaunchedEffect(cameraGesture) {
                if(cameraGesture!=seenCameraGesture) {
                    seenCameraGesture=cameraGesture
                    if(engine.visible){engine.interrupt();speech.stop();setup=false
                        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)camera=true
                        else permissions.launch(arrayOf(Manifest.permission.CAMERA))}
                }
            }
            LaunchedEffect(pendingPhone) {
                pendingPhone?.takeIf {it.autoOpen && engine.visible}?.let(::openPhoneAction)
            }
            MaterialTheme(colorScheme=darkColorScheme(background=Color(0xff0a1116),surface=Color(0xff0a1116),primary=Color(0xffbde2c5))) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).then(if(welcome || setup || camera || playingVideo)Modifier.verticalScroll(pageScroll,overscrollEffect=null) else Modifier),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(welcome) {
                            CompanionWelcome(dev.puck.MuseRuntime.get(this@ApplianceActivity),::openSetupLink,::beginPairing,
                                {permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA,Manifest.permission.POST_NOTIFICATIONS))},
                                {val intent=Intent(android.provider.Settings.ACTION_HOME_SETTINGS);NavigationGuard.allow(this@ApplianceActivity,intent);startActivity(intent)},
                                {engine.store.prefs.edit().putBoolean("onboarding_done",true).apply();welcome=false})
                        } else if(playingVideo && video!=null) {
                            VideoPane(video!!){playingVideo=false}
                        } else if(camera) {
                            CameraPane({file->engine.photo.value=file;camera=false},{camera=false})
                        } else if(setup) {
                            Setup(raw,pairs,observing)
                        } else {
                            val busy=status in ApplianceEngine.BUSY_STATES
                            val interacting=status=="Listening" || busy
                            val idle=!interacting && notification==null && update.isBlank() && pendingCall==null && pendingPhone==null && photo==null && speechMessage.isBlank() && engine.store.key!=null
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val petSize=if(idle)minOf(280.dp,maxWidth,(maxHeight-96.dp).coerceAtLeast(72.dp)) else if(interacting)72.dp else if(notification!=null)if(notifications.size>1)72.dp else 96.dp else if(update.isNotBlank())120.dp else 180.dp
                            Column(Modifier.fillMaxSize().then(if(idle)Modifier else Modifier.verticalScroll(pageScroll,overscrollEffect=null)),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp,if(idle)Alignment.CenterVertically else Alignment.Top)) {
                            Text(portrait.name,style=MaterialTheme.typography.titleMedium)
                            Pet(if(speaking)"Speaking" else if(unread>0 && !interacting)"New update" else status,portrait,if(unread>0)"cheerful" else mood,Modifier.size(petSize).semantics {contentDescription="Get ${portrait.name} update"}.combinedClickable(onClick={CompanionHaptics.pulse(this@ApplianceActivity,"pet");if(notification!=null){engine.presentNotification();engine.notifications.read(notification.id)}else engine.checkIn(true,true)},onLongClick={setup=true}))
                            CompanionStatus(if(speaking)"Speaking" else if(unread>0 && status in setOf("Resting","Muse update","Update ready"))"New notifications" else status)
                            if(interacting) Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(captionState,style=MaterialTheme.typography.labelSmall)
                                    Text(transcript.ifBlank {"…"},Modifier.fillMaxWidth().heightIn(min=140.dp,max=280.dp).verticalScroll(transcriptScroll),
                                        fontSize=28.sp,lineHeight=36.sp,fontWeight=FontWeight.Medium)
                                    if(!followTranscript)TextButton(onClick={followTranscript=true}) {Text("Follow live words")}
                                }
                            }
                            if(notifications.isNotEmpty() && !interacting)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text((if(notifications.size>1)"${notificationIndex.coerceAtMost(notifications.lastIndex)+1} / ${notifications.size}" else "1 notification")+(if(unread>0)" · $unread new" else ""),Modifier.weight(1f).clickable {engine.presentNotification()},style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                                if(notifications.size>1) {
                                    TextButton(enabled=notificationIndex>0,onClick={notificationIndex--;engine.presentNotification();notifications.getOrNull(notificationIndex)?.let {engine.notifications.read(it.id)}},modifier=Modifier.width(48.dp).semantics {contentDescription="Previous notification"}){Text("‹",fontSize=24.sp)}
                                    TextButton(enabled=notificationIndex<notifications.lastIndex,onClick={notificationIndex++;engine.presentNotification();notifications.getOrNull(notificationIndex)?.let {engine.notifications.read(it.id)}},modifier=Modifier.width(48.dp).semantics {contentDescription="Next notification"}){Text("›",fontSize=24.sp)}
                                }
                                TextButton(onClick={speech.stop();engine.clearNotifications()}){Text("Clear all")}
                            }
                            if(notification!=null && !interacting && status!="Reply ready") {
                                key(notification.id,notification.posted) {
                                val dismissState=rememberSwipeToDismissBoxState(confirmValueChange={value->if(value!=SwipeToDismissBoxValue.Settled){engine.dismissNotification(notification.id);true}else false})
                                SwipeToDismissBox(dismissState,backgroundContent={}) {
                                    MessageBubble(notification.appName,notification.title,notification.text,if(notifications.size>1)100.dp else 140.dp,
                                        {engine.notifications.read(notification.id);if(speaking)speech.stop() else speech.read(notification.display)},
                                        {speech.stop();engine.dismissNotification(notification.id)},speaking)
                                }}
                            }
                            if(update.isNotBlank() && !interacting && (notification==null || status=="Reply ready"))key(update) {
                                val replyDismiss=rememberSwipeToDismissBoxState(confirmValueChange={value->if(value!=SwipeToDismissBoxValue.Settled){speech.stop();engine.dismiss();true}else false})
                                SwipeToDismissBox(replyDismiss,backgroundContent={}) {
                                    MessageBubble(portrait.name,"",update,200.dp,
                                        {if(speaking)speech.stop() else speech.read(update)},{speech.stop();engine.dismiss()},speaking)
                                }
                            }
                            pendingCall?.let {call -> Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(call.name?:"Google Voice call",style=MaterialTheme.typography.titleMedium)
                                    Text(call.number?:"Choose the person to call")
                                    if(call.number!=null)Button(onClick={openGoogleVoiceCall(call)}) {Text("Open Google Voice")}
                                    TextButton(onClick={chooseCallContact(call)}) {Text(if(call.number==null)"Choose contact" else "Change contact")}
                                    TextButton(onClick={engine.cancelCall();engine.callNotice("Call cancelled; nothing was placed.")}) {Text("Cancel call")}
                                }
                            } }
                            pendingPhone?.let {phone -> Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(phone.request.action.summary())
                                    val needsContact=dev.puck.core.CommandValidator.needsContact(phone.request.action)
                                    if(needsContact)Button(onClick={choosePhoneContact(phone)}) {Text("Choose contact")}
                                    else Button(onClick={openPhoneAction(phone)}) {Text(if(phone.request.action is dev.puck.core.DeviceAction.Message)"Open Google Voice draft" else "Open")}
                                    TextButton(onClick={engine.cancelPhone();engine.callNotice("Cancelled before opening.")}) {Text("Cancel")}
                                }
                            } }
                            if(pendingVideo!=null)Text("Video attached to your next question")
                            if(video!=null && !videoDismissed && !interacting)Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                Button(onClick={speech.stop();engine.sendVideo()}){Text("Send video to Muse")}
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
                                TextButton(onClick={speech.stop();playingVideo=true}){Text("Play last video")}
                                TextButton(onClick={videoPlayer?.stopPlayback();playingVideo=false;engine.dismissVideo()}){Text("Dismiss video")}
                                }
                            }
                            if(photo!=null) {Text("Photo attached to your next question");TextButton(onClick={photo?.delete();engine.photo.value=null}) {Text("Remove photo")}}
                            if(engine.store.key==null) TextButton(onClick={setup=true}) {Text("Set up button")}
                            }
                            }
                            Text(if(engine.store.key!=null)"Double tap your button for camera · Hold to talk" else "Hold Talk, speak, then release to send",style=MaterialTheme.typography.bodySmall)
                            if(engine.store.key==null) {
                                if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)Button(onClick={permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))},modifier=Modifier.fillMaxWidth()){Text("Enable microphone to talk")}
                                else HoldControl("Hold to talk",press={engine.edge(true)},release={engine.edge(false)},cancel={engine.interrupt();engine.edge(false)})
                            }
                            if(!interacting)OutlinedButton(onClick={
                                if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)camera=true
                                else permissions.launch(arrayOf(Manifest.permission.CAMERA))
                            },Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text("Photo")}
                        }
                    }
                }
            }
        }
        runCatching { startForegroundService(Intent(this,ApplianceService::class.java)) }
    }
    private fun openSetupLink(url:String){
        val intent=Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url))
        NavigationGuard.allow(this,intent)
        runCatching {startActivity(intent)}
    }
    @android.annotation.SuppressLint("MissingPermission")
    private fun beginPairing(){
        if(android.os.Build.VERSION.SDK_INT>=31 && listOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE).any {checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}) {
            pairingPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE));return
        }
        val adapter=getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        if(adapter==null){dev.puck.MuseRuntime.get(this).setupStatus.value="This phone has no Bluetooth adapter";return}
        if(!adapter.isEnabled){val intent=Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE);NavigationGuard.allow(this,intent);bluetoothEnable.launch(intent);return}
        dev.puck.MuseRuntime.get(this).startPairing()
    }
    private fun unlockForCall(call:ApplianceEngine.CallRequest,action:()->Unit) {
        if(engine.pendingCall.value?.id!=call.id)return
        val keyguard=getSystemService(android.app.KeyguardManager::class.java)
        if(!keyguard.isKeyguardLocked)action() else keyguard.requestDismissKeyguard(this,
            object:android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded(){if(!isFinishing && engine.pendingCall.value?.id==call.id)action()}
                override fun onDismissCancelled(){engine.callNotice("Unlock cancelled. No call was opened.")}
                override fun onDismissError(){engine.callNotice("Unlock the phone to open Google Voice.")}
            })
    }
    private fun chooseCallContact(call:ApplianceEngine.CallRequest)=unlockForCall(call) {
        pickingCallId=call.id
        runCatching {
            val intent=Intent(Intent.ACTION_PICK,android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            NavigationGuard.allow(this,intent);callContact.launch(intent)
        }
            .onFailure {pickingCallId=null;engine.callNotice("No contact picker is available. No call was opened.")}
    }
    fun openGoogleVoiceCall(call:ApplianceEngine.CallRequest)=unlockForCall(call) {
        val number=call.number?:return@unlockForCall
        val action=dev.puck.core.DeviceAction.Dial(number)
        val result=dev.puck.DeviceCapabilities(this).open(action,googleVoice=true)
        if(result==dev.puck.core.CapabilityResult.HandedOff) {
            engine.cancelCall();engine.callNotice("Opened Google Voice for $number. Use its Call control; Muse Launcher cannot verify call completion.")
        } else engine.callNotice("Google Voice could not open. Check that it is installed and set up; no call was placed.")
    }
    private fun unlockForPhone(phone:ApplianceEngine.PhoneRequest,action:()->Unit) {
        if(engine.pendingPhone.value?.request?.id!=phone.request.id)return
        val keyguard=getSystemService(android.app.KeyguardManager::class.java)
        if(phone.request.action==dev.puck.core.DeviceAction.Camera || !keyguard.isKeyguardLocked)action() else keyguard.requestDismissKeyguard(this,
            object:android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded(){if(!isFinishing && engine.pendingPhone.value?.request?.id==phone.request.id)action()}
                override fun onDismissCancelled(){engine.callNotice("Unlock cancelled. Nothing was opened.")}
                override fun onDismissError(){engine.callNotice("Unlock the phone to open this action.")}
            })
    }
    private fun choosePhoneContact(phone:ApplianceEngine.PhoneRequest)=unlockForPhone(phone) {
        pickingPhoneId=phone.request.id
        runCatching {
            val intent=Intent(Intent.ACTION_PICK,android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            NavigationGuard.allow(this,intent);callContact.launch(intent)
        }
            .onFailure {pickingPhoneId=null;engine.callNotice("No contact picker is available.")}
    }
    fun openPhoneAction(phone:ApplianceEngine.PhoneRequest)=unlockForPhone(phone) {
        if(phone.request.action==dev.puck.core.DeviceAction.Camera && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(Manifest.permission.CAMERA));return@unlockForPhone
        }
        val action=engine.claimPhone(phone.request.id)?:return@unlockForPhone
        engine.interrupt()
        if(action==dev.puck.core.DeviceAction.Camera) {camera=true;engine.callNotice("Camera opened inside Muse Launcher. Take the photo here.")}
        else {
            val result=dev.puck.DeviceCapabilities(this).open(action,googleVoice=true)
            engine.callNotice(if(result==dev.puck.core.CapabilityResult.HandedOff && action is dev.puck.core.DeviceAction.Message)
                "Opened Google Voice with your draft. Choose and verify the recipient again in Voice; use its Send control. Delivery is unverified."
                else result.note())
        }
    }
    @Composable private fun Setup(raw:Int?,pairs:Int,observing:Boolean) {
        var authorized by remember {mutableStateOf(!engine.store.hasPin())}
        var pin by remember {mutableStateOf("")}
        var endpoint by remember {mutableStateOf(engine.store.endpoint)}
        var token by remember {mutableStateOf("")}
        var message by remember {mutableStateOf("")}
        var forgetMuse by remember {mutableStateOf(false)}
        Text("Muse Launcher setup")
        if(!authorized) {
            OutlinedTextField(pin,{pin=it},label={Text("Maintenance passphrase")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
            Button(onClick={authorized=engine.store.authenticates(pin);pin=""}) {Text("Unlock setup")}
        } else {
            Button(onClick={engine.interrupt();setup=false;welcome=true}){Text("Connect Muse / guided setup")}
            OutlinedButton(onClick={forgetMuse=true}){Text("Forget Muse connection")}
            if(forgetMuse)AlertDialog(onDismissRequest={forgetMuse=false},title={Text("Forget Muse on this phone?")},text={Text("Removes saved SDK and pairing credentials. Your local messages and media remain. Also remove Muse Launcher in Muse Devices to revoke remote access.")},confirmButton={TextButton(onClick={engine.interrupt();dev.puck.MuseRuntime.get(this@ApplianceActivity).forget();forgetMuse=false;message="Muse connection removed"}){Text("Forget connection")}},dismissButton={TextButton(onClick={forgetMuse=false}){Text("Keep connection")}})
            Text(if(MuseNotifications.allowed(this@ApplianceActivity))"Notification access enabled" else "Notification access is off")
            Text("Your companion presents notifications from all apps. Previews stay on this phone and never execute commands. Swipe or Clear all also dismisses their clearable Android notifications.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={val intent=Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);NavigationGuard.allow(this@ApplianceActivity,intent);startActivity(intent)}){Text("Notification access")}
            val savedVideo by engine.video.collectAsState()
            if(savedVideo?.isFile==true)TextButton(onClick=engine::restoreVideo){Text("Show last saved video on Home")}
            val portraitStatus by engine.portraitStatus.collectAsState()
            Text(portraitStatus)
            Button(onClick=engine::syncPortrait) {Text("Link my Muse avatar")}
            var autoRead by remember {mutableStateOf(engine.store.prefs.getBoolean("auto_read",false))}
            Row(verticalAlignment=Alignment.CenterVertically) {Switch(autoRead,{autoRead=it;engine.store.prefs.edit().putBoolean("auto_read",it).apply()});Text("Read voice replies aloud")}
            var accurateCaptions by remember {mutableStateOf(engine.store.prefs.getBoolean("accurate_captions",false))}
            if(LiveTranscript.enhancedAvailable(this@ApplianceActivity))Row(verticalAlignment=Alignment.CenterVertically) {Text("Enhanced English captions",Modifier.weight(1f));Switch(accurateCaptions,{accurateCaptions=it;engine.store.prefs.edit().putBoolean("accurate_captions",it).apply();lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO){runCatching {LiveTranscript.warm(this@ApplianceActivity)}}})}
            Text("Enhanced favors accuracy; turn it off for faster first words. Both stay on this phone and never delay sending audio.",style=MaterialTheme.typography.bodySmall)
            val voiceChoices by speech.voices.collectAsState()
            val selectedVoice by speech.selectedVoice.collectAsState()
            val onlineVoices by speech.allowOnline.collectAsState()
            Text("Read-back voice")
            Row(verticalAlignment=Alignment.CenterVertically) {Text("Allow online Android voices",Modifier.weight(1f));Switch(onlineVoices,speech::setOnline)}
            Text("Online voices send reply text to your Android speech provider. Offline voices work without that connection.",style=MaterialTheme.typography.bodySmall)
            voiceChoices.take(8).forEach {voice->
                TextButton(onClick={speech.choose(voice.id);speech.read("Hello, I am your Muse. Here is your latest update.")}) {
                    Text((if(selectedVoice==voice.id)"✓ " else "")+voice.label)
                }
            }
            Text("These are installed Android voices. Muse has not exposed its own voice through this SDK.",style=MaterialTheme.typography.bodySmall)
            var haptics by remember {mutableStateOf(engine.store.prefs.getBoolean("haptics",true))}
            Row(verticalAlignment=Alignment.CenterVertically) {Switch(haptics,{haptics=it;engine.store.prefs.edit().putBoolean("haptics",it).apply()});Text("Button and response vibrations")}
            Text(if(NavigationGuard.connected)"Companion navigation guard connected" else "Companion navigation guard is not connected")
            Button(onClick={
                NavigationGuard.setEnabled(this@ApplianceActivity,true)
                if(NavigationGuard.connected)message="Companion navigation enabled"
                else {
                    val intent=Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    NavigationGuard.allow(this@ApplianceActivity,intent);startActivity(intent)
                    message="Re-enable Muse Launcher's navigation service in Android accessibility settings"
                }
            }) {Text("Enable companion navigation")}
            TextButton(onClick={NavigationGuard.setEnabled(this@ApplianceActivity,false);message="Navigation guard paused for maintenance"}) {Text("Pause navigation guard for maintenance")}
            if(dev.puck.BuildConfig.DEBUG) {
                var awake by remember {mutableStateOf(dev.puck.DebugScreen.enabled(this@ApplianceActivity))}
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Keep screen awake while debugging",Modifier.weight(1f))
                    Switch(awake,{awake=it;dev.puck.DebugScreen.set(this@ApplianceActivity,it)})
                }
            }
            if(AppliancePolicy.owner(this@ApplianceActivity)) {
                var ssid by remember {mutableStateOf("")}
                var wifiPassword by remember {mutableStateOf("")}
                OutlinedTextField(ssid,{ssid=it},label={Text("Wi-Fi network name")})
                OutlinedTextField(wifiPassword,{wifiPassword=it},label={Text("Wi-Fi password (blank for open network)")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
                Button(enabled=ssid.isNotBlank(),onClick={
                    message=runCatching {
                        val wifi=getSystemService(android.net.wifi.WifiManager::class.java)
                        val config=android.net.wifi.WifiConfiguration().apply {
                            SSID=org.json.JSONObject.quote(ssid)
                            if(wifiPassword.isBlank())allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                            else {require(wifiPassword.length in 8..63);preSharedKey=org.json.JSONObject.quote(wifiPassword)}
                        }
                        val network=wifi.addNetwork(config);check(network>=0);check(wifi.enableNetwork(network,true));check(wifi.reconnect());wifiPassword="";"Wi-Fi connection requested"
                    }.getOrDefault("Could not configure Wi-Fi; check device owner and credentials")
                }) {Text("Connect Wi-Fi")}
            }
            TextButton(onClick={NavigationGuard.setEnabled(this@ApplianceActivity,false);AppliancePolicy.companionEntry(this@ApplianceActivity,false);message="Legacy Muse Launcher Talk entry restored for maintenance"}) {Text("Restore legacy launcher entry")}
            OutlinedTextField(endpoint,{endpoint=it},label={Text("HTTPS agent server")})
            OutlinedTextField(token,{token=it},label={Text("Device token (leave blank to keep)")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
            Button(onClick={message=runCatching {engine.store.endpoint=endpoint;if(token.isNotBlank())engine.store.token=token;token="";"Saved"}.getOrDefault("Use a valid HTTPS address")}) {Text("Save connection")}
            Button(onClick={permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA,Manifest.permission.POST_NOTIFICATIONS))}) {Text("Grant microphone and camera")}
            Button(onClick={engine.interrupt();engine.calibrating=true;engine.pairs.value=0}) {Text("Observe physical button")}
            if(observing) {
                Text("Press/release your extra button three times. Observed key: ${raw?:"none"}; pairs: $pairs")
                Button(enabled=pairs>=3,onClick={engine.verify()}) {Text("Save verified button")}
            }
            Text("Saved key: ${engine.store.key?:"not verified"}")
            OutlinedTextField(pin,{pin=it},label={Text("New maintenance passphrase (8+ chars)")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
            Button(enabled=pin.length>=8,onClick={engine.store.setMaintenancePin(pin);pin="";message="Maintenance protected"}) {Text("Protect maintenance")}
            if(AppliancePolicy.owner(this@ApplianceActivity)) {
                Button(enabled=engine.store.hasPin(),onClick={AppliancePolicy.configure(this@ApplianceActivity);startLockTask();setup=false}) {Text("Enter Muse Launcher-only mode")}
                Button(onClick={AppliancePolicy.recover(this@ApplianceActivity);stopLockTask();message="Maintenance mode"}) {Text("Exit appliance for maintenance")}
            } else {
                Text("Device owner not provisioned. Session pinning can keep Muse Launcher on screen until exit or reboot.")
                Button(enabled=engine.store.hasPin(),onClick={
                    message=runCatching {startLockTask();"Accept Android's pinning prompt. This is session-only protection."}.getOrDefault("Could not start screen pinning")
                }) {Text("Pin Muse Launcher for this session")}
                TextButton(onClick={
                    message=runCatching {stopLockTask();"Session pinning ended"}.getOrDefault("Could not stop pinning")
                }) {Text("Exit pinning for maintenance")}
            }
            var museVoice by remember {mutableStateOf(engine.store.prefs.getBoolean("muse_voice",true))}
            Row(verticalAlignment=Alignment.CenterVertically) {Switch(museVoice,{museVoice=it;engine.store.prefs.edit().putBoolean("muse_voice",it).apply()});Text("Use paired Muse for voice")}
            var checkins by remember {mutableStateOf(engine.store.prefs.getBoolean("checkins",true))}
            Row(verticalAlignment=Alignment.CenterVertically) {Switch(checkins,{checkins=it;engine.store.prefs.edit().putBoolean("checkins",it).apply()});Text("Check for updates while Muse Launcher is visible")}
            Text("Tap the pet for an update; automatic checks are at most once every 30 minutes.",style=MaterialTheme.typography.bodySmall)
            Text("Paired Muse receives voice and attached photos directly. The HTTPS server is optional. Muse native updates remain service-dependent.",style=MaterialTheme.typography.bodySmall)
            Button(onClick=engine::retry) {Text("Retry queued turn")}
            Text("Retries reuse the turn ID. A failed provider or uncertain Muse outcome needs a fresh recording; it will not be sent twice.",style=MaterialTheme.typography.bodySmall)
            Text(message)
        }
        TextButton(onClick={engine.calibrating=false;setup=false}) {Text("Back to Muse Launcher")}
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent)
        if(!engine.calibrating){setup=false;playingVideo=false}
    }
    override fun onResume(){super.onResume();NavigationGuard.clear(this);if(::engine.isInitialized)engine.visible=true}
    override fun onPause(){videoPlayer?.pause();if(::speech.isInitialized)speech.stop();if(::engine.isInitialized){engine.visible=false;if(engine.cameraActive || !engine.rootConnected.value)engine.interrupt()};super.onPause()}
    override fun onDestroy(){if(::engine.isInitialized && engine.onButtonPress===stopVoiceForButton)engine.onButtonPress=null;if(::speech.isInitialized)speech.close();super.onDestroy()}
    override fun onWindowFocusChanged(focus:Boolean){super.onWindowFocusChanged(focus);if(!focus && ::engine.isInitialized)engine.interrupt()}
    @Composable private fun MessageBubble(source:String,title:String,text:String,maxText:androidx.compose.ui.unit.Dp,read:()->Unit,dismiss:()->Unit,speaking:Boolean) {
        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.Start) {
            Surface(Modifier.fillMaxWidth(),color=Color(0xff1c2c34),shape=androidx.compose.foundation.shape.RoundedCornerShape(topStart=22.dp,topEnd=22.dp,bottomEnd=22.dp,bottomStart=6.dp)) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(source,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                    if(title.isNotBlank() && title!=source && title!=text)Text(title,fontSize=17.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(text,Modifier.fillMaxWidth().heightIn(max=maxText).verticalScroll(rememberScrollState(),overscrollEffect=null),fontSize=20.sp,lineHeight=27.sp)
                    Row {TextButton(onClick=read){Text(if(speaking)"Stop voice" else "Read aloud")};TextButton(onClick=dismiss){Text("Dismiss")}}
                }
            }
        }
    }
    @Composable private fun VideoPane(file:File,close:()->Unit) {
        val profile by engine.portrait.collectAsState()
        var player by remember {mutableStateOf<android.widget.VideoView?>(null)}
        var error by remember {mutableStateOf("")}
        DisposableEffect(file){onDispose{player?.stopPlayback();if(videoPlayer===player)videoPlayer=null}}
        AndroidView(factory={c->android.widget.VideoView(c).also {view->
            player=view;videoPlayer=view
            view.setVideoPath(file.path)
            view.setMediaController(android.widget.MediaController(c).also {it.setAnchorView(view)})
            view.setOnPreparedListener {if(engine.visible)view.start()}
            view.setOnErrorListener {_,_,_->error="Video playback unavailable";true}
        }},modifier=Modifier.fillMaxWidth().height(350.dp))
        Text(error);TextButton(onClick=close){Text("Back to ${profile.name}")}
    }
    @android.annotation.SuppressLint("MissingPermission")
    @Composable private fun CameraPane(saved:(File)->Unit,close:()->Unit) {
        val profile by engine.portrait.collectAsState()
        var capture by remember {mutableStateOf<ImageCapture?>(null)}
        var recorder by remember {mutableStateOf<Recorder?>(null)}
        var recording by remember {mutableStateOf<Recording?>(null)}
        var previewUseCase by remember {mutableStateOf<Preview?>(null)}
        var photoBound by remember {mutableStateOf(false)}
        var error by remember {mutableStateOf("")}
        var taking by remember {mutableStateOf(false)}
        var videoStarted by remember {mutableStateOf(false)}
        var savingVideo by remember {mutableStateOf(false)}
        var elapsed by remember {mutableStateOf(0L)}
        var previewFile by remember {mutableStateOf<File?>(null)}
        val active=remember {java.util.concurrent.atomic.AtomicBoolean(true)}
        val provider=remember {ProcessCameraProvider.getInstance(this)}
        val shutter by engine.shutterGesture.collectAsState()
        val videoRequest by engine.videoRequest.collectAsState()
        var seenShutter by remember {mutableStateOf(engine.shutterGesture.value)}
        DisposableEffect(Unit){engine.cameraActive=true;onDispose{
            active.set(false);recording?.stop();recording=null;engine.videoRecording.value=false
            runCatching {engine.microphoneMode?.invoke(false)}
            engine.interrupt();engine.cameraActive=false;engine.cameraReady=false
            if(provider.isDone)runCatching {provider.get().unbindAll()}
        }}
        val ready=capture!=null && recorder!=null && !taking && !savingVideo && recording==null && previewFile==null
        SideEffect {engine.cameraReady=ready}
        val takePhoto:()->Unit={
            if(engine.cameraReady && capture!=null) {
                taking=true;engine.cameraReady=false;error=""
                CompanionHaptics.pulse(this,"camera")
                val file=File(engine.store.dir,"${UUID.randomUUID()}.jpg")
                runCatching {
                    if(!photoBound){provider.get().unbindAll();provider.get().bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,previewUseCase,capture);photoBound=true}
                    capture?.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),mainExecutor,object:ImageCapture.OnImageSavedCallback{
                        override fun onImageSaved(r:ImageCapture.OutputFileResults){
                            if(active.get() && engine.visible){engine.photo.value?.delete();engine.clearVideoAttachment();engine.photo.value=file;previewFile=file}
                            else file.delete()
                        }
                        override fun onError(e:ImageCaptureException){file.delete();if(active.get()){taking=false;error="Photo failed; reopen camera to try again"}}
                    })
                }.onFailure {file.delete();taking=false;error="Camera unavailable; reopen to try again"}
            }
        }
        LaunchedEffect(shutter){if(shutter!=seenShutter){seenShutter=shutter;takePhoto()}}
        LaunchedEffect(videoRequest) {
            if(videoRequest.held && recording==null && engine.cameraReady) {
                val file=File(engine.store.dir,"video-${UUID.randomUUID()}.mp4")
                val session=videoRequest.id
                val audioAllowed=engine.store.prefs.getBoolean("video_audio",true) && checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
                if(getSystemService(android.media.AudioManager::class.java).mode!=android.media.AudioManager.MODE_NORMAL){error="Video paused during a call";engine.interrupt()}
                else runCatching {
                    engine.cameraReady=false;error="";elapsed=0;videoStarted=false
                    if(audioAllowed)engine.microphoneMode?.invoke(true)
                    engine.videoRecording.value=true
                    var pending=recorder!!.prepareRecording(this@ApplianceActivity,FileOutputOptions.Builder(file).setFileSizeLimit(8_000_000).build())
                    if(audioAllowed)pending=pending.withAudioEnabled()
                    engine.store.prefs.edit().putString("camera_video_stage","starting").apply()
                    recording=pending.start(mainExecutor){event->
                        when(event) {
                            is VideoRecordEvent.Start->{if(active.get() && engine.videoRequest.value.id==session)videoStarted=true;engine.store.prefs.edit().putString("camera_video_stage","recording").apply();if(active.get() && engine.videoRequest.value.id==session && engine.videoRequest.value.held)CompanionHaptics.pulse(this@ApplianceActivity,"listening")}
                            is VideoRecordEvent.Status->{if(active.get() && engine.videoRequest.value.id==session)elapsed=event.recordingStats.recordedDurationNanos/1_000_000_000}
                            is VideoRecordEvent.Finalize->{
                                engine.store.prefs.edit().putString("camera_video_stage","finalized_${event.error}").apply()
                                recording=null
                                if(engine.videoRequest.value.id==session){engine.videoRecording.value=false;runCatching {engine.microphoneMode?.invoke(false)}}
                                val cancelled=engine.videoRequest.value.id!=session || engine.videoRequest.value.cancelled || !active.get() || !engine.visible
                                val valid=(!event.hasError() || event.error==VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED) && file.length()>0
                                if(!cancelled && valid){
                                    if(engine.videoRequest.value.held)engine.interrupt("Video stopped at size limit")
                                    engine.saveVideo(file);previewFile=file;CompanionHaptics.pulse(this@ApplianceActivity,"reply")
                                }else {file.delete();if(active.get()){savingVideo=false;error=if(cancelled)"Video cancelled" else "Video failed; release and try again";if(engine.videoRequest.value.held)engine.interrupt()}}
                            }
                        }
                    }
                }.onFailure {file.delete();recording=null;engine.videoRecording.value=false;runCatching {engine.microphoneMode?.invoke(false)};error="Could not record video";engine.interrupt()}
            } else if(!videoRequest.held && recording!=null) {
                savingVideo=true;recording?.stop();CompanionHaptics.pulse(this@ApplianceActivity,"sending")
            }
        }
        LaunchedEffect(previewFile){previewFile?.let {file->kotlinx.coroutines.delay(1000);if(active.get() && engine.visible){if(file.extension=="mp4")close() else saved(file)}}}
        val bitmap=remember(previewFile){previewFile?.let {file->
            if(file.extension=="mp4")runCatching {android.media.MediaMetadataRetriever().use {it.setDataSource(file.path);it.getScaledFrameAtTime(0,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,640,480)?.asImageBitmap()}}.getOrNull()
            else android.graphics.BitmapFactory.decodeFile(file.path)?.asImageBitmap()
        }}
        if(previewFile!=null) {
            if(bitmap!=null)Image(bitmap,"Captured preview",Modifier.fillMaxWidth().height(250.dp),contentScale=ContentScale.Fit)
            Text(if(previewFile?.extension=="mp4")"Video saved · returning to ${profile.name}" else "Photo attached · returning to ${profile.name}")
        } else {
            AndroidView(factory={c->PreviewView(c).also {view->
                view.implementationMode=PreviewView.ImplementationMode.COMPATIBLE
                view.contentDescription="Camera preview"
                provider.addListener({if(!active.get())return@addListener;runCatching {
                    val preview=Preview.Builder().build().also {it.setSurfaceProvider(view.surfaceProvider)};previewUseCase=preview
                    val image=ImageCapture.Builder().setTargetResolution(android.util.Size(1280,960)).build()
                    val videoRecorder=Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD,FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                    val videoCapture=VideoCapture.withOutput(videoRecorder)
                    engine.store.prefs.edit().putString("camera_video_stage","binding").apply()
                    runCatching {provider.get().bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,image,videoCapture);photoBound=true}
                        .getOrElse {provider.get().unbindAll();provider.get().bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,videoCapture);photoBound=false}
                    capture=image;recorder=videoRecorder;engine.store.prefs.edit().putString("camera_video_stage","ready").apply()
                }.onFailure{error="Camera unavailable";engine.store.prefs.edit().putString("camera_video_stage","bind_failed_"+it.javaClass.simpleName).apply()}},mainExecutor)
            }},modifier=Modifier.fillMaxWidth().height(250.dp).clipToBounds())
            Spacer(Modifier.height(12.dp))
            if(recording!=null || savingVideo)Text(if(savingVideo)"Saving video…" else if(!videoStarted)"Starting video…" else "● Recording video · ${elapsed}s · release to stop",color=Color(0xffffaaa0))
            else {Button(enabled=ready,onClick=takePhoto){Text(if(taking)"Taking photo…" else "Take photo")};Text(if(engine.store.key!=null)"Tap red for photo · Hold red for video" else "Take a photo or hold below for video")}
            if(engine.store.key==null)HoldControl("Hold to record video",enabled=recorder!=null && !taking && !savingVideo && previewFile==null,press={engine.edge(true)},release={engine.edge(false)},cancel={engine.interrupt();engine.edge(false)})
            Text(error);TextButton(onClick=close){Text("Cancel")}
        }
    }

}
@Composable private fun CompanionStatus(status:String) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val owner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val animate=lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f
    val busy=status in ApplianceEngine.BUSY_STATES || status=="Checking for updates"
    var beat by remember {mutableStateOf(false)}
    LaunchedEffect(status,animate){while(animate && status in setOf("Listening","Speaking")){beat=!beat;kotlinx.coroutines.delay(350)}}
    val label=when(status){"Sending"->"Sending your audio";"Muse thinking"->"Muse is thinking";"Muse replying"->"Reply arriving";"Listening"->"Listening · release to send";"Resting"->"Ready";else->status}
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
        if(busy && animate)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
        else if(status in setOf("Listening","Speaking"))Row(Modifier.height(26.dp),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalAlignment=Alignment.CenterVertically) {
            repeat(4){i->val height by androidx.compose.animation.core.animateFloatAsState(if(animate && (beat==(i%2==0)))24f else 8f,label="Voice activity")
                Surface(Modifier.width(4.dp).height(height.dp),color=MaterialTheme.colorScheme.primary,shape=androidx.compose.foundation.shape.RoundedCornerShape(2.dp)){} }
        } else Canvas(Modifier.size(12.dp)){drawCircle(if(status.contains("unavailable",true))Color(0xfff3b79d) else Color(0xffbde2c5))}
        Text(label,style=MaterialTheme.typography.titleMedium)
    }
}
@Composable private fun Pet(status:String,portrait:MusePortrait.Profile,mood:String,modifier:Modifier) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val owner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    var raised by remember {mutableStateOf(false)}
    val animate=android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f && lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(animate,status) {while(animate){raised=!raised;kotlinx.coroutines.delay(if(status=="Speaking")350 else if(status in ApplianceEngine.BUSY_STATES)700 else 1600)}}
    val bob by androidx.compose.animation.core.animateFloatAsState(if(animate && raised)3f else 0f,label="Pet breath")
    val atlas=remember(portrait.animation,portrait.updated){portrait.animation?.let {android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap()}}
    var frame by remember {mutableStateOf(0)}
    val row=when {status=="Speaking"->3;status=="Listening"->1;status in ApplianceEngine.BUSY_STATES || status in setOf("Checking for updates","New update")->2;else->0}
    LaunchedEffect(animate,row,atlas){frame=0;while(animate && atlas!=null){kotlinx.coroutines.delay(if(row==0)500 else 220);frame=(frame+1)%6}}
    if(atlas!=null) {
        val column=if(frame<4)frame else 6-frame
        Canvas(modifier){
            val left=atlas.width*column/4;val top=atlas.height*row/4
            val right=atlas.width*(column+1)/4;val bottom=atlas.height*(row+1)/4
            val edge=size.minDimension.toInt()
            drawImage(atlas,srcOffset=androidx.compose.ui.unit.IntOffset(left,top),srcSize=androidx.compose.ui.unit.IntSize(right-left,bottom-top),dstOffset=androidx.compose.ui.unit.IntOffset((size.width.toInt()-edge)/2,(size.height.toInt()-edge)/2),dstSize=androidx.compose.ui.unit.IntSize(edge,edge),filterQuality=androidx.compose.ui.graphics.FilterQuality.High)
        }
        return
    }
    val portraitBitmap=remember(portrait.updated){portrait.image?.let {android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap()}}
    if(portraitBitmap!=null) {
        val tilt by androidx.compose.animation.core.animateFloatAsState(if(animate && status in ApplianceEngine.BUSY_STATES)if(raised)3f else -3f else 0f,label="Muse thought")
        Box(modifier,contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize()){drawCircle(if(mood=="concerned")Color(0xffffc28d) else if(status=="Listening")Color(0xffbde2c5) else Color(0xffabcde8),size.minDimension*.46f,alpha=if(raised).12f else .07f)}
            Image(portraitBitmap,contentDescription=portrait.name+" Muse avatar",modifier=Modifier.fillMaxSize(.90f).graphicsLayer {translationY=bob;rotationZ=tilt;scaleX=if(status=="Speaking" && raised)1.035f else 1f;scaleY=scaleX},contentScale=ContentScale.Fit)
        }
        return
    }
    Canvas(modifier) {
        val w=size.width;val h=size.height
        drawRoundRect(Color(0xffe8c697),Offset(w*.12f,h*.16f+bob),Size(w*.76f,h*.68f),androidx.compose.ui.geometry.CornerRadius(w*.2f))
        val eye=if(status in setOf("Resting","Reply ready"))h*.025f else h*.055f
        for(x in listOf(.34f,.66f))drawOval(Color(0xff172b23),Offset(w*x-w*.03f,h*.43f+bob),Size(w*.06f,eye))
        if(status=="Speaking")drawOval(Color(0xff172b23),Offset(w*.44f,h*.56f+bob),Size(w*.12f,if(raised)h*.10f else h*.035f))
        else if(status=="Listening")drawCircle(Color(0xff172b23),w*.04f,Offset(w*.5f,h*.65f+bob))
        else drawArc(Color(0xff172b23),0f,180f,false,Offset(w*.42f,h*.55f+bob),Size(w*.16f,h*.12f),style=androidx.compose.ui.graphics.drawscope.Stroke(w*.02f))
    }
}
