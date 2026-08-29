package cloud.dcompany.erp.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.net.ClientUpdateNotice
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_APK_BYTES = 512L * 1024L * 1024L
private const val MIN_FREE_SPACE_MARGIN_BYTES = 16L * 1024L * 1024L
private const val MAX_REDIRECTS = 5
private const val READY_FILE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
private val SHA256 = Regex("^[0-9a-f]{64}$")

data class DirectUpdateDescriptor(
    val url: String,
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val sizeBytes: Long,
    val expectedCurrentSignerSha256: String?,
)

enum class DirectUpdateMetadataProblem {
    MissingOrUnsafeApkUrl,
    MissingOrInvalidVersion,
    MissingOrInvalidChecksum,
    MissingOrInvalidSize,
    InvalidExpectedSigner,
}

sealed interface DirectUpdateMetadataResult {
    data class Valid(val descriptor: DirectUpdateDescriptor) : DirectUpdateMetadataResult
    data class Invalid(val problem: DirectUpdateMetadataProblem) : DirectUpdateMetadataResult
}

/**
 * Strict server-metadata gate for the direct-distribution flavor.
 *
 * The ordinary Play/shared build never calls this download path. A valid
 * descriptor is still not install authority: the downloaded archive must
 * independently pass package/version/signing-lineage checks below.
 */
fun validateDirectUpdateMetadata(
    notice: ClientUpdateNotice,
    installedVersionCode: Int = BuildConfig.VERSION_CODE,
): DirectUpdateMetadataResult {
    val url = safeHttpsApkUrl(notice.updateUrl)
        ?: return DirectUpdateMetadataResult.Invalid(
            DirectUpdateMetadataProblem.MissingOrUnsafeApkUrl,
        )
    val versionCode = notice.latestVersionCode
        ?.takeIf { it > installedVersionCode }
        ?: return DirectUpdateMetadataResult.Invalid(
            DirectUpdateMetadataProblem.MissingOrInvalidVersion,
        )
    val versionName = notice.latestVersionName
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 80 }
        ?: return DirectUpdateMetadataResult.Invalid(
            DirectUpdateMetadataProblem.MissingOrInvalidVersion,
        )
    val checksum = normalizeSha256(notice.apkSha256)
        ?: return DirectUpdateMetadataResult.Invalid(
            DirectUpdateMetadataProblem.MissingOrInvalidChecksum,
        )
    val size = notice.apkSizeBytes
        ?.takeIf { it in 1..MAX_APK_BYTES }
        ?: return DirectUpdateMetadataResult.Invalid(
            DirectUpdateMetadataProblem.MissingOrInvalidSize,
        )
    val expectedSigner = normalizeSha256(notice.apkSigningCertSha256)
        ?: return DirectUpdateMetadataResult.Invalid(
            DirectUpdateMetadataProblem.InvalidExpectedSigner,
        )
    return DirectUpdateMetadataResult.Valid(
        DirectUpdateDescriptor(
            url = url,
            versionCode = versionCode,
            versionName = versionName,
            sha256 = checksum,
            sizeBytes = size,
            expectedCurrentSignerSha256 = expectedSigner,
        ),
    )
}

/** Direct APKs must remain HTTPS APK resources across every redirect. */
fun safeHttpsApkUrl(raw: String?): String? {
    val candidate = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) return null
    if (!uri.path.orEmpty().lowercase().endsWith(".apk")) return null
    return uri.toASCIIString()
}

fun normalizeSha256(raw: String?): String? = raw
    ?.trim()
    ?.replace(":", "")
    ?.lowercase()
    ?.takeIf(SHA256::matches)

/**
 * Signing continuity policy extracted from PackageManager so its edge cases
 * remain JVM-testable. Android only supports rotation for a single signer;
 * multi-signer APKs must keep the exact current signer set.
 */
