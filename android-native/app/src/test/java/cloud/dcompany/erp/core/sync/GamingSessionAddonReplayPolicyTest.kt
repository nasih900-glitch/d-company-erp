package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.GamingSessionAddonActionState
import cloud.dcompany.erp.core.db.GamingSessionAddonActionType
import cloud.dcompany.erp.core.db.GamingSessionAddonCacheEntity
import cloud.dcompany.erp.core.db.LocalGamingSessionAddonActionEntity
import cloud.dcompany.erp.core.db.LocalModifierSelectionSnapshot
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.MenuModifierEntity
import cloud.dcompany.erp.core.db.MenuModifierGroupEntity
import cloud.dcompany.erp.core.db.MenuVariantEntity
import cloud.dcompany.erp.core.db.decodeModifierSelections
import cloud.dcompany.erp.core.db.encodeModifierSelections
import cloud.dcompany.erp.ui.screens.CartModifierSelection
import cloud.dcompany.erp.ui.screens.gaming.GameSession
import cloud.dcompany.erp.ui.screens.gaming.GamingSessionAddonUi
import cloud.dcompany.erp.ui.screens.gaming.GamingUiState
import cloud.dcompany.erp.ui.screens.gaming.SessionAddon
import cloud.dcompany.erp.ui.screens.gaming.SessionAddonActionUi
import cloud.dcompany.erp.ui.screens.gaming.gamingSessionAddonBillableTotalMinor
import cloud.dcompany.erp.ui.screens.gaming.mergeGamingSessionAddons
import cloud.dcompany.erp.ui.screens.gaming.gamingAddonSelectionError
import cloud.dcompany.erp.ui.screens.gaming.sessionAddonReceiptError
import cloud.dcompany.erp.ui.screens.gaming.sessionCancellationAddonBlockMessage
import cloud.dcompany.erp.ui.screens.gaming.toSessionAddonCreateBody
import cloud.dcompany.erp.ui.screens.gaming.toCacheEntity
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingSessionAddonReplayPolicyTest {

    @Test
    fun `ambiguous Add replay retains the exact captured item body`() {
        val original = action(
            modifierSelectionsJson = encodeModifierSelections(
                listOf(
                    LocalModifierSelectionSnapshot(
                        modifierId = "modifier-cheese",
                        modifierGroupId = "group-toppings",
                        name = "Cheese",
                        priceDeltaMinor = 2_500,
                        qty = 2,
                    ),
                    LocalModifierSelectionSnapshot(
                        modifierId = "modifier-spice",
                        modifierGroupId = "group-spice",
                        name = "Medium spicy",
                        priceDeltaMinor = 0,
                        qty = 1,
                    ),
                ),
            ),
            qty = 3,
            expectedUnitPriceMinor = 18_750,
            note = "No onion",
        )
        val afterLostResponse = original.copy(
            state = GamingSessionAddonActionState.AMBIGUOUS,
            lastError = "The response ended before its receipt arrived.",
        )

        val body = original.toSessionAddonCreateBody()
        assertEquals(body, afterLostResponse.toSessionAddonCreateBody())
        assertEquals(original.clientLineId, body.clientLineId)
        assertEquals("menu-burger", body.menuItemId)
        assertEquals("variant-large", body.variantId)
        assertEquals(3, body.qty)
        assertEquals(18_750L, body.expectedUnitPriceMinor)
        assertEquals("No onion", body.note)
        assertEquals(
            listOf("modifier-cheese" to 2, "modifier-spice" to 1),
            body.modifiers.map { it.modifierId to it.qty },
        )
    }

    @Test
    fun `Add receipt must match the saved session line actor and terminal`() {
        val saved = action()
        val valid = receipt()

        assertNull(sessionAddonReceiptError(saved, valid))
        assertTrue(
            sessionAddonReceiptError(saved, valid.copy(gamingSessionId = "another-session"))
                .orEmpty().contains("session", ignoreCase = true),
        )
        assertTrue(
            sessionAddonReceiptError(saved, valid.copy(clientLineId = "another-line"))
                .orEmpty().contains("identity", ignoreCase = true),
        )
        assertTrue(
            sessionAddonReceiptError(saved, valid.copy(createdTerminalId = "another-terminal"))
                .orEmpty().contains("employee or terminal", ignoreCase = true),
        )
        assertTrue(
            sessionAddonReceiptError(saved, valid.copy(variantId = "another-variant"))
                .orEmpty().contains("snapshot", ignoreCase = true),
        )
        assertTrue(
            sessionAddonReceiptError(saved, valid.copy(catalogUnitPriceMinor = 99))
                .orEmpty().contains("catalogue price", ignoreCase = true),
        )
        assertTrue(
            sessionAddonReceiptError(saved, valid.copy(voidedAt = "2026-08-29T12:30:00Z"))
                .orEmpty().contains("voided item", ignoreCase = true),
        )
    }

    @Test
    fun `Add receipt accepts authoritative display metadata changed after offline capture`() {
        val saved = action(menuItemName = "Old canned drink name")
        val renamedAndRecategorised = receipt().copy(
            menuItemName = "Cold Coffee Can",
            menuItemType = "drink",
        )

        assertNull(sessionAddonReceiptError(saved, renamedAndRecategorised))
    }

    @Test
    fun `Add receipt rejects invalid authoritative display metadata`() {
        val saved = action()

        listOf(
            receipt().copy(menuItemName = "   "),
            receipt().copy(menuItemName = "x".repeat(201)),
            receipt().copy(menuItemType = "service"),
        ).forEach { invalid ->
            assertTrue(
                sessionAddonReceiptError(saved, invalid)
                    .orEmpty().contains("display metadata", ignoreCase = true),
            )
        }
    }

    @Test
    fun `Void receipt must match the exact retained add-on actor and reason`() {
        val saved = action(
            actionType = GamingSessionAddonActionType.VOID,
            serverAddonId = "addon-1",
            voidReason = "Customer changed their mind",
        )
        val valid = receipt().copy(
            createdBy = "original-cashier",
            createdTerminalId = "original-terminal",
            voidedAt = "2026-08-29T12:30:00Z",
            voidedBy = saved.ownerUserId,
            voidReason = saved.voidReason,
        )

        assertNull(sessionAddonReceiptError(saved, valid))
        assertTrue(
            sessionAddonReceiptError(saved, valid.copy(id = "another-addon"))
                .orEmpty().contains("void receipt", ignoreCase = true),
        )
        assertTrue(
            sessionAddonReceiptError(saved, valid.copy(voidedBy = "another-user"))
                .orEmpty().contains("void receipt", ignoreCase = true),
        )
        assertTrue(
            sessionAddonReceiptError(saved, valid.copy(voidReason = "A different reason"))
                .orEmpty().contains("void receipt", ignoreCase = true),
        )
    }

    @Test
    fun `server cached modified item normalizes into a Void receipt that confirms`() {
        val serverReceipt = receipt().copy(modifiers = serverModifierReceipt())
        val cached = serverReceipt.toCacheEntity()
        val merged = mergeGamingSessionAddons(listOf(cached), emptyList()).single()
        val retainedModifiers = decodeModifierSelections(merged.modifierSelectionsJson)

        assertEquals(1, retainedModifiers.size)
        assertEquals("modifier-cheese", retainedModifiers.single().modifierId)
        assertEquals("group-toppings", retainedModifiers.single().modifierGroupId)
        assertEquals("Cheese", retainedModifiers.single().name)
        assertEquals(2_500L, retainedModifiers.single().priceDeltaMinor)
        assertEquals(1, retainedModifiers.single().qty)

        val voidAction = action(
            actionType = GamingSessionAddonActionType.VOID,
            serverAddonId = serverReceipt.id,
            modifierSelectionsJson = merged.modifierSelectionsJson,
            voidReason = "Customer changed their mind",
        )
        val confirmedVoidReceipt = serverReceipt.copy(
            createdBy = "original-cashier",
            createdTerminalId = "original-terminal",
            voidedAt = "2026-08-29T12:30:00Z",
            voidedBy = voidAction.ownerUserId,
            voidReason = voidAction.voidReason,
        )

        assertNull(sessionAddonReceiptError(voidAction, confirmedVoidReceipt))
    }

    @Test
    fun `malformed or mismatched modifier evidence fails closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            receipt().copy(modifiers = buildJsonArray {
                add(buildJsonObject {
                    put("modifier_id", JsonPrimitive("modifier-cheese"))
                    // Missing group, name and immutable price evidence.
                    put("qty", JsonPrimitive(1))
                })
            }).toCacheEntity()
        }

        val malformedLocal = action(modifierSelectionsJson = "{not-json")
        assertThrows(IllegalArgumentException::class.java) {
            malformedLocal.toSessionAddonCreateBody()
        }
        assertTrue(
            sessionAddonReceiptError(malformedLocal, receipt())
                .orEmpty().contains("invalid modifier evidence", ignoreCase = true),
        )

        val expected = action(
            modifierSelectionsJson = encodeModifierSelections(
                listOf(
                    LocalModifierSelectionSnapshot(
                        modifierId = "modifier-cheese",
                        modifierGroupId = "group-toppings",
                        name = "Cheese",
                        priceDeltaMinor = 2_500,
                        qty = 1,
                    ),
                ),
            ),
        )
        val mismatchedReceipt = receipt().copy(modifiers = buildJsonArray {
            add(buildJsonObject {
                put("modifier_id", JsonPrimitive("modifier-onion"))
                put("modifier_group_id", JsonPrimitive("group-toppings"))
                put("name", JsonPrimitive("Onion"))
                put("price_delta_minor", JsonPrimitive(2_500))
                put("qty", JsonPrimitive(1))
            })
        })
        assertTrue(
            sessionAddonReceiptError(expected, mismatchedReceipt)
                .orEmpty().contains("snapshot", ignoreCase = true),
        )
    }

    @Test
    fun `add-on refresh is limited to one visible unbilled session per station`() {
        val sessions = listOf(
            session(id = "active-first", stationId = "station-1", status = "active"),
            session(id = "active-duplicate", stationId = "station-1", status = "paused"),
            session(id = "paused", stationId = "station-2", status = "paused"),
            session(id = "ended-unbilled", stationId = "station-3", status = "ended"),
            session(
                id = "ended-billed",
                stationId = "station-4",
                status = "ended",
                orderId = "order-4",
            ),
            session(id = "cancelled", stationId = "station-5", status = "cancelled"),
            session(id = "blank-station", stationId = "", status = "active"),
        )

        assertEquals(
            listOf("active-first", "paused", "ended-unbilled"),
            gamingAddonSessionIdsForPull(sessions),
        )
    }

    @Test
    fun `exact reconciliation selects only distinct local sessions missing from board`() {
        assertEquals(
            listOf("cancelled-on-web", "sent-on-other-device"),
            missingGamingSessionResolutionIds(
                boardSessionIds = listOf("active", "ended-unbilled", " "),
                localServerIds = listOf(
                    "active",
                    "cancelled-on-web",
                    null,
                    "",
                    " cancelled-on-web ",
                    "sent-on-other-device",
                    "ended-unbilled",
                ),
            ),
        )
    }

    @Test
    fun `UI merge retains pending Void and rejected Add recovery evidence`() {
        val cached = cache(clientLineId = "cached-line")
        val pendingVoid = action(
            actionId = "22222222-2222-4222-8222-222222222222",
            actionType = GamingSessionAddonActionType.VOID,
            clientLineId = cached.clientLineId,
            serverAddonId = cached.id,
            voidReason = "Customer changed their mind",
        )
        val pendingOfflineAdd = action(
            actionId = "33333333-3333-4333-8333-333333333333",
            clientLineId = "offline-line",
            localSessionId = "local-session-2",
            serverSessionId = null,
            menuItemName = "Cold Coffee",
        )
        val rejectedOfflineAdd = action(
            actionId = "44444444-4444-4444-8444-444444444444",
            clientLineId = "rejected-line",
            localSessionId = "local-session-1",
            serverSessionId = "session-1",
            menuItemName = "Brownie",
            state = GamingSessionAddonActionState.REJECTED,
            lastError = "The catalogue price changed.",
        )

        val merged = mergeGamingSessionAddons(
            cache = listOf(cached),
            actions = listOf(pendingVoid, pendingOfflineAdd, rejectedOfflineAdd),
        ).associateBy { it.clientLineId }

        val voiding = merged.getValue(cached.clientLineId)
        assertFalse(voiding.voided)
        assertEquals(GamingSessionAddonActionType.VOID, voiding.localActionType)
        assertEquals(GamingSessionAddonActionState.PENDING, voiding.localState)
        assertEquals(pendingVoid.actionId, voiding.actionId)

        val pending = merged.getValue(pendingOfflineAdd.clientLineId)
        assertEquals(pendingOfflineAdd.localSessionId, pending.localSessionId)
        assertEquals(GamingSessionAddonActionType.ADD, pending.localActionType)
        assertEquals(GamingSessionAddonActionState.PENDING, pending.localState)
        assertEquals(18_750L * pendingOfflineAdd.qty, pending.lineTotalMinor)

        val rejected = merged.getValue(rejectedOfflineAdd.clientLineId)
        assertEquals(GamingSessionAddonActionState.REJECTED, rejected.localState)
        assertEquals(rejectedOfflineAdd.lastError, rejected.lastError)
        assertEquals(rejectedOfflineAdd.actionId, rejected.actionId)

        // A refused Add remains visible for review, but the displayed billable
        // subtotal includes only the active server row and still-pending Add.
        assertEquals(3, merged.size)
        assertEquals(75_000L, gamingSessionAddonBillableTotalMinor(merged.values.toList()))
        assertEquals(
            voiding.lineTotalMinor,
            gamingSessionAddonBillableTotalMinor(
                listOf(
                    voiding.copy(
                        localActionType = GamingSessionAddonActionType.VOID,
                        localState = GamingSessionAddonActionState.REJECTED,
                    ),
                ),
            ),
        )

        // The rejected action remains in the unresolved stream, so every
        // Send-to-POS path stays blocked until staff acknowledge/discard it.
        val uiState = GamingUiState(
            sessionAddonActions = listOf(
                SessionAddonActionUi(
                    actionId = rejectedOfflineAdd.actionId,
                    actionType = rejectedOfflineAdd.actionType,
                    serverSessionId = rejectedOfflineAdd.serverSessionId,
                    localSessionId = rejectedOfflineAdd.localSessionId,
                    clientLineId = rejectedOfflineAdd.clientLineId,
                    state = rejectedOfflineAdd.state,
                    lastError = rejectedOfflineAdd.lastError,
                ),
            ),
        )
        assertFalse(
            uiState.unresolvedAddonsFor(
                session(id = "session-1", stationId = "station-1", status = "ended"),
            ).isEmpty(),
        )
        assertTrue(
            uiState.copy(sessionAddonActions = emptyList()).unresolvedAddonsFor(
                session(id = "session-1", stationId = "station-1", status = "ended"),
            ).isEmpty(),
        )
    }

    @Test
    fun `whole-session cancellation explains the exact unresolved item prerequisite`() {
        val base = GamingSessionAddonUi(
            id = "addon-1",
            serverAddonId = "addon-1",
            serverSessionId = "session-1",
            clientLineId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            menuItemId = "menu-burger",
            menuItemName = "Burger",
            menuItemType = "food",
            qty = 1,
            unitPriceMinor = 18_750,
            lineTotalMinor = 18_750,
        )

        assertNull(sessionCancellationAddonBlockMessage(listOf(base.copy(voided = true))))
        assertTrue(
            sessionCancellationAddonBlockMessage(listOf(base))
                .orEmpty().contains("Void 1 active Gaming item", ignoreCase = true),
        )
        assertTrue(
            sessionCancellationAddonBlockMessage(
                listOf(
                    base.copy(
                        localActionType = GamingSessionAddonActionType.ADD,
                        localState = GamingSessionAddonActionState.PENDING,
                    ),
                ),
            ).orEmpty().contains("awaiting server confirmation", ignoreCase = true),
        )
        assertTrue(
            sessionCancellationAddonBlockMessage(
                listOf(
                    base.copy(
                        localActionType = GamingSessionAddonActionType.VOID,
                        localState = GamingSessionAddonActionState.REJECTED,
                    ),
                ),
            ).orEmpty().contains("Tap Review", ignoreCase = true),
        )
    }

    @Test
    fun `Gaming Add validates active modifier groups and their selection bounds`() {
        val item = MenuItemEntity(
            id = "menu-burger",
            categoryId = "food",
            sku = "BURGER",
            name = "Burger",
            type = "food",
            basePriceMinor = 15_000,
            taxRate = 0.0,
            hsnCode = null,
            priceIncludesTax = true,
            isAvailable = true,
            description = null,
        )
        val variant = MenuVariantEntity(
            id = "variant-large",
            menuItemId = item.id,
            name = "Large",
            priceDeltaMinor = 2_000,
            sortOrder = 0,
            isActive = true,
        )
        val group = MenuModifierGroupEntity(
            id = "group-toppings",
            menuItemId = item.id,
            name = "Toppings",
            minSelect = 1,
            maxSelect = 2,
            sortOrder = 0,
            isActive = true,
        )
        val cheese = MenuModifierEntity(
            id = "modifier-cheese",
            menuItemId = item.id,
            modifierGroupId = group.id,
            name = "Cheese",
            priceDeltaMinor = 2_500,
            maxQuantity = 2,
            sortOrder = 0,
            isActive = true,
        )

        assertNull(
            gamingAddonSelectionError(
                item,
                variant,
                listOf(CartModifierSelection(cheese, 1)),
                listOf(variant),
                listOf(group),
                listOf(cheese),
            ),
        )
        assertTrue(
            gamingAddonSelectionError(
                item,
                variant,
                emptyList(),
                listOf(variant),
                listOf(group),
                listOf(cheese),
            ).orEmpty().contains("requires", ignoreCase = true),
        )
        assertTrue(
            gamingAddonSelectionError(
                item,
                variant,
                listOf(CartModifierSelection(cheese.copy(modifierGroupId = "inactive-group"), 1)),
                listOf(variant),
                listOf(group),
                listOf(cheese),
            ).orEmpty().contains("changed", ignoreCase = true),
        )
        assertTrue(
            gamingAddonSelectionError(
                item,
                variant,
                listOf(CartModifierSelection(cheese, 3)),
                listOf(variant),
                listOf(group),
                listOf(cheese),
            ).orEmpty().contains("changed", ignoreCase = true),
        )
    }

    private fun action(
        actionId: String = "11111111-1111-4111-8111-111111111111",
        actionType: String = GamingSessionAddonActionType.ADD,
        localSessionId: String? = "local-session-1",
        serverSessionId: String? = "session-1",
        clientLineId: String = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        serverAddonId: String? = null,
        menuItemName: String = "Burger",
        modifierSelectionsJson: String = "[]",
        qty: Int = 2,
        expectedUnitPriceMinor: Long = 18_750,
        note: String? = null,
        voidReason: String? = null,
        state: String = GamingSessionAddonActionState.PENDING,
        lastError: String? = null,
    ) = LocalGamingSessionAddonActionEntity(
        actionId = actionId,
        actionType = actionType,
        ownerCompanyId = "company-1",
        ownerUserId = "user-1",
        branchId = "branch-1",
        terminalId = "terminal-1",
        localSessionId = localSessionId,
        serverSessionId = serverSessionId,
        shiftId = "shift-1",
        clientLineId = clientLineId,
        serverAddonId = serverAddonId,
        menuItemId = "menu-burger",
        menuItemName = menuItemName,
        menuItemType = "food",
        variantId = "variant-large",
        modifierSelectionsJson = modifierSelectionsJson,
        qty = qty,
        expectedUnitPriceMinor = expectedUnitPriceMinor,
        note = note,
        voidReason = voidReason,
        createdAtMillis = 1_787_995_200_000L,
        state = state,
        lastError = lastError,
    )

    private fun receipt() = SessionAddon(
        id = "addon-1",
        gamingSessionId = "session-1",
        clientLineId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        menuItemId = "menu-burger",
        menuItemName = "Burger",
        menuItemType = "food",
        variantId = "variant-large",
        modifiers = buildJsonArray {},
        qty = 2,
        catalogUnitPriceMinor = 18_750,
        unitPriceMinor = 18_750,
        lineTotalMinor = 37_500,
        discountMinor = 0,
        taxRate = 0.0,
        taxableValueMinor = 37_500,
        cgstMinor = 0,
        sgstMinor = 0,
        igstMinor = 0,
        cessMinor = 0,
        createdBy = "user-1",
        createdTerminalId = "terminal-1",
        createdAt = "2026-08-29T12:00:00Z",
    )

    private fun serverModifierReceipt() = buildJsonArray {
        add(buildJsonObject {
            put("modifier_id", JsonPrimitive("modifier-cheese"))
            put("modifier_group_id", JsonPrimitive("group-toppings"))
            put("group_name", JsonPrimitive("Toppings"))
            put("name", JsonPrimitive("Cheese"))
            put("qty", JsonPrimitive(1))
            put("price_delta_minor", JsonPrimitive(2_500))
            put("per_item_delta_minor", JsonPrimitive(2_500))
            put("line_delta_minor", JsonPrimitive(5_000))
        })
    }

    private fun session(
        id: String,
        stationId: String,
        status: String,
        orderId: String? = null,
    ) = GameSession(
        id = id,
        stationId = stationId,
        shiftId = "shift-1",
        status = status,
        startAt = "2026-08-29T12:00:00Z",
        orderId = orderId,
    )

    private fun cache(clientLineId: String) = GamingSessionAddonCacheEntity(
        id = "addon-1",
        gamingSessionId = "session-1",
        clientLineId = clientLineId,
        menuItemId = "menu-burger",
        menuItemName = "Burger",
        menuItemType = "food",
        variantId = "variant-large",
        qty = 2,
        catalogUnitPriceMinor = 18_750,
        unitPriceMinor = 18_750,
        lineTotalMinor = 37_500,
        discountMinor = 0,
        taxRate = 0.0,
        taxableValueMinor = 37_500,
        cgstMinor = 0,
        sgstMinor = 0,
        igstMinor = 0,
        cessMinor = 0,
        createdBy = "user-1",
        createdTerminalId = "terminal-1",
        createdAtMillis = 1_787_995_200_000L,
    )
}
