package dev.puck

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.puck.appliance.ApplianceActivity
import dev.puck.appliance.ApplianceEngine
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ACTION_DIAL with reserved fiction number only; never taps Voice's Call control. */
@RunWith(AndroidJUnit4::class)
class ApplianceCallTest {
    @Test fun requestedAppOpensAndUnrequestedAppReturnsToPuck() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val automation=inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckNavigationGuard")=="true")
        val c=inst.targetContext
        assumeTrue(!c.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        fun shell(command:String)=automation.executeShellCommand(command).let {fd->android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {it.readBytes()}}
        // Instrumentation restarts the target process; rebind its existing enabled service.
        val services=String(shell("settings get secure enabled_accessibility_services")).trim()
        require(services.matches(Regex("[A-Za-z0-9_./:$-]+")))
        val component="dev.puck.launcher/dev.puck.appliance.NavigationGuard"
        val other=services.split(":").filter {it!=component}.joinToString(":")
        shell("am start -n dev.puck.launcher/dev.puck.appliance.ApplianceActivity")
        if(other.isEmpty())shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services $other")
        Thread.sleep(250)
        shell("settings put secure enabled_accessibility_services $services")
        shell("settings put secure accessibility_enabled 1")
        val deadline=System.currentTimeMillis()+10000
        while(!dev.puck.appliance.NavigationGuard.connected && System.currentTimeMillis()<deadline)Thread.sleep(100)
        assertTrue("Navigation guard not connected",dev.puck.appliance.NavigationGuard.connected)
        val app=DeviceCapabilities.launchableApps(c).first {it.packageName=="com.google.android.calculator"}
        val engine=ApplianceEngine.get(c)
        fun awaitPackage(pkg:String) {
            val end=System.currentTimeMillis()+8000
            while(automation.rootInActiveWindow?.packageName!=pkg && System.currentTimeMillis()<end)Thread.sleep(100)
            assertEquals(pkg,automation.rootInActiveWindow?.packageName)
        }
        ActivityScenario.launch(ApplianceActivity::class.java).use {scenario ->
            scenario.onActivity {engine.pendingPhone.value=ApplianceEngine.PhoneRequest(dev.puck.core.WorkspaceRequest.Confirmation("guarded-calculator-fixture",
                dev.puck.core.DeviceAction.OpenApp(app.packageName,app.activityName)),true)}
            try {
                awaitPackage(app.packageName)
                assertNull(engine.pendingPhone.value)
                shell("input keyevent 3");awaitPackage(c.packageName)
                val before=dev.puck.appliance.NavigationGuard.redirects(c)
                shell("am start -a android.settings.SETTINGS")
                val end=System.currentTimeMillis()+8000
                while(dev.puck.appliance.NavigationGuard.redirects(c)==before && System.currentTimeMillis()<end)Thread.sleep(100)
                assertTrue("Unrequested app was not redirected",dev.puck.appliance.NavigationGuard.redirects(c)>before)
                awaitPackage(c.packageName)
            } finally {engine.cancelPhone();shell("am start -n dev.puck.launcher/dev.puck.appliance.ApplianceActivity")}
        }
    }
    @Test fun foregroundAppRequestOpensCalculatorInsidePinnedTask() {
        val inst=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckAppHandoff")=="true")
        val c=inst.targetContext
        assumeTrue(!c.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val app=DeviceCapabilities.launchableApps(c).firstOrNull {it.packageName=="com.google.android.calculator"}
        assertNotNull("Installed calculator fixture unavailable",app)
        val engine=ApplianceEngine.get(c)
        fun shell(command:String)=inst.uiAutomation.executeShellCommand(command).let {fd->android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {it.readBytes()}}
        val exitSetting=String(shell("settings get secure lock_to_app_exit_locked")).trim()
        ActivityScenario.launch(ApplianceActivity::class.java).use {scenario ->
            var task=0
            scenario.onActivity {task=it.taskId}
            shell("am task lock $task")
            try {
                scenario.onActivity {
                    engine.pendingPhone.value=ApplianceEngine.PhoneRequest(dev.puck.core.WorkspaceRequest.Confirmation("calculator-fixture",
                        dev.puck.core.DeviceAction.OpenApp(app!!.packageName,app.activityName)),true)
                }
                val deadline=System.currentTimeMillis()+8000
                while(inst.uiAutomation.rootInActiveWindow?.packageName!=app!!.packageName && System.currentTimeMillis()<deadline)Thread.sleep(100)
                assertEquals(app!!.packageName,inst.uiAutomation.rootInActiveWindow?.packageName)
                assertNull("App action was not consumed",engine.pendingPhone.value)
                assertTrue("Pinning was lost",String(shell("dumpsys activity activities")).contains("mLockTaskModeState=PINNED"))
            } finally {
                engine.cancelPhone()
                shell("am start -n dev.puck.launcher/dev.puck.appliance.ApplianceActivity")
                shell("settings put secure lock_to_app_exit_locked 0")
                try {shell("am task lock stop")} finally {shell("settings put secure lock_to_app_exit_locked $exitSetting")}
            }
        }
    }
    @Test fun reviewedPhoneActionCanBeClaimedOnlyOnce() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val engine=ApplianceEngine.get(c)
        val action=dev.puck.core.DeviceAction.Message("Generated draft fixture")
        engine.pendingPhone.value=ApplianceEngine.PhoneRequest(dev.puck.core.WorkspaceRequest.Confirmation("draft-fixture",action),false)
        assertNull(engine.claimPhone("wrong-id"))
        engine.selectPhoneContact("draft-fixture","+12025550100")
        val claimed=engine.claimPhone("draft-fixture") as dev.puck.core.DeviceAction.Message
        assertEquals("+12025550100",claimed.number)
        assertEquals("Generated draft fixture",claimed.body)
        assertNull(engine.claimPhone("draft-fixture"))
    }
    @Test fun pinnedCompanionOpensGoogleVoiceDialerWithoutCalling() {
        val inst=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckCallHandoff")=="true")
        val c=inst.targetContext
        assumeTrue(!c.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val engine=ApplianceEngine.get(c)
        val call=ApplianceEngine.CallRequest("reserved-call-fixture","+12025550100")
        ActivityScenario.launch(ApplianceActivity::class.java).use {scenario ->
            var task=0
            scenario.onActivity {task=it.taskId}
            fun shell(command:String)=inst.uiAutomation.executeShellCommand(command).let {fd->android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {it.readBytes()}}
            val exitSetting=String(shell("settings get secure lock_to_app_exit_locked")).trim()
            shell("am task lock $task")
            Thread.sleep(300)
            scenario.onActivity {engine.pendingCall.value=call;it.openGoogleVoiceCall(call)}
            try {
                val deadline=System.currentTimeMillis()+8000
                var voice=false
                var numberShown=false
                while(System.currentTimeMillis()<deadline) {
                    val root=inst.uiAutomation.rootInActiveWindow
                    if(root?.packageName=="com.google.android.apps.googlevoice") {
                        voice=true
                        fun visit(n:android.view.accessibility.AccessibilityNodeInfo) {
                            val digits=n.text?.toString()?.filter(Char::isDigit).orEmpty()
                            if(digits.endsWith("2025550100"))numberShown=true
                            for(i in 0 until n.childCount)n.getChild(i)?.let(::visit)
                        }
                        visit(root)
                        if(numberShown)break
                    }
                    Thread.sleep(150)
                }
                c.filesDir.resolve("call-handoff-fixture.json").writeText(org.json.JSONObject().put("voice_foreground",voice).put("reserved_number_shown",numberShown).put("call_control_pressed",false).toString())
                assertTrue("Google Voice did not become foreground from pinned Puck",voice)
                assertTrue("Reserved number was not shown for review",numberShown)
            } finally {
                if(engine.pendingCall.value?.id==call.id)engine.cancelCall()
                // Let ActivityScenario close without Android re-locking the debug device.
                shell("settings put secure lock_to_app_exit_locked 0")
                try {shell("am task lock stop")} finally {
                    if(exitSetting=="null")shell("settings delete secure lock_to_app_exit_locked")
                    else shell("settings put secure lock_to_app_exit_locked $exitSetting")
                }
                shell("am start -n dev.puck.launcher/dev.puck.appliance.ApplianceActivity")
            }
        }
    }
}
