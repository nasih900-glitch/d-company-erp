package cloud.dcompany.erp.ui.screens.settings

import android.os.Build
import cloud.dcompany.erp.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

internal const val BUG_REPORT_TITLE_MIN_LENGTH = 5
internal const val BUG_REPORT_TITLE_MAX_LENGTH = 160
internal const val BUG_REPORT_DESCRIPTION_MIN_LENGTH = 10
internal const val BUG_REPORT_DETAIL_MAX_LENGTH = 4_000

@Serializable
enum class BugReportCategory(val wireValue: String, val label: String) {
    @SerialName("crash")
    Crash("crash", "Crash or freeze"),

    @SerialName("incorrect_data")
    IncorrectData("incorrect_data", "Incorrect data or totals"),

    @SerialName("payment")
    Payment("payment", "Payment or receipt"),

    @SerialName("sync")
    Sync("sync", "Sync or offline issue"),

    @SerialName("permission")
    Permission("permission", "Access or permission"),

    @SerialName("performance")
    Performance("performance", "Slow or unresponsive"),

    @SerialName("usability")
    Usability("usability", "Confusing or difficult to use"),

    @SerialName("other")
    Other("other", "Other"),
}

@Serializable
enum class BugReportSeverity(val wireValue: String, val label: String, val guidance: String) {
    @SerialName("low")
    Low("low", "Low", "Minor inconvenience; work can continue"),

    @SerialName("medium")
    Medium("medium", "Medium", "Work is harder, but there is a workaround"),

    @SerialName("high")
    High("high", "High", "A key task is blocked for one or more staff"),

    @SerialName("critical")
    Critical("critical", "Critical", "Payments, data, or the whole operation may be at risk"),
}

data class BugReportDraft(
    val category: BugReportCategory = BugReportCategory.Usability,
    val severity: BugReportSeverity = BugReportSeverity.Medium,
    val title: String = "",
    val description: String = "",
    val reproductionSteps: String = "",
    val expectedBehavior: String = "",
    val actualBehavior: String = "",
) {
    fun validate(): BugReportValidation = BugReportValidation(
        title = when {
            title.trim().length < BUG_REPORT_TITLE_MIN_LENGTH ->
                "Enter a short title of at least $BUG_REPORT_TITLE_MIN_LENGTH characters."
            title.trim().length > BUG_REPORT_TITLE_MAX_LENGTH ->
                "Title must be $BUG_REPORT_TITLE_MAX_LENGTH characters or fewer."
            else -> null
        },
        description = when {
            description.trim().length < BUG_REPORT_DESCRIPTION_MIN_LENGTH ->
                "Describe the problem in at least $BUG_REPORT_DESCRIPTION_MIN_LENGTH characters."
            description.trim().length > BUG_REPORT_DETAIL_MAX_LENGTH ->
                "Description must be $BUG_REPORT_DETAIL_MAX_LENGTH characters or fewer."
            else -> null
        },
        reproductionSteps = optionalLengthError("Steps to reproduce", reproductionSteps),
        expectedBehavior = optionalLengthError("Expected behaviour", expectedBehavior),
        actualBehavior = optionalLengthError("What actually happened", actualBehavior),
    )

    fun toRequest(clientContext: BugReportClientContext): BugReportCreateRequest =
        BugReportCreateRequest(
            category = category,
            severity = severity,
            title = title.trim(),
            description = description.trim(),
            reproductionSteps = reproductionSteps.trim().ifBlank { null },
            expectedBehavior = expectedBehavior.trim().ifBlank { null },
            actualBehavior = actualBehavior.trim().ifBlank { null },
            clientContext = clientContext,
        )
}

data class BugReportValidation(
    val title: String? = null,
    val description: String? = null,
    val reproductionSteps: String? = null,
    val expectedBehavior: String? = null,
    val actualBehavior: String? = null,
) {
    val isValid: Boolean
        get() = title == null && description == null && reproductionSteps == null &&
            expectedBehavior == null && actualBehavior == null
}

private fun optionalLengthError(label: String, value: String): String? =
    if (value.trim().length > BUG_REPORT_DETAIL_MAX_LENGTH) {
        "$label must be $BUG_REPORT_DETAIL_MAX_LENGTH characters or fewer."
    } else {
        null
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
 * This function has no access to auth tokens, log buffers, orders, customers,
 * payments, or free-form application state.
 */
internal fun currentAndroidBugReportContext(
    currentScreen: String,
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
    currentScreen = currentScreen,
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
        currentScreen = currentScreen.trim().take(100).ifBlank { null },
        branchId = strictUuidOrNull(branchId),
        branchName = branchName?.trim()?.take(200)?.ifBlank { null },
        terminalId = strictUuidOrNull(terminalId),
        terminalName = terminalName?.trim()?.take(160)?.ifBlank { null },
        connectivity = connectivity.wireValue,
        occurredAt = occurredAt,
    )
}

private fun strictUuidOrNull(value: String?): String? {
    val candidate = value?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
    val parsed = runCatching { UUID.fromString(candidate) }.getOrNull() ?: return null
    return parsed.toString().takeIf { it == candidate }
}
