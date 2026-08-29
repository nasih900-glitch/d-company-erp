package cloud.dcompany.erp.ui.screens.settings

import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.WorkspacePresentationPolicy
import cloud.dcompany.erp.ui.presentationPolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.ZoneId

internal sealed interface DestructiveSettingsAction {
    data object DiscardCompanyEdits : DestructiveSettingsAction
    data class DiscardBranchForm(val branchName: String, val isNew: Boolean) : DestructiveSettingsAction
    data class DeleteTerminal(val terminalName: String) : DestructiveSettingsAction
}

internal data class SettingsConfirmation(
    val title: String,
    val body: String,
    val confirmLabel: String,
)

internal fun settingsConfirmation(action: DestructiveSettingsAction): SettingsConfirmation = when (action) {
    DestructiveSettingsAction.DiscardCompanyEdits -> SettingsConfirmation(
        title = "Discard company changes?",
        body = "Your unsaved company identity, timezone and payment-QR edits will be lost.",
        confirmLabel = "Discard changes",
    )
    is DestructiveSettingsAction.DiscardBranchForm -> SettingsConfirmation(
        title = if (action.isNew) "Discard new shop?" else "Discard shop edits?",
        body = if (action.isNew) {
            "The unsaved shop setup for ${action.branchName.ifBlank { "this shop" }} will be lost."
        } else {
            "Unsaved hours, licence and address edits for " +
                "${action.branchName.ifBlank { "this shop" }} will be lost."
        },
        confirmLabel = "Discard edits",
    )
    is DestructiveSettingsAction.DeleteTerminal -> SettingsConfirmation(
        title = "Delete ${action.terminalName}?",
        body = "This removes the till assignment. It cannot be deleted if shifts, orders, or audit " +
            "history depend on it. Confirm only if this terminal is genuinely unused.",
        confirmLabel = "Delete terminal",
    )
}

/**
 * Wire models copied field-for-field from the FastAPI schemas rather than
 * guessed — a wrong name here fails at runtime, not compile time:
 *
 *   CompanyRead / CompanyUpdate / BranchRead / BranchCreate / BranchUpdate /
 *   TerminalRead / TerminalCreate / TerminalUpdate
 *                                   backend/app/api/v1/settings/router.py
 *   OtpChallengeResponse / PasswordResetConfirm / AccountActionResponse
 *                                   backend/app/api/v1/auth/router.py
 *
 * Every *update* body field is nullable with a null default on purpose. The
 * shared Json is configured with `explicitNulls = false`, so a null is dropped
 * from the payload entirely — which is exactly PATCH semantics, and exactly
 * what the backend expects: `update_company` skips any field that arrives as
 * None. The corollary is that these endpoints can *set* a value but cannot
 * *clear* one; the single exception is `upi_vpa`, whose validator explicitly
 * allows an empty string. The UI says so rather than silently doing nothing.
 */

@Serializable
data class CompanyDto(
    val id: String,
    val name: String,
    @SerialName("legal_name") val legalName: String? = null,
    val currency: String = "INR",
    val timezone: String = "Asia/Kolkata",
    val country: String? = null,
    val gstin: String? = null,
    val pan: String? = null,
    @SerialName("gst_registration_type") val gstRegistrationType: String = "regular",
    @SerialName("is_composition") val isComposition: Boolean = false,
    @SerialName("e_invoicing_enabled") val eInvoicingEnabled: Boolean = false,
    @SerialName("fiscal_year_start_month") val fiscalYearStartMonth: Int = 4,
    @SerialName("google_sheets_webhook_url") val googleSheetsWebhookUrl: String? = null,
    @SerialName("upi_vpa") val upiVpa: String? = null,
    @SerialName("payment_provider") val paymentProvider: String? = null,
    @SerialName("payment_key_id") val paymentKeyId: String? = null,
    /** The secret itself is never sent by the backend — only whether one exists. */
    @SerialName("payment_secret_set") val paymentSecretSet: Boolean = false,
)

@Serializable
data class CompanyUpdateBody(
    val name: String? = null,
    @SerialName("legal_name") val legalName: String? = null,
    val timezone: String? = null,
    val gstin: String? = null,
    val pan: String? = null,
    @SerialName("gst_registration_type") val gstRegistrationType: String? = null,
    @SerialName("is_composition") val isComposition: Boolean? = null,
    @SerialName("e_invoicing_enabled") val eInvoicingEnabled: Boolean? = null,
    @SerialName("upi_vpa") val upiVpa: String? = null,
)

