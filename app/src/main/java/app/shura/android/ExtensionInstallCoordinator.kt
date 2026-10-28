package app.shura.android

import app.shura.core.content.ContentPolicyEngine
import app.shura.core.repository.ExtensionDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ApkDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
)

data class ApkArchiveMetadata(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val isMihonExtension: Boolean,
    val signerSha256: Set<String>,
)

data class VerifiedExtensionApk(
    val descriptor: ExtensionDescriptor,
    val file: File,
    val archive: ApkArchiveMetadata,
    val sha256: String,
)

fun interface ApkArchiveInspector {
    fun inspect(file: File): ApkArchiveMetadata
}

fun interface HttpConnectionOpener {
    fun open(uri: URI): HttpURLConnection
}

class HttpsApkDownloader(
    private val openConnection: HttpConnectionOpener = HttpConnectionOpener {
        it.toURL().openConnection() as HttpURLConnection
    },
    private val maxBytes: Long = MAX_APK_BYTES,
) {
    init {
        require(maxBytes > 0) { "حد حجم APK يجب أن يكون موجبًا" }
    }

    suspend fun download(
        url: String,
        destination: File,
        onProgress: (ApkDownloadProgress) -> Unit,
    ): Long {
        var current = safeHttpsUri(url)
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val connection = openConnection.open(current)
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
                connection.setRequestProperty("User-Agent", "Shura/0.1.2 extension-installer")
                when (val status = connection.responseCode) {
                    in REDIRECT_CODES -> {
                        check(redirects < MAX_REDIRECTS) { "تجاوز رابط APK عدد التحويلات المسموح" }
                        val location = connection.getHeaderField("Location")
                            ?: error("رابط APK أعاد تحويلًا بلا عنوان")
                        current = safeHttpsUri(current.resolve(location).toString())
                        redirects++
                    }
                    HttpURLConnection.HTTP_OK -> {
                        val totalBytes = connection.contentLengthLong.takeIf { it >= 0L }
                        require(totalBytes == null || totalBytes in 1..maxBytes) {
                            "حجم APK يتجاوز الحد المسموح أو يساوي صفرًا"
                        }
                        destination.parentFile?.let { parent ->
                            if (!parent.exists() && !parent.mkdirs()) error("تعذر إنشاء مجلد تنزيل APK")
                        }
                        var downloaded = 0L
                        try {
                            connection.inputStream.use { input ->
                                FileOutputStream(destination).use { output ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        downloaded += count
                                        require(downloaded <= maxBytes) { "حجم APK يتجاوز الحد المسموح" }
                                        output.write(buffer, 0, count)
                                        onProgress(ApkDownloadProgress(downloaded, totalBytes))
                                    }
                                    output.fd.sync()
                                }
                            }
                            require(downloaded > 0L) { "ملف APK الذي تم تنزيله فارغ" }
                            require(totalBytes == null || downloaded == totalBytes) {
                                "اكتمل اتصال التنزيل بملف APK ناقص"
                            }
                            return downloaded
                        } catch (error: Exception) {
                            destination.delete()
                            throw error
                        }
                    }
                    else -> throw IOException("فشل تنزيل APK: HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun safeHttpsUri(value: String): URI {
        val uri = try {
            URI(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("رابط APK غير صالح", error)
        }
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null,
        ) { "يجب أن يستخدم رابط APK اتصال HTTPS آمنًا" }
        return uri
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAX_REDIRECTS = 5
        const val MAX_APK_BYTES = 128L * 1024 * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

class ExtensionInstallCoordinator(
    private val cacheDirectory: File,
    private val downloader: HttpsApkDownloader,
    private val archiveInspector: ApkArchiveInspector,
    private val contentPolicy: ContentPolicyEngine = ContentPolicyEngine(),
) {
    suspend fun prepare(
        extension: ExtensionDescriptor,
        installedSignerSha256: Set<String> = emptySet(),
        onProgress: (ApkDownloadProgress) -> Unit,
        onVerifying: () -> Unit = {},
    ): VerifiedExtensionApk {
        require(!contentPolicy.isBlocked(extension)) {
            "رفض تثبيت الإضافة وفق سياسة المحتوى المدمجة"
        }
        require(extension.packageName.matches(PACKAGE_NAME_PATTERN)) { "معرّف حزمة الإضافة غير صالح" }
        val apkUrl = requireNotNull(extension.apkUrl) { "لا يحتوي الفهرس رابط APK لهذه الإضافة" }
        safeHttpsUri(apkUrl)

        val destination = File.createTempFile("shura-extension-", ".apk", cacheDirectory)
        try {
            downloader.download(apkUrl, destination, onProgress)
            onVerifying()
            val digest = sha256(destination)
            extension.apkSha256?.let { expected ->
                require(digest.equals(expected, ignoreCase = true)) {
                    "فشل التحقق من بصمة APK المنشورة في الفهرس"
                }
            }
            val archive = archiveInspector.inspect(destination)
            require(archive.packageName == extension.packageName) {
                "معرّف الحزمة لا يطابق فهرس '${extension.name}': المتوقع " +
                    "${extension.packageName}، والفعلي ${archive.packageName}"
            }
            require(archive.versionCode == extension.versionCode) {
                "رقم إصدار APK لا يطابق فهرس '${extension.name}' (${extension.packageName}): " +
                    "المتوقع versionName='${extension.version}' versionCode=${extension.versionCode}، " +
                    "والفعلي versionName='${archive.versionName ?: "غير متوفر"}' " +
                    "versionCode=${archive.versionCode}"
            }
            require(extension.version.isBlank() || archive.versionName == extension.version) {
                "اسم إصدار APK لا يطابق فهرس '${extension.name}' (${extension.packageName}): " +
                    "المتوقع versionName='${extension.version}' versionCode=${extension.versionCode}، " +
                    "والفعلي versionName='${archive.versionName ?: "غير متوفر"}' " +
                    "versionCode=${archive.versionCode}"
            }
            extension.apkSignerSha256?.let { expectedSigner ->
                require(archive.signerSha256.any { it.equals(expectedSigner, ignoreCase = true) }) {
                    "توقيع APK لا يطابق بصمة التوقيع المنشورة في الفهرس"
                }
            }
            require(archive.isMihonExtension) {
                "هذا APK لا يعلن بيانات إضافة Mihon المتوافقة"
            }
            require(archive.signerSha256.isNotEmpty()) { "تعذر التحقق من توقيع APK" }
            if (installedSignerSha256.isNotEmpty()) {
                require(archive.signerSha256.any(installedSignerSha256::contains)) {
                    "توقيع APK الجديد لا يطابق توقيع الإضافة المثبتة"
                }
            }
            return VerifiedExtensionApk(extension, destination, archive, digest)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun safeHttpsUri(value: String) {
        val uri = try {
            URI(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("رابط APK غير صالح", error)
        }
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null,
        ) { "يجب أن يستخدم رابط APK اتصال HTTPS آمنًا" }
    }

    private companion object {
        val PACKAGE_NAME_PATTERN = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    }
}
