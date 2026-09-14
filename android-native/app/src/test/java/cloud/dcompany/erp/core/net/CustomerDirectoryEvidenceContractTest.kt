package cloud.dcompany.erp.core.net

import cloud.dcompany.erp.core.db.CustomerDirectoryStateEntity
import cloud.dcompany.erp.core.db.CustomerWriteState
import cloud.dcompany.erp.core.db.LocalCustomerEntity
import cloud.dcompany.erp.core.db.LocalOrderEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.sync.customerWriteActionId
import cloud.dcompany.erp.ui.screens.customerDirectoryEvidenceForUpdate
import cloud.dcompany.erp.ui.screens.customers.CustomerUpsertBody
import cloud.dcompany.erp.ui.screens.customers.newCustomerActionToken
import cloud.dcompany.erp.ui.screens.customers.rearmedForRetry
import cloud.dcompany.erp.ui.screens.customers.requireCustomerDirectorySnapshot
import cloud.dcompany.erp.ui.screens.gaming.SessionStartBody
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import okhttp3.Headers.Companion.headersOf
import retrofit2.Response

class CustomerDirectoryEvidenceContractTest {

    @Test
    fun `legacy customer action and explicit edit tokens remain stable through retry`() {
        val rejected = LocalCustomerEntity(
            localId = "local-1",
            phone = "9876543210",
            createdAtMillis = 1,
            state = CustomerWriteState.REJECTED,
            lastError = "retryable",
            version = 7,
        )
        val retried = rejected.rearmedForRetry()

        assertEquals(7L, retried.version)
        assertEquals(CustomerWriteState.PENDING, retried.state)
        assertEquals(null, retried.lastError)
        assertEquals("customer-upsert:local-1", retried.customerWriteActionId())

        assertEquals(
            "customer-upsert:legacy-create",
            rejected.copy(localId = "legacy-create", version = 0).customerWriteActionId(),
        )
        assertEquals(
            "customer-update:legacy-update",
            rejected.copy(
                localId = "legacy-update",
                serverId = "server-1",
                version = 0,
            ).customerWriteActionId(),
        )

        val priorToken = newCustomerActionToken()
        val editedToken = newCustomerActionToken()
        assertFalse(priorToken == editedToken)
        val initial = rejected.copy(clientActionToken = priorToken)
        val initialActionId = "customer-upsert:local-1:$priorToken"
        assertEquals(initialActionId, initial.customerWriteActionId())
        assertEquals(initialActionId, initial.rearmedForRetry().customerWriteActionId())
        assertEquals(initialActionId, initial.copy().customerWriteActionId())

        val edited = initial.copy(
            name = "Explicit edit",
            version = initial.version + 1,
            clientActionToken = editedToken,
        )
        val editedActionId = "customer-upsert:local-1:$editedToken"
        assertEquals(editedActionId, edited.customerWriteActionId())
        assertEquals(editedActionId, edited.rearmedForRetry().customerWriteActionId())
        assertEquals(editedActionId, edited.copy().customerWriteActionId())
    }