@Serializable
data class BranchDto(
    val id: String,
    val name: String,
    val code: String? = null,
    /** Stable fiscal-document namespace, separate from the editable display code. */
    @SerialName("invoice_series_code") val invoiceSeriesCode: String = "",
    val address: String? = null,
    val timezone: String? = null,
    @SerialName("opens_at") val opensAt: String? = null,
    @SerialName("closes_at") val closesAt: String? = null,
    @SerialName("state_code") val stateCode: String? = null,
    @SerialName("fssai_license_no") val fssaiLicenseNo: String? = null,
    @SerialName("trade_license_no") val tradeLicenseNo: String? = null,
    @SerialName("branch_gstin") val branchGstin: String? = null,
)

/** Serves both POST /settings/branches and PATCH /settings/branches/{id}. */
@Serializable
data class BranchWriteBody(
    val name: String,
    val code: String? = null,
    /**
     * Nullable only for recovery of a pre-v3.0.4 queued branch. New forms
     * always send this explicitly; the transition backend accepts omission
     * only when [code] is itself exactly two alphanumeric characters.
     */
    @SerialName("invoice_series_code") val invoiceSeriesCode: String? = null,
    val address: String? = null,
    val timezone: String? = null,
    @SerialName("opens_at") val opensAt: String? = null,
    @SerialName("closes_at") val closesAt: String? = null,
    @SerialName("state_code") val stateCode: String? = null,
    @SerialName("fssai_license_no") val fssaiLicenseNo: String? = null,
    @SerialName("trade_license_no") val tradeLicenseNo: String? = null,
    @SerialName("branch_gstin") val branchGstin: String? = null,
)

