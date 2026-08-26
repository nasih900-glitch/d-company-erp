package cloud.dcompany.erp.core.net

import cloud.dcompany.erp.BuildConfig
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal const val CLIENT_PLATFORM_HEADER = "X-Client-Platform"
internal const val CLIENT_VERSION_CODE_HEADER = "X-Client-Version-Code"
internal const val ANDROID_CLIENT_PLATFORM = "android"

/**
 * One implementation for every OkHttp client in the app (REST and realtime).
 * The server uses these headers to reject a native build that is below its
 * minimum safe API contract before any write handler can run.
 */
internal class ClientIdentityInterceptor(
    private val versionCode: Int = BuildConfig.VERSION_CODE,
) : Interceptor {
    init {
        require(versionCode > 0) { "Android versionCode must be positive" }
    }

    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().withAndroidClientIdentity(versionCode))
}

internal fun Request.withAndroidClientIdentity(versionCode: Int): Request {
    require(versionCode > 0) { "Android versionCode must be positive" }
    return newBuilder()
        .header(CLIENT_PLATFORM_HEADER, ANDROID_CLIENT_PLATFORM)
        .header(CLIENT_VERSION_CODE_HEADER, versionCode.toString())
        .build()
}
