package dev.puck.appliance

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.*
import kotlinx.coroutines.*

/** Idle input/update service. The microphone is opened only after a long press. */
class ApplianceService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var socket:LocalSocket?=null
    private val screenOff=object:android.content.BroadcastReceiver() {
        override fun onReceive(c:android.content.Context,i:Intent) {ApplianceEngine.get(c).interrupt("Screen switched off; nothing sent")}
    }
    override fun onCreate() {
        super.onCreate()
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("appliance","Muse Launcher companion",NotificationManager.IMPORTANCE_LOW))
        val launch=PendingIntent.getActivity(this,0,Intent(this,ApplianceActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val note=Notification.Builder(this,"appliance").setSmallIcon(dev.puck.R.drawable.puck_icon).setContentTitle("Muse Launcher is available").setContentText("Button and updates active; microphone starts only while holding").setContentIntent(launch).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(42,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(42,note,0)
        registerReceiver(screenOff,android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
        val engine=ApplianceEngine.get(this)
        scope.launch {while(isActive) {
            if((engine.status.value=="Listening" || engine.videoRecording.value) && getSystemService(android.media.AudioManager::class.java).mode!=android.media.AudioManager.MODE_NORMAL) withContext(Dispatchers.Main) {engine.interrupt("Audio interrupted; nothing sent")}
            delay(250)
        }}
        engine.microphoneMode={ recording ->
            val type=if(recording)ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else if(Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            startForeground(42,note,type)
        }
        scope.launch {
            while(isActive) {
                try {
                    LocalSocket().use { s ->
                        socket=s;s.connect(LocalSocketAddress("puck-button",LocalSocketAddress.Namespace.ABSTRACT))
                        check(s.peerCredentials.uid==0)
                        withContext(Dispatchers.Main) {engine.rootConnected.value=true}
                        val reader=s.inputStream.bufferedReader()
                        while(isActive) {
                            val line=reader.readLine()?:break
                            check(line=="D" || line=="U")
                            withContext(Dispatchers.Main) {
                                if(engine.store.key!=null) {
                                    if(line=="D")startActivity(Intent(this@ApplianceService,ApplianceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                                    engine.edge(line=="D")
                                }
                            }
                        }
                    }
                } catch(_:Exception) { }
                withContext(Dispatchers.Main) {if(engine.rootConnected.value)engine.interrupt("Button helper disconnected");engine.rootConnected.value=false}
                delay(5000)
            }
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_STICKY
    override fun onBind(intent:Intent?)=null
    override fun onDestroy() {runCatching {unregisterReceiver(screenOff)};socket?.close();scope.cancel();ApplianceEngine.get(this).interrupt();super.onDestroy()}
}
class ApplianceBoot:android.content.BroadcastReceiver() {
    override fun onReceive(c:android.content.Context,i:Intent) {
        if(i.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        if(!AppliancePolicy.owner(c))return
        runCatching { c.startForegroundService(Intent(c,ApplianceService::class.java)) }
        runCatching {c.startActivity(Intent(c,ApplianceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    }
}
