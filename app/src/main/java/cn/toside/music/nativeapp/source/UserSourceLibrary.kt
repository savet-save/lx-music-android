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
internal data class UserSourceCatalog(val selected: String?, val entries: List<UserSourceEntry>,
    val enabled: List<String> = listOfNotNull(selected)) {
    val enabledEntries get() = enabled.mapNotNull { id -> entries.firstOrNull { it.id == id } }
    val disabledEntries get() = entries.filter { it.id !in enabled }
    fun withEnabled(ids: List<String>) = copy(selected = ids.firstOrNull(), enabled = ids)
}

/** The selected ID and scripts are written atomically; importing one script never discards the others. */
internal class UserSourceLibrary(private val context: Context) {
    private val target = AtomicFile(File(context.filesDir, "user-sources.json"))
    fun read(): UserSourceCatalog = synchronized(lock) {
        if (!target.baseFile.isFile) {
            val legacy = File(context.filesDir, "user-source.js")
            val bundled = listOf(bundled(BUNDLED_ASSET), bundled(BUNDLED_KUWO_ASSET), bundled(BUNDLED_BILIBILI_ASSET))
            val entry = if (legacy.isFile) metadata(legacy.inputStream().use { it.readBounded(2 * 1024 * 1024) }.toString(Charsets.UTF_8)) else bundled.first()
            return@synchronized updateBundledKuwo(UserSourceCatalog(entry.id, (listOf(entry) + bundled).distinctBy { it.id })).also(::write)
        }
        val data = target.openRead().use { JSONObject(it.readBounded(32 * 1024 * 1024).toString(Charsets.UTF_8)) }
        val values = data.getJSONArray("sources")
        val entries = (0 until values.length()).map { index ->
            val entry = values.getJSONObject(index)
            metadata(entry.getString("script")).copy(allowUpdateAlert = entry.optBoolean("allowUpdateAlert", true))
        }.distinctBy { it.id }
        val selected = data.optString("selected").takeIf { id -> entries.any { it.id == id } }
        val enabled = if (data.optInt("version", 1) < 4) listOfNotNull(selected) else {
            val ids = data.optJSONArray("enabled") ?: JSONArray()
            (0 until ids.length()).map { ids.optString(it) }.distinct()
                .filter { id -> entries.any { it.id == id } }.take(MAX_ENABLED)
        }
        val catalog = UserSourceCatalog(enabled.firstOrNull(), entries, enabled)
        // Add each newly packaged source once, preserving selection, disabled state and earlier removals.
        val version = data.optInt("version", 1)
        if (version < CATALOG_VERSION) {
            val additions = buildList {
                if (version < 2) add(bundled(BUNDLED_ASSET))
                if (version < 3) add(bundled(BUNDLED_KUWO_ASSET))
                if (version < 5) add(bundled(BUNDLED_BILIBILI_ASSET))
            }
            return@synchronized updateBundledKuwo(catalog.copy(entries = (entries + additions).distinctBy { it.id })).also(::write)
        }
        catalog
    }

    private fun updateBundledKuwo(catalog: UserSourceCatalog): UserSourceCatalog {
        val legacy = catalog.entries.firstOrNull { it.id in LEGACY_KUWO_IDS } ?: return catalog
        val updated = bundled(BUNDLED_KUWO_ASSET).copy(allowUpdateAlert = legacy.allowUpdateAlert)
        val entries = catalog.entries.filterNot { it.id == updated.id }.map {
            if (it.id in LEGACY_KUWO_IDS) updated else it
        }.distinctBy { it.id }
        val enabled = catalog.enabled.map { if (it in LEGACY_KUWO_IDS) updated.id else it }.distinct()
        return catalog.copy(entries = entries).withEnabled(enabled)
    }

    fun selected(): UserSourceEntry? = read().let { data -> data.entries.firstOrNull { it.id == data.selected } }
    fun isBilibili(id: String) = id == bundled(BUNDLED_BILIBILI_ASSET).id
    fun isBundled(id: String) = listOf(BUNDLED_ASSET, BUNDLED_KUWO_ASSET, BUNDLED_BILIBILI_ASSET).any { id == bundled(it).id }
    fun bilibiliEnabled() = read().enabled.any(::isBilibili)
    fun addValidated(script: String, enableWithOthers: Boolean = false): UserSourceEntry = synchronized(lock) {
        val current = read()
        val parsed = metadata(script)
        val entry = current.entries.firstOrNull { it.id == parsed.id } ?: parsed
        val entries = if (current.entries.any { it.id == entry.id }) current.entries else current.entries + entry
        val enabled = when {
            !enableWithOthers -> listOf(entry.id)
            entry.id in current.enabled -> current.enabled
            current.enabled.size < MAX_ENABLED -> listOf(entry.id) + current.enabled
            else -> current.enabled
        }
        write(UserSourceCatalog(enabled.firstOrNull(), entries, enabled))
        entry
    }
    fun select(id: String?) = synchronized(lock) {
        val current = read()
        require(id == null || current.entries.any { it.id == id }) { "音源不存在" }
        write(current.withEnabled(listOfNotNull(id)))
    }
    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val current = read()
        require(current.entries.any { it.id == id }) { "音源不存在" }
        val next = if (enabled) (current.enabled + id).distinct() else current.enabled - id
        require(next.size <= MAX_ENABLED) { "最多同时启用 $MAX_ENABLED 个音源，请先停用一个音源" }
        write(current.withEnabled(next))
    }
    fun move(id: String, delta: Int) = synchronized(lock) {
        val current = read()
        val from = current.enabled.indexOf(id)
        require(from >= 0) { "请先启用音源" }
        val next = current.enabled.toMutableList()
        val to = (from + delta).coerceIn(0, next.lastIndex)
        if (to != from) { next.removeAt(from); next.add(to, id); write(current.withEnabled(next)) }
    }
    fun setEnabledOrder(ids: List<String>) = synchronized(lock) {
        val current = read()
        require(ids.distinct().size == ids.size && ids.size <= MAX_ENABLED && ids.all { id -> current.entries.any { it.id == id } }) { "无效的音源顺序" }
        write(current.withEnabled(ids))
    }
    fun remove(id: String) = synchronized(lock) {
        val current = read()
        write(current.copy(entries = current.entries.filterNot { it.id == id }).withEnabled(current.enabled - id))
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
            .put("enabled", JSONArray(data.enabled))
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
        const val BUNDLED_BILIBILI_ASSET = "script/bilibili.js"
        const val MAX_ENABLED = 3
        private const val CATALOG_VERSION = 7
        // Previous bundled scripts (1.0.0 and 1.0.1), with LF or CRLF line endings.
        private val LEGACY_KUWO_IDS = setOf(
            "f491d9760eb1d8f8d788bd5863e8ef07cb89e55a1c88e76d7277f6bbc21e08b5",
            "ea9f11fe9fb043eada025882de4eb6be97d5897fa74e09f6667db1fc2d0e48e1",
            "509403a3912edd238124c7272ca2a20ea317110e6492d3a60d54fd17fbf774e5",
            "c3b76a28843b50b43164cb450da88b6864eaa2aea3a3ad85f677a700178f053e",
        )
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