fun signingLineageIsContinuous(
    installedCurrentSigners: Set<String>,
    installedSigningHistory: Set<String>,
    candidateCurrentSigners: Set<String>,
    candidateSigningHistory: Set<String>,
): Boolean {
    if (
        installedCurrentSigners.isEmpty() ||
        installedSigningHistory.isEmpty() ||
        candidateCurrentSigners.isEmpty() ||
        candidateSigningHistory.isEmpty()
    ) return false

    if (installedCurrentSigners.size > 1 || candidateCurrentSigners.size > 1) {
        return installedCurrentSigners == candidateCurrentSigners &&
            installedSigningHistory.intersect(candidateSigningHistory).isNotEmpty()
    }

    val installedCurrent = installedCurrentSigners.single()
    return installedCurrent in candidateSigningHistory &&
        installedSigningHistory.intersect(candidateSigningHistory).isNotEmpty()
}

enum class ArchiveVerificationProblem {
    UnreadableArchive,
    WrongPackage,
    WrongVersion,
    NotNewer,
    MissingSignature,
    UnexpectedSigner,
    BrokenSigningLineage,
}

sealed interface ArchiveVerificationResult {
    data object Verified : ArchiveVerificationResult
    data class Rejected(val problem: ArchiveVerificationProblem) : ArchiveVerificationResult
}

private data class SigningEvidence(
    val current: Set<String>,
    val history: Set<String>,
)

