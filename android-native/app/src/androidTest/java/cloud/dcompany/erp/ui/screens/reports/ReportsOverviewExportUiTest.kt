package cloud.dcompany.erp.ui.screens.reports

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeMarker
import cloud.dcompany.erp.core.auth.ScopeDataPurger
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-process controlled provider: no exported component, storage permission or real user file. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ReportsOverviewExportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun createDocumentUsesCsvFilenameAndReturnsSingleNonceAfterStateRestoration(): Unit = runBlocking {
        val request = request()
        val registry = DocumentRegistry(compose.activity)
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        val results = mutableListOf<Pair<String, Uri?>>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val launch = rememberReportsCsvDocumentPicker { id, uri -> results += id to uri }
                MaterialTheme { ReportsOverviewExportButton(true, false) { launch(request) } }
            }
        }
        compose.onNodeWithText("Export").assertIsDisplayed()
        compose.onNodeWithTag("reports-overview-export")
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(1, registry.launches)
        assertTrue(results.isEmpty())
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, registry.intent!!.action)
        assertEquals("text/csv", registry.intent!!.type)
        assertEquals(request.filename, registry.intent!!.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(registry.intent!!.hasCategory(Intent.CATEGORY_OPENABLE))
        restoration.emulateSavedInstanceStateRestore()
        val destination = Uri.parse("content://controlled-reports/saved.csv")
        compose.runOnIdle { registry.complete(Activity.RESULT_OK, destination) }
        assertEquals(listOf(request.id to destination), results)
        // Duplicate delivery has no pending nonce and cannot trigger another save.
        compose.runOnIdle { registry.complete(Activity.RESULT_OK, destination) }
        assertEquals(1, results.size)
    }

    @Test
    fun cancelledPickerReturnsNoDestination(): Unit = runBlocking {
        val request = request()
        val registry = DocumentRegistry(compose.activity)
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        val results = mutableListOf<Pair<String, Uri?>>()
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val launch = rememberReportsCsvDocumentPicker { id, uri -> results += id to uri }
                MaterialTheme { ReportsOverviewExportButton(true, false) { launch(request) } }
            }
        }
        compose.onNodeWithTag("reports-overview-export").performClick()
        compose.runOnIdle { registry.complete(Activity.RESULT_CANCELED, null) }
        assertEquals(listOf(request.id to null), results)
    }

    @Test
    fun disabledAndBusyExportControlsKeepMinimumTargetWithoutInvokingAction() {
        var calls = 0
        compose.setContent {
            MaterialTheme { ReportsOverviewExportButton(true, true) { calls++ } }
        }
        compose.onNodeWithText("Export…").assertIsDisplayed()
        compose.onNodeWithTag("reports-overview-export")
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsNotEnabled()
        assertEquals(0, calls)
    }

    @Test
    fun controlledProviderReceivesExactBytesOnlyAfterFetchAndAuthorization(): Unit = runBlocking {
        val file = File.createTempFile("reports-csv-", ".csv", compose.activity.cacheDir)
        try {
            val provider = CsvProvider(file)
            provider.attachInfo(compose.activity, ProviderInfo().apply { authority = "controlled-reports" })
            val resolver = ContentResolver.wrap(provider)
            val bytes = "'café,\"quotes\"\r\n'=SUM(1,2),0.125,-1250\r\n".toByteArray()
            var fetched = false
            var authorized = false
            provider.beforeOpen = { assertTrue(fetched) }
            val result = exportReportsOverviewCsv(
                isCurrent = { true }, fetch = { fetched = true; bytes },
                authorizeWrite = { authorized = true; true },
                openOutput = { resolver.openOutputStream(Uri.parse("content://controlled-reports/report.csv"), "wt") },
            )
            assertEquals(ReportsCsvExportResult.Saved, result)
            assertTrue(authorized)
            assertEquals(1, provider.opens)
            assertArrayEquals(bytes, file.readBytes())
            assertFalse(provider.descriptor!!.fileDescriptor.valid())
        } finally {
            file.delete()
        }
    }

    @Test
    fun providerOpenRevocationAndProviderFailureCannotSaveOrReportSuccess(): Unit = runBlocking {
        val file = File.createTempFile("reports-csv-", ".csv", compose.activity.cacheDir)
        try {
            val provider = CsvProvider(file)
            provider.attachInfo(compose.activity, ProviderInfo().apply { authority = "controlled-reports" })
            val resolver = ContentResolver.wrap(provider)
            var current = true
            provider.beforeOpen = { current = false }
            val result = exportReportsOverviewCsv(
                isCurrent = { current }, fetch = { byteArrayOf(1, 2, 3) },
                authorizeWrite = { fail("Stale provider open must not authorize writing"); false },
                openOutput = { resolver.openOutputStream(Uri.parse("content://controlled-reports/report.csv"), "wt") },
            )
            assertEquals(ReportsCsvExportResult.Stale, result)
            assertEquals(0L, file.length())
            current = true
            provider.beforeOpen = { throw FileNotFoundException("Controlled provider refused this destination") }
            val failed = exportReportsOverviewCsv(
                isCurrent = { current }, fetch = { byteArrayOf(1, 2, 3) }, authorizeWrite = { true },
                openOutput = { resolver.openOutputStream(Uri.parse("content://controlled-reports/report.csv"), "wt") },
            )
            assertTrue(failed is ReportsCsvExportResult.Failed)
            assertEquals(0L, file.length())
        } finally {
            file.delete()
        }
    }

    private class DocumentRegistry(private val context: Context) : ActivityResultRegistry() {
        var launches = 0
        var intent: Intent? = null
        private var requestCode = 0
        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
            launches++
            intent = contract.createIntent(context, input)
        }
        fun complete(result: Int, uri: Uri?) {
            dispatchResult(requestCode, result, uri?.let { Intent().setData(it) })
        }
    }

    private class CsvProvider(private val file: File) : ContentProvider() {
        var opens = 0
        var beforeOpen: () -> Unit = {}
        var descriptor: ParcelFileDescriptor? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/csv"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            assertEquals("wt", mode)
            opens++
            beforeOpen()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
                .also { descriptor = it }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private suspend fun request(): ReportsOverviewExportRequest {
        val scope = CacheScope("operator", "synthetic-company", "00000000-0000-0000-0000-000000000003", "till")
        val coordinator = CacheIsolationCoordinator(
            object : ScopeDataPurger {
                override suspend fun hasUnresolvedWork() = false
                override suspend fun purgeIfClean() = true
            },
            object : CacheScopeMarker {
                override fun current() = scope
                override fun remember(scope: CacheScope) = true
                override fun clear() = true
            },
        )
        coordinator.activateValidated(scope)
        return ReportsOverviewExportRequest(
            "synthetic-live-nonce", requireNotNull(coordinator.currentLease()),
            ReportSelection(ReportPeriod.MONTHLY, LocalDate.of(2026, 10, 27), YearMonth.of(2026, 10), "2026-27", 3),
            "2026-10-01", "2026-10-31", "Asia/Dubai", 1L,
        )
    }
}
