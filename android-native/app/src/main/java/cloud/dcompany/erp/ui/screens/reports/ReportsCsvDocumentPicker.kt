package cloud.dcompany.erp.ui.screens.reports

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.theme.WorkspacePresentationGold
import cloud.dcompany.erp.ui.theme.WorkspacePresentationOnGold

private class CsvCreateDocument : ActivityResultContracts.CreateDocument("text/csv") {
    // Export writes a byte stream, so exclude virtual-only document destinations.
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
}

/** Only a nonce survives recreation; authority and downloaded bytes stay in the live ViewModel. */
@Composable
internal fun rememberReportsCsvDocumentPicker(
    onResult: (String, Uri?) -> Unit,
): (ReportsOverviewExportRequest) -> Unit {
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(CsvCreateDocument()) { uri ->
        val id = pendingId
        pendingId = null
        if (id != null) onResult(id, uri)
    }
    return { request ->
        check(pendingId == null) { "A document picker is already open." }
        pendingId = request.id
        try {
            launcher.launch(request.filename)
        } catch (error: Exception) {
            pendingId = null
            throw error
        }
    }
}

@Composable
internal fun ReportsOverviewExportButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    ErpButton(
        text = "Export",
        onClick = onClick,
        enabled = enabled,
        busy = busy,
        leadingIcon = Icons.Filled.FileDownload,
        containerColor = WorkspacePresentationGold,
        contentColor = WorkspacePresentationOnGold,
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
            .testTag("reports-overview-export")
            .semantics { contentDescription = "Export Overview CSV" },
    )
}

internal tailrec fun Context.reportsActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.reportsActivity()
    else -> null
}
