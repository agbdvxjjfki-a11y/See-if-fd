package app.shura.core.repository

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.charset.StandardCharsets

interface RepositoryConfigurationStore {
    fun load(): List<String>
    fun save(repositoryUrls: List<String>)
}

class FileRepositoryConfigurationStore(
    private val file: Path,
) : RepositoryConfigurationStore {
    override fun load(): List<String> {
        if (!Files.exists(file)) return emptyList()
        require(Files.size(file) <= MAX_CONFIG_BYTES) { "ملف إعدادات المستودعات أكبر من الحد المسموح" }

        val json = runCatching { JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)) }
            .getOrElse { throw IllegalStateException("تعذر قراءة إعدادات المستودعات", it) }
        require(json.isJsonArray) { "تنسيق إعدادات المستودعات غير صالح" }
        return json.asJsonArray.mapIndexed { index, value ->
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                "عنوان المستودع رقم ${index + 1} غير صالح"
            }
            value.asString
        }
    }

    override fun save(repositoryUrls: List<String>) {
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporaryFile = Files.createTempFile(parent, "shura-repositories-", ".tmp")
        try {
            Files.writeString(
                temporaryFile,
                GSON.toJson(repositoryUrls),
                StandardCharsets.UTF_8,
            )
            try {
                Files.move(
                    temporaryFile,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }

    private companion object {
        const val MAX_CONFIG_BYTES = 1024 * 1024L
        val GSON = com.google.gson.Gson()
    }
}
