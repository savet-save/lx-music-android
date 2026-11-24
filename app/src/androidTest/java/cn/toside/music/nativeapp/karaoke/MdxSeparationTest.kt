package cn.toside.music.nativeapp.karaoke

import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class MdxSeparationTest {
    @Test fun packagedModelIsPreparedOnFirstUse() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mdxBundledModel") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = checkNotNull(context.assets.list("karaoke-models")).single()
        val expected = context.noBackupFilesDir.resolve("karaoke-models/$name")
        assertFalse(expected.exists())
        val mdx = MdxSeparation(context)
        assertEquals(expected, mdx.installedModel())
        assertTrue(expected.isFile)
        assertEquals(expected, mdx.installedModel())
    }

    @Test fun dynamicallyRegisteredModelPreservesFrameCountAndReconstructsTheVocalAcrossBlocks() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File.createTempFile("mdx-test", "", context.cacheDir).apply { delete(); mkdir() }
        try {
            // A tiny ONNX Identity graph with the production MDX tensor shape.
            val model = folder.resolve("identity.onnx").apply { writeBytes(Base64.decode(IDENTITY_MODEL, Base64.DEFAULT)) }
            val input = folder.resolve("input.wav")
            val output = folder.resolve("output.wav")
            val frames = KaraokeAudio.RATE * 7L
            KaraokeAudio.WavWriter(input).use { writer ->
                val chunk = ShortArray(4800 * 2)
                repeat(70) { block ->
                    for (frame in 0 until 4800) {
                        val time = (block * 4800 + frame) * 2 * PI / KaraokeAudio.RATE
                        chunk[frame * 2] = (12000 * sin(time * 440)).toInt().toShort()
                        chunk[frame * 2 + 1] = (8000 * sin(time * 730)).toInt().toShort()
                    }
                    writer.write(chunk, chunk.size)
                }
            }
            val reported = mutableListOf<Int>()
            separate(model, input, output, frames) { reported.add(it) }
            assertEquals(input.length(), output.length())
            assertEquals(100, reported.last())
            assertTrue(reported.any { it in 1..99 })
            assertTrue(reported.zipWithNext().all { (left, right) -> left <= right })
            RandomAccessFile(output, "r").use { wave ->
                wave.seek(40)
                assertEquals((frames * 4).toInt(), Integer.reverseBytes(wave.readInt()))
                // In this model the entire mix is the vocal stem. Only the model's 3%
                // compensation and small resampling errors should remain.
                for (frame in 480 until frames.toInt() - 480 step 30) {
                    wave.seek(KaraokeAudio.HEADER_BYTES + frame * KaraokeAudio.BYTES_PER_FRAME)
                    assertTrue(kotlin.math.abs(java.lang.Short.reverseBytes(wave.readShort()).toInt()) < 700)
                    assertTrue(kotlin.math.abs(java.lang.Short.reverseBytes(wave.readShort()).toInt()) < 700)
                }
            }
            val tuned = MdxParameters(strengthPercent = 90, overlapMs = 1000, threads = 1)
            separate(model, input, output, frames, tuned) {}
            assertEquals(input.length(), output.length())
            // An identity prediction subtracted at 90% should leave 10% of the original, including across the join.
            assertTrue(vocalProjection(output, input) in 0.09..0.11)
            val before = output.readBytes()
            try {
                separate(model, input, output, frames) { if (it > 0) throw CancellationException("cancel test") }
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                assertTrue(before.contentEquals(output.readBytes()))
            }
            assertTrue(folder.listFiles().orEmpty().none { it.name.startsWith("mdx-") })
        } finally { folder.deleteRecursively() }
    }

    @Test fun officialModelReducesAnOffCenterVoice() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("mdxRealModel") == "true")
        val context = instrumentation.targetContext
        val fixtures = context.cacheDir.resolve("mdx-verification")
        val input = fixtures.resolve("mixture.wav")
        val reference = fixtures.resolve("vocals.wav")
        val backing = fixtures.resolve("backing.wav")
        val base = fixtures.resolve("center.wav")
        val original = fixtures.resolve("original.wav")
        val model = checkNotNull(MdxSeparation(context).installedModel())
        val fallback = KaraokeAudio.prepare(context, Uri.fromFile(input), base, 0) {}
        var lastBucket = -1
        val started = SystemClock.elapsedRealtime()
        val prepared = KaraokeAudio.prepare(context, Uri.fromFile(input), backing, 0, original, model, onSeparationError = { throw it }) { percent ->
            val bucket = percent / 10
            if (bucket > lastBucket) {
                lastBucket = bucket
                Log.i("MdxSeparationTest", "Preparation $percent%, app PSS ${Debug.getPss() / 1024} MiB")
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i("MdxSeparationTest", "Prepared in $elapsed ms, app PSS after inference ${Debug.getPss() / 1024} MiB")
        assertTrue(prepared.separated)
        assertEquals(fallback.frames, prepared.frames)
        assertEquals(input.length(), backing.length())
        assertEquals(input.length(), original.length())
        val baseline = vocalProjection(base, reference)
        val separated = vocalProjection(backing, reference)
        Log.i("MdxSeparationTest", "Residual vocal projection: center=${20 * log10(baseline)} dB, MDX-Net=${20 * log10(separated)} dB")
        assertTrue("MDX-Net should reduce the off-center voice: $baseline vs $separated", separated < baseline * 0.75)
    }

    private fun vocalProjection(audio: File, reference: File): Double {
        var correlation = 0.0
        var referencePower = 0.0
        DataInputStream(BufferedInputStream(audio.inputStream())).use { output ->
            DataInputStream(BufferedInputStream(reference.inputStream())).use { vocal ->
                output.skipBytes(KaraokeAudio.HEADER_BYTES.toInt())
                vocal.skipBytes(KaraokeAudio.HEADER_BYTES.toInt())
                repeat(((reference.length() - KaraokeAudio.HEADER_BYTES) / 2).toInt()) {
                    val voice = java.lang.Short.reverseBytes(vocal.readShort()).toDouble()
                    correlation += voice * java.lang.Short.reverseBytes(output.readShort())
                    referencePower += voice * voice
                }
            }
        }
        return kotlin.math.abs(correlation / referencePower)
    }

    private companion object {
        const val IDENTITY_MODEL = "CAg6dAoXCgNtaXgSBnZvY2FscyIISWRlbnRpdHkSFG1keF9pZGVudGl0eV9maXh0dXJl" +
            "Wh8KA21peBIYChYIARISCgIIAQoCCAQKAwiAEAoDCIACYiIKBnZvY2FscxIYChYIARISCgIIAQoCCAQKAwiAEAoDCIACQgIQDQ=="
    }
}
