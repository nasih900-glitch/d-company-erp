package cloud.dcompany.erp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ReceiptInvestigation
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Read-only retained source projection; neither the local payment receipt nor a new sale. */
@Composable
internal fun ReceiptInvestigationPanel(
    orderId: String,
    ownerScopeKey: String,
    shopTimezone: String? = null,
    onAuditReference: ((String) -> Unit)? = null,
) {
    var record by remember(orderId,ownerScopeKey) { mutableStateOf<ReceiptInvestigation?>(null) }
    var failure by remember(orderId,ownerScopeKey) { mutableStateOf<String?>(null) }
    var reload by remember(orderId,ownerScopeKey) { mutableIntStateOf(0) }
    LaunchedEffect(orderId,ownerScopeKey,reload) {
        failure=null
        try { record=ApiClient.api.investigateReceipt(orderId) }
        catch(cancelled:CancellationException) { throw cancelled }
        catch(error:Exception) { failure=error.message ?: "Bill investigation could not load. Retained receipt facts are unchanged." }
    }
    SectionCard(title="Bill investigation",subtitle="Retained receipt, player, drink and settlement evidence") {
        val data=record
        if(data==null && failure==null) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let { Text(it,color=MaterialTheme.colorScheme.error); TextButton({reload++}){Text("Retry investigation")} }
        if(data!=null) ReceiptInvestigationContent(data,shopTimezone,onAuditReference)
    }
}

