package cloud.dcompany.erp.core.update

import android.content.Context
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.TokenStore
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.net.ApiClient
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import retrofit2.http.Body
import retrofit2.http.POST

private const val UPDATE_QUEUE_FORMAT = 1
private const val MAX_STORED_UPDATE_EVENTS = 100
private const val MAX_HEARTBEAT_EVENTS = 20
private const val MAX_REPORTED_OUTBOX_COUNT = 1_000_000

internal enum class UpdateRuntimeState(val wireValue: String) {
    IDLE("idle"),
    UPDATE_AVAILABLE("update_available"),
    DOWNLOADING("downloading"),
    VERIFYING("verifying"),
    VERIFIED("verified"),
    INSTALLER_OPENED("installer_opened"),
    FAILED("failed"),
}

internal enum class UpdateEventType(val wireValue: String) {
    UPDATE_OFFERED("update_offered"),
    DOWNLOAD_STARTED("download_started"),
    DOWNLOAD_VERIFIED("download_verified"),
    INSTALLER_OPENED("installer_opened"),
    UPGRADE_CONFIRMED("upgrade_confirmed"),
    UPDATE_CANCELLED("update_cancelled"),
    UPDATE_FAILED("update_failed"),
}

internal enum class UpdateErrorCode(val wireValue: String) {
    NETWORK_ERROR("network_error"),
    HTTP_ERROR("http_error"),
    INSUFFICIENT_STORAGE("insufficient_storage"),
    INVALID_METADATA("invalid_metadata"),
    SIZE_MISMATCH("size_mismatch"),
    CHECKSUM_MISMATCH("checksum_mismatch"),
    ARCHIVE_UNREADABLE("archive_unreadable"),
    PACKAGE_MISMATCH("package_mismatch"),
    VERSION_MISMATCH("version_mismatch"),
    SIGNER_MISMATCH("signer_mismatch"),
    INSTALLER_PERMISSION_DENIED("installer_permission_denied"),
    INSTALLER_UNAVAILABLE("installer_unavailable"),
    INSTALLER_NOT_COMPLETED("installer_not_completed"),
    UNKNOWN("unknown"),
}

/**
 * Local-only tenant binding. It is deliberately omitted from the wire DTO:
 * the authenticated backend derives tenant, user and terminal authority.
 */
@Serializable
internal data class UpdateTelemetryBinding(
    val userId: String,
    val companyId: String,
    val branchId: String? = null,
    val terminalId: String? = null,
) {
    init {
        require(userId.isNotBlank())
        require(companyId.isNotBlank())
    }

    companion object {
        fun from(scope: CacheScope): UpdateTelemetryBinding = UpdateTelemetryBinding(
            userId = scope.userId,
            companyId = scope.companyId,
            branchId = scope.branchId,
            terminalId = scope.terminalId,
        )
    }
}

@Serializable
internal data class QueuedUpdateEvent(
    val clientEventId: String,
    val eventType: String,
    val targetVersionName: String,
    val targetVersionCode: Int,
    val errorCode: String? = null,
    val occurredAtMillis: Long,
    val binding: UpdateTelemetryBinding,
    /** Used only to avoid recreating one-shot events after a process death. */
    val dedupeKey: String? = null,
)

@Serializable
private data class QueuedUpdateEventEnvelope(
    val format: Int = UPDATE_QUEUE_FORMAT,
    val events: List<QueuedUpdateEvent> = emptyList(),
)

@Serializable
internal data class ClientUpdateEventRequest(
    @SerialName("client_event_id") val clientEventId: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("target_version_name") val targetVersionName: String,
    @SerialName("target_version_code") val targetVersionCode: Int,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
)

@Serializable
internal data class ClientInstallationHeartbeatRequest(
    @SerialName("installation_id") val installationId: String,
    val platform: String,
    @SerialName("distribution_channel") val distributionChannel: String,
    @SerialName("version_name") val versionName: String,
    @SerialName("version_code") val versionCode: Int,
    @SerialName("pending_outbox_count") val pendingOutboxCount: Int,
    @SerialName("last_successful_sync_at") val lastSuccessfulSyncAt: String? = null,
    @SerialName("update_state") val updateState: String,
    @SerialName("update_error_code") val updateErrorCode: String? = null,
    val events: List<ClientUpdateEventRequest>,
)

