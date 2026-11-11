package cn.toside.music.nativeapp.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcExportTest {
    @Test fun exportsSortedMillisecondTimestampsAndTranslationsWithoutInjectedLines() {
        val lines = listOf(LyricLine(62_345, "第二行\n继续", translation = "second", romanization = "second"),
            LyricLine(-5, "第一行", translation = "first"), LyricLine(1000, ""))
        assertEquals("[00:00.000]第一行\n[00:00.000]first\n[01:02.345]第二行 继续\n[01:02.345]second\n", exportLrc(lines))
        assertEquals(listOf(0L, 0L, 62_345L, 62_345L), LrcParser.parse(exportLrc(lines)).map { it.timeMs })
    }
}
