package cloud.dcompany.erp.core.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ClientUpdateNotice
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val MAX_APK_BYTES = 512L * 1024L * 1024L
private const val MIN_FREE_SPACE_MARGIN_BYTES = 16L * 1024L * 1024L
private const val MAX_REDIRECTS = 5
private const val READY_FILE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
private const val PROGRESS_INTERVAL_BYTES = 256L * 1024L
private const val PROGRESS_INTERVAL_NANOS = 1_000_000_000L
private val SHA256 = Regex("^[0-9a-f]{64}$")
private val UPDATE_VERSION_NAME = Regex("^[0-9A-Za-z][0-9A-Za-z._+\\-]{0,79}$")
private val CONTENT_RANGE = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$")
private val STRONG_ETAG = Regex("^\"[\\x21\\x23-\\x5B\\x5D-\\x7E]+\"$")

internal fun isValidUpdateVersionName(raw: String): Boolean = UPDATE_VERSION_NAME.matches(raw)

@Serializable
data class DirectUpdateDescriptor(
    val url: String,
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val sizeBytes: Long,
    val expectedCurrentSignerSha256: String?,
)

internal class UpdatePreparationException(
    val errorCode: UpdateErrorCode,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

@Serializable
internal data class VerifiedUpdateArtifactRecord(
    val descriptor: DirectUpdateDescriptor,
    val fileName: String,
    val verifiedAtMillis: Long,
    val telemetryBinding: UpdateTelemetryBinding? = null,
)

@Serializable
internal data class PartialUpdateRecord(
    val descriptor: DirectUpdateDescriptor,
    val strongEtag: String? = null,
)

internal fun verifiedArtifactRecordIsStructurallyValid(
    record: VerifiedUpdateArtifactRecord,
    nowMillis: Long,
): Boolean =
    record.fileName.isNotBlank() &&
        record.fileName.length <= 160 &&
        !record.fileName.contains('/') &&
        !record.fileName.contains('\\') &&
        record.fileName.endsWith(".apk") &&
        record.verifiedAtMillis in 1..nowMillis &&
        nowMillis - record.verifiedAtMillis <= READY_FILE_MAX_AGE_MILLIS &&
        record.descriptor.versionCode > BuildConfig.VERSION_CODE &&
        isValidUpdateVersionName(record.descriptor.versionName) &&
        safeHttpsApkUrl(record.descriptor.url) == record.descriptor.url &&
        normalizeSha256(record.descriptor.sha256) == record.descriptor.sha256 &&
        record.descriptor.sizeBytes in 1..MAX_APK_BYTES &&
        record.descriptor.expectedCurrentSignerSha256 != null &&
        normalizeSha256(record.descriptor.expectedCurrentSignerSha256) ==
            record.descriptor.expectedCurrentSignerSha256

/** Metadata is committed only after both digest and PackageManager verification succeed. */
internal class VerifiedUpdateArtifactStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun save(record: VerifiedUpdateArtifactRecord): Boolean = synchronized(lock) {
        prefs.edit()
            .putString(KEY_RECORD, ApiClient.json.encodeToString(record))
            .commit()
    }

    fun restore(nowMillis: Long = System.currentTimeMillis()): VerifiedUpdateArtifactRecord? =
        synchronized(lock) {
            val raw = prefs.getString(KEY_RECORD, null) ?: return@synchronized null
            val record = runCatching {
                ApiClient.json.decodeFromString<VerifiedUpdateArtifactRecord>(raw)
            }.getOrNull()
            if (record == null || !verifiedArtifactRecordIsStructurallyValid(record, nowMillis)) {
                clearLocked()
                null
            } else {
                record
            }
        }

    fun clear(): Boolean = synchronized(lock) { clearLocked() }

    /** A cancelled older operation must never erase a newer operation's record. */
    fun clearIfMatches(record: VerifiedUpdateArtifactRecord): Boolean = synchronized(lock) {
        val current = prefs.getString(KEY_RECORD, null)?.let { raw ->
            runCatching {
                ApiClient.json.decodeFromString<VerifiedUpdateArtifactRecord>(raw)
            }.getOrNull()
        }
        if (current != record) return@synchronized false
        clearLocked()
    }

    private fun clearLocked(): Boolean = prefs.edit().remove(KEY_RECORD).commit()

    private companion object {
        const val PREFS_NAME = "dcompany_verified_update"
        const val KEY_RECORD = "artifact"
    }
}

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
        ?.takeIf(::isValidUpdateVersionName)
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
    data class Paused(
        val descriptor: DirectUpdateDescriptor,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AppUpdateUiState
    data class Verifying(val descriptor: DirectUpdateDescriptor) : AppUpdateUiState {
        val versionCode: Int get() = descriptor.versionCode
    }
    @ConsistentCopyVisibility
    data class Ready internal constructor(
        val descriptor: DirectUpdateDescriptor,
        val filePath: String,
        internal val telemetryBinding: UpdateTelemetryBinding? = null,
    ) : AppUpdateUiState {
        val versionCode: Int get() = descriptor.versionCode
    }
    @ConsistentCopyVisibility
    data class Failed internal constructor(
        val versionCode: Int?,
        val message: String,
        val descriptor: DirectUpdateDescriptor? = null,
        internal val errorCode: UpdateErrorCode = UpdateErrorCode.UNKNOWN,
        val canResume: Boolean = false,
    ) : AppUpdateUiState
}

