package app.shura.android

import kotlin.test.Test
import kotlin.test.assertEquals

class ApkNsfwClassificationTest {
    @Test
    fun distinguishesAbsentMetadataFromInvalidOrExplicitValues() {
        assertEquals(
            ApkNsfwClassification.MISSING,
            classifyApkNsfwMetadata(present = false, value = null),
        )
        assertEquals(
            ApkNsfwClassification.INVALID,
            classifyApkNsfwMetadata(present = true, value = null),
        )
        assertEquals(
            ApkNsfwClassification.SAFE,
            classifyApkNsfwMetadata(present = true, value = 0),
        )
        assertEquals(
            ApkNsfwClassification.NSFW,
            classifyApkNsfwMetadata(present = true, value = 1),
        )
        listOf(-1, 2, "0", true, 0L).forEach { value ->
            assertEquals(
                ApkNsfwClassification.INVALID,
                classifyApkNsfwMetadata(present = true, value = value),
            )
        }
    }
}
