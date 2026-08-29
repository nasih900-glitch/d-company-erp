package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GamingSessionAddonDaoTest {

    private lateinit var db: ErpDatabase
    private lateinit var dao: GamingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
        dao = db.gamingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun syncQueueIsOwnerScopedAndRejectsDuplicateLinesAndChangedShift() {
        val first = addonAction(
            actionId = "11111111-1111-4111-8111-111111111111",
            clientLineId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            ownerUserId = "employee-a",
        )
        val secondEmployee = addonAction(
            actionId = "22222222-2222-4222-8222-222222222222",
            clientLineId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            ownerUserId = "employee-b",
        )

        runBlocking {
            dao.insertLocalSession(localSession())
            assertTrue(dao.captureSessionAddonAction(first))
            assertFalse(
                dao.captureSessionAddonAction(
                    first.copy(actionId = "33333333-3333-4333-8333-333333333333"),
                ),
            )
            assertTrue(dao.captureSessionAddonAction(secondEmployee))

            assertEquals(
                listOf(first.actionId),
                dao.sessionAddonActionsForSync(
                    companyId = "company-1",
                    userId = "employee-a",
                    branchId = "branch-1",
                    terminalId = "terminal-1",
                ).map { it.actionId },
            )
            assertEquals(
                listOf(secondEmployee.actionId),
                dao.sessionAddonActionsForSync(
                    companyId = "company-1",
                    userId = "employee-b",
                    branchId = "branch-1",
                    terminalId = "terminal-1",
                ).map { it.actionId },
            )
            assertTrue(
                dao.sessionAddonActionsForSync(
                    companyId = "company-1",
                    userId = "employee-a",
                    branchId = "branch-1",
                    terminalId = "another-terminal",
                ).isEmpty(),
            )
        }

        val mismatch = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                dao.captureSessionAddonAction(
                    addonAction(
                        actionId = "44444444-4444-4444-8444-444444444444",
                        clientLineId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                        ownerUserId = "employee-a",
                    ).copy(shiftId = "shift-that-is-not-the-session-shift"),
                )
            }
        }
        assertTrue(mismatch.message.orEmpty().contains("captured shift", ignoreCase = true))
    }

    @Test
    fun offlineAddThenVoidKeepsOneWayDependenciesUntilBothReceiptsConfirm() = runBlocking {
        val localSession = localSession(
            serverId = null,
            state = GamingSessionState.START_PENDING,
            status = "starting",
        )
        dao.insertLocalSession(localSession)
        val add = addonAction(
            actionId = "55555555-5555-4555-8555-555555555555",
            clientLineId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            ownerUserId = "employee-a",
            serverSessionId = null,
        )
        val void = add.copy(
            actionId = "66666666-6666-4666-8666-666666666666",
            actionType = GamingSessionAddonActionType.VOID,
            voidReason = "Customer changed their mind",
            createdAtMillis = add.createdAtMillis + 1,
        )

        assertTrue(dao.captureSessionAddonAction(add))
        assertTrue(dao.captureSessionAddonAction(void))
        assertEquals(
            listOf(add.actionId, void.actionId),
            dao.sessionAddonActionsForSync(
                companyId = add.ownerCompanyId,
                userId = add.ownerUserId,
                branchId = add.branchId,
                terminalId = add.terminalId,
            ).map { it.actionId },
        )
        val pendingGroup = db.outboxSafetyDao().unresolvedGroups()
            .single { it.resource == "gaming_session_addons" }
        assertEquals(GamingSessionAddonActionState.PENDING, pendingGroup.state)
        assertEquals(2, pendingGroup.count)

        assertEquals(2, dao.resolveSessionAddonServerId(localSession.localId, "server-session-1"))
        val resolvedAdd = dao.sessionAddonAction(add.actionId)!!
        assertEquals("server-session-1", resolvedAdd.serverSessionId)
        assertEquals(add.menuItemId, resolvedAdd.menuItemId)
        assertEquals(add.modifierSelectionsJson, resolvedAdd.modifierSelectionsJson)
        assertEquals(add.expectedUnitPriceMinor, resolvedAdd.expectedUnitPriceMinor)

        assertEquals(
            1,
            dao.markSessionAddonActionConfirmed(
                actionId = add.actionId,
                actionType = GamingSessionAddonActionType.ADD,
                serverAddonId = "server-addon-1",
                resolvedAtMillis = 2_000,
            ),
        )
        assertEquals(
            "server-addon-1",
            dao.sessionAddonAddActionForLine(
                localSessionId = localSession.localId,
                serverSessionId = "server-session-1",
                clientLineId = add.clientLineId,
            )?.serverAddonId,
        )

        assertEquals(1, dao.resolveSessionAddonVoidTarget(void.actionId, "server-addon-1"))
        assertEquals(
            1,
            dao.markSessionAddonActionConfirmed(
                actionId = void.actionId,
                actionType = GamingSessionAddonActionType.VOID,
                serverAddonId = "server-addon-1",
                resolvedAtMillis = 2_100,
            ),
        )

        assertEquals(
            GamingSessionAddonActionState.CONFIRMED,
            dao.sessionAddonAction(add.actionId)?.state,
        )
        val confirmedVoid = dao.sessionAddonAction(void.actionId)!!
        assertEquals(GamingSessionAddonActionState.CONFIRMED, confirmedVoid.state)
        assertEquals("server-addon-1", confirmedVoid.serverAddonId)
        assertEquals(void.voidReason, confirmedVoid.voidReason)
        assertEquals(
            0,
            dao.unresolvedSessionAddonActionCount(
                localSessionId = localSession.localId,
                serverSessionId = "server-session-1",
            ),
        )
        assertTrue(
            db.outboxSafetyDao().unresolvedGroups()
                .none { it.resource == "gaming_session_addons" },
        )
    }

    private fun localSession(
        serverId: String? = "server-session-1",
        state: String = GamingSessionState.START_SYNCED,
        status: String = "active",
    ) = LocalGamingSessionEntity(
        localId = "local-session-1",
        serverId = serverId,
        stationId = "station-1",
        shiftId = "shift-1",
        startedAtMillis = 1_000,
        state = state,
        status = status,
    )

    private fun addonAction(
        actionId: String,
        clientLineId: String,
        ownerUserId: String,
        serverSessionId: String? = "server-session-1",
    ) = LocalGamingSessionAddonActionEntity(
        actionId = actionId,
        actionType = GamingSessionAddonActionType.ADD,
        ownerCompanyId = "company-1",
        ownerUserId = ownerUserId,
        branchId = "branch-1",
        terminalId = "terminal-1",
        localSessionId = "local-session-1",
        serverSessionId = serverSessionId,
        shiftId = "shift-1",
        clientLineId = clientLineId,
        menuItemId = "menu-burger",
        menuItemName = "Burger",
        menuItemType = "food",
        variantId = "variant-large",
        modifierSelectionsJson = """[{"modifierId":"modifier-cheese","name":"Cheese","priceDeltaMinor":2500,"qty":1}]""",
        qty = 2,
        expectedUnitPriceMinor = 18_750,
        note = "No onion",
        createdAtMillis = 1_500,
    )
}
