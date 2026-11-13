package cn.toside.music.nativeapp.source

import cn.toside.music.nativeapp.utils.readBounded

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class UserSourceEntry(val id: String, val name: String, val description: String, val version: String,
    val author: String, val homepage: String, val script: String, val allowUpdateAlert: Boolean = true)
internal data class UserSourceCatalog(val selected: String?, val entries: List<UserSourceEntry>)

/** The selected ID and scripts are written atomically; importing one script never discards the others. */
internal class UserSourceLibrary(private val context: Context) {
    private val target = AtomicFile(File(context.filesDir, "user-sources.json"))
    fun read(): UserSourceCatalog = synchronized(lock) {
        if (!target.baseFile.isFile) {
            val legacy = File(context.filesDir, "user-source.js")
            val bundled = listOf(bundled(BUNDLED_ASSET), bundled(BUNDLED_KUWO_ASSET))
            val entry = if (legacy.isFile) metadata(legacy.inputStream().use { it.readBounded(2 * 1024 * 1024) }.toString(Charsets.UTF_8)) else bundled.first()
            return@synchronized UserSourceCatalog(entry.id, (listOf(entry) + bundled).distinctBy { it.id }).also(::write)
        }
        val data = target.openRead().use { JSONObject(it.readBounded(32 * 1024 * 1024).toString(Charsets.UTF_8)) }
        val values = data.getJSONArray("sources")
        val entries = (0 until values.length()).map { index ->
            val entry = values.getJSONObject(index)
            metadata(entry.getString("script")).copy(allowUpdateAlert = entry.optBoolean("allowUpdateAlert", true))
        }.distinctBy { it.id }
        val catalog = UserSourceCatalog(data.optString("selected").takeIf { id -> entries.any { it.id == id } }, entries)
        // Add each newly packaged source once, preserving selection, disabled state and earlier removals.
        val version = data.optInt("version", 1)
        if (version < CATALOG_VERSION) {
            val additions = buildList {
                if (version < 2) add(bundled(BUNDLED_ASSET))
                if (version < 3) add(bundled(BUNDLED_KUWO_ASSET))
            }
            return@synchronized catalog.copy(entries = (entries + additions).distinctBy { it.id }).also(::write)
        }
        catalog
    }
    fun selected(): UserSourceEntry? = read().let { data -> data.entries.firstOrNull { it.id == data.selected } }
    fun addValidated(script: String): UserSourceEntry = synchronized(lock) {
        val entry = metadata(script); val current = read()
        write(UserSourceCatalog(entry.id, current.entries.filterNot { it.id == entry.id } + entry))
        entry
    }
    fun select(id: String?) = synchronized(lock) {
        val current = read()
        require(id == null || current.entries.any { it.id == id }) { "音源不存在" }
        write(current.copy(selected = id))
    }
    fun remove(id: String) = synchronized(lock) {
        val current = read()
        write(UserSourceCatalog(current.selected.takeUnless { it == id }, current.entries.filterNot { it.id == id }))
    }
    fun allowUpdates(id: String, enabled: Boolean) = synchronized(lock) {
        val current = read()
        write(current.copy(entries = current.entries.map { if (it.id == id) it.copy(allowUpdateAlert = enabled) else it }))
    }
    private fun bundled(asset: String): UserSourceEntry = metadata(context.assets.open(asset).use {
        it.readBounded(2 * 1024 * 1024).toString(Charsets.UTF_8)
    })
    private fun write(data: UserSourceCatalog) {
        val raw = JSONObject().put("version", CATALOG_VERSION).put("selected", data.selected ?: JSONObject.NULL)
            .put("sources", JSONArray().apply { data.entries.forEach { put(JSONObject().put("script", it.script).put("allowUpdateAlert", it.allowUpdateAlert)) } })
            .toString().toByteArray(Charsets.UTF_8)
        require(raw.size <= 32 * 1024 * 1024) { "音源库超过 32 MB" }
        context.filesDir.mkdirs()
        val stream = target.startWrite()
        try { stream.write(raw); target.finishWrite(stream) } catch (e: Exception) { target.failWrite(stream); throw e }
    }
    companion object {
        const val BUNDLED_ASSET = "script/flower.js"
        const val BUNDLED_KUWO_ASSET = "script/oiapi-kuwo.js"
        private const val CATALOG_VERSION = 3
        private val lock = Any()
        fun metadata(script: String): UserSourceEntry {
            require(script.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "音源文件超过 2 MB" }
            val head = script.take(8192).substringBefore("*/")
            fun field(name: String, max: Int) = Regex("(?m)^\\s*\\*?\\s*@${name}\\s+([^\\r\\n]+)").find(head)?.groupValues?.get(1)?.trim()?.take(max).orEmpty()
            val id = MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
            return UserSourceEntry(id, field("name", 100).ifBlank { "自定义音源" }, field("description", 1024),
                field("version", 100), field("author", 128), field("homepage", 2048), script)
        }
    }
}
