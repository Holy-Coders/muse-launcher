package dev.puck.appliance

import android.media.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decode a clip's own audio locally for Muse's proven WAV path; never capture the mic. */
object VideoAudio {
    fun wave(file:File):ByteArray? {
        val extractor=MediaExtractor()
        var decoder:MediaCodec?=null
        try {
            extractor.setDataSource(file.path)
            val track=(0 until extractor.trackCount).firstOrNull {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}?:return null
            extractor.selectTrack(track)
            val format=extractor.getTrackFormat(track)
            require(!format.containsKey(MediaFormat.KEY_DURATION) || format.getLong(MediaFormat.KEY_DURATION)<=60_000_000){"Video audio exceeds 60 seconds"}
            val codec=MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)));decoder=codec
            codec.configure(format,null,null,0);codec.start()
            var sampleRate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding=AudioFormat.ENCODING_PCM_16BIT
            val pcm=ByteArrayOutputStream();val info=MediaCodec.BufferInfo()
            var inputDone=false;var outputDone=false;var frames=0L
            val deadline=android.os.SystemClock.elapsedRealtime()+30_000
            while(!outputDone) {
                check(android.os.SystemClock.elapsedRealtime()<deadline){"Video audio decode timed out"}
                if(!inputDone) {
                    val slot=codec.dequeueInputBuffer(10_000)
                    if(slot>=0) {
                        val input=requireNotNull(codec.getInputBuffer(slot));val count=extractor.readSampleData(input,0)
                        if(count<0){codec.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}
                        else {codec.queueInputBuffer(slot,0,count,extractor.sampleTime,0);extractor.advance()}
                    }
                }
                val slot=codec.dequeueOutputBuffer(info,10_000)
                if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output=codec.outputFormat
                    sampleRate=output.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding=if(output.containsKey(MediaFormat.KEY_PCM_ENCODING))output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(sampleRate in 8000..192000 && channels in 1..8 && encoding in setOf(AudioFormat.ENCODING_PCM_16BIT,AudioFormat.ENCODING_PCM_FLOAT))
                } else if(slot>=0) {
                    val output=codec.getOutputBuffer(slot)
                    if(output!=null && info.size>0) {
                        output.position(info.offset);output.limit(info.offset+info.size);output.order(ByteOrder.LITTLE_ENDIAN)
                        val bytes=if(encoding==AudioFormat.ENCODING_PCM_FLOAT)4 else 2
                        while(output.remaining()>=channels*bytes) {
                            var sum=0f
                            repeat(channels){sum+=if(bytes==4)output.float.coerceIn(-1f,1f)*32767f else output.short.toFloat()}
                            // Bounded mono downsampling to the same 16 kHz WAV used for voice.
                            val before=frames*16000/sampleRate;frames++
                            val copies=(frames*16000/sampleRate-before).toInt()
                            if(copies>0) {
                                val sample=(sum/channels).toInt().coerceIn(-32768,32767)
                                repeat(copies){pcm.write(sample and 255);pcm.write((sample shr 8) and 255)}
                                require(pcm.size()<=1_920_000){"Video audio exceeds 60 seconds"}
                            }
                        }
                    }
                    outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    codec.releaseOutputBuffer(slot,false)
                }
            }
            if(pcm.size()<3200)return null
            val payload=pcm.toByteArray()
            val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(payload.size+36).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(payload.size)
            return header.array()+payload
        } finally {
            decoder?.let {runCatching {it.stop()};it.release()};extractor.release()
        }
    }
}