@Serializable
internal data class ClientInstallationHeartbeatResponse(
    @SerialName("installation_id") val installationId: String,
    @SerialName("terminal_id") val terminalId: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String,
    @SerialName("accepted_event_count") val acceptedEventCount: Int,
    @SerialName("duplicate_event_count") val duplicateEventCount: Int,
)

internal interface ClientInstallationApi {
    @POST("client-installations/heartbeat")
    suspend fun heartbeat(
        @Body body: ClientInstallationHeartbeatRequest,
    ): ClientInstallationHeartbeatResponse
}

internal data class UpdateQueueSelection(
    val retained: List<QueuedUpdateEvent>,
    val batch: List<QueuedUpdateEvent>,
    val droppedCount: Int,
)

/** Pure policy used by the durable store and JVM tests. */
internal fun selectEventsForScope(
    events: List<QueuedUpdateEvent>,
    binding: UpdateTelemetryBinding,
    limit: Int = MAX_HEARTBEAT_EVENTS,
): UpdateQueueSelection {
    require(limit in 1..MAX_HEARTBEAT_EVENTS)
    val valid = events.filter(::validQueuedUpdateEvent)
    val retained = valid.filter { it.binding == binding }
    return UpdateQueueSelection(
        retained = retained,
        batch = retained.take(limit),
        droppedCount = events.size - retained.size,
    )
}

internal fun appendBoundedUpdateEvent(
    existing: List<QueuedUpdateEvent>,
    event: QueuedUpdateEvent,
    maximum: Int = MAX_STORED_UPDATE_EVENTS,
): List<QueuedUpdateEvent> {
    require(maximum > 0)
    if (!validQueuedUpdateEvent(event)) return existing.takeLast(maximum)
    if (existing.any { it.clientEventId == event.clientEventId }) return existing.takeLast(maximum)
    if (event.dedupeKey != null && existing.any {
            it.binding == event.binding && it.dedupeKey == event.dedupeKey
        }
    ) return existing.takeLast(maximum)
    return (existing + event).takeLast(maximum)
}

internal fun isCanonicalRandomUuidV4(raw: String): Boolean {
    val parsed = runCatching { UUID.fromString(raw) }.getOrNull() ?: return false
    return parsed.version() == 4 && parsed.toString() == raw.lowercase()
}

private fun validQueuedUpdateEvent(event: QueuedUpdateEvent): Boolean {
    if (!isCanonicalRandomUuidV4(event.clientEventId)) return false
    if (event.eventType !in UpdateEventType.entries.map { it.wireValue }) return false
    if (event.targetVersionCode <= 0) return false
    if (!isValidUpdateVersionName(event.targetVersionName)) return false
    if (event.occurredAtMillis <= 0) return false
    val validErrorCodes = UpdateErrorCode.entries.map { it.wireValue }
    return if (event.eventType == UpdateEventType.UPDATE_FAILED.wireValue) {
        event.errorCode in validErrorCodes
    } else {
        event.errorCode == null
    }
}

