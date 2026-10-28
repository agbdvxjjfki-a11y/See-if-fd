package app.shura.android

import app.shura.core.content.ContentPolicyEngine
import app.shura.core.content.ContentPolicyResult
import app.shura.core.repository.ExtensionDescriptor

class ExtensionSafetyGate(
    private val policy: ContentPolicyEngine = ContentPolicyEngine(),
) {
    fun evaluate(extension: ExtensionDescriptor): ContentPolicyResult = policy.evaluate(extension)

    fun mayInstall(extension: ExtensionDescriptor): Boolean =
        !evaluate(extension).isBlocked
}
