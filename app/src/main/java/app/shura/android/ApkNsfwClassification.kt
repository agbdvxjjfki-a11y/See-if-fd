package app.shura.android

enum class ApkNsfwClassification {
    SAFE,
    NSFW,
    MISSING,
    INVALID,
}

internal fun classifyApkNsfwMetadata(present: Boolean, value: Any?): ApkNsfwClassification {
    if (!present) return ApkNsfwClassification.MISSING
    return when (value) {
        0 -> ApkNsfwClassification.SAFE
        1 -> ApkNsfwClassification.NSFW
        else -> ApkNsfwClassification.INVALID
    }
}
