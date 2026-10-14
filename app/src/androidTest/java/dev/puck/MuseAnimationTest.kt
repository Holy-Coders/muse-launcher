package dev.puck

import android.content.ContextWrapper
import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.puck.appliance.MuseAnimation
import dev.puck.appliance.MusePortrait
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MuseAnimationTest {
    @Test fun customAvatarGeneratesPrivateMotionAndGenericIgnoresCustomAssets() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(base.cacheDir,"avatar-fixture-${UUID.randomUUID()}").apply {mkdirs()}
        val c=object:ContextWrapper(base){override fun getFilesDir()=dir}
        try {
            val original=File(dir,"muse-portrait.png")
            val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawCircle(64f,64f,42f,Paint().apply {color=Color.MAGENTA})
            original.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            val bytes=original.readBytes()
            File(dir,"muse-portrait.json").writeText(JSONObject().put("name","Fixture Muse").put("updated",1).put("avatar_kind","custom").toString())
            MuseAnimation.generate(c,"Fixture Muse",original)
            val profile=MusePortrait.load(c)
            assertNotNull(profile.animation);assertEquals(5,profile.animationRows)
            assertArrayEquals("Generation changed original artwork",bytes,original.readBytes())
            val atlas=BitmapFactory.decodeFile(profile.animation!!.path)
            assertEquals(1024,atlas.width);assertEquals(1280,atlas.height)
            assertEquals(0,Color.alpha(atlas.getPixel(0,0)))
            val idle=Bitmap.createBitmap(atlas,0,0,256,256)
            val look=Bitmap.createBitmap(atlas,0,1024,256,256)
            assertFalse("Look-up pose identical to idle",idle.sameAs(look))
            idle.recycle();look.recycle();atlas.recycle()
            File(dir,"muse-portrait.json").writeText(JSONObject().put("name","Fixture Muse").put("updated",2).put("avatar_kind","generic").toString())
            val generic=MusePortrait.load(c)
            assertNull(generic.image);assertNull(generic.animation)
        } finally {dir.deleteRecursively()}
    }
}
