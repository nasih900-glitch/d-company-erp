package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.BuildConfig
import java.io.IOException
import java.time.Instant
import java.util.UUID
import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer

/**
 * Network interceptor so every physical network attempt, including an OkHttp
 * transport retry, receives a fresh nonce/timestamp/signature. Enrollment is
 * the sole proof-of-possession body endpoint and intentionally has no device
 * proof headers until the public key exists server-side.
 */
internal class RemoteDeviceSigningInterceptor(
    private val identity: (Request) -> PersistedRemoteDeviceIdentity?,
    private val currentScope: () -> RemoteAssistanceJournalScope?,
    private val keyStore: RemoteDeviceKeyStore,
    private val expectedApiOrigin: HttpUrl = BuildConfig.API_BASE_URL.toHttpUrl(),
    private val epochSeconds: () -> Long = { Instant.now().epochSecond },
    private val nonce: () -> String = { UUID.randomUUID().toString() },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request().withoutRemoteDeviceProofHeaders()
        if (!remoteDeviceProofOriginMatches(original.url, expectedApiOrigin)) {
            throw IOException("Remote device proof is restricted to the configured HTTPS API origin")
        }
        val taggedScope = original.tag(RemoteRequestScopeTag::class.java)?.scope
            ?: throw IOException("Remote device request is missing its authenticated journal scope")
        if (taggedScope != currentScope()) {
            throw IOException("Remote device request scope is no longer authenticated")
        }
        if (original.url.encodedPath.endsWith(ENROLLMENT_PATH_SUFFIX)) {
            return chain.proceed(original)
        }
        val current = identity(original)
            ?: throw IOException("Remote device enrollment is required")
        val signedAt = epochSeconds()
        val requestNonce = nonce()
        val contentHash = requestContentSha256(original)
        val rawTarget = buildString {
            append(original.url.encodedPath)
            original.url.encodedQuery?.let { query -> append('?').append(query) }
        }
        val statement = try {
            canonicalRemoteRequestStatement(
                method = original.method,
                rawTarget = rawTarget,
                contentSha256 = contentHash,
                signedAtEpochSeconds = signedAt,
                nonce = requestNonce,
                keyId = current.keyId,
            )
        } catch (error: IllegalArgumentException) {
            throw IOException("Remote device request could not be canonicalized", error)
        }
        val signature = try {
            remoteBase64Url(keyStore.sign(current.keyId, statement))
        } catch (error: Exception) {
            throw IOException("Remote device request could not be signed", error)
        } finally {
            statement.fill(0)
        }
        return chain.proceed(
            original.newBuilder()
                .header(REMOTE_DEVICE_KEY_ID_HEADER, current.keyId)
                .header(REMOTE_DEVICE_TIMESTAMP_HEADER, signedAt.toString())
                .header(REMOTE_DEVICE_NONCE_HEADER, requestNonce)
                .header(REMOTE_DEVICE_CONTENT_SHA256_HEADER, contentHash)
                .header(REMOTE_DEVICE_SIGNATURE_HEADER, signature)
                .build(),
        )
    }

    private fun requestContentSha256(request: Request): String {
        val body = request.body ?: return remoteSha256Hex(ByteArray(0))
        if (body.isDuplex() || body.isOneShot()) {
            throw IOException("Remote device request body must be replayable")
        }
        val buffer = Buffer()
        return try {
            body.writeTo(buffer)
            val bytes = buffer.readByteArray()
            try {
                remoteSha256Hex(bytes)
            } finally {
                bytes.fill(0)
            }
        } finally {
            buffer.clear()
        }
    }

    private fun Request.withoutRemoteDeviceProofHeaders(): Request = newBuilder()
        .removeHeader(REMOTE_DEVICE_KEY_ID_HEADER)
        .removeHeader(REMOTE_DEVICE_TIMESTAMP_HEADER)
        .removeHeader(REMOTE_DEVICE_NONCE_HEADER)
        .removeHeader(REMOTE_DEVICE_CONTENT_SHA256_HEADER)
        .removeHeader(REMOTE_DEVICE_SIGNATURE_HEADER)
        .build()

    private companion object {
        const val ENROLLMENT_PATH_SUFFIX = "/remote-assistance/device/keys/enroll"
    }
}

internal fun remoteDeviceProofOriginMatches(request: HttpUrl, configuredApi: HttpUrl): Boolean =
    configuredApi.scheme == "https" &&
        request.scheme == configuredApi.scheme &&
        request.host == configuredApi.host &&
        request.port == configuredApi.port

internal const val REMOTE_DEVICE_KEY_ID_HEADER = "X-ERP-Device-Key-Id"
internal const val REMOTE_DEVICE_TIMESTAMP_HEADER = "X-ERP-Device-Timestamp"
internal const val REMOTE_DEVICE_NONCE_HEADER = "X-ERP-Device-Nonce"
internal const val REMOTE_DEVICE_CONTENT_SHA256_HEADER = "X-ERP-Content-SHA256"
internal const val REMOTE_DEVICE_SIGNATURE_HEADER = "X-ERP-Device-Signature"
