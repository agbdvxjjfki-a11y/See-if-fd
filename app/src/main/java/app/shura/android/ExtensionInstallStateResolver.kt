package app.shura.android

import android.content.pm.PackageInstaller
import app.shura.core.repository.ExtensionDescriptor

internal fun extensionOperationForInstalledPackage(
    extension: ExtensionDescriptor,
    installed: InstalledExtensionPackage?,
): ExtensionOperationState {
    val phase = when {
        installed == null -> ExtensionOperationPhase.NOT_INSTALLED
        installed.packageName != extension.packageName -> ExtensionOperationPhase.INCOMPATIBLE
        !installed.isMihonExtension -> ExtensionOperationPhase.INCOMPATIBLE
        extension.versionCode > installed.versionCode -> ExtensionOperationPhase.UPDATE_AVAILABLE
        else -> ExtensionOperationPhase.INSTALLED
    }
    return ExtensionOperationState(phase)
}

internal fun extensionInstallOutcome(
    status: Int,
    expectedPackage: String,
    expectedVersionCode: Long,
    installed: InstalledExtensionPackage?,
    systemMessage: String?,
): ExtensionOperationState {
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
        return ExtensionOperationState(ExtensionOperationPhase.AWAITING_ANDROID)
    }
    if (status == PackageInstaller.STATUS_SUCCESS &&
        installed?.packageName == expectedPackage &&
        installed.isMihonExtension &&
        installed.versionCode >= expectedVersionCode
    ) {
        return ExtensionOperationState(ExtensionOperationPhase.INSTALLED)
    }
    if (installed?.packageName == expectedPackage && !installed.isMihonExtension) {
        return ExtensionOperationState(
            ExtensionOperationPhase.INCOMPATIBLE,
            message = "الحزمة المثبتة ليست إضافة Mihon معروفة",
        )
    }
    val message = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "رفض Android التثبيت بسبب تعارض التوقيع أو الحزمة"
        PackageInstaller.STATUS_FAILURE_INVALID -> "رفض Android ملف APK باعتباره غير صالح"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "أُلغي التثبيت أو رُفضت موافقة Android"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "هذا الإصدار غير متوافق مع جهاز Android"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "لا توجد مساحة كافية لإكمال التثبيت"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "منع Android تثبيت هذه الحزمة"
        PackageInstaller.STATUS_SUCCESS -> "لم يؤكد Android تثبيت الحزمة المطلوبة وإصدارها"
        else -> systemMessage?.takeIf(String::isNotBlank) ?: "فشل التثبيت بواسطة Android"
    }
    return ExtensionOperationState(ExtensionOperationPhase.FAILED, message = message)
}
