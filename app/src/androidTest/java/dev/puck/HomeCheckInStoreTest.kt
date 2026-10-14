package dev.puck

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.puck.core.HomeCheckIn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeCheckInStoreTest {
    @Test fun cooldownClaimIsAtomicPersistedAndRespectsOptOut() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.cacheDir.resolve("check-in-test-${java.util.UUID.randomUUID()}.preferences_pb")
        var job = SupervisorJob()
        fun preferences() = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        var prefs = preferences()
        val store = DraftStore(context, prefs)
        try {
            store.setHomeCheckIns(true)
            val now = System.currentTimeMillis()
            val claims = (1..8).map { async(Dispatchers.IO) { DraftStore(context, prefs).claimHomeCheckIn(now) } }.awaitAll()
            assertEquals(1, claims.count { it })
            store.dismissUpdate("test-update")
            job.cancelAndJoin()
            job = SupervisorJob()
            prefs = preferences()
            val reopened = DraftStore(context, prefs)
            assertEquals(setOf("test-update"), reopened.dismissedUpdates.first())
            assertTrue("test-update" in reopened.readUpdates.first())
            assertFalse(reopened.claimHomeCheckIn(now + HomeCheckIn.INTERVAL_MILLIS - 1))
            reopened.setHomeCheckIns(false)
            assertFalse(reopened.claimHomeCheckIn(now + HomeCheckIn.INTERVAL_MILLIS))
            reopened.setHomeCheckIns(true)
            assertFalse(reopened.claimHomeCheckIn(now - 1))
            assertTrue(reopened.claimHomeCheckIn(now + HomeCheckIn.INTERVAL_MILLIS))
        } finally { job.cancelAndJoin(); file.delete() }
    }
}
