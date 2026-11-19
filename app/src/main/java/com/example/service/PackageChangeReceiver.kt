package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.example.data.registry.PlayerRegistryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Dynamically registered receiver observing package installs, removals, updates, and
 * enable/disable state changes to trigger incremental registry updates.
 */
class PackageChangeReceiver(
    private val repository: PlayerRegistryRepository,
    private val scope: CoroutineScope,
    private val onRegistryUpdated: (() -> Unit)? = null
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val pkgName = intent.data?.schemeSpecificPart ?: return

        val isRemoved = action == Intent.ACTION_PACKAGE_REMOVED &&
            !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        scope.launch(Dispatchers.IO) {
            repository.onPackageLifecycleEvent(pkgName, isRemoved = isRemoved)
            onRegistryUpdated?.invoke()
        }
    }

    companion object {
        fun createIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
        }
    }
}
