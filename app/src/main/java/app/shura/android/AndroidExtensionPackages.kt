package app.shura.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class InstalledExtensionPackage(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val isMihonExtension: Boolean,
    val signerSha256: Set<String>,
    val nsfwClassification: ApkNsfwClassification,
)

class AndroidExtensionPackages(private val context: Context) {
    private val packageManager = context.packageManager

    fun inspectInstalled(packageName: String): InstalledExtensionPackage? {
        val info = try {
            getPackageInfo(packageName, includeSigning = true)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return info.toInstalledExtensionPackage()
    }

    fun inspectArchive(file: File): ApkArchiveMetadata {
        val info = getArchiveInfo(file)
            ?: throw IllegalArgumentException("الملف الذي تم تنزيله ليس حزمة APK صالحة")
        val packageName = info.packageName
            ?: throw IllegalArgumentException("ملف APK لا يحتوي معرّف حزمة")
        val signerCertificates = info.signatureBytes()
        return ApkArchiveMetadata(
            packageName = packageName,
            versionCode = info.versionCodeLongCompat(),
            versionName = info.versionName,
            isMihonExtension = info.isMihonExtensionPackage(),
            signerSha256 = signerCertificates.map(::sha256).toSet(),
            nsfwClassification = info.nsfwExtensionClassification(),
        )
    }

    @Throws(IOException::class)
    fun install(verifiedApk: VerifiedExtensionApk) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(verifiedApk.archive.packageName)
            setSize(verifiedApk.file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                verifiedApk.file.inputStream().use { input ->
                    session.openWrite("extension.apk", 0, verifiedApk.file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callbackIntent = Intent(context, PackageInstallStatusReceiver::class.java).apply {
                    action = ACTION_PACKAGE_INSTALL_STATUS
                    putExtra(EXTRA_INSTALL_PACKAGE, verifiedApk.archive.packageName)
                    putExtra(EXTRA_INSTALL_VERSION, verifiedApk.archive.versionCode)
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
                )
                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            installer.abandonSession(sessionId)
            throw error
        } finally {
            verifiedApk.file.delete()
        }
    }

    fun uninstallSettingsIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )

    private fun getPackageInfo(packageName: String, includeSigning: Boolean): PackageInfo {
        val flags = PackageManager.GET_META_DATA.toLong() +
            PackageManager.GET_CONFIGURATIONS.toLong() +
            if (includeSigning) signingFlag().toLong() else 0L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags.toInt())
        }
    }

    private fun getArchiveInfo(file: File): PackageInfo? {
        val flags = PackageManager.GET_META_DATA.toLong() +
            PackageManager.GET_CONFIGURATIONS.toLong() +
            signingFlag().toLong()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(file.absolutePath, flags.toInt())
        }
    }

    private fun PackageInfo.toInstalledExtensionPackage() = InstalledExtensionPackage(
        packageName = packageName,
        versionName = versionName,
        versionCode = versionCodeLongCompat(),
        isMihonExtension = isMihonExtensionPackage(),
        signerSha256 = signatureBytes().map(::sha256).toSet(),
        nsfwClassification = nsfwExtensionClassification(),
    )

    private fun PackageInfo.isMihonExtensionPackage(): Boolean =
        reqFeatures.orEmpty().any { it.name == MIHON_EXTENSION_FEATURE }

    @Suppress("DEPRECATION")
    private fun PackageInfo.nsfwExtensionClassification(): ApkNsfwClassification {
        val metadata = applicationInfo?.metaData ?: return ApkNsfwClassification.MISSING
        val present = metadata.containsKey(MIHON_EXTENSION_NSFW_METADATA)
        return classifyApkNsfwMetadata(
            present = present,
            value = if (present) metadata.get(MIHON_EXTENSION_NSFW_METADATA) else null,
        )
    }

    private fun PackageInfo.signatureBytes(): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.let { signing ->
                val signatures = if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                }
                signatures.map { it.toByteArray() }
            }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            signatures?.map { it.toByteArray() }.orEmpty()
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeLongCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        const val ACTION_PACKAGE_INSTALL_STATUS = "app.shura.android.PACKAGE_INSTALL_STATUS"
        const val EXTRA_INSTALL_PACKAGE = "app.shura.android.extra.INSTALL_PACKAGE"
        const val EXTRA_INSTALL_VERSION = "app.shura.android.extra.INSTALL_VERSION"
        const val INSTALL_RESULTS_PREFERENCES = "shura_install_results"
        const val EXTRA_INSTALL_RESULT_PACKAGE = "last_package"
        const val EXTRA_INSTALL_RESULT_VERSION = "last_version"
        const val EXTRA_INSTALL_RESULT_STATUS = "last_status"
        const val EXTRA_INSTALL_RESULT_MESSAGE = "last_message"
        private const val MIHON_EXTENSION_FEATURE = "tachiyomi.extension"
        private const val MIHON_EXTENSION_NSFW_METADATA = "tachiyomi.extension.nsfw"
    }
}
