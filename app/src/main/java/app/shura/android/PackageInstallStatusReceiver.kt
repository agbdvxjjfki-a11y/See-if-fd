package app.shura.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow

object PackageInstallEvents {
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
}

class PackageInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(AndroidExtensionPackages.EXTRA_INSTALL_PACKAGE)
        if (packageName.isNullOrBlank()) {
            Log.e(TAG, "PackageInstaller callback omitted the expected package ID")
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val preferences = context.getSharedPreferences(
            AndroidExtensionPackages.INSTALL_RESULTS_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        preferences.edit()
            .putString(AndroidExtensionPackages.EXTRA_INSTALL_RESULT_PACKAGE, packageName)
            .putLong(
                AndroidExtensionPackages.EXTRA_INSTALL_RESULT_VERSION,
                intent.getLongExtra(AndroidExtensionPackages.EXTRA_INSTALL_VERSION, -1L),
            )
            .putInt(AndroidExtensionPackages.EXTRA_INSTALL_RESULT_STATUS, status)
            .putString(
                AndroidExtensionPackages.EXTRA_INSTALL_RESULT_MESSAGE,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            )
            .apply()
        PackageInstallEvents.events.tryEmit(packageName)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirmation == null) {
                Log.e(TAG, "PackageInstaller requested user action without a confirmation intent")
                return
            }
            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(confirmation)
        }
    }

    private companion object {
        const val TAG = "ShuraPackageInstall"
    }
}