    @Test
    fun `unrelated draft edit cannot refresh stale customer evidence`() {
        val staleDraft = LocalOrderEntity(
            localId = "order-1",
            shiftId = "shift-1",
            type = "takeaway",
            customerName = "Customer Before Delete",
            customerPhone = "9876543210",
            customerDirectoryRevision = 503,
            customerDirectoryCompanyId = "company-1",
            estimateMinor = 1_000,
            createdAtMillis = 1,
            syncState = SyncState.DRAFT,
        )
        val refreshedState = CustomerDirectoryStateEntity(
            companyId = "company-1",
            deletionRevision = 504,
        )

        val unchanged = staleDraft.customerDirectoryEvidenceForUpdate(
            updatedCustomerName = staleDraft.customerName,
            updatedCustomerPhone = staleDraft.customerPhone,
            freshDirectoryState = refreshedState,
        )
        assertEquals(503L, unchanged.revision)
        assertEquals("company-1", unchanged.companyId)

        val legacyUnchanged = staleDraft.copy(
            customerDirectoryRevision = null,
            customerDirectoryCompanyId = null,
        ).customerDirectoryEvidenceForUpdate(
            updatedCustomerName = staleDraft.customerName,
            updatedCustomerPhone = staleDraft.customerPhone,
            freshDirectoryState = refreshedState,
        )
        assertEquals(null, legacyUnchanged.revision)
        assertEquals(null, legacyUnchanged.companyId)

        val replaced = staleDraft.customerDirectoryEvidenceForUpdate(
            updatedCustomerName = "Intentional New Customer",
            updatedCustomerPhone = staleDraft.customerPhone,
            freshDirectoryState = refreshedState,
        )
        assertEquals(504L, replaced.revision)
        assertEquals("company-1", replaced.companyId)
    }

    @Test
    fun `legacy actions omit both optional directory fields`() {
        val customer = ApiClient.json.parseToJsonElement(
            ApiClient.json.encodeToString(CustomerUpsertBody(phone = "9876543210")),
        ).jsonObject
        val gaming = ApiClient.json.parseToJsonElement(
            ApiClient.json.encodeToString(
                SessionStartBody(
                    stationId = "station-1",
                    shiftId = "shift-1",
                    startedAt = "2026-09-14T12:00:00Z",
                    customerPhone = "9876543210",
                    expectedRatePerHourMinor = 15_000,
                ),
            ),
        ).jsonObject
        val order = ApiClient.json.parseToJsonElement(
            ApiClient.json.encodeToString(
                CreateOrderRequest(
                    type = "takeaway",
                    shiftId = "shift-1",
                    lines = emptyList(),
                    customerPhone = "9876543210",
                ),
            ),
        ).jsonObject

        for (body in listOf(customer, gaming, order)) {
            assertFalse(body.containsKey("customer_directory_revision"))
            assertFalse(body.containsKey("customer_directory_company_id"))
        }
    }

    @Test
    fun `captured actions serialize the exact tenant revision pair`() {
        val body = ApiClient.json.parseToJsonElement(
            ApiClient.json.encodeToString(
                CreateOrderRequest(
                    type = "takeaway",
                    shiftId = "shift-1",
                    lines = emptyList(),
                    customerPhone = "9876543210",
                    customerDirectoryRevision = 503,
                    customerDirectoryCompanyId = "company-1",
                ),
            ),
        ).jsonObject

        assertEquals(JsonPrimitive(503), body["customer_directory_revision"])
        assertEquals(JsonPrimitive("company-1"), body["customer_directory_company_id"])
    }

    @Test
    fun `directory response requires the exact authenticated tenant and integer revision`() {
        val valid = Response.success(
            emptyList<cloud.dcompany.erp.ui.screens.customers.Customer>(),
            headersOf(
                "X-Customer-Directory-Company-Id", "company-1",
                "X-Customer-Directory-Revision", "503",
            ),
        ).requireCustomerDirectorySnapshot("company-1")
        assertEquals(503L, valid.deletionRevision)

        runCatching {
            Response.success(
                emptyList<cloud.dcompany.erp.ui.screens.customers.Customer>(),
                headersOf(
                    "X-Customer-Directory-Company-Id", "company-2",
                    "X-Customer-Directory-Revision", "503",
                ),
            ).requireCustomerDirectorySnapshot("company-1")
        }.onSuccess { error("cross-tenant directory response was accepted") }

        runCatching {
            Response.success(
                emptyList<cloud.dcompany.erp.ui.screens.customers.Customer>(),
                headersOf(
                    "X-Customer-Directory-Company-Id", "company-1",
                    "X-Customer-Directory-Revision", "5.0",
                ),
            ).requireCustomerDirectorySnapshot("company-1")
        }.onSuccess { error("malformed directory revision was accepted") }
    }
}
