package cloud.dcompany.erp.ui.screens.settings

import android.os.Build
import cloud.dcompany.erp.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

internal const val BUG_REPORT_DESCRIPTION_MIN_LENGTH = 10
internal const val BUG_REPORT_DETAIL_MAX_LENGTH = 4_000

@Serializable
enum class BugReportCategory(val wireValue: String) {
    @SerialName("crash") Crash("crash"),
    @SerialName("incorrect_data") IncorrectData("incorrect_data"),
    @SerialName("payment") Payment("payment"),
    @SerialName("sync") Sync("sync"),
    @SerialName("permission") Permission("permission"),
    @SerialName("performance") Performance("performance"),
    @SerialName("usability") Usability("usability"),
    @SerialName("other") Other("other"),
}

@Serializable
enum class BugReportSeverity(val wireValue: String) {
    @SerialName("low") Low("low"),
    @SerialName("medium") Medium("medium"),
    @SerialName("high") High("high"),
    @SerialName("critical") Critical("critical"),
}

/** Three staff-facing choices are faster and less technical than a category taxonomy. */
enum class SupportRequestReason(
    val label: String,
    val detail: String,
    val category: BugReportCategory,
    val titlePrefix: String,
) {
    SomethingFailed(
        label = "Something failed",
        detail = "A button, payment or task did not complete",
        category = BugReportCategory.Other,
        titlePrefix = "Action failed",
    ),
    NeedGuidance(
        label = "I'm stuck",
        detail = "I am not sure what to do next",
        category = BugReportCategory.Usability,
        titlePrefix = "Staff needs help",
    ),
    IncorrectInformation(
        label = "A total looks wrong",
        detail = "Money, quantity or status appears incorrect",
        category = BugReportCategory.IncorrectData,
        titlePrefix = "Incorrect information",
    ),
}

enum class WorkContinuation(
    val label: String,
    val severity: BugReportSeverity,
) {
    Yes("Yes", BugReportSeverity.Low),
    Difficult("With difficulty", BugReportSeverity.Medium),
    Blocked("No", BugReportSeverity.High),
}

data class BugReportLaunchContext(
    val currentScreen: String,
    /** Allowlisted action name only; never include order/customer/payment content. */
    val lastAction: String? = null,
    /** Stable code only; never include an exception message or stack trace. */
    val errorCode: String? = null,
)

data class BugReportOwnerScope(
    val companyId: String,
    val userId: String,
)

data class BugReportDraft(
    val reason: SupportRequestReason = SupportRequestReason.NeedGuidance,
    val canContinue: WorkContinuation = WorkContinuation.Difficult,
    val description: String = "",
) {
    fun validate(): BugReportValidation = BugReportValidation(
        description = when {
            description.trim().length < BUG_REPORT_DESCRIPTION_MIN_LENGTH ->
                "Tell us what happened in at least $BUG_REPORT_DESCRIPTION_MIN_LENGTH characters."
            description.trim().length > BUG_REPORT_DETAIL_MAX_LENGTH ->
                "Description must be $BUG_REPORT_DETAIL_MAX_LENGTH characters or fewer."
            else -> null
        },
    )

    fun toRequest(clientContext: BugReportClientContext): BugReportCreateRequest {
        val screen = clientContext.currentScreen?.takeIf(String::isNotBlank) ?: "ERP"
        return BugReportCreateRequest(
            category = reason.category,
            severity = canContinue.severity,
            title = "${reason.titlePrefix} · $screen".take(160),
            description = description.trim(),
            clientContext = clientContext,
        )
    }
}

data class BugReportValidation(val description: String? = null) {
    val isValid: Boolean get() = description == null
}

@Serializable
data class BugReportCreateRequest(
    val category: BugReportCategory,
    val severity: BugReportSeverity,
    val title: String,
    val description: String,
    @SerialName("reproduction_steps") val reproductionSteps: String? = null,
    @SerialName("expected_behavior") val expectedBehavior: String? = null,
    @SerialName("actual_behavior") val actualBehavior: String? = null,
    @SerialName("client_context") val clientContext: BugReportClientContext,
)

@Serializable
data class BugReportClientContext(
    val platform: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("version_code") val versionCode: Int? = null,
    @SerialName("device_model") val deviceModel: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("current_screen") val currentScreen: String? = null,
    @SerialName("last_action") val lastAction: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("branch_name") val branchName: String? = null,
    @SerialName("terminal_id") val terminalId: String? = null,
    @SerialName("terminal_name") val terminalName: String? = null,
    val connectivity: String,
    @SerialName("occurred_at") val occurredAt: String? = null,
)

