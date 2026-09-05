package cloud.dcompany.erp.ui.screens

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val receiptDateTimeFormatter = DateTimeFormatter
    .ofPattern("dd MMM yyyy · h:mm a 'IST'", Locale.ENGLISH)
    .withZone(ZoneId.of("Asia/Kolkata"))

/**
 * D Company's single Kerala shop uses IST for financial dates. Presentation
 * must not move a payment onto another day when viewed from a London device.
 * The original wire timestamp remains unchanged for settlement and auditing.
 */
internal fun String.receiptDateTime(): String = runCatching {
    receiptDateTimeFormatter.format(Instant.parse(this))
}.getOrDefault("Time unavailable")
