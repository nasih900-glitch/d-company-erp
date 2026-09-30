package cloud.dcompany.erp.ui.screens.shift

import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.update.isCanonicalRandomUuidV4

internal data class ShiftCloseOriginDecision(
    val allowed: Boolean,
    val message: String? = null,
)

/** Opener attribution never grants or removes another authorised employee's access. */
internal fun shiftCloseOriginDecision(
    shift: ResolvedOpenShift,
    currentInstallationId: String?,
): ShiftCloseOriginDecision {
    val server = shift.server
    val local = shift.local
    val installation = currentInstallationId?.takeIf(::isCanonicalRandomUuidV4)
    val echoed = server?.openingClientInstallationId?.takeIf(::isCanonicalRandomUuidV4)
    if (server?.openingClientPlatform == "android" && installation != null && echoed == installation) {
        return ShiftCloseOriginDecision(true)
    }
    val knownOriginRejection = local?.lastError?.let { error ->
        error.contains("This Android app installation is not verified as the one that opened this shift") ||
            error.contains("cannot be closed from this browser or a different app installation")
    } == true
    if (knownOriginRejection || (echoed != null && echoed != installation)) {
        return ShiftCloseOriginDecision(false, ANDROID_SHIFT_ORIGIN_GUIDANCE)
    }
    // Original local rows must retain the Code21 causal-key close path. An
    // absent echo cannot prove they came from another installation. Failed
    // adopted rows are stopped above when the server definitively rejected them.
    if (local != null) return ShiftCloseOriginDecision(true)
    return when (server?.openingClientPlatform) {
        "web", "ios" -> ShiftCloseOriginDecision(true)
        "android" -> if (installation != null && echoed == installation) {
            ShiftCloseOriginDecision(true)
        } else {
            ShiftCloseOriginDecision(false, ANDROID_SHIFT_ORIGIN_GUIDANCE)
        }
        // Pre-protocol server shifts have no client-platform fact and remain
        // closable through the backend's legacy path. Do not invent an origin
        // or turn the new Android gate into a permanent historical-shift lock.
        else -> ShiftCloseOriginDecision(true)
    }
}

internal const val ANDROID_SHIFT_ORIGIN_GUIDANCE =
    "This app installation is not verified as the one that opened this Android shift. Reconnect and refresh Shift first. Any staff member with Shift close access may close it on the verified originating installation. If the app was reinstalled or its identity changed, ask the protected owner to use Recover Android shift in Web ERP after isolating the originating app and reviewing saved work. No new drawer count will be queued until origin is verified."
