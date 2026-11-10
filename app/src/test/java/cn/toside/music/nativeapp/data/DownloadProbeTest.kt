package cn.toside.music.nativeapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class DownloadProbeTest {
    private class Server : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val url = "http://127.0.0.1:${server.localPort}"
        val requests = CopyOnWriteArrayList<List<String>>()
        val stalled = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val written = AtomicInteger()
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                sockets.add(socket)
                thread(isDaemon = true) {
                    try { socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val lines = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                        requests += lines
                        val path = lines.first().split(' ')[1]
                        val output = it.getOutputStream()
                        if (path == "/stall") {
                            output.write("HTTP/1.1 200 OK\r\nContent-Length: 10240\r\n\r\n".toByteArray()); output.flush()
                            stalled.countDown()
                            while (it.getInputStream().read() != -1) { }
                            disconnected.countDown()
                        } else {
                            val size = when (path) { "/short" -> 128; "/empty" -> 0; "/ignored", "/unbounded" -> 64 * 1024; else -> DownloadProbe.MAX_BYTES }
                            val status = when (path) { "/redirect" -> "302 Found"; "/missing" -> "403 Forbidden"; "/range" -> "206 Partial Content"; else -> "200 OK" }
                            output.write(buildString {
                                append("HTTP/1.1 $status\r\nConnection: close\r\n")
                                append("Content-Type: ${if (path == "/html") "text/html" else "audio/mpeg"}\r\n")
                                if (path != "/unbounded") append("Content-Length: $size\r\n")
                                if (path == "/range") append("Content-Range: bytes 0-10239/100000\r\n")
                                if (path == "/redirect") append("Location: /range\r\n")
                                append("\r\n")
                            }.toByteArray())
                            repeat((size + 127) / 128) {
                                output.write(ByteArray(128)); output.flush(); written.addAndGet(128)
                                if (path == "/ignored" || path == "/unbounded") Thread.sleep(2)
                            }
                        }
                    } } catch (_: Exception) { disconnected.countDown() }
                    finally { sockets.remove(socket) }
                }
            }
        }
        override fun close() { server.close(); sockets.forEach { it.close() }; worker.join(1000) }
    }

    @Test fun rangeReadStopsAtTenKiBAndDoesNotUseCompressionOrDiskCache() = runBlocking {
        Server().use { server ->
            val progress = CopyOnWriteArrayList<Int>()
            DownloadProbe().check("${server.url}/range") { progress += it }
            val request = server.requests.single().joinToString("\n").lowercase()
            assertTrue(request.contains("range: bytes=0-10239"))
            assertTrue(request.contains("accept-encoding: identity"))
            assertTrue(request.contains("cache-control: no-store"))
            assertEquals(DownloadProbe.MAX_BYTES, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        }
    }

    @Test fun ignoredRangeAndMissingLengthStillStopAtTheLimit() = runBlocking {
        for (path in listOf("ignored", "unbounded")) Server().use { server ->
            val progress = CopyOnWriteArrayList<Int>()
            DownloadProbe().check("${server.url}/$path") { progress += it }
            assertEquals(DownloadProbe.MAX_BYTES, progress.last())
            assertTrue(server.disconnected.await(2, TimeUnit.SECONDS))
            assertTrue("Must stop the response before downloading all of it", server.written.get() < 64 * 1024)
        }
    }

    @Test fun redirectsAndShortNonemptyResponsesAreDownloadable() = runBlocking {
        Server().use { server ->
            DownloadProbe().check("${server.url}/redirect") { }
            var bytes = 0
            DownloadProbe().check("${server.url}/short") { bytes = it }
            assertEquals(128, bytes)
        }
    }

    @Test fun errorsEmptyBodiesAndErrorPagesAreUnavailable() = runBlocking {
        Server().use { server ->
            for (path in listOf("missing", "empty", "html")) {
                assertTrue(path, runCatching { DownloadProbe().check("${server.url}/$path") { } }.isFailure)
            }
            assertTrue(runCatching { DownloadProbe().check("file:///tmp/audio.mp3") { } }.isFailure)
        }
    }

    @Test fun cancellationDisconnectsAnInFlightRead() = runBlocking {
        Server().use { server ->
            val result = async { DownloadProbe().check("${server.url}/stall") { } }
            withTimeout(2000) { while (server.stalled.count > 0) delay(10) }
            result.cancel()
            try { withTimeout(1000) { result.await() }; fail("Expected cancellation") } catch (_: CancellationException) { }
            assertTrue(server.disconnected.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun aStalledResponseTimesOut() = runBlocking {
        Server().use { server ->
            val client = OkHttpClient.Builder().readTimeout(150, TimeUnit.MILLISECONDS).callTimeout(1, TimeUnit.SECONDS).build()
            assertTrue(runCatching { DownloadProbe(client).check("${server.url}/stall") { } }.isFailure)
        }
    }
}