@Serializable
data class BugReportCreateResponse(
    val id: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class BugReportMinePage(
    val items: List<BugReportMineItem> = emptyList(),
    val total: Int = 0,
    val limit: Int = 20,
    val offset: Int = 0,
)

@Serializable
data class BugReportInboxPage(
    val items: List<BugReportInboxItem> = emptyList(),
    val total: Int = 0,
    val limit: Int = 50,
    val offset: Int = 0,
)

@Serializable
data class BugReportInboxItem(
    val id: String,
    val title: String,
    val description: String,
    val severity: String,
    val status: String,
    val reporter: BugReportInboxReporter,
    @SerialName("client_context") val clientContext: BugReportClientContext,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class BugReportInboxReporter(
    @SerialName("user_id") val userId: String,
    val name: String,
    val email: String,
)

@Serializable
data class BugReportMineItem(
    val id: String,
    val title: String,
    val status: String,
    @SerialName("public_replies") val publicReplies: List<BugReportPublicReply> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class BugReportPublicReply(
    val id: String,
    @SerialName("author_name") val authorName: String,
    val message: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class BugReportAttachment(
    val id: String,
    val filename: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("byte_size") val byteSize: Int,
    @SerialName("created_at") val createdAt: String,
    val available: Boolean = true,
)

enum class BugReportConnectivity(val wireValue: String) {
    Online("online"),
    Offline("offline"),
    Unknown("unknown"),
}

internal fun bugReportConnectivity(
    effectiveOnline: Boolean,
    networkValidated: Boolean,
): BugReportConnectivity = when {
    effectiveOnline -> BugReportConnectivity.Online
    !networkValidated -> BugReportConnectivity.Offline
    else -> BugReportConnectivity.Unknown
}

/**
 * Only deliberately selected, non-secret operational metadata is attached.
 * This function has no access to auth tokens, logs, orders, customers,
 * payments, screenshots, or arbitrary application state.
 */
internal fun currentAndroidBugReportContext(
    launchContext: BugReportLaunchContext,
    branchId: String?,
    branchName: String?,
    terminalId: String?,
    terminalName: String?,
    connectivity: BugReportConnectivity,
): BugReportClientContext = buildBugReportClientContext(
    appVersion = BuildConfig.VERSION_NAME,
    versionCode = BuildConfig.VERSION_CODE,
    manufacturer = Build.MANUFACTURER,
    model = Build.MODEL,
    osRelease = Build.VERSION.RELEASE,
    apiLevel = Build.VERSION.SDK_INT,
    currentScreen = launchContext.currentScreen,
    lastAction = launchContext.lastAction,
    errorCode = launchContext.errorCode,
    branchId = branchId,
    branchName = branchName,
    terminalId = terminalId,
    terminalName = terminalName,
    connectivity = connectivity,
    occurredAt = Instant.now().toString(),
)

internal fun buildBugReportClientContext(
    appVersion: String,
    versionCode: Int,
    manufacturer: String,
    model: String,
    osRelease: String,
    apiLevel: Int,
    currentScreen: String,
    lastAction: String? = null,
    errorCode: String? = null,
    branchId: String?,
    branchName: String?,
    terminalId: String?,
    terminalName: String?,
    connectivity: BugReportConnectivity,
    occurredAt: String,
): BugReportClientContext {
    val device = listOf(manufacturer.trim(), model.trim())
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .joinToString(" ")
        .ifBlank { "Android device" }
    val release = osRelease.trim().ifBlank { "unknown" }
    return BugReportClientContext(
        platform = "android",
        appVersion = appVersion.trim().take(40).ifBlank { null },
        versionCode = versionCode.takeIf { it >= 1 },
        deviceModel = device.take(160),
        osVersion = "Android $release (API $apiLevel)".take(100),
        currentScreen = safeContextValue(currentScreen, 100),
        lastAction = safeContextValue(lastAction, 120),
        errorCode = safeErrorCode(errorCode),
        branchId = strictUuidOrNull(branchId),
        branchName = branchName?.trim()?.take(200)?.ifBlank { null },
        terminalId = strictUuidOrNull(terminalId),
        terminalName = terminalName?.trim()?.take(160)?.ifBlank { null },
        connectivity = connectivity.wireValue,
        occurredAt = occurredAt,
    )
}

private fun safeContextValue(value: String?, maximum: Int): String? {
    val cleaned = value?.trim()?.take(maximum)?.ifBlank { null } ?: return null
    // Context values are supplied by app-owned enums, not free-form user data.
    // Reject line breaks/control characters if a future call site gets this wrong.
    return cleaned.takeIf { candidate -> candidate.all { it >= ' ' && it != '\u007f' } }
}

private fun safeErrorCode(value: String?): String? {
    val cleaned = value?.trim()?.take(100)?.ifBlank { null } ?: return null
    return cleaned.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]+")) }
}

private fun strictUuidOrNull(value: String?): String? {
    val candidate = value?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
    val parsed = runCatching { UUID.fromString(candidate) }.getOrNull() ?: return null
    return parsed.toString().takeIf { it == candidate }
}
