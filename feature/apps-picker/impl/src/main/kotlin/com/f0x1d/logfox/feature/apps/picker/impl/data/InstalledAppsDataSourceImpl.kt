package com.f0x1d.logfox.feature.apps.picker.impl.data

import android.content.Context
import android.content.pm.ApplicationInfo
import com.f0x1d.logfox.feature.apps.picker.api.InstalledApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal class InstalledAppsDataSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstalledAppsDataSource {

    override fun getInstalledApps(): List<InstalledApp> = context.packageManager
        .getInstalledPackages(0)
        .map { packageInfo ->
            val appInfo = packageInfo.applicationInfo
            val isSystemApp = appInfo?.let {
                (it.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
                    (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
            } ?: false

            InstalledApp(
                title = appInfo?.loadLabel(context.packageManager).toString(),
                packageName = packageInfo.packageName,
                isSystemApp = isSystemApp,
            )
        }
        .sortedBy(InstalledApp::title)
}
