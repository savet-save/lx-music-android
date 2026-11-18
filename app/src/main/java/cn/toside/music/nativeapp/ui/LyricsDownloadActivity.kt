package cn.toside.music.nativeapp.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import cn.toside.music.nativeapp.R
import cn.toside.music.nativeapp.data.Song
import cn.toside.music.nativeapp.lyrics.Lyrics
import cn.toside.music.nativeapp.lyrics.exportLrc
import cn.toside.music.nativeapp.online.MatchedLyrics
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** A destination selected by the system works for local songs without broad filesystem permissions. */
class LyricsDownloadActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var draft: File
    private lateinit var status: TextView
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiPreferences.context(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val original = Song(intent.getLongExtra("id",0), Uri.parse(intent.getStringExtra("uri").orEmpty()), intent.getStringExtra("title").orEmpty(),
            intent.getStringExtra("artist").orEmpty(), intent.getLongExtra("duration",0), intent.getStringExtra("source") ?: "local")
        val reference = MatchedLyrics.reference(original)
        val name = savedInstanceState?.getString("draft")?.takeIf { it.matches(Regex("lyric-download-[a-f0-9-]{36}\\.lrc")) }
            ?: "lyric-download-${UUID.randomUUID()}.lrc"
        draft = File(cacheDir, name)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p=(20*resources.displayMetrics.density).toInt(); setPadding(p,p,p,p) }
        panel.addView(TextView(this).apply { text = getString(R.string.lyrics_download); textSize=24f })
        panel.addView(TextView(this).apply { text = getString(R.string.lyrics_match_hint) })
        fun field(label: Int, value: String) = EditText(this).apply { hint = getString(label); contentDescription = hint; setSingleLine(true); setText(value); panel.addView(this) }
        val title = field(R.string.metadata_title, reference.title)
        val artist = field(R.string.metadata_artist, reference.artist)
        status = TextView(this).also { panel.addView(it) }
        val button = Button(this).apply { text=getString(R.string.lyrics_search_save); tag="lyrics-search-save" }.also { panel.addView(it) }
        button.setOnClickListener {
            button.isEnabled=false; status.text=getString(R.string.lyrics_matching)
            scope.launch {
                try {
                    require(title.text.isNotBlank()) { getString(R.string.metadata_title) }
                    val song=original.copy(title=title.text.toString().trim(), artist=artist.text.toString().trim(), source="local")
                    val lines=MatchedLyrics(this@LyricsDownloadActivity).find(song)
                    check(lines.isNotEmpty()) { getString(R.string.lyrics_no_match) }
                    Lyrics(this@LyricsDownloadActivity).cache(original,lines)
                    withContext(Dispatchers.IO) { draft.writeText(exportLrc(lines),Charsets.UTF_8) }
                    val fileName="${song.title} - ${song.artist}".replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").take(160)+".lrc"
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,fileName), 1)
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { status.text=e.localizedMessage }
                finally { button.isEnabled=true }
            }
        }
        panel.addView(Button(this).apply { text=getString(android.R.string.cancel); setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(panel) })
    }
    override fun onSaveInstanceState(state: Bundle) { state.putString("draft",draft.name); super.onSaveInstanceState(state) }
    @Deprecated("Legacy platform picker result")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=1 || resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { require(draft.isFile); requireNotNull(contentResolver.openOutputStream(uri,"w")).use { out -> draft.inputStream().use { it.copyTo(out) } } }
                setResult(RESULT_OK); finish()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { status.text=e.localizedMessage }
        }
    }
    override fun onDestroy() { scope.cancel(); if(isFinishing) draft.delete(); super.onDestroy() }
    companion object {
        fun intent(context:Context,song:Song)=Intent(context,LyricsDownloadActivity::class.java).putExtra("id",song.id).putExtra("uri",song.uri.toString())
            .putExtra("title",song.title).putExtra("artist",song.artist).putExtra("duration",song.duration).putExtra("source",song.source)
    }
}
