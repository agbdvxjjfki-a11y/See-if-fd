package app.shura.android

import android.content.pm.PackageInstaller
import app.shura.core.repository.ExtensionDescriptor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ExtensionInstallCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun downloadsAndVerifiesPackageVersionAndOptionalTrustedHash() = runBlocking {
        val apkBytes = byteArrayOf(1, 2, 3, 4)
        val descriptor = extension().copy(apkSha256 = sha256(apkBytes))
        var progress = ApkDownloadProgress(0, null)
        val coordinator = coordinator(apkBytes, ApkArchiveMetadata(
            packageName = descriptor.packageName,
            versionCode = descriptor.versionCode,
            versionName = descriptor.version,
            isMihonExtension = true,
            signerSha256 = setOf("trusted-signer"),
        ))

        val result = coordinator.prepare(
            extension = descriptor,
            onProgress = { progress = it },
        )

        assertEquals(descriptor.packageName, result.archive.packageName)
        assertEquals(descriptor.versionCode, result.archive.versionCode)
        assertEquals(sha256(apkBytes), result.sha256)
        assertEquals(apkBytes.size.toLong(), progress.downloadedBytes)
        assertTrue(result.file.exists())
    }

    @Test
    fun blocksPolicyRejectedExtensionBeforeNetworkDownload() = runBlocking {
        var requested = false
        val coordinator = ExtensionInstallCoordinator(
            cacheDirectory = temporaryFolder.root,
            downloader = HttpsApkDownloader(HttpConnectionOpener {
                requested = true
                FakeHttpConnection(it.toURL(), 200, byteArrayOf(1))
            }),
            archiveInspector = ApkArchiveInspector {
                error("A blocked extension must not be inspected")
            },
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.prepare(extension(name = "Hentai Reader"), onProgress = {})
        }
        assertTrue(!requested)
    }

    @Test
    fun rejectsWrongPackageVersionSignatureAndBadHash(): Unit = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        val descriptor = extension().copy(apkSha256 = sha256(byteArrayOf(8)))

        assertFailsWith<IllegalArgumentException> {
            coordinator(bytes, archive(packageName = "org.other.extension"))
                .prepare(descriptor.copy(apkSha256 = null), onProgress = {})
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator(bytes, archive(versionCode = 4))
                .prepare(descriptor.copy(apkSha256 = null), onProgress = {})
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator(bytes, archive().copy(versionName = "1.3"))
                .prepare(descriptor.copy(apkSha256 = null), onProgress = {})
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator(bytes, archive())
                .prepare(descriptor, onProgress = {})
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator(bytes, archive(signer = "new-signer"))
                .prepare(
                    descriptor.copy(apkSha256 = null),
                    installedSignerSha256 = setOf("old-signer"),
                    onProgress = {},
                )
        }
        assertFailsWith<IllegalArgumentException> {
            coordinator(bytes, archive(isMihonExtension = false))
                .prepare(descriptor.copy(apkSha256 = null), onProgress = {})
        }
        Unit
    }

    @Test
    fun rejectsFailedAndTruncatedDownloadsAndDeletesPartialFiles() = runBlocking {
        val errorDownloader = HttpsApkDownloader(HttpConnectionOpener {
            FakeHttpConnection(it.toURL(), 503, byteArrayOf())
        })
        val coordinator = ExtensionInstallCoordinator(
            cacheDirectory = temporaryFolder.root,
            downloader = errorDownloader,
            archiveInspector = ApkArchiveInspector { error("must not inspect") },
        )
        assertFailsWith<java.io.IOException> {
            coordinator.prepare(extension(), onProgress = {})
        }
        assertEquals(0, temporaryFolder.root.listFiles().orEmpty().size)

        val truncated = HttpsApkDownloader(HttpConnectionOpener {
            FakeHttpConnection(it.toURL(), 200, byteArrayOf(1), contentLength = 8)
        })
        val truncatedCoordinator = ExtensionInstallCoordinator(
            cacheDirectory = temporaryFolder.root,
            downloader = truncated,
            archiveInspector = ApkArchiveInspector { error("must not inspect") },
        )
        assertFailsWith<IllegalArgumentException> {
            truncatedCoordinator.prepare(extension(), onProgress = {})
        }
        assertEquals(0, temporaryFolder.root.listFiles().orEmpty().size)
    }

    @Test
    fun rejectsHttpsDowngradeRedirect() = runBlocking {
        val downloader = HttpsApkDownloader(HttpConnectionOpener {
            FakeHttpConnection(it.toURL(), 302, byteArrayOf(), location = "http://example.org/file.apk")
        })
        val destination = File(temporaryFolder.root, "download.apk")

        assertFailsWith<IllegalArgumentException> {
            downloader.download("https://example.org/file.apk", destination, onProgress = {})
        }
        assertTrue(!destination.exists())
    }

    @Test
    fun onlyReportsInstalledAfterAndroidSuccessAndInstalledPackageVersionVerification() {
        val androidSuccess = extensionInstallOutcome(
            status = PackageInstaller.STATUS_SUCCESS,
            expectedPackage = "org.example.extension",
            expectedVersionCode = 7,
            installed = InstalledExtensionPackage(
                packageName = "org.example.extension",
                versionName = "1.2",
                versionCode = 7,
                isMihonExtension = true,
                signerSha256 = setOf("trusted-signer"),
            ),
            systemMessage = null,
        )
        val mismatchedPackage = extensionInstallOutcome(
            status = PackageInstaller.STATUS_SUCCESS,
            expectedPackage = "org.example.extension",
            expectedVersionCode = 7,
            installed = InstalledExtensionPackage(
                packageName = "org.other.extension",
                versionName = "1.2",
                versionCode = 7,
                isMihonExtension = true,
                signerSha256 = setOf("trusted-signer"),
            ),
            systemMessage = null,
        )
        val declined = extensionInstallOutcome(
            status = PackageInstaller.STATUS_FAILURE_ABORTED,
            expectedPackage = "org.example.extension",
            expectedVersionCode = 7,
            installed = null,
            systemMessage = null,
        )

        assertEquals(ExtensionOperationPhase.INSTALLED, androidSuccess.phase)
        assertEquals(ExtensionOperationPhase.FAILED, mismatchedPackage.phase)
        assertEquals(ExtensionOperationPhase.FAILED, declined.phase)
        assertTrue(declined.message?.contains("أُلغي") == true)
    }

    @Test
    fun detectsUpdatesOnlyForRecognizedMihonExtensionPackages() {
        val descriptor = extension()
        val update = extensionOperationForInstalledPackage(
            descriptor,
            InstalledExtensionPackage(
                packageName = descriptor.packageName,
                versionName = "1.1",
                versionCode = descriptor.versionCode - 1,
                isMihonExtension = true,
                signerSha256 = setOf("trusted-signer"),
            ),
        )
        val incompatible = extensionOperationForInstalledPackage(
            descriptor,
            InstalledExtensionPackage(
                packageName = descriptor.packageName,
                versionName = "1.1",
                versionCode = descriptor.versionCode - 1,
                isMihonExtension = false,
                signerSha256 = emptySet(),
            ),
        )
        val wrongPackage = extensionOperationForInstalledPackage(
            descriptor,
            InstalledExtensionPackage(
                packageName = "org.example.other",
                versionName = descriptor.version,
                versionCode = descriptor.versionCode,
                isMihonExtension = true,
                signerSha256 = setOf("trusted-signer"),
            ),
        )
        val newerInstalled = extensionOperationForInstalledPackage(
            descriptor,
            InstalledExtensionPackage(
                packageName = descriptor.packageName,
                versionName = "1.3",
                versionCode = descriptor.versionCode + 1,
                isMihonExtension = true,
                signerSha256 = setOf("trusted-signer"),
            ),
        )

        assertEquals(ExtensionOperationPhase.UPDATE_AVAILABLE, update.phase)
        assertEquals(ExtensionOperationPhase.INCOMPATIBLE, incompatible.phase)
        assertEquals(ExtensionOperationPhase.INCOMPATIBLE, wrongPackage.phase)
        assertEquals(ExtensionOperationPhase.INSTALLED, newerInstalled.phase)
    }

    private fun coordinator(
        body: ByteArray,
        metadata: ApkArchiveMetadata = archive(),
    ) = ExtensionInstallCoordinator(
        cacheDirectory = temporaryFolder.root,
        downloader = HttpsApkDownloader(HttpConnectionOpener {
            FakeHttpConnection(it.toURL(), 200, body)
        }),
        archiveInspector = ApkArchiveInspector { metadata },
    )

    private fun archive(
        packageName: String = "org.example.extension",
        versionCode: Long = 7,
        signer: String = "trusted-signer",
        isMihonExtension: Boolean = true,
    ) = ApkArchiveMetadata(
        packageName = packageName,
        versionCode = versionCode,
        versionName = "1.2",
        isMihonExtension = isMihonExtension,
        signerSha256 = setOf(signer),
    )

    private fun extension(name: String = "Safe Reader") = ExtensionDescriptor(
        name = name,
        packageName = "org.example.extension",
        apkPath = "extension.apk",
        language = "en",
        version = "1.2",
        versionCode = 7,
        repositoryUrl = "https://example.org/repository",
        adultFlag = 0,
        apkUrl = "https://example.org/repository/extension.apk",
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class FakeHttpConnection(
        url: URL,
        private val status: Int,
        private val body: ByteArray,
        private val contentLength: Long = body.size.toLong(),
        private val location: String? = null,
    ) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = contentLength
        override fun getHeaderField(name: String?): String? =
            if (name.equals("Location", ignoreCase = true)) location else null
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }
}