private class UpdateEventQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun enqueue(event: QueuedUpdateEvent): Boolean = synchronized(lock) {
        val before = readLocked()
        val after = appendBoundedUpdateEvent(before, event)
        if (after == before) {
            // An exact/deduplicated event is already durable, which is enough
            // to clear the corresponding one-shot pending marker.
            return@synchronized before.any {
                it.clientEventId == event.clientEventId ||
                    (event.dedupeKey != null &&
                        it.binding == event.binding &&
                        it.dedupeKey == event.dedupeKey)
            }
        }
        writeLocked(after)
    }

    fun selectAndDropForeign(binding: UpdateTelemetryBinding): List<QueuedUpdateEvent> =
        synchronized(lock) {
            val before = readLocked()
            val selection = selectEventsForScope(before, binding)
            if (selection.retained != before) writeLocked(selection.retained)
            selection.batch
        }

    fun acknowledge(clientEventIds: Set<String>) = synchronized(lock) {
        if (clientEventIds.isEmpty()) return@synchronized
        val before = readLocked()
        val after = before.filterNot { it.clientEventId in clientEventIds }
        if (after != before) writeLocked(after)
    }

    fun runtimeState(binding: UpdateTelemetryBinding): Pair<UpdateRuntimeState, UpdateErrorCode?> = synchronized(lock) {
        val storedBinding = prefs.getString(KEY_RUNTIME_BINDING, null)?.let { raw ->
            runCatching { ApiClient.json.decodeFromString<UpdateTelemetryBinding>(raw) }.getOrNull()
        }
        if (storedBinding != binding) {
            prefs.edit()
                .remove(KEY_RUNTIME_STATE)
                .remove(KEY_RUNTIME_ERROR)
                .remove(KEY_RUNTIME_BINDING)
                .commit()
            return@synchronized UpdateRuntimeState.IDLE to null
        }
        val state = prefs.getString(KEY_RUNTIME_STATE, null)
            ?.let { raw -> UpdateRuntimeState.entries.firstOrNull { it.wireValue == raw } }
            ?: UpdateRuntimeState.IDLE
        val error = prefs.getString(KEY_RUNTIME_ERROR, null)
            ?.let { raw -> UpdateErrorCode.entries.firstOrNull { it.wireValue == raw } }
        if (state == UpdateRuntimeState.FAILED && error == null) {
            UpdateRuntimeState.FAILED to UpdateErrorCode.UNKNOWN
        } else {
            state to if (state == UpdateRuntimeState.FAILED) error else null
        }
    }

    fun setRuntimeState(
        state: UpdateRuntimeState,
        error: UpdateErrorCode? = null,
        binding: UpdateTelemetryBinding?,
    ): Boolean {
        require((state == UpdateRuntimeState.FAILED) == (error != null)) {
            "Only a failed update state may carry an error code"
        }
        return synchronized(lock) {
            if (binding == null) return@synchronized false
            prefs.edit()
                .putString(KEY_RUNTIME_STATE, state.wireValue)
                .putString(KEY_RUNTIME_BINDING, ApiClient.json.encodeToString(binding))
                .apply {
                    if (error == null) remove(KEY_RUNTIME_ERROR)
                    else putString(KEY_RUNTIME_ERROR, error.wireValue)
                }
                .commit()
        }
    }

    fun clearRuntimeState(): Boolean = synchronized(lock) {
        prefs.edit()
            .remove(KEY_RUNTIME_STATE)
            .remove(KEY_RUNTIME_ERROR)
            .remove(KEY_RUNTIME_BINDING)
            .commit()
    }

    private fun readLocked(): List<QueuedUpdateEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        val decoded = runCatching {
            ApiClient.json.decodeFromString<QueuedUpdateEventEnvelope>(raw)
        }.getOrNull() ?: return emptyList()
        if (decoded.format != UPDATE_QUEUE_FORMAT) return emptyList()
        return decoded.events.filter(::validQueuedUpdateEvent).takeLast(MAX_STORED_UPDATE_EVENTS)
    }

    private fun writeLocked(events: List<QueuedUpdateEvent>): Boolean = prefs.edit()
        .putString(
            KEY_EVENTS,
            ApiClient.json.encodeToString(QueuedUpdateEventEnvelope(events = events)),
        )
        .commit()

    private companion object {
        const val PREFS_NAME = "dcompany_update_events"
        const val KEY_EVENTS = "events"
        const val KEY_RUNTIME_STATE = "runtime_state"
        const val KEY_RUNTIME_ERROR = "runtime_error"
        const val KEY_RUNTIME_BINDING = "runtime_binding"
    }
}

internal data class PendingUpgrade(
    val targetVersionName: String,
    val targetVersionCode: Int,
)

internal data class PendingInstallerAttempt(
    val targetVersionName: String,
    val targetVersionCode: Int,
    val openedAtMillis: Long,
    val binding: UpdateTelemetryBinding,
)

