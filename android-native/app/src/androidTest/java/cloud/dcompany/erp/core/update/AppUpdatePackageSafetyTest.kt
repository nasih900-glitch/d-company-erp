package cloud.dcompany.erp.core.update

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.net.ClientUpdateNotice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdatePackageSafetyTest {
    @Test
    fun sharedDebugBuildHasNoDirectInstallerPermissionOrProvider() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )

        assertFalse(BuildConfig.DIRECT_UPDATES_ENABLED)
        assertFalse(info.requestedPermissions.orEmpty().contains(Manifest.permission.REQUEST_INSTALL_PACKAGES))
        assertNull(
            context.packageManager.resolveContentProvider(
                "${context.packageName}.updates",
                PackageManager.MATCH_DISABLED_COMPONENTS,
            ),
        )
    }

    @Test
    fun installedArchiveIsReadableButRejectedAsNotNewer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val installedVersion = if (android.os.Build.VERSION.SDK_INT >= 28) {
            installed.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            installed.versionCode.toLong()
        }
        val sourceApk = File(context.applicationInfo.sourceDir)
        assertTrue(sourceApk.isFile)

        val result = AndroidApkVerifier(context).verify(
            sourceApk,
            DirectUpdateDescriptor(
                url = "https://updates.example.test/current.apk",
                versionCode = installedVersion.toInt(),
                versionName = installed.versionName.orEmpty(),
                sha256 = "00".repeat(32),
                sizeBytes = sourceApk.length(),
                expectedCurrentSignerSha256 = null,
            ),
        )

        assertEquals(
            ArchiveVerificationResult.Rejected(ArchiveVerificationProblem.NotNewer),
            result,
        )
    }
}