@Serializable
data class TerminalDto(
    val id: String,
    @SerialName("branch_id") val branchId: String,
    val name: String,
    /** Absent only on an older server; that legacy contract behaved as hybrid. */
    val purpose: String = TerminalPurpose.HYBRID,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
data class TerminalCreateBody(
    @SerialName("branch_id") val branchId: String,
    val name: String,
    val purpose: String,
    @SerialName("device_id") val deviceId: String? = null,
)

@Serializable
data class TerminalUpdateBody(
    val name: String? = null,
    val purpose: String? = null,
    /** Empty string is intentional: the backend normalizes it to null/clear. */
    @SerialName("device_id") val deviceId: String? = null,
)

data class TerminalEditForm(
    val id: String,
    val branchId: String,
    val originalName: String,
    val name: String,
    val purpose: String,
    val originalPurpose: String = purpose,
    val deviceId: String,
)

fun TerminalDto.toEditForm() = TerminalEditForm(
    id = id,
    branchId = branchId,
    originalName = name,
    name = name,
    purpose = purpose,
    deviceId = deviceId.orEmpty(),
)

fun TerminalEditForm.validate(): String? {
    if (name.isBlank()) return "Give the till a name."
    if (name.trim().length > 100) return "Till name must be 100 characters or fewer."
    if (!TerminalPurpose.isKnown(purpose)) return "Choose how this terminal will be used."
    return terminalDeviceIdError(deviceId)
}

fun TerminalEditForm.toBody() = TerminalUpdateBody(
    name = name.trim(),
    purpose = purpose,
    // Send blank explicitly so the backend clears a removed device binding.
    deviceId = deviceId.trim(),
)

internal data class TerminalPurposeOption(
    val id: String,
    val label: String,
    val description: String,
)

internal fun terminalPurposeOptions(
    presentation: WorkspacePresentationPolicy,
): List<TerminalPurposeOption> {
    val hybrid = TerminalPurposeOption(
        id = TerminalPurpose.HYBRID,
        label = presentation.hybridTerminalLabel,
        description = "Runs Gaming, POS, payments and one accountable shift in the same workspace.",
    )
    if (presentation.singleHybridTerminalOnly) return listOf(hybrid)
    return listOf(
        TerminalPurposeOption(
            id = TerminalPurpose.CAFE_POS,
            label = presentation.posOnlyTerminalLabel,
            description = if (presentation.showsRestaurantOperations) {
                "Takes food payments and receives bills from Gaming Area."
            } else {
                "Legacy POS-only till. Use only when a separate payment counter is genuinely required."
            },
        ),
        TerminalPurposeOption(
            id = TerminalPurpose.GAMING,
            label = presentation.gamingTerminalLabel,
            description = if (presentation.showsRestaurantOperations) {
                "Starts gaming sessions and must hand completed bills to an open Cafe POS shift."
            } else {
                "Starts gaming sessions; completed bills require an open receiving POS shift."
            },
        ),
        hybrid,
    )
}

internal val terminalPurposeOptions: List<TerminalPurposeOption> =
    terminalPurposeOptions(WorkspaceFeatureProfiles.FullHospitality.presentationPolicy())

internal fun terminalPurposeLabel(
    purpose: String,
    presentation: WorkspacePresentationPolicy = WorkspaceFeatureProfiles.FullHospitality.presentationPolicy(),
): String = terminalPurposeOptions(presentation).firstOrNull { it.id == purpose }?.label ?: "Unknown purpose"

@Serializable
data class OtpChallengeDto(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("expires_in") val expiresIn: Int = 0,
    val destination: String = "",
)

@Serializable
data class PasswordResetRequestBody(val email: String)

@Serializable
data class PasswordResetConfirmBody(
    @SerialName("challenge_id") val challengeId: String,
    val code: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class AccountActionDto(val message: String = "")

// ---------------------------------------------------------------- validation

/**
 * The backend resolves this from the tz database and 422s on anything else,
 * because a non-IANA zone (say "IST") makes date formatting throw while a
 * receipt is printing. Android ships the same database, so the same check runs
 * here first and the operator gets the message on the field instead of after a
 * round trip. A server that disagrees still wins: its 422 is shown verbatim.
 */
private val ianaZoneIds: Set<String> by lazy { ZoneId.getAvailableZoneIds() }
private val invoiceSeriesPattern = Regex("^[A-Z0-9]{2}$")

/** Word-for-word the backend's own message, so both paths read identically. */
const val TIMEZONE_MESSAGE = "timezone must be a valid IANA name like Asia/Kolkata"

fun isIanaTimezone(value: String): Boolean = value.trim() in ianaZoneIds

/** Same normalization and format accepted by BranchCreate/BranchUpdate. */
internal fun normalizeInvoiceSeries(value: String): String = value.trim().uppercase()

internal fun isValidInvoiceSeries(value: String): Boolean =
    invoiceSeriesPattern.matches(normalizeInvoiceSeries(value))

/**
 * Recover only the exact backward-compatible case accepted by the server.
 * Never truncate a longer operational code: doing so could silently choose
 * the wrong legal invoice namespace.
 */
internal fun resolveQueuedInvoiceSeries(explicit: String?, operationalCode: String?): String? {
    if (explicit != null) {
        return normalizeInvoiceSeries(explicit).takeIf(::isValidInvoiceSeries)
    }
    return operationalCode?.let(::normalizeInvoiceSeries)?.takeIf(::isValidInvoiceSeries)
}

/** A handful of matches for the tablet keyboard — nobody types a tz list by hand. */
fun timezoneSuggestions(typed: String, limit: Int = 6): List<String> {
    val query = typed.trim()
    if (query.isEmpty()) return listOf("Asia/Kolkata", "Asia/Dubai", "UTC")
    if (isIanaTimezone(query)) return emptyList()
    return ianaZoneIds.asSequence()
        .filter { it.contains(query, ignoreCase = true) }
        .sorted()
        .take(limit)
        .toList()
}

private val upiVpaPattern = Regex("^[A-Za-z0-9.\\-_]{2,256}@[A-Za-z]{2,64}$")
private val hhmmPattern = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
private val stateCodePattern = Regex("^\\d{2}$")
private val fssaiPattern = Regex("^\\d{14}$")
private const val TERMINAL_DEVICE_ID_MAX_LENGTH = 100

val gstRegistrationTypes = listOf("regular", "composition", "unregistered", "sez")

fun terminalDeviceIdError(value: String): String? =
    if (value.trim().length > TERMINAL_DEVICE_ID_MAX_LENGTH) {
        "Tablet device ID must be $TERMINAL_DEVICE_ID_MAX_LENGTH characters or fewer."
    } else {
        null
    }

// ---------------------------------------------------------------- form state

data class CompanyForm(
    val name: String = "",
    val legalName: String = "",
    val timezone: String = "",
    val gstin: String = "",
    val pan: String = "",
    val gstRegistrationType: String = "regular",
    val isComposition: Boolean = false,
    val eInvoicingEnabled: Boolean = false,
    val upiVpa: String = "",
)

fun CompanyDto.toForm() = CompanyForm(
    name = name,
    legalName = legalName.orEmpty(),
    timezone = timezone,
    gstin = gstin.orEmpty(),
    pan = pan.orEmpty(),
    gstRegistrationType = gstRegistrationType,
    isComposition = isComposition,
    eInvoicingEnabled = eInvoicingEnabled,
    upiVpa = upiVpa.orEmpty(),
)

/** Null when the form is safe to send; otherwise the message to show. */
fun CompanyForm.validate(): String? {
    if (name.isBlank()) return "Company display name cannot be empty."
    if (name.trim().length > 200) return "Company display name must be 200 characters or fewer."
    if (legalName.trim().length > 200) return "Legal name must be 200 characters or fewer."
    if (!isIanaTimezone(timezone)) return TIMEZONE_MESSAGE
    if (gstin.isNotBlank() && gstin.trim().length != 15) {
        return "GSTIN must be exactly 15 characters."
    }
    if (pan.isNotBlank() && pan.trim().length != 10) {
        return "PAN must be exactly 10 characters."
    }
    if (gstRegistrationType !in gstRegistrationTypes) {
        return "Pick a GST registration type."
    }
    val vpa = upiVpa.trim()
    if (vpa.isNotEmpty() && !upiVpaPattern.matches(vpa)) {
        return "UPI ID must look like name@bank."
    }
    return null
}

fun CompanyForm.toBody() = CompanyUpdateBody(
    name = name.trim(),
    legalName = legalName.trim().ifBlank { null },
    timezone = timezone.trim(),
    gstin = gstin.trim().uppercase().ifBlank { null },
    pan = pan.trim().uppercase().ifBlank { null },
    gstRegistrationType = gstRegistrationType,
    isComposition = isComposition,
    eInvoicingEnabled = eInvoicingEnabled,
    // Always sent, blank included: "" is the only value this endpoint treats
    // as "clear it", and clearing the VPA is how you turn the checkout QR off.
    upiVpa = upiVpa.trim(),
)

data class BranchForm(
    /** Null means this is a new branch rather than an edit. */
    val id: String? = null,
    val name: String = "",
    val code: String = "",
    val invoiceSeriesCode: String = "",
    val address: String = "",
    val timezone: String = "Asia/Kolkata",
    val opensAt: String = "09:00",
    val closesAt: String = "23:30",
    val stateCode: String = "32",
    val fssaiLicenseNo: String = "",
    val tradeLicenseNo: String = "",
    val branchGstin: String = "",
) {
    val isNew: Boolean get() = id == null
}

fun BranchDto.toForm() = BranchForm(
    id = id,
    name = name,
    code = code.orEmpty(),
    invoiceSeriesCode = invoiceSeriesCode,
    address = address.orEmpty(),
    timezone = timezone.orEmpty(),
    opensAt = opensAt.orEmpty(),
    closesAt = closesAt.orEmpty(),
    stateCode = stateCode.orEmpty(),
    fssaiLicenseNo = fssaiLicenseNo.orEmpty(),
    tradeLicenseNo = tradeLicenseNo.orEmpty(),
    branchGstin = branchGstin.orEmpty(),
)

fun BranchForm.validate(): String? {
    if (name.isBlank()) return "Shop name cannot be empty."
    if (name.trim().length > 200) return "Shop name must be 200 characters or fewer."
    if (code.trim().length > 10) return "Short code must be 10 characters or fewer."
    if (!isValidInvoiceSeries(invoiceSeriesCode)) {
        return "Invoice series must be exactly two letters or digits, for example MN."
    }
    if (address.trim().length > 500) return "Address must be 500 characters or fewer."
    val tz = timezone.trim()
    if (tz.isNotEmpty() && !isIanaTimezone(tz)) return TIMEZONE_MESSAGE
    val opens = opensAt.trim()
    if (opens.isNotEmpty() && !hhmmPattern.matches(opens)) {
        return "Opening time must be 24-hour HH:MM, e.g. 09:00."
    }
    val closes = closesAt.trim()
    if (closes.isNotEmpty() && !hhmmPattern.matches(closes)) {
        return "Closing time must be 24-hour HH:MM, e.g. 23:30."
    }
    val state = stateCode.trim()
    if (state.isNotEmpty() && !stateCodePattern.matches(state)) {
        return "GST state code must be two digits (Kerala is 32)."
    }
    val fssai = fssaiLicenseNo.trim()
    if (fssai.isNotEmpty() && !fssaiPattern.matches(fssai)) {
        return "FSSAI licence must be exactly 14 digits."
    }
    if (tradeLicenseNo.trim().length > 50) {
        return "Trade licence number must be 50 characters or fewer."
    }
    val gstin = branchGstin.trim()
    if (gstin.isNotEmpty() && gstin.length != 15) {
        return "Shop GSTIN must be exactly 15 characters."
    }
    return null
}

fun BranchForm.toBody() = BranchWriteBody(
    name = name.trim(),
    code = code.trim().uppercase().ifBlank { null },
    invoiceSeriesCode = normalizeInvoiceSeries(invoiceSeriesCode),
    address = address.trim().ifBlank { null },
    timezone = timezone.trim().ifBlank { null },
    opensAt = opensAt.trim().ifBlank { null },
    closesAt = closesAt.trim().ifBlank { null },
    stateCode = stateCode.trim().ifBlank { null },
    fssaiLicenseNo = fssaiLicenseNo.trim().ifBlank { null },
    tradeLicenseNo = tradeLicenseNo.trim().ifBlank { null },
    branchGstin = branchGstin.trim().uppercase().ifBlank { null },
)
