package cloud.dcompany.erp.core.net

import java.time.Instant

const val OFFLINE_CAPTURED_HEADER = "X-Offline-Captured"
const val CLIENT_OCCURRED_AT_HEADER = "X-Client-Occurred-At"
const val CLIENT_ACTION_ID_HEADER = "X-Client-Action-Id"

/**
 * Explicit headers for one persisted outbox action. Callers must pass this map
 * only from SyncEngine replay paths; ordinary live actions and reads use no
 * provenance headers and remain distinguishable in the server audit trail.
 */
fun outboxProvenanceHeaders(
    occurredAtMillis: Long?,
    actionId: String,
): Map<String, String> = buildMap {
    put(OFFLINE_CAPTURED_HEADER, "true")
    occurredAtMillis
        ?.takeIf { it >= 0L }
        ?.let { millis -> put(CLIENT_OCCURRED_AT_HEADER, Instant.ofEpochMilli(millis).toString()) }
    actionId.trim().takeIf { it.isNotEmpty() }?.let { put(CLIENT_ACTION_ID_HEADER, it) }
}
