package cn.toside.music.nativeapp.update

import org.junit.Assert.*
import org.junit.Test

class UpdateNotesTest {
    @Test fun publishedReleaseBecomesASingleUserFacingChange() {
        val body = requireNotNull(javaClass.classLoader?.getResource("update/release-v0.9.8.md")).readText()
        assertEquals("• 减少设置弹窗", UpdateNotes.summary(body))
    }

    @Test fun readableMarkdownKeepsDescriptionsWithoutRawLinksOrFormatting() {
        val body = """
            ## 更新记录
            - **修复** [歌词显示](https://example.org/lyrics)中的 `偏移` 问题。
              保留原有歌曲设置。
            - feat(player): 支持 *逐字歌词* 与 _译文_。

            **Full Changelog**: https://example.org/compare/a...b
        """.trimIndent()
        assertEquals("• 修复 歌词显示中的 偏移 问题。 保留原有歌曲设置。\n• 支持 逐字歌词 与 译文。", UpdateNotes.summary(body))
    }

    @Test fun technicalSectionsAreSkippedWithoutLosingLaterChanges() {
        val body = """
            ## 更新记录
            - 修复播放中断。
            ## APK SHA-256
            | 文件 | 哈希 |
            | --- | --- |
            | app.apk | ${"a".repeat(64)} |
            ### 构建详情
            - 内部构建任务。
            ## 使用改进
            - 优化列表滚动。
        """.trimIndent()
        assertEquals("• 修复播放中断。\n• 优化列表滚动。", UpdateNotes.summary(body))
    }

    @Test fun buildMetadataVersionBumpsAndDuplicateChangesDoNotFillThePreview() {
        val body = """
            <!-- generated release metadata -->
            - docs: 更新开发说明
            - chore(release): 0.9.8
            - 更新版本到0.9.8
            - 修复同步断开。
            - 修复同步断开。
            <details><summary>构建信息</summary>内部任务清单</details>
            ```shell
            sha256sum app.apk
            ```
        """.trimIndent()
        assertEquals("• 修复同步断开。", UpdateNotes.summary(body))
    }

    @Test fun largeReleaseBodiesHaveABoundedPreviewWithoutSplittingEmoji() {
        val text = (1..8).joinToString("\n") { "- 改进 $it：" + "🌷".repeat(200) }
        val lines = UpdateNotes.summary(text).lines()
        assertEquals(5, lines.size)
        lines.forEach { line ->
            assertTrue(line.endsWith("…"))
            assertTrue(line.codePointCount(0, line.length) <= 142)
            assertFalse(line.dropLast(1).last().isHighSurrogate())
        }
    }

    @Test fun technicalOnlyOrEmptyBodiesHaveNoFabricatedChanges() {
        assertEquals("", UpdateNotes.summary(""))
        assertEquals("", UpdateNotes.summary("## APK SHA-256\n| app.apk | ${"a".repeat(64)} |"))
        assertEquals("", UpdateNotes.summary("[完整改动](https://example.org/compare/a...b)"))
    }

    @Test fun userFacingInstallationFixesAreNotMistakenForChecksumMetadata() {
        assertEquals("• 修复升级后无法安装的问题。", UpdateNotes.summary("## 修复 APK 安装失败\n- 修复升级后无法安装的问题。"))
        assertEquals("• 修复安装包校验异常。", UpdateNotes.summary("## 安装包校验改进\n- 修复安装包校验异常。"))
        assertEquals("• 修复更新版本到0.9.8后无法播放的问题。", UpdateNotes.summary("- 修复更新版本到0.9.8后无法播放的问题。"))
    }
}
