package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.ui.screens.shift.work.StartWorkJson
import cloud.dcompany.erp.ui.screens.shift.work.StartWorkOutcome
import cloud.dcompany.erp.ui.screens.shift.work.StartWorkReference
import cloud.dcompany.erp.ui.screens.shift.work.workDigest
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

internal fun StartWorkReference.knownAttempt() = KnownJournalAttempt(KnownJournalDomain.START_WORK,
    HandoverScope(actorId, companyId, branchId, terminalId), actionKey, actionId,
    "online-start-work", workDigest(StartWorkJson.encodeToString(this)))

internal data class ReviewedStartWorkResolution(val reference: StartWorkReference, val outcome: StartWorkOutcome) {
    fun validate(expected: KnownJournalAttempt) {
        reference.validate(); outcome.validate(reference)
        require(expected == reference.knownAttempt() && outcome.state in setOf("committed", "cancelled"))
    }
}

internal class StartWorkReviewRequest(val permit: HandoverCoordinator.StartWorkReviewPermit,
    private val frozenBody: okhttp3.RequestBody?) {
    fun matches(request: Request): Boolean = request.url.encodedQuery == null && request.url.toUri().path == request.url.encodedPath &&
        request.body === frozenBody && if (permit.mode == "actor_status") {
            request.method == "GET" && request.url.encodedPath == "/api/v1/pos/shifts/start-work/${permit.reference.actionId}"
        } else request.method == "POST" && request.url.encodedPath == "/api/v1/pos/shifts/start-work/recovery" &&
            request.header("Authorization") == null && request.header("X-Terminal-Id") == null
}

/** Only a verified retained exact action may use these two finite recovery routes. */
internal class StartWorkReviewCallFactory(private val delegate: Call.Factory,
    private val permit: HandoverCoordinator.StartWorkReviewPermit) : Call.Factory {
    override fun newCall(request: Request): Call {
        check(OperationDispatchContext.captured() == null && OwnerSupportDeliveryContext.captured() == null)
        val ref = permit.reference
        val owner = permit.mode != "actor_status"
        require(request.url.encodedQuery == null)
        require(request.url.toUri().path == request.url.encodedPath)
        val frozen = if (owner) {
            require(request.method == "POST" && request.url.encodedPath == "/api/v1/pos/shifts/start-work/recovery")
            val body = requireNotNull(request.body); require(body.contentLength() in 1..8192)
            val buffer = Buffer(); body.writeTo(buffer); val raw = buffer.readByteArray(); require(raw.size <= 8192)
            val obj = StartWorkJson.parseToJsonElement(raw.decodeToString()).jsonObject
            require(obj.keys == setOf("reference", "mode", "owner_email", "owner_password", "reason"))
            val wireRef = StartWorkJson.decodeFromJsonElement(StartWorkReference.serializer(), requireNotNull(obj["reference"]))
            require(wireRef == ref && obj["mode"]?.jsonPrimitive?.content == permit.mode.removePrefix("owner_"))
            request.newBuilder().removeHeader("Authorization").removeHeader("X-Terminal-Id")
                .method("POST", raw.toRequestBody(body.contentType())).build()
        } else {
            require(request.method == "GET" && request.url.encodedPath == "/api/v1/pos/shifts/start-work/${ref.actionId}")
            require(request.body == null)
            request
        }
        val call = delegate.newCall(frozen.newBuilder().tag(StartWorkReviewRequest::class.java, StartWorkReviewRequest(permit, frozen.body)).build())
        return object : Call by call {
            override fun execute(): Response = throw IOException("Use the finite asynchronous recovery client.")
            override fun clone(): Call = throw IOException("Recovery cannot be automatically cloned.")
            override fun enqueue(callback: Callback) = call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    try { callback.onFailure(call, e) } finally { runBlocking { permit.owner.endStartWorkReviewWire(permit) } }
                }
                override fun onResponse(call: Call, response: Response) {
                    try { callback.onResponse(call, response) } finally { runBlocking { permit.owner.endStartWorkReviewWire(permit) } }
                }
            })
        }
    }
}
