package cn.toside.music.nativeapp.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.online.BilibiliCredentials
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BilibiliUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun children(view:View):List<View> = listOf(view) + if(view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    @Test fun tokenPageDoesNotRestoreOrAutofillCredentials() {
        val activity=instrumentation.startActivitySync(Intent(context,BilibiliLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { instrumentation.runOnMainSync {
            val input=activity.window.decorView.findViewWithTag<EditText>("bili-token")
            assertFalse(input.isSaveEnabled);assertEquals("",input.text.toString())
            assertNotNull(activity.window.decorView.findViewWithTag<View>("bili-token-save"))
            assertNotNull(activity.window.decorView.findViewWithTag<View>("bili-token-clear"))
        } } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun lyricsDownloadLetsLocalAndVideoSongsCorrectTitleAndSinger() {
        for(song in listOf(Song(1,Uri.EMPTY,"Monsters","Katie Sky",240000),Song(2,Uri.EMPTY,"王睿卓 - 茶花开了【动态歌词】","上传者",270000,"bili"))) {
            val activity=instrumentation.startActivitySync(LyricsDownloadActivity.intent(context,song).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try { instrumentation.runOnMainSync {
                val fields=children(activity.window.decorView).filterIsInstance<EditText>()
                assertEquals(2,fields.size)
                assertEquals(if(song.source=="local") "Monsters" else "茶花开了",fields[0].text.toString())
                assertEquals(if(song.source=="local") "Katie Sky" else "王睿卓",fields[1].text.toString())
                assertNotNull(activity.window.decorView.findViewWithTag<View>("lyrics-search-save"))
            } } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }
    @Test fun clearingCredentialsCancelsPendingValidationAndPreventsLateSaving()=runBlocking {
        val credentials=BilibiliCredentials(context)
        val previous=credentials.load()
        val activity=instrumentation.startActivitySync(Intent(context,BilibiliLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        try {
            val validate:suspend (String)->String = { entered.complete(Unit);finish.await();"fixture" }
            BilibiliLoginActivity::class.java.getDeclaredField("verifySession").apply { isAccessible=true }.set(activity,validate)
            instrumentation.runOnMainSync {
                activity.window.decorView.findViewWithTag<EditText>("bili-token").setText("fixture-not-an-account")
                activity.window.decorView.findViewWithTag<View>("bili-token-save").performClick()
            }
            withTimeout(10000) { entered.await() }
            instrumentation.runOnMainSync { activity.window.decorView.findViewWithTag<View>("bili-token-clear").performClick() }
            finish.complete(Unit)
            withTimeout(10000) {
                while(true) {
                    var ready=false
                    instrumentation.runOnMainSync { ready=activity.window.decorView.findViewWithTag<View>("bili-token-save").isEnabled }
                    if(ready && credentials.load().isEmpty()) break
                    delay(50)
                }
            }
            assertEquals("",credentials.load())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            if(previous.isNotEmpty()) credentials.save(previous) else credentials.clear()
        }
    }
}
