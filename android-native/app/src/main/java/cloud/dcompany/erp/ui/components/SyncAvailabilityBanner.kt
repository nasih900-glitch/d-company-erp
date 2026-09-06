package cloud.dcompany.erp.ui.components

import cloud.dcompany.erp.core.net.BackendReachability
import cloud.dcompany.erp.core.net.ConnectivityPhase

enum class SyncAvailabilityProblem {
    NONE,
    VERIFYING,
    NO_NETWORK,
    SERVER_UNREACHABLE,
    RECOVERING,
}

internal data class SyncAvailabilityCopy(
    val title: String,
    val detail: String,
)

internal fun syncAvailabilityProblem(
    networkValidated: Boolean,
    backendReachability: BackendReachability,
): SyncAvailabilityProblem = when {
    !networkValidated -> SyncAvailabilityProblem.NO_NETWORK
    backendReachability == BackendReachability.UNREACHABLE ->
        SyncAvailabilityProblem.SERVER_UNREACHABLE
    backendReachability == BackendReachability.UNKNOWN -> SyncAvailabilityProblem.VERIFYING
    else -> SyncAvailabilityProblem.NONE
}

internal fun syncAvailabilityProblem(phase: ConnectivityPhase): SyncAvailabilityProblem = when (phase) {
    ConnectivityPhase.NO_NETWORK -> SyncAvailabilityProblem.NO_NETWORK
    ConnectivityPhase.VERIFYING -> SyncAvailabilityProblem.VERIFYING
    ConnectivityPhase.SERVER_UNREACHABLE -> SyncAvailabilityProblem.SERVER_UNREACHABLE
    ConnectivityPhase.RECOVERING -> SyncAvailabilityProblem.RECOVERING
    ConnectivityPhase.ONLINE -> SyncAvailabilityProblem.NONE
}

internal fun syncAvailabilityCopy(problem: SyncAvailabilityProblem): SyncAvailabilityCopy? = when (problem) {
    SyncAvailabilityProblem.NONE -> null
    SyncAvailabilityProblem.VERIFYING -> SyncAvailabilityCopy(
        title = "Checking the ERP connection",
        detail = "The tablet is verifying the server before it sends saved work. You can keep " +
            "viewing saved data; online actions resume automatically after verification.",
    )
    SyncAvailabilityProblem.NO_NETWORK -> SyncAvailabilityCopy(
        title = "Offline — no internet connection",
        detail = "You are viewing saved data. Any offline-capable change is saved locally, " +
            "shown as waiting to sync, and sent automatically when the connection returns.",
    )
    SyncAvailabilityProblem.SERVER_UNREACHABLE -> SyncAvailabilityCopy(
        title = "ERP server unavailable",
        detail = "Internet is connected, but the ERP server cannot be reached. You are viewing " +
            "saved data. Any offline-capable change is saved locally, shown as waiting to sync, " +
            "and sent automatically after the server reconnects.",
    )
    SyncAvailabilityProblem.RECOVERING -> SyncAvailabilityCopy(
        title = "Connection restored — checking saved work",
        detail = "The ERP server is responding again. The tablet is completing a short safety " +
            "check, then saved work will synchronise automatically. Do not repeat a payment.",
    )
}

internal fun syncAvailabilityDialogTitle(problem: SyncAvailabilityProblem): String =
    syncAvailabilityCopy(problem)?.title ?: "Connected to the ERP server"
