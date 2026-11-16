package cn.toside.music.nativeapp

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Silent audio and terminal/partial HTTP failures for real player and system-download fallback. */
internal class MultiSourceAudioServer(payload: ByteArray? = null) : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val requests = CopyOnWriteArrayList<String>()
    val requestHeaders = CopyOnWriteArrayList<Map<String,String>>()
    private val broken = AtomicInteger()
    val bytes = payload ?: ByteBuffer.allocate(44 + 16000 * 12 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()).putInt(capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
        putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        put("data".toByteArray()).putInt(capacity() - 44)
    }.array()
    fun url(path: String) = "http://127.0.0.1:${server.localPort}/$path.wav"
    private val worker = Thread {
        while (!server.isClosed) {
            try {
                val socket = server.accept(); sockets.add(socket)
                Thread {
                    try { socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                        var offset = 0
                        val headers=mutableMapOf<String,String>()
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            headers[header.substringBefore(':').lowercase()]=header.substringAfter(':').trim()
                            if (header.startsWith("Range:", true)) offset = header.substringAfter("bytes=").substringBefore('-').toIntOrNull() ?: 0
                        }
                        requests.add(path)
                        requestHeaders.add(headers)
                        val output = it.getOutputStream()
                        when {
                            path.startsWith("/bad") -> output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            path.startsWith("/broken") && broken.incrementAndGet() > 1 -> output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            else -> {
                                val remaining = bytes.size - offset
                                val status = if (offset > 0) "206 Partial Content" else "200 OK"
                                output.write("HTTP/1.1 $status\r\nContent-Type: audio/wav\r\nContent-Length: $remaining\r\nAccept-Ranges: bytes\r\nETag: \"multi-source-test\"\r\nContent-Range: bytes $offset-${bytes.lastIndex}/${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                output.write(bytes, offset, if (path.startsWith("/broken")) minOf(2048, remaining) else remaining)
                            }
                        }
                        output.flush()
                    } } catch (_: Exception) { }
                    finally { sockets.remove(socket) }
                }.apply { isDaemon = true; start() }
            } catch (_: Exception) { }
        }
    }.apply { isDaemon = true; start() }
    override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; worker.join(2000) }
}
