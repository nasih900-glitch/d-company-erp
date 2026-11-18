package cloud.dcompany.erp.ui.screens.settings

import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.auth.AccessTokenIdentityParser
import cloud.dcompany.erp.core.handover.OperationDispatchContext
import cloud.dcompany.erp.core.handover.OperationDispatchGate
import cloud.dcompany.erp.core.net.ApiClient
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import retrofit2.Retrofit

/** Pre-terminal history is owner-read only, not operational or Room write authority. */
internal class BugReportOwnerHistory(
    private val owner: BugReportOwnerScope,
    private val originalBearer: () -> String?,
    private val originalLoginIsCurrent: () -> Boolean,
) {
    fun bearer(): String {
        val bearer = originalBearer().takeIf { originalLoginIsCurrent() }
        val identity = bearer?.let(AccessTokenIdentityParser::parse)
        if (identity == null || identity.userId != owner.userId || identity.companyId != owner.companyId) {
            throw IOException("The original support-history login changed.")
        }
        return requireNotNull(bearer)
    }
    val gate = object : OperationDispatchGate {
        override fun authorize(request: Request) {
            bearer()
            if (request.method != "GET" || request.url.encodedPath != "/api/v1/bug-reports/mine" ||
                request.url.queryParameterNames != setOf("limit", "offset") ||
                request.url.queryParameterValues("limit") != listOf("20") ||
                request.url.queryParameterValues("offset") != listOf("0") ||
                request.tag(OperationDispatchContext::class.java) != null) {
                throw IOException("Only the original employee's support history may be read before workspace selection.")
            }
        }
    }
    fun client() = ApiClient.ephemeralAuthorityClient(bearer(), terminalId = null,
        beforeDispatch = { bearer() }, singleAttempt = true, operationGate = gate)

    suspend fun read(): BugReportMinePage = Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL)
        .callFactory(client()).addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
        .build().create(BugReportApi::class.java).mine()
}
