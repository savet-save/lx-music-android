package cn.toside.music.nativeapp.karaoke

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class KaraokeAudioTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun centerReductionAttenuatesMiddleVoiceBandAndPreservesStereoDifference() {
        val reducer = KaraokeAudio.StereoCenterReduction()
        var before = 0.0
        var after = 0.0
        repeat(4800) { frame ->
            val center = 8000 * sin(2 * PI * 1000 * frame / KaraokeAudio.RATE)
            val side = 5000 * sin(2 * PI * 440 * frame / KaraokeAudio.RATE)
            val cut = reducer.center(center)
            val left = center + side - cut
            val right = center - side - cut
            assertEquals(side * 2, left - right, 1e-8)
            if (frame > 1000) {
                before += center * center
                after += (center - cut) * (center - cut)
            }
        }
        assertTrue(after / before < .05)
    }

    @Test fun mixingAlignsAndTrimsBothTracksWithIndependentGain() = runBlocking {
        val rate = KaraokeAudio.RATE
        val backing = files.newFile("backing.wav")
        KaraokeAudio.WavWriter(backing).use { sink ->
            val samples = ShortArray(rate * 2) { if (it % 2 == 0) 1000 else -1000 }
            sink.write(samples, samples.size)
        }
        val voice = files.newFile("voice.pcm").apply {
            writeBytes(ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(rate) { putShort(4000) } }.array())
        }
        val take = KaraokeTake(KaraokeBacking(backing, rate.toLong(), true), voice, rate.toLong())
        val mix = files.newFile("mix.wav")
        val count = KaraokeAudio.mix(take, KaraokeEdit(music = 50, voice = 25, trimStartMs = 100,
            trimEndMs = 100, voiceDelayMs = 200), mix)
        assertEquals(38_400L, count)
        val wav = ByteBuffer.wrap(mix.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(38_400 * 4, wav.getInt(40))
        assertEquals(500.toShort(), wav.getShort(44))
        assertEquals((-500).toShort(), wav.getShort(46))
        assertEquals(1500.toShort(), wav.getShort(44 + 4800 * 4))
        assertEquals(500.toShort(), wav.getShort(46 + 4800 * 4))
    }

    @Test fun noiseReductionSuppressesLowLevelRumbleInTheVocalTrack() = runBlocking {
        val frames = KaraokeAudio.RATE
        val backing = files.newFile("silent.wav")
        val samples = ShortArray(frames * 2)
        KaraokeAudio.WavWriter(backing).use { it.write(samples, samples.size) }
        val voice = files.newFile("rumble.pcm")
        voice.writeBytes(ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { putShort((200 * sin(2 * PI * 40 * it / frames)).toInt().toShort()) }
        }.array())
        val take = KaraokeTake(KaraokeBacking(backing, frames.toLong(), true), voice, frames.toLong())
        val result = files.newFile("noise-reduced.wav")
        KaraokeAudio.mix(take, KaraokeEdit(music = 0, voice = 100, reduceNoise = true), result)
        val wave = ByteBuffer.wrap(result.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val peak = (frames / 2 until frames).maxOf { kotlin.math.abs(wave.getShort(44 + it * 4).toInt()) }
        assertTrue("A quiet rumble should be attenuated, peak=$peak", peak < 50)
    }
}
