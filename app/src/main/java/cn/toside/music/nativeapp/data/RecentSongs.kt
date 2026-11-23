package cn.toside.music.nativeapp.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Playback history is independent of the active queue and keeps each recording once. */
internal class RecentSongs(context: Context, name: String = "play-history") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val changes = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SONGS_KEY) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    suspend fun list(): List<Song> = withContext(Dispatchers.IO) { synchronized(lock) { read() } }
    suspend fun record(song: Song) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val songs = (listOf(song) + read().filterNot { it.id == song.id && it.source == song.source }).take(200)
            val array = JSONArray().apply { songs.forEach { s -> put(JSONObject().put("id", s.id).put("uri", s.uri.toString())
                .put("title", s.title).put("artist", s.artist).put("duration", s.duration).put("source", s.source)
                .put("info", s.sourceInfo ?: JSONObject.NULL)) } }
            check(preferences.edit().putString(SONGS_KEY, array.toString()).commit()) { "保存播放历史失败" }
        }
    }
    suspend fun clear() = withContext(Dispatchers.IO) { synchronized(lock) { preferences.edit().remove(SONGS_KEY).commit() } }
    private fun read(): List<Song> {
        val array = JSONArray(preferences.getString(SONGS_KEY, "[]"))
        return (0 until array.length()).map { index -> array.getJSONObject(index).let { item ->
            Song(item.getLong("id"), Uri.parse(item.getString("uri")), item.getString("title"), item.getString("artist"),
                item.getLong("duration"), item.getString("source"), if (item.isNull("info")) null else item.getString("info"))
        } }
    }
    companion object {
        private const val SONGS_KEY = "songs"
        private val lock = Any()
    }
}
