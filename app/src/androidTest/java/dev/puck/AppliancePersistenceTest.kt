package dev.puck

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.puck.appliance.ApplianceStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated context keeps fixtures away from personal app data and pairing. */
@RunWith(AndroidJUnit4::class)
class AppliancePersistenceTest {
    private fun isolated():android.content.Context {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val suffix=java.util.UUID.randomUUID().toString()
        return object:android.content.ContextWrapper(base) {
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences("fixture_$suffix",mode)
            override fun getFilesDir()=java.io.File(base.cacheDir,"fixture_$suffix").apply {mkdirs()}
        }
    }
    @Test fun verifiedProfileSurvivesReopenAndFirmwareMismatchDisablesIt() {
        val context=isolated()
        val a=ApplianceStore(context);a.prefs.edit().clear().commit()
        a.key=305;assertEquals(305,ApplianceStore(context).key)
        a.prefs.edit().putString("firmware","other firmware").commit()
        assertNull(ApplianceStore(context).key);a.prefs.edit().clear().commit()
    }
    @Test fun maintenanceAuthenticationAndEndpointValidation() {
        val context=isolated()
        val a=ApplianceStore(context);a.prefs.edit().clear().commit()
        a.setMaintenancePin("fixture passphrase")
        assertTrue(ApplianceStore(context).authenticates("fixture passphrase"))
        a.prefs.edit().putLong("pin_retry",0).commit()
        assertFalse(a.authenticates("wrong passphrase"))
        for(url in listOf("http://host","https://user:pass@host","https://host?token=secret")) {
            try {a.endpoint=url;fail("Accepted $url")}catch(_:IllegalArgumentException){}
        }
        a.endpoint="https://example.invalid";assertEquals(a.endpoint,ApplianceStore(context).endpoint)
        a.prefs.edit().clear().commit()
    }
}
