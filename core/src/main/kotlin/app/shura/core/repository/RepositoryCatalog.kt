package app.shura.core.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

@ConsistentCopyVisibility
data class RepositoryAddress private constructor(val url: URI) {
    val normalized: String
        get() = url.toString().removeSuffix("/")

    fun artifactUri(path: String): URI {
        val relative = runCatching { URI(path.trim()) }
            .getOrElse { throw IllegalArgumentException("مسار ملف الإضافة غير صالح", it) }
        require(
            !relative.isAbsolute &&
                relative.rawAuthority == null &&
                relative.rawQuery == null &&
                relative.rawFragment == null &&
                !relative.rawPath.isNullOrBlank() &&
                !relative.rawPath.startsWith("/") &&
                '\\' !in relative.path,
        ) { "يجب أن يكون مسار ملف الإضافة نسبيًا للمستودع" }
        require(relative.path.split('/').none { it == "." || it == ".." }) {
            "مسار ملف الإضافة يتجاوز مسار المستودع"
        }

        val resolved = url.resolve(relative)
        val repositoryPath = url.path
        require(
            resolved.scheme == "https" &&
                resolved.host.equals(url.host, ignoreCase = true) &&
                resolved.port == url.port &&
                resolved.path.startsWith(repositoryPath),
        ) { "عنوان ملف الإضافة خارج نطاق المستودع" }
        return resolved
    }

    companion object {
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
            val path = uri.path.trimEnd('/')
            val repositoryPath = if (path.isEmpty()) "/" else "$path/"
            return RepositoryAddress(
                URI("https", null, uri.host.lowercase(), uri.port, repositoryPath, null, null),
            )
        }
    }
}

data class ExtensionDescriptor(
    val name: String,
    val packageName: String,
    val apkPath: String,
    val language: String,
    val version: String,
    val versionCode: Long,
    val repositoryUrl: String,
    val adultFlag: Int,
)

class RepositoryCatalogParser {
    fun parse(indexJson: String, repository: RepositoryAddress): List<ExtensionDescriptor> {
        val root = runCatching { JsonParser.parseString(indexJson) }
            .getOrElse { throw IllegalArgumentException("تعذر تحليل فهرس المستودع", it) }
        require(root.isJsonArray) { "تنسيق الفهرس غير مدعوم: المتوقع مصفوفة إضافات JSON" }

        val seenPackages = mutableSetOf<String>()
        return root.asJsonArray.mapIndexed { index, element ->
            val item = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
                ?: throw IllegalArgumentException("بيانات الإضافة رقم ${index + 1} ليست كائن JSON")
            parseExtension(item, repository).also { descriptor ->
                require(seenPackages.add(descriptor.packageName)) {
                    "الفهرس يحتوي معرّف حزمة مكررًا: ${descriptor.packageName}"
                }
            }
        }
    }

    private fun parseExtension(item: JsonObject, repository: RepositoryAddress): ExtensionDescriptor {
        val name = item.string("name")
        val packageName = item.string("pkg")
        val apkPath = item.string("apk")
        val version = item.string("version")
        val language = item.string("lang")
        val versionCode = item.long("versionCode")
        val adultFlag = item.intOrZero("nsfw")
        require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
            "معرّف حزمة الإضافة غير صالح"
        }
        require(name.isNotBlank() && version.isNotBlank() && language.isNotBlank()) {
            "بيانات الإضافة تتضمن حقولًا فارغة"
        }
        repository.artifactUri(apkPath)
        require(versionCode >= 0) { "رقم إصدار الإضافة غير صالح" }
        require(adultFlag >= 0) { "تصنيف المحتوى في الفهرس غير صالح" }
        return ExtensionDescriptor(
            name = name,
            packageName = packageName,
            apkPath = apkPath,
            language = language,
            version = version,
            versionCode = versionCode,
            repositoryUrl = repository.normalized,
            adultFlag = adultFlag,
        )
    }

    private fun JsonObject.string(key: String): String {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "حقل الفهرس '$key' مفقود أو غير صالح"
        }
        return value.asString.also {
            require(it.isNotBlank()) { "حقل الفهرس '$key' فارغ" }
        }
    }

    private fun JsonObject.long(key: String): Long = runCatching {
        val value = get(key)
        require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isNumber)
        value.asString.toLong()
    }.getOrElse { throw IllegalArgumentException("حقل الفهرس '$key' مفقود أو غير صالح", it) }

    private fun JsonObject.intOrZero(key: String): Int {
        val value = get(key) ?: return 0
        return runCatching {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            value.asString.toInt()
        }.getOrElse {
            throw IllegalArgumentException("حقل الفهرس '$key' غير صالح", it)
        }
    }
}