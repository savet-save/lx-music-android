package cn.toside.music.nativeapp.karaoke

import cn.toside.music.nativeapp.data.AppPreferences

internal data class MdxParameters(
    val strengthPercent: Int = 103,
    val overlapMs: Int = 250,
    val threads: Int = availableThreads,
) {
    val cacheMode: String
        get() = if (strengthPercent == Default.strengthPercent && overlapMs == Default.overlapMs) {
            "mdx-9703-v1"
        } else {
            "mdx-9703-v1-$strengthPercent-$overlapMs"
        }

    companion object {
        const val ENABLED_KEY = "karaoke.mdx.enabled"
        const val STRENGTH_KEY = "karaoke.mdx.strength"
        const val OVERLAP_KEY = "karaoke.mdx.overlap"
        const val THREADS_KEY = "karaoke.mdx.threads"
        const val AUTO_THREADS = "auto"

        val availableThreads get() = Runtime.getRuntime().availableProcessors()
        val Default = MdxParameters()
        val strengths = listOf(90, 100, 103, 110, 120)
        val overlaps = listOf(125, 250, 500, 1000)
        val threadCounts = listOf(1, 2, 4, 8, 16, 32)

        fun read(preferences: AppPreferences): MdxParameters {
            fun choice(key: String, default: Int, options: List<Int>): Int =
                preferences.string(key, default.toString()).toIntOrNull()?.takeIf { it in options } ?: default
            val threads = preferences.string(THREADS_KEY, AUTO_THREADS).toIntOrNull()
                ?.takeIf { it in threadCounts } ?: availableThreads

            return MdxParameters(
                choice(STRENGTH_KEY, Default.strengthPercent, strengths),
                choice(OVERLAP_KEY, Default.overlapMs, overlaps),
                threads,
            )
        }
    }
}
