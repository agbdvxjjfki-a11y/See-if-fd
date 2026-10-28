package app.shura.core.repository

import app.shura.core.repository.proto.ContentWarning
import app.shura.core.repository.proto.Index
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

class RepositoryAddress private constructor(
    val url: URI,
    val inputUrl: String,
) {
    val normalized: String
        get() = url.toString().removeSuffix("/")

    val isDirectIndexUrl: Boolean
        get() = url.path.substringAfterLast('/').lowercase() in INDEX_FILENAMES

    fun indexCandidates(): List<URI> {
        if (isDirectIndexUrl) return listOf(url)
        val directory = url.toString().let { if (it.endsWith('/')) it else "$it/" }
        return listOf("index.pb", "index.min.json", "index.json").map { URI(directory).resolve(it) }
    }

    fun artifactUri(path: String, indexUrl: URI = url): URI {
        val relative = runCatching { URI(path.trim()) }
            .getOrElse { throw IllegalArgumentException("مسار ملف الإضافة غير صالح", it) }
        if (!relative.isAbsolute) {
            require(relative.rawAuthority == null) {
                "يجب أن يكون مسار ملف الإضافة نسبيًا لموقع الفهرس"
            }
        }
        val indexDirectory = indexUrl.resolve(".")
        val resolved = if (relative.isAbsolute) relative else indexDirectory.resolve(relative)
        require(
            resolved.scheme.equals("https", ignoreCase = true) &&
                !resolved.host.isNullOrBlank() &&
                resolved.userInfo == null &&
                resolved.fragment == null,
        ) { "يجب أن يستخدم رابط ملف الإضافة HTTPS دون بيانات دخول" }
        if (!relative.isAbsolute) {
            require(
                resolved.host.equals(indexDirectory.host, ignoreCase = true) &&
                    resolved.port == indexDirectory.port &&
                (relative.path.startsWith('/') || resolved.path.startsWith(indexDirectory.path)),
            ) { "مسار ملف الإضافة يتجاوز موقع الفهرس" }
        }
        return resolved
    }

    companion object {
        private val INDEX_FILENAMES = setOf("index.pb", "index.json", "index.min.json")

        fun parse(value: String): RepositoryAddress {
            val uri = runCatching { URI(value.trim()) }
                .getOrElse { throw IllegalArgumentException("رابط المستودع غير صالح") }
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "يجب أن يستخدم رابط المستودع HTTPS"
            }
            require(!uri.host.isNullOrBlank() && uri.userInfo == null) {
                "يجب أن يحتوي الرابط على مضيف صالح دون بيانات دخول"
            }
            require(uri.query == null && uri.fragment == null) {
                "لا يقبل رابط المستودع معاملات أو أجزاء إضافية"
            }
            require(uri.path.split('/').none { it == "." || it == ".." }) {
                "مسار المستودع غير صالح"
            }
            require(!uri.path.substringAfterLast('/').lowercase().endsWith(".apk")) {
                "أدخل رابط فهرس المستودع، وليس رابط ملف APK"
            }
            val filename = uri.path.substringAfterLast('/').lowercase()
            val directIndex = filename in INDEX_FILENAMES
            val path = when {
                directIndex -> uri.path
                uri.path.isBlank() -> "/"
                else -> uri.path.trimEnd('/') + "/"
            }
            return RepositoryAddress(
                url = URI("https", null, uri.host.lowercase(), uri.port, path, null, null),
                inputUrl = value.trim(),
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is RepositoryAddress && url == other.url

    override fun hashCode(): Int = url.hashCode()

    override fun toString(): String = normalized
}

data class RepositorySourceDescriptor(
    val id: String,
    val name: String,
    val language: String,
    val homeUrl: String?,
    val mirrorUrls: List<String> = emptyList(),
    val message: String? = null,
)

data class ExtensionDescriptor(
    val name: String,
    val packageName: String,
    val apkPath: String,
    val language: String,
    val version: String,
    val versionCode: Long,
    val repositoryUrl: String,
    val adultFlag: Int,
    val sources: List<RepositorySourceDescriptor> = emptyList(),
    val apkUrl: String? = null,
    val iconUrl: String? = null,
    val apkSha256: String? = null,
    val apkSignerSha256: String? = null,
)

data class ParsedRepositoryIndex(
    val name: String,
    val extensions: List<ExtensionDescriptor>,
    val resourceBaseUrl: URI,
)

class RepositoryCatalogParser(
    private val jsonParser: JsonRepositoryIndexParser = JsonRepositoryIndexParser(),
    private val protobufParser: ProtobufRepositoryIndexParser = ProtobufRepositoryIndexParser(),
) {
    fun parse(indexJson: String, repository: RepositoryAddress): List<ExtensionDescriptor> =
        jsonParser.parse(indexJson, repository, repository.url).extensions

    fun parse(
        index: FetchedRepositoryIndex,
        repository: RepositoryAddress,
        fetchExternalList: ((URI) -> FetchedRepositoryIndex)? = null,
    ): ParsedRepositoryIndex {
        val filename = index.diagnostic.finalUrl.substringAfterLast('/').lowercase()
        val isProtobuf = when {
            filename.endsWith(".pb") -> true
            filename.endsWith(".json") -> false
            index.contentType?.contains("protobuf", ignoreCase = true) == true -> true
            else -> index.contentType?.contains("octet-stream", ignoreCase = true) == true
        }
        val parsed = if (isProtobuf) {
            protobufParser.parse(index.body, repository, URI(index.diagnostic.finalUrl), fetchExternalList)
        } else {
            val json = index.body.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            jsonParser.parse(json, repository, URI(index.diagnostic.finalUrl))
        }
        return parsed.copy(
            extensions = parsed.extensions.map { extension ->
                extension.copy(
                    repositoryUrl = repository.normalized,
                    apkUrl = repository.artifactUri(extension.apkPath, parsed.resourceBaseUrl).toString(),
                    iconUrl = extension.iconUrl?.let {
                        repository.artifactUri(it, parsed.resourceBaseUrl).toString()
                    },
                )
            },
        )
    }
}

class JsonRepositoryIndexParser {
    fun parse(
        indexJson: String,
        repository: RepositoryAddress,
        indexUrl: URI,
    ): ParsedRepositoryIndex {
        val root = runCatching { JsonParser.parseString(indexJson) }
            .getOrElse { throw IllegalArgumentException("تعذر تحليل فهرس JSON", it) }
        require(root.isJsonArray) { "تنسيق JSON غير مدعوم: المتوقع مصفوفة إضافات" }

        val seenPackages = mutableSetOf<String>()
        val extensions = root.asJsonArray.mapIndexed { index, element ->
            val item = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
                ?: throw IllegalArgumentException("بيانات الإضافة رقم ${index + 1} ليست كائن JSON")
            parseExtension(item, repository, indexUrl).also { descriptor ->
                require(seenPackages.add(descriptor.packageName)) {
                    "الفهرس يحتوي معرّف حزمة مكررًا: ${descriptor.packageName}"
                }
            }
        }
        return ParsedRepositoryIndex(name = repository.normalized, extensions = extensions, resourceBaseUrl = indexUrl)
    }

    private fun parseExtension(
        item: JsonObject,
        repository: RepositoryAddress,
        indexUrl: URI,
    ): ExtensionDescriptor {
        val name = item.string("name")
        val packageName = item.string("pkg")
        val apkPath = item.string("apk")
        val version = item.string("version")
        val language = item.string("lang")
        val versionCode = item.longEither("code", "versionCode")
        val adultFlag = item.intOrZero("nsfw")
        requireValidPackage(packageName)
        require(versionCode >= 0) { "رقم إصدار الإضافة غير صالح" }
        require(adultFlag >= 0) { "تصنيف المحتوى في الفهرس غير صالح" }
        val apkUrl = repository.artifactUri(apkPath, indexUrl).toString()
        return ExtensionDescriptor(
            name = name,
            packageName = packageName,
            apkPath = apkPath,
            language = language,
            version = version,
            versionCode = versionCode,
            repositoryUrl = repository.normalized,
            adultFlag = adultFlag,
            sources = item.sources(),
            apkUrl = apkUrl,
            iconUrl = item.optionalString("icon"),
            apkSha256 = item.optionalString("sha256")?.also {
                require(it.matches(Regex("[A-Fa-f0-9]{64}"))) { "بصمة APK غير صالحة" }
            },
            apkSignerSha256 = item.optionalString("signingKey")?.also {
                require(it.matches(Regex("[A-Fa-f0-9]{64}"))) { "بصمة توقيع الإضافة غير صالحة" }
            },
        )
    }

    private fun JsonObject.sources(): List<RepositorySourceDescriptor> {
        val value = get("sources") ?: return emptyList()
        require(value.isJsonArray) { "حقل الفهرس 'sources' غير صالح" }
        return value.asJsonArray.mapIndexed { index, element ->
            val source = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
                ?: throw IllegalArgumentException("مصدر الإضافة رقم ${index + 1} غير صالح")
            RepositorySourceDescriptor(
                id = source.optionalString("id") ?: "",
                name = source.optionalString("name") ?: "",
                language = source.optionalString("lang") ?: "",
                homeUrl = source.optionalString("baseUrl"),
                mirrorUrls = source.optionalStringArray("mirrors"),
            )
        }
    }
}

class ProtobufRepositoryIndexParser {
    fun parse(
        indexBytes: ByteArray,
        repository: RepositoryAddress,
        indexUrl: URI,
        fetchExternalList: ((URI) -> FetchedRepositoryIndex)? = null,
    ): ParsedRepositoryIndex {
        val index = runCatching { Index.parseFrom(indexBytes) }
            .getOrElse { throw IllegalArgumentException("تعذر تحليل فهرس Protocol Buffers", it) }
        var resourceBaseUrl = indexUrl
        val extensionList = when {
            index.hasExtensionList() -> index.extensionList
            index.hasExtensionListUrl() -> {
                val url = repository.artifactUri(index.extensionListUrl, indexUrl)
                val fetch = fetchExternalList
                    ?: throw IllegalArgumentException("الفهرس يشير إلى قائمة خارجية ولم يتوفر جالبها")
                val fetched = try {
                    fetch(url)
                } catch (error: Exception) {
                    throw IllegalArgumentException(
                        "تعذر جلب قائمة الإضافات الخارجية من ${safeUrl(url)}: " +
                            (error.message ?: error.javaClass.simpleName),
                        error,
                    )
                }
                resourceBaseUrl = URI(fetched.diagnostic.finalUrl)
                runCatching { app.shura.core.repository.proto.ExtensionList.parseFrom(fetched.body) }
                    .getOrElse {
                        throw IllegalArgumentException(
                            "تم تنزيل قائمة الإضافات من ${safeUrl(url)} لكن تعذر تحليل Protocol Buffers",
                            it,
                        )
                    }
            }
            else -> throw IllegalArgumentException("فهرس Protocol Buffers لا يحتوي قائمة إضافات")
        }
        val seenPackages = mutableSetOf<String>()
        val extensions = extensionList.extensionsList.mapIndexed { itemIndex, item ->
            val name = item.name.requireNonBlank("name", itemIndex)
            val packageName = item.packageName.requireNonBlank("packageName", itemIndex)
            val apkUrl = item.resources.apkUrl.requireNonBlank("resources.apkUrl", itemIndex)
            val version = item.versionName.requireNonBlank("versionName", itemIndex)
            requireValidPackage(packageName)
            require(item.versionCode >= 0) { "رمز إصدار الإضافة رقم ${itemIndex + 1} غير صالح" }
            require(seenPackages.add(packageName)) {
                "الفهرس يحتوي معرّف حزمة مكررًا: $packageName"
            }
            val sources = item.sourcesList.map { source ->
                RepositorySourceDescriptor(
                    id = source.id.toString(),
                    name = source.name,
                    language = source.language,
                    homeUrl = source.homeUrl.takeIf(String::isNotBlank),
                    mirrorUrls = source.mirrorUrlsList,
                    message = source.message.takeIf(String::isNotBlank),
                )
            }
            ExtensionDescriptor(
                name = name,
                packageName = packageName,
                apkPath = apkUrl,
                language = sources.firstOrNull()?.language.orEmpty(),
                version = version,
                versionCode = item.versionCode,
                repositoryUrl = repository.normalized,
                adultFlag = if (item.contentWarning == ContentWarning.CONTENT_WARNING_NSFW) 1 else 0,
                sources = sources,
                iconUrl = item.resources.iconUrl.takeIf(String::isNotBlank),
                apkSignerSha256 = index.signingKey.takeIf { it.matches(Regex("[A-Fa-f0-9]{64}")) },
            )
        }
        return ParsedRepositoryIndex(
            name = index.name.takeIf(String::isNotBlank) ?: repository.normalized,
            extensions = extensions,
            resourceBaseUrl = resourceBaseUrl,
        )
    }
}

private fun safeUrl(uri: URI): String =
    URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString() +
        if (uri.rawQuery != null) "?<redacted>" else ""

private fun requireValidPackage(packageName: String) {
    require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
        "معرّف حزمة الإضافة غير صالح"
    }
}

private fun String.requireNonBlank(field: String, itemIndex: Int): String =
    also { require(isNotBlank()) { "حقل '$field' مفقود في الإضافة رقم ${itemIndex + 1}" } }

private fun JsonObject.string(key: String): String {
    val value = get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
        "حقل الفهرس '$key' مفقود أو غير صالح"
    }
    return value.asString.also {
        require(it.isNotBlank()) { "حقل الفهرس '$key' فارغ" }
    }
}

private fun JsonObject.optionalString(key: String): String? {
    val value = get(key) ?: return null
    require(value.isJsonPrimitive) { "حقل الفهرس '$key' غير صالح" }
    return value.asString.takeIf(String::isNotBlank)
}

private fun JsonObject.optionalStringArray(key: String): List<String> {
    val value = get(key) ?: return emptyList()
    require(value.isJsonArray) { "حقل الفهرس '$key' غير صالح" }
    return value.asJsonArray.map { element ->
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "قيمة في حقل الفهرس '$key' غير صالحة"
        }
        element.asString
    }
}

private fun JsonObject.longEither(first: String, second: String): Long = runCatching {
    val value = get(first) ?: get(second)
    require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isNumber)
    value.asString.toLong()
}.getOrElse { throw IllegalArgumentException("حقل الفهرس '$first' أو '$second' مفقود أو غير صالح", it) }

private fun JsonObject.intOrZero(key: String): Int {
    val value = get(key) ?: return 0
    return runCatching {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        value.asString.toInt()
    }.getOrElse {
        throw IllegalArgumentException("حقل الفهرس '$key' غير صالح", it)
    }
}
