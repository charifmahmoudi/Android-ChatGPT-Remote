package com.charifmahmoudi.chatgptremote

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestSecurityInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun applicationDisablesBackupAndCleartextTraffic() {
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertFalse(applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
        assertFalse(applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)
    }

    @Test
    fun foregroundServiceIsPrivateAndUsesSpecialUseType() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS,
        )
        val service = packageInfo.services.orEmpty().singleOrNull {
            it.name == TunnelService::class.java.name
        }

        assertNotNull(service)
        assertFalse(service!!.exported)
        assertTrue(
            service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0,
        )
        assertTrue(
            packageInfo.requestedPermissions.orEmpty().toSet().containsAll(
                setOf(
                    "android.permission.INTERNET",
                    "android.permission.FOREGROUND_SERVICE",
                    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                    "android.permission.POST_NOTIFICATIONS",
                ),
            ),
        )
    }
}
