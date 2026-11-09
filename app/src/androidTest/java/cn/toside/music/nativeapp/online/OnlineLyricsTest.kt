package cn.toside.music.nativeapp.online

import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.lyrics.LrcParser
import cn.toside.music.nativeapp.lyrics.QrcCipher

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.DeflaterOutputStream
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class OnlineLyricsTest {
    @Test fun parsesKrcWordTimingAndHtmlEntities() {
        val krc = "[id:test]\n[1200,900]<0,200,0>Hello &amp; <200,700,0>world\n[2500,400]<0,400,0>next"
        val compressed = ByteArrayOutputStream().also { stream -> DeflaterOutputStream(stream).use { it.write(krc.toByteArray()) } }.toByteArray()
        val key = byteArrayOf(0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69)
        compressed.indices.forEach { compressed[it] = (compressed[it].toInt() xor key[it % key.size].toInt()).toByte() }
        val lines = OnlineLyrics().parseKrc("krc1".toByteArray() + compressed)
        assertEquals(listOf(1200L, 2500L), lines.map { it.timeMs })
        assertEquals(listOf("Hello & world", "next"), lines.map { it.text })
    }
    @Test fun decodesKuwoCompressedPlainLrc() {
        val lrc = "[00:01.25]歌词\n[00:02.00]下一句"
        val compressed = ByteArrayOutputStream().also { stream -> DeflaterOutputStream(stream).use { it.write(lrc.toByteArray()) } }.toByteArray()
        val raw = "tp=content\r\nlength=1\r\n\r\n".toByteArray() + compressed
        assertEquals(lrc, OnlineLyrics().decodeKuwo(raw))
        assertEquals(listOf("歌词", "下一句"), LrcParser.parse(OnlineLyrics().decodeKuwo(raw)).map { it.text })
    }
    @Test fun decodesKuwoEncryptedWordTimedLrc() {
        val lrc = "[00:01.25]<0,300>歌词"
        val bytes = lrc.toByteArray()
        val key = "yeelion".toByteArray()
        bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor key[it % key.size].toInt()).toByte() }
        val encoded = android.util.Base64.encode(bytes, android.util.Base64.NO_WRAP)
        val compressed = ByteArrayOutputStream().also { stream -> DeflaterOutputStream(stream).use { it.write(encoded) } }.toByteArray()
        val raw = "tp=content\r\n\r\n".toByteArray() + compressed
        assertEquals("歌词", LrcParser.parse(OnlineLyrics().decodeKuwo(raw)).single().text)
    }
    @Test fun decryptsQqQrcAndConvertsWordTiming() {
        val xml = "<LyricContent LyricContent=\"[1200,900]Hello(1200,300) world(1500,600)\n[2500,300]Next(2500,300)\"/>"
        val compressed = ByteArrayOutputStream().also { stream -> DeflaterOutputStream(stream).use { it.write(xml.toByteArray()) } }.toByteArray()
        val padded = compressed.copyOf((compressed.size + 7) / 8 * 8)
        val hex = QrcCipher.crypt(padded, encrypt = true).joinToString("") { "%02X".format(it.toInt() and 255) }
        val lines = OnlineLyrics().parseQrc(OnlineLyrics().decodeQrc(hex))
        assertEquals(listOf(1200L, 2500L), lines.map { it.timeMs })
        assertEquals(listOf("Hello world", "Next"), lines.map { it.text })
    }
    @Test fun miguFallsBackToLineLyricsWhenWordLyricsAreInvalid() = runBlocking {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true) {
                try {
                    repeat(2) {
                        server.accept().use { client ->
                            client.soTimeout = 5000
                            val reader = client.getInputStream().bufferedReader()
                            val request = reader.readLine().orEmpty()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            val body = if (request.startsWith("GET /word.mrc ")) "invalid MRC" else "[00:01.25]示例歌词"
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            client.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes)
                            }
                        }
                    }
                } catch (_: IOException) { /* Test teardown closes the listener. */ }
            }
            val base = "http://127.0.0.1:${server.localPort}"
            val info = JSONObject().put("mrcUrl", "$base/word.mrc").put("lrcUrl", "$base/line.lrc")
            val song = Song(1, Uri.EMPTY, "Song", "Singer", 5000, "mg", info.toString())
            val line = OnlineLyrics().forSong(song).single()
            worker.join(1000)
            assertEquals(1250L, line.timeMs)
            assertEquals("示例歌词", line.text)
        }
    }
}
