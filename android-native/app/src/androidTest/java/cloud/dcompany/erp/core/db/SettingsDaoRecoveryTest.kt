package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDaoRecoveryTest {

    private lateinit var db: ErpDatabase
    private lateinit var dao: SettingsDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
        dao = db.settingsDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun companyRetryIsRejectedOnlyAndPreservesIdentity() = runBlocking {
        val original = companyEdit("stable-company-edit")
        dao.insertLocalCompanyEdit(original)
        dao.markCompanyEditRejected(original.localId, "Timezone was refused")

        assertEquals(1, dao.retryCompanyEdit(original.localId))
        assertEquals(0, dao.retryCompanyEdit(original.localId))
        assertEquals(
            original.copy(syncState = SettingsWriteState.PENDING, lastError = null),
            dao.pushableCompanyEdit(),
        )

        // Pending/ambiguous work can never be discarded by the recovery UI.
        assertEquals(0, dao.discardRejectedCompanyEdit(original.localId))
        dao.markCompanyEditRejected(original.localId, "Timezone was refused")
        assertEquals(1, dao.discardRejectedCompanyEdit(original.localId))
        assertEquals(0, dao.discardRejectedCompanyEdit(original.localId))
        assertNull(dao.observePendingCompanyEdit().first())
    }

    @Test
    fun branchAndTerminalRecoveryAreGuardedAndKeepLocalIds() = runBlocking {
        val branch = LocalBranchEntity(
            localId = "stable-branch-create",
            name = "Second floor",
            code = "F2",
            invoiceSeriesCode = "F2",
            address = null,
            timezone = "Asia/Kolkata",
            opensAt = "10:00",
            closesAt = "23:00",
            stateCode = "32",
            fssaiLicenseNo = null,
            tradeLicenseNo = null,
            branchGstin = null,
            createdAtMillis = 100,
        )
        dao.insertLocalBranch(branch)
        dao.markBranchRejected(branch.localId, "Code already exists")
        assertEquals(1, dao.retryBranch(branch.localId))
        assertEquals(0, dao.retryBranch(branch.localId))
        assertEquals(branch, dao.pushableBranches().single())
        assertEquals(0, dao.discardRejectedBranch(branch.localId))
        dao.markBranchRejected(branch.localId, "Code already exists")
        assertEquals(1, dao.discardRejectedBranch(branch.localId))

        val terminal = LocalTerminalEntity(
            localId = "stable-terminal-create",
            branchId = "server-branch-id",
            name = "Counter 2",
            deviceId = "tablet-2",
            createdAtMillis = 200,
        )
        dao.insertLocalTerminal(terminal)
        dao.markTerminalRejected(terminal.localId, "Device id already exists")
        assertEquals(1, dao.retryTerminal(terminal.localId))
        assertEquals(0, dao.retryTerminal(terminal.localId))
        assertEquals(terminal.localId, dao.pushableTerminals().single().localId)
        assertEquals(0, dao.discardRejectedTerminal(terminal.localId))
        dao.markTerminalRejected(terminal.localId, "Device id already exists")
        assertEquals(1, dao.discardRejectedTerminal(terminal.localId))
    }

    private fun companyEdit(localId: String) = LocalCompanyEditEntity(
        localId = localId,
        name = "D Company",
        legalName = null,
        timezone = "Asia/Kolkata",
        gstin = null,
        pan = null,
        gstRegistrationType = "unregistered",
        isComposition = false,
        eInvoicingEnabled = false,
        upiVpa = "",
        createdAtMillis = 1,
    )
}
