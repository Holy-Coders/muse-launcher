package dev.puck

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.content.ComponentName
import android.content.pm.PackageManager
import dev.puck.appliance.*

@RunWith(AndroidJUnit4::class)
class ApplianceShellTest {
    @Test fun lockedSurfaceAndPrivateServiceAreDeclared() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val activity=c.packageManager.getActivityInfo(ComponentName(c,ApplianceActivity::class.java),0)
        assertTrue(activity.exported)
        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK,activity.launchMode)
        val service=c.packageManager.getServiceInfo(ComponentName(c,ApplianceService::class.java),0)
        assertFalse(service.exported)
        assertTrue(service.foregroundServiceType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
    }
    @Test fun adminRequiresAndroidSystemBindingPermission() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val info=c.packageManager.getReceiverInfo(ComponentName(c,ApplianceAdmin::class.java),PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_DEVICE_ADMIN",info.permission)
        assertTrue(info.metaData.containsKey("android.app.device_admin"))
    }
}
