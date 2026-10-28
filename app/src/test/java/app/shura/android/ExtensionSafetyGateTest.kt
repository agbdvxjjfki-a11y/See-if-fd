package app.shura.android

import app.shura.core.repository.ExtensionDescriptor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionSafetyGateTest {
    private val gate = ExtensionSafetyGate()

    @Test
    fun explicitContentIsBlockedAtTheInstallDecisionBoundary() {
        assertFalse(gate.mayInstall(extension(name = "Hentai Reader", nsfw = 0)))
        assertFalse(gate.mayInstall(extension(name = "Catalog entry", nsfw = 1)))
    }

    @Test
    fun ordinaryRomanceIsNotBlocked() {
        assertTrue(gate.mayInstall(extension(name = "Summer Romance")))
    }

    private fun extension(
        name: String,
        nsfw: Int = 0,
    ) = ExtensionDescriptor(
        name = name,
        packageName = "org.example.reader",
        apkPath = "reader.apk",
        language = "en",
        version = "1.0",
        versionCode = 1,
        repositoryUrl = "https://example.org/extensions",
        adultFlag = nsfw,
    )
}
