package cloud.dcompany.erp.core.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

/**
 * Real migrations against real schema history, not a guess. `ErpDatabase`
 * forbids destructive fallback because it holds captured sales and shift
 * state that exist nowhere else until they sync — a migration that's wrong
 * about column names or a `NOT NULL` default isn't a lint warning here, it's
 * a hard crash for every tablet already in the field the moment it updates.
 *
 * Schema snapshots live in the canonical `app/schemas/` directory, generated
 * by Room itself (`ksp { arg("room.schemaLocation", ...) }` in build.gradle.kts).
 * The androidTest source set reads that directory directly, so a generated
 * version cannot be omitted from migration tests by forgetting a manual copy.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ErpDatabase::class.java,
        emptyList(),
    )

    @Test
    fun migrate1To2_preservesExistingDataAndAddsLocalShifts() {
        // Seed a v1 database with real rows — the migration is additive-only
        // (CREATE TABLE, no ALTER against menu_items/local_orders/etc), but
        // the whole point of testing it is not trusting that by inspection.
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO menu_categories (id, name, sortOrder) VALUES ('cat-1', 'Drinks', 0)",
            )
            execSQL(
                "INSERT INTO menu_items (id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1)",
            )
            execSQL(
                "INSERT INTO local_orders (localId, shiftId, type, estimateMinor, paymentMethod, " +
                    "tenderedMinor, tipMinor, createdAtMillis, syncState) " +
                    "VALUES ('order-1', 'shift-1', 'dine_in', 12000, 'cash', 12000, 0, 1000, 'pending')",
            )
            close()
        }

        // MIGRATION_1_2 only adds local_shifts — validated against the real,
        // Room-generated v2 schema (schemas/.../2.json), not a hand check.
        val migrated = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        migrated.query("SELECT id, name FROM menu_items WHERE id = 'item-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "menu_items row lost across the migration" }
            assert(cursor.getString(1) == "Cold Coffee")
        }
        migrated.query("SELECT localId, syncState FROM local_orders WHERE localId = 'order-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "local_orders row lost across the migration — this is real captured revenue" }
            assert(cursor.getString(1) == "pending")
        }
        migrated.query("SELECT COUNT(*) FROM local_shifts").use { cursor ->
            cursor.moveToFirst()
            assert(cursor.getInt(0) == 0) { "local_shifts should exist and start empty" }
        }
        migrated.close()
    }

    @Test
    fun migrate2To3_preservesExistingDataAndAddsPhase2Tables() {
        // v2 already has local_shifts (from MIGRATION_1_2) — seed one real
        // open shift plus the same v1 rows, so this test also proves the
        // Phase 2 migration doesn't disturb Phase 1's table.
        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO menu_items (id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1)",
            )
            execSQL(
                "INSERT INTO local_shifts (localId, openingFloatMinor, openedAtMillis, state) " +
                    "VALUES ('shift-1', 100000, 1000, 'open_synced')",
            )
            close()
        }

        // MIGRATION_2_3 adds the 8 Phase 2 tables (gaming/kitchen/tables) in
        // one pass — validated against the real, Room-generated v3 schema.
        val migrated = helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3)

        migrated.query("SELECT id, name FROM menu_items WHERE id = 'item-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "menu_items row lost across the migration" }
            assert(cursor.getString(1) == "Cold Coffee")
        }
        migrated.query("SELECT localId, state FROM local_shifts WHERE localId = 'shift-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "local_shifts row lost across the migration" }
            assert(cursor.getString(1) == "open_synced")
        }
        for (table in listOf(
            "gaming_stations", "gaming_session_cache", "local_gaming_sessions",
            "kitchen_order_cache", "local_kitchen_advances",
            "cafe_floors", "cafe_tables", "local_table_orders",
        )) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assert(cursor.getInt(0) == 0) { "$table should exist and start empty" }
            }
        }
        migrated.close()
    }

    @Test
    fun migrate3To4_preservesExistingDataAndAddsRefundTables() {
        // v3 already has a Phase 2 table (gaming_stations) — seed one row
        // there plus a v1 row, so this test also proves the Phase 3
        // migration doesn't disturb earlier phases' tables.
        helper.createDatabase(dbName, 3).apply {
            execSQL(
                "INSERT INTO menu_items (id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1)",
            )
            execSQL(
                "INSERT INTO gaming_stations (id, code, name, type, ratePerHourMinor, isActive) " +
                    "VALUES ('station-1', 'PS5-01', 'PS5 Station 1', 'ps5', 15000, 1)",
            )
            close()
        }

        // MIGRATION_3_4 adds the 2 Phase 3 tables (Refunds) — validated
        // against the real, Room-generated v4 schema.
        val migrated = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4)

        migrated.query("SELECT id, name FROM menu_items WHERE id = 'item-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "menu_items row lost across the migration" }
            assert(cursor.getString(1) == "Cold Coffee")
        }
        migrated.query("SELECT id, name FROM gaming_stations WHERE id = 'station-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "gaming_stations row lost across the migration" }
            assert(cursor.getString(1) == "PS5 Station 1")
        }
        for (table in listOf("refund_order_cache", "local_refunds")) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assert(cursor.getInt(0) == 0) { "$table should exist and start empty" }
            }
        }
        migrated.close()
    }

    @Test
    fun migrate4To5_preservesExistingDataAndAddsReportSnapshots() {
        // v4 already has a Phase 3 table (local_refunds) — seed one row there
        // plus a v1 row, so this test also proves the Phase 5 migration
        // doesn't disturb earlier phases' tables.
        helper.createDatabase(dbName, 4).apply {
            execSQL(
                "INSERT INTO menu_items (id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1)",
            )
            execSQL(
                "INSERT INTO local_refunds (localId, orderId, reasonCode, amountMinor, createdAtMillis, state) " +
                    "VALUES ('refund-1', 'order-1', 'other', 4300, 1000, 'synced')",
            )
            close()
        }

        // MIGRATION_4_5 adds the one Phase 5 table (report_snapshots) —
        // validated against the real, Room-generated v5 schema.
        val migrated = helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5)

        migrated.query("SELECT id, name FROM menu_items WHERE id = 'item-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "menu_items row lost across the migration" }
            assert(cursor.getString(1) == "Cold Coffee")
        }
        migrated.query("SELECT localId, state FROM local_refunds WHERE localId = 'refund-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "local_refunds row lost across the migration" }
            assert(cursor.getString(1) == "synced")
        }
        migrated.query("SELECT COUNT(*) FROM report_snapshots").use { cursor ->
            cursor.moveToFirst()
            assert(cursor.getInt(0) == 0) { "report_snapshots should exist and start empty" }
        }
        migrated.close()
    }

    @Test
    fun migrate5To6_preservesExistingDataAndAddsCustomerTables() {
        // v5 already has a Phase 5 table (report_snapshots) — seed a row
        // there plus a v1 row, so this test also proves the Phase 6
        // migration doesn't disturb earlier phases' tables.
        helper.createDatabase(dbName, 5).apply {
            execSQL(
                "INSERT INTO menu_items (id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1)",
            )
            execSQL(
                "INSERT INTO report_snapshots (`key`, jsonBody, fetchedAtMillis) " +
                    "VALUES ('daily:2026-08-19', '{}', 1000)",
            )
            close()
        }

        // MIGRATION_5_6 adds the two Phase 6 tables (customer_cache,
        // local_customers) — validated against the real, Room-generated v6
        // schema.
        val migrated = helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6)

        migrated.query("SELECT id, name FROM menu_items WHERE id = 'item-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "menu_items row lost across the migration" }
            assert(cursor.getString(1) == "Cold Coffee")
        }
        migrated.query("SELECT `key`, jsonBody FROM report_snapshots WHERE `key` = 'daily:2026-08-19'").use { cursor ->
            assert(cursor.moveToFirst()) { "report_snapshots row lost across the migration" }
            assert(cursor.getString(1) == "{}")
        }
        for (table in listOf("customer_cache", "local_customers")) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assert(cursor.getInt(0) == 0) { "$table should exist and start empty" }
            }
        }
        migrated.close()
    }

    @Test
    fun migrate6To7_preservesExistingDataAndWidensMenuItemsAndAddsMenuOutboxTables() {
        // v6 already has a Phase 6 table (local_customers) — seed a row
        // there plus a v1 menu_items row (using v6's narrower column set,
        // since `type`/`hsnCode`/`priceIncludesTax`/`description` don't
        // exist until this migration adds them), so this test also proves
        // the Phase 7 migration doesn't disturb earlier phases' tables.
        helper.createDatabase(dbName, 6).apply {
            execSQL(
                "INSERT INTO menu_items (id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1)",
            )
            execSQL(
                "INSERT INTO local_customers (localId, phone, createdAtMillis, state, version) " +
                    "VALUES ('cust-1', '9999900001', 1000, 'synced', 0)",
            )
            close()
        }

        // MIGRATION_6_7 widens menu_items (type/hsnCode/priceIncludesTax/
        // description) and adds the two Phase 7 outbox tables
        // (local_menu_categories, local_menu_items) — validated against the
        // real, Room-generated v7 schema.
        val migrated = helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7)

        migrated.query(
            "SELECT name, type, hsnCode, priceIncludesTax, description FROM menu_items WHERE id = 'item-1'",
        ).use { cursor ->
            assert(cursor.moveToFirst()) { "menu_items row lost across the migration" }
            assert(cursor.getString(0) == "Cold Coffee")
            assert(cursor.getString(1) == "") { "new NOT NULL `type` column should default to ''" }
            assert(cursor.isNull(2)) { "new nullable `hsnCode` column should default to NULL" }
            assert(cursor.getInt(3) == 1) { "new NOT NULL `priceIncludesTax` column should default to true" }
            assert(cursor.isNull(4)) { "new nullable `description` column should default to NULL" }
        }
        migrated.query("SELECT localId, state FROM local_customers WHERE localId = 'cust-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "local_customers row lost across the migration" }
            assert(cursor.getString(1) == "synced")
        }
        for (table in listOf("local_menu_categories", "local_menu_items")) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assert(cursor.getInt(0) == 0) { "$table should exist and start empty" }
            }
        }
        migrated.close()
    }

    @Test
    fun migrate7To8_preservesExistingDataAndAddsStaffAndAttendanceTables() {
        // Same "oldest table survives" canary the rest of this file uses —
        // a v1 menu_items row (v7's already-widened column set, since
        // MIGRATION_6_7 already ran by this point) plus a Phase 7 table
        // (local_menu_categories), to prove this migration disturbs neither
        // the original table nor the immediately-preceding phase's.
        helper.createDatabase(dbName, 7).apply {
            execSQL(
                "INSERT INTO menu_items " +
                    "(id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable, type, hsnCode, priceIncludesTax, description) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1, 'drink', NULL, 1, NULL)",
            )
            execSQL(
                "INSERT INTO local_menu_categories (localId, name, createdAtMillis, state, version) " +
                    "VALUES ('cat-1', 'Snacks', 1000, 'synced', 0)",
            )
            close()
        }

        // MIGRATION_7_8 adds the three new Phase 8 tables (staff_cache,
        // local_staff, on_shift_cache) — validated against the real,
        // Room-generated v8 schema.
        val migrated = helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8)

        migrated.query("SELECT name FROM menu_items WHERE id = 'item-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "menu_items row lost across the migration" }
            assert(cursor.getString(0) == "Cold Coffee")
        }
        migrated.query("SELECT localId, name, state FROM local_menu_categories WHERE localId = 'cat-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "local_menu_categories row lost across the migration" }
            assert(cursor.getString(1) == "Snacks")
            assert(cursor.getString(2) == "synced")
        }
        for (table in listOf("staff_cache", "local_staff", "on_shift_cache")) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assert(cursor.getInt(0) == 0) { "$table should exist and start empty" }
            }
        }
        migrated.close()
    }

    @Test
    fun migrate8To9_preservesExistingDataAndAddsInventoryTables() {
        // Same "oldest table survives" canary — a v1 menu_items row plus a
        // Phase 8 table (local_staff) — to prove this migration disturbs
        // neither the original table nor the immediately-preceding phase's.
        helper.createDatabase(dbName, 8).apply {
            execSQL(
                "INSERT INTO menu_items " +
                    "(id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable, type, hsnCode, priceIncludesTax, description) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1, 'drink', NULL, 1, NULL)",
            )
            execSQL(
                "INSERT INTO local_staff " +
                    "(localId, serverId, name, phone, status, roleCode, pendingDelete, createdAtMillis, state, lastError, version) " +
                    "VALUES ('staff-1', 'server-staff-1', 'Rafi', NULL, NULL, NULL, 0, 1000, 'synced', NULL, 0)",
            )
            close()
        }

        // MIGRATION_8_9 adds the eight new Phase 9 tables (ingredient_cache,
        // local_ingredients, supplier_cache, local_suppliers, batch_cache,
        // local_grns, local_grn_lines, local_adjustments) — validated against
        // the real, Room-generated v9 schema.
        val migrated = helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9)

        migrated.query("SELECT name FROM menu_items WHERE id = 'item-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "menu_items row lost across the migration" }
            assert(cursor.getString(0) == "Cold Coffee")
        }
        migrated.query("SELECT localId, name, state FROM local_staff WHERE localId = 'staff-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "local_staff row lost across the migration" }
            assert(cursor.getString(1) == "Rafi")
            assert(cursor.getString(2) == "synced")
        }
        for (table in listOf(
            "ingredient_cache", "local_ingredients", "supplier_cache", "local_suppliers",
            "batch_cache", "local_grns", "local_grn_lines", "local_adjustments",
        )) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assert(cursor.getInt(0) == 0) { "$table should exist and start empty" }
            }
        }
        migrated.close()
    }

    @Test
    fun migrate9To10_preservesExistingDataAndAddsFinanceTables() {
        // Same "oldest table survives" canary — a v1 menu_items row plus a
        // Phase 9 table (local_ingredients) — to prove this migration
        // disturbs neither the original table nor the immediately-preceding
        // phase's.
        helper.createDatabase(dbName, 9).apply {
            execSQL(
                "INSERT INTO menu_items " +
                    "(id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable, type, hsnCode, priceIncludesTax, description) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1, 'drink', NULL, 1, NULL)",
            )
            execSQL(
                "INSERT INTO local_ingredients " +
                    "(localId, serverId, sku, name, baseUnit, reorderThreshold, reorderQty, pendingDelete, createdAtMillis, state, lastError, version) " +
                    "VALUES ('ing-1', 'server-ing-1', 'COFFEE-BEAN', 'Coffee', 'g', 500, 1000, 0, 1000, 'synced', NULL, 0)",
            )
            close()
        }

        // MIGRATION_9_10 adds the six new Phase 10 tables (expense_cache,
        // local_expenses, asset_cache, local_assets, capital_entry_cache,
        // local_capital_entries) — validated against the real, Room-generated
        // v10 schema.
        val migrated = helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10)

        migrated.query("SELECT name FROM menu_items WHERE id = 'item-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "menu_items row lost across the migration" }
            assert(cursor.getString(0) == "Cold Coffee")
        }
        migrated.query("SELECT localId, name, state FROM local_ingredients WHERE localId = 'ing-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "local_ingredients row lost across the migration" }
            assert(cursor.getString(1) == "Coffee")
            assert(cursor.getString(2) == "synced")
        }
        for (table in listOf(
            "expense_cache", "local_expenses", "asset_cache", "local_assets",
            "capital_entry_cache", "local_capital_entries",
        )) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assert(cursor.getInt(0) == 0) { "$table should exist and start empty" }
            }
        }
        migrated.close()
    }

    @Test
    fun migrate10To11_preservesExistingDataAndAddsEventTables() {
        // Same "oldest table survives" canary — a v1 menu_items row plus a
        // Phase 10 table (local_expenses) — to prove this migration disturbs
        // neither the original table nor the immediately-preceding phase's.
        helper.createDatabase(dbName, 10).apply {
            execSQL(
                "INSERT INTO menu_items " +
                    "(id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable, type, hsnCode, priceIncludesTax, description) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1, 'drink', NULL, 1, NULL)",
            )
            execSQL(
                "INSERT INTO local_expenses " +
                    "(localId, branchId, categoryId, supplierId, amountMinor, paidVia, paidAt, vendorName, invoiceNo, note, createdAtMillis, syncState, lastError) " +
                    "VALUES ('exp-1', 'branch-1', 'cat-1', NULL, 45000, 'cash', '2026-08-22T10:00:00Z', NULL, NULL, NULL, 1000, 'synced', NULL)",
            )
            close()
        }

        // MIGRATION_10_11 adds the four new Phase 11 tables (event_cache,
        // event_ticket_cache, local_ticket_sales, local_check_ins) —
        // validated against the real, Room-generated v11 schema.
        val migrated = helper.runMigrationsAndValidate(dbName, 11, true, MIGRATION_10_11)

        migrated.query("SELECT name FROM menu_items WHERE id = 'item-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "menu_items row lost across the migration" }
            assert(cursor.getString(0) == "Cold Coffee")
        }
        migrated.query("SELECT localId, amountMinor, syncState FROM local_expenses WHERE localId = 'exp-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "local_expenses row lost across the migration" }
            assert(cursor.getLong(1) == 45000L)
            assert(cursor.getString(2) == "synced")
        }
        for (table in listOf(
            "event_cache", "event_ticket_cache", "local_ticket_sales", "local_check_ins",
        )) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assert(cursor.getInt(0) == 0) { "$table should exist and start empty" }
            }
        }
        migrated.close()
    }

    @Test
    fun migrate11To12_preservesExistingDataAndAddsMembershipTables() {
        // Same "oldest table survives" canary — a v1 menu_items row plus a
        // Phase 11 table (local_ticket_sales) — to prove this migration
        // disturbs neither the original table nor the immediately-preceding
        // phase's.
        helper.createDatabase(dbName, 11).apply {
            execSQL(
                "INSERT INTO menu_items " +
                    "(id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable, type, hsnCode, priceIncludesTax, description) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1, 'drink', NULL, 1, NULL)",
            )
            execSQL(
                "INSERT INTO local_ticket_sales " +
                    "(localId, eventId, customerName, customerPhone, seat, qty, note, createdAtMillis, syncState, lastError) " +
                    "VALUES ('sale-1', 'event-1', 'Rahul', NULL, NULL, 2, NULL, 1000, 'synced', NULL)",
            )
            close()
        }

        // MIGRATION_11_12 adds the four new Phase 12 tables (membership_tier_cache,
        // customer_membership_cache, local_subscriptions, local_membership_cancellations)
        // — validated against the real, Room-generated v12 schema.
        val migrated = helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12)

        migrated.query("SELECT name FROM menu_items WHERE id = 'item-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "menu_items row lost across the migration" }
            assert(cursor.getString(0) == "Cold Coffee")
        }
        migrated.query("SELECT localId, customerName, syncState FROM local_ticket_sales WHERE localId = 'sale-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "local_ticket_sales row lost across the migration" }
            assert(cursor.getString(1) == "Rahul")
            assert(cursor.getString(2) == "synced")
        }
        for (table in listOf(
            "membership_tier_cache", "customer_membership_cache",
            "local_subscriptions", "local_membership_cancellations",
        )) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assert(cursor.getInt(0) == 0) { "$table should exist and start empty" }
            }
        }
        migrated.close()
    }

    @Test
    fun migrate12To13_preservesExistingDataAndAddsSettingsTables() {
        // Same "oldest table survives" canary — a v1 menu_items row plus a
        // Phase 12 table (local_subscriptions) — to prove this migration
        // disturbs neither the original table nor the immediately-preceding
        // phase's.
        helper.createDatabase(dbName, 12).apply {
            execSQL(
                "INSERT INTO menu_items " +
                    "(id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable, type, hsnCode, priceIncludesTax, description) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1, 'drink', NULL, 1, NULL)",
            )
            execSQL(
                "INSERT INTO local_subscriptions " +
                    "(localId, customerId, tierId, billingCycle, paidVia, createdAtMillis, syncState, lastError) " +
                    "VALUES ('sub-1', 'cust-1', 'tier-1', 'monthly', 'cash', 1000, 'synced', NULL)",
            )
            close()
        }

        // MIGRATION_12_13 adds the six new Phase 13 tables (company_cache,
        // local_company_edits, branch_cache, local_branches, terminal_cache,
        // local_terminals) — validated against the real, Room-generated v13
        // schema.
        val migrated = helper.runMigrationsAndValidate(dbName, 13, true, MIGRATION_12_13)

        migrated.query("SELECT name FROM menu_items WHERE id = 'item-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "menu_items row lost across the migration" }
            assert(cursor.getString(0) == "Cold Coffee")
        }
        migrated.query("SELECT localId, customerId, syncState FROM local_subscriptions WHERE localId = 'sub-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "local_subscriptions row lost across the migration" }
            assert(cursor.getString(1) == "cust-1")
            assert(cursor.getString(2) == "synced")
        }
        for (table in listOf(
            "company_cache", "local_company_edits",
            "branch_cache", "local_branches",
            "terminal_cache", "local_terminals",
        )) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assert(cursor.getInt(0) == 0) { "$table should exist and start empty" }
            }
        }
        migrated.close()
    }

    @Test
    fun migrate13To14_preservesExistingDataAndAddsHeldOrderTables() {
        // Same "oldest table survives" canary — a v1 menu_items row plus a
        // Phase 13 table (local_branches) — to prove this migration disturbs
        // neither the original table nor the immediately-preceding phase's.
        helper.createDatabase(dbName, 13).apply {
            execSQL(
                "INSERT INTO menu_items " +
                    "(id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable, type, hsnCode, priceIncludesTax, description) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1, 'drink', NULL, 1, NULL)",
            )
            execSQL(
                "INSERT INTO local_branches " +
                    "(localId, name, code, address, timezone, opensAt, closesAt, stateCode, " +
                    "fssaiLicenseNo, tradeLicenseNo, branchGstin, createdAtMillis, syncState, lastError) " +
                    "VALUES ('branch-1', 'Airport', NULL, NULL, NULL, NULL, NULL, NULL, " +
                    "NULL, NULL, NULL, 1000, 'synced', NULL)",
            )
            execSQL(
                "INSERT INTO local_gaming_sessions " +
                    "(localId, serverId, stationId, startedAtMillis, state, status) " +
                    "VALUES ('gaming-1', 'server-gaming-1', 'station-1', 1000, 'stopped', 'ended')",
            )
            close()
        }

        // MIGRATION_13_14 adds held_order_cache and local_held_order_payments
        // — validated against the real, Room-generated v14 schema.
        val migrated = helper.runMigrationsAndValidate(dbName, 14, true, MIGRATION_13_14)

        migrated.query("SELECT name FROM menu_items WHERE id = 'item-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "menu_items row lost across the migration" }
            assert(cursor.getString(0) == "Cold Coffee")
        }
        migrated.query("SELECT localId, name, syncState FROM local_branches WHERE localId = 'branch-1'").use { cursor ->
            assert(cursor.moveToFirst()) { "local_branches row lost across the migration" }
            assert(cursor.getString(1) == "Airport")
            assert(cursor.getString(2) == "synced")
        }
        migrated.query("SELECT state, orderId FROM local_gaming_sessions WHERE localId = 'gaming-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ended_unbilled", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        for (table in listOf("held_order_cache", "local_held_order_payments")) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assert(cursor.getInt(0) == 0) { "$table should exist and start empty" }
            }
        }
        migrated.execSQL(
            "INSERT INTO held_order_cache " +
                "(id, invoiceNo, type, sourceLabel, totalMinor, paidMinor, itemsCount, customerName, createdAt, heldAt) " +
                "VALUES ('order-1', NULL, 'dine_in', 'Table 4', 10000, 0, 2, NULL, " +
                "'2026-08-25T10:00:00Z', '2026-08-25T10:10:00Z')",
        )
        migrated.execSQL(
            "INSERT INTO local_held_order_payments " +
                "(localId, targetOrderId, method, amountMinor, tenderedMinor, expectedTotalMinor, " +
                "expectedDueMinor, createdAtMillis, syncState, lastError) " +
                "VALUES ('payment-1', 'order-1', 'cash', 10000, 20000, 10000, 10000, 1000, 'pending', NULL)",
        )
        migrated.query("SELECT amountMinor, tenderedMinor FROM local_held_order_payments").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(10000L, cursor.getLong(0))
            assertEquals(20000L, cursor.getLong(1))
        }
        val duplicateRejected = try {
            migrated.execSQL(
                "INSERT INTO local_held_order_payments " +
                    "(localId, targetOrderId, method, amountMinor, tenderedMinor, expectedTotalMinor, " +
                    "expectedDueMinor, createdAtMillis, syncState, lastError) " +
                    "VALUES ('payment-2', 'order-1', 'cash', 10000, 10000, 10000, 10000, 1001, 'pending', NULL)",
            )
            false
        } catch (_: SQLiteConstraintException) {
            true
        }
        assertTrue("one held order must not accept two local payments", duplicateRejected)
        migrated.close()
    }

    @Test
    fun migrate14To15_preservesConfirmedPaymentsAndAddsCheckoutClaims() {
        helper.createDatabase(dbName, 14).apply {
            execSQL(
                "INSERT INTO menu_items " +
                    "(id, categoryId, sku, name, basePriceMinor, taxRate, isAvailable, type, " +
                    "hsnCode, priceIncludesTax, description) " +
                    "VALUES ('item-1', 'cat-1', 'SKU1', 'Cold Coffee', 12000, 0.05, 1, " +
                    "'drink', NULL, 1, NULL)",
            )
            execSQL(
                "INSERT INTO held_order_cache " +
                    "(id, invoiceNo, type, sourceLabel, totalMinor, paidMinor, itemsCount, " +
                    "customerName, createdAt, heldAt) VALUES ('order-1', NULL, 'dine_in', " +
                    "'Table 4', 10000, 0, 2, NULL, '2026-08-25T10:00:00Z', " +
                    "'2026-08-25T10:10:00Z')",
            )
            // This row represents money staff already confirmed in v14. The
            // upgrade must retain it even though old builds had no claim.
            execSQL(
                "INSERT INTO local_held_order_payments " +
                    "(localId, targetOrderId, method, amountMinor, tenderedMinor, " +
                    "expectedTotalMinor, expectedDueMinor, createdAtMillis, syncState, lastError) " +
                    "VALUES ('payment-1', 'order-1', 'cash', 10000, 20000, 10000, 10000, " +
                    "1000, 'pending', NULL)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 15, true, MIGRATION_14_15)

        migrated.query(
            "SELECT totalMinor, checkoutVersion FROM held_order_cache WHERE id = 'order-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(10000L, cursor.getLong(0))
            assertEquals(1L, cursor.getLong(1))
        }
        migrated.query(
            "SELECT amountMinor, claimToken, claimExpiresAtMillis, claimOrderVersion " +
                "FROM local_held_order_payments WHERE localId = 'payment-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(10000L, cursor.getLong(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
        }
        migrated.execSQL(
            "UPDATE local_held_order_payments SET claimToken = 'opaque-token', " +
                "claimExpiresAtMillis = 2000, claimOrderVersion = 7 " +
                "WHERE localId = 'payment-1'",
        )
        migrated.query(
            "SELECT claimToken, claimExpiresAtMillis, claimOrderVersion " +
                "FROM local_held_order_payments WHERE localId = 'payment-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("opaque-token", cursor.getString(0))
            assertEquals(2000L, cursor.getLong(1))
            assertEquals(7L, cursor.getLong(2))
        }
        migrated.close()
    }

    @Test
    fun migrate15To16_preservesShiftOutboxAndAddsTerminalServerCache() {
        helper.createDatabase(dbName, 15).apply {
            execSQL(
                "INSERT INTO local_shifts " +
                    "(localId, serverShiftId, openingFloatMinor, openedAtMillis, state, " +
                    "countedMinor, closedAtMillis, varianceMinor, lastError) VALUES " +
                    "('open-pending', NULL, 5000, 1000, 'open_pending', NULL, NULL, NULL, NULL), " +
                    "('close-pending', 'server-close', 5000, 1100, 'close_pending', 6000, 1200, NULL, NULL), " +
                    "('old-open-rejected', NULL, 5000, 900, 'rejected', NULL, NULL, NULL, 'open failed'), " +
                    "('old-close-rejected', 'server-rejected', 5000, 800, 'rejected', 5000, 1300, NULL, 'close failed')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 16, true, MIGRATION_15_16)

        migrated.query(
            "SELECT state, serverShiftId, terminalId, branchId, openedByUserId, " +
                "openedByName, openedByEmail FROM local_shifts WHERE localId = 'open-pending'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("open_pending", cursor.getString(0))
            for (column in 1..6) assertTrue(cursor.isNull(column))
        }
        migrated.query(
            "SELECT state, serverShiftId, countedMinor, closedAtMillis " +
                "FROM local_shifts WHERE localId = 'close-pending'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("close_pending", cursor.getString(0))
            assertEquals("server-close", cursor.getString(1))
            assertEquals(6000L, cursor.getLong(2))
            assertEquals(1200L, cursor.getLong(3))
        }
        migrated.query(
            "SELECT localId, state FROM local_shifts " +
                "WHERE localId IN ('old-open-rejected', 'old-close-rejected') ORDER BY localId",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("old-close-rejected", cursor.getString(0))
            assertEquals("close_rejected", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("old-open-rejected", cursor.getString(0))
            assertEquals("open_rejected", cursor.getString(1))
        }
        migrated.execSQL(
            "INSERT INTO server_open_shift_cache " +
                "(terminalId, serverShiftId, branchId, status, openingFloatMinor, expectedMinor, " +
                "openedAtMillis, openedByUserId, openedByName, openedByEmail, verifiedAtMillis) " +
                "VALUES ('terminal-1', 'server-1', 'branch-1', 'open', 5000, 7500, 1000, " +
                "'user-1', 'Rafi', 'rafi@example.com', 2000)",
        )
        migrated.query(
            "SELECT openedByName, openedByEmail, expectedMinor FROM server_open_shift_cache " +
                "WHERE terminalId = 'terminal-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Rafi", cursor.getString(0))
            assertEquals("rafi@example.com", cursor.getString(1))
            assertEquals(7500L, cursor.getLong(2))
        }
        migrated.close()
    }

    @Test
    fun migrate13To17_recoversLegacyGamingStartStopAndStoppedRows() {
        helper.createDatabase(dbName, 13).apply {
            execSQL(
                "INSERT INTO local_gaming_sessions " +
                    "(localId, serverId, stationId, shiftId, startedAtMillis, state, status, " +
                    "endAtMillis, billableMinutes, amountMinor, lastError) VALUES " +
                    "('legacy-start', NULL, 'ps5-1', 'shift-1', 1000, 'rejected', 'active', " +
                    "NULL, NULL, NULL, 'start refused'), " +
                    "('legacy-stop', 'session-stop', 'vr-1', NULL, 1100, 'rejected', 'active', " +
                    "NULL, NULL, NULL, 'stop refused'), " +
                    "('legacy-stopped', 'session-ended', 'sim-1', NULL, 1200, 'stopped', 'ended', " +
                    "1800, 10, 25000, NULL)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            17,
            true,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
        )

        migrated.query(
            "SELECT localId, state, status, serverId, amountMinor, orderId, lastError " +
                "FROM local_gaming_sessions ORDER BY localId",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-start", cursor.getString(0))
            assertEquals("start_rejected", cursor.getString(1))
            assertEquals("start_failed", cursor.getString(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertEquals("start refused", cursor.getString(6))

            assertTrue(cursor.moveToNext())
            assertEquals("legacy-stop", cursor.getString(0))
            assertEquals("stop_rejected", cursor.getString(1))
            assertEquals("active", cursor.getString(2))
            assertEquals("session-stop", cursor.getString(3))
            assertEquals("stop refused", cursor.getString(6))

            assertTrue(cursor.moveToNext())
            assertEquals("legacy-stopped", cursor.getString(0))
            assertEquals("ended_unbilled", cursor.getString(1))
            assertEquals("ended", cursor.getString(2))
            assertEquals(25000L, cursor.getLong(4))
            assertTrue(cursor.isNull(5))
        }
        migrated.close()
    }

    @Test
    fun migrate16To17_classifiesEveryLegacyGamingFailureWithoutReplayingIt() {
        helper.createDatabase(dbName, 16).apply {
            execSQL(
                "INSERT INTO local_gaming_sessions " +
                    "(localId, serverId, stationId, shiftId, startedAtMillis, state, status, " +
                    "endAtMillis, billableMinutes, amountMinor, orderId, lastError) VALUES " +
                    "('start', NULL, 'ps5-1', 'shift-1', 1000, 'rejected', 'starting', " +
                    "NULL, NULL, NULL, NULL, 'start failed'), " +
                    "('stop', 'session-2', 'vr-1', NULL, 1100, 'rejected', 'stopping', " +
                    "NULL, NULL, NULL, NULL, 'stop failed'), " +
                    "('send', 'session-3', 'sim-1', NULL, 1200, 'rejected', 'ended', " +
                    "1800, 10, 25000, NULL, 'send failed'), " +
                    "('already-sent', 'session-4', 'stream-1', NULL, 1300, 'rejected', 'ended', " +
                    "1900, 10, 30000, 'order-4', 'stale local state')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 17, true, MIGRATION_16_17)

        migrated.query(
            "SELECT localId, state, status, serverId, amountMinor, orderId, lastError " +
                "FROM local_gaming_sessions ORDER BY localId",
        ).use { cursor ->
            val recovered = buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(0),
                        listOf(
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getString(3),
                            if (cursor.isNull(4)) null else cursor.getLong(4).toString(),
                            cursor.getString(5),
                            cursor.getString(6),
                        ),
                    )
                }
            }
            assertEquals("sent", recovered.getValue("already-sent")[0])
            assertEquals("order-4", recovered.getValue("already-sent")[4])
            assertEquals("send_rejected", recovered.getValue("send")[0])
            assertEquals("ended", recovered.getValue("send")[1])
            assertEquals("25000", recovered.getValue("send")[3])
            assertEquals("send failed", recovered.getValue("send")[5])
            assertEquals("start_rejected", recovered.getValue("start")[0])
            assertEquals("start_failed", recovered.getValue("start")[1])
            assertTrue(recovered.getValue("start")[2] == null)
            assertEquals("stop_rejected", recovered.getValue("stop")[0])
            assertEquals("active", recovered.getValue("stop")[1])
            assertEquals("session-2", recovered.getValue("stop")[2])
        }

        // Idempotency matters because SyncEngine and GamingViewModel also run
        // the same safety net for imported backups already stamped as v17.
        migrated.execSQL(RECOVER_LEGACY_GAMING_REJECTIONS_SQL)
        migrated.query("SELECT COUNT(*) FROM local_gaming_sessions WHERE state = 'rejected'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate17To18_preservesQueuedMembershipAndAddsOnlyHistoricalShiftBinding() {
        helper.createDatabase(dbName, 17).apply {
            execSQL(
                "INSERT INTO local_subscriptions " +
                    "(localId, customerId, tierId, billingCycle, paidVia, createdAtMillis, " +
                    "syncState, lastError) VALUES " +
                    "('legacy-sub', 'customer-1', 'gold', 'monthly', 'cash', 1000, " +
                    "'rejected', 'old build could not post')",
            )
            execSQL(
                "INSERT INTO customer_membership_cache " +
                    "(id, customerId, tierId, tierCode, tierName, billingCycle, startsAt, " +
                    "expiresAt, cancelledAt, autoRenew, amountPaidMinor, isActive) VALUES " +
                    "('membership-1', 'customer-1', 'gold', 'gold', 'Gold', 'monthly', " +
                    "'2026-08-01T00:00:00Z', '2026-08-31T00:00:00Z', NULL, 0, 199900, 1)",
            )
            close()
        }

        // v18's exported schema contains only shiftId. New price/payment facts
        // belong to 18->19; changing this historical boundary makes Room reject
        // a legitimate v17 installation during upgrade.
        val migrated = helper.runMigrationsAndValidate(dbName, 18, true, MIGRATION_17_18)

        migrated.query(
            "SELECT customerId, tierId, paidVia, syncState, lastError, shiftId " +
                "FROM local_subscriptions WHERE localId = 'legacy-sub'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("customer-1", cursor.getString(0))
            assertEquals("gold", cursor.getString(1))
            assertEquals("cash", cursor.getString(2))
            assertEquals("rejected", cursor.getString(3))
            assertEquals("old build could not post", cursor.getString(4))
            assertTrue(cursor.isNull(5)) // never guess which drawer owned it
        }
        migrated.query(
            "SELECT amountPaidMinor, isActive FROM customer_membership_cache " +
                "WHERE id = 'membership-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(199900L, cursor.getLong(0))
            assertEquals(1, cursor.getInt(1))
        }
        migrated.close()
    }

    @Test
    fun migrate18To19_quarantinesLegacyPriceAndAddsMembershipReceiptRecoveryTables() {
        helper.createDatabase(dbName, 18).apply {
            execSQL(
                "INSERT INTO local_subscriptions " +
                    "(localId, customerId, tierId, shiftId, billingCycle, paidVia, " +
                    "createdAtMillis, syncState, lastError) VALUES " +
                    "('legacy-sub', 'customer-1', 'gold', 'shift-1', 'monthly', 'upi', " +
                    "1000, 'rejected', 'price not snapshotted')",
            )
            execSQL(
                "INSERT INTO customer_membership_cache " +
                    "(id, customerId, tierId, tierCode, tierName, billingCycle, startsAt, " +
                    "expiresAt, cancelledAt, autoRenew, amountPaidMinor, isActive) VALUES " +
                    "('membership-1', 'customer-1', 'gold', 'gold', 'Gold', 'monthly', " +
                    "'2026-08-01T00:00:00Z', '2026-08-31T00:00:00Z', NULL, 0, 199900, 1)",
            )
            execSQL(
                "INSERT INTO server_open_shift_cache " +
                    "(terminalId, serverShiftId, branchId, status, openingFloatMinor, " +
                    "expectedMinor, openedAtMillis, openedByUserId, openedByName, " +
                    "openedByEmail, verifiedAtMillis) VALUES " +
                    "('terminal-1', 'shift-1', 'branch-1', 'open', 5000, 7500, 1000, " +
                    "'owner-1', 'Owner', 'owner@example.com', 2000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 19, true, MIGRATION_18_19)

        migrated.query(
            "SELECT shiftId, expectedAmountMinor, syncState, lastError " +
                "FROM local_subscriptions WHERE localId = 'legacy-sub'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("shift-1", cursor.getString(0))
            assertTrue(cursor.isNull(1)) // no price inference from current tier
            assertEquals("rejected", cursor.getString(2))
            assertEquals("price not snapshotted", cursor.getString(3))
        }
        migrated.query(
            "SELECT amountPaidMinor, revokedAt, paymentId, paymentReceiptNo, refundId " +
                "FROM customer_membership_cache WHERE id = 'membership-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(199900L, cursor.getLong(0))
            for (column in 1..4) assertTrue(cursor.isNull(column))
        }
        migrated.query(
            "SELECT expectedMinor, posCollectionsMinor, membershipCollectionsMinor, " +
                "grossCollectionsMinor, settledMembershipRefundsMinor " +
                "FROM server_open_shift_cache WHERE terminalId = 'terminal-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7500L, cursor.getLong(0))
            for (column in 1..4) assertTrue(cursor.isNull(column))
        }
        for (table in listOf("customer_membership_history_cache", "local_membership_refunds")) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        migrated.execSQL(
            "INSERT INTO local_membership_refunds " +
                "(localId, customerId, subscriptionId, shiftId, expectedAmountMinor, " +
                "method, reason, externalReference, settledAtMillis, serverRefundId, " +
                "receiptNo, withdrawalReason, withdrawalAtMillis, createdAtMillis, " +
                "syncState, lastError) VALUES " +
                "('refund-1', 'customer-1', 'membership-1', 'shift-1', 199900, " +
                "'cash', 'Customer requested refund', NULL, NULL, NULL, NULL, NULL, " +
                "NULL, 3000, 'request_pending', NULL)",
        )
        migrated.query(
            "SELECT shiftId, expectedAmountMinor, syncState FROM local_membership_refunds " +
                "WHERE localId = 'refund-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("shift-1", cursor.getString(0))
            assertEquals(199900L, cursor.getLong(1))
            assertEquals("request_pending", cursor.getString(2))
        }
        migrated.close()
    }

    @Test
    fun migrate19To20_quarantinesAmbiguousRefundsAndAddsExactSettlementProvenance() {
        helper.createDatabase(dbName, 19).apply {
            execSQL(
                "INSERT INTO local_refunds " +
                    "(localId, orderId, invoiceNo, reasonCode, amountMinor, note, " +
                    "createdAtMillis, state, settlementMethod, lastError) VALUES " +
                    "('accepted-old', 'order-1', 'INV-1', 'billing_error', 5000, NULL, " +
                    "1000, 'accepted_cash_due', 'cash', NULL), " +
                    "('pending-old', 'order-2', 'INV-2', 'other', 3000, NULL, " +
                    "1100, 'request_pending', NULL, 'connection lost'), " +
                    "('settled-old', 'order-3', 'INV-3', 'other', 2000, NULL, " +
                    "1200, 'synced', 'cash', NULL)",
            )
            execSQL(
                "INSERT INTO server_open_shift_cache " +
                    "(terminalId, serverShiftId, branchId, status, openingFloatMinor, expectedMinor, " +
                    "posCollectionsMinor, membershipCollectionsMinor, grossCollectionsMinor, " +
                    "settledMembershipRefundsMinor, openedAtMillis, openedByUserId, openedByName, " +
                    "openedByEmail, verifiedAtMillis) VALUES " +
                    "('terminal-1', 'shift-1', 'branch-1', 'open', 10000, 15000, " +
                    "5000, 0, 5000, 0, 900, 'owner-1', 'Owner', 'owner@example.com', 1300)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 20, true, MIGRATION_19_20)

        migrated.query(
            "SELECT localId, state, clientActionId, shiftId, serverRequestId, lastError " +
                "FROM local_refunds ORDER BY localId",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("accepted-old", cursor.getString(0))
            assertEquals(RefundState.LEGACY_RECONCILIATION_REQUIRED, cursor.getString(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.getString(5).contains("do not pay again", ignoreCase = true))

            assertTrue(cursor.moveToNext())
            assertEquals("pending-old", cursor.getString(0))
            assertEquals(RefundState.LEGACY_RECONCILIATION_REQUIRED, cursor.getString(1))
            assertTrue(cursor.getString(5).contains("connection lost"))

            assertTrue(cursor.moveToNext())
            assertEquals("settled-old", cursor.getString(0))
            assertEquals(RefundState.LEGACY_SETTLED, cursor.getString(1))
        }
        migrated.query(
            "SELECT settledPosRefundsMinor, totalRefundsMinor, netCollectionsMinor " +
                "FROM server_open_shift_cache WHERE terminalId = 'terminal-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
        migrated.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN (" +
                "'index_local_refunds_shiftId', 'index_local_refunds_clientActionId', " +
                "'index_local_refunds_serverRequestId')",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate20To21_preservesAndClassifiesLegacyMembershipPaymentsAndAddsShiftHistory() {
        helper.createDatabase(dbName, 20).apply {
            execSQL(
                "INSERT INTO local_subscriptions " +
                    "(localId, customerId, tierId, shiftId, expectedAmountMinor, billingCycle, " +
                    "paidVia, createdAtMillis, syncState, lastError) VALUES " +
                    "('known', 'customer-1', 'tier-1', 'shift-1', 19900, 'monthly', " +
                    "'upi', 1000, 'pending', 'response lost'), " +
                    "('unknown', 'customer-2', 'tier-2', NULL, NULL, 'monthly', " +
                    "'cash', 2000, 'rejected', 'old row'), " +
                    "('done', 'customer-3', 'tier-3', 'shift-3', 9900, 'monthly', " +
                    "'cash', 3000, 'synced', NULL)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 21, true, MIGRATION_20_21)

        migrated.query(
            "SELECT sourceLegacyLocalId, rootClientActionId, kind, shiftId, " +
                "expectedAmountMinor, state, lastError " +
                "FROM local_membership_payment_actions ORDER BY sourceLegacyLocalId",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("known", cursor.getString(0))
            assertEquals("membership-subscribe:known", cursor.getString(1))
            assertEquals(MembershipPaymentActionKind.LEGACY_PROBE, cursor.getString(2))
            assertEquals("shift-1", cursor.getString(3))
            assertEquals(19900L, cursor.getLong(4))
            assertEquals(MembershipMoneyActionState.PENDING, cursor.getString(5))
            assertEquals("response lost", cursor.getString(6))

            assertTrue(cursor.moveToNext())
            assertEquals("unknown", cursor.getString(0))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertEquals(MembershipMoneyActionState.LEGACY_PROVENANCE_MISSING, cursor.getString(5))
            assertTrue(cursor.getString(6).contains("Do not collect", ignoreCase = true))
            assertTrue(!cursor.moveToNext()) // synced source rows are not copied
        }
        migrated.query(
            "SELECT localId, syncState FROM local_subscriptions ORDER BY localId",
        ).use { cursor ->
            val states = mutableMapOf<String, String>()
            while (cursor.moveToNext()) states[cursor.getString(0)] = cursor.getString(1)
            assertEquals("synced", states["done"])
            assertEquals("migrated_v21", states["known"])
            assertEquals("migrated_v21", states["unknown"])
        }
        for (table in listOf("membership_payment_task_cache", "shift_history_cache")) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        migrated.query(
            "SELECT name, dflt_value FROM pragma_table_info('customer_membership_cache') " +
                "WHERE name IN ('paymentEvidenceTimeUntrusted', " +
                "'paymentProviderEvidenceReconciled') ORDER BY name",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("paymentEvidenceTimeUntrusted", cursor.getString(0))
            assertEquals("0", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("paymentProviderEvidenceReconciled", cursor.getString(0))
            assertEquals("1", cursor.getString(1))
        }
        migrated.close()
    }

    @Test
    fun migrate21To22_preservesLegacyRefundEvidenceAndNeverReplaysItAsFreshMoney() {
        helper.createDatabase(dbName, 21).apply {
            execSQL(
                "INSERT INTO customer_membership_history_cache " +
                    "(id, customerId, tierId, tierCode, tierName, billingCycle, startsAt, " +
                    "expiresAt, autoRenew, amountPaidMinor, paymentId, paymentMethod, " +
                    "paymentEvidenceTimeUntrusted, paymentProviderEvidenceReconciled, isActive) VALUES " +
                    "('membership-1', 'customer-1', 'tier-1', 'gold', 'Gold', 'monthly', " +
                    "'2026-08-01T00:00:00Z', '2026-09-01T00:00:00Z', 0, 19900, " +
                    "'payment-1', 'cash', 0, 1, 0), " +
                    "('membership-2', 'customer-2', 'tier-2', 'silver', 'Silver', 'monthly', " +
                    "'2026-08-01T00:00:00Z', '2026-09-01T00:00:00Z', 0, 9900, " +
                    "'payment-2', 'upi', 0, 1, 0)",
            )
            execSQL(
                "INSERT INTO local_membership_refunds " +
                    "(localId, customerId, subscriptionId, shiftId, expectedAmountMinor, method, " +
                    "reason, externalReference, settledAtMillis, serverRefundId, createdAtMillis, " +
                    "syncState, lastError) VALUES " +
                    "('server-known', 'customer-1', 'membership-1', 'shift-1', 19900, 'cash', " +
                    "'Customer request', NULL, 1200, 'refund-server-1', 1000, " +
                    "'cash_settle_pending', 'response lost'), " +
                    "('register-provider', 'customer-2', 'membership-2', 'shift-2', 9900, 'upi', " +
                    "'Customer request', 'provider-ref-1', 2200, NULL, 2000, " +
                    "'request_rejected', 'acceptance lost'), " +
                    "('missing-payment', 'customer-3', 'membership-3', 'shift-3', 4900, 'cash', " +
                    "'Customer request', NULL, NULL, NULL, 3000, 'request_pending', NULL), " +
                    "('done', 'customer-4', 'membership-4', 'shift-4', 3900, 'cash', " +
                    "'Customer request', NULL, 4000, 'refund-4', 3900, 'synced', NULL)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 22, true, MIGRATION_21_22)

        migrated.query(
            "SELECT sourceLegacyLocalId, rootClientActionId, serverRefundId, kind, paymentId, " +
                "cashHandoverConfirmed, state, lastError FROM local_membership_refund_actions " +
                "ORDER BY sourceLegacyLocalId",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("missing-payment", cursor.getString(0))
            assertEquals(MembershipRefundActionKind.LEGACY_REGISTER, cursor.getString(3))
            assertTrue(cursor.isNull(4))
            assertEquals(MembershipMoneyActionState.LEGACY_PROVENANCE_MISSING, cursor.getString(6))
            assertTrue(cursor.getString(7).contains("no verified payment", ignoreCase = true))

            assertTrue(cursor.moveToNext())
            assertEquals("register-provider", cursor.getString(0))
            assertEquals("membership-refund:register-provider", cursor.getString(1))
            assertTrue(cursor.isNull(2))
            assertEquals(MembershipRefundActionKind.LEGACY_REGISTER, cursor.getString(3))
            assertEquals("payment-2", cursor.getString(4))
            assertEquals(MembershipMoneyActionState.PENDING, cursor.getString(6))

            assertTrue(cursor.moveToNext())
            assertEquals("server-known", cursor.getString(0))
            assertEquals("refund-server-1", cursor.getString(2))
            assertEquals(MembershipRefundActionKind.LEGACY_RECONCILE_SERVER, cursor.getString(3))
            assertEquals("payment-1", cursor.getString(4))
            assertEquals(1, cursor.getInt(5))
            assertEquals(MembershipMoneyActionState.PENDING, cursor.getString(6))
            assertTrue(!cursor.moveToNext())
        }
        migrated.query(
            "SELECT localId, syncState FROM local_membership_refunds ORDER BY localId",
        ).use { cursor ->
            val states = mutableMapOf<String, String>()
            while (cursor.moveToNext()) states[cursor.getString(0)] = cursor.getString(1)
            assertEquals("synced", states["done"])
            assertEquals("migrated_v22", states["missing-payment"])
            assertEquals("migrated_v22", states["register-provider"])
            assertEquals("migrated_v22", states["server-known"])
        }
        for (table in listOf("membership_refund_task_cache", "membership_refund_attempt_cache")) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        migrated.query(
            "SELECT name, dflt_value FROM pragma_table_info('customer_membership_history_cache') " +
                "WHERE name IN ('refundEvidenceTimeUntrusted', 'refundProviderEvidenceReconciled', " +
                "'refundCustomerSpendReconciled') ORDER BY name",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("refundCustomerSpendReconciled", cursor.getString(0))
            assertEquals("1", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("refundEvidenceTimeUntrusted", cursor.getString(0))
            assertEquals("0", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("refundProviderEvidenceReconciled", cursor.getString(0))
            assertEquals("1", cursor.getString(1))
        }
        migrated.close()
    }

    @Test
    fun migrate22To23_preservesHeldPaymentsAndInstallsAtomicShiftCloseGuards() {
        helper.createDatabase(dbName, 22).apply {
            execSQL(
                "INSERT INTO local_held_order_payments " +
                    "(localId, targetOrderId, method, amountMinor, tenderedMinor, " +
                    "expectedTotalMinor, expectedDueMinor, claimToken, claimExpiresAtMillis, " +
                    "claimOrderVersion, createdAtMillis, syncState, lastError) VALUES " +
                    "('legacy-payment', 'order-1', 'upi', 1500, NULL, 1500, 1500, " +
                    "'claim-1', 9000, 3, 1000, 'pending', 'response unknown')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 23, true, MIGRATION_22_23)

        migrated.query(
            "SELECT localId, shiftId, terminalId, syncState, lastError " +
                "FROM local_held_order_payments WHERE localId = 'legacy-payment'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-payment", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertEquals(HeldOrderPaymentState.PENDING, cursor.getString(3))
            assertEquals("response unknown", cursor.getString(4))
        }
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name IN " +
                "('index_local_held_order_payments_shiftId', " +
                "'index_local_held_order_payments_terminalId') ORDER BY name",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("index_local_held_order_payments_shiftId", cursor.getString(0))
            assertTrue(cursor.moveToNext())
            assertEquals("index_local_held_order_payments_terminalId", cursor.getString(0))
            assertTrue(!cursor.moveToNext())
        }
        migrated.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' " +
                "AND name LIKE 'guard_%_while_shift_closing'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(18, cursor.getInt(0))
        }

        migrated.execSQL(
            "INSERT INTO local_shifts " +
                "(localId, serverShiftId, terminalId, branchId, openingFloatMinor, " +
                "openedAtMillis, state) VALUES " +
                "('shift-closing', 'server-shift', 'terminal-1', 'branch-1', 5000, 1000, " +
                "'close_pending')",
        )
        var lateSaleBlocked = false
        try {
            migrated.execSQL(
                "INSERT INTO local_orders " +
                    "(localId, shiftId, type, estimateMinor, paymentMethod, tenderedMinor, " +
                    "tipMinor, createdAtMillis, syncState) VALUES " +
                    "('late-sale', 'shift-closing', 'dine_in', 1000, 'cash', 1000, 0, 2000, 'pending')",
            )
        } catch (e: SQLiteConstraintException) {
            lateSaleBlocked = e.message.orEmpty().contains(SHIFT_CLOSING_WRITE_GUARD)
        }
        assertTrue(lateSaleBlocked)

        // An already-pending sync may finish while the close intent exists,
        // but a stale screen cannot create a new stop leg on that lifecycle.
        migrated.execSQL(
            "INSERT INTO local_gaming_sessions " +
                "(localId, serverId, stationId, shiftId, startedAtMillis, state, status) VALUES " +
                "('gaming-1', 'server-gaming-1', 'station-1', 'shift-closing', 1000, " +
                "'start_synced', 'active')",
        )
        var lateStopBlocked = false
        try {
            migrated.execSQL(
                "UPDATE local_gaming_sessions SET state = 'stop_pending' WHERE localId = 'gaming-1'",
            )
        } catch (e: SQLiteConstraintException) {
            lateStopBlocked = e.message.orEmpty().contains(SHIFT_CLOSING_WRITE_GUARD)
        }
        assertTrue(lateStopBlocked)
        migrated.close()
    }

    @Test
    fun migrate23To24PreservesCafeIntentAndAddsDurableRoundsAndCancellationAcks() {
        helper.createDatabase(dbName, 23).apply {
            execSQL(
                "INSERT INTO kitchen_order_cache " +
                    "(id, type, kitchenState, minutesWaiting, lines) VALUES " +
                    "('kitchen-1', 'dine_in', 'served', 4, '[]')",
            )
            execSQL(
                "INSERT INTO local_table_orders " +
                    "(localId, orderId, tableId, tableCode, shiftId, lines, createdAtMillis, state, lastError) " +
                    "VALUES " +
                    "('legacy-pending', NULL, 'table-1', 'T1', 'shift-1', " +
                    "'[{' || char(34) || 'menu_item_id' || char(34) || ':' || char(34) || " +
                    "'menu-1' || char(34) || ',' || char(34) || 'qty' || char(34) || ':2}]', " +
                    "1000, 'pending', 'response unknown'), " +
                    "('legacy-rejected', 'server-order-2', 'table-2', 'T2', 'shift-1', " +
                    "'[{' || char(34) || 'menu_item_id' || char(34) || ':' || char(34) || " +
                    "'menu-2' || char(34) || ',' || char(34) || 'qty' || char(34) || ':1}]', " +
                    "2000, 'rejected', 'shift closed')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 24, true, MIGRATION_23_24)

        migrated.query(
            "SELECT pendingCancellations FROM kitchen_order_cache WHERE id = 'kitchen-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
        }
        migrated.query(
            "SELECT localBillId, serverOrderId, tableId, tableCode, shiftId, localStatus " +
                "FROM local_cafe_bills ORDER BY localBillId",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-pending", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertEquals("table-1", cursor.getString(2))
            assertEquals("T1", cursor.getString(3))
            assertEquals("shift-1", cursor.getString(4))
            assertEquals(LocalCafeBillState.OPEN, cursor.getString(5))

            assertTrue(cursor.moveToNext())
            assertEquals("legacy-rejected", cursor.getString(0))
            assertEquals("server-order-2", cursor.getString(1))
            assertTrue(!cursor.moveToNext())
        }
        migrated.query(
            "SELECT actionId, localBillId, sequence, kind, payload, dedupeKey, state, lastError " +
                "FROM local_cafe_actions ORDER BY actionId",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-pending", cursor.getString(0))
            assertEquals("legacy-pending", cursor.getString(1))
            assertEquals(1L, cursor.getLong(2))
            assertEquals(CafeActionKind.LEGACY_CREATE_AND_SEND, cursor.getString(3))
            assertTrue(cursor.getString(4).contains("\"menu_item_id\":\"menu-1\""))
            assertEquals("legacy:legacy-pending", cursor.getString(5))
            assertEquals(CafeActionState.PENDING, cursor.getString(6))
            assertEquals("response unknown", cursor.getString(7))

            assertTrue(cursor.moveToNext())
            assertEquals("legacy-rejected", cursor.getString(0))
            assertEquals(CafeActionState.REJECTED, cursor.getString(6))
            assertEquals("shift closed", cursor.getString(7))
            assertTrue(!cursor.moveToNext())
        }
        migrated.query(
            "SELECT COUNT(*) FROM local_table_orders WHERE state IN ('pending', 'rejected')",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        for (table in listOf("cafe_bill_cache", "local_kitchen_cancellation_acks")) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }

        // The v23 close guard remains, and v24 adds the same atomic refusal
        // for a late table action whose shift is carried by its bill header.
        migrated.execSQL(
            "INSERT INTO local_shifts " +
                "(localId, serverShiftId, terminalId, branchId, openingFloatMinor, " +
                "openedAtMillis, state) VALUES " +
                "('shift-closing-v24', 'server-shift-v24', 'terminal-1', 'branch-1', " +
                "5000, 1000, 'close_pending')",
        )
        migrated.execSQL(
            "INSERT INTO local_cafe_bills " +
                "(localBillId, tableId, tableCode, shiftId, localStatus, createdAtMillis) " +
                "VALUES ('late-bill', 'table-3', 'T3', 'shift-closing-v24', 'open', 3000)",
        )
        var lateRoundBlocked = false
        try {
            migrated.execSQL(
                "INSERT INTO local_cafe_actions " +
                    "(actionId, localBillId, sequence, kind, payload, dedupeKey, " +
                    "createdAtMillis, state) VALUES " +
                    "('late-round', 'late-bill', 1, 'create_round', '{}', " +
                    "'late-round-key', 3000, 'pending')",
            )
        } catch (e: SQLiteConstraintException) {
            lateRoundBlocked = e.message.orEmpty().contains(SHIFT_CLOSING_WRITE_GUARD)
        }
        assertTrue(lateRoundBlocked)
        migrated.close()
    }

    @Test
    fun migrate24To25_preservesCoreShiftHistoryAndInvalidatesUnprovenBreakdown() {
        helper.createDatabase(dbName, 24).apply {
            // These non-zero v24 values could be authoritative, but v24 also
            // wrote DTO defaults for legacy responses. The migration cannot
            // distinguish provenance, so only the stable core is preserved.
            execSQL(
                "INSERT INTO shift_history_cache (" +
                    "id, branchId, terminalId, status, openedAtMillis, closedAtMillis, " +
                    "openingFloatMinor, expectedMinor, countedMinor, varianceMinor, " +
                    "posSalesMinor, membershipSalesMinor, grossCollectionsMinor, " +
                    "settledPosRefundsMinor, settledMembershipRefundsMinor, " +
                    "totalRefundsMinor, netCollectionsMinor, openedByUserId, " +
                    "openedByName, openedByEmail, fetchedAtMillis) VALUES (" +
                    "'shift-1', 'branch-1', 'terminal-1', 'closed', 1000, 2000, " +
                    "50000, 90000, 90000, 0, 83600, 10000, 93600, 3600, 1000, 4600, " +
                    "89000, 'user-1', 'Rafi', 'rafi@example.com', 3000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 25, true, MIGRATION_24_25)

        migrated.query(
            "SELECT id, branchId, terminalId, status, openedAtMillis, closedAtMillis, " +
                "openingFloatMinor, expectedMinor, countedMinor, varianceMinor, posSalesMinor, " +
                "openedByUserId, openedByName, openedByEmail, fetchedAtMillis " +
                "FROM shift_history_cache WHERE id = 'shift-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("shift-1", cursor.getString(0))
            assertEquals("branch-1", cursor.getString(1))
            assertEquals("terminal-1", cursor.getString(2))
            assertEquals("closed", cursor.getString(3))
            assertEquals(1000L, cursor.getLong(4))
            assertEquals(2000L, cursor.getLong(5))
            assertEquals(50000L, cursor.getLong(6))
            assertEquals(90000L, cursor.getLong(7))
            assertEquals(90000L, cursor.getLong(8))
            assertEquals(0L, cursor.getLong(9))
            assertEquals(83600L, cursor.getLong(10))
            assertEquals("user-1", cursor.getString(11))
            assertEquals("Rafi", cursor.getString(12))
            assertEquals("rafi@example.com", cursor.getString(13))
            assertEquals(3000L, cursor.getLong(14))
        }
        migrated.query(
            "SELECT membershipSalesMinor, grossCollectionsMinor, settledPosRefundsMinor, " +
                "settledMembershipRefundsMinor, totalRefundsMinor, netCollectionsMinor " +
                "FROM shift_history_cache WHERE id = 'shift-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            for (column in 0..5) assertTrue(cursor.isNull(column))
        }
        migrated.close()
    }

    @Test
    fun migrate25To26_suppressesHistoricalCloseAndPreservesUnresolvedCloseReceipt() {
        helper.createDatabase(dbName, 25).apply {
            execSQL(
                "INSERT INTO local_shifts " +
                    "(localId, serverShiftId, terminalId, branchId, openingFloatMinor, " +
                    "openedAtMillis, state, countedMinor, closedAtMillis, varianceMinor, lastError) VALUES " +
                    "('historical', 'server-old', 'terminal-1', 'branch-1', 5000, " +
                    "1000, 'closed', 5000, 2000, 0, NULL), " +
                    "('pending-close', 'server-pending', 'terminal-1', 'branch-1', 5000, " +
                    "3000, 'close_pending', 5000, 4000, NULL, NULL), " +
                    "('rejected-close', 'server-rejected', 'terminal-1', 'branch-1', 5000, " +
                    "5000, 'close_rejected', 5000, 6000, NULL, 'unfinished work')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 26, true, MIGRATION_25_26)

        migrated.query(
            "SELECT localId, closeResultPending FROM local_shifts ORDER BY openedAtMillis",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("historical", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertTrue(cursor.moveToNext())
            assertEquals("pending-close", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertTrue(cursor.moveToNext())
            assertEquals("rejected-close", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
        migrated.close()
    }

    @Test
    fun migrate26To27_preservesGamingRowsAndAddsLockedBillingOwnershipSnapshots() {
        helper.createDatabase(dbName, 26).apply {
            execSQL(
                "INSERT INTO gaming_session_cache " +
                    "(id, stationId, status, startAtMillis, endAtMillis, timerMinutes, " +
                    "timerEndsAtMillis, billableMinutes, amountMinor, customerName, customerPhone, orderId) " +
                    "VALUES ('server-session', 'station-1', 'ended', 1000, 2000, 30, " +
                    "31000, 30, 7500, 'Guest', '9999999999', NULL)",
            )
            execSQL(
                "INSERT INTO local_gaming_sessions " +
                    "(localId, serverId, stationId, shiftId, customerPhone, timerMinutes, " +
                    "startedAtMillis, state, status, endAtMillis, timerEndsAtMillis, " +
                    "billableMinutes, amountMinor, orderId, lastError) " +
                    "VALUES ('local-session', 'server-session', 'station-1', 'shift-1', " +
                    "'9999999999', 30, 1000, 'ended_unbilled', 'ended', 2000, 31000, " +
                    "30, 7500, NULL, NULL)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 27, true, MIGRATION_26_27)

        migrated.query(
            "SELECT id, shiftId, ratePerHourMinor, packageId, extraControllers, amountMinor " +
                "FROM gaming_session_cache WHERE id = 'server-session'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("server-session", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertEquals(0, cursor.getInt(4))
            assertEquals(7500L, cursor.getLong(5))
        }
        migrated.query(
            "SELECT localId, ratePerHourMinor, packageId, extraControllers, amountMinor " +
                "FROM local_gaming_sessions WHERE localId = 'local-session'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("local-session", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals(7500L, cursor.getLong(4))
        }
        migrated.query("SELECT COUNT(*) FROM gaming_package_cache").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate27To28_preservesSessionsAndAddsImmutablePackageExtensionOutbox() {
        helper.createDatabase(dbName, 27).apply {
            execSQL(
                "INSERT INTO gaming_package_cache " +
                    "(id, stationType, variant, kind, name, durationMinutes, priceMinor) " +
                    "VALUES ('base-60', 'ps5', 'solo', 'base', 'Solo 60 min', 60, 15000)",
            )
            execSQL(
                "INSERT INTO local_gaming_sessions " +
                    "(localId, serverId, stationId, shiftId, customerPhone, timerMinutes, " +
                    "startedAtMillis, state, status, endAtMillis, timerEndsAtMillis, " +
                    "billableMinutes, amountMinor, ratePerHourMinor, packageId, " +
                    "extraControllers, orderId, lastError) VALUES " +
                    "('local-session', 'server-session', 'station-1', 'shift-1', " +
                    "'9999999999', 60, 1000, 'start_synced', 'active', NULL, 61000, " +
                    "NULL, 15000, 15000, 'base-60', 1, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO local_gaming_sessions " +
                    "(localId, serverId, stationId, shiftId, timerMinutes, startedAtMillis, " +
                    "state, status, endAtMillis, packageId, extraControllers) VALUES " +
                    "('missing-package', NULL, 'station-2', 'shift-1', 45, 1500, " +
                    "'stop_pending', 'stopping', 2500, 'base-60', 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 28, true, MIGRATION_27_28)

        migrated.query(
            "SELECT localId, packagePriceMinor, packageDurationMinutes, packageVariant, " +
                "amountMinor, packageId, billingMode, packageStationTypeSnapshot " +
                "FROM local_gaming_sessions WHERE localId = 'local-session'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("local-session", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertEquals(15000L, cursor.getLong(4))
            assertEquals("base-60", cursor.getString(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))
        }
        migrated.query(
            "SELECT billingMode, packagePriceMinorSnapshot, packageDurationMinutesSnapshot, " +
                "packageVariantSnapshot, packageStationTypeSnapshot FROM gaming_session_cache",
        ).use { cursor ->
            // This fixture has no server session row, but schema validation
            // above proves all five authoritative snapshot columns exist.
            assertTrue(!cursor.moveToFirst())
        }
        migrated.query(
            "SELECT packagePriceMinor, packageDurationMinutes, packageVariant, state, status, " +
                "endAtMillis, lastError, legacyResolution, legacyResolutionReason, " +
                "legacyResolutionReferenceOrderId, legacyResolutionAttemptState, " +
                "legacyResolutionError, legacyResolutionCapturedAtMillis, legacyResolvedAtMillis, " +
                "legacyResolvedByUserId, legacyResolutionReceiptId, " +
                "legacyOriginalCapturedStartAtMillis, legacyOriginalCapturedStopAtMillis " +
                "FROM local_gaming_sessions WHERE localId = 'missing-package'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertEquals(GamingSessionState.START_REJECTED, cursor.getString(3))
            assertEquals("start_failed", cursor.getString(4))
            assertEquals(2500L, cursor.getLong(5))
            assertEquals(LEGACY_PACKAGE_START_REVIEW_ERROR, cursor.getString(6))
            for (column in 7..15) assertTrue(cursor.isNull(column))
            assertEquals(1500L, cursor.getLong(16))
            assertEquals(2500L, cursor.getLong(17))
        }

        migrated.execSQL(
            "INSERT INTO local_gaming_package_extensions " +
                "(actionId, serverSessionId, localSessionId, shiftId, packageId, " +
                "expectedPackagePriceMinor, expectedPackageDurationMinutes, " +
                "expectedPackageVariant, expectedSessionTimerMinutes, " +
                "expectedSessionAmountMinor, createdAtMillis, state, lastError, " +
                "resolvedAtMillis, resolutionReason) VALUES " +
                "('11111111-1111-4111-8111-111111111111', 'server-session', " +
                "'local-session', 'shift-1', 'extend-30', 7500, 30, 'solo', " +
                "60, 15000, 2000, 'ambiguous', 'response unknown', NULL, NULL)",
        )
        migrated.query(
            "SELECT actionId, expectedPackagePriceMinor, expectedPackageDurationMinutes, " +
                "expectedPackageVariant, expectedSessionTimerMinutes, " +
                "expectedSessionAmountMinor, state, lastError, resolvedAtMillis, resolutionReason " +
                "FROM local_gaming_package_extensions",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("11111111-1111-4111-8111-111111111111", cursor.getString(0))
            assertEquals(7500L, cursor.getLong(1))
            assertEquals(30, cursor.getInt(2))
            assertEquals("solo", cursor.getString(3))
            assertEquals(60, cursor.getInt(4))
            assertEquals(15000L, cursor.getLong(5))
            assertEquals(GamingPackageExtensionState.AMBIGUOUS, cursor.getString(6))
            assertEquals("response unknown", cursor.getString(7))
            assertTrue(cursor.isNull(8))
            assertTrue(cursor.isNull(9))
        }
        migrated.close()
    }

    @Test
    fun migrate28To29_preservesCapturedSalesAndAddsRecoverablePosDraftSchema() {
        helper.createDatabase(dbName, 28).apply {
            execSQL(
                "INSERT INTO local_orders " +
                    "(localId, shiftId, type, customerName, estimateMinor, paymentMethod, " +
                    "tenderedMinor, tipMinor, createdAtMillis, syncState) VALUES " +
                    "('sale-1', 'shift-1', 'dine_in', 'Guest', 12500, 'cash', " +
                    "20000, 0, 1234, 'pending')",
            )
            execSQL(
                "INSERT INTO local_order_lines " +
                    "(orderLocalId, menuItemId, name, qty, unitPriceMinor) VALUES " +
                    "('sale-1', 'item-1', 'Cold coffee', 1, 12500)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 29, true, MIGRATION_28_29)

        migrated.query(
            "SELECT localId, syncState, estimateMinor, manualDiscountMinor, updatedAtMillis, " +
                "serverSubtotalMinor, checkoutClaimToken, discountRequestVersion, revision, " +
                "capturedAmountMinor FROM local_orders WHERE localId = 'sale-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("sale-1", cursor.getString(0))
            assertEquals(SyncState.PENDING, cursor.getString(1))
            assertEquals(12500L, cursor.getLong(2))
            assertEquals(0L, cursor.getLong(3))
            assertEquals(1234L, cursor.getLong(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))
            assertEquals(0L, cursor.getLong(8))
            assertTrue(cursor.isNull(9))
        }
        migrated.query(
            "SELECT clientLineId, variantId, variantPriceDeltaMinor, modifierSelectionsJson, note " +
                "FROM local_order_lines WHERE orderLocalId = 'sale-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertEquals(0L, cursor.getLong(2))
            assertEquals("[]", cursor.getString(3))
            assertTrue(cursor.isNull(4))
        }
        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND " +
                "name IN ('menu_variants', 'menu_modifiers') ORDER BY name",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("menu_modifiers", cursor.getString(0))
            assertTrue(cursor.moveToNext())
            assertEquals("menu_variants", cursor.getString(0))
            assertTrue(!cursor.moveToNext())
        }

        migrated.execSQL(
            "INSERT INTO local_shifts " +
                "(localId, serverShiftId, terminalId, branchId, openingFloatMinor, " +
                "openedAtMillis, state, closeResultPending) VALUES " +
                "('closing-shift', 'server-shift', 'terminal-1', 'branch-1', 0, 2000, " +
                "'close_pending', 0)",
        )
        var draftBlocked = false
        try {
            migrated.execSQL(
                "INSERT INTO local_orders " +
                    "(localId, shiftId, type, estimateMinor, paymentMethod, tenderedMinor, " +
                    "tipMinor, createdAtMillis, updatedAtMillis, syncState, manualDiscountMinor) VALUES " +
                    "('late-draft', 'closing-shift', 'dine_in', 1000, '', 0, 0, 2001, 2001, 'draft', 0)",
            )
        } catch (e: SQLiteConstraintException) {
            draftBlocked = e.message.orEmpty().contains(SHIFT_CLOSING_WRITE_GUARD)
        }
        assertTrue(draftBlocked)
        migrated.close()
    }

    @Test
    fun migrate29To30_preservesSalesAndAddsDurableReceiptHistory() {
        helper.createDatabase(dbName, 29).apply {
            execSQL(
                "INSERT INTO local_orders " +
                    "(localId, shiftId, type, estimateMinor, paymentMethod, tenderedMinor, " +
                    "tipMinor, createdAtMillis, updatedAtMillis, syncState, manualDiscountMinor, revision) " +
                    "VALUES ('sale-before-receipts', 'shift-1', 'dine_in', 12500, 'cash', " +
                    "20000, 0, 1234, 1234, 'synced', 0, 4)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 30, true, MIGRATION_29_30)
        migrated.query(
            "SELECT syncState, revision FROM local_orders WHERE localId = 'sale-before-receipts'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(SyncState.SYNCED, cursor.getString(0))
            assertEquals(4L, cursor.getLong(1))
        }
        migrated.query("SELECT COUNT(*) FROM pos_receipts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate30To31_preservesShiftCachesAndAddsPaymentRailBreakdown() {
        helper.createDatabase(dbName, 30).apply {
            execSQL(
                "INSERT INTO server_open_shift_cache " +
                    "(terminalId, serverShiftId, branchId, status, openingFloatMinor, " +
                    "expectedMinor, posCollectionsMinor, membershipCollectionsMinor, " +
                    "grossCollectionsMinor, openedAtMillis, verifiedAtMillis) VALUES " +
                    "('terminal-1', 'shift-open', 'branch-1', 'open', 21000, 98300, " +
                    "136300, 0, 136300, 1000, 2000)",
            )
            execSQL(
                "INSERT INTO shift_history_cache " +
                    "(id, branchId, terminalId, status, openedAtMillis, openingFloatMinor, " +
                    "posSalesMinor, fetchedAtMillis) VALUES " +
                    "('shift-old', 'branch-1', 'terminal-1', 'closed', 500, 10000, 25000, 2000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 31, true, MIGRATION_30_31)
        migrated.query(
            "SELECT grossCollectionsMinor, cashCollectionsMinor, cardCollectionsMinor, " +
                "upiCollectionsMinor, otherCollectionsMinor FROM server_open_shift_cache " +
                "WHERE serverShiftId = 'shift-open'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(136300L, cursor.getLong(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }
        migrated.query(
            "SELECT posSalesMinor, cashCollectionsMinor, cardCollectionsMinor, " +
                "upiCollectionsMinor, otherCollectionsMinor FROM shift_history_cache " +
                "WHERE id = 'shift-old'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(25000L, cursor.getLong(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }
        migrated.close()
    }

    @Test
    fun migrate31To32_preservesPreparedSaleAndAddsPointsAndCustomerHistory() {
        helper.createDatabase(dbName, 31).apply {
            execSQL(
                "INSERT INTO local_orders " +
                    "(localId, serverOrderId, shiftId, type, estimateMinor, serverTotalMinor, " +
                    "checkoutClaimToken, checkoutVersion, paymentMethod, tenderedMinor, tipMinor, " +
                    "createdAtMillis, updatedAtMillis, syncState, manualDiscountMinor, revision) " +
                    "VALUES ('prepared-sale', 'server-order', 'shift-1', 'dine_in', 15000, 15000, " +
                    "'claim-1', 7, '', 0, 0, 1000, 2000, 'draft', 0, 3)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 32, true, MIGRATION_31_32)
        migrated.query(
            "SELECT serverOrderId, serverTotalMinor, checkoutClaimToken, checkoutVersion, " +
                "serverPointsRedeemedMinor, serverPointsRedeemed FROM local_orders " +
                "WHERE localId = 'prepared-sale'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("server-order", cursor.getString(0))
            assertEquals(15000L, cursor.getLong(1))
            assertEquals("claim-1", cursor.getString(2))
            assertEquals(7L, cursor.getLong(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
        }
        migrated.query("SELECT COUNT(*) FROM customer_order_history_cache").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.execSQL(
            "INSERT INTO customer_order_history_cache " +
                "(id, customerId, status, type, totalMinor, paidMinor, refundedMinor, " +
                "pointsRedeemedMinor, itemsCount, paymentMethods, createdAt) VALUES " +
                "('order-1', 'customer-1', 'paid', 'dine_in', 14800, 14800, 0, 200, 1, " +
                "'upi', '2026-08-27T19:00:00Z')",
        )
        migrated.query(
            "SELECT customerId, pointsRedeemedMinor FROM customer_order_history_cache " +
                "WHERE id = 'order-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("customer-1", cursor.getString(0))
            assertEquals(200L, cursor.getLong(1))
        }
        migrated.close()
    }

    @Test
    fun migrate32To33_preservesInventoryAndFreezesOfflineGrnProvenance() {
        helper.createDatabase(dbName, 32).apply {
            execSQL(
                "INSERT INTO ingredient_cache " +
                    "(id, sku, name, baseUnit, currentQty, reorderThreshold, reorderQty, avgCostMinor) " +
                    "VALUES ('ingredient-1', 'MILK', 'Milk', 'ml', 2500, 500, 1000, 12)",
            )
            execSQL(
                "INSERT INTO batch_cache " +
                    "(id, ingredientId, receivedAt, qtyOnHand, costPerUnitMinor, lotCode) " +
                    "VALUES ('batch-1', 'ingredient-1', '2026-08-27T09:00:00Z', 2500, 12, 'LOT-1')",
            )
            execSQL(
                "INSERT INTO local_grns " +
                    "(localId, branchId, supplierId, supplierInvoiceNo, notes, createdAtMillis, syncState) " +
                    "VALUES ('grn-1', 'branch-1', 'supplier-1', 'INV-1', 'offline', " +
                    "1787821200000, 'pending')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 33, true, MIGRATION_32_33)
        migrated.query(
            "SELECT currentQty, avgCostMinor, valuationMinor, projectionBranchId " +
                "FROM ingredient_cache WHERE id = 'ingredient-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2500.0, cursor.getDouble(0), 0.0)
            assertEquals(12L, cursor.getLong(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
        }
        migrated.query(
            "SELECT ingredientId, branchId, qtyOnHand FROM batch_cache WHERE id = 'batch-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ingredient-1", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertEquals(2500.0, cursor.getDouble(2), 0.0)
        }
        migrated.query(
            "SELECT supplierInvoiceNo, supplierInvoiceAmountMinor, receivedAt, syncState " +
                "FROM local_grns WHERE localId = 'grn-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("INV-1", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.getString(2).startsWith("2026-"))
            assertEquals(SyncState.PENDING, cursor.getString(3))
        }
        migrated.close()
    }

    @Test
    fun migrate33To34_preservesBranchesAndOnlyRecoversExactLegacyInvoiceSeries() {
        helper.createDatabase(dbName, 33).apply {
            execSQL(
                "INSERT INTO branch_cache (id, name, code) VALUES " +
                    "('branch-exact', 'Second Floor', ' f2 '), " +
                    "('branch-ambiguous', 'Main North', 'Main-North')",
            )
            execSQL(
                "INSERT INTO local_branches " +
                    "(localId, name, code, createdAtMillis, syncState) VALUES " +
                    "('queued-exact', 'Kiosk', ' k1 ', 1000, 'pending'), " +
                    "('queued-ambiguous', 'Terrace', 'Terrace', 2000, 'pending')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 34, true, MIGRATION_33_34)
        migrated.query(
            "SELECT id, invoiceSeriesCode FROM branch_cache ORDER BY id",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("branch-ambiguous", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.moveToNext())
            assertEquals("branch-exact", cursor.getString(0))
            assertEquals("F2", cursor.getString(1))
        }
        migrated.query(
            "SELECT localId, invoiceSeriesCode, syncState FROM local_branches ORDER BY localId",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("queued-ambiguous", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertEquals(SyncState.PENDING, cursor.getString(2))
            assertTrue(cursor.moveToNext())
            assertEquals("queued-exact", cursor.getString(0))
            assertEquals("K1", cursor.getString(1))
            assertEquals(SyncState.PENDING, cursor.getString(2))
        }
        migrated.close()
    }

    @Test
    fun migrate34To35_preservesRefundMoneyAndLeavesLegacyEvidenceUnknown() {
        helper.createDatabase(dbName, 34).apply {
            execSQL(
                "INSERT INTO refund_order_cache " +
                    "(id, invoiceNo, status, type, totalMinor, paidMinor, refundableMinor) VALUES " +
                    "('order-1', 'INV-1', 'paid', 'pos', 20000, 20000, 12500)",
            )
            execSQL(
                "INSERT INTO local_refunds " +
                    "(localId, clientActionId, orderId, reasonCode, amountMinor, createdAtMillis, state, " +
                    "settlementMethod, providerSettledAtMillis, externalReference, withdrawalAtMillis) VALUES " +
                    "('refund-1', 'refund-action-1', 'order-1', 'billing_error', 7500, 1000, " +
                    "'settled', 'cash', NULL, NULL, NULL), " +
                    "('refund-conflict', 'refund-action-conflict', 'order-2', 'billing_error', " +
                    "5000, 1100, 'withdrawn', 'upi', 1200, 'UPI-LEGACY-REF', 1300)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 35, true, MIGRATION_34_35)
        migrated.query(
            "SELECT paidMinor, refundableMinor, pendingRefundMinor, paymentMethodsCsv " +
                "FROM refund_order_cache WHERE id = 'order-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(20_000L, cursor.getLong(0))
            assertEquals(12_500L, cursor.getLong(1))
            assertEquals(0L, cursor.getLong(2))
            assertEquals("", cursor.getString(3))
        }
        migrated.query(
            "SELECT amountMinor, state, acceptedByUserId, capturedTimeReconciled, " +
                "loyaltyReconciliationState, payoutConflict FROM local_refunds WHERE localId = 'refund-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7_500L, cursor.getLong(0))
            assertEquals(RefundState.SETTLED, cursor.getString(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertEquals(0, cursor.getInt(5))
        }
        migrated.query(
            "SELECT state, providerSettledAtMillis, externalReference, withdrawalAtMillis, " +
                "payoutConflict FROM local_refunds WHERE localId = 'refund-conflict'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(RefundState.WITHDRAWN, cursor.getString(0))
            assertEquals(1_200L, cursor.getLong(1))
            assertEquals("UPI-LEGACY-REF", cursor.getString(2))
            assertEquals(1_300L, cursor.getLong(3))
            assertEquals(1, cursor.getInt(4))
        }
        migrated.close()
    }

    @Test
    fun migrate35To36_defaultsExistingAndQueuedTerminalsToHybridWithoutNameInference() {
        helper.createDatabase(dbName, 35).apply {
            execSQL(
                "INSERT INTO terminal_cache " +
                    "(id, branchId, name, deviceId, lastSeenAt) VALUES " +
                    "('terminal-gaming-name', 'branch-1', 'Gaming Area', NULL, NULL), " +
                    "('terminal-cafe-name', 'branch-1', 'Cafe POS', NULL, NULL)",
            )
            execSQL(
                "INSERT INTO local_terminals " +
                    "(localId, branchId, name, deviceId, createdAtMillis, syncState, lastError) VALUES " +
                    "('queued-gaming-name', 'branch-1', 'Gaming Till', NULL, 1000, 'pending', NULL)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 36, true, MIGRATION_35_36)
        migrated.query(
            "SELECT name, purpose FROM terminal_cache ORDER BY id",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Cafe POS", cursor.getString(0))
            assertEquals("hybrid", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("Gaming Area", cursor.getString(0))
            assertEquals("hybrid", cursor.getString(1))
        }
        migrated.query(
            "SELECT name, purpose, syncState FROM local_terminals WHERE localId = 'queued-gaming-name'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Gaming Till", cursor.getString(0))
            assertEquals("hybrid", cursor.getString(1))
            assertEquals(SyncState.PENDING, cursor.getString(2))
        }
        migrated.close()
    }

    @Test
    fun migrate36To37_addsOwnerScopedDurableSupportReportAndAttachmentOutbox() {
        helper.createDatabase(dbName, 36).close()

        val migrated = helper.runMigrationsAndValidate(dbName, 37, true, MIGRATION_36_37)
        migrated.execSQL(
            "INSERT INTO local_bug_reports " +
                "(localId, ownerCompanyId, ownerUserId, requestJson, title, screen, " +
                "createdAtMillis, state, attemptCount) VALUES " +
                "('report-local', 'company-1', 'user-1', '{}', 'Staff needs help', " +
                "'Gaming', 1000, 'pending', 0)",
        )
        migrated.execSQL(
            "INSERT INTO local_bug_report_attachments " +
                "(localId, reportLocalId, ownerCompanyId, ownerUserId, filename, contentType, " +
                "content, byteSize, createdAtMillis, state, attemptCount) VALUES " +
                "('image-local', 'report-local', 'company-1', 'user-1', 'support-image.jpg', " +
                "'image/jpeg', X'010203', 3, 1000, 'pending', 0)",
        )
        migrated.query(
            "SELECT ownerCompanyId, ownerUserId, state FROM local_bug_reports WHERE localId = 'report-local'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("company-1", cursor.getString(0))
            assertEquals("user-1", cursor.getString(1))
            assertEquals("pending", cursor.getString(2))
        }
        migrated.query(
            "SELECT reportLocalId, contentType, byteSize FROM local_bug_report_attachments " +
                "WHERE localId = 'image-local'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("report-local", cursor.getString(0))
            assertEquals("image/jpeg", cursor.getString(1))
            assertEquals(3, cursor.getInt(2))
        }
        migrated.close()
    }

    @Test
    fun migrate37To38BindsAttachmentOwnerToParentAndQuarantinesLegacyMismatch() {
        helper.createDatabase(dbName, 37).apply {
            execSQL(
                "INSERT INTO local_bug_reports " +
                    "(localId, ownerCompanyId, ownerUserId, requestJson, title, screen, " +
                    "createdAtMillis, state, attemptCount) VALUES " +
                    "('report-local', 'company-1', 'user-1', '{}', 'Staff needs help', " +
                    "'Gaming', 1000, 'sent', 1)",
            )
            execSQL(
                "INSERT INTO local_bug_report_attachments " +
                    "(localId, reportLocalId, ownerCompanyId, ownerUserId, filename, contentType, " +
                    "content, byteSize, createdAtMillis, state, attemptCount) VALUES " +
                    "('image-local', 'report-local', 'company-1', 'user-2', 'support-image.jpg', " +
                    "'image/jpeg', X'010203', 3, 1000, 'pending', 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 38, true, MIGRATION_37_38)
        migrated.query(
            "SELECT ownerCompanyId, ownerUserId, state, lastError, length(content) " +
                "FROM local_bug_report_attachments WHERE localId = 'image-local'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("company-1", cursor.getString(0))
            assertEquals("user-1", cursor.getString(1))
            assertEquals(BugReportOutboxState.DISCARDED, cursor.getString(2))
            assertTrue(cursor.getString(3).contains("privacy", ignoreCase = true))
            assertEquals(0, cursor.getInt(4))
        }
        migrated.query(
            "SELECT COUNT(*) FROM local_bug_report_attachments " +
                "WHERE localId = 'image-local' AND state = 'pending'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate36To38CreatesOwnerBoundSupportOutboxInOneReleaseUpgrade() {
        helper.createDatabase(dbName, 36).close()

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            38,
            true,
            MIGRATION_36_37,
            MIGRATION_37_38,
        )
        migrated.execSQL(
            "INSERT INTO local_bug_reports " +
                "(localId, ownerCompanyId, ownerUserId, requestJson, title, screen, " +
                "createdAtMillis, state, attemptCount) VALUES " +
                "('report-local', 'company-1', 'user-1', '{}', 'Staff needs help', " +
                "'Gaming', 1000, 'pending', 0)",
        )
        migrated.execSQL(
            "INSERT INTO local_bug_report_attachments " +
                "(localId, reportLocalId, ownerCompanyId, ownerUserId, filename, contentType, " +
                "content, byteSize, createdAtMillis, state, attemptCount) VALUES " +
                "('image-local', 'report-local', 'company-1', 'user-1', 'support-image.jpg', " +
                "'image/jpeg', X'010203', 3, 1000, 'pending', 0)",
        )
        migrated.query(
            "SELECT COUNT(*) FROM local_bug_report_attachments WHERE reportLocalId = 'report-local'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate38To39CreatesDurableGamingAddonLedgerAndShiftGuard() {
        helper.createDatabase(dbName, 38).apply {
            execSQL(
                "INSERT INTO local_shifts " +
                    "(localId, serverShiftId, terminalId, branchId, openingFloatMinor, " +
                    "openedAtMillis, openedByUserId, state, closeResultPending) VALUES " +
                    "('shift-open', 'server-shift-open', 'terminal-1', 'branch-1', 0, 1000, " +
                    "'user-1', 'open_synced', 0), " +
                    "('shift-closing', 'server-shift-closing', 'terminal-1', 'branch-1', 0, 1000, " +
                    "'user-1', 'close_pending', 1)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 39, true, MIGRATION_38_39)
        migrated.execSQL(
            "INSERT INTO gaming_session_addon_cache " +
                "(id, gamingSessionId, clientLineId, menuItemId, menuItemName, menuItemType, " +
                "modifiersJson, qty, catalogUnitPriceMinor, unitPriceMinor, lineTotalMinor, " +
                "discountMinor, taxRate, taxableValueMinor, cgstMinor, sgstMinor, igstMinor, " +
                "cessMinor, createdBy, createdTerminalId, createdAtMillis) VALUES " +
                "('addon-1', 'session-1', 'line-1', 'item-1', 'Cola', 'drink', '[]', 2, " +
                "10000, 10000, 20000, 0, 0.0, 20000, 0, 0, 0, 0, 'user-1', 'terminal-1', 1001)",
        )
        migrated.execSQL(
            "INSERT INTO local_gaming_session_addon_actions " +
                "(actionId, actionType, ownerCompanyId, ownerUserId, branchId, terminalId, " +
                "serverSessionId, shiftId, clientLineId, menuItemId, menuItemName, menuItemType, " +
                "modifierSelectionsJson, qty, expectedUnitPriceMinor, createdAtMillis, state) VALUES " +
                "('11111111-1111-1111-1111-111111111111', 'add', 'company-1', 'user-1', " +
                "'branch-1', 'terminal-1', 'session-1', 'server-shift-open', " +
                "'22222222-2222-2222-2222-222222222222', 'item-1', 'Cola', 'drink', " +
                "'[]', 2, 10000, 1002, 'pending')",
        )
        migrated.query(
            "SELECT ownerCompanyId, ownerUserId, terminalId, shiftId, clientLineId, state " +
                "FROM local_gaming_session_addon_actions",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("company-1", cursor.getString(0))
            assertEquals("user-1", cursor.getString(1))
            assertEquals("terminal-1", cursor.getString(2))
            assertEquals("server-shift-open", cursor.getString(3))
            assertEquals("22222222-2222-2222-2222-222222222222", cursor.getString(4))
            assertEquals(GamingSessionAddonActionState.PENDING, cursor.getString(5))
        }

        var closingGuarded = false
        try {
            migrated.execSQL(
                "INSERT INTO local_gaming_session_addon_actions " +
                    "(actionId, actionType, ownerCompanyId, ownerUserId, branchId, terminalId, " +
                    "serverSessionId, shiftId, clientLineId, menuItemId, menuItemName, menuItemType, " +
                    "modifierSelectionsJson, qty, expectedUnitPriceMinor, createdAtMillis, state) VALUES " +
                    "('33333333-3333-3333-3333-333333333333', 'add', 'company-1', 'user-1', " +
                    "'branch-1', 'terminal-1', 'session-2', 'server-shift-closing', " +
                    "'44444444-4444-4444-4444-444444444444', 'item-1', 'Cola', 'drink', " +
                    "'[]', 1, 10000, 1003, 'pending')",
            )
        } catch (failure: SQLiteConstraintException) {
            closingGuarded = failure.message.orEmpty().contains(SHIFT_CLOSING_WRITE_GUARD)
        }
        assertTrue("A closing shift must reject a newly queued Gaming item", closingGuarded)
        migrated.close()
    }

    @Test
    fun migrate39To40PreservesLocalEvidenceAndAddsCanonicalReceiptCache() {
        helper.createDatabase(dbName, 39).apply {
            execSQL(
                "INSERT INTO pos_receipts " +
                    "(receiptId, orderId, paymentId, shiftId, sourceKind, sourceLabel, " +
                    "customerName, customerPhone, orderNote, subtotalMinor, discountMinor, " +
                    "taxMinor, roundOffMinor, totalMinor, dueBeforePaymentMinor, method, " +
                    "amountMinor, billAmountMinor, tipMinor, tenderedMinor, changeMinor, " +
                    "refExternal, paidAt, orderStatus, invoiceNo, fiscalYear, invoiceIssuedAt, " +
                    "linesJson, createdAtMillis, acknowledgedAtMillis) VALUES " +
                    "('payment-1', 'order-1', 'payment-1', 'shift-1', 'direct_pos', 'POS', " +
                    "NULL, NULL, NULL, 10000, 0, 0, 0, 10000, 10000, 'cash', 10000, " +
                    "10000, 0, 10000, 0, NULL, '2026-08-29T10:00:00Z', 'paid', 'INV-1', " +
                    "'2026-27', '2026-08-29T10:00:00Z', '[]', 1000, NULL)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 40, true, MIGRATION_39_40)
        migrated.query("SELECT orderId FROM pos_receipts WHERE receiptId = 'payment-1'").use { cursor ->
            assertTrue("Local payment evidence must survive the cache migration", cursor.moveToFirst())
            assertEquals("order-1", cursor.getString(0))
        }
        migrated.execSQL(
            "INSERT INTO canonical_pos_receipts " +
                "(orderId, companyId, branchId, terminalId, invoiceNo, status, orderType, " +
                "totalMinor, paidMinor, invoiceIssuedAt, invoiceIssuedAtMillis, payloadJson, " +
                "fetchedAtMillis) VALUES ('order-2', 'company-1', 'branch-1', 'terminal-1', " +
                "'INV-2', 'paid', 'gaming', 12500, 12500, '2026-08-29T11:00:00Z', " +
                "2000, '{}', 3000)",
        )
        migrated.execSQL(
            "INSERT INTO canonical_receipt_sync_state " +
                "(id, nextCursor, hasMore, loadedCount, fetchedAtMillis, unavailableMessage) VALUES " +
                "(1, 'cursor-2', 1, 50, 3000, NULL)",
        )
        migrated.query(
            "SELECT companyId, branchId, totalMinor FROM canonical_pos_receipts WHERE orderId = 'order-2'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("company-1", cursor.getString(0))
            assertEquals("branch-1", cursor.getString(1))
            assertEquals(12_500L, cursor.getLong(2))
        }
        migrated.query(
            "SELECT loadedCount FROM canonical_receipt_sync_state WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(50, cursor.getInt(0))
        }
        migrated.close()
    }
}
