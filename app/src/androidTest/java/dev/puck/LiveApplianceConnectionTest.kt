package dev.puck

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.puck.core.ConnectionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicit live tests; only generated fixtures, no personal images or microphone input. */
@RunWith(AndroidJUnit4::class)
class LiveApplianceConnectionTest {
    @Test fun generatedVideoIsUnderstoodAndJournalKeepsSavedClip() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckLiveVideo")=="true")
        val inst=InstrumentationRegistry.getInstrumentation()
        val c=inst.targetContext
        val runtime=MuseRuntime.get(c)
        assumeTrue(runtime.vault.enabled && runtime.vault.hasCredentials())
        runtime.connect()
        withTimeout(60_000){runtime.connection.first {it==ConnectionStatus.MuseConnected}}
        val engine=withContext(Dispatchers.Main){dev.puck.appliance.ApplianceEngine.get(c)}
        val oldUpdate=engine.update.value;val oldStatus=engine.status.value
        val id=UUID.randomUUID().toString()
        val clip=java.io.File(engine.store.dir,"fixture-$id.mp4")
        val bytes=inst.uiAutomation.executeShellCommand("cat /sdcard/Download/muse-launcher-generated-video.mp4").let {fd->android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {it.readBytes()}}
        clip.writeBytes(bytes)
        val journal=engine.store.writeTurn(id,null,null,clip)
        try {
            withContext(Dispatchers.Main){engine.retry()}
            withTimeout(120_000){while(runCatching {org.json.JSONObject(journal.readText()).optString("state")}.getOrDefault("")!="sent")delay(200)}
            val reply=engine.update.value.lowercase()
            assertTrue("Muse did not see both phases of the generated video",reply.contains("red") && reply.contains("green"))
            assertTrue("Muse ignored the video audio request",reply.contains("video audio works"))
            assertTrue("Sending deleted saved clip",clip.exists())
            c.filesDir.resolve("appliance-video-result.json").writeText(org.json.JSONObject().put("sent",true).put("both_colors_understood",true).put("saved_clip_retained",true).put("embedded_audio_followed",true).put("fixture_bytes",bytes.size).toString())
        } finally {
            clip.delete();journal.delete()
            withContext(Dispatchers.Main){engine.update.value=oldUpdate;engine.store.prefs.edit().putString("update",oldUpdate).apply();engine.status.value=oldStatus}
        }
    }
    @Test fun companionProducesOtherPhoneActionsWithoutExecutingThem() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckLiveMuse")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val runtime=MuseRuntime.get(c)
        assumeTrue(runtime.vault.enabled && runtime.vault.hasCredentials())
        runtime.connect()
        withTimeout(60_000){runtime.connection.first {it==ConnectionStatus.MuseConnected}}
        val cases=listOf(
            "Open the camera inside Puck. Protocol fixture only: return the camera action; do not use tools." to dev.puck.core.DeviceAction.Camera,
            "Draft a Google Voice message saying Generated draft fixture. The recipient number is unknown; I will choose it locally. Protocol fixture only: return the message action; do not use tools or send anything." to dev.puck.core.DeviceAction.Message("Generated draft fixture")
        )
        for((text,expected) in cases) {
            val id=UUID.randomUUID().toString()
            val reply=withContext(Dispatchers.IO){runtime.send(id,text,"appliance")}
            assertTrue("Muse phone action reply unavailable",reply.optBoolean("ok"))
            val request=dev.puck.core.MuseReply.decodeAppliance(id,reply.getString("text"),runtime.replyApps()).request as dev.puck.core.WorkspaceRequest.Confirmation
            assertEquals(expected,request.action)
        }
    }
    @Test fun unknownContactProducesGoogleVoiceCallProposal() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckLiveMuse")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val runtime=MuseRuntime.get(c)
        assumeTrue(runtime.vault.enabled && runtime.vault.hasCredentials())
        runtime.connect()
        withTimeout(60_000){runtime.connection.first {it==ConnectionStatus.MuseConnected}}
        val id=UUID.randomUUID().toString()
        val reply=withContext(Dispatchers.IO) {runtime.send(id,"Call a contact using Google Voice. This is a protocol fixture: the number is unknown and I will select it locally. Do not use tools or place a call.","appliance")}
        assertTrue("Muse call proposal unavailable",reply.optBoolean("ok"))
        val request=dev.puck.core.MuseReply.decodeAppliance(id,reply.getString("text")).request as dev.puck.core.WorkspaceRequest.Confirmation
        assertEquals(dev.puck.core.DeviceAction.Dial(),request.action)
    }
    @Test fun persistentMuseConversationAndPhoto() = runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckLiveMuse")=="true")
        val c=inst.targetContext
        val runtime=MuseRuntime.get(c)
        assumeTrue(runtime.vault.enabled && runtime.vault.hasCredentials())
        runtime.connect()
        withTimeout(60_000) {runtime.connection.first {it==ConnectionStatus.MuseConnected}}
        val id=UUID.randomUUID().toString()
        try {
            val first=withContext(Dispatchers.IO) {runtime.send(id,"Puck connection test. Say briefly that you can hear me. Do not use tools or perform any actions.","native")}
            assertTrue("Muse text reply unavailable",first.optBoolean("ok"))
            assertTrue(first.optString("text").isNotBlank())
            val bitmap=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.MAGENTA)}
            val bytes=java.io.ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)}.toByteArray()
            bitmap.recycle()
            val second=withContext(Dispatchers.IO) {runtime.send(UUID.randomUUID().toString(),"Puck photo test: what is the main color in this generated test image? Reply briefly. Do not use tools or perform actions.","native",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))}
            assertTrue("Muse photo reply unavailable",second.optBoolean("ok"))
            val reply=second.optString("text").lowercase()
            assertTrue("Muse did not identify fixture color",listOf("magenta","pink","purple","fuchsia").any {it in reply})
        } finally {
            c.filesDir.resolve("appliance-live-muse-stats.json").writeText(org.json.JSONObject(runtime.linkStats()).put("status",runtime.setupStatus.value).toString())
        }
    }
    @Test fun attachedVoiceReceivesAnAnswer() = runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckLiveMuse")=="true")
        val runtime=MuseRuntime.get(c)
        assumeTrue(runtime.vault.enabled && runtime.vault.hasCredentials())
        runtime.connect()
        withTimeout(60_000) {runtime.connection.first {it==ConnectionStatus.MuseConnected}}
        try {
            val file=java.io.File("/sdcard/Download/puck-generated-voice-test.wav")
            val bytes=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat ${file.path}").let { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {it.readBytes()} }
            assertTrue(bytes.size>3244)
            val result=withContext(Dispatchers.IO) {runtime.send(UUID.randomUUID().toString(),"The attached voice recording contains the user's question. Transcribe it and answer briefly. Do not use external tools or perform actions.","native","",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))}
            assertTrue("Muse audio reply unavailable",result.optBoolean("ok"))
            assertTrue("Muse did not answer audio fixture",result.optString("text").lowercase().contains("audio works"))
        } finally {c.filesDir.resolve("appliance-live-audio-stats.json").writeText(org.json.JSONObject(runtime.linkStats()).put("status",runtime.setupStatus.value).toString())}
    }
    @Test fun queuedVoiceRendersAndDeletesItsRecording() = runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckLiveMuse")=="true")
        val runtime=MuseRuntime.get(c)
        assumeTrue(runtime.vault.enabled && runtime.vault.hasCredentials())
        val engine=withContext(Dispatchers.Main) {dev.puck.appliance.ApplianceEngine.get(c)}
        withTimeout(60_000) {runtime.connection.first {it==ConnectionStatus.MuseConnected}}
        val id=UUID.randomUUID().toString()
        val bytes=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/puck-generated-voice-test.wav").let {fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {it.readBytes()}}
        val audio=java.io.File(engine.store.dir,"$id.wav").apply {writeBytes(bytes)}
        val journal=engine.store.writeTurn(id,audio,null)
        withContext(Dispatchers.Main) {engine.retry()}
        withTimeout(120_000) {while(org.json.JSONObject(journal.readText()).optString("state")!="sent")delay(200)}
        assertFalse(audio.exists())
        assertTrue(engine.update.value.lowercase().contains("audio works"))
        assertEquals("Reply ready",engine.status.value)
        c.filesDir.resolve("appliance-voice-latency.json").writeText(org.json.JSONObject(runtime.linkStats()).getJSONObject("reply").toString())
    }
    @Test fun queuedPhotoAndVoiceRenderAndDeleteBothFiles() = runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckLiveMuse")=="true")
        val runtime=MuseRuntime.get(c)
        assumeTrue(runtime.vault.enabled && runtime.vault.hasCredentials())
        val engine=withContext(Dispatchers.Main) {dev.puck.appliance.ApplianceEngine.get(c)}
        withTimeout(60_000) {runtime.connection.first {it==ConnectionStatus.MuseConnected}}
        val id=UUID.randomUUID().toString()
        val bytes=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /sdcard/Download/puck-generated-photo-question.wav").let {fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {it.readBytes()}}
        val audio=java.io.File(engine.store.dir,"$id.wav").apply {writeBytes(bytes)}
        val photo=java.io.File(engine.store.dir,"$id.jpg")
        val bitmap=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.MAGENTA)}
        photo.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)};bitmap.recycle()
        val journal=engine.store.writeTurn(id,audio,photo)
        withContext(Dispatchers.Main) {engine.retry()}
        try {withTimeout(120_000) {while(runCatching {org.json.JSONObject(journal.readText()).optString("state")}.getOrDefault("")!="sent")delay(200)}}
        finally {c.filesDir.resolve("appliance-photo-voice-stats.json").writeText(org.json.JSONObject(runtime.linkStats()).put("status",runtime.setupStatus.value).toString())}
        assertFalse(audio.exists());assertFalse(photo.exists())
        assertTrue(listOf("magenta","pink","purple","fuchsia").any {it in engine.update.value.lowercase()})
    }
    @Test fun informationOnlyCheckInRendersAnUpdate() = runBlocking {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("puckLiveMuse")=="true")
        val runtime=MuseRuntime.get(c)
        assumeTrue(runtime.vault.enabled && runtime.vault.hasCredentials())
        val engine=withContext(Dispatchers.Main) {dev.puck.appliance.ApplianceEngine.get(c)}
        withTimeout(60_000) {runtime.connection.first {it==ConnectionStatus.MuseConnected}}
        withContext(Dispatchers.Main) {engine.dismiss();engine.checkIn(true)}
        withTimeout(120_000) {engine.update.first {it.isNotBlank()}}
        assertTrue(engine.store.prefs.getLong("last_checkin",0)>0)
    }
    @Test fun configureAuthorizedVoiceServer() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("puckConfigureServer")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val store=dev.puck.appliance.ApplianceStore(c)
        if(args.getString("puckClearServer")=="true") {store.endpoint="";assertEquals("",store.endpoint);return}
        store.endpoint=requireNotNull(args.getString("puckEndpoint"))
        store.token=requireNotNull(args.getString("puckDeviceToken"))
        assertTrue(store.token.length>=32)
        args.getString("puckMaintenance")?.let {store.setMaintenancePin(it)}
    }
}