internal fun shouldRecordUpgrade(
    previouslyObservedVersionCode: Int,
    currentVersionCode: Int,
    installedOverExistingApp: Boolean,
): Boolean {
    require(previouslyObservedVersionCode >= 0)
    require(currentVersionCode > 0)
    return previouslyObservedVersionCode in 1 until currentVersionCode ||
        (previouslyObservedVersionCode == 0 && installedOverExistingApp)
}

/**
 * A random app-installation identity and update lifecycle markers. No Android
 * hardware identifier, advertising identifier, email or bearer is collected.
 */
internal class InstallationIdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun installationId(): String? = synchronized(lock) {
        prefs.getString(KEY_INSTALLATION_ID, null)
            ?.takeIf(::isUuid)
            ?: UUID.randomUUID().toString().also { generated ->
                if (!prefs.edit().putString(KEY_INSTALLATION_ID, generated).commit()) {
                    return@synchronized null
                }
            }
    }

    fun observeInstalledVersion(
        currentVersionCode: Int,
        currentVersionName: String,
        installedOverExistingApp: Boolean,
    ): Boolean = synchronized(lock) {
        require(currentVersionCode > 0)
        require(isValidUpdateVersionName(currentVersionName))
        val prior = prefs.getInt(KEY_OBSERVED_VERSION_CODE, 0)
        val isUpgrade = shouldRecordUpgrade(prior, currentVersionCode, installedOverExistingApp)
        val editor = prefs.edit()
            .putInt(KEY_OBSERVED_VERSION_CODE, currentVersionCode)
            .putString(KEY_OBSERVED_VERSION_NAME, currentVersionName)
        if (isUpgrade) {
            editor
                .putInt(KEY_PENDING_UPGRADE_CODE, currentVersionCode)
                .putString(KEY_PENDING_UPGRADE_NAME, currentVersionName)
        } else if (prior > currentVersionCode) {
            // A downgrade is never reported as a successful upgrade.
            editor.remove(KEY_PENDING_UPGRADE_CODE).remove(KEY_PENDING_UPGRADE_NAME)
        }
        editor.commit()
    }

    fun pendingUpgrade(): PendingUpgrade? = synchronized(lock) {
        val code = prefs.getInt(KEY_PENDING_UPGRADE_CODE, 0).takeIf { it > 0 } ?: return@synchronized null
        val name = prefs.getString(KEY_PENDING_UPGRADE_NAME, null)
            ?.takeIf(::isValidUpdateVersionName)
            ?: return@synchronized null
        PendingUpgrade(name, code)
    }

    fun clearPendingUpgrade(targetVersionCode: Int): Boolean = synchronized(lock) {
        if (prefs.getInt(KEY_PENDING_UPGRADE_CODE, 0) != targetVersionCode) return@synchronized false
        prefs.edit().remove(KEY_PENDING_UPGRADE_CODE).remove(KEY_PENDING_UPGRADE_NAME).commit()
    }

    fun rememberInstallerAttempt(
        descriptor: DirectUpdateDescriptor,
        binding: UpdateTelemetryBinding,
        openedAtMillis: Long,
    ): Boolean = synchronized(lock) {
        prefs.edit()
            .putInt(KEY_INSTALLER_TARGET_CODE, descriptor.versionCode)
            .putString(KEY_INSTALLER_TARGET_NAME, descriptor.versionName)
            .putLong(KEY_INSTALLER_OPENED_AT, openedAtMillis)
            .putString(KEY_INSTALLER_BINDING, ApiClient.json.encodeToString(binding))
            .commit()
    }

    fun pendingInstallerAttempt(): PendingInstallerAttempt? = synchronized(lock) {
        val code = prefs.getInt(KEY_INSTALLER_TARGET_CODE, 0).takeIf { it > 0 }
            ?: return@synchronized null
        val name = prefs.getString(KEY_INSTALLER_TARGET_NAME, null)
            ?.takeIf(::isValidUpdateVersionName)
            ?: return@synchronized null
        val openedAt = prefs.getLong(KEY_INSTALLER_OPENED_AT, 0L).takeIf { it > 0 }
            ?: return@synchronized null
        val binding = prefs.getString(KEY_INSTALLER_BINDING, null)?.let { raw ->
            runCatching { ApiClient.json.decodeFromString<UpdateTelemetryBinding>(raw) }.getOrNull()
        } ?: return@synchronized null
        PendingInstallerAttempt(name, code, openedAt, binding)
    }

    fun clearInstallerAttempt(targetVersionCode: Int): Boolean = synchronized(lock) {
        if (prefs.getInt(KEY_INSTALLER_TARGET_CODE, 0) != targetVersionCode) return@synchronized false
        prefs.edit()
            .remove(KEY_INSTALLER_TARGET_CODE)
            .remove(KEY_INSTALLER_TARGET_NAME)
            .remove(KEY_INSTALLER_OPENED_AT)
            .remove(KEY_INSTALLER_BINDING)
            .commit()
    }

    private fun isUuid(raw: String): Boolean = isCanonicalRandomUuidV4(raw)

    private companion object {
        const val PREFS_NAME = "dcompany_installation_identity"
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_OBSERVED_VERSION_CODE = "observed_version_code"
        const val KEY_OBSERVED_VERSION_NAME = "observed_version_name"
        const val KEY_PENDING_UPGRADE_CODE = "pending_upgrade_code"
        const val KEY_PENDING_UPGRADE_NAME = "pending_upgrade_name"
        const val KEY_INSTALLER_TARGET_CODE = "installer_target_code"
        const val KEY_INSTALLER_TARGET_NAME = "installer_target_name"
        const val KEY_INSTALLER_OPENED_AT = "installer_opened_at"
        const val KEY_INSTALLER_BINDING = "installer_binding"
    }
}

