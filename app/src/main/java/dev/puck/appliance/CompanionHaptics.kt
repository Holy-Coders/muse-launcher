package dev.puck.appliance

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object CompanionHaptics {
    fun pulse(c:Context,event:String) {
        if(!c.getSharedPreferences("appliance",Context.MODE_PRIVATE).getBoolean("haptics",true))return
        if(android.provider.Settings.System.getInt(c.contentResolver,android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,1)==0)return
        val v=if(Build.VERSION.SDK_INT>=31)c.getSystemService(VibratorManager::class.java).defaultVibrator else c.getSystemService(Vibrator::class.java)
        if(!v.hasVibrator())return
        val pattern=when(event){"reply"->longArrayOf(0,18,65,18);"error"->longArrayOf(0,55,60,55);"listening"->longArrayOf(0,28);"camera"->longArrayOf(0,35);else->longArrayOf(0,12)}
        runCatching {v.vibrate(VibrationEffect.createWaveform(pattern,-1))}
    }
}
