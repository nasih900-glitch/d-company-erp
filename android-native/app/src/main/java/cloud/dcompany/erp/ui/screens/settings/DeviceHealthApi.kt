package cloud.dcompany.erp.ui.screens.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/** Read-only, company-scoped existing support/settings contracts; never queued or persisted. */
internal interface DeviceHealthApi {
    @GET("client-installations")
    suspend fun installations(@Query("limit") limit:Int=50,@Query("offset") offset:Int=0): ReportedInstallationPage
    @GET("client-diagnostics/events")
    suspend fun incidents(@Query("event_type") eventType:String?=null,@Query("limit") limit:Int=50,@Query("offset") offset:Int=0): ReportedIncidentPage
    @GET("client-diagnostics/system-health")
    suspend fun health(): ReportedSystemHealth
}

@Serializable
internal data class ReportedInstallation(
    @SerialName("installation_id") val installationId:String,
    val platform:String,
    @SerialName("distribution_channel") val distributionChannel:String,
    @SerialName("version_name") val versionName:String,
    @SerialName("version_code") val versionCode:Int,
    @SerialName("pending_outbox_count") val pendingOutboxCount:Int,
    @SerialName("last_successful_sync_at") val lastSuccessfulSyncAt:String?=null,
    @SerialName("update_state") val updateState:String,
    @SerialName("update_error_code") val updateErrorCode:String?=null,
    @SerialName("last_seen_at") val lastSeenAt:String,
    @SerialName("is_stale") val isStale:Boolean,
    @SerialName("last_user_name") val lastUserName:String?=null,
    @SerialName("terminal_name") val terminalName:String?=null,
)
@Serializable
internal data class ReportedInstallationPage(
    @SerialName("server_time") val serverTime:String,
    @SerialName("stale_after_hours") val staleAfterHours:Int,
    val total:Int,val items:List<ReportedInstallation>,
)
@Serializable
internal data class ReportedIncident(
    val id:String,
    @SerialName("installation_id") val installationId:String,
    @SerialName("event_type") val eventType:String,
    val severity:String,
    @SerialName("occurred_at") val occurredAt:String,
    @SerialName("received_at") val receivedAt:String,
    @SerialName("version_name") val versionName:String,
    @SerialName("version_code") val versionCode:Int,
    val component:String,
    @SerialName("reason_code") val reasonCode:String,
    @SerialName("http_status") val httpStatus:Int?=null,
    @SerialName("duration_bucket") val durationBucket:String?=null,
    val connectivity:String,
    @SerialName("pending_outbox_count") val pendingOutboxCount:Int?=null,
)
@Serializable
internal data class ReportedIncidentPage(val items:List<ReportedIncident>,val total:Int,val limit:Int,val offset:Int,
    @SerialName("retention_days") val retentionDays:Int)
@Serializable
internal data class ReportedHealthDevices(val total:Int,
    @SerialName("seen_last_24h") val recent:Int,
    val stale:Int,
    @SerialName("with_pending_sync") val withPending:Int,
    @SerialName("outdated_installations") val outdatedRecent:Int,
    @SerialName("stale_with_last_reported_pending") val stalePending:Int,
)
@Serializable
internal data class ReportedHealthDependencies(val api:String,val database:String,val redis:String)
@Serializable
internal data class ReportedHealthBackups(val status:String,
    @SerialName("last_success_at") val lastSuccessAt:String?=null,
    @SerialName("restore_tested_at") val restoreTestedAt:String?=null,
    @SerialName("evidence_code") val evidenceCode:String)
@Serializable
internal data class ReportedSystemHealth(val status:String,
    @SerialName("server_time") val serverTime:String,
    val dependencies:ReportedHealthDependencies,val backups:ReportedHealthBackups,
    val devices:ReportedHealthDevices,val recommendations:List<String>)

/** These OS records retain the importing build, not the historical process's actual build. */
private val importedExitReasons=setOf("process_anr","java_crash_exit","native_crash_exit","low_memory_exit","excessive_resource_exit")
private val capturedJavaReasons=setOf("out_of_memory","stack_overflow","security_exception","illegal_state",
    "null_pointer","concurrent_modification","unhandled_java_exception")
internal fun ReportedIncident.buildEvidence():String = when {
    reasonCode in importedExitReasons -> "Observed/imported by build $versionCode · $versionName. Actual incident build was not retained."
    eventType=="crash" && reasonCode in capturedJavaReasons -> "Captured event build $versionCode · $versionName"
    eventType in setOf("crash","anr") -> "Reported build $versionCode · $versionName. Actual incident build provenance is unavailable."
    else -> "Reporting build $versionCode · $versionName"
}
internal fun ReportedHealthDevices.coverageEvidence():String = when {
    total==0 -> "No reporting tablets · coverage unknown"
    recent==0 -> "No recent tablet reports · current status unknown"
    outdatedRecent>0 -> "$outdatedRecent of $recent recently reporting tablets reported an older supported build"
    else -> "$recent recently reporting tablets reported supported builds; stale tablets remain unknown"
}
