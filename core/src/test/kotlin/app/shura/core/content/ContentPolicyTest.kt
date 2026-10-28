package app.shura.core.content

import app.shura.core.repository.ExtensionDescriptor
import app.shura.core.repository.RepositorySourceDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentPolicyTest {
    @Test
    fun blocksAdultExtensionMetadataAndExplicitPackageNames() {
        val engine = ContentPolicyEngine()

        val blocked = engine.evaluate(
            extensionName = "Hentai Lovers",
            packageName = "com.example.hentai.lovers",
            description = "adult sexual content with explicit scenes",
            tags = listOf("adult", "nsfw", "ecchi"),
            repositorySource = "community"
        )

        assertTrue(blocked.isBlocked)
        assertEquals(ContentRisk.ADULT, blocked.risk)
        assertTrue(blocked.reasons.any { it.contains("adult", ignoreCase = true) || it.contains("nsfw", ignoreCase = true) })
    }

    @Test
    fun allowsSafeExtensionsWhenNoAdultIndicatorsExist() {
        val engine = ContentPolicyEngine()

        val result = engine.evaluate(
            extensionName = "Daily Manga",
            packageName = "com.example.daily.manga",
            description = "A clean manga reader extension that adds sources and translations.",
            tags = listOf("manga", "reader"),
            repositorySource = "official"
        )

        assertFalse(result.isBlocked)
        assertEquals(ContentRisk.NONE, result.risk)
    }

    @Test
    fun rejectsDisabledOverridesAndRecordsSourceReason() {
        val engine = ContentPolicyEngine()

        val result = engine.evaluate(
            extensionName = "Jav Reader",
            packageName = "com.example.jav.reader",
            description = "uncensored adult sexual scenes and explicit hentai",
            tags = listOf("adult"),
            repositorySource = "custom"
        )

        assertTrue(result.isBlocked)
        assertTrue(result.reasonSource.contains("blocklist", ignoreCase = true) || result.reasonSource.contains("metadata", ignoreCase = true))
    }

    @Test
    fun romanceIsNotBlockedButMatureIndicatorsRemainVisibleAsWarnings() {
        val result = ContentPolicyEngine().evaluate(
            extensionName = "A Summer Romance",
            packageName = "com.example.romance",
            description = "A story about two people falling in love.",
            tags = listOf("romance", "drama"),
            repositorySource = "community",
        )

        assertFalse(result.isBlocked)
        assertEquals(ContentRisk.NONE, result.risk)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun blocksRepositoryAdultFlagAndRetainsItsEvidence() {
        val result = ContentPolicyEngine().evaluate(
            extensionName = "Mystery",
            packageName = "com.example.mystery",
            description = "A mystery story.",
            tags = emptyList(),
            repositorySource = "custom",
            adultFlag = 1,
        )

        assertTrue(result.isBlocked)
        assertTrue(result.reasons.any { it.contains("nsfw flag") })
    }

    @Test
    fun blocksExtensionWhenItsListedSourceHasAnExplicitIndicator() {
        val result = ContentPolicyEngine().evaluate(
            ExtensionDescriptor(
                name = "Source catalog",
                packageName = "org.example.catalog",
                apkPath = "catalog.apk",
                language = "en",
                version = "1.0",
                versionCode = 1,
                repositoryUrl = "https://example.org",
                adultFlag = 0,
                sources = listOf(
                    RepositorySourceDescriptor(
                        id = "1",
                        name = "Hentai Library",
                        language = "en",
                        homeUrl = null,
                    ),
                ),
            ),
        )

        assertTrue(result.isBlocked)
    }

    @Test
    fun descriptorEvaluationUsesRepositoryClassificationAndWarnsOnMissingMetadata() {
        val result = ContentPolicyEngine().evaluate(
            ExtensionDescriptor(
                name = "Unclassified",
                packageName = "org.example.unclassified",
                apkPath = "unclassified.apk",
                language = "en",
                version = "1.0",
                versionCode = 1,
                repositoryUrl = "https://example.org",
                adultFlag = 0,
            ),
        )

        assertFalse(result.isBlocked)
        assertEquals(ContentRisk.UNKNOWN, result.risk)
        assertTrue(result.warnings.any { it.contains("description") })
        assertTrue(result.warnings.any { it.contains("tags") })
    }

    @Test
    fun doesNotBlockMatureKeywordsWithoutExplicitIndicators() {
        val result = ContentPolicyEngine().evaluate(
            extensionName = "Adult Romance",
            packageName = "com.example.romance",
            description = "A romantic story with mature themes.",
            tags = listOf("romance", "adult"),
            repositorySource = "community",
        )

        assertFalse(result.isBlocked)
        assertTrue(result.warnings.isNotEmpty())
    }
}
