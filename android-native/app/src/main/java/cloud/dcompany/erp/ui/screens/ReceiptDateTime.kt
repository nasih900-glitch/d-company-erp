package cloud.dcompany.erp.ui.screens

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Single Kerala shop's presentation zone; never used to rewrite wire timestamps. */
internal val shopBusinessZone: ZoneId = ZoneId.of("Asia/Kolkata")

private val receiptDateTimeFormatter = DateTimeFormatter
    .ofPattern("dd MMM yyyy · h:mm a 'IST'", Locale.ENGLISH)
    .withZone(shopBusinessZone)

private val businessClockFormatter = DateTimeFormatter
    .ofPattern("h:mm a 'IST'", Locale.ENGLISH)
    .withZone(shopBusinessZone)

internal fun String.businessClockTime(): String = runCatching {
    businessClockFormatter.format(Instant.parse(this))
}.getOrDefault("Time unavailable")

internal fun Long.businessDateTime(): String = runCatching {
    receiptDateTimeFormatter.format(Instant.ofEpochMilli(this))
}.getOrDefault("Time unavailable")

/**
 * D Company's single Kerala shop uses IST for financial dates. Presentation
 * must not move a payment onto another day when viewed from a London device.
 * The original wire timestamp remains unchanged for settlement and auditing.
 */
internal fun String.receiptDateTime(): String = runCatching {
    receiptDateTimeFormatter.format(Instant.parse(this))
}.getOrDefault("Time unavailable")
