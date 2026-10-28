package app.shura.android

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class SavedOnlineManga(val id: String, val title: String)

data class OnlineReadingProgress(
    val mangaId: String,
    val chapterId: String,
    val pageIndex: Int,
)

internal fun encodeOnlineLibrary(entries: List<SavedOnlineManga>): String =
    entries.distinctBy { it.id }.joinToString("\n") { entry ->
        "${entry.id}\t${URLEncoder.encode(entry.title, StandardCharsets.UTF_8)}"
    }

internal fun decodeOnlineLibrary(value: String?): List<SavedOnlineManga> =
    value.orEmpty().lineSequence().mapNotNull { line ->
        val separator = line.indexOf('\t')
        if (separator <= 0) return@mapNotNull null
        val id = line.substring(0, separator).takeIf(UUID_PATTERN::matches) ?: return@mapNotNull null
        val title = runCatching {
            URLDecoder.decode(line.substring(separator + 1), StandardCharsets.UTF_8).trim()
        }.getOrNull()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        SavedOnlineManga(id, title)
    }.distinctBy { it.id }.toList()

internal fun encodeOnlineReadingProgress(progress: Map<String, OnlineReadingProgress>): String =
    progress.values.joinToString("\n") { entry ->
        "${entry.mangaId}\t${entry.chapterId}\t${entry.pageIndex}"
    }

internal fun decodeOnlineReadingProgress(value: String?): Map<String, OnlineReadingProgress> =
    value.orEmpty().lineSequence().mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size != 3) return@mapNotNull null
        val mangaId = fields[0].takeIf(UUID_PATTERN::matches) ?: return@mapNotNull null
        val chapterId = fields[1].takeIf(UUID_PATTERN::matches) ?: return@mapNotNull null
        val pageIndex = fields[2].toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        OnlineReadingProgress(mangaId, chapterId, pageIndex)
    }.associateBy { it.mangaId }

private val UUID_PATTERN =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
