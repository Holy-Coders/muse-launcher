package dev.puck.appliance
import org.junit.Assert.*
import org.junit.Test

class NavigationGateTest {
    private fun allowed(pkg:String,now:Long=10,expiry:Long=20,enabled:Boolean=true,locked:Boolean=false)=
        NavigationGate.allowed(pkg,"puck",setOf("requested.app"),now,expiry,enabled,locked)
    @Test fun handoffAllowsOnlyItsPackagesAndExpires() {
        assertTrue(allowed("requested.app"))
        assertFalse(allowed("other.app"))
        assertFalse(allowed("requested.app",now=20))
        assertFalse(NavigationGate.allowed("requested.app","puck",emptySet(),10,20,true,false))
    }
    @Test fun nativeUnlockAndExplicitMaintenanceAreNeverIntercepted() {
        assertTrue(allowed("puck"));assertTrue(allowed("com.android.systemui"))
        assertTrue(allowed("com.google.android.apps.googlevoice"))
        assertTrue(allowed("com.android.settings",enabled=false))
        assertTrue(allowed("other.app",locked=true))
        assertFalse(allowed("com.android.settings"))
        assertTrue(NavigationGate.allowed("keyboard","puck",emptySet(),10,20,true,false,setOf("keyboard")))
    }
}
