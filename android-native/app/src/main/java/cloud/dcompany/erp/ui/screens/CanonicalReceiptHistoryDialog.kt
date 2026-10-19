package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.ui.theme.operationalDanger
import cloud.dcompany.erp.ui.theme.operationalDangerContainer
import cloud.dcompany.erp.ui.theme.operationalGood
import cloud.dcompany.erp.ui.theme.operationalGoodContainer
import cloud.dcompany.erp.ui.theme.operationalInformation
import cloud.dcompany.erp.ui.theme.operationalInformationContainer
import cloud.dcompany.erp.ui.theme.operationalWarning
import cloud.dcompany.erp.ui.theme.operationalWarningContainer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.ui.screens.audit.AuditLogScreen
import cloud.dcompany.erp.ui.screens.audit.AuditLogViewModel
import cloud.dcompany.erp.ui.screens.finance.TenderContext
import cloud.dcompany.erp.ui.screens.finance.TenderWorkspace
import cloud.dcompany.erp.ui.screens.finance.tenderScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cloud.dcompany.erp.core.net.CanonicalReceipt
import cloud.dcompany.erp.core.net.CanonicalReceiptGamingSession
import cloud.dcompany.erp.core.net.CanonicalReceiptLine
import cloud.dcompany.erp.core.net.CanonicalReceiptPayment
import cloud.dcompany.erp.core.net.CanonicalReceiptRefund
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import java.math.BigDecimal
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun CanonicalReceiptHistoryDialog(
    receipts: List<CanonicalReceipt>,
    hasMore: Boolean,
    loading: Boolean,
    error: String?,
    hasLocalReceipt: Boolean,
    onOpenLastLocalReceipt: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenReceipt: (String) -> Unit,
    search: ReceiptSearchUiState,
    onSearch: (String) -> Unit,
    onLoadMoreSearch: () -> Unit,
    onRefundReceipt: ((CanonicalReceipt) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var selectedOrderId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchDraft by rememberSaveable { mutableStateOf(search.query) }
    val displayedReceipts = if (search.query.isBlank()) receipts else search.receipts
    val selected = selectedOrderId?.let { id ->
        search.detail?.takeIf { search.query.isNotBlank() && it.orderId == id }
            ?: displayedReceipts.firstOrNull { it.orderId == id }
            ?: receipts.firstOrNull { it.orderId == id }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = Radius.shapeXl,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f)
                .widthIn(max = 980.dp)
                .heightIn(min = 420.dp, max = 760.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, Radius.shapeXl),
        ) {
            if (selected == null) {
                ReceiptHistoryList(
                    receipts = displayedReceipts,
                    hasMore = if (search.query.isBlank()) hasMore else search.hasMore,
                    loading = if (search.query.isBlank()) loading else search.loading,
                    error = if (search.query.isBlank()) error else search.error,
                    searchDraft = searchDraft,
                    search = search,
                    onSearchDraftChange = { searchDraft = it.take(200) },
                    onSearch = { onSearch(searchDraft) },
                    onClearSearch = { searchDraft = ""; onSearch("") },
                    hasLocalReceipt = hasLocalReceipt,
                    onOpenLastLocalReceipt = onOpenLastLocalReceipt,
                    onSelect = {
                        selectedOrderId = it.orderId
                        onOpenReceipt(it.orderId)
                    },
                    onRefresh = if (search.query.isBlank()) {
                        onRefresh
                    } else {
                        { onSearch(search.query) }
                    },
                    onLoadMore = if (search.query.isBlank()) onLoadMore else onLoadMoreSearch,
                    onDismiss = onDismiss,
                )
            } else {
                CanonicalReceiptDetail(
                    receipt = selected,
                    loading = if (search.query.isBlank()) loading else search.detailLoading,
                    error = if (search.query.isBlank()) error else search.detailError,
                    onRefresh = { onOpenReceipt(selected.orderId) },
                    onBack = { selectedOrderId = null },
                    onDismiss = onDismiss,
                    onRefundReceipt = onRefundReceipt,
                )
            }
        }
    }
}