class AndroidApkVerifier(private val context: Context) {
    fun verify(file: File, descriptor: DirectUpdateDescriptor): ArchiveVerificationResult {
        val packageManager = context.packageManager
        val candidate = packageInfo(packageManager, file.absolutePath)
            ?: return ArchiveVerificationResult.Rejected(
                ArchiveVerificationProblem.UnreadableArchive,
            )
        if (candidate.packageName != context.packageName) {
            return ArchiveVerificationResult.Rejected(ArchiveVerificationProblem.WrongPackage)
        }
        val candidateVersion = PackageInfoCompat.getLongVersionCode(candidate)
        if (candidateVersion != descriptor.versionCode.toLong()) {
            return ArchiveVerificationResult.Rejected(ArchiveVerificationProblem.WrongVersion)
        }
        if (candidate.versionName != descriptor.versionName) {
            return ArchiveVerificationResult.Rejected(ArchiveVerificationProblem.WrongVersion)
        }

        val installed = runCatching {
            packageInfo(packageManager, context.packageName, archive = false)
        }.getOrNull() ?: return ArchiveVerificationResult.Rejected(
            ArchiveVerificationProblem.UnreadableArchive,
        )
        val installedVersion = PackageInfoCompat.getLongVersionCode(installed)
        if (candidateVersion <= installedVersion) {
            return ArchiveVerificationResult.Rejected(ArchiveVerificationProblem.NotNewer)
        }

        val installedSigning = installed.signingEvidence()
        val candidateSigning = candidate.signingEvidence()
        if (installedSigning.current.isEmpty() || candidateSigning.current.isEmpty()) {
            return ArchiveVerificationResult.Rejected(ArchiveVerificationProblem.MissingSignature)
        }
        val expected = descriptor.expectedCurrentSignerSha256
        if (expected != null && candidateSigning.current != setOf(expected)) {
            return ArchiveVerificationResult.Rejected(ArchiveVerificationProblem.UnexpectedSigner)
        }
        if (
            !signingLineageIsContinuous(
                installedCurrentSigners = installedSigning.current,
                installedSigningHistory = installedSigning.history,
                candidateCurrentSigners = candidateSigning.current,
                candidateSigningHistory = candidateSigning.history,
            )
        ) {
            return ArchiveVerificationResult.Rejected(
                ArchiveVerificationProblem.BrokenSigningLineage,
            )
        }
        return ArchiveVerificationResult.Verified
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(
        packageManager: PackageManager,
        packageNameOrArchivePath: String,
        archive: Boolean = true,
    ): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (archive) {
            packageManager.getPackageArchiveInfo(packageNameOrArchivePath, flags)
        } else {
            packageManager.getPackageInfo(packageNameOrArchivePath, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signingEvidence(): SigningEvidence {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val fingerprints = signatures.orEmpty().mapTo(linkedSetOf(), ::signatureSha256)
            return SigningEvidence(fingerprints, fingerprints)
        }
        val info = signingInfo ?: return SigningEvidence(emptySet(), emptySet())
        val current = info.apkContentsSigners.orEmpty().mapTo(linkedSetOf(), ::signatureSha256)
        val history = if (info.hasMultipleSigners()) {
            current
        } else {
            info.signingCertificateHistory.orEmpty().mapTo(linkedSetOf(), ::signatureSha256)
        }
        return SigningEvidence(current, history.ifEmpty { current })
    }

    private fun signatureSha256(signature: android.content.pm.Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data class Downloading(
        val descriptor: DirectUpdateDescriptor,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AppUpdateUiState {
        val versionCode: Int get() = descriptor.versionCode
    }
    data class Verifying(val descriptor: DirectUpdateDescriptor) : AppUpdateUiState {
        val versionCode: Int get() = descriptor.versionCode
    }
    data class Ready(
        val descriptor: DirectUpdateDescriptor,
        val filePath: String,
    ) : AppUpdateUiState {
        val versionCode: Int get() = descriptor.versionCode
    }
    data class Failed(
        val versionCode: Int?,
        val message: String,
        val descriptor: DirectUpdateDescriptor? = null,
    ) : AppUpdateUiState
}

internal fun AppUpdateUiState.matchesDescriptor(descriptor: DirectUpdateDescriptor): Boolean =
    when (this) {
        is AppUpdateUiState.Downloading -> this.descriptor == descriptor
        is AppUpdateUiState.Verifying -> this.descriptor == descriptor
        is AppUpdateUiState.Ready -> this.descriptor == descriptor
        is AppUpdateUiState.Failed -> this.descriptor == descriptor
        AppUpdateUiState.Idle -> false
    }

/** One explicit tap starts one download. Failures never auto-retry. */
class AppUpdateViewModel : ViewModel() {
    private val app = DCompanyApp.instance
    private val downloader = VerifiedApkDownloader(app)
    private val verifier = AndroidApkVerifier(app)
    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()
    @Volatile
    private var downloadJob: Job? = null
    @Volatile
    private var operationGeneration = 0L

    init {
        downloader.deleteExpiredFiles()
    }

    fun download(notice: ClientUpdateNotice) {
        if (!BuildConfig.DIRECT_UPDATES_ENABLED) return
        val validation = validateDirectUpdateMetadata(notice)
        if (validation !is DirectUpdateMetadataResult.Valid) {
            invalidateCurrentArtifact()
            _state.value = AppUpdateUiState.Failed(
                notice.latestVersionCode,
                "This release does not include complete verified-download details. Open the HTTPS update link instead.",
            )
            return
        }
        val descriptor = validation.descriptor
        val current = _state.value
        if (current is AppUpdateUiState.Ready && current.descriptor == descriptor) return
        if (downloadJob?.isActive == true && current.matchesDescriptor(descriptor)) return

        // Compatibility metadata can be corrected without changing versionCode.
        // Treat URL, versionName, digest, size and signer as one immutable
        // artifact identity: cancel the old operation and make its Ready file
        // unreachable before starting the replacement download.
        val previousJob = downloadJob
        val generation = ++operationGeneration
        previousJob?.cancel()
        (current as? AppUpdateUiState.Ready)?.let { File(it.filePath).delete() }

        // Publish feedback synchronously so a slow DNS/TLS connection never
        // leaves the operator wondering whether the tap was accepted.
        _state.value = AppUpdateUiState.Downloading(
            descriptor = descriptor,
            downloadedBytes = 0,
            totalBytes = descriptor.sizeBytes,
        )
        val replacementJob = viewModelScope.launch(
            context = Dispatchers.IO,
            start = CoroutineStart.LAZY,
        ) {
            try {
                // The old download owns the same private directory. Wait for
                // its cancellation cleanup before the replacement may delete,
                // write or atomically rename any artifact.
                previousJob?.cancelAndJoin()
                currentCoroutineContext().ensureActive()
                val file = downloader.download(descriptor) { downloaded ->
                    publishIfCurrent(
                        generation,
                        AppUpdateUiState.Downloading(
                            descriptor = descriptor,
                            downloadedBytes = downloaded,
                            totalBytes = descriptor.sizeBytes,
                        ),
                    )
                }
                if (generation != operationGeneration) {
                    file.delete()
                    return@launch
                }
                publishIfCurrent(generation, AppUpdateUiState.Verifying(descriptor))
                when (val result = verifier.verify(file, descriptor)) {
                    ArchiveVerificationResult.Verified -> {
                        publishIfCurrent(
                            generation,
                            AppUpdateUiState.Ready(
                                descriptor = descriptor,
                                filePath = file.absolutePath,
                            ),
                        )
                    }
                    is ArchiveVerificationResult.Rejected -> {
                        file.delete()
                        publishIfCurrent(
                            generation,
                            AppUpdateUiState.Failed(
                                descriptor.versionCode,
                                result.problem.userMessage(),
                                descriptor,
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                publishIfCurrent(generation, AppUpdateUiState.Idle)
                throw cancelled
            } catch (failure: Exception) {
                publishIfCurrent(
                    generation,
                    AppUpdateUiState.Failed(
                        descriptor.versionCode,
                        failure.message ?: "The verified update could not be prepared.",
                        descriptor,
                    ),
                )
            } finally {
                val finishingJob = currentCoroutineContext().job
                if (downloadJob === finishingJob) downloadJob = null
            }
        }
        downloadJob = replacementJob
        replacementJob.start()
    }

    fun cancel() {
        operationGeneration += 1
        val currentJob = downloadJob
        currentJob?.cancel()
        if (currentJob == null) downloader.deletePartialFiles()
        _state.value = AppUpdateUiState.Idle
    }

    fun verifiedFile(notice: ClientUpdateNotice): File? {
        val descriptor = (validateDirectUpdateMetadata(notice) as? DirectUpdateMetadataResult.Valid)
            ?.descriptor ?: return null
        val ready = _state.value as? AppUpdateUiState.Ready ?: return null
        if (ready.descriptor != descriptor) return null
        return File(ready.filePath).takeIf { it.isFile && it.parentFile == downloader.directory }
    }

    fun discard() {
        operationGeneration += 1
        val currentJob = downloadJob
        currentJob?.cancel()
        (_state.value as? AppUpdateUiState.Ready)?.let { File(it.filePath).delete() }
        if (currentJob == null) downloader.deletePartialFiles()
        _state.value = AppUpdateUiState.Idle
    }

    override fun onCleared() {
        operationGeneration += 1
        val currentJob = downloadJob
        currentJob?.cancel()
        if (currentJob == null) downloader.deletePartialFiles()
        super.onCleared()
    }

    private fun invalidateCurrentArtifact() {
        operationGeneration += 1
        val currentJob = downloadJob
        currentJob?.cancel()
        (_state.value as? AppUpdateUiState.Ready)?.let { File(it.filePath).delete() }
        if (currentJob == null) downloader.deletePartialFiles()
    }

    private fun publishIfCurrent(generation: Long, next: AppUpdateUiState) {
        if (generation == operationGeneration) _state.value = next
    }
}

private class VerifiedApkDownloader(context: Context) {
    val directory = File(context.cacheDir, "verified-updates")
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun download(
        descriptor: DirectUpdateDescriptor,
        onProgress: (Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        ensureDirectory()
        deletePartialFiles()
        directory.listFiles().orEmpty()
            .filter { it.extension == "apk" }
            .forEach(File::delete)

        val usableSpace = directory.usableSpace
        if (usableSpace > 0 && usableSpace < descriptor.sizeBytes + MIN_FREE_SPACE_MARGIN_BYTES) {
            throw IOException("Not enough free storage for this update.")
        }
        val stem = "d-company-${descriptor.versionCode}-${descriptor.sha256.take(12)}"
        val partial = File(directory, "$stem.apk.part")
        val verified = File(directory, "$stem.apk")
        try {
            val response = openFollowingSafeRedirects(descriptor.url)
            response.use { safeResponse ->
                if (!safeResponse.isSuccessful) {
                    throw IOException("Update download failed (HTTP ${safeResponse.code}).")
                }
                val body = safeResponse.body ?: throw IOException("The update download was empty.")
                val declaredLength = body.contentLength()
                if (declaredLength >= 0 && declaredLength != descriptor.sizeBytes) {
                    throw IOException("The update size did not match the server release details.")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastPublished = -1L
                FileOutputStream(partial).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            total += read
                            if (total > descriptor.sizeBytes) {
                                throw IOException("The update exceeded its advertised size.")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            if (total == descriptor.sizeBytes || total - lastPublished >= 256L * 1024L) {
                                lastPublished = total
                                onProgress(total)
                            }
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
                if (total != descriptor.sizeBytes) {
                    throw IOException("The update was incomplete. Nothing was installed.")
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (actualSha != descriptor.sha256) {
                    throw IOException("The update failed its security checksum. Nothing was installed.")
                }
            }

            Files.move(
                partial.toPath(),
                verified.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            verified
        } catch (failure: Exception) {
            partial.delete()
            verified.delete()
            throw failure
        }
    }

    fun deletePartialFiles() {
        directory.listFiles().orEmpty()
            .filter { it.name.endsWith(".part") }
            .forEach(File::delete)
    }

    fun deleteExpiredFiles(nowMillis: Long = System.currentTimeMillis()) {
        directory.listFiles().orEmpty()
            .filter { nowMillis - it.lastModified() > READY_FILE_MAX_AGE_MILLIS }
            .forEach(File::delete)
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("The update download folder could not be created.")
        }
        if (!directory.isDirectory) throw IOException("The update download folder is unavailable.")
    }

    private suspend fun openFollowingSafeRedirects(initialUrl: String): Response {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
                .header("Accept-Encoding", "identity")
                .build()
            val response = client.newCall(request).await()
            if (!response.isRedirect) return response
            val location = response.header("Location")
            response.close()
            if (redirectCount == MAX_REDIRECTS || location.isNullOrBlank()) {
                throw IOException("The update link redirected too many times.")
            }
            val resolved = runCatching { URI(url).resolve(location).toASCIIString() }.getOrNull()
            url = safeHttpsApkUrl(resolved)
                ?: throw IOException("The update redirected to an unsafe address.")
        }
        throw IOException("The update link redirected too many times.")
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        },
    )
}

private fun ArchiveVerificationProblem.userMessage(): String = when (this) {
    ArchiveVerificationProblem.UnreadableArchive ->
        "Android could not read the downloaded update. Nothing was installed."
    ArchiveVerificationProblem.WrongPackage ->
        "This file is not the D Company ERP app. Nothing was installed."
    ArchiveVerificationProblem.WrongVersion,
    ArchiveVerificationProblem.NotNewer ->
        "This file is not the exact newer release announced by the server. Nothing was installed."
    ArchiveVerificationProblem.MissingSignature,
    ArchiveVerificationProblem.UnexpectedSigner,
    ArchiveVerificationProblem.BrokenSigningLineage ->
        "The update signature does not match the installed D Company ERP app. Nothing was installed."
}
