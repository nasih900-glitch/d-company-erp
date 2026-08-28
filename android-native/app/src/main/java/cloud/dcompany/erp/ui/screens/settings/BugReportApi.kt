package cloud.dcompany.erp.ui.screens.settings

import retrofit2.http.Body
import okhttp3.MultipartBody
import retrofit2.http.Header
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface BugReportApi {
    @POST("bug-reports")
    suspend fun create(
        @Body body: BugReportCreateRequest,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): BugReportCreateResponse

    @GET("bug-reports/mine")
    suspend fun mine(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): BugReportMinePage

    @Multipart
    @POST("bug-reports/mine/{report_id}/attachments")
    suspend fun uploadAttachment(
        @Path("report_id") reportId: String,
        @Part file: MultipartBody.Part,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): BugReportAttachment
}
