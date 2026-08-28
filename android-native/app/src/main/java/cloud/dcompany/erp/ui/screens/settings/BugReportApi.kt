package cloud.dcompany.erp.ui.screens.settings

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface BugReportApi {
    @POST("bug-reports")
    suspend fun create(
        @Body body: BugReportCreateRequest,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): BugReportCreateResponse
}
