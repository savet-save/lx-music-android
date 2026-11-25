package cn.toside.music.nativeapp.online

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.data.BilibiliMp3Test
import cn.toside.music.nativeapp.data.Downloads
import cn.toside.music.nativeapp.source.SourceAudioResolver
import cn.toside.music.nativeapp.source.UserSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class BilibiliLiveTest {
    @Test fun liveSearchAudioDownloadAndMp3Conversion()=runBlocking {
        assumeTrue("Pass liveBilibili=true for public network verification",InstrumentationRegistry.getArguments().getString("liveBilibili")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val sources=UserSources(context);val before=sources.catalog();val downloads=Downloads(context)
        var key:String?=null
        try {
            val results=BilibiliApi(context).search("茶花开了 王睿卓",1)
            assertTrue("Bilibili search returned no videos",results.isNotEmpty())
            val song=results.firstOrNull { it.duration in 260000..280000 } ?: results.first()
            val entry=before.entries.single { it.name=="B站音源" };sources.setEnabledOrder(listOf(entry.id))
            val resolver=SourceAudioResolver(sources,{ _, _ -> emptyList()},false)
            downloads.startWithSources(song,"mp3") { item,_,excluded -> resolver.resolve(item,"original",excluded) }
            key=downloads.list().first { it.song.id==song.id }.key
            val task=BilibiliMp3Test().waitFor(downloads,key)
            val extractor=MediaExtractor()
            val duration=try {
                extractor.setDataSource(context,task.localUri!!,null)
                val format=extractor.getTrackFormat(0);assertEquals("audio/mpeg",format.getString(MediaFormat.KEY_MIME));format.getLong(MediaFormat.KEY_DURATION)/1000
            } finally { extractor.release() }
            assertTrue(kotlin.math.abs(duration-task.song.duration)<500)
            File(context.getExternalFilesDir(null),"bilibili-verification.json").writeText(JSONObject().put("bvid",JSONObject(song.sourceInfo!!).getString("bvid"))
                .put("title",song.title).put("durationMs",duration).put("sourceDurationMs",task.song.duration).put("bytes",task.bytes).put("mime","audio/mpeg").toString(2))
        } finally {
            key?.let { k -> downloads.list().firstOrNull { it.key==k }?.let { downloads.remove(it) } }
            sources.setEnabledOrder(before.enabled);sources.close()
        }
    }
}
