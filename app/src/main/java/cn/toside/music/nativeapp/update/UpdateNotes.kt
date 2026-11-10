package cn.toside.music.nativeapp.update

import java.util.Locale

/** A bounded, readable preview of a release body. Downloads and verification use asset metadata. */
internal object UpdateNotes {
    private val heading = Regex("^(#{1,6})\\s+(.+)$")
    private val listItem = Regex("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)")
    private val technicalHeadings = setOf("sha256", "apksha256", "安装包sha256", "安裝包sha256", "sha256checksums",
        "apksha256checksums", "sha256校验", "sha256校驗", "校验和", "校驗和", "校验值", "校驗值", "checksum", "checksums",
        "apk", "asset", "assets", "artifact", "artifacts", "安装包", "安裝包", "安装包下载", "安裝包下載",
        "下载地址", "下載地址", "下载链接", "下載連結", "downloadlinks")
    private val link = Regex("!?\\[([^]\\r\\n]*)]\\((?:[^()\\r\\n]|\\([^()\\r\\n]*\\))*\\)")
    private val reference = Regex("(?:完整改动|完整改動|完整更新说明|完整更新說明|full changelog|compare(?: changes)?|[a-f0-9]{7,64}|.*\\.apk)", RegexOption.IGNORE_CASE)
    private val maintenance = Regex("^(?:test|tests|docs|ci|build|chore|style)(?:\\([^)]*\\))?!?:", RegexOption.IGNORE_CASE)
    private val changePrefix = Regex("^(?:feat|fix|perf|refactor|revert)(?:\\([^)]*\\))?!?:\\s*", RegexOption.IGNORE_CASE)
    private val versionChange = Regex("(?:^|并|並|[,，；;]\\s*)(?:更新|升级|升級|调整|調整)版本(?:号|號)?(?:到|至|为|為)?\\s*v?\\d+(?:\\.\\d+){1,3}\\s*[.。]?\\s*$", RegexOption.IGNORE_CASE)
    private val versionOnly = Regex("^(?:(?:bump|release|version)(?:\\s+version|\\s+to)?\\s+)?v?\\d+(?:\\.\\d+){1,3}[.。]?$", RegexOption.IGNORE_CASE)

    fun summary(markdown: String): String {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) { blocks += current.toString(); current.clear() }
        }
        var skippedLevel: Int? = null
        var fence: String? = null
        val body = markdown.take(32_000)
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<details\\b.*?</details>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("```") || line.startsWith("~~~")) {
                flush()
                val marker = line.take(3)
                if (fence == null) fence = marker else if (fence == marker) fence = null
                continue
            }
            if (fence != null) continue
            val title = heading.matchEntire(line)
            if (title != null) {
                flush()
                val level = title.groupValues[1].length
                if (skippedLevel != null && level <= skippedLevel) skippedLevel = null
                val name = title.groupValues[2].lowercase(Locale.ROOT).filterNot { it.isWhitespace() || it in "*_:-：" }
                if (skippedLevel == null && name in technicalHeadings) skippedLevel = level
                continue
            }
            if (skippedLevel != null) continue
            if (line.isBlank() || line.startsWith('|') || line.matches(Regex("[-*_]{3,}"))) { flush(); continue }
            if (listItem.containsMatchIn(line)) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(line.replaceFirst(listItem, ""))
        }
        flush()
        return blocks.map(::plainText).filter { it.isNotBlank() }.distinct().take(5)
            .joinToString("\n") { "• ${shorten(it)}" }
    }

    private fun plainText(block: String): String {
        if (maintenance.containsMatchIn(block)) return ""
        val value = block.replace(link) { match -> match.groupValues[1].takeUnless(reference::matches).orEmpty() }
            .replace(Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b[a-fA-F0-9]{64}\\b"), "")
            .replace(Regex("\\(\\s*(?:[a-fA-F0-9]{7,40})?\\s*\\)"), "")
            .replace(Regex("<[^>]*>"), " ")
            .replace("**", "").replace("__", "").replace("`", "")
            .replace(Regex("(?<!\\w)([*_])([^*_\\r\\n]+)\\1(?!\\w)"), "$2")
            .replaceFirst(changePrefix, "").replace(versionChange, "")
            .replace(Regex("\\s+"), " ").trim(' ', ',', '，', ';', '；', ':', '：', '-', '–')
        return value.takeUnless { versionOnly.matches(it) || reference.matches(it) }.orEmpty()
    }

    private fun shorten(value: String): String = if (value.codePointCount(0, value.length) <= 140) value
        else value.substring(0, value.offsetByCodePoints(0, 139)).trimEnd() + "…"
}
