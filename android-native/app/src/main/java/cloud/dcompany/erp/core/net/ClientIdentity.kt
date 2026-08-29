package cloud.dcompany.erp.core.net

import cloud.dcompany.erp.BuildConfig
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal const val CLIENT_PLATFORM_HEADER = "X-Client-Platform"
internal const val CLIENT_VERSION_CODE_HEADER = "X-Client-Version-Code"
internal const val CLIENT_DISTRIBUTION_CHANNEL_HEADER = "X-Client-Distribution-Channel"
internal const val ANDROID_CLIENT_PLATFORM = "android"
internal val ANDROID_DISTRIBUTION_CHANNELS = setOf("direct", "play", "managed")

/**
 * One implementation for every OkHttp client in the app (REST and realtime).
 * The server uses these headers to reject a native build that is below its
 * minimum safe API contract before any write handler can run.
 */
internal class ClientIdentityInterceptor(
    private val versionCode: Int = BuildConfig.VERSION_CODE,
    private val distributionChannel: String = BuildConfig.DISTRIBUTION_CHANNEL,
) : Interceptor {
    init {
        require(versionCode > 0) { "Android versionCode must be positive" }
        require(distributionChannel in ANDROID_DISTRIBUTION_CHANNELS) {
            "Android distribution channel is not supported"
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain.request().withAndroidClientIdentity(versionCode, distributionChannel),
        )
}

internal fun Request.withAndroidClientIdentity(
    versionCode: Int,
    distributionChannel: String = BuildConfig.DISTRIBUTION_CHANNEL,
): Request {
    require(versionCode > 0) { "Android versionCode must be positive" }
    require(distributionChannel in ANDROID_DISTRIBUTION_CHANNELS) {
        "Android distribution channel is not supported"
    }
    return newBuilder()
        .header(CLIENT_PLATFORM_HEADER, ANDROID_CLIENT_PLATFORM)
        .header(CLIENT_VERSION_CODE_HEADER, versionCode.toString())
        .header(CLIENT_DISTRIBUTION_CHANNEL_HEADER, distributionChannel)
        .build()
}