@Composable
internal fun ReceiptInvestigationContent(data:ReceiptInvestigation,zone:String?,onAuditReference:((String)->Unit)?=null) {
    val receipt=data.receipt
    var technical by remember(receipt.orderId){mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(Spacing.sm),modifier=Modifier.fillMaxWidth()) {
        Text(receipt.invoiceNo,style=MaterialTheme.typography.titleMedium)
        InvestigationFact("Original bill",receipt.totalMinor.asRupees())
        InvestigationFact("Paid / refunded","${receipt.paidMinor.asRupees()} / ${receipt.refundedMinor.asRupees()}")
        if(receipt.pendingRefundMinor>0) InvestigationFact("Refund reserved, not settled",receipt.pendingRefundMinor.asRupees())
        InvestigationFact("Invoice issued",investigationTime(receipt.invoiceIssuedAt,zone))
        Text(data.historicalEvidenceNote,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Payments",fontWeight=FontWeight.SemiBold)
        receipt.payments.forEach { payment ->
            InvestigationFact("${payment.method.uppercase(Locale.ROOT)} · ${payment.amountMinor.asRupees()}",
                "${investigationTime(payment.paidAt,zone)} · ${payment.recordedByName?.let { "$it (current account label)" } ?: "Recorder name unavailable"}")
            if(payment.tenderCorrectionId==null && payment.method.equals("cash",ignoreCase=true)) {
                payment.tenderedMinor?.let{InvestigationFact("Cash tender",it.asRupees())}
                payment.changeMinor?.let{InvestigationFact("Cash change",it.asRupees())}
            }
            if(payment.tenderCorrectionId!=null) {
                InvestigationFact("Originally recorded",payment.originalMethod?.uppercase(Locale.ROOT) ?: "Method unavailable")
                payment.originalTenderedMinor?.let{InvestigationFact("Original Cash tender",it.asRupees())}
                payment.originalChangeMinor?.let{InvestigationFact("Original Cash change",it.asRupees())}
                payment.tenderCorrectionRecordedAt?.let{InvestigationFact("Classification corrected",investigationTime(it,zone))}
                Text("Original payment and bill are retained. Detailed correction evidence requires protected owner verification.",style=MaterialTheme.typography.bodySmall)
            }
        }
        if(receipt.refunds.isNotEmpty()) Text("Refunds",fontWeight=FontWeight.SemiBold)
        receipt.refunds.forEach { refund ->
            InvestigationFact(refund.receiptNo ?: "Recorded refund",refund.amountMinor.asRupees())
            InvestigationFact("Settlement",refund.settledAt?.let{"${refund.settlementMethod ?: "Recorded method"} · ${investigationTime(it,zone)}"}
                ?: "No completed settlement timestamp retained")
            refund.note?.let{InvestigationFact("Reason / note",it)}
        }
        if(receipt.gamingSessions.isNotEmpty()) Text("Sessions",fontWeight=FontWeight.SemiBold)
        receipt.gamingSessions.forEach { session ->
            InvestigationFact("Station", "${session.stationName} · current station label")
            InvestigationFact("Session timing","Started ${investigationTime(session.startedAt,zone)} · stopped ${session.stoppedAt?.let{investigationTime(it,zone)} ?: "Stop evidence unavailable"}")
            session.amountMinor?.let{InvestigationFact("Retained session amount",it.asRupees())}
        }
        if(data.participantIntervals.isNotEmpty()) Text("Players · joining and leaving",fontWeight=FontWeight.SemiBold)
        data.participantIntervals.groupBy{it.gamingSessionId to it.participantIdentityId}.forEach { (identity,intervals) ->
            val name=intervals.firstNotNullOfOrNull{it.guestName?.takeIf(String::isNotBlank)}
                ?: if(intervals.any{it.customerId!=null}) "Saved customer · event-time name unavailable" else "Anonymous player"
            Text(name,fontWeight=FontWeight.Medium)
            intervals.forEachIndexed { index,interval ->
                InvestigationFact(if(index==0) "Joined" else "Rejoined",investigationTime(interval.joinedAt,zone))
                InvestigationFact("Leave",interval.leftAt?.let{investigationTime(it,zone)} ?: "No separate Leave recorded; see the session stop")
                InvestigationFact("Timing evidence","${interval.joinTimingSource} · joined at ${playingTime(interval.joinedPlayElapsedMs)} of session play")
            }
            val charges=data.participantCharges.filter{it.gamingSessionId==identity.first && it.participantIdentityId==identity.second}
            charges.forEach { charge ->
                InvestigationFact("One settled player charge","${charge.chargeMinor.asRupees()} · ${playingTime(charge.playElapsedMs)} across ${charge.intervalCount} interval(s)")
            }
            if(charges.isEmpty()) Text("No individual settled charge evidence retained.",style=MaterialTheme.typography.bodySmall)
        }
        if(data.addons.isNotEmpty()) Text("Drinks and add-ons",fontWeight=FontWeight.SemiBold)
        data.addons.forEach { addon ->
            InvestigationFact(addon.menuItemNameSnapshot,"${addon.qty} × ${addon.unitPriceMinor.asRupees()} = ${addon.lineTotalMinor.asRupees()}")
            InvestigationFact("Confirmed addition",investigationTime(addon.createdAt,zone))
            addon.voidedAt?.let { InvestigationFact("Removed", "${investigationTime(it,zone)} · ${addon.voidReason ?: "Reason not retained"}") }
        }
        if(data.auditSourceReferences.isNotEmpty()) {
            Text("Related activity",fontWeight=FontWeight.SemiBold)
            data.auditSourceReferences.forEach { reference ->
                if(onAuditReference!=null) TextButton({onAuditReference(reference)}){Text("Audit · $reference")}
                else Text(reference,style=MaterialTheme.typography.bodySmall)
            }
            Text("Audit requires its own protected owner verification.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton({technical=!technical}){Text(if(technical) "Hide source identities" else "Source identities")}
        if(technical) {
            InvestigationFact("Order",receipt.orderId)
            InvestigationFact("Date basis",data.invoiceDateBasis)
            receipt.payments.forEach{InvestigationFact("Payment / correction","${it.id} · ${it.tenderCorrectionId ?: "No correction"}")}
            data.participantIntervals.forEach{InvestigationFact("Player interval / identity","${it.id} · ${it.participantIdentityId}")}
            data.addons.forEach{InvestigationFact("Add-on / captured line","${it.id} · ${it.clientLineId}")}
        }
    }
}

@Composable
private fun InvestigationFact(label:String,value:String) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.bodyMedium)
    }
}
private fun playingTime(milliseconds:Long):String {
    val seconds=milliseconds/1000
    return "${seconds/3600} hr ${(seconds/60)%60} min ${seconds%60} sec"
}
private fun investigationTime(value:String,zone:String?):String = runCatching {
    val timezone=ZoneId.of(zone ?: "UTC")
    DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm:ss",Locale.ENGLISH).withZone(timezone)
        .format(Instant.parse(value))+" · ${timezone.id}"+(if(zone == null) " (shop timezone unavailable)" else "")
}.getOrDefault("Time unavailable; original timestamp is retained")
