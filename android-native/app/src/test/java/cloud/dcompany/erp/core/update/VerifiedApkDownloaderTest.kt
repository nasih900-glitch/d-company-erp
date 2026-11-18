package cloud.dcompany.erp.core.update

import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.net.ApiClient
import java.io.File
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VerifiedApkDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            protocols = listOf(Protocol.HTTP_1_1)
            start()
        }
        directory = Files.createTempDirectory("verified-update-test").toFile()
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .followRedirects(false)
            .followSslRedirects(false)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
    }

    @Test
    fun disconnectKeepsBytesAndRecreatedDownloaderResumesWithRangeAndIfRange() = runBlocking {
        val bytes = payload()
        val descriptor = descriptor(bytes)
        server.enqueue(
            fullResponse(bytes)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val first = runCatching { downloader().download(descriptor) {} }.exceptionOrNull()
        assertEquals(UpdateErrorCode.NETWORK_ERROR, (first as UpdatePreparationException).errorCode)
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val partial = partialFile(descriptor)
        assertTrue(partial.length() in 1 until bytes.size.toLong())

        val offset = partial.length().toInt()
        server.enqueue(partialResponse(bytes, offset))
        val completed = VerifiedApkDownloader(directory, client).download(descriptor) {}

        assertTrue(verifyFileSizeAndSha256(completed, descriptor))
        val resumed = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("bytes=$offset-", resumed?.getHeader("Range"))
        assertEquals(ETAG, resumed?.getHeader("If-Range"))
    }

    @Test
    fun serverIgnoringRangeRestartsFromZeroWithoutDuplicatingPrefix() = runBlocking {
        val bytes = payload()
        val descriptor = descriptor(bytes)
        seedPartial(descriptor, bytes.copyOfRange(0, 4_096))
        server.enqueue(fullResponse(bytes))

        val completed = downloader().download(descriptor) {}

        assertTrue(verifyFileSizeAndSha256(completed, descriptor))
        assertEquals("bytes=4096-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun invalidPartialRangeDeletesTaintedBytes() = runBlocking {
        val bytes = payload()
        val descriptor = descriptor(bytes)
        seedPartial(descriptor, bytes.copyOfRange(0, 4_096))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", ETAG)
                .setHeader("Content-Range", "bytes 4097-${bytes.lastIndex}/${bytes.size}")
                .setBody(Buffer().write(bytes, 4_096, bytes.size - 4_096)),
        )

        val failure = runCatching { downloader().download(descriptor) {} }.exceptionOrNull()

        assertEquals(UpdateErrorCode.SIZE_MISMATCH, (failure as UpdatePreparationException).errorCode)
        assertFalse(partialFile(descriptor).exists())
        assertFalse(metadataFile(descriptor).exists())
    }

    @Test
    fun changedOrMissingValidatorOnValidPartialRangeIsRejected() = runBlocking {
        val bytes = payload()
        val descriptor = descriptor(bytes)
        listOf("\"changed\"", null).forEach { validator ->
            seedPartial(descriptor, bytes.copyOfRange(0, 4_096))
            val response = partialResponse(bytes, 4_096)
            if (validator == null) response.removeHeader("ETag") else response.setHeader("ETag", validator)
            server.enqueue(response)

            val failure = runCatching { downloader().download(descriptor) {} }.exceptionOrNull()

            assertEquals(
                UpdateErrorCode.CHECKSUM_MISMATCH,
                (failure as UpdatePreparationException).errorCode,
            )
            assertFalse(partialFile(descriptor).exists())
        }
    }

    @Test
    fun resumeWithoutAnyValidatorIsAcceptedOnlyAfterFinalChecksum() = runBlocking {
        val bytes = payload()
        val descriptor = descriptor(bytes)
        val offset = 4_096
        seedPartial(descriptor, bytes.copyOfRange(0, offset), etag = null)
        server.enqueue(partialResponse(bytes, offset).removeHeader("ETag"))

        val completed = downloader().download(descriptor) {}

        assertTrue(verifyFileSizeAndSha256(completed, descriptor))
        val request = server.takeRequest()
        assertEquals("bytes=$offset-", request.getHeader("Range"))
        assertEquals(null, request.getHeader("If-Range"))
    }

    @Test
    fun weakValidatorIsIgnoredButNonIdentityEncodingIsRejected() = runBlocking {
        val bytes = payload()
        val descriptor = descriptor(bytes)
        server.enqueue(fullResponse(bytes).setHeader("ETag", "W/\"release-50\""))
        val weak = downloader().download(descriptor) {}
        assertTrue(verifyFileSizeAndSha256(weak, descriptor))
        weak.delete()

        server.enqueue(fullResponse(bytes).setHeader("Content-Encoding", "gzip"))
        val encoded = runCatching { downloader().download(descriptor) {} }.exceptionOrNull()
        assertEquals(
            UpdateErrorCode.CHECKSUM_MISMATCH,
            (encoded as UpdatePreparationException).errorCode,
        )
        assertFalse(partialFile(descriptor).exists())
    }

    @Test
    fun completePartialSurvivesRecreationAndIsRehashedBeforePromotion() = runBlocking {
        val bytes = payload()
        val descriptor = descriptor(bytes)
        seedPartial(descriptor, bytes)
        val recreated = VerifiedApkDownloader(directory, client)

        assertEquals(bytes.size.toLong(), recreated.pendingResume()?.downloadedBytes)
        val completed = recreated.download(descriptor) {}

        assertTrue(verifyFileSizeAndSha256(completed, descriptor))
        assertEquals(0, server.requestCount)
        assertFalse(metadataFile(descriptor).exists())
    }

    @Test
    fun corruptCompletePartialIsDeletedBeforeCleanRestart() = runBlocking {
        val bytes = payload()
        val descriptor = descriptor(bytes)
        val corrupt = bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        seedPartial(descriptor, corrupt)
        server.enqueue(fullResponse(bytes))

        val completed = downloader().download(descriptor) {}

        assertTrue(verifyFileSizeAndSha256(completed, descriptor))
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test
    fun incomplete416PerformsOneBoundedCleanRestart() = runBlocking {
        val bytes = payload()
        val descriptor = descriptor(bytes)
        seedPartial(descriptor, bytes.copyOfRange(0, 4_096))
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(fullResponse(bytes))

        val completed = downloader().download(descriptor) {}

        assertTrue(verifyFileSizeAndSha256(completed, descriptor))
        assertEquals("bytes=4096-", server.takeRequest().getHeader("Range"))
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test
    fun chunkedFullBodyIsAcceptedWithFinalExactHash() = runBlocking {
        val bytes = payload()
        val descriptor = descriptor(bytes)
        server.enqueue(
            MockResponse()
                .setHeader("ETag", ETAG)
                .setChunkedBody(Buffer().write(bytes), 2_047),
        )
        val full = downloader().download(descriptor) {}
        assertTrue(verifyFileSizeAndSha256(full, descriptor))
    }

    @Test
    fun chunkedResumedBodyIsAcceptedWithFinalExactHash() = runBlocking {
        val bytes = payload()
        val descriptor = descriptor(bytes)
        val offset = 4_096
        seedPartial(descriptor, bytes.copyOfRange(0, offset))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", ETAG)
                .setHeader("Content-Range", "bytes $offset-${bytes.lastIndex}/${bytes.size}")
                .setChunkedBody(Buffer().write(bytes, offset, bytes.size - offset), 1_013),
        )
        val resumed = downloader().download(descriptor) {}
        assertTrue(verifyFileSizeAndSha256(resumed, descriptor))
    }

    @Test
    fun checksumCorruptionAndOversizeResponsesAreDeleted() = runBlocking {
        val bytes = payload()
        val descriptor = descriptor(bytes)
        val corrupt = bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        server.enqueue(fullResponse(corrupt))
        val checksumFailure = runCatching { downloader().download(descriptor) {} }.exceptionOrNull()
        assertEquals(
            UpdateErrorCode.CHECKSUM_MISMATCH,
            (checksumFailure as UpdatePreparationException).errorCode,
        )
        assertFalse(partialFile(descriptor).exists())

        server.enqueue(
            MockResponse()
                .setHeader("ETag", ETAG)
                .setHeader("Content-Length", bytes.size + 1)
                .setBody(Buffer().write(bytes).writeByte(1)),
        )
        val oversizeFailure = runCatching { downloader().download(descriptor) {} }.exceptionOrNull()
        assertEquals(
            UpdateErrorCode.SIZE_MISMATCH,
            (oversizeFailure as UpdatePreparationException).errorCode,
        )
        assertFalse(partialFile(descriptor).exists())
    }

    @Test
    fun freshValidatedResponseKeepsExactPrefixAndMetadataAfterBodyReadTimeout() = runBlocking {
        val bytes = payload(256 * 1024)
        val descriptor = descriptor(bytes)
        val prefixSize = 4_096
        val injected = AtomicBoolean(false)
        val faultClient = client.newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val original = requireNotNull(response.body)
                val source = object : ForwardingSource(original.source()) {
                    private var delivered = 0L

                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (byteCount == 0L) return 0L
                        if (delivered == prefixSize.toLong()) {
                            injected.set(true)
                            throw SocketTimeoutException("Fixture body read timed out after a verified prefix")
                        }
                        val count = super.read(sink, minOf(byteCount, prefixSize - delivered))
                        if (count > 0) delivered += count
                        return count
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = source
                }).build()
            }
            .build()
        server.enqueue(fullResponse(bytes))

        val failure = runCatching {
            VerifiedApkDownloader(directory, faultClient).download(descriptor) {}
        }.exceptionOrNull() as UpdatePreparationException

        assertEquals(UpdateErrorCode.NETWORK_ERROR, failure.errorCode)
        assertTrue(injected.get())
        assertTrue(failure.cause is SocketTimeoutException)
        assertArrayEquals(bytes.copyOfRange(0, prefixSize), partialFile(descriptor).readBytes())
        assertEquals(PartialUpdateRecord(descriptor, ETAG), savedMetadata(descriptor))
        assertFalse(verifiedFile(descriptor).exists())
        val initial = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(initial)
        assertEquals(null, initial?.getHeader("Range"))

        server.enqueue(partialResponse(bytes, prefixSize))
        val completed = downloader().download(descriptor) {}
        assertTrue(verifyFileSizeAndSha256(completed, descriptor))
        val resumed = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("bytes=$prefixSize-", resumed?.getHeader("Range"))
        assertEquals(ETAG, resumed?.getHeader("If-Range"))
    }

    @Test
    fun realReadTimeoutRetainsPreviouslyValidatedResumeStateAndRecovers() = runBlocking {
        val shortClient = client.newBuilder()
            .readTimeout(75, TimeUnit.MILLISECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        assertTimedTransferRetainsResumeState(shortClient) {
            it.setBodyDelay(250, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun realWholeCallBudgetRetainsPreviouslyValidatedResumeStateAndRecovers() = runBlocking {
        val budgetClient = client.newBuilder()
            .readTimeout(1, TimeUnit.SECONDS)
            .callTimeout(400, TimeUnit.MILLISECONDS)
            .build()
        assertTimedTransferRetainsResumeState(budgetClient) {
            it.throttleBody(8 * 1024, 100, TimeUnit.MILLISECONDS)
        }
    }

    private suspend fun assertTimedTransferRetainsResumeState(
        timedClient: OkHttpClient,
        delayedResponse: (MockResponse) -> MockResponse,
    ) {
        val bytes = payload(256 * 1024)
        val descriptor = descriptor(bytes)
        val prefixSize = 4_096
        seedPartial(descriptor, bytes.copyOfRange(0, prefixSize))
        val timedAttempt = AtomicBoolean(true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val offset = request.getHeader("Range")?.removePrefix("bytes=")
                    ?.removeSuffix("-")?.toIntOrNull()
                    ?: return MockResponse().setResponseCode(400)
                if (request.getHeader("If-Range") != ETAG || offset !in prefixSize until bytes.size) {
                    return MockResponse().setResponseCode(400)
                }
                val response = partialResponse(bytes, offset)
                return if (timedAttempt.get()) delayedResponse(response) else response
            }
        }

        val failure = runCatching {
            VerifiedApkDownloader(directory, timedClient).download(descriptor) {}
        }.exceptionOrNull() as UpdatePreparationException

        assertEquals(UpdateErrorCode.NETWORK_ERROR, failure.errorCode)
        assertTrue(failure.cause is InterruptedIOException)
        val savedBytes = partialFile(descriptor).readBytes()
        assertTrue(savedBytes.size in prefixSize until bytes.size)
        assertArrayEquals(bytes.copyOfRange(0, savedBytes.size), savedBytes)
        assertEquals(PartialUpdateRecord(descriptor, ETAG), savedMetadata(descriptor))
        assertFalse(verifiedFile(descriptor).exists())

        timedAttempt.set(false)
        val resumed = AtomicReference<Request>()
        val recoveryClient = client.newBuilder().addNetworkInterceptor { chain ->
            resumed.set(chain.request())
            chain.proceed(chain.request())
        }.build()
        val completed = VerifiedApkDownloader(directory, recoveryClient).download(descriptor) {}
        assertTrue(verifyFileSizeAndSha256(completed, descriptor))
        assertEquals("bytes=${savedBytes.size}-", resumed.get()?.header("Range"))
        assertEquals(ETAG, resumed.get()?.header("If-Range"))
    }

    @Test
    fun slowTransferWithinBudgetCompletesAndPublishesTimeBasedProgress() = runBlocking {
        val bytes = payload(128 * 1024)
        val descriptor = descriptor(bytes)
        server.enqueue(fullResponse(bytes).throttleBody(8 * 1024, 80, TimeUnit.MILLISECONDS))
        val progress = mutableListOf<Long>()

        val completed = downloader().download(descriptor, progress::add)

        assertTrue(verifyFileSizeAndSha256(completed, descriptor))
        assertTrue(progress.any { it in 1 until bytes.size.toLong() })
    }

    @Test
    fun cancellationWaitsForWriterToStopAndLaterResumeUsesStableLength() = runBlocking {
        val bytes = payload(2 * 1024 * 1024)
        val descriptor = descriptor(bytes)
        server.enqueue(fullResponse(bytes).throttleBody(8 * 1024, 20, TimeUnit.MILLISECONDS))
        val started = java.util.concurrent.CountDownLatch(1)
        val job = async(Dispatchers.IO) {
            downloader().download(descriptor) { if (it > 0) started.countDown() }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val lengthAfterJoin = partialFile(descriptor).length()
        Thread.sleep(150)
        assertEquals(lengthAfterJoin, partialFile(descriptor).length())
        assertTrue(lengthAfterJoin in 1 until bytes.size.toLong())

        server.enqueue(partialResponse(bytes, lengthAfterJoin.toInt()))
        val completed = withTimeout(5_000) {
            VerifiedApkDownloader(directory, client).download(descriptor) {}
        }
        assertTrue(verifyFileSizeAndSha256(completed, descriptor))
    }

    @Test
    fun changedDescriptorAndUnsafeRedirectCannotReuseOldBytes() = runBlocking {
        val bytes = payload()
        val original = descriptor(bytes)
        seedPartial(original, bytes.copyOfRange(0, 4_096))
        val correctedBytes = payload().also { it[0] = 99 }
        val corrected = descriptor(correctedBytes)
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://updates.example.test/release.apk"),
        )

        val failure = runCatching { downloader().download(corrected) {} }.exceptionOrNull()

        assertEquals(UpdateErrorCode.INVALID_METADATA, (failure as UpdatePreparationException).errorCode)
        assertFalse(partialFile(original).exists())
        assertFalse(partialFile(corrected).exists())
    }

    @Test
    fun sameHashUrlCorrectionStartsCleanAgainstCorrectedUrl() = runBlocking {
        val bytes = payload()
        val original = descriptor(bytes).copy(url = server.url("/old.apk").toString())
        val corrected = original.copy(url = server.url("/corrected.apk").toString())
        seedPartial(original, bytes.copyOfRange(0, 4_096))
        server.enqueue(fullResponse(bytes))

        val completed = downloader().download(corrected) {}

        assertTrue(verifyFileSizeAndSha256(completed, corrected))
        val request = server.takeRequest()
        assertEquals("/corrected.apk", request.path)
        assertEquals(null, request.getHeader("Range"))
    }

    @Test
    fun pendingExplicitDiscardFinishesBeforeAReplacementDownloadCanWrite() = runBlocking {
        val bytes = payload()
        val descriptor = descriptor(bytes)
        seedPartial(descriptor, bytes.copyOfRange(0, 4_096))
        val downloader = downloader()
        val discard = downloader.requestDiscard()
        val recreated = downloader()
        server.enqueue(fullResponse(bytes))
        val pendingResume = async(Dispatchers.IO) { recreated.pendingResume() }
        val replacement = async(Dispatchers.IO) { recreated.download(descriptor) {} }
        delay(100)
        assertEquals(0, server.requestCount)
        assertFalse(pendingResume.isCompleted)

        downloader.discardAll(discard)
        assertEquals(null, pendingResume.await())
        val completed = replacement.await()

        assertTrue(verifyFileSizeAndSha256(completed, descriptor))
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    private fun downloader() = VerifiedApkDownloader(directory, client)

    private fun descriptor(bytes: ByteArray) = DirectUpdateDescriptor(
        url = server.url("/d-company.apk").toString(),
        versionCode = BuildConfig.VERSION_CODE + 1,
        versionName = "test-next",
        sha256 = sha256(bytes),
        sizeBytes = bytes.size.toLong(),
        expectedCurrentSignerSha256 = "12".repeat(32),
    )

    private fun fullResponse(bytes: ByteArray) = MockResponse()
        .setResponseCode(200)
        .setHeader("ETag", ETAG)
        .setHeader("Content-Length", bytes.size)
        .setBody(Buffer().write(bytes))

    private fun partialResponse(bytes: ByteArray, offset: Int) = MockResponse()
        .setResponseCode(206)
        .setHeader("ETag", ETAG)
        .setHeader("Content-Range", "bytes $offset-${bytes.lastIndex}/${bytes.size}")
        .setHeader("Content-Length", bytes.size - offset)
        .setBody(Buffer().write(bytes, offset, bytes.size - offset))

    private fun seedPartial(
        descriptor: DirectUpdateDescriptor,
        bytes: ByteArray,
        etag: String? = ETAG,
    ) {
        directory.mkdirs()
        partialFile(descriptor).writeBytes(bytes)
        metadataFile(descriptor).writeText(
            ApiClient.json.encodeToString(PartialUpdateRecord(descriptor, etag)),
        )
    }

    private fun partialFile(descriptor: DirectUpdateDescriptor) = File(
        directory,
        "d-company-${descriptor.versionCode}-${descriptor.sha256.take(12)}.apk.part",
    )

    private fun metadataFile(descriptor: DirectUpdateDescriptor) = File(
        directory,
        "d-company-${descriptor.versionCode}-${descriptor.sha256.take(12)}.resume.json",
    )

    private fun savedMetadata(descriptor: DirectUpdateDescriptor) =
        ApiClient.json.decodeFromString<PartialUpdateRecord>(metadataFile(descriptor).readText())

    private fun verifiedFile(descriptor: DirectUpdateDescriptor) =
        File(directory, partialFile(descriptor).name.removeSuffix(".part"))

    private fun payload(size: Int = 128 * 1024): ByteArray =
        ByteArray(size) { index -> (index * 31).toByte() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ETAG = "\"release-50\""
    }
}
