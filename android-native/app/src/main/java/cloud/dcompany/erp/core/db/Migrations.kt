package cloud.dcompany.erp.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * This database holds captured sales and shift state that may exist nowhere
 * else until synced, and `ErpDatabase` deliberately has no destructive
 * fallback. Durable outbox tables are migrated losslessly. A replaceable
 * server cache may be rebuilt only when its preserved columns and deliberate
 * invalidation semantics are covered by an instrumentation migration test.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_shifts` (
                `localId` TEXT NOT NULL,
                `serverShiftId` TEXT,
                `openingFloatMinor` INTEGER NOT NULL,
                `openedAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `countedMinor` INTEGER,
                `closedAtMillis` INTEGER,
                `varianceMinor` INTEGER,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_shifts_state` ON `local_shifts` (`state`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_shifts_openedAtMillis` ON `local_shifts` (`openedAtMillis`)",
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `gaming_stations` (
                `id` TEXT NOT NULL,
                `code` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `ratePerHourMinor` INTEGER NOT NULL,
                `isActive` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `gaming_session_cache` (
                `id` TEXT NOT NULL,
                `stationId` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `startAtMillis` INTEGER NOT NULL,
                `endAtMillis` INTEGER,
                `timerMinutes` INTEGER,
                `timerEndsAtMillis` INTEGER,
                `billableMinutes` INTEGER,
                `amountMinor` INTEGER,
                `customerName` TEXT,
                `customerPhone` TEXT,
                `orderId` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_gaming_sessions` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT,
                `stationId` TEXT NOT NULL,
                `shiftId` TEXT,
                `customerPhone` TEXT,
                `timerMinutes` INTEGER,
                `startedAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `endAtMillis` INTEGER,
                `timerEndsAtMillis` INTEGER,
                `billableMinutes` INTEGER,
                `amountMinor` INTEGER,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_gaming_sessions_state` ON `local_gaming_sessions` (`state`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `kitchen_order_cache` (
                `id` TEXT NOT NULL,
                `invoiceNo` TEXT,
                `type` TEXT NOT NULL,
                `tableCode` TEXT,
                `customerName` TEXT,
                `openedAt` TEXT,
                `kitchenState` TEXT NOT NULL,
                `minutesWaiting` INTEGER NOT NULL,
                `lines` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_kitchen_advances` (
                `localId` TEXT NOT NULL,
                `orderId` TEXT NOT NULL,
                `targetState` TEXT NOT NULL,
                `requestedAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_kitchen_advances_orderId` ON `local_kitchen_advances` (`orderId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cafe_floors` (
                `id` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cafe_tables` (
                `id` TEXT NOT NULL,
                `floorId` TEXT NOT NULL,
                `code` TEXT NOT NULL,
                `seats` INTEGER NOT NULL,
                `shape` TEXT NOT NULL,
                `x` REAL NOT NULL,
                `y` REAL NOT NULL,
                `status` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_table_orders` (
                `localId` TEXT NOT NULL,
                `orderId` TEXT,
                `tableId` TEXT NOT NULL,
                `tableCode` TEXT NOT NULL,
                `shiftId` TEXT NOT NULL,
                `lines` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_table_orders_state` ON `local_table_orders` (`state`)",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `refund_order_cache` (
                `id` TEXT NOT NULL,
                `invoiceNo` TEXT,
                `status` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `totalMinor` INTEGER NOT NULL,
                `paidMinor` INTEGER NOT NULL,
                `refundableMinor` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_refunds` (
                `localId` TEXT NOT NULL,
                `orderId` TEXT NOT NULL,
                `invoiceNo` TEXT,
                `reasonCode` TEXT NOT NULL,
                `amountMinor` INTEGER NOT NULL,
                `note` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `settlementMethod` TEXT,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_refunds_state` ON `local_refunds` (`state`)",
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `report_snapshots` (
                `key` TEXT NOT NULL,
                `jsonBody` TEXT NOT NULL,
                `fetchedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`key`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `customer_cache` (
                `id` TEXT NOT NULL,
                `name` TEXT,
                `phone` TEXT NOT NULL,
                `email` TEXT,
                `birthday` TEXT,
                `visitCount` INTEGER NOT NULL,
                `totalSpentMinor` INTEGER NOT NULL,
                `loyaltyPoints` INTEGER NOT NULL,
                `lifetimeGamingPointsEarned` INTEGER NOT NULL,
                `gamingRank` TEXT NOT NULL,
                `gamingRankFloor` INTEGER NOT NULL,
                `nextGamingRank` TEXT,
                `nextGamingRankFloor` INTEGER,
                `pointsToNextGamingRank` INTEGER,
                `lastVisitAt` TEXT,
                `notes` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_customers` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT,
                `phone` TEXT,
                `name` TEXT,
                `email` TEXT,
                `birthday` TEXT,
                `notes` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                `version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_customers_state` ON `local_customers` (`state`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_customers_serverId` ON `local_customers` (`serverId`)",
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Widen the existing read-cache table — it's wholesale-replaced on
        // every sync, so the placeholder defaults below only need to be
        // schema-valid; real values land on the very next pullMenu().
        db.execSQL("ALTER TABLE `menu_items` ADD COLUMN `type` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `menu_items` ADD COLUMN `hsnCode` TEXT")
        db.execSQL("ALTER TABLE `menu_items` ADD COLUMN `priceIncludesTax` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `menu_items` ADD COLUMN `description` TEXT")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_menu_categories` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT,
                `name` TEXT,
                `sortOrder` INTEGER,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                `version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_menu_categories_state` ON `local_menu_categories` (`state`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_menu_categories_serverId` ON `local_menu_categories` (`serverId`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_menu_items` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT NOT NULL,
                `categoryId` TEXT,
                `name` TEXT,
                `description` TEXT,
                `isAvailable` INTEGER,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                `version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_menu_items_state` ON `local_menu_items` (`state`)",
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `staff_cache` (
                `id` TEXT NOT NULL,
                `email` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `phone` TEXT,
                `status` TEXT NOT NULL,
                `rolesCsv` TEXT NOT NULL,
                `lastLoginAt` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_staff` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT NOT NULL,
                `name` TEXT,
                `phone` TEXT,
                `status` TEXT,
                `roleCode` TEXT,
                `pendingDelete` INTEGER NOT NULL DEFAULT 0,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                `version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_staff_state` ON `local_staff` (`state`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_staff_serverId` ON `local_staff` (`serverId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `on_shift_cache` (
                `attendanceId` TEXT NOT NULL,
                `userId` TEXT NOT NULL,
                `userName` TEXT,
                `userEmail` TEXT,
                `branchId` TEXT NOT NULL,
                `branchName` TEXT,
                `clockInAt` TEXT NOT NULL,
                PRIMARY KEY(`attendanceId`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ingredient_cache` (
                `id` TEXT NOT NULL,
                `sku` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `baseUnit` TEXT NOT NULL,
                `currentQty` REAL NOT NULL,
                `reorderThreshold` REAL NOT NULL,
                `reorderQty` REAL NOT NULL,
                `avgCostMinor` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_ingredients` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT,
                `sku` TEXT,
                `name` TEXT,
                `baseUnit` TEXT,
                `reorderThreshold` REAL,
                `reorderQty` REAL,
                `pendingDelete` INTEGER NOT NULL DEFAULT 0,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                `version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_ingredients_state` ON `local_ingredients` (`state`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_ingredients_serverId` ON `local_ingredients` (`serverId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `supplier_cache` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `contact` TEXT,
                `gstin` TEXT,
                `paymentTerms` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_suppliers` (
                `localId` TEXT NOT NULL,
                `serverId` TEXT,
                `name` TEXT,
                `contact` TEXT,
                `gstin` TEXT,
                `paymentTerms` TEXT,
                `pendingDelete` INTEGER NOT NULL DEFAULT 0,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                `version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_suppliers_state` ON `local_suppliers` (`state`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_suppliers_serverId` ON `local_suppliers` (`serverId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `batch_cache` (
                `id` TEXT NOT NULL,
                `ingredientId` TEXT NOT NULL,
                `receivedAt` TEXT NOT NULL,
                `expiresAt` TEXT,
                `qtyOnHand` REAL NOT NULL,
                `costPerUnitMinor` INTEGER NOT NULL,
                `lotCode` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_batch_cache_ingredientId` ON `batch_cache` (`ingredientId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_grns` (
                `localId` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `supplierId` TEXT NOT NULL,
                `supplierInvoiceNo` TEXT,
                `notes` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_grn_lines` (
                `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `grnLocalId` TEXT NOT NULL,
                `ingredientId` TEXT NOT NULL,
                `qty` REAL NOT NULL,
                `unitCostMinor` INTEGER NOT NULL,
                `expiresAt` TEXT,
                `lotCode` TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_grn_lines_grnLocalId` ON `local_grn_lines` (`grnLocalId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_adjustments` (
                `localId` TEXT NOT NULL,
                `ingredientId` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `qtyDelta` REAL NOT NULL,
                `type` TEXT NOT NULL,
                `note` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `expense_cache` (
                `id` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `categoryId` TEXT NOT NULL,
                `supplierId` TEXT,
                `amountMinor` INTEGER NOT NULL,
                `paidVia` TEXT NOT NULL,
                `paidAt` TEXT NOT NULL,
                `vendorName` TEXT,
                `invoiceNo` TEXT,
                `note` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_expenses` (
                `localId` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `categoryId` TEXT NOT NULL,
                `supplierId` TEXT,
                `amountMinor` INTEGER NOT NULL,
                `paidVia` TEXT NOT NULL,
                `paidAt` TEXT NOT NULL,
                `vendorName` TEXT,
                `invoiceNo` TEXT,
                `note` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `asset_cache` (
                `id` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `purchaseMinor` INTEGER NOT NULL,
                `purchaseDate` TEXT NOT NULL,
                `usefulLifeMonths` INTEGER NOT NULL,
                `salvageMinor` INTEGER NOT NULL,
                `depreciationMethod` TEXT NOT NULL,
                `notes` TEXT,
                `accumulatedDepreciationMinor` INTEGER NOT NULL,
                `bookValueMinor` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_assets` (
                `localId` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `purchaseMinor` INTEGER NOT NULL,
                `purchaseDate` TEXT NOT NULL,
                `usefulLifeMonths` INTEGER NOT NULL,
                `salvageMinor` INTEGER NOT NULL,
                `notes` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `capital_entry_cache` (
                `id` TEXT NOT NULL,
                `partnerId` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `amountMinor` INTEGER NOT NULL,
                `effectiveAt` TEXT NOT NULL,
                `settlementAccount` TEXT NOT NULL,
                `sourceRef` TEXT,
                `note` TEXT,
                `createdByName` TEXT,
                `createdAt` TEXT NOT NULL,
                `voidedAt` TEXT,
                `voidReason` TEXT,
                `isVoided` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_capital_entry_cache_partnerId` ON `capital_entry_cache` (`partnerId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_capital_entries` (
                `localId` TEXT NOT NULL,
                `partnerId` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `amountMinor` INTEGER NOT NULL,
                `effectiveAt` TEXT NOT NULL,
                `settlementAccount` TEXT NOT NULL,
                `sourceRef` TEXT NOT NULL,
                `note` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `event_cache` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT,
                `eventType` TEXT NOT NULL,
                `screen` TEXT NOT NULL,
                `startsAt` TEXT NOT NULL,
                `endsAt` TEXT,
                `capacity` INTEGER NOT NULL,
                `sold` INTEGER NOT NULL,
                `remaining` INTEGER NOT NULL,
                `baseTicketPriceMinor` INTEGER NOT NULL,
                `sacCode` TEXT NOT NULL,
                `taxRate` REAL NOT NULL,
                `status` TEXT NOT NULL,
                `posterUrl` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `event_ticket_cache` (
                `id` TEXT NOT NULL,
                `eventId` TEXT NOT NULL,
                `ticketNo` TEXT NOT NULL,
                `eventName` TEXT NOT NULL,
                `customerName` TEXT,
                `customerPhone` TEXT,
                `seat` TEXT,
                `pricePaidMinor` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `checkedInAt` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_event_ticket_cache_eventId` ON `event_ticket_cache` (`eventId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_ticket_sales` (
                `localId` TEXT NOT NULL,
                `eventId` TEXT NOT NULL,
                `customerName` TEXT NOT NULL,
                `customerPhone` TEXT,
                `seat` TEXT,
                `qty` INTEGER NOT NULL,
                `note` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_check_ins` (
                `localId` TEXT NOT NULL,
                `eventId` TEXT NOT NULL,
                `ticketId` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `membership_tier_cache` (
                `id` TEXT NOT NULL,
                `code` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `monthlyPriceMinor` INTEGER NOT NULL,
                `annualPriceMinor` INTEGER,
                `foodDiscountPct` REAL NOT NULL,
                `gamingDiscountPct` REAL NOT NULL,
                `hookahDiscountPct` REAL NOT NULL,
                `pointMultiplier` REAL NOT NULL,
                `freeGamingMinutesPerWeek` INTEGER NOT NULL,
                `freeHookahPerMonth` INTEGER NOT NULL,
                `priorityBooking` INTEGER NOT NULL,
                `description` TEXT,
                `sortOrder` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `customer_membership_cache` (
                `id` TEXT NOT NULL,
                `customerId` TEXT NOT NULL,
                `tierId` TEXT NOT NULL,
                `tierCode` TEXT NOT NULL,
                `tierName` TEXT NOT NULL,
                `billingCycle` TEXT NOT NULL,
                `startsAt` TEXT NOT NULL,
                `expiresAt` TEXT NOT NULL,
                `cancelledAt` TEXT,
                `autoRenew` INTEGER NOT NULL,
                `amountPaidMinor` INTEGER NOT NULL,
                `isActive` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_customer_membership_cache_customerId` ON `customer_membership_cache` (`customerId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_subscriptions` (
                `localId` TEXT NOT NULL,
                `customerId` TEXT NOT NULL,
                `tierId` TEXT NOT NULL,
                `billingCycle` TEXT NOT NULL,
                `paidVia` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_membership_cancellations` (
                `localId` TEXT NOT NULL,
                `customerId` TEXT NOT NULL,
                `subscriptionId` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `company_cache` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `legalName` TEXT,
                `currency` TEXT NOT NULL,
                `timezone` TEXT NOT NULL,
                `country` TEXT,
                `gstin` TEXT,
                `pan` TEXT,
                `gstRegistrationType` TEXT NOT NULL,
                `isComposition` INTEGER NOT NULL,
                `eInvoicingEnabled` INTEGER NOT NULL,
                `fiscalYearStartMonth` INTEGER NOT NULL,
                `upiVpa` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_company_edits` (
                `localId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `legalName` TEXT,
                `timezone` TEXT NOT NULL,
                `gstin` TEXT,
                `pan` TEXT,
                `gstRegistrationType` TEXT NOT NULL,
                `isComposition` INTEGER NOT NULL,
                `eInvoicingEnabled` INTEGER NOT NULL,
                `upiVpa` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `branch_cache` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `code` TEXT,
                `address` TEXT,
                `timezone` TEXT,
                `opensAt` TEXT,
                `closesAt` TEXT,
                `stateCode` TEXT,
                `fssaiLicenseNo` TEXT,
                `tradeLicenseNo` TEXT,
                `branchGstin` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_branches` (
                `localId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `code` TEXT,
                `address` TEXT,
                `timezone` TEXT,
                `opensAt` TEXT,
                `closesAt` TEXT,
                `stateCode` TEXT,
                `fssaiLicenseNo` TEXT,
                `tradeLicenseNo` TEXT,
                `branchGstin` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `terminal_cache` (
                `id` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `deviceId` TEXT,
                `lastSeenAt` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_terminal_cache_branchId` ON `terminal_cache` (`branchId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_terminals` (
                `localId` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `deviceId` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `local_gaming_sessions` ADD COLUMN `orderId` TEXT")
        db.execSQL(
            "UPDATE `local_gaming_sessions` SET `state` = 'ended_unbilled' WHERE `state` = 'stopped'",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `held_order_cache` (
                `id` TEXT NOT NULL,
                `invoiceNo` TEXT,
                `type` TEXT NOT NULL,
                `sourceLabel` TEXT,
                `totalMinor` INTEGER NOT NULL,
                `paidMinor` INTEGER NOT NULL,
                `itemsCount` INTEGER NOT NULL,
                `customerName` TEXT,
                `createdAt` TEXT NOT NULL,
                `heldAt` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_held_order_payments` (
                `localId` TEXT NOT NULL,
                `targetOrderId` TEXT NOT NULL,
                `method` TEXT NOT NULL,
                `amountMinor` INTEGER NOT NULL,
                `tenderedMinor` INTEGER,
                `expectedTotalMinor` INTEGER NOT NULL,
                `expectedDueMinor` INTEGER NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_held_order_payments_syncState` " +
                "ON `local_held_order_payments` (`syncState`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_held_order_payments_targetOrderId` " +
                "ON `local_held_order_payments` (`targetOrderId`)",
        )
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `held_order_cache` ADD COLUMN `checkoutVersion` " +
                "INTEGER NOT NULL DEFAULT 1",
        )
        // Existing pending rows are real staff-confirmed payments created by
        // v14. Null claim fields deliberately preserve them for safe claim
        // acquisition and same-idempotency-key recovery after upgrade.
        db.execSQL("ALTER TABLE `local_held_order_payments` ADD COLUMN `claimToken` TEXT")
        db.execSQL(
            "ALTER TABLE `local_held_order_payments` ADD COLUMN `claimExpiresAtMillis` INTEGER",
        )
        db.execSQL(
            "ALTER TABLE `local_held_order_payments` ADD COLUMN `claimOrderVersion` INTEGER",
        )
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Nullable additions preserve every existing open/close outbox leg.
        // Identity is filled only after the global outbox-owner gate verifies
        // the signed-in profile; migration time has no trustworthy actor.
        db.execSQL("ALTER TABLE `local_shifts` ADD COLUMN `terminalId` TEXT")
        db.execSQL("ALTER TABLE `local_shifts` ADD COLUMN `branchId` TEXT")
        db.execSQL("ALTER TABLE `local_shifts` ADD COLUMN `openedByUserId` TEXT")
        db.execSQL("ALTER TABLE `local_shifts` ADD COLUMN `openedByName` TEXT")
        db.execSQL("ALTER TABLE `local_shifts` ADD COLUMN `openedByEmail` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_shifts_terminalId` " +
                "ON `local_shifts` (`terminalId`)",
        )

        // v15 used one ambiguous `rejected` state. Split it without losing
        // which leg failed: a server id proves the open leg had succeeded.
        db.execSQL(
            "UPDATE `local_shifts` SET `state` = CASE " +
                "WHEN `serverShiftId` IS NULL THEN 'open_rejected' " +
                "ELSE 'close_rejected' END WHERE `state` = 'rejected'",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `server_open_shift_cache` (
                `terminalId` TEXT NOT NULL,
                `serverShiftId` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `openingFloatMinor` INTEGER NOT NULL,
                `expectedMinor` INTEGER,
                `openedAtMillis` INTEGER NOT NULL,
                `openedByUserId` TEXT,
                `openedByName` TEXT,
                `openedByEmail` TEXT,
                `verifiedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`terminalId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_server_open_shift_cache_serverShiftId` " +
                "ON `server_open_shift_cache` (`serverShiftId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_server_open_shift_cache_branchId` " +
                "ON `server_open_shift_cache` (`branchId`)",
        )
    }
}

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Data-only repair. Database 13 and earlier stored every failed gaming
        // leg as `rejected`; the newer DAO intentionally understands only the
        // leg-specific states because each one has a different safe recovery
        // action. Classify from durable evidence without replaying an action.
        db.execSQL(RECOVER_LEGACY_GAMING_REJECTIONS_SQL)
    }
}

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Do not guess which drawer owns an older queued membership charge.
        // Null quarantines it; Sync surfaces an explicit recovery message.
        // Every new v18 capture stores its resolved local/server shift id.
        db.execSQL("ALTER TABLE `local_subscriptions` ADD COLUMN `shiftId` TEXT")
    }
}

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // These columns are part of the new v19 money contract. Keep them out
        // of 17->18: v18 was already exported with only shiftId, and changing a
        // historical migration would make Room reject a legitimate v17 app.
        // Null deliberately quarantines legacy rows rather than guessing the
        // amount an owner saw or collected.
        db.execSQL("ALTER TABLE `local_subscriptions` ADD COLUMN `expectedAmountMinor` INTEGER")
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `revokedAt` TEXT")
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `paymentId` TEXT")
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `paymentMethod` TEXT")
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `paymentShiftId` TEXT")
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `refundedAt` TEXT")
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `refundMethod` TEXT")
        db.execSQL("ALTER TABLE `server_open_shift_cache` ADD COLUMN `posCollectionsMinor` INTEGER")
        db.execSQL("ALTER TABLE `server_open_shift_cache` ADD COLUMN `membershipCollectionsMinor` INTEGER")
        db.execSQL("ALTER TABLE `server_open_shift_cache` ADD COLUMN `grossCollectionsMinor` INTEGER")
        db.execSQL("ALTER TABLE `server_open_shift_cache` ADD COLUMN `settledMembershipRefundsMinor` INTEGER")
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `paymentReceiptNo` TEXT")
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `paymentPaidAt` TEXT")
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `refundId` TEXT")
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `refundStatus` TEXT")
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `refundAcceptedAt` TEXT")
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `refundReceiptNo` TEXT")
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `refundExternalReference` TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `customer_membership_history_cache` (
                `id` TEXT NOT NULL,
                `customerId` TEXT NOT NULL,
                `tierId` TEXT NOT NULL,
                `tierCode` TEXT NOT NULL,
                `tierName` TEXT NOT NULL,
                `billingCycle` TEXT NOT NULL,
                `startsAt` TEXT NOT NULL,
                `expiresAt` TEXT NOT NULL,
                `cancelledAt` TEXT,
                `revokedAt` TEXT,
                `autoRenew` INTEGER NOT NULL,
                `amountPaidMinor` INTEGER NOT NULL,
                `paymentId` TEXT,
                `paymentMethod` TEXT,
                `paymentShiftId` TEXT,
                `paymentReceiptNo` TEXT,
                `paymentPaidAt` TEXT,
                `refundId` TEXT,
                `refundStatus` TEXT,
                `refundAcceptedAt` TEXT,
                `refundedAt` TEXT,
                `refundMethod` TEXT,
                `refundReceiptNo` TEXT,
                `refundExternalReference` TEXT,
                `isActive` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_customer_membership_history_cache_customerId` " +
                "ON `customer_membership_history_cache` (`customerId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_membership_refunds` (
                `localId` TEXT NOT NULL,
                `customerId` TEXT NOT NULL,
                `subscriptionId` TEXT NOT NULL,
                `shiftId` TEXT NOT NULL,
                `expectedAmountMinor` INTEGER NOT NULL,
                `method` TEXT NOT NULL,
                `reason` TEXT NOT NULL,
                `externalReference` TEXT,
                `settledAtMillis` INTEGER,
                `serverRefundId` TEXT,
                `receiptNo` TEXT,
                `withdrawalReason` TEXT,
                `withdrawalAtMillis` INTEGER,
                `createdAtMillis` INTEGER NOT NULL,
                `syncState` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_membership_refunds_subscriptionId` " +
                "ON `local_membership_refunds` (`subscriptionId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_membership_refunds_shiftId` " +
                "ON `local_membership_refunds` (`shiftId`)",
        )
    }
}

val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // v19's POS refund row had no exact drawer/actor/server-request
        // identity and its accepted_cash_due flag was only a local fiction:
        // the retired backend endpoint had already posted the financial
        // refund. Preserve every row, but never reinterpret an ambiguous one
        // as a new cash-due task after upgrade.
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `clientActionId` TEXT")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `shiftId` TEXT")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `serverShiftId` TEXT")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `branchId` TEXT")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `terminalId` TEXT")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `capturedByUserId` TEXT")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `expectedPaidMinor` INTEGER")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `expectedRefundableMinor` INTEGER")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `mode` TEXT")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `externalReference` TEXT")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `providerSettledAtMillis` INTEGER")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `serverRequestId` TEXT")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `serverRefundId` TEXT")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `acceptedAtMillis` INTEGER")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `cashHandoffStartedAtMillis` INTEGER")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `settledAtMillis` INTEGER")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `withdrawalAtMillis` INTEGER")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `withdrawalReason` TEXT")
        db.execSQL("ALTER TABLE `local_refunds` ADD COLUMN `receiptNo` TEXT")
        db.execSQL("ALTER TABLE `server_open_shift_cache` ADD COLUMN `settledPosRefundsMinor` INTEGER")
        db.execSQL("ALTER TABLE `server_open_shift_cache` ADD COLUMN `totalRefundsMinor` INTEGER")
        db.execSQL("ALTER TABLE `server_open_shift_cache` ADD COLUMN `netCollectionsMinor` INTEGER")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_refunds_shiftId` " +
                "ON `local_refunds` (`shiftId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_refunds_clientActionId` " +
                "ON `local_refunds` (`clientActionId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_refunds_serverRequestId` " +
                "ON `local_refunds` (`serverRequestId`)",
        )
        db.execSQL(
            """
            UPDATE `local_refunds`
               SET `state` = 'legacy_reconciliation_required',
                   `lastError` = CASE
                       WHEN `lastError` IS NULL OR trim(`lastError`) = '' THEN
                           'Created by an older refund workflow. Do not pay again; an owner must reconcile server and drawer records.'
                       ELSE `lastError` || ' Older refund workflow: do not pay again; owner reconciliation required.'
                   END
             WHERE `state` IN (
                 'request_pending', 'request_rejected', 'accepted_cash_due',
                 'pending', 'rejected', 'legacy_cash_given_pending',
                 'legacy_cash_given_rejected'
             )
            """.trimIndent(),
        )
    }
}

val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Payment evidence is read-only server truth. Defaults preserve every
        // v20 receipt while making the absence of a warning explicit.
        db.execSQL("ALTER TABLE `customer_membership_cache` ADD COLUMN `paymentEvidenceOccurredAt` TEXT")
        db.execSQL(
            "ALTER TABLE `customer_membership_cache` ADD COLUMN " +
                "`paymentEvidenceTimeUntrusted` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `customer_membership_cache` ADD COLUMN " +
                "`paymentProviderEvidenceReconciled` INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL("ALTER TABLE `customer_membership_history_cache` ADD COLUMN `paymentEvidenceOccurredAt` TEXT")
        db.execSQL(
            "ALTER TABLE `customer_membership_history_cache` ADD COLUMN " +
                "`paymentEvidenceTimeUntrusted` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `customer_membership_history_cache` ADD COLUMN " +
                "`paymentProviderEvidenceReconciled` INTEGER NOT NULL DEFAULT 1",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `membership_payment_task_cache` (
                `id` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `terminalId` TEXT NOT NULL,
                `customerId` TEXT NOT NULL,
                `tierId` TEXT NOT NULL,
                `shiftId` TEXT NOT NULL,
                `billingCycle` TEXT NOT NULL,
                `paidVia` TEXT NOT NULL,
                `amountMinor` INTEGER NOT NULL,
                `customerName` TEXT,
                `customerPhone` TEXT NOT NULL,
                `tierCode` TEXT NOT NULL,
                `tierName` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `acceptedAt` TEXT NOT NULL,
                `preparedBy` TEXT NOT NULL,
                `preparedByName` TEXT,
                `collectionStartedAt` TEXT,
                `valueCompletedAt` TEXT,
                `valueCompletedBy` TEXT,
                `valueCompletedByName` TEXT,
                `actionStartedBy` TEXT,
                `actionStartedByName` TEXT,
                `actionKind` TEXT,
                `settledAt` TEXT,
                `settledBy` TEXT,
                `settledByName` TEXT,
                `membershipId` TEXT,
                `paymentId` TEXT,
                `receiptNo` TEXT,
                `externalReference` TEXT,
                `evidenceOccurredAt` TEXT,
                `evidenceTimeUntrusted` INTEGER NOT NULL,
                `providerEvidenceReconciled` INTEGER NOT NULL,
                `customerSpendReconciled` INTEGER NOT NULL,
                `resolution` TEXT,
                `resolvedAt` TEXT,
                `resolvedBy` TEXT,
                `resolvedByName` TEXT,
                `actionStateVerified` INTEGER NOT NULL,
                `providerVerificationStatus` TEXT,
                `providerVerificationReference` TEXT,
                `providerCheckedAt` TEXT,
                `cashReturnConfirmed` INTEGER NOT NULL,
                `actionTakeoverConfirmed` INTEGER NOT NULL,
                `actionTakeoverReason` TEXT,
                `clientActionId` TEXT NOT NULL,
                `fetchedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_membership_payment_task_cache_clientActionId` " +
                "ON `membership_payment_task_cache` (`clientActionId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_membership_payment_task_cache_shiftId` " +
                "ON `membership_payment_task_cache` (`shiftId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_membership_payment_task_cache_status` " +
                "ON `membership_payment_task_cache` (`status`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_membership_payment_task_cache_terminalId_acceptedAt` " +
                "ON `membership_payment_task_cache` (`terminalId`, `acceptedAt`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_membership_payment_actions` (
                `actionId` TEXT NOT NULL,
                `rootClientActionId` TEXT NOT NULL,
                `serverRequestId` TEXT,
                `sourceLegacyLocalId` TEXT,
                `kind` TEXT NOT NULL,
                `customerId` TEXT NOT NULL,
                `tierId` TEXT NOT NULL,
                `shiftId` TEXT,
                `branchId` TEXT,
                `terminalId` TEXT,
                `actorUserId` TEXT,
                `billingCycle` TEXT NOT NULL,
                `paidVia` TEXT NOT NULL,
                `expectedAmountMinor` INTEGER,
                `occurredAtMillis` INTEGER,
                `externalReference` TEXT,
                `resolution` TEXT,
                `reason` TEXT,
                `actionStateVerified` INTEGER NOT NULL,
                `providerVerificationStatus` TEXT,
                `providerVerificationReference` TEXT,
                `providerEvidenceOccurredAtMillis` INTEGER,
                `cashReturnConfirmed` INTEGER NOT NULL,
                `actionTakeoverConfirmed` INTEGER NOT NULL,
                `actionTakeoverReason` TEXT,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`actionId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_membership_payment_actions_rootClientActionId_kind` " +
                "ON `local_membership_payment_actions` (`rootClientActionId`, `kind`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_membership_payment_actions_sourceLegacyLocalId` " +
                "ON `local_membership_payment_actions` (`sourceLegacyLocalId`)",
        )
        for (column in listOf("serverRequestId", "shiftId", "state")) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_local_membership_payment_actions_$column` " +
                    "ON `local_membership_payment_actions` (`$column`)",
            )
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `shift_history_cache` (
                `id` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `terminalId` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `openedAtMillis` INTEGER NOT NULL,
                `closedAtMillis` INTEGER,
                `openingFloatMinor` INTEGER NOT NULL,
                `expectedMinor` INTEGER,
                `countedMinor` INTEGER,
                `varianceMinor` INTEGER,
                `posSalesMinor` INTEGER NOT NULL,
                `membershipSalesMinor` INTEGER NOT NULL,
                `grossCollectionsMinor` INTEGER NOT NULL,
                `settledPosRefundsMinor` INTEGER NOT NULL,
                `settledMembershipRefundsMinor` INTEGER NOT NULL,
                `totalRefundsMinor` INTEGER NOT NULL,
                `netCollectionsMinor` INTEGER NOT NULL,
                `openedByUserId` TEXT,
                `openedByName` TEXT,
                `openedByEmail` TEXT,
                `fetchedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_shift_history_cache_terminalId_openedAtMillis` " +
                "ON `shift_history_cache` (`terminalId`, `openedAtMillis`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_shift_history_cache_status` " +
                "ON `shift_history_cache` (`status`)",
        )

        // Preserve every pre-reservation payment attempt. Known shift/amount
        // rows get one exact legacy replay action; missing provenance is
        // quarantined and never rebound to the current drawer or tier price.
        db.execSQL(
            """
            INSERT OR IGNORE INTO `local_membership_payment_actions` (
                `actionId`, `rootClientActionId`, `serverRequestId`,
                `sourceLegacyLocalId`, `kind`, `customerId`, `tierId`, `shiftId`,
                `branchId`, `terminalId`, `actorUserId`, `billingCycle`, `paidVia`,
                `expectedAmountMinor`, `occurredAtMillis`, `externalReference`,
                `resolution`, `reason`, `actionStateVerified`,
                `providerVerificationStatus`, `providerVerificationReference`,
                `providerEvidenceOccurredAtMillis`, `cashReturnConfirmed`,
                `actionTakeoverConfirmed`, `actionTakeoverReason`, `createdAtMillis`,
                `state`, `lastError`
            )
            SELECT
                'membership-payment-legacy-probe:' || `localId`,
                'membership-subscribe:' || `localId`,
                NULL, `localId`, 'legacy_probe', `customerId`, `tierId`, `shiftId`,
                NULL, NULL, NULL, `billingCycle`, `paidVia`, `expectedAmountMinor`,
                `createdAtMillis`, NULL, NULL, NULL, 0, NULL, NULL, NULL, 0, 0,
                NULL, `createdAtMillis`,
                CASE
                    WHEN `shiftId` IS NULL OR `expectedAmountMinor` IS NULL
                        THEN 'legacy_provenance_missing'
                    ELSE 'pending'
                END,
                CASE
                    WHEN `shiftId` IS NULL OR `expectedAmountMinor` IS NULL THEN
                        COALESCE(`lastError` || ' ', '') ||
                        'Legacy membership payment lacks its original shift or captured amount. Do not collect or replay it.'
                    ELSE `lastError`
                END
            FROM `local_subscriptions`
            WHERE `syncState` <> 'synced'
            """.trimIndent(),
        )
        db.execSQL(
            "UPDATE `local_subscriptions` SET `syncState` = 'migrated_v21' " +
                "WHERE `syncState` <> 'synced'",
        )
    }
}

val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf("customer_membership_cache", "customer_membership_history_cache")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `refundEvidenceOccurredAt` TEXT")
            db.execSQL(
                "ALTER TABLE `$table` ADD COLUMN `refundEvidenceTimeUntrusted` " +
                    "INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE `$table` ADD COLUMN `refundProviderEvidenceReconciled` " +
                    "INTEGER NOT NULL DEFAULT 1",
            )
            db.execSQL(
                "ALTER TABLE `$table` ADD COLUMN `refundCustomerSpendReconciled` " +
                    "INTEGER NOT NULL DEFAULT 1",
            )
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `membership_refund_task_cache` (
                `id` TEXT NOT NULL, `branchId` TEXT NOT NULL, `terminalId` TEXT NOT NULL,
                `membershipId` TEXT NOT NULL, `paymentId` TEXT NOT NULL, `shiftId` TEXT NOT NULL,
                `method` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `acceptedAt` TEXT NOT NULL,
                `status` TEXT NOT NULL, `handoffStartedAt` TEXT, `payoutCompletedAt` TEXT,
                `payoutCompletedBy` TEXT, `payoutCompletedByName` TEXT, `acceptedBy` TEXT,
                `acceptedByName` TEXT, `actionStartedBy` TEXT, `actionStartedByName` TEXT,
                `actionKind` TEXT, `settledAt` TEXT, `settledBy` TEXT, `settledByName` TEXT,
                `reason` TEXT NOT NULL, `externalReference` TEXT, `receiptNo` TEXT,
                `entitlementRestored` INTEGER NOT NULL, `customerId` TEXT, `customerName` TEXT,
                `customerPhone` TEXT, `tierName` TEXT, `originalPaymentReceiptNo` TEXT,
                `resolution` TEXT, `resolutionReason` TEXT, `resolvedAt` TEXT, `resolvedBy` TEXT,
                `resolvedByName` TEXT, `evidenceOccurredAt` TEXT,
                `evidenceTimeUntrusted` INTEGER NOT NULL,
                `providerEvidenceReconciled` INTEGER NOT NULL,
                `customerSpendReconciled` INTEGER NOT NULL, `actionStateVerified` INTEGER NOT NULL,
                `providerVerificationStatus` TEXT, `providerVerificationReference` TEXT,
                `providerCheckedAt` TEXT, `cashReturnConfirmed` INTEGER NOT NULL,
                `actionTakeoverConfirmed` INTEGER NOT NULL, `actionTakeoverReason` TEXT,
                `fetchedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        for (column in listOf("membershipId", "paymentId", "shiftId", "status")) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_membership_refund_task_cache_$column` " +
                    "ON `membership_refund_task_cache` (`$column`)",
            )
        }
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_membership_refund_task_cache_terminalId_acceptedAt` " +
                "ON `membership_refund_task_cache` (`terminalId`, `acceptedAt`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_membership_refund_actions` (
                `actionId` TEXT NOT NULL, `rootClientActionId` TEXT NOT NULL,
                `serverRefundId` TEXT, `sourceLegacyLocalId` TEXT, `kind` TEXT NOT NULL,
                `customerId` TEXT NOT NULL, `membershipId` TEXT NOT NULL, `paymentId` TEXT,
                `shiftId` TEXT NOT NULL, `branchId` TEXT, `terminalId` TEXT, `actorUserId` TEXT,
                `paidVia` TEXT NOT NULL, `expectedAmountMinor` INTEGER NOT NULL,
                `reason` TEXT NOT NULL, `occurredAtMillis` INTEGER, `externalReference` TEXT,
                `resolution` TEXT, `reconciliationShiftId` TEXT,
                `providerVerificationStatus` TEXT, `providerVerificationReference` TEXT,
                `providerEvidenceOccurredAtMillis` INTEGER, `cashHandoverConfirmed` INTEGER NOT NULL,
                `actionStateVerified` INTEGER NOT NULL, `cashReturnConfirmed` INTEGER NOT NULL,
                `actionTakeoverConfirmed` INTEGER NOT NULL, `actionTakeoverReason` TEXT,
                `createdAtMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, `lastError` TEXT,
                PRIMARY KEY(`actionId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_membership_refund_actions_rootClientActionId_kind` " +
                "ON `local_membership_refund_actions` (`rootClientActionId`, `kind`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_membership_refund_actions_sourceLegacyLocalId` " +
                "ON `local_membership_refund_actions` (`sourceLegacyLocalId`)",
        )
        for (column in listOf("serverRefundId", "membershipId", "paymentId", "shiftId", "state")) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_local_membership_refund_actions_$column` " +
                    "ON `local_membership_refund_actions` (`$column`)",
            )
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `membership_refund_attempt_cache` (
                `id` TEXT NOT NULL, `branchId` TEXT NOT NULL, `terminalId` TEXT NOT NULL,
                `originalClientActionId` TEXT NOT NULL, `customerId` TEXT NOT NULL,
                `membershipId` TEXT NOT NULL, `paymentId` TEXT NOT NULL,
                `sourceShiftId` TEXT NOT NULL, `expectedAmountMinor` INTEGER NOT NULL,
                `paidVia` TEXT NOT NULL, `capturedAt` TEXT NOT NULL,
                `capturedTimeUntrusted` INTEGER NOT NULL, `registeredAt` TEXT NOT NULL,
                `registeredBy` TEXT NOT NULL, `registeredByName` TEXT, `status` TEXT NOT NULL,
                `resolutionId` TEXT, `fetchedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_membership_refund_attempt_cache_originalClientActionId` " +
                "ON `membership_refund_attempt_cache` (`originalClientActionId`)",
        )
        for (column in listOf("sourceShiftId", "status", "terminalId")) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_membership_refund_attempt_cache_$column` " +
                    "ON `membership_refund_attempt_cache` (`$column`)",
            )
        }

        // v20 allowed a provider payout before server acceptance and allowed
        // a cash handover without the v22 begin/completion journal. Never
        // replay those rows as a fresh refund. Adopt an existing server task
        // when its id is known; otherwise register a zero-value recovery task.
        db.execSQL(
            """
            INSERT OR IGNORE INTO `local_membership_refund_actions` (
                `actionId`, `rootClientActionId`, `serverRefundId`, `sourceLegacyLocalId`,
                `kind`, `customerId`, `membershipId`, `paymentId`, `shiftId`, `branchId`,
                `terminalId`, `actorUserId`, `paidVia`, `expectedAmountMinor`, `reason`,
                `occurredAtMillis`, `externalReference`, `resolution`, `reconciliationShiftId`,
                `providerVerificationStatus`, `providerVerificationReference`,
                `providerEvidenceOccurredAtMillis`, `cashHandoverConfirmed`,
                `actionStateVerified`, `cashReturnConfirmed`, `actionTakeoverConfirmed`,
                `actionTakeoverReason`, `createdAtMillis`, `state`, `lastError`
            )
            SELECT
                'membership-refund-legacy:' || r.`localId`,
                'membership-refund:' || r.`localId`, r.`serverRefundId`, r.`localId`,
                CASE WHEN r.`serverRefundId` IS NULL THEN 'legacy_register'
                     ELSE 'legacy_reconcile_server' END,
                r.`customerId`, r.`subscriptionId`,
                COALESCE(
                    (SELECT h.`paymentId` FROM `customer_membership_history_cache` h
                      WHERE h.`id` = r.`subscriptionId` LIMIT 1),
                    (SELECT c.`paymentId` FROM `customer_membership_cache` c
                      WHERE c.`id` = r.`subscriptionId` LIMIT 1)
                ),
                r.`shiftId`, NULL, NULL, NULL, r.`method`, r.`expectedAmountMinor`,
                r.`reason`, COALESCE(r.`settledAtMillis`, r.`createdAtMillis`),
                r.`externalReference`, NULL, NULL, NULL, NULL, NULL,
                CASE WHEN r.`method` = 'cash' AND r.`settledAtMillis` IS NOT NULL THEN 1 ELSE 0 END,
                0, 0, 0, NULL, r.`createdAtMillis`,
                CASE
                    WHEN trim(r.`shiftId`) = '' THEN 'legacy_provenance_missing'
                    WHEN r.`serverRefundId` IS NULL AND COALESCE(
                        (SELECT h.`paymentId` FROM `customer_membership_history_cache` h
                          WHERE h.`id` = r.`subscriptionId` LIMIT 1),
                        (SELECT c.`paymentId` FROM `customer_membership_cache` c
                          WHERE c.`id` = r.`subscriptionId` LIMIT 1)
                    ) IS NULL THEN 'legacy_provenance_missing'
                    ELSE 'pending'
                END,
                CASE
                    WHEN trim(r.`shiftId`) = '' THEN
                        COALESCE(r.`lastError` || ' ', '') ||
                        'Legacy membership refund has no source shift. Do not repeat a payout.'
                    WHEN r.`serverRefundId` IS NULL AND COALESCE(
                        (SELECT h.`paymentId` FROM `customer_membership_history_cache` h
                          WHERE h.`id` = r.`subscriptionId` LIMIT 1),
                        (SELECT c.`paymentId` FROM `customer_membership_cache` c
                          WHERE c.`id` = r.`subscriptionId` LIMIT 1)
                    ) IS NULL THEN
                        COALESCE(r.`lastError` || ' ', '') ||
                        'Legacy membership refund has no verified payment id. Verify independent evidence; do not repeat a payout.'
                    ELSE r.`lastError`
                END
            FROM `local_membership_refunds` r
            WHERE r.`syncState` NOT IN ('synced', 'withdrawn')
            """.trimIndent(),
        )
        db.execSQL(
            "UPDATE `local_membership_refunds` SET `syncState` = 'migrated_v22' " +
                "WHERE `syncState` NOT IN ('synced', 'withdrawn')",
        )
    }
}

val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Older held-payment rows cannot be guessed into a drawer. They stay
        // nullable and fail the close gate until their original settlement is
        // reconciled; every v23 capture writes both values before collection.
        db.execSQL("ALTER TABLE `local_held_order_payments` ADD COLUMN `shiftId` TEXT")
        db.execSQL("ALTER TABLE `local_held_order_payments` ADD COLUMN `terminalId` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_held_order_payments_shiftId` " +
                "ON `local_held_order_payments` (`shiftId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_held_order_payments_terminalId` " +
                "ON `local_held_order_payments` (`terminalId`)",
        )
        installShiftClosingWriteGuards(db)
    }
}

val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `kitchen_order_cache` ADD COLUMN `pendingCancellations` " +
                "TEXT NOT NULL DEFAULT '[]'",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cafe_bill_cache` (
                `orderId` TEXT NOT NULL,
                `tableId` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `sourceLabel` TEXT,
                `subtotalMinor` INTEGER NOT NULL,
                `taxMinor` INTEGER NOT NULL,
                `totalMinor` INTEGER NOT NULL,
                `openedAt` TEXT NOT NULL,
                `heldAt` TEXT,
                `checkoutVersion` INTEGER NOT NULL,
                `lines` TEXT NOT NULL,
                `voidedLines` TEXT NOT NULL,
                PRIMARY KEY(`orderId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_cafe_bill_cache_tableId` " +
                "ON `cafe_bill_cache` (`tableId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_cafe_bill_cache_status` " +
                "ON `cafe_bill_cache` (`status`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_cafe_bills` (
                `localBillId` TEXT NOT NULL,
                `serverOrderId` TEXT,
                `tableId` TEXT NOT NULL,
                `tableCode` TEXT NOT NULL,
                `shiftId` TEXT NOT NULL,
                `confirmedCheckoutVersion` INTEGER,
                `localStatus` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`localBillId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_cafe_bills_tableId` " +
                "ON `local_cafe_bills` (`tableId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_cafe_bills_serverOrderId` " +
                "ON `local_cafe_bills` (`serverOrderId`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_cafe_actions` (
                `actionId` TEXT NOT NULL,
                `localBillId` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `kind` TEXT NOT NULL,
                `payload` TEXT NOT NULL,
                `capturedCheckoutVersion` INTEGER,
                `dedupeKey` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`actionId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_cafe_actions_localBillId_sequence` " +
                "ON `local_cafe_actions` (`localBillId`, `sequence`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_cafe_actions_state` " +
                "ON `local_cafe_actions` (`state`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_cafe_actions_dedupeKey` " +
                "ON `local_cafe_actions` (`dedupeKey`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `local_kitchen_cancellation_acks` (
                `localId` TEXT NOT NULL,
                `orderId` TEXT NOT NULL,
                `lineId` TEXT NOT NULL,
                `requestedAtMillis` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `lastError` TEXT,
                PRIMARY KEY(`localId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_kitchen_cancellation_acks_orderId_lineId` " +
                "ON `local_kitchen_cancellation_acks` (`orderId`, `lineId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_kitchen_cancellation_acks_state` " +
                "ON `local_kitchen_cancellation_acks` (`state`)",
        )

        // Losslessly move the old one-shot create+hold outbox into the first
        // v24 action chain. Its original localId remains the action and API
        // idempotency identity, and rejected rows stay visible for recovery.
        db.execSQL(
            """
            INSERT OR ABORT INTO `local_cafe_bills` (
                `localBillId`, `serverOrderId`, `tableId`, `tableCode`, `shiftId`,
                `confirmedCheckoutVersion`, `localStatus`, `createdAtMillis`
            )
            SELECT `localId`, `orderId`, `tableId`, `tableCode`, `shiftId`,
                   NULL, 'open', `createdAtMillis`
              FROM `local_table_orders`
             WHERE `state` IN ('pending', 'rejected')
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR ABORT INTO `local_cafe_actions` (
                `actionId`, `localBillId`, `sequence`, `kind`, `payload`,
                `capturedCheckoutVersion`, `dedupeKey`, `createdAtMillis`, `state`, `lastError`
            )
            SELECT `localId`, `localId`, 1, 'legacy_create_and_send',
                   '{"legacy_lines":' || `lines` || '}', NULL,
                   'legacy:' || `localId`, `createdAtMillis`, `state`, `lastError`
              FROM `local_table_orders`
             WHERE `state` IN ('pending', 'rejected')
            """.trimIndent(),
        )
        db.execSQL(
            "DELETE FROM `local_table_orders` WHERE `state` IN ('pending', 'rejected')",
        )
        installShiftClosingWriteGuards(db)
    }
}

val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // v24 persisted backend-optional accounting fields as NOT NULL. A
        // legacy ShiftRead response was therefore indistinguishable from a
        // real server-asserted zero once cached. Rebuild only this replaceable
        // server-history cache so absence can stay null. Preserve identity,
        // lifecycle, drawer and POS-receipt values; mark every migrated
        // optional breakdown unavailable until the next authoritative pull.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `_shift_history_cache_v25` (
                `id` TEXT NOT NULL,
                `branchId` TEXT NOT NULL,
                `terminalId` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `openedAtMillis` INTEGER NOT NULL,
                `closedAtMillis` INTEGER,
                `openingFloatMinor` INTEGER NOT NULL,
                `expectedMinor` INTEGER,
                `countedMinor` INTEGER,
                `varianceMinor` INTEGER,
                `posSalesMinor` INTEGER NOT NULL,
                `membershipSalesMinor` INTEGER,
                `grossCollectionsMinor` INTEGER,
                `settledPosRefundsMinor` INTEGER,
                `settledMembershipRefundsMinor` INTEGER,
                `totalRefundsMinor` INTEGER,
                `netCollectionsMinor` INTEGER,
                `openedByUserId` TEXT,
                `openedByName` TEXT,
                `openedByEmail` TEXT,
                `fetchedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `_shift_history_cache_v25` (
                `id`, `branchId`, `terminalId`, `status`, `openedAtMillis`, `closedAtMillis`,
                `openingFloatMinor`, `expectedMinor`, `countedMinor`, `varianceMinor`,
                `posSalesMinor`, `membershipSalesMinor`, `grossCollectionsMinor`,
                `settledPosRefundsMinor`, `settledMembershipRefundsMinor`,
                `totalRefundsMinor`, `netCollectionsMinor`, `openedByUserId`,
                `openedByName`, `openedByEmail`, `fetchedAtMillis`
            )
            SELECT
                `id`, `branchId`, `terminalId`, `status`, `openedAtMillis`, `closedAtMillis`,
                `openingFloatMinor`, `expectedMinor`, `countedMinor`, `varianceMinor`,
                `posSalesMinor`, NULL, NULL, NULL, NULL, NULL, NULL,
                `openedByUserId`, `openedByName`, `openedByEmail`, `fetchedAtMillis`
            FROM `shift_history_cache`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `shift_history_cache`")
        db.execSQL("ALTER TABLE `_shift_history_cache_v25` RENAME TO `shift_history_cache`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_shift_history_cache_terminalId_openedAtMillis` " +
                "ON `shift_history_cache` (`terminalId`, `openedAtMillis`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_shift_history_cache_status` " +
                "ON `shift_history_cache` (`status`)",
        )
    }
}

val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // An old CLOSED row is history, not a new UI event. Only an unresolved
        // close lifecycle survives upgrade as a pending result receipt.
        db.execSQL(
            "ALTER TABLE `local_shifts` ADD COLUMN `closeResultPending` " +
                "INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "UPDATE `local_shifts` SET `closeResultPending` = 1 " +
                "WHERE `state` IN ('close_pending', 'close_rejected')",
        )
    }
}

val ALL_MIGRATIONS = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
    MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
    MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
    MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
    MIGRATION_24_25, MIGRATION_25_26,
)
