package app.shura.core.content

import app.shura.core.repository.ExtensionDescriptor

enum class ContentRisk {
    NONE,
    UNKNOWN,
    MATURE,
    ADULT,
}

data class ContentPolicyResult(
    val isBlocked: Boolean,
    val risk: ContentRisk,
    val reasons: List<String>,
    val reasonSource: String,
    val warnings: List<String> = emptyList(),
)

class ContentPolicyEngine {
    fun evaluate(
        extension: ExtensionDescriptor,
        description: String? = null,
        tags: List<String> = emptyList(),
    ): ContentPolicyResult = evaluate(
        extensionName = extension.name,
        packageName = extension.packageName,
        description = description.orEmpty(),
        tags = tags + extension.sources.flatMap { source ->
            listOfNotNull(source.name.takeIf(String::isNotBlank), source.message?.takeIf(String::isNotBlank))
        },
        repositorySource = extension.repositoryUrl,
        adultFlag = extension.adultFlag,
    )

    fun evaluate(
        extensionName: String,
        packageName: String,
        description: String,
        tags: List<String>,
        repositorySource: String,
        adultFlag: Int = 0,
    ): ContentPolicyResult {
        val metadata = listOf(
            "name" to extensionName,
            "package name" to packageName,
            "description" to description,
        ) + tags.map { "tag" to it }

        val indicators = metadata.flatMap { (field, value) ->
            indicators(value).map { (indicator, explicit) ->
                "$field contains ${if (explicit) "explicit" else "adult-content"} indicator '$indicator'" to explicit
            }
        }
        val flagEvidence = when {
            adultFlag > 0 -> "repository nsfw flag is set"
            adultFlag < 0 -> "repository nsfw flag is invalid"
            else -> null
        }
        val explicitEvidence = indicators.filter { it.second }.map { it.first } +
            listOfNotNull(flagEvidence?.takeIf { adultFlag > 0 })
        val warningEvidence = indicators.filterNot { it.second }.map { it.first } +
            listOfNotNull(flagEvidence?.takeIf { adultFlag < 0 })
        val unknownEvidence = buildList {
            if (description.isBlank()) add("extension description is unavailable")
            if (tags.isEmpty()) add("extension content tags are unavailable")
        }

        val blocked = explicitEvidence.isNotEmpty()
        return ContentPolicyResult(
            isBlocked = blocked,
            risk = when {
                blocked -> ContentRisk.ADULT
                warningEvidence.isNotEmpty() -> ContentRisk.MATURE
                unknownEvidence.isNotEmpty() -> ContentRisk.UNKNOWN
                else -> ContentRisk.NONE
            },
            reasons = (explicitEvidence + warningEvidence + unknownEvidence).distinct(),
            reasonSource = if (blocked) "metadata and repository classification" else "no explicit indicators ($repositorySource repository)",
            warnings = (warningEvidence + unknownEvidence).distinct(),
        )
    }

    private fun indicators(value: String): List<Pair<String, Boolean>> {
        val normalized = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        if (normalized.isEmpty()) return emptyList()

        return INDICATORS.filter { indicator ->
            Regex("""(?:^|\s)${Regex.escape(indicator.first)}(?:$|\s)""").containsMatchIn(normalized)
        }
    }

    private companion object {
        val INDICATORS = listOf(
            "adult" to false,
            "ecchi" to false,
            "sex" to false,
            "sexual" to false,
            "nsfw" to false,
            "explicit" to true,
            "hentai" to true,
            "jav" to true,
            "porn" to true,
            "pornography" to true,
            "uncensored" to true,
            "xxx" to true,
        )
    }
}
