@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package cn.toside.music.nativeapp.data

import android.app.DownloadManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.DownloadTestScheduler
import cn.toside.music.nativeapp.MultiSourceAudioServer
import cn.toside.music.nativeapp.lyrics.Lyrics
import cn.toside.music.nativeapp.lyrics.LyricLine
import cn.toside.music.nativeapp.source.SourceAudio
import cn.toside.music.nativeapp.online.BilibiliApi
import cn.toside.music.nativeapp.player.AudioStreamCache
import cn.toside.music.nativeapp.player.NativeAudioPlayer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BilibiliMp3Test {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    internal suspend fun waitFor(downloads:Downloads,key:String, lyrics:Boolean=false):DownloadTask = withTimeout(240_000) {
        var systemId:Long?=null; var scheduler:DownloadTestScheduler?=null
        while(true) {
            val task=downloads.list().single { it.key==key }
            check(!task.failed) { "${task.status}: ${task.reason}: ${task.error}" }
            if(task.status==DownloadStatus.COMPLETE && (!lyrics || task.lyricStatus==DownloadLyricStatus.SAVED)) return@withTimeout task
            if(systemId!=task.systemId) { systemId=task.systemId;scheduler=systemId?.let(::DownloadTestScheduler) }
            if(task.status in setOf(DownloadStatus.QUEUED,DownloadStatus.PAUSED)) scheduler?.onStatus(DownloadManager.STATUS_PENDING)
            delay(250)
        }
        error("unreachable")
    }
    @Test fun completedSystemTransferIsConvertedToRealMp3WithMatchingLrc()=runBlocking {
        MultiSourceAudioServer().use { server ->
            val id=System.nanoTime()
            val song=Song(id,Uri.EMPTY,"Bili MP3 fixture $id","test",12000,"bili","""{"bvid":"BV1xx411c7mD","songmid":"fixture-$id","cid":1}""")
            val downloads=Downloads(context)
            Lyrics(context).cache(song,listOf(LyricLine(1000,"first line"),LyricLine(5000,"second line")))
            var key:String?=null
            try {
                downloads.startWithSources(song,"mp3") { _,_,_ -> SourceAudio(server.url("good"),"original","fixture",listOf("fixture"),"fixture") }
                key=downloads.list().single { it.song.id==id }.key
                val task=waitFor(downloads,key,true)
                assertNull(task.systemId);assertNotNull(task.localUri);assertTrue(task.fileName!!.endsWith(".mp3"))
                val extractor=MediaExtractor()
                try {
                    extractor.setDataSource(context,task.localUri!!,null)
                    val format=extractor.getTrackFormat(0)
                    assertEquals("audio/mpeg",format.getString(MediaFormat.KEY_MIME))
                    assertTrue(kotlin.math.abs(format.getLong(MediaFormat.KEY_DURATION)/1000-12000)<350)
                } finally { extractor.release() }
                val bytes=requireNotNull(context.contentResolver.openInputStream(task.localUri!!)).use { it.readBytes() }
                assertTrue(bytes.size>400000)
                assertEquals(14,(bytes[2].toInt() ushr 4) and 15) // MPEG-1 Layer III, 320 kbps.
                val lrc=requireNotNull(context.contentResolver.openInputStream(task.lyricUri!!)).bufferedReader().use { it.readText() }
                assertTrue(lrc.contains("[00:01.000]first line"));assertTrue(lrc.contains("[00:05.000]second line"))
                assertTrue(downloads.pendingConversions().isEmpty())
                assertTrue(server.requestHeaders.any { it["referer"]=="https://www.bilibili.com/" })
                assertTrue(server.requestHeaders.all { "cookie" !in it })
            } finally {
                key?.let { k -> downloads.list().firstOrNull { it.key==k }?.let { downloads.remove(it) } }
            }
        }
    }
    @Test fun streamingSendsRefererAndUserAgentWithoutAccountCookies()=runBlocking {
        MultiSourceAudioServer().use { server ->
            val folder=java.io.File(context.cacheDir,"bili-http-${System.nanoTime()}")
            val cache=AudioStreamCache(context,folder)
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            var player:NativeAudioPlayer?=null
            val failure=java.util.concurrent.atomic.AtomicReference<String?>()
            try {
                instrumentation.runOnMainSync {
                    player=NativeAudioPlayer(context,cache,false,{},{},{_,error -> failure.set(error)},BilibiliApi.audioHeaders)
                    player!!.prepare(Uri.parse(server.url("good")),"bili-headers",0,1f,0f,true)
                }
                withTimeout(20000) {
                    while(true) {
                        var ready=false
                        instrumentation.runOnMainSync { ready=player!!.isPlaying && player!!.position > 200 }
                        check(failure.get()==null) { failure.get().orEmpty() }
                        if(ready) break
                        delay(100)
                    }
                }
                assertTrue(server.requestHeaders.any { it["referer"]==BilibiliApi.audioHeaders["Referer"] && it["user-agent"]==BilibiliApi.audioHeaders["User-Agent"] })
                assertTrue(server.requestHeaders.all { "cookie" !in it })
            } finally { instrumentation.runOnMainSync { player?.release() }; cache.close();folder.deleteRecursively() }
        }
    }
}
