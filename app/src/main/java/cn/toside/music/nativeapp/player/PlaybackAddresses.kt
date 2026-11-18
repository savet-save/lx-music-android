package cn.toside.music.nativeapp.player

import java.net.URI

/** Avoid rejected CDN endpoints while accepting newly signed URLs when all hosts were tried. */
internal class PlaybackAddresses {
    private val failed = mutableSetOf<String>()
    private val rejectedHosts = mutableSetOf<String>()
    private fun host(url: String) = runCatching { URI(url).host }.getOrNull()
    fun failed(url: String, rejected: Boolean) {
        failed.add(url)
        if (rejected) host(url)?.let(rejectedHosts::add)
    }
    fun choose(primary: String, backups: List<String>): String {
        val urls = (listOf(primary) + backups).distinct()
        return urls.firstOrNull { it !in failed && host(it) !in rejectedHosts }
            ?: urls.firstOrNull { it != primary && it !in failed }
            ?: urls.firstOrNull { it !in failed } ?: primary
    }
}
