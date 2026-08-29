package cloud.dcompany.erp.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    onDismiss: () -> Unit,
) {
    var selectedOrderId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedOrderId?.let { id -> receipts.firstOrNull { it.orderId == id } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = Brand.SurfaceOverlay,
            shape = Radius.shapeXl,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f)
                .widthIn(max = 980.dp)
                .heightIn(min = 420.dp, max = 760.dp)
                .border(1.dp, Brand.Border, Radius.shapeXl),
        ) {
            if (selected == null) {
                ReceiptHistoryList(
                    receipts = receipts,
                    hasMore = hasMore,
                    loading = loading,
                    error = error,
                    hasLocalReceipt = hasLocalReceipt,
                    onOpenLastLocalReceipt = onOpenLastLocalReceipt,
                    onSelect = {
                        selectedOrderId = it.orderId
                        onOpenReceipt(it.orderId)
                    },
                    onRefresh = onRefresh,
                    onLoadMore = onLoadMore,
                    onDismiss = onDismiss,
                )
            } else {
                CanonicalReceiptDetail(
                    receipt = selected,
                    loading = loading,
                    error = error,
                    onRefresh = { onOpenReceipt(selected.orderId) },
                    onBack = { selectedOrderId = null },
                    onDismiss = onDismiss,
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
        HorizontalDivider(color = Brand.BorderSubtle)

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
                        tint = Brand.ForegroundMuted,
                        modifier = Modifier.size(40.dp),
                    )
                    Text("No shared receipts loaded", style = MaterialTheme.typography.titleLarge)
                    Text(
                        error ?: "Completed receipts will appear here from web or Android.",
                        color = if (error == null) Brand.ForegroundMuted else Brand.Warning,
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
                        color = Brand.ForegroundFaint,
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
            .background(Brand.WarningMuted, Radius.shapeMd)
            .border(1.dp, Brand.Warning.copy(alpha = 0.45f), Radius.shapeMd)
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, tint = Brand.Warning)
        Text(message, color = Brand.Warning, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReceiptHistoryRow(receipt: CanonicalReceipt, onClick: () -> Unit) {
    val source = receipt.gamingSessions.firstOrNull()?.stationName
        ?: receipt.orderType.replace('_', ' ').replaceFirstChar(Char::uppercase)
    val paymentSummary = receiptPaymentSummary(receipt.payments)
    Surface(
        color = Brand.SurfaceRaised,
        shape = Radius.shapeLg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .clickable(onClick = onClick)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg),
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
                tint = if (receipt.gamingSessions.isEmpty()) Brand.ForegroundMuted else Brand.Gold,
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
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                paymentSummary?.let { summary ->
                    Text(
                        summary,
                        color = Brand.ForegroundFaint,
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
                    color = if (receipt.status == "refunded") Brand.Warning else Brand.Good,
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
) {
    Column {
        ReceiptDialogHeader(
            title = receipt.invoiceNo,
            subtitle = "Official server receipt · ${receipt.invoiceIssuedAt.receiptDateTime()}",
            onBack = onBack,
            onRefresh = onRefresh,
            refreshing = loading,
            onDismiss = onDismiss,
        )
        HorizontalDivider(color = Brand.BorderSubtle)
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
                    ReceiptValueRow("Order opened by", receipt.openedByName ?: "Staff record unavailable")
                    ReceiptValueRow("Shift opened by", receipt.shiftOpenedByName ?: "Staff record unavailable")
                    receipt.customerName?.let { ReceiptValueRow("Customer", it) }
                    receipt.customerPhone?.let { ReceiptValueRow("Phone", it) }
                }
            }
            item {
                ReceiptDetailSection("Items") {
                    receipt.lines.forEachIndexed { index, line ->
                        if (index > 0) HorizontalDivider(color = Brand.BorderSubtle)
                        ReceiptLineRow(line)
                    }
                }
            }
            if (receipt.gamingSessions.isNotEmpty()) {
                item {
                    ReceiptDetailSection("Gaming provenance") {
                        receipt.gamingSessions.forEachIndexed { index, session ->
                            if (index > 0) HorizontalDivider(color = Brand.BorderSubtle)
                            ReceiptGamingRow(session)
                        }
                    }
                }
            }
            item {
                ReceiptDetailSection("Payments") {
                    if (receipt.payments.isEmpty()) {
                        Text("No payment rows recorded.", color = Brand.ForegroundMuted)
                    } else {
                        receipt.payments.forEachIndexed { index, payment ->
                            if (index > 0) HorizontalDivider(color = Brand.BorderSubtle)
                            ReceiptPaymentRow(payment)
                        }
                    }
                }
            }
            if (receipt.refunds.isNotEmpty()) {
                item {
                    ReceiptDetailSection("Refunds") {
                        receipt.refunds.forEachIndexed { index, refund ->
                            if (index > 0) HorizontalDivider(color = Brand.BorderSubtle)
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
                    if (receipt.taxMinor != 0L) ReceiptMoneyRow("Tax", receipt.taxMinor)
                    if (receipt.tipMinor != 0L) ReceiptMoneyRow("Tip", receipt.tipMinor)
                    if (receipt.roundOffMinor != 0L) ReceiptMoneyRow("Round-off", receipt.roundOffMinor)
                    HorizontalDivider(color = Brand.Border)
                    ReceiptMoneyRow("Total", receipt.totalMinor, emphasized = true)
                    ReceiptMoneyRow("Paid", receipt.paidMinor)
                    if (receipt.refundedMinor != 0L) {
                        ReceiptMoneyRow("Refunded", -receipt.refundedMinor)
                    }
                    ReceiptMoneyRow("Net collected", receipt.netCollectedMinor, emphasized = true)
                }
            }
            item {
                Text(
                    "Order ${receipt.orderId.take(8)} · Terminal ${receipt.terminalId.take(8)} · FY ${receipt.fiscalYear}",
                    color = Brand.ForegroundFaint,
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
            Text(subtitle, color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodySmall)
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
        color = Brand.SurfaceRaised,
        shape = Radius.shapeLg,
        modifier = Modifier.fillMaxWidth().border(1.dp, Brand.BorderSubtle, Radius.shapeLg),
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
                color = if (line.voidedAt == null) Brand.Foreground else Brand.ForegroundMuted,
                fontWeight = FontWeight.Medium,
            )
            listOfNotNull(
                variantName,
                modifierLabels.takeIf(List<String>::isNotEmpty)?.joinToString(", "),
                line.note?.let { "Note: $it" },
                line.discountMinor.takeIf { it != 0L }?.let {
                    "Line discount: ${it.asRupees()}"
                },
                line.voidedByName?.let { "Voided by: $it" },
                line.voidReason?.let { "VOIDED: $it" },
            ).forEach { detail ->
                Text(
                    detail,
                    color = if (line.voidedAt == null) Brand.ForegroundMuted else Brand.Danger,
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
            session.amountMinor?.let { Text(it.asRupees(), color = Brand.Gold) }
        }
        ReceiptValueRow("Mode", session.billingMode.replace('_', ' '))
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
        Text(label, color = Brand.ForegroundMuted, modifier = Modifier.weight(1f))
        Text(value, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReceiptMoneyRow(label: String, amountMinor: Long, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            color = if (emphasized) Brand.Foreground else Brand.ForegroundMuted,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            amountMinor.asRupees(),
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private val receiptDateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy · h:mm a")

private fun String.receiptDateTime(): String = runCatching {
    receiptDateTimeFormatter.format(Instant.parse(this).atZone(ZoneId.systemDefault()))
}.getOrDefault(this)

private fun String.receiptQuantity(): String = runCatching {
    BigDecimal(this).stripTrailingZeros().toPlainString()
}.getOrDefault(this)