enum class InstallerLaunchResult {
    OPENED,
    PERMISSION_REQUIRED,
    VERIFIED_FILE_UNAVAILABLE,
    INSTALLER_UNAVAILABLE,
}

internal fun AppUpdateUiState.matchesDescriptor(descriptor: DirectUpdateDescriptor): Boolean =
    when (this) {
        is AppUpdateUiState.Downloading -> this.descriptor == descriptor
        is AppUpdateUiState.Paused -> this.descriptor == descriptor
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
    private val artifactStore = VerifiedUpdateArtifactStore(app)
    private val telemetry = app.updateTelemetry
    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()
    @Volatile
    private var downloadJob: Job? = null
    @Volatile
    private var cleanupJob: Job? = null
    @Volatile
    private var operationGeneration = 0L
    @Volatile
    private var operationBinding: UpdateTelemetryBinding? = null

    init {
        recoverVerifiedArtifact()
    }

    fun download(notice: ClientUpdateNotice) {
        if (!BuildConfig.DIRECT_UPDATES_ENABLED) return
        val binding = telemetry.currentBinding()
        val validation = validateDirectUpdateMetadata(notice)
        if (validation !is DirectUpdateMetadataResult.Valid) {
            invalidateCurrentArtifact()
            telemetry.recordFailed(
                descriptor = null,
                fallbackVersionName = notice.latestVersionName ?: BuildConfig.VERSION_NAME,
                fallbackVersionCode = notice.latestVersionCode ?: BuildConfig.VERSION_CODE,
                errorCode = UpdateErrorCode.INVALID_METADATA,
                binding = binding,
            )
            _state.value = AppUpdateUiState.Failed(
                notice.latestVersionCode,
                "This release does not include complete verified-download details. Open the HTTPS update link instead.",
                errorCode = UpdateErrorCode.INVALID_METADATA,
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
        val pendingCleanup = cleanupJob
        val generation = ++operationGeneration
        operationBinding = binding
        previousJob?.cancel()
        (current as? AppUpdateUiState.Ready)?.let { File(it.filePath).delete() }
        artifactStore.clear()

        // Publish feedback synchronously so a slow DNS/TLS connection never
        // leaves the operator wondering whether the tap was accepted.
        _state.value = AppUpdateUiState.Downloading(
            descriptor = descriptor,
            downloadedBytes = 0,
            totalBytes = descriptor.sizeBytes,
        )
        telemetry.recordDownloadStarted(descriptor, binding)
        val replacementJob = viewModelScope.launch(
            context = Dispatchers.IO,
            start = CoroutineStart.LAZY,
        ) {
            var preparedFile: File? = null
            var persistedRecord: VerifiedUpdateArtifactRecord? = null
            try {
                // The old download owns the same private directory. Wait for
                // its cancellation cleanup before the replacement may delete,
                // write or atomically rename any artifact.
                previousJob?.cancelAndJoin()
                pendingCleanup?.join()
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
                preparedFile = file
                if (generation != operationGeneration) {
                    file.delete()
                    return@launch
                }
                publishIfCurrent(generation, AppUpdateUiState.Verifying(descriptor))
                telemetry.recordVerifying(binding)
                val verification = verifier.verify(file, descriptor)
                currentCoroutineContext().ensureActive()
                if (generation != operationGeneration) {
                    file.delete()
                    return@launch
                }
                when (verification) {
                    ArchiveVerificationResult.Verified -> {
                        val record = VerifiedUpdateArtifactRecord(
                            descriptor = descriptor,
                            fileName = file.name,
                            verifiedAtMillis = System.currentTimeMillis(),
                            telemetryBinding = binding,
                        )
                        if (!artifactStore.save(record)) {
                            file.delete()
                            throw UpdatePreparationException(
                                UpdateErrorCode.UNKNOWN,
                                "The verified update could not be saved safely. Download it again.",
                            )
                        }
                        persistedRecord = record
                        currentCoroutineContext().ensureActive()
                        if (generation != operationGeneration) {
                            artifactStore.clearIfMatches(record)
                            file.delete()
                            return@launch
                        }
                        telemetry.recordDownloadVerified(descriptor, binding)
                        publishIfCurrent(
                            generation,
                            AppUpdateUiState.Ready(
                                descriptor = descriptor,
                                filePath = file.absolutePath,
                                telemetryBinding = binding,
                            ),
                        )
                    }
                    is ArchiveVerificationResult.Rejected -> {
                        file.delete()
                        artifactStore.clear()
                        val errorCode = verification.problem.telemetryErrorCode()
                        telemetry.recordFailed(descriptor, errorCode = errorCode, binding = binding)
                        publishIfCurrent(
                            generation,
                            AppUpdateUiState.Failed(
                                descriptor.versionCode,
                                verification.problem.userMessage(),
                                descriptor,
                                errorCode,
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                persistedRecord?.let(artifactStore::clearIfMatches)
                publishIfCurrent(generation, AppUpdateUiState.Idle)
                throw cancelled
            } catch (failure: Exception) {
                artifactStore.clear()
                val errorCode = (failure as? UpdatePreparationException)?.errorCode
                    ?: UpdateErrorCode.UNKNOWN
                val canResume = errorCode == UpdateErrorCode.NETWORK_ERROR &&
                    downloader.resumableBytes(descriptor) > 0
                telemetry.recordFailed(descriptor, errorCode = errorCode, binding = binding)
                publishIfCurrent(
                    generation,
                    AppUpdateUiState.Failed(
                        descriptor.versionCode,
                        if (failure is UpdatePreparationException) {
                            failure.message ?: "The verified update could not be prepared."
                        } else {
                            "The verified update could not be prepared. Nothing was installed."
                        },
                        descriptor,
                        errorCode,
                        canResume,
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

    /** Pause keeps descriptor-bound bytes so the next tap can resume safely. */
    fun cancel() {
        val current = _state.value
        val descriptor = when (current) {
            is AppUpdateUiState.Downloading -> current.descriptor
            is AppUpdateUiState.Verifying -> current.descriptor
            else -> null
        }
        descriptor?.let { telemetry.recordCancelled(it, operationBinding) }
        operationGeneration += 1
        val currentJob = downloadJob
        currentJob?.cancel()
        _state.value = when (current) {
            is AppUpdateUiState.Downloading -> AppUpdateUiState.Paused(
                current.descriptor,
                current.downloadedBytes,
                current.totalBytes,
            )
            is AppUpdateUiState.Verifying -> AppUpdateUiState.Paused(
                current.descriptor,
                current.descriptor.sizeBytes,
                current.descriptor.sizeBytes,
            )
            else -> AppUpdateUiState.Idle
        }
    }

    fun verifiedFile(notice: ClientUpdateNotice): File? {
        val descriptor = (validateDirectUpdateMetadata(notice) as? DirectUpdateMetadataResult.Valid)
            ?.descriptor ?: return null
        val ready = _state.value as? AppUpdateUiState.Ready ?: return null
        if (ready.descriptor != descriptor) return null
        val file = File(ready.filePath).takeIf {
            it.isFile && it.parentFile == downloader.directory
        }
        if (file == null) {
            artifactStore.clear()
            telemetry.recordFailed(
                descriptor,
                errorCode = UpdateErrorCode.ARCHIVE_UNREADABLE,
                binding = ready.telemetryBinding,
            )
            _state.value = AppUpdateUiState.Failed(
                versionCode = descriptor.versionCode,
                message = "The verified update file is no longer available. Download it again.",
                descriptor = descriptor,
                errorCode = UpdateErrorCode.ARCHIVE_UNREADABLE,
            )
        }
        return file
    }

    fun discard() {
        val ready = _state.value as? AppUpdateUiState.Ready
        ready?.let { telemetry.recordCancelled(it.descriptor, it.telemetryBinding) }
        operationGeneration += 1
        val currentJob = downloadJob
        currentJob?.cancel()
        ready?.let { File(it.filePath).delete() }
        artifactStore.clear()
        telemetry.recordIdle()
        _state.value = AppUpdateUiState.Idle
        scheduleDiscard(currentJob)
    }

    fun installerLaunchResult(notice: ClientUpdateNotice, result: InstallerLaunchResult) {
        val descriptor = (validateDirectUpdateMetadata(notice) as? DirectUpdateMetadataResult.Valid)
            ?.descriptor ?: return
        val ready = _state.value as? AppUpdateUiState.Ready
        val binding = ready?.takeIf { it.descriptor == descriptor }?.telemetryBinding
        when (result) {
            InstallerLaunchResult.OPENED -> telemetry.recordInstallerOpened(descriptor, binding)
            InstallerLaunchResult.PERMISSION_REQUIRED -> telemetry.recordFailed(
                descriptor,
                errorCode = UpdateErrorCode.INSTALLER_PERMISSION_DENIED,
                binding = binding,
            )
            InstallerLaunchResult.VERIFIED_FILE_UNAVAILABLE -> telemetry.recordFailed(
                descriptor,
                errorCode = UpdateErrorCode.ARCHIVE_UNREADABLE,
                binding = binding,
            )
            InstallerLaunchResult.INSTALLER_UNAVAILABLE -> telemetry.recordFailed(
                descriptor,
                errorCode = UpdateErrorCode.INSTALLER_UNAVAILABLE,
                binding = binding,
            )
        }
    }

    override fun onCleared() {
        operationGeneration += 1
        downloadJob?.cancel()
        super.onCleared()
    }

    private fun invalidateCurrentArtifact() {
        operationGeneration += 1
        val currentJob = downloadJob
        currentJob?.cancel()
        (_state.value as? AppUpdateUiState.Ready)?.let { File(it.filePath).delete() }
        artifactStore.clear()
        scheduleDiscard(currentJob)
    }

    private fun recoverVerifiedArtifact() {
        val generation = operationGeneration
        viewModelScope.launch(Dispatchers.IO) {
            downloader.deleteExpiredFiles()
            if (!BuildConfig.DIRECT_UPDATES_ENABLED) {
                artifactStore.clear()
                if (generation == operationGeneration) telemetry.recordIdle()
                return@launch
            }
            val record = artifactStore.restore()
            if (record == null) {
                val pending = downloader.pendingResume()
                if (generation == operationGeneration) {
                    if (pending != null) {
                        _state.value = AppUpdateUiState.Paused(
                            pending.descriptor,
                            pending.downloadedBytes,
                            pending.descriptor.sizeBytes,
                        )
                    } else {
                        telemetry.recordIdle()
                    }
                }
                return@launch
            }
            val file = File(downloader.directory, record.fileName)
            val validFile = file.parentFile == downloader.directory &&
                verifyFileSizeAndSha256(file, record.descriptor)
            val archiveResult = if (validFile) {
                runCatching { verifier.verify(file, record.descriptor) }.getOrNull()
            } else {
                null
            }
            if (generation != operationGeneration) return@launch
            if (archiveResult == ArchiveVerificationResult.Verified) {
                telemetry.restoreVerifiedState(record.telemetryBinding)
                publishIfCurrent(
                    generation,
                    AppUpdateUiState.Ready(
                        descriptor = record.descriptor,
                        filePath = file.absolutePath,
                        telemetryBinding = record.telemetryBinding,
                    ),
                )
            } else {
                file.delete()
                artifactStore.clear()
            }
        }
    }

    private fun publishIfCurrent(generation: Long, next: AppUpdateUiState) {
        if (generation == operationGeneration) _state.value = next
    }

    /** A later download joins this job; it never cancels an explicit discard. */
    private fun scheduleDiscard(currentJob: Job?) {
        val previousCleanup = cleanupJob
        val discardRequest = downloader.requestDiscard()
        val replacement = CLEANUP_SCOPE.launch(start = CoroutineStart.LAZY) {
            currentJob?.cancelAndJoin()
            previousCleanup?.join()
            downloader.discardAll(discardRequest)
        }
        cleanupJob = replacement
        replacement.invokeOnCompletion {
            if (cleanupJob === replacement) cleanupJob = null
        }
        replacement.start()
    }

    private companion object {
        val CLEANUP_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

internal class VerifiedApkDownloader internal constructor(
    val directory: File,
    private val client: OkHttpClient,
) {
    constructor(context: Context) : this(
        directory = File(context.cacheDir, "verified-updates"),
        client = productionUpdateClient(),
    )

    private val fileMutex = PROCESS_FILE_MUTEX

    // Deliberately use currently usable bytes plus a fixed margin. Asking
    // StorageManager to evict other app caches would make a background ERP
    // update unexpectedly destructive; a streaming ENOSPC still fails closed.
    @SuppressLint("UsableSpace")
    suspend fun download(
        descriptor: DirectUpdateDescriptor,
        onProgress: (Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        awaitPendingDiscards()
        fileMutex.withLock {
        ensureDirectory()
        val files = filesFor(descriptor)
        reconcileFiles(files, descriptor)

        if (verifyFileSizeAndSha256(files.verified, descriptor)) return@withLock files.verified
        if (files.partial.length() == descriptor.sizeBytes) {
            if (verifyFileSizeAndSha256(files.partial, descriptor)) {
                atomicMove(files.partial, files.verified)
                files.metadata.delete()
                return@withLock files.verified
            }
            deleteResumeFiles(files)
        }

        val usableSpace = directory.usableSpace
        val remainingBytes = descriptor.sizeBytes - files.partial.length().coerceAtMost(descriptor.sizeBytes)
        if (usableSpace > 0 && usableSpace < remainingBytes + MIN_FREE_SPACE_MARGIN_BYTES) {
            throw UpdatePreparationException(
                UpdateErrorCode.INSUFFICIENT_STORAGE,
                "Not enough free storage for this update.",
            )
        }

        try {
            downloadWithAtMostOneCleanRestart(files, descriptor, onProgress)
            if (!verifyFileSizeAndSha256(files.partial, descriptor)) {
                deleteResumeFiles(files)
                throw UpdatePreparationException(
                    UpdateErrorCode.CHECKSUM_MISMATCH,
                    "The update failed its security checksum. Nothing was installed.",
                )
            }
            atomicMove(files.partial, files.verified)
            files.metadata.delete()
            files.verified
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: UpdatePreparationException) {
            if (failure.errorCode in TAINTED_DOWNLOAD_ERRORS) deleteResumeFiles(files)
            throw failure
        }
        }
    }

    internal fun requestDiscard(): DiscardRequest = synchronized(PENDING_DISCARDS) {
        val key = directory.absoluteFile.normalize().path
        val completion = CompletableDeferred<Unit>()
        DiscardRequest(key, PENDING_DISCARDS.put(key, completion), completion)
    }

    suspend fun discardAll(request: DiscardRequest = requestDiscard()) =
        withContext(Dispatchers.IO) {
            try {
                request.predecessor?.await()
                fileMutex.withLock {
                    directory.listFiles().orEmpty().forEach(File::delete)
                }
            } finally {
                request.completion.complete(Unit)
                PENDING_DISCARDS.remove(request.key, request.completion)
            }
        }

    suspend fun resumableBytes(descriptor: DirectUpdateDescriptor): Long =
        withContext(Dispatchers.IO) { fileMutex.withLock {
            val files = filesFor(descriptor)
            val record = readPartialRecord(files.metadata)
            files.partial.length().takeIf {
                record?.descriptor == descriptor && it in 1..descriptor.sizeBytes
            } ?: 0L
        } }

    suspend fun pendingResume(): PendingUpdateResume? =
        withContext(Dispatchers.IO) {
            awaitPendingDiscards()
            fileMutex.withLock {
                val metadataFiles = directory.listFiles().orEmpty()
                    .filter { it.name.endsWith(".resume.json") }
                val pending = metadataFiles.singleOrNull()?.let { metadata ->
                    val record = readPartialRecord(metadata) ?: return@let null
                    if (!partialDescriptorIsStructurallyValid(record)) return@let null
                    val files = filesFor(record.descriptor)
                    val downloaded = files.partial.length()
                    if (metadata != files.metadata || downloaded !in 1..record.descriptor.sizeBytes) {
                        null
                    } else {
                        PendingUpdateResume(record.descriptor, downloaded)
                    }
                }
                if (pending == null && metadataFiles.isNotEmpty()) {
                    directory.listFiles().orEmpty()
                        .filter { it.name.endsWith(".part") || it.name.endsWith(".resume.json") }
                        .forEach(File::delete)
                }
                pending
            }
        }

    suspend fun deleteExpiredFiles(nowMillis: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { fileMutex.withLock {
            directory.listFiles().orEmpty()
                .filter { nowMillis - it.lastModified() > READY_FILE_MAX_AGE_MILLIS }
                .forEach(File::delete)
        } }

    private suspend fun downloadWithAtMostOneCleanRestart(
        files: DownloadFiles,
        descriptor: DirectUpdateDescriptor,
        onProgress: (Long) -> Unit,
    ) {
        var cleanRestartAvailable = true
        while (true) {
            val record = readPartialRecord(files.metadata)
                ?.takeIf { it.descriptor == descriptor }
                ?: PartialUpdateRecord(descriptor)
            val offset = files.partial.length()
            onProgress(offset)
            val requestResult = executeRequestFollowingSafeRedirects(
                initialUrl = descriptor.url,
                offset = offset,
                strongEtag = record.strongEtag,
            ) { response ->
                consumeDownloadResponse(response, files, descriptor, record, offset, onProgress)
            }
            if (requestResult != DownloadResponseResult.RestartClean) return
            if (!cleanRestartAvailable) {
                deleteResumeFiles(files)
                throw UpdatePreparationException(
                    UpdateErrorCode.SIZE_MISMATCH,
                    "The update server could not resume this saved download safely.",
                )
            }
            cleanRestartAvailable = false
            deleteResumeFiles(files)
        }
    }

    private fun consumeDownloadResponse(
        response: Response,
        files: DownloadFiles,
        descriptor: DirectUpdateDescriptor,
        record: PartialUpdateRecord,
        requestedOffset: Long,
        onProgress: (Long) -> Unit,
    ): DownloadResponseResult {
        if (response.code == 416) {
            return if (
                requestedOffset == descriptor.sizeBytes &&
                verifyFileSizeAndSha256(files.partial, descriptor)
            ) DownloadResponseResult.Complete else DownloadResponseResult.RestartClean
        }
        if (response.code !in setOf(200, 206)) {
            throw UpdatePreparationException(
                UpdateErrorCode.HTTP_ERROR,
                "Update download failed (HTTP ${response.code}). Saved download progress was kept.",
            )
        }

        val append = response.code == 206
        val contentEncoding = response.header("Content-Encoding")?.trim()
        if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) {
            throw UpdatePreparationException(
                UpdateErrorCode.CHECKSUM_MISMATCH,
                "The update server returned encoded bytes that cannot be resumed safely.",
            )
        }
        // Weak or malformed validators are never persisted or sent in If-Range.
        // The descriptor SHA-256 remains the authority for a fresh full body.
        val responseEtag = response.header("ETag")?.trim()?.takeIf(::isStrongEtag)
        if (append) {
            validatePartialResponse(response, descriptor, requestedOffset, record.strongEtag, responseEtag)
        }
        val start = if (append) requestedOffset else 0L
        val expectedBodyBytes = descriptor.sizeBytes - start
        val body = response.body ?: throw taintedSizeError("The update download was empty.")
        if (body.contentLength() >= 0 && body.contentLength() != expectedBodyBytes) {
            throw taintedSizeError("The update size did not match the server release details.")
        }

        writePartialRecordAtomically(
            files.metadata,
            PartialUpdateRecord(descriptor, responseEtag),
        )
        var total = start
        var lastPublished = start
        var lastPublishedAt = System.nanoTime()
        FileOutputStream(files.partial, append).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > descriptor.sizeBytes) throw taintedSizeError(
                        "The update exceeded its advertised size.",
                    )
                    output.write(buffer, 0, read)
                    val now = System.nanoTime()
                    if (
                        total == descriptor.sizeBytes ||
                        total - lastPublished >= PROGRESS_INTERVAL_BYTES ||
                        now - lastPublishedAt >= PROGRESS_INTERVAL_NANOS
                    ) {
                        lastPublished = total
                        lastPublishedAt = now
                        onProgress(total)
                    }
                }
            }
            output.flush()
            output.fd.sync()
        }
        if (total != descriptor.sizeBytes) {
            throw UpdatePreparationException(
                UpdateErrorCode.NETWORK_ERROR,
                "The connection ended early. Your saved download can resume.",
            )
        }
        return DownloadResponseResult.Complete
    }

    private fun validatePartialResponse(
        response: Response,
        descriptor: DirectUpdateDescriptor,
        requestedOffset: Long,
        storedEtag: String?,
        responseEtag: String?,
    ) {
        val match = response.header("Content-Range")?.let(CONTENT_RANGE::matchEntire)
            ?: throw taintedSizeError("The update server returned an invalid resume range.")
        val (start, end, total) = match.destructured
        if (
            start.toLongOrNull() != requestedOffset ||
            end.toLongOrNull() != descriptor.sizeBytes - 1 ||
            total.toLongOrNull() != descriptor.sizeBytes
        ) throw taintedSizeError("The update server returned the wrong resume range.")
        if (storedEtag != null && responseEtag != storedEtag) {
            throw UpdatePreparationException(
                UpdateErrorCode.CHECKSUM_MISMATCH,
                "The update changed while it was downloading. Start it again.",
            )
        }
    }

    private fun reconcileFiles(files: DownloadFiles, descriptor: DirectUpdateDescriptor) {
        val record = readPartialRecord(files.metadata)
        val partialIsReusable = record?.descriptor == descriptor &&
            files.partial.isFile && files.partial.length() in 0..descriptor.sizeBytes
        directory.listFiles().orEmpty()
            .filter { it != files.partial && it != files.metadata && it != files.verified }
            .forEach(File::delete)
        if (!partialIsReusable) {
            files.partial.delete()
            files.metadata.delete()
        }
        if (files.verified.exists() && !verifyFileSizeAndSha256(files.verified, descriptor)) {
            files.verified.delete()
        }
    }

    private fun filesFor(descriptor: DirectUpdateDescriptor): DownloadFiles {
        val stem = "d-company-${descriptor.versionCode}-${descriptor.sha256.take(12)}"
        return DownloadFiles(
            partial = File(directory, "$stem.apk.part"),
            metadata = File(directory, "$stem.resume.json"),
            verified = File(directory, "$stem.apk"),
        )
    }

    private fun readPartialRecord(file: File): PartialUpdateRecord? = runCatching {
        if (!file.isFile || file.length() !in 1..16_384) return@runCatching null
        ApiClient.json.decodeFromString<PartialUpdateRecord>(file.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun partialDescriptorIsStructurallyValid(record: PartialUpdateRecord): Boolean {
        val descriptor = record.descriptor
        return descriptor.versionCode > BuildConfig.VERSION_CODE &&
            isValidUpdateVersionName(descriptor.versionName) &&
            safeHttpsApkUrl(descriptor.url) == descriptor.url &&
            normalizeSha256(descriptor.sha256) == descriptor.sha256 &&
            descriptor.sizeBytes in 1..MAX_APK_BYTES &&
            descriptor.expectedCurrentSignerSha256 != null &&
            normalizeSha256(descriptor.expectedCurrentSignerSha256) ==
                descriptor.expectedCurrentSignerSha256 &&
            (record.strongEtag == null || isStrongEtag(record.strongEtag))
    }

    private fun writePartialRecordAtomically(file: File, record: PartialUpdateRecord) {
        val temporary = File(directory, "${file.name}.tmp")
        val bytes = ApiClient.json.encodeToString(record).toByteArray(Charsets.UTF_8)
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        atomicMove(temporary, file)
    }

    private fun deleteResumeFiles(files: DownloadFiles) {
        files.partial.delete()
        files.metadata.delete()
    }

    private fun atomicMove(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) {
            throw UpdatePreparationException(
                UpdateErrorCode.INSUFFICIENT_STORAGE,
                "The update download folder could not be created.",
            )
        }
        if (!directory.isDirectory) throw UpdatePreparationException(
            UpdateErrorCode.INSUFFICIENT_STORAGE,
            "The update download folder is unavailable.",
        )
    }

    private suspend fun awaitPendingDiscards() {
        val key = directory.absoluteFile.normalize().path
        while (true) {
            val pending = PENDING_DISCARDS[key] ?: return
            pending.await()
        }
    }

    private suspend fun <T> executeRequestFollowingSafeRedirects(
        initialUrl: String,
        offset: Long,
        strongEtag: String?,
        consume: (Response) -> T,
    ): T {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
                .header("Accept-Encoding", "identity")
                .apply {
                    if (offset > 0) {
                        header("Range", "bytes=$offset-")
                        strongEtag?.let { header("If-Range", it) }
                    }
                }
                .build()
            val call = client.newCall(request)
            val result = try {
                call.executeAndConsume { response ->
                    if (!response.isRedirect) return@executeAndConsume consume(response)
                    RedirectResponse(response.header("Location"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: UpdatePreparationException) {
                throw failure
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                throw UpdatePreparationException(
                    UpdateErrorCode.NETWORK_ERROR,
                    "The connection was interrupted. Your saved download can resume.",
                    failure,
                )
            }
            if (result !is RedirectResponse) {
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
            if (redirectCount == MAX_REDIRECTS || result.location.isNullOrBlank()) {
                throw UpdatePreparationException(
                    UpdateErrorCode.HTTP_ERROR,
                    "The update link redirected too many times.",
                )
            }
            val resolved = runCatching { URI(url).resolve(result.location).toASCIIString() }.getOrNull()
            url = safeHttpsApkUrl(resolved) ?: throw UpdatePreparationException(
                UpdateErrorCode.INVALID_METADATA,
                "The update redirected to an unsafe address.",
            )
        }
        error("redirect loop exhausted")
    }

    private data class DownloadFiles(val partial: File, val metadata: File, val verified: File)
    private data class RedirectResponse(val location: String?)
    private enum class DownloadResponseResult { Complete, RestartClean }

    private companion object {
        val PROCESS_FILE_MUTEX = Mutex()
        val PENDING_DISCARDS = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val TAINTED_DOWNLOAD_ERRORS = setOf(
            UpdateErrorCode.SIZE_MISMATCH,
            UpdateErrorCode.CHECKSUM_MISMATCH,
            UpdateErrorCode.INVALID_METADATA,
        )

        fun productionUpdateClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES)
            .build()

        fun isStrongEtag(value: String): Boolean =
            STRONG_ETAG.matches(value)

        fun taintedSizeError(message: String) = UpdatePreparationException(
            UpdateErrorCode.SIZE_MISMATCH,
            message,
        )
    }
}

internal data class DiscardRequest(
    val key: String,
    val predecessor: CompletableDeferred<Unit>?,
    val completion: CompletableDeferred<Unit>,
)

internal data class PendingUpdateResume(
    val descriptor: DirectUpdateDescriptor,
    val downloadedBytes: Long,
)

internal fun verifyFileSizeAndSha256(
    file: File,
    descriptor: DirectUpdateDescriptor,
): Boolean {
    if (!file.isFile || file.length() != descriptor.sizeBytes) return false
    val digest = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                md.update(buffer, 0, read)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull() ?: return false
    return digest == descriptor.sha256
}

/** Cancellation aborts the socket, then waits for the writer to close before returning. */
private suspend fun <T> Call.executeAndConsume(consume: (Response) -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) this@executeAndConsume.cancel()
        }
    }
    try {
        execute().use(consume)
    } finally {
        finished.set(true)
        withContext(NonCancellable) { cancellationWatcher.cancelAndJoin() }
    }
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

private fun ArchiveVerificationProblem.telemetryErrorCode(): UpdateErrorCode = when (this) {
    ArchiveVerificationProblem.UnreadableArchive,
    ArchiveVerificationProblem.MissingSignature -> UpdateErrorCode.ARCHIVE_UNREADABLE
    ArchiveVerificationProblem.WrongPackage -> UpdateErrorCode.PACKAGE_MISMATCH
    ArchiveVerificationProblem.WrongVersion,
    ArchiveVerificationProblem.NotNewer -> UpdateErrorCode.VERSION_MISMATCH
    ArchiveVerificationProblem.UnexpectedSigner,
    ArchiveVerificationProblem.BrokenSigningLineage -> UpdateErrorCode.SIGNER_MISMATCH
}
