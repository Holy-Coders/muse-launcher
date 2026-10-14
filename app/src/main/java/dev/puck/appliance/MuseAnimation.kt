package dev.puck.appliance

import android.content.Context
import android.graphics.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Generates private procedural motion from the user's original image, with no service/API. */
object MuseAnimation {
    fun generate(c:Context,name:String,original:File) {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeFile(original.path,bounds)
        require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
        val options=BitmapFactory.Options().apply {inSampleSize=if(maxOf(bounds.outWidth,bounds.outHeight)>512)4 else 1}
        val source=requireNotNull(BitmapFactory.decodeFile(original.path,options))
        val edge=256
        val atlas=Bitmap.createBitmap(edge*4,edge*5,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(atlas);val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        try {
            for(row in 0..4)for(frame in 0..3) {
                val phase=floatArrayOf(0f,1f,0f,-1f)[frame]
                val cell=Rect(frame*edge,row*edge,(frame+1)*edge,(row+1)*edge)
                canvas.save();canvas.clipRect(cell);canvas.translate(cell.left+edge/2f,cell.top+edge/2f)
                val tilt=when(row){1->phase*3f;2->phase*7f;3->phase*2f;4->8f+phase*2f;else->phase}
                canvas.rotate(tilt)
                val scale=when(row){1->.92f+phase*.015f;3->.92f+phase*.03f;4->.90f;else->.92f}
                canvas.scale(scale,if(row==4)scale*.93f else scale)
                canvas.translate(if(row==4)5f else 0f,if(row==4)-9f else phase*3f)
                val ratio=minOf((edge-24f)/source.width,(edge-24f)/source.height)
                val w=source.width*ratio;val h=source.height*ratio
                canvas.drawBitmap(source,null,RectF(-w/2,-h/2,w/2,h/2),paint)
                canvas.restore()
            }
            val file=File(c.filesDir,"muse-animation.png");val temp=File(c.filesDir,"muse-animation.tmp")
            temp.outputStream().use {check(atlas.compress(Bitmap.CompressFormat.PNG,100,it))};check(temp.renameTo(file))
            val hash=MessageDigest.getInstance("SHA-256").digest(original.readBytes()).joinToString(""){"%02x".format(it)}
            val meta=JSONObject().put("name",name).put("original_sha256",hash).put("rows",5).put("columns",4).put("generator","procedural-v1")
            val metaTemp=File(c.filesDir,"muse-animation-meta.tmp");metaTemp.writeText(meta.toString());check(metaTemp.renameTo(File(c.filesDir,"muse-animation.json")))
        } finally {source.recycle();atlas.recycle()}
    }
}
