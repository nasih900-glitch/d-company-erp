package cloud.dcompany.erp.ui.screens.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.ZoneId

/**
 * Wire models copied field-for-field from the FastAPI schemas rather than
 * guessed — a wrong name here fails at runtime, not compile time:
 *
 *   CompanyRead / CompanyUpdate / BranchRead / BranchCreate / BranchUpdate /
 *   TerminalRead / TerminalCreate   backend/app/api/v1/settings/router.py
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
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
data class TerminalCreateBody(
    @SerialName("branch_id") val branchId: String,
    val name: String,
    @SerialName("device_id") val deviceId: String? = null,
)

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

/** Word-for-word the backend's own message, so both paths read identically. */
const val TIMEZONE_MESSAGE = "timezone must be a valid IANA name like Asia/Kolkata"

fun isIanaTimezone(value: String): Boolean = value.trim() in ianaZoneIds

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

val gstRegistrationTypes = listOf("regular", "composition", "unregistered", "sez")

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
    if (name.isBlank()) return "Branch name cannot be empty."
    if (name.trim().length > 200) return "Branch name must be 200 characters or fewer."
    if (code.trim().length > 10) return "Short code must be 10 characters or fewer."
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
        return "Branch GSTIN must be exactly 15 characters."
    }
    return null
}

fun BranchForm.toBody() = BranchWriteBody(
    name = name.trim(),
    code = code.trim().uppercase().ifBlank { null },
    address = address.trim().ifBlank { null },
    timezone = timezone.trim().ifBlank { null },
    opensAt = opensAt.trim().ifBlank { null },
    closesAt = closesAt.trim().ifBlank { null },
    stateCode = stateCode.trim().ifBlank { null },
    fssaiLicenseNo = fssaiLicenseNo.trim().ifBlank { null },
    tradeLicenseNo = tradeLicenseNo.trim().ifBlank { null },
    branchGstin = branchGstin.trim().uppercase().ifBlank { null },
)
