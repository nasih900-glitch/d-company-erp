package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditableOutboxRejectionCasDaoTest {

    private lateinit var db: ErpDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun staleCustomerRefusalCannotRejectEditSavedDuringFlight() = runBlocking {
        val dao = db.customerDao()
        val sent = LocalCustomerEntity(
            localId = "customer-edit",
            serverId = "server-customer",
            name = "Name sent",
            createdAtMillis = 1_000,
            version = 4,
        )
        dao.upsertLocal(sent)
        dao.upsertLocal(sent.copy(name = "Newer name", version = 5))

        assertEquals(0, dao.markRejected(sent.localId, "Old request refused", sent.version))

        val newer = dao.getLocal(sent.localId)!!
        assertEquals("Newer name", newer.name)
        assertEquals(5L, newer.version)
        assertEquals(CustomerWriteState.PENDING, newer.state)
        assertNull(newer.lastError)
        assertEquals(1, dao.markRejected(newer.localId, "Current request refused", newer.version))
        assertEquals(CustomerWriteState.REJECTED, dao.getLocal(newer.localId)?.state)
        assertEquals("Current request refused", dao.getLocal(newer.localId)?.lastError)
    }

    @Test
    fun staleStaffRefusalCannotRejectEditSavedDuringFlight() = runBlocking {
        val dao = db.staffDao()
        val sent = LocalStaffEntity(
            localId = "staff-edit",
            serverId = "server-staff",
            name = "Name sent",
            createdAtMillis = 1_000,
            version = 7,
        )
        dao.upsertLocal(sent)
        dao.upsertLocal(sent.copy(name = "Newer name", version = 8))

        assertEquals(0, dao.markRejected(sent.localId, "Old request refused", sent.version))

        val newer = dao.getLocal(sent.localId)!!
        assertEquals("Newer name", newer.name)
        assertEquals(8L, newer.version)
        assertEquals(StaffWriteState.PENDING, newer.state)
        assertNull(newer.lastError)
        assertEquals(1, dao.markRejected(newer.localId, "Current request refused", newer.version))
        assertEquals(StaffWriteState.REJECTED, dao.getLocal(newer.localId)?.state)
        assertEquals("Current request refused", dao.getLocal(newer.localId)?.lastError)
    }

    @Test
    fun revokedStaffAuthorityDiscardUsesVersionCas() = runBlocking {
        val dao = db.staffDao()
        val sent = LocalStaffEntity(
            localId = "staff-revoked",
            serverId = "server-staff",
            name = "Sent name",
            createdAtMillis = 1_000,
            version = 3,
        )
        dao.upsertLocal(sent)
        dao.upsertLocal(sent.copy(name = "Newer name", version = 4))

        assertEquals(0, dao.deleteIfVersion(sent.localId, sent.version))
        assertEquals("Newer name", dao.getLocal(sent.localId)?.name)
        assertEquals(1, dao.deleteIfVersion(sent.localId, 4))
        assertNull(dao.getLocal(sent.localId))
    }

    @Test
    fun staleIngredientRefusalCannotRejectEditSavedDuringFlight() = runBlocking {
        val dao = db.inventoryDao()
        val sent = LocalIngredientEntity(
            localId = "ingredient-edit",
            serverId = "server-ingredient",
            name = "Ingredient sent",
            baseUnit = "kg",
            createdAtMillis = 1_000,
            version = 10,
        )
        dao.upsertLocalIngredient(sent)
        dao.upsertLocalIngredient(sent.copy(name = "Newer ingredient", version = 11))

        assertEquals(
            0,
            dao.markIngredientRejected(sent.localId, "Old request refused", sent.version),
        )

        val newer = dao.getLocalIngredient(sent.localId)!!
        assertEquals("Newer ingredient", newer.name)
        assertEquals(11L, newer.version)
        assertEquals(IngredientWriteState.PENDING, newer.state)
        assertNull(newer.lastError)
        assertEquals(
            1,
            dao.markIngredientRejected(newer.localId, "Current request refused", newer.version),
        )
        assertEquals(IngredientWriteState.REJECTED, dao.getLocalIngredient(newer.localId)?.state)
        assertEquals("Current request refused", dao.getLocalIngredient(newer.localId)?.lastError)
    }

    @Test
    fun staleSupplierRefusalCannotRejectEditSavedDuringFlight() = runBlocking {
        val dao = db.inventoryDao()
        val sent = LocalSupplierEntity(
            localId = "supplier-edit",
            serverId = "server-supplier",
            name = "Supplier sent",
            createdAtMillis = 1_000,
            version = 12,
        )
        dao.upsertLocalSupplier(sent)
        dao.upsertLocalSupplier(sent.copy(name = "Newer supplier", version = 13))

        assertEquals(
            0,
            dao.markSupplierRejected(sent.localId, "Old request refused", sent.version),
        )

        val newer = dao.getLocalSupplier(sent.localId)!!
        assertEquals("Newer supplier", newer.name)
        assertEquals(13L, newer.version)
        assertEquals(SupplierWriteState.PENDING, newer.state)
        assertNull(newer.lastError)
        assertEquals(
            1,
            dao.markSupplierRejected(newer.localId, "Current request refused", newer.version),
        )
        assertEquals(SupplierWriteState.REJECTED, dao.getLocalSupplier(newer.localId)?.state)
        assertEquals("Current request refused", dao.getLocalSupplier(newer.localId)?.lastError)
    }

    @Test
    fun ingredientCreatePayloadBecomesImmutableBeforeNetworkAttempt() = runBlocking {
        val dao = db.inventoryDao()
        val draft = LocalIngredientEntity(
            localId = "ingredient-create",
            sku = "MILK",
            name = "Milk",
            baseUnit = "ml",
            reorderThreshold = 1.0,
            reorderQty = 2.0,
            createdAtMillis = 1_000,
        )
        dao.upsertLocalIngredient(draft)

        assertEquals(1, dao.claimIngredientCreate(draft.localId, draft.version))
        assertEquals(IngredientWriteState.CREATE_ATTEMPTED, dao.getLocalIngredient(draft.localId)?.state)
        assertEquals(draft.name, dao.pushableIngredients().single().name)
        assertEquals(
            0,
            dao.updateMutableIngredientCreate(
                draft.localId, draft.version, "MILK-2", "Changed", "ml", 3.0, 4.0,
            ),
        )
        assertEquals(0, dao.deleteMutableIngredientCreate(draft.localId))
        assertEquals(0, dao.retryRejectedIngredient(draft.localId))
        assertEquals("Milk", dao.getLocalIngredient(draft.localId)?.name)
    }

    @Test
    fun supplierCreatePayloadBecomesImmutableBeforeNetworkAttempt() = runBlocking {
        val dao = db.inventoryDao()
        val draft = LocalSupplierEntity(
            localId = "supplier-create",
            name = "Original supplier",
            contact = "111",
            createdAtMillis = 1_000,
        )
        dao.upsertLocalSupplier(draft)

        assertEquals(1, dao.claimSupplierCreate(draft.localId, draft.version))
        assertEquals(SupplierWriteState.CREATE_ATTEMPTED, dao.getLocalSupplier(draft.localId)?.state)
        assertEquals(draft.name, dao.pushableSuppliers().single().name)
        assertEquals(
            0,
            dao.updateMutableSupplierCreate(
                draft.localId, draft.version, "Changed", "222", null, null,
            ),
        )
        assertEquals(0, dao.deleteMutableSupplierCreate(draft.localId))
        assertEquals(0, dao.retryRejectedSupplier(draft.localId))
        assertEquals("Original supplier", dao.getLocalSupplier(draft.localId)?.name)
    }
}
