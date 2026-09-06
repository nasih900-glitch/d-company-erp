package cloud.dcompany.erp.core.remote

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

internal const val REMOTE_ENROLLMENT_SIGNATURE_DOMAIN =
    "D-COMPANY-ERP-REMOTE-ENROLLMENT-V1"
internal const val REMOTE_REQUEST_SIGNATURE_DOMAIN =
    "D-COMPANY-ERP-REMOTE-REQUEST-V1"

internal fun remoteSha256Hex(bytes: ByteArray): String = MessageDigest
    .getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

internal fun remoteBase64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun canonicalRemoteEnrollmentStatement(
    companyId: String,
    installationId: String,
    keyId: String,
    enrollmentId: String,
    signedAtEpochSeconds: Long,
    nonce: String,
    spkiSha256: String,
): ByteArray {
    requireCanonicalRemoteUuid(companyId, "companyId")
    requireCanonicalRemoteUuid(installationId, "installationId")
    requireCanonicalRemoteUuid(keyId, "keyId")
    requireCanonicalRemoteUuid(enrollmentId, "enrollmentId")
    requireCanonicalRemoteUuid(nonce, "nonce")
    require(signedAtEpochSeconds >= 0L) { "signedAtEpochSeconds must be non-negative" }
    require(spkiSha256.matches(LOWERCASE_SHA256)) { "SPKI hash must be lowercase SHA-256" }
    return listOf(
        REMOTE_ENROLLMENT_SIGNATURE_DOMAIN,
        companyId,
        installationId,
        keyId,
        enrollmentId,
        signedAtEpochSeconds.toString(),
        nonce,
        spkiSha256,
    ).joinToString("\n").toByteArray(StandardCharsets.US_ASCII)
}

internal fun canonicalRemoteRequestStatement(
    method: String,
    rawTarget: String,
    contentSha256: String,
    signedAtEpochSeconds: Long,
    nonce: String,
    keyId: String,
): ByteArray {
    val canonicalMethod = method.uppercase(Locale.ROOT)
    require(canonicalMethod.matches(HTTP_METHOD)) { "HTTP method is not canonical" }
    require(
        rawTarget.startsWith("/api/v1/remote-assistance/device/") &&
            '\n' !in rawTarget && '\r' !in rawTarget,
    ) { "Remote request target is outside the signed device API" }
    require(contentSha256.matches(LOWERCASE_SHA256)) { "Content hash must be lowercase SHA-256" }
    require(signedAtEpochSeconds >= 0L) { "signedAtEpochSeconds must be non-negative" }
    requireCanonicalRemoteUuid(nonce, "nonce")
    requireCanonicalRemoteUuid(keyId, "keyId")
    return listOf(
        REMOTE_REQUEST_SIGNATURE_DOMAIN,
        canonicalMethod,
        rawTarget,
        contentSha256,
        signedAtEpochSeconds.toString(),
        nonce,
        keyId,
    ).joinToString("\n").toByteArray(StandardCharsets.US_ASCII)
}

internal fun groupedRemotePairingCode(value: String): String? {
    val canonical = value.trim().uppercase(Locale.ROOT)
    if (!canonical.matches(PAIRING_CODE)) return null
    return canonical.chunked(4).joinToString("-")
}

private fun requireCanonicalRemoteUuid(value: String, label: String) {
    require(isCanonicalUuidV4(value)) { "$label must be a canonical UUID v4" }
}

private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
private val HTTP_METHOD = Regex("[A-Z]+")
private val PAIRING_CODE = Regex("[0-9A-HJKMNP-TV-Z]{12}")
