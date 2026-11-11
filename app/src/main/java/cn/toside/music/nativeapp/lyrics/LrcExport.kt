package cn.toside.music.nativeapp.lyrics

/** Standard UTF-8 LRC with translations and romanization at the original line timestamp. */
internal fun exportLrc(lines: List<LyricLine>): String = buildString {
    lines.sortedBy { it.timeMs }.forEach { line ->
        val time = line.timeMs.coerceAtLeast(0)
        val stamp = "[${(time / 60_000).toString().padStart(2, '0')}:${(time / 1000 % 60).toString().padStart(2, '0')}.${(time % 1000).toString().padStart(3, '0')}]"
        listOf(line.text, line.translation, line.romanization).distinct().filter { it.isNotBlank() }.forEach { text ->
            append(stamp); append(text.replace(Regex("[\\r\\n]+"), " ")); append('\n')
        }
    }
}
