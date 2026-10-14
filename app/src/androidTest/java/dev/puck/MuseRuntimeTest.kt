package dev.puck

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MuseRuntimeTest {
    @Test fun androidNativeCryptoAndNoiseRuntimeLoadsWithoutCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        synchronized(Python::class.java) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        }
        assertTrue(Python.getInstance().getModule("puck_muse").callAttr("crypto_smoke").toBoolean())
    }
    @Test fun vaultCiphertextReopensAndDoesNotContainPlaintext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = "instrumented-test-only"
        val sentinel = "puck-test-private-sentinel"
        val prefs = context.getSharedPreferences("muse_private", Context.MODE_PRIVATE)
        try {
            assertTrue(MuseVault(context).save(key, sentinel))
            assertFalse(prefs.getString(key, "")!!.contains(sentinel))
            assertEquals(sentinel, MuseVault(context).read(key))
        } finally { prefs.edit().remove(key).commit() }
    }
}