@Composable
private fun ReceiptHistoryList(
    receipts: List<CanonicalReceipt>,
    hasMore: Boolean,
    loading: Boolean,
    error: String?,
    searchDraft: String,
    search: ReceiptSearchUiState,
    onSearchDraftChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    hasLocalReceipt: Boolean,
    onOpenLastLocalReceipt: () -> Unit,
    onSelect: (CanonicalReceipt) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column {
        ReceiptDialogHeader(
            title = "Receipts",
            subtitle = if (receipts.isEmpty()) {
                "Shared history from this shop"
            } else {
                "${receipts.size} most recent loaded · newest first"
            },
            onBack = null,
            onRefresh = onRefresh,
            refreshing = loading,
            onDismiss = onDismiss,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchDraft,
                onValueChange = onSearchDraftChange,
                label = { Text("Invoice, suffix, order or session ID") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            ErpButton(
                text = if (search.loading) "Searching…" else "Search",
                onClick = onSearch,
                enabled = searchDraft.isNotBlank() && !search.loading,
                leadingIcon = Icons.Default.Search,
                intent = ActionIntent.Secondary,
            )
            if (search.query.isNotBlank()) {
                ErpButton("Clear", onClearSearch, intent = ActionIntent.Quiet)
            }
        }
        if (search.query.isNotBlank()) {
            Text(
                when (search.source) {
                    ReceiptSearchSource.ONLINE ->
                        "Online search across retained history · exact identifiers · ${receipts.size} loaded"
                    ReceiptSearchSource.CACHED -> {
                        val cached = search.cachedAtMillis?.let {
                            java.time.Instant.ofEpochMilli(it).toString().receiptDateTime()
                        } ?: "time unavailable"
                        "Offline cached lookup · bounded to receipts already on this tablet · cached $cached · not full history"
                    }
                    ReceiptSearchSource.NONE -> "Searching retained receipt history…"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            )
        }

        if (receipts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    modifier = Modifier.padding(Spacing.xl),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ReceiptLong,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        if (search.query.isBlank()) "No shared receipts loaded" else "No matching receipt",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        error ?: "Completed receipts will appear here from web or Android.",
                        color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.operationalWarning,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (hasLocalReceipt) {
                            ErpButton(
                                "Last tablet receipt",
                                onOpenLastLocalReceipt,
                                intent = ActionIntent.Secondary,
                            )
                        }
                        ErpButton("Try again", onRefresh, intent = ActionIntent.Primary)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (hasLocalReceipt) {
                    item(key = "receipt-history-local-evidence") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            ErpButton(
                                "Last tablet receipt",
                                onOpenLastLocalReceipt,
                                intent = ActionIntent.Quiet,
                            )
                        }
                    }
                }
                error?.let { message ->
                    item(key = "receipt-history-error") {
                        ReceiptHistoryNotice(message)
                    }
                }
                items(receipts, key = CanonicalReceipt::orderId) { receipt ->
                    ReceiptHistoryRow(receipt = receipt, onClick = { onSelect(receipt) })
                }
                if (hasMore) {
                    item(key = "receipt-history-more") {
                        Box(
                            Modifier.fillMaxWidth().padding(Spacing.md),
                            contentAlignment = Alignment.Center,
                        ) {
                            ErpButton(
                                if (loading) "Loading…" else "Load 50 more",
                                onLoadMore,
                                enabled = !loading,
                                intent = ActionIntent.Secondary,
                            )
                        }
                    }
                }
                item(key = "receipt-history-scope") {
                    Text(
                        "History is scoped to this company and shop. Older pages load only when requested.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiptHistoryNotice(message: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .background(MaterialTheme.colorScheme.operationalWarningContainer, Radius.shapeMd)
            .border(1.dp, MaterialTheme.colorScheme.operationalWarning.copy(alpha = 0.45f), Radius.shapeMd)
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.operationalWarning)
        Text(message, color = MaterialTheme.colorScheme.operationalWarning, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReceiptHistoryRow(receipt: CanonicalReceipt, onClick: () -> Unit) {
    val source = receipt.gamingSessions.firstOrNull()?.stationName
        ?: receipt.orderType.replace('_', ' ').replaceFirstChar(Char::uppercase)
    val paymentSummary = receiptPaymentSummary(receipt.payments)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = Radius.shapeLg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .clickable(onClick = onClick)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.shapeLg),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (receipt.gamingSessions.isEmpty()) Icons.AutoMirrored.Filled.ReceiptLong
                else Icons.Default.SportsEsports,
                contentDescription = null,
                tint = if (receipt.gamingSessions.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    receipt.invoiceNo,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$source · ${receipt.invoiceIssuedAt.receiptDateTime()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                paymentSummary?.let { summary ->
                    Text(
                        summary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    receipt.totalMinor.asRupees(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    receipt.status.uppercase(),
                    color = if (receipt.status == "refunded") MaterialTheme.colorScheme.operationalWarning else MaterialTheme.colorScheme.operationalGood,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

internal fun receiptPaymentSummary(payments: List<CanonicalReceiptPayment>): String? {
    if (payments.isEmpty()) return null
    val methods = payments.map { it.method.uppercase() }.distinct().joinToString(" + ")
    val actors = payments
        .mapNotNull { it.recordedByName?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
    val actorLabel = when (actors.size) {
        0 -> "payment actor unavailable"
        1 -> "recorded by ${actors.single()}"
        else -> "recorded by ${actors.joinToString(", ")}"
    }
    return "$methods · $actorLabel"
}

@Composable
private fun CanonicalReceiptDetail(
    receipt: CanonicalReceipt,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onRefundReceipt: ((CanonicalReceipt) -> Unit)?,
) {
    val clipboard = LocalClipboardManager.current
    val account by DCompanyApp.instance.shiftCache.profile.collectAsStateWithLifecycle()
    val ownerKey = account?.let { "${it.companyId}|${it.userId}|${it.branchId.orEmpty()}" }.orEmpty()
    val canCorrect = account?.tenderScope(receipt.branchId) != null
    var correctedPayment by remember(receipt.orderId, ownerKey) { mutableStateOf<String?>(null) }
    var investigate by remember(receipt.orderId, ownerKey) { mutableStateOf(false) }
    var linkedAudit by remember(receipt.orderId, ownerKey) { mutableStateOf<String?>(null) }
    Column {
        ReceiptDialogHeader(
            title = receipt.invoiceNo,
            subtitle = "Official server receipt · ${receipt.invoiceIssuedAt.receiptDateTime()}",
            onBack = onBack,
            onRefresh = onRefresh,
            refreshing = loading,
            onDismiss = onDismiss,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            error?.let { message ->
                item(key = "receipt-detail-error") { ReceiptHistoryNotice(message) }
            }
            item {
                ReceiptDetailSection("Sale") {
                    ReceiptValueRow("Status", receipt.status.uppercase())
                    ReceiptValueRow("Order type", receipt.orderType.replace('_', ' '))
                    ReceiptValueRow("Order ID", receipt.orderId)
                    ReceiptValueRow("Order opened by", receipt.openedByName ?: "Staff record unavailable")
                    ReceiptValueRow("Shift opened by", receipt.shiftOpenedByName ?: "Staff record unavailable")
                    receipt.customerName?.let { ReceiptValueRow("Customer", it) }
                    receipt.customerPhone?.let { ReceiptValueRow("Phone", it) }
                }
            }
            item {
                ReceiptDetailSection("Copy references") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ErpButton(
                            "Copy invoice",
                            { clipboard.setText(AnnotatedString(receipt.invoiceNo)) },
                            leadingIcon = Icons.Default.ContentCopy,
                            intent = ActionIntent.Secondary,
                        )
                        ErpButton(
                            "Copy order ID",
                            { clipboard.setText(AnnotatedString(receipt.orderId)) },
                            intent = ActionIntent.Quiet,
                        )
                    }
                    receipt.gamingSessions.forEach { session ->
                        ErpButton(
                            "Copy session ${session.stationCode}",
                            { clipboard.setText(AnnotatedString(session.id)) },
                            intent = ActionIntent.Quiet,
                        )
                    }
                }
            }
            if (onRefundReceipt != null && receipt.refundableMinor > 0L && receipt.pendingRefundMinor == 0L) {
                item {
                    ErpButton(
                        text = "Open refund for this exact receipt",
                        onClick = { onRefundReceipt(receipt) },
                        intent = ActionIntent.Warning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                ReceiptDetailSection("Items") {
                    receipt.lines.forEachIndexed { index, line ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ReceiptLineRow(line)
                    }
                }
            }
            if (receipt.gamingSessions.isNotEmpty()) {
                item {
                    ReceiptDetailSection("Gaming provenance") {
                        receipt.gamingSessions.forEachIndexed { index, session ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ReceiptGamingRow(session)
                        }
                    }
                }
            }
            item {
                ReceiptDetailSection("Payments") {
                    if (receipt.payments.isEmpty()) {
                        Text("No payment rows recorded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        receipt.payments.forEachIndexed { index, payment ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ReceiptPaymentRow(payment)
                            if (canCorrect && (payment.method == "cash" || payment.tenderCorrectionId != null)) {
                                TextButton({ correctedPayment = if (correctedPayment == payment.id) null else payment.id }) {
                                    Text(if (correctedPayment == payment.id) "Hide payment evidence" else if (payment.tenderCorrectionId != null) "View protected correction evidence" else "Correct this Cash payment to UPI")
                                }
                            }
                        }
                    }
                }
            }
            correctedPayment?.let { paymentId -> receipt.payments.firstOrNull { it.id == paymentId }?.let { payment ->
                item(key = "protected-payment-workspace") {
                    TenderWorkspace(TenderContext.Payment(receipt.branchId, payment.id, receipt.invoiceNo),
                        onCommitted = { onRefresh() }, allowNewPreparation = payment.method == "cash" && payment.tenderCorrectionId == null)
                }
            } }
            item(key = "consolidated-investigation") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton({ investigate = !investigate }) { Text(if (investigate) "Hide consolidated bill investigation" else "Investigate players, drinks, payments and related activity") }
                    if (investigate) ReceiptInvestigationPanel(receipt.orderId, ownerKey, onAuditReference =
                        if (account?.auditAccess == true) ({ reference -> linkedAudit = reference }) else null)
                    linkedAudit?.let { reference ->
                        TextButton({ linkedAudit = null }) { Text("Close linked protected Audit") }
                        Box(Modifier.fillMaxWidth().height(640.dp)) {
                            AuditLogScreen(vm = viewModel<AuditLogViewModel>(key = "receipt-linked-audit"), initialReference = reference)
                        }
                    }
                }
            }
            if (receipt.refunds.isNotEmpty()) {
                item {
                    ReceiptDetailSection("Refunds") {
                        receipt.refunds.forEachIndexed { index, refund ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ReceiptRefundRow(refund)
                        }
                    }
                }
            }
            item {
                ReceiptDetailSection("Totals") {
                    ReceiptMoneyRow("Subtotal", receipt.subtotalMinor)
                    if (receipt.discountMinor != 0L) ReceiptMoneyRow("Discount", -receipt.discountMinor)
                    if (receipt.manualDiscountMinor != 0L) {
                        ReceiptMoneyRow("Includes manual discount", receipt.manualDiscountMinor)
                    }
                    if (receipt.pointsRedeemedMinor != 0L) {
                        ReceiptMoneyRow("Points redeemed", -receipt.pointsRedeemedMinor)
                    }
                    ReceiptMoneyRow("Tax actually charged", receipt.taxMinor)
                    if (receipt.tipMinor != 0L) ReceiptMoneyRow("Tip", receipt.tipMinor)
                    if (receipt.roundOffMinor != 0L) ReceiptMoneyRow("Round-off", receipt.roundOffMinor)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    ReceiptMoneyRow("Total", receipt.totalMinor, emphasized = true)
                    ReceiptMoneyRow("Paid", receipt.paidMinor)
                    if (receipt.refundedMinor != 0L) {
                        ReceiptMoneyRow("Refunded", -receipt.refundedMinor)
                    }
                    if (receipt.pendingRefundMinor != 0L) {
                        ReceiptMoneyRow("Refund reserved", -receipt.pendingRefundMinor)
                    }
                    ReceiptMoneyRow("Available to refund", receipt.refundableMinor)
                    ReceiptMoneyRow("Net collected", receipt.netCollectedMinor, emphasized = true)
                }
            }
            item {
                Text(
                    "Order ${receipt.orderId.take(8)} · Terminal ${receipt.terminalId.take(8)} · FY ${receipt.fiscalYear}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ReceiptDialogHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    refreshing: Boolean,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onBack?.let {
            IconButton(onClick = it, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to receipts")
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        onRefresh?.let {
            IconButton(onClick = it, enabled = !refreshing, modifier = Modifier.size(48.dp)) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh receipts")
                }
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close receipts")
        }
    }
}

@Composable
private fun ReceiptDetailSection(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = Radius.shapeLg,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.shapeLg),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ReceiptLineRow(line: CanonicalReceiptLine) {
    val variantName = line.variantSnapshot?.get("name")?.jsonPrimitive?.contentOrNull
    val modifierLabels = remember(line.modifiers) {
        line.modifiers.mapNotNull { modifier ->
            modifier["name"]?.jsonPrimitive?.contentOrNull?.let { name ->
                val qty = modifier["qty"]?.jsonPrimitive?.contentOrNull
                if (qty == null || qty == "1") name else "$name × $qty"
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${line.qty.receiptQuantity()} × ${line.menuItemName}",
                color = if (line.voidedAt == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            listOfNotNull(
                variantName,
                modifierLabels.takeIf(List<String>::isNotEmpty)?.joinToString(", "),
                "Configured tax rate ${configuredTaxPercent(line.taxRate)} · tax charged ${(line.cgstMinor + line.sgstMinor + line.igstMinor + line.cessMinor).asRupees()}",
                line.note?.let { "Note: $it" },
                line.discountMinor.takeIf { it != 0L }?.let {
                    "Line discount: ${it.asRupees()}"
                },
                line.voidedByName?.let { "Voided by: $it" },
                line.voidReason?.let { "VOIDED: $it" },
            ).forEach { detail ->
                Text(
                    detail,
                    color = if (line.voidedAt == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.operationalDanger,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(line.lineTotalMinor.asRupees(), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ReceiptPaymentRow(payment: CanonicalReceiptPayment) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        ReceiptMoneyRow(payment.method.uppercase(), payment.amountMinor, emphasized = true)
        ReceiptValueRow("Recorded by", payment.recordedByName ?: "Staff record unavailable")
        ReceiptValueRow("Paid", payment.paidAt.receiptDateTime())
        if (payment.tenderCorrectionId != null) {
            ReceiptValueRow("Originally recorded method",payment.originalMethod?.uppercase() ?: "Not retained")
            payment.originalTenderedMinor?.let { ReceiptMoneyRow("Originally recorded cash received",it) }
            payment.originalChangeMinor?.let { ReceiptMoneyRow("Originally recorded change",it) }
            payment.tenderCorrectionRecordedAt?.let { ReceiptValueRow("Correction recorded",it.receiptDateTime()) }
            Text("The effective method is shown above. Original payment, bill total and recorded drawer close are retained.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        payment.tenderedMinor?.let { ReceiptMoneyRow("Cash received", it) }
        payment.changeMinor?.let { ReceiptMoneyRow("Change", it) }
        payment.reference?.takeIf(String::isNotBlank)?.let { ReceiptValueRow("Reference", it) }
    }
}

@Composable
private fun ReceiptRefundRow(refund: CanonicalReceiptRefund) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        ReceiptMoneyRow("Refund", -refund.amountMinor, emphasized = true)
        ReceiptValueRow("Reason", refund.reasonCode.replace('_', ' '))
        ReceiptValueRow("Approved by", refund.approvedByName ?: "Staff record unavailable")
        refund.managerOverrideUserName?.let { ReceiptValueRow("Manager override", it) }
        refund.settlementMethod?.let { ReceiptValueRow("Settlement", it.uppercase()) }
        refund.settledByName?.let { ReceiptValueRow("Settled by", it) }
        refund.settledAt?.let { ReceiptValueRow("Settled", it.receiptDateTime()) }
        refund.receiptNo?.let { ReceiptValueRow("Refund receipt", it) }
        refund.externalReference?.takeIf(String::isNotBlank)?.let {
            ReceiptValueRow("Reference", it)
        }
        refund.note?.takeIf(String::isNotBlank)?.let { ReceiptValueRow("Note", it) }
    }
}

@Composable
private fun ReceiptGamingRow(session: CanonicalReceiptGamingSession) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(session.stationName, fontWeight = FontWeight.SemiBold)
            session.amountMinor?.let { Text(it.asRupees(), color = MaterialTheme.colorScheme.primary) }
        }
        ReceiptValueRow("Mode", session.billingMode.replace('_', ' '))
        when (session.billingMode) {
            "hourly" -> ReceiptValueRow("Locked hourly rate", "${session.ratePerHourMinor.asRupees()}/hr")
            "package" -> ReceiptValueRow(
                "Package",
                session.effectivePackagePriceMinorSnapshot?.let { price ->
                    val variant = session.effectivePackageVariantSnapshot
                        ?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "Package"
                    val duration = session.effectivePackageDurationMinutesSnapshot
                        ?.let { "$it min" } ?: "duration unavailable"
                    "$variant · $duration · ${price.asRupees()}"
                } ?: "Package snapshot unavailable (legacy receipt)",
            )
            else -> ReceiptValueRow("Billing basis", "Unavailable for this legacy receipt")
        }
        session.bookedControllerSurchargeMinor?.let { ReceiptMoneyRow("Booked controller fee", it) }
        session.extensionPackageMinor?.takeIf { it > 0L }?.let { ReceiptMoneyRow("Paid package extensions", it) }
        session.extensionControllerSurchargeMinor?.takeIf { it > 0L }?.let {
            ReceiptMoneyRow("Extension controller fee", it)
        }
        session.lateFriendChargeMinor?.let { ReceiptMoneyRow("Late player controller fee", it) }
        session.billableMinutes?.let { ReceiptValueRow("Billable time", "$it min") }
        ReceiptValueRow("Started by", session.startedByName ?: "Staff record unavailable")
        session.stoppedByName?.let { ReceiptValueRow("Stopped by", it) }
        session.sentToPosByName?.let { ReceiptValueRow("Sent to POS by", it) }
        ReceiptValueRow("Started", session.startedAt.receiptDateTime())
        session.stoppedAt?.let { ReceiptValueRow("Stopped", it.receiptDateTime()) }
    }
}

@Composable
private fun ReceiptValueRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReceiptMoneyRow(label: String, amountMinor: Long, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            color = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            amountMinor.asRupees(),
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun String.receiptQuantity(): String = runCatching {
    BigDecimal(this).stripTrailingZeros().toPlainString()
}.getOrDefault(this)

/** Persisted fractions remain exact: 0.18 is 18%, 0.005 is 0.5%. */
internal fun configuredTaxPercent(fraction:String):String = runCatching {
    BigDecimal(fraction).movePointRight(2).stripTrailingZeros().toPlainString()+"%"
}.getOrElse { "Rate unavailable" }
