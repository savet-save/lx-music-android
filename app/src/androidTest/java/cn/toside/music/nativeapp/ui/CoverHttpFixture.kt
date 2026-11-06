package cn.toside.music.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Unique URLs with full 512 px decoded images, large enough to exceed the cover LRU on tablets. */
internal class CoverHttpFixture : AutoCloseable {
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val requests = AtomicInteger()
    val address = "http://127.0.0.1:${server.localPort}"
    private val body = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).let { bitmap ->
        Canvas(bitmap).apply { drawColor(Color.rgb(46, 133, 173)); drawText("COVER", 60f, 270f, Paint().apply { color = Color.WHITE; textSize = 90f }) }
        ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray() }
    }
    private val worker = thread(isDaemon = true, name = "cover-fixture") {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            sockets.add(socket)
            thread(isDaemon = true) {
                try { socket.use {
                    val input = it.getInputStream().bufferedReader()
                    if (input.readLine() == null) return@use
                    while (!input.readLine().isNullOrEmpty()) { }
                    requests.incrementAndGet()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                } } catch (_: Exception) { /* Test teardown closes sockets. */ }
                finally { sockets.remove(socket) }
            }
        }
    }
    override fun close() { server.close(); sockets.forEach { it.close() }; worker.join(1000) }
}