/**
 * Best-effort installation reporting. This queue is intentionally separate
 * from Room: telemetry failure can never block, delete or rewrite a financial
 * outbox row.
 */
internal class UpdateTelemetryCoordinator(
    context: Context,
    private val tokens: TokenStore,
    private val cacheIsolation: CacheIsolationCoordinator,
    private val db: ErpDatabase,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val queue = UpdateEventQueueStore(context.applicationContext)
    val installation = InstallationIdentityStore(context.applicationContext)
    private val heartbeatMutex = Mutex()

    fun currentBinding(): UpdateTelemetryBinding? = cacheIsolation.currentLease()
        ?.scope
        ?.let(UpdateTelemetryBinding::from)

    fun recordOffered(descriptor: DirectUpdateDescriptor, binding: UpdateTelemetryBinding? = currentBinding()) {
        recordOffered(descriptor.versionName, descriptor.versionCode, binding)
    }

    fun recordOffered(
        targetVersionName: String,
        targetVersionCode: Int,
        binding: UpdateTelemetryBinding? = currentBinding(),
    ) {
        queue.setRuntimeState(UpdateRuntimeState.UPDATE_AVAILABLE, binding = binding)
        if (targetVersionCode <= 0 || !isValidUpdateVersionName(targetVersionName)) return
        record(
            type = UpdateEventType.UPDATE_OFFERED,
            targetVersionName = targetVersionName,
            targetVersionCode = targetVersionCode,
            binding = binding,
            dedupeKey = "offer:$targetVersionCode",
        )
    }

    fun recordDownloadStarted(descriptor: DirectUpdateDescriptor, binding: UpdateTelemetryBinding?) {
        queue.setRuntimeState(UpdateRuntimeState.DOWNLOADING, binding = binding)
        record(UpdateEventType.DOWNLOAD_STARTED, descriptor, binding)
    }

    fun recordVerifying(binding: UpdateTelemetryBinding?) {
        queue.setRuntimeState(UpdateRuntimeState.VERIFYING, binding = binding)
    }

    fun recordDownloadVerified(descriptor: DirectUpdateDescriptor, binding: UpdateTelemetryBinding?) {
        queue.setRuntimeState(UpdateRuntimeState.VERIFIED, binding = binding)
        record(UpdateEventType.DOWNLOAD_VERIFIED, descriptor, binding)
    }

    fun restoreVerifiedState(binding: UpdateTelemetryBinding?) {
        queue.setRuntimeState(UpdateRuntimeState.VERIFIED, binding = binding)
    }

    fun recordInstallerOpened(descriptor: DirectUpdateDescriptor, binding: UpdateTelemetryBinding?) {
        queue.setRuntimeState(UpdateRuntimeState.INSTALLER_OPENED, binding = binding)
        if (binding == null || binding != currentBinding()) return
        if (!installation.rememberInstallerAttempt(descriptor, binding, clockMillis())) return
        record(UpdateEventType.INSTALLER_OPENED, descriptor, binding)
    }

    fun recordCancelled(descriptor: DirectUpdateDescriptor, binding: UpdateTelemetryBinding?) {
        queue.setRuntimeState(UpdateRuntimeState.UPDATE_AVAILABLE, binding = binding)
        record(UpdateEventType.UPDATE_CANCELLED, descriptor, binding)
    }

    fun recordFailed(
        descriptor: DirectUpdateDescriptor?,
        fallbackVersionName: String = BuildConfig.VERSION_NAME,
        fallbackVersionCode: Int = BuildConfig.VERSION_CODE,
        errorCode: UpdateErrorCode,
        binding: UpdateTelemetryBinding? = currentBinding(),
    ) {
        queue.setRuntimeState(UpdateRuntimeState.FAILED, errorCode, binding)
        record(
            type = UpdateEventType.UPDATE_FAILED,
            targetVersionName = descriptor?.versionName
                ?: fallbackVersionName.takeIf(::isValidUpdateVersionName)
                ?: BuildConfig.VERSION_NAME,
            targetVersionCode = descriptor?.versionCode ?: fallbackVersionCode.coerceAtLeast(1),
            binding = binding,
            errorCode = errorCode,
        )
    }

    fun recordIdle() {
        queue.clearRuntimeState()
    }

    /** Called after a verified workspace is active; pending upgrade has no tenant until then. */
    fun promotePendingUpgrade() {
        val binding = currentBinding() ?: return
        promotePendingUpgrade(binding)
    }

    private fun promotePendingUpgrade(binding: UpdateTelemetryBinding) {
        val pending = installation.pendingUpgrade() ?: return
        if (binding != currentBinding()) return
        val queued = record(
            type = UpdateEventType.UPGRADE_CONFIRMED,
            targetVersionName = pending.targetVersionName,
            targetVersionCode = pending.targetVersionCode,
            binding = binding,
            dedupeKey = "upgrade:${pending.targetVersionCode}",
        )
        if (queued) installation.clearPendingUpgrade(pending.targetVersionCode)
    }

    /** A resumed unchanged build means Android's confirmation flow did not complete. */
    fun reconcileInstallerReturn(minimumAgeMillis: Long = 500L) {
        val pending = installation.pendingInstallerAttempt() ?: return
        if (pending.targetVersionCode <= BuildConfig.VERSION_CODE) {
            installation.clearInstallerAttempt(pending.targetVersionCode)
            return
        }
        if (clockMillis() - pending.openedAtMillis < minimumAgeMillis) return
        val current = currentBinding() ?: return
        if (current != pending.binding) {
            // Never relabel an old workspace's installer action with the new
            // user's bearer. The stale action is intentionally discarded.
            installation.clearInstallerAttempt(pending.targetVersionCode)
            return
        }
        queue.setRuntimeState(UpdateRuntimeState.UPDATE_AVAILABLE, binding = pending.binding)
        record(
            type = UpdateEventType.UPDATE_CANCELLED,
            targetVersionName = pending.targetVersionName,
            targetVersionCode = pending.targetVersionCode,
            binding = pending.binding,
            dedupeKey = "installer-cancel:${pending.targetVersionCode}:${pending.openedAtMillis}",
        )
        installation.clearInstallerAttempt(pending.targetVersionCode)
    }

    suspend fun heartbeat() = heartbeatMutex.withLock {
        val cacheLease = cacheIsolation.currentLease() ?: return@withLock
        val tokenLease = tokens.refreshLease() ?: return@withLock
        val binding = UpdateTelemetryBinding.from(cacheLease.scope)
        promotePendingUpgrade(binding)
        val events = queue.selectAndDropForeign(binding)

        val pendingOutbox = try {
            db.outboxSafetyDao().unresolvedGroups().sumOf { it.count.toLong() }
                .coerceIn(0L, MAX_REPORTED_OUTBOX_COUNT.toLong())
                .toInt()
        } catch (_: Exception) {
            // Zero means positively clear. If Room cannot answer, omit this
            // optional heartbeat rather than publishing a false clean queue.
            return@withLock
        }
        val lastSync = runCatching { db.syncMetaDao().latestSuccessfulSyncMillis() }.getOrNull()
            ?.takeIf { it > 0 }
            ?.let { Instant.ofEpochMilli(it).toString() }

        // Scope and login can change while Room is read. Capture the current
        // bearer only after those reads and refuse to send if either lease is stale.
        if (cacheIsolation.currentLease() != cacheLease || !tokens.isCurrent(tokenLease)) {
            return@withLock
        }
        val access = tokens.currentAccessFor(tokenLease) ?: return@withLock
        val (runtimeState, runtimeError) = queue.runtimeState(binding)
        val installationId = installation.installationId() ?: return@withLock
        val request = ClientInstallationHeartbeatRequest(
            installationId = installationId,
            platform = "android",
            distributionChannel = BuildConfig.DISTRIBUTION_CHANNEL,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            pendingOutboxCount = pendingOutbox,
            lastSuccessfulSyncAt = lastSync,
            updateState = runtimeState.wireValue,
            updateErrorCode = runtimeError?.wireValue,
            events = events.map { event ->
                ClientUpdateEventRequest(
                    clientEventId = event.clientEventId,
                    eventType = event.eventType,
                    targetVersionName = event.targetVersionName,
                    targetVersionCode = event.targetVersionCode,
                    errorCode = event.errorCode,
                    occurredAt = Instant.ofEpochMilli(event.occurredAtMillis).toString(),
                )
            },
        )

        try {
            val api = ApiClient.createEphemeralAuthorityApi<ClientInstallationApi>(
                accessToken = access,
                terminalId = binding.terminalId,
            )
            val response = api.heartbeat(request)
            if (response.installationId != request.installationId) return@withLock
            val handled = response.acceptedEventCount + response.duplicateEventCount
            if (handled == events.size) {
                queue.acknowledge(events.mapTo(linkedSetOf()) { it.clientEventId })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Optional telemetry never changes auth, compatibility authority,
            // update readiness or the financial outbox.
        }
    }

    private fun record(
        type: UpdateEventType,
        descriptor: DirectUpdateDescriptor,
        binding: UpdateTelemetryBinding?,
        errorCode: UpdateErrorCode? = null,
        dedupeKey: String? = null,
    ): Boolean = record(
        type = type,
        targetVersionName = descriptor.versionName,
        targetVersionCode = descriptor.versionCode,
        binding = binding,
        errorCode = errorCode,
        dedupeKey = dedupeKey,
    )

    private fun record(
        type: UpdateEventType,
        targetVersionName: String,
        targetVersionCode: Int,
        binding: UpdateTelemetryBinding?,
        errorCode: UpdateErrorCode? = null,
        dedupeKey: String? = null,
    ): Boolean {
        // The binding belongs to the action's originating workspace. If that
        // workspace has changed, discard instead of relabelling it with the
        // newly logged-in employee's bearer.
        if (binding == null || binding != currentBinding()) return false
        return queue.enqueue(
            QueuedUpdateEvent(
                clientEventId = UUID.randomUUID().toString(),
                eventType = type.wireValue,
                targetVersionName = targetVersionName,
                targetVersionCode = targetVersionCode,
                errorCode = errorCode?.wireValue,
                occurredAtMillis = clockMillis(),
                binding = binding,
                dedupeKey = dedupeKey,
            ),
        )
    }
}
