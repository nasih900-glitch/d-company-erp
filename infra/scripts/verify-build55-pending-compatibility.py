#!/usr/bin/env python3
"""Exact Build54/0091 -> Build55/0093 preservation admission.

Opaque tablet work is retained as reported, including current-build work. This
profile never drains queues, reclassifies old rows, edits telemetry, or grants an
Android offer. Normal CLI admission stays closed until independent source/schema
freeze and migration/restore tests have pinned this profile.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
from datetime import datetime
from pathlib import Path
from uuid import UUID

PRIOR_REVISION = "5ad146ae076f5178acc2c8e4cb840ecbb0d0343a"
PRIOR_VERSION = "3.1.46"
PRIOR_DATABASE_HEAD = "0091"
CANDIDATE_VERSION = "3.1.47"
CANDIDATE_DATABASE_HEAD = "0093"
PRIOR_SOURCE_ARCHIVE_SHA256 = (
    "7ded1fbc0afb2fb0dc93421bd6fc9b9e9ecef677e69b28ec1a4830d8252f6db5"
)
PRIOR_BACKEND_MANIFEST_SHA256 = (
    "3b82279f4bb7edad7640bcb73c7b76cbff7c25000beaf4ff988d3a33b6a7cf03"
)
PRIOR_PROTECTED_SOURCE_SHA256 = (
    "8f3c6156826a3c0e40ab9ff0f0750c7e7243b1b1648032a3ecaae19b3f77c2b3"
)
PRIOR_SCHEMA_SHA256 = "f3be2f415f6180433cd117aaa5b9e5b65a46e8122ac265bcc10521570fe6c26d"
PRIOR_DATABASE_IDENTITY = {
    "database_name": "erp",
    "database_oid": 16384,
    "system_identifier": "7695211338147233825",
}
PRIOR_SCHEMA_ROW_COUNT = 4485
PRIOR_TABLE_NAMES_SHA256 = (
    "be45aec51c716fb1f961dac032d71a7979c29ea3c58f9c497b02d26ee1db2474"
)
ALL_INSTALLATIONS_IDENTITY_SHA256 = (
    "ea5bfed358dc2a4a16a3876bb8a45daa75c40833498680e62643b44c90114664"
)
FLEET_GUARD_SHA256 = "495f0fce983228b68d3a5141f844c35c5ed1b0438dc26ff041737aba3b046f8b"
EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
SERVICES = ("backend", "caddy", "frontend", "postgres", "redis")
PRIOR_IMAGES = {
    "backend": "sha256:936db8ea9033b5eebe049a89f536c5dbe924be6cf308425fc5c1e1c40925ea74",
    "caddy": "sha256:00e645ae268a194c01682ca1704ea4719dd7add48301bcc90fe9daeff2caba20",
    "frontend": "sha256:e96276203ef3d429a5b276735f5e19344385c192b82cdb67ead0dd2a755762d8",
    "postgres": "sha256:1cda7bf6d3a9aa1f6f6d80be010bc6f04673057a2b608b7a46e0d0fa9dc07b9a",
    "redis": "sha256:dbcb980813ceb17ff0eddac4a31ce9c9ff7f5f5b5433a3f8e71be444abcc8401",
}
PRIOR_ENVIRONMENT_SHA256 = {
    "backend": "3330cc914ab7814b3b503dbd538b26b9d38752810f56430ce811bdc42eaaf91b",
    "caddy": "d5dc922cb1819841942b32f4c784da0962b34bcea4ff259e0d72fb9711bd1c71",
    "frontend": "267b46eeea2aaaf7f05825c1430da29a350a5b6bcec9959ac8027bfd53ef7b9b",
    "postgres": "83ad37062c1cd053b7a54dae2235dd99a1c57cb784a0addf080b256b2d50fb3a",
    "redis": "917b30d24b4f5abfc05346a032f859ab3a7c5cb9b77f34f4aed8c9b2949ab8b4",
}
PRIOR_COMMAND_SHA256 = {
    "backend": "98abce658d9d19c71c7523522cdfad41498a7c65df43a81864696b7c982990ce",
    "caddy": "a3c23ab06fbf190ea08f12d5786aac735394681be159c9db95d6be56625daab4",
    "frontend": "38a49158f19383d63a7deccb19bc93105a87393a9f8f7fb9e728ae6c5fd4d587",
    "redis": "cae550842eb117a31b10306bf83978957c939e643f0853b7c7fead1ba08a71b9",
    "postgres": "39baf63151d31de5015432df0a9b277c2a6948d4749ea0353349b9ac19e8e012",
}
PRIOR_COMPOSE_SHA256 = (
    "4f999db66a4acbbf2306fbecc7640794f600ee85055923dca2ca0eabbb7d9bc9"
)
PRIOR_OPERATIONAL_OVERRIDE_SHA256 = (
    "8efb4fbb75d87136e7425ff0277455c972148bfc6a38a8ceecd8db20a7c9fff4"
)
BACKEND_UVICORN_ENTRYPOINT = [
    "/usr/local/bin/python3.14",
    "/usr/local/bin/uvicorn",
    "app.main:app",
    "--host",
    "0.0.0.0",
    "--port",
    "8000",
    "--proxy-headers",
]
# Independent review fills these final closure values; the helper cannot hash
# its own enclosing archive. Historical release maps remain immutable.
CANDIDATE_RELEASE_FROZEN = True
CANDIDATE_BACKEND_MANIFEST_SHA256: str | None = '2c9e7ee728f511ee73371118421572ac7b0a1fb68e95ccf18b63a5c41dbc15a4'
CANDIDATE_PROTECTED_SOURCE_SHA256: str | None = '2ed9d023c601ad044bf980702c85336838c767ade2463ef9d9454e6e46692280'
EXPECTED_POLICY_SCHEMA_ROWS: tuple[str, ...] = (
    "column|android_update_policy|1|id|integer|NO||||32|2|0||||||||||pg_catalog|int4|NO||||||NO|NEVER||YES",
    "column|android_update_policy|2|mode|character varying|NO||20|80|||||||||||||pg_catalog|varchar|NO||||||NO|NEVER||YES",
    "column|android_update_policy|3|revision|integer|NO||||32|2|0||||||||||pg_catalog|int4|NO||||||NO|NEVER||YES",
    "column|android_update_policy|4|release_id|uuid|YES||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|android_update_policy|5|release_fingerprint|character varying|YES||64|256|||||||||||||pg_catalog|varchar|NO||||||NO|NEVER||YES",
    "column|android_update_policy|6|reason|character varying|NO||500|2000|||||||||||||pg_catalog|varchar|NO||||||NO|NEVER||YES",
    "column|android_update_policy|7|updated_by|uuid|NO||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|android_update_policy|8|created_at|timestamp with time zone|NO|now()||||||6|||||||||pg_catalog|timestamptz|NO||||||NO|NEVER||YES",
    "column|android_update_policy|9|updated_at|timestamp with time zone|NO|now()||||||6|||||||||pg_catalog|timestamptz|NO||||||NO|NEVER||YES",
    "constraint|android_update_policy|android_update_policy_id_check|CHECK (id = 1)",
    "constraint|android_update_policy|android_update_policy_mode_check|CHECK (mode::text = ANY (ARRAY['optional'::character varying, 'required'::character varying]::text[]))",
    "constraint|android_update_policy|android_update_policy_pkey|PRIMARY KEY (id)",
    "constraint|android_update_policy|android_update_policy_reason_check|CHECK (length(TRIM(BOTH FROM reason)) >= 3 AND length(TRIM(BOTH FROM reason)) <= 500)",
    "constraint|android_update_policy|android_update_policy_release_id_fkey|FOREIGN KEY (release_id) REFERENCES android_releases(id) ON DELETE RESTRICT",
    "constraint|android_update_policy|android_update_policy_revision_check|CHECK (revision >= 1 AND revision <= 2147483647)",
    "constraint|android_update_policy|android_update_policy_updated_by_fkey|FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE RESTRICT",
    "constraint|android_update_policy|ck_android_update_policy_target|CHECK (mode::text = 'optional'::text AND release_id IS NULL AND release_fingerprint IS NULL OR mode::text = 'required'::text AND release_id IS NOT NULL AND release_fingerprint IS NOT NULL AND release_fingerprint::text ~ '^[a-f0-9]{64}$'::text)",
    "index|android_update_policy|android_update_policy_pkey|CREATE UNIQUE INDEX android_update_policy_pkey ON public.android_update_policy USING btree (id)",
)
# Exact catalogue changes from the independently reviewed PostgreSQL16 rehearsal.
# Source closure admission remains closed until its separate final freeze.
EXPECTED_0093_SCHEMA_ADDED_ROWS: tuple[str, ...] = (
    "column|gaming_sessions|51|retained_addon_order_id|uuid|YES||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_addon_lines|1|id|uuid|NO||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_addon_lines|2|receipt_id|uuid|NO||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_addon_lines|3|addon_id|uuid|NO||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_addon_lines|4|order_line_id|uuid|NO||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_addon_lines|5|source_order_id|uuid|YES||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_addon_lines|6|retained_order_id|uuid|NO||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|10|charge_taxable_value_minor|bigint|YES||||64|2|0||||||||||pg_catalog|int8|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|11|charge_cgst_minor|bigint|YES||||64|2|0||||||||||pg_catalog|int8|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|12|charge_sgst_minor|bigint|YES||||64|2|0||||||||||pg_catalog|int8|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|13|charge_igst_minor|bigint|YES||||64|2|0||||||||||pg_catalog|int8|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|14|charge_cess_minor|bigint|YES||||64|2|0||||||||||pg_catalog|int8|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|15|actor_user_id|uuid|NO||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|16|terminal_id|uuid|NO||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|17|mode|character varying|NO||24|96|||||||||||||pg_catalog|varchar|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|18|original_session_status|character varying|NO||20|80|||||||||||||pg_catalog|varchar|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|19|original_amount_minor|bigint|NO||||64|2|0||||||||||pg_catalog|int8|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|1|id|uuid|NO||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|20|session_billing_revision|integer|NO||||32|2|0||||||||||pg_catalog|int4|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|21|session_participant_revision|integer|NO||||32|2|0||||||||||pg_catalog|int4|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|22|source_order_status|character varying|YES||20|80|||||||||||||pg_catalog|varchar|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|23|source_checkout_version|integer|YES||||32|2|0||||||||||pg_catalog|int4|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|24|source_total_minor|bigint|YES||||64|2|0||||||||||pg_catalog|int8|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|25|retained_total_minor|bigint|NO||||64|2|0||||||||||pg_catalog|int8|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|26|addon_count|integer|NO||||32|2|0||||||||||pg_catalog|int4|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|27|reason|character varying|NO||500|2000|||||||||||||pg_catalog|varchar|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|28|idempotency_key|character varying|NO||160|640|||||||||||||pg_catalog|varchar|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|29|request_hash|character varying|NO||64|256|||||||||||||pg_catalog|varchar|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|2|company_id|uuid|NO||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|30|request_payload|jsonb|NO||||||||||||||||pg_catalog|jsonb|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|31|response_payload|jsonb|NO||||||||||||||||pg_catalog|jsonb|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|32|voided_at|timestamp with time zone|NO|||||||6|||||||||pg_catalog|timestamptz|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|3|gaming_session_id|uuid|NO||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|4|source_order_id|uuid|YES||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|5|retained_order_id|uuid|YES||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|6|gaming_charge_line_id|uuid|YES||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|7|charge_unit_price_minor|bigint|YES||||64|2|0||||||||||pg_catalog|int8|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|8|charge_line_total_minor|bigint|YES||||64|2|0||||||||||pg_catalog|int8|NO||||||NO|NEVER||YES",
    "column|gaming_void_receipts|9|charge_discount_minor|bigint|YES||||64|2|0||||||||||pg_catalog|int8|NO||||||NO|NEVER||YES",
    "constraint|gaming_sessions|gaming_sessions_retained_addon_order_id_fkey|FOREIGN KEY (retained_addon_order_id) REFERENCES orders(id) ON DELETE RESTRICT",
    "constraint|gaming_sessions|trg_gaming_sessions_validate_order_unlink|TRIGGER DEFERRABLE INITIALLY DEFERRED",
    "constraint|gaming_sessions|trg_gaming_sessions_validate_void|TRIGGER DEFERRABLE INITIALLY DEFERRED",
    "constraint|gaming_void_addon_lines|ck_gaming_void_addon_line_same_linked_order|CHECK (source_order_id IS NULL OR source_order_id = retained_order_id)",
    "constraint|gaming_void_addon_lines|gaming_void_addon_lines_addon_id_fkey|FOREIGN KEY (addon_id) REFERENCES gaming_session_addons(id) ON DELETE RESTRICT",
    "constraint|gaming_void_addon_lines|gaming_void_addon_lines_order_line_id_fkey|FOREIGN KEY (order_line_id) REFERENCES order_lines(id) ON DELETE RESTRICT",
    "constraint|gaming_void_addon_lines|gaming_void_addon_lines_pkey|PRIMARY KEY (id)",
    "constraint|gaming_void_addon_lines|gaming_void_addon_lines_receipt_id_fkey|FOREIGN KEY (receipt_id) REFERENCES gaming_void_receipts(id) ON DELETE RESTRICT",
    "constraint|gaming_void_addon_lines|gaming_void_addon_lines_retained_order_id_fkey|FOREIGN KEY (retained_order_id) REFERENCES orders(id) ON DELETE RESTRICT",
    "constraint|gaming_void_addon_lines|gaming_void_addon_lines_source_order_id_fkey|FOREIGN KEY (source_order_id) REFERENCES orders(id) ON DELETE RESTRICT",
    "constraint|gaming_void_addon_lines|trg_gaming_void_addon_lines_validate|TRIGGER DEFERRABLE INITIALLY DEFERRED",
    "constraint|gaming_void_addon_lines|uq_gaming_void_addon_line_addon|UNIQUE (addon_id)",
    "constraint|gaming_void_addon_lines|uq_gaming_void_addon_line_order_line|UNIQUE (order_line_id)",
    "constraint|gaming_void_receipts|ck_gaming_void_receipt_amounts|CHECK (original_amount_minor >= 0 AND retained_total_minor >= 0 AND addon_count >= 0 AND session_billing_revision >= 0 AND session_participant_revision >= 0)",
    "constraint|gaming_void_receipts|ck_gaming_void_receipt_identity|CHECK (length(TRIM(BOTH FROM reason)) >= 1 AND length(TRIM(BOTH FROM reason)) <= 500 AND length(TRIM(BOTH FROM idempotency_key)) > 0 AND request_hash::text ~ '^[0-9a-f]{64}$'::text)",
    "constraint|gaming_void_receipts|ck_gaming_void_receipt_mode_scope|CHECK (mode::text = 'unlinked_addons'::text AND source_order_id IS NULL AND addon_count > 0 AND retained_order_id IS NOT NULL OR mode::text = 'linked_pos'::text AND source_order_id IS NOT NULL)",
    "constraint|gaming_void_receipts|ck_gaming_void_receipt_mode|CHECK (mode::text = ANY (ARRAY['unlinked_addons'::character varying, 'linked_pos'::character varying]::text[]))",
    "constraint|gaming_void_receipts|ck_gaming_void_receipt_payloads|CHECK (jsonb_typeof(request_payload) = 'object'::text AND jsonb_typeof(response_payload) = 'object'::text)",
    "constraint|gaming_void_receipts|ck_gaming_void_receipt_retained_bill|CHECK (addon_count = 0 AND retained_order_id IS NULL AND retained_total_minor = 0 OR addon_count > 0 AND retained_order_id IS NOT NULL)",
    "constraint|gaming_void_receipts|ck_gaming_void_receipt_source_review|CHECK (source_order_id IS NULL AND source_order_status IS NULL AND source_checkout_version IS NULL AND source_total_minor IS NULL AND gaming_charge_line_id IS NULL AND charge_unit_price_minor IS NULL AND charge_line_total_minor IS NULL AND charge_discount_minor IS NULL AND charge_taxable_value_minor IS NULL AND charge_cgst_minor IS NULL AND charge_sgst_minor IS NULL AND charge_igst_minor IS NULL AND charge_cess_minor IS NULL OR source_order_id IS NOT NULL AND source_order_status::text = 'held'::text AND source_checkout_version > 0 AND source_total_minor >= 0 AND gaming_charge_line_id IS NOT NULL AND charge_unit_price_minor >= 0 AND charge_line_total_minor >= 0 AND charge_discount_minor >= 0 AND charge_taxable_value_minor >= 0 AND charge_cgst_minor >= 0 AND charge_sgst_minor >= 0 AND charge_igst_minor >= 0 AND charge_cess_minor >= 0 AND charge_unit_price_minor = charge_line_total_minor AND charge_discount_minor <= original_amount_minor AND charge_line_total_minor >= (original_amount_minor - charge_discount_minor) AND charge_line_total_minor = (charge_taxable_value_minor + charge_cgst_minor + charge_sgst_minor + charge_igst_minor + charge_cess_minor))",
    "constraint|gaming_void_receipts|gaming_void_receipts_actor_user_id_fkey|FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE RESTRICT",
    "constraint|gaming_void_receipts|gaming_void_receipts_company_id_fkey|FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT",
    "constraint|gaming_void_receipts|gaming_void_receipts_gaming_charge_line_id_fkey|FOREIGN KEY (gaming_charge_line_id) REFERENCES order_lines(id) ON DELETE RESTRICT",
    "constraint|gaming_void_receipts|gaming_void_receipts_gaming_session_id_fkey|FOREIGN KEY (gaming_session_id) REFERENCES gaming_sessions(id) ON DELETE RESTRICT",
    "constraint|gaming_void_receipts|gaming_void_receipts_pkey|PRIMARY KEY (id)",
    "constraint|gaming_void_receipts|gaming_void_receipts_retained_order_id_fkey|FOREIGN KEY (retained_order_id) REFERENCES orders(id) ON DELETE RESTRICT",
    "constraint|gaming_void_receipts|gaming_void_receipts_source_order_id_fkey|FOREIGN KEY (source_order_id) REFERENCES orders(id) ON DELETE RESTRICT",
    "constraint|gaming_void_receipts|gaming_void_receipts_terminal_id_fkey|FOREIGN KEY (terminal_id) REFERENCES terminals(id) ON DELETE RESTRICT",
    "constraint|gaming_void_receipts|trg_gaming_void_receipts_validate_session|TRIGGER DEFERRABLE INITIALLY DEFERRED",
    "constraint|gaming_void_receipts|uq_gaming_void_receipt_company_key|UNIQUE (company_id, idempotency_key)",
    "constraint|gaming_void_receipts|uq_gaming_void_receipt_session|UNIQUE (gaming_session_id)",
    "constraint|order_lines|trg_order_lines_guard_gaming_void|TRIGGER DEFERRABLE INITIALLY DEFERRED",
    "constraint|orders|trg_orders_guard_gaming_bill_void|TRIGGER DEFERRABLE INITIALLY DEFERRED",
    "function|assert_gaming_void_final_state|target_session_id uuid, target_company_id uuid|CREATE OR REPLACE FUNCTION public.assert_gaming_void_final_state(target_session_id uuid, target_company_id uuid)\n RETURNS void\n LANGUAGE plpgsql\nAS $function$\n        DECLARE\n            target_session gaming_sessions%ROWTYPE;\n            receipt gaming_void_receipts%ROWTYPE;\n            active_addons integer;\n            mapped_addons integer;\n            retained_status text;\n            retained_total bigint;\n            source_status text;\n            source_company uuid;\n            source_branch uuid;\n            source_terminal uuid;\n            session_branch uuid;\n            charge_order uuid;\n            charge_voided_at timestamptz;\n            charge_unit_price bigint;\n            charge_line_total bigint;\n            charge_discount bigint;\n            charge_taxable bigint;\n            charge_cgst bigint;\n            charge_sgst bigint;\n            charge_igst bigint;\n            charge_cess bigint;\n        BEGIN\n            SELECT * INTO target_session FROM gaming_sessions\n             WHERE id = target_session_id;\n            IF target_session.id IS NULL\n               OR target_session.company_id IS DISTINCT FROM target_company_id THEN\n                RAISE EXCEPTION 'Gaming Void final state has invalid session scope'\n                    USING ERRCODE = '23514';\n            END IF;\n            SELECT * INTO receipt FROM gaming_void_receipts\n             WHERE gaming_session_id = target_session.id;\n            IF target_session.status <> 'cancelled' THEN\n                IF receipt.id IS NOT NULL THEN\n                    RAISE EXCEPTION 'Gaming Void receipt requires a cancelled session'\n                        USING ERRCODE = '23514';\n                END IF;\n                RETURN;\n            END IF;\n            SELECT count(*) INTO active_addons FROM gaming_session_addons a\n             WHERE a.gaming_session_id = target_session.id\n               AND a.company_id = target_session.company_id\n               AND a.voided_at IS NULL;\n            IF active_addons = 0 AND receipt.id IS NULL THEN\n                RETURN;\n            END IF;\n            IF receipt.id IS NULL\n               OR receipt.company_id IS DISTINCT FROM target_session.company_id\n               OR receipt.retained_order_id IS DISTINCT FROM target_session.retained_addon_order_id\n               OR target_session.order_id IS NOT NULL\n               OR receipt.addon_count <> active_addons THEN\n                RAISE EXCEPTION 'cancelled Gaming session does not match its immutable Void receipt'\n                    USING ERRCODE = '23514';\n            END IF;\n            IF target_session.amount_minor IS DISTINCT FROM 0\n               OR target_session.billable_minutes IS DISTINCT FROM 0\n               OR target_session.cancelled_at IS DISTINCT FROM receipt.voided_at\n               OR target_session.cancelled_by IS DISTINCT FROM receipt.actor_user_id\n               OR target_session.cancel_reason IS DISTINCT FROM receipt.reason THEN\n                RAISE EXCEPTION 'cancelled Gaming session facts do not match its immutable Void receipt'\n                    USING ERRCODE = '23514';\n            END IF;\n            SELECT station.branch_id INTO session_branch\n              FROM stations station WHERE station.id = target_session.station_id;\n            IF session_branch IS NULL THEN\n                RAISE EXCEPTION 'Gaming Void final state has invalid station scope'\n                    USING ERRCODE = '23514';\n            END IF;\n            IF receipt.source_order_id IS NOT NULL THEN\n                SELECT company_id, branch_id, terminal_id, status\n                  INTO source_company, source_branch, source_terminal, source_status\n                  FROM orders WHERE id = receipt.source_order_id;\n                IF source_company IS DISTINCT FROM receipt.company_id\n                   OR source_branch IS DISTINCT FROM session_branch\n                   OR source_terminal IS DISTINCT FROM receipt.terminal_id THEN\n                    RAISE EXCEPTION 'Gaming Void source order has invalid company/branch/terminal scope'\n                        USING ERRCODE = '23514';\n                END IF;\n                SELECT line.order_id, line.voided_at, line.unit_price_minor,\n                       line.line_total_minor, line.discount_minor,\n                       line.taxable_value_minor, line.cgst_minor, line.sgst_minor,\n                       line.igst_minor, line.cess_minor\n                  INTO charge_order, charge_voided_at, charge_unit_price,\n                       charge_line_total, charge_discount, charge_taxable,\n                       charge_cgst, charge_sgst, charge_igst, charge_cess\n                  FROM order_lines line WHERE line.id = receipt.gaming_charge_line_id;\n                IF charge_order IS DISTINCT FROM receipt.source_order_id\n                   OR charge_voided_at IS NULL\n                   OR charge_unit_price IS DISTINCT FROM receipt.charge_unit_price_minor\n                   OR charge_line_total IS DISTINCT FROM receipt.charge_line_total_minor\n                   OR charge_discount IS DISTINCT FROM receipt.charge_discount_minor\n                   OR charge_taxable IS DISTINCT FROM receipt.charge_taxable_value_minor\n                   OR charge_cgst IS DISTINCT FROM receipt.charge_cgst_minor\n                   OR charge_sgst IS DISTINCT FROM receipt.charge_sgst_minor\n                   OR charge_igst IS DISTINCT FROM receipt.charge_igst_minor\n                   OR charge_cess IS DISTINCT FROM receipt.charge_cess_minor THEN\n                    RAISE EXCEPTION 'Gaming charge line does not match its immutable Void receipt'\n                        USING ERRCODE = '23514';\n                END IF;\n            END IF;\n            IF active_addons > 0 THEN\n                SELECT status, total_minor INTO retained_status, retained_total FROM orders\n                 WHERE id = target_session.retained_addon_order_id;\n                IF retained_status NOT IN ('held','paid','refunded')\n                   OR retained_total IS DISTINCT FROM receipt.retained_total_minor THEN\n                    RAISE EXCEPTION 'retained Gaming add-on bill is missing or not payable'\n                        USING ERRCODE = '23514';\n                END IF;\n                SELECT count(*) INTO mapped_addons\n                  FROM gaming_void_addon_lines map\n                  JOIN gaming_session_addons addon ON addon.id = map.addon_id\n                  JOIN order_lines line ON line.id = map.order_line_id\n                 WHERE map.receipt_id = receipt.id\n                   AND addon.gaming_session_id = target_session.id\n                   AND addon.voided_at IS NULL\n                   AND map.source_order_id IS NOT DISTINCT FROM receipt.source_order_id\n                   AND map.retained_order_id = target_session.retained_addon_order_id\n                   AND line.order_id = target_session.retained_addon_order_id\n                   AND line.voided_at IS NULL\n                   AND line.client_line_id = addon.client_line_id\n                   AND line.menu_item_id = addon.menu_item_id\n                   AND line.qty = addon.qty\n                   AND line.unit_price_minor = addon.unit_price_minor\n                   AND line.line_total_minor = addon.line_total_minor\n                   AND line.discount_minor = addon.discount_minor\n                   AND line.taxable_value_minor = addon.taxable_value_minor\n                   AND line.cgst_minor = addon.cgst_minor\n                   AND line.sgst_minor = addon.sgst_minor\n                   AND line.igst_minor = addon.igst_minor\n                   AND line.cess_minor = addon.cess_minor\n                   AND line.hsn_or_sac IS NOT DISTINCT FROM addon.hsn_or_sac\n                   AND line.tax_rate IS NOT DISTINCT FROM addon.tax_rate\n                   AND line.menu_item_name_snapshot = addon.menu_item_name_snapshot\n                   AND line.menu_item_type_snapshot = addon.menu_item_type_snapshot\n                   AND line.variant_id IS NOT DISTINCT FROM addon.variant_id\n                   AND line.variant_snapshot IS NOT DISTINCT FROM addon.variant_snapshot\n                   AND line.modifiers IS NOT DISTINCT FROM addon.modifiers\n                   AND line.note IS NOT DISTINCT FROM addon.note\n                   AND line.kitchen_status = 'served'\n                   AND line.kitchen_released_at IS NOT DISTINCT FROM addon.created_at\n                   AND line.kitchen_served_at IS NOT DISTINCT FROM addon.created_at\n                   AND line.created_at IS NOT DISTINCT FROM addon.created_at;\n                IF mapped_addons <> active_addons THEN\n                    RAISE EXCEPTION 'retained Gaming add-on bill is not an exact immutable bijection'\n                        USING ERRCODE = '23514';\n                END IF;\n            ELSIF receipt.source_order_id IS NOT NULL THEN\n                IF source_status IS DISTINCT FROM 'void' THEN\n                    RAISE EXCEPTION 'Gaming Void without retained add-ons must void its source bill'\n                        USING ERRCODE = '23514';\n                END IF;\n            END IF;\n            RETURN;\n        END $function$\n",
    "function|capture_order_line_reporting_snapshot||CREATE OR REPLACE FUNCTION public.capture_order_line_reporting_snapshot()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$\n        DECLARE\n            sold_item_name text;\n            sold_item_type text;\n            sold_item_company uuid;\n            sale_company uuid;\n            staged_item_name text;\n            staged_item_type text;\n            staged_company uuid;\n        BEGIN\n            IF TG_OP = 'UPDATE'\n               AND NEW.order_id IS NOT DISTINCT FROM OLD.order_id\n               AND NEW.menu_item_id IS NOT DISTINCT FROM OLD.menu_item_id THEN\n                IF NEW.menu_item_name_snapshot IS DISTINCT FROM OLD.menu_item_name_snapshot\n                   OR NEW.menu_item_type_snapshot IS DISTINCT FROM OLD.menu_item_type_snapshot\n                   OR NEW.reporting_snapshot_revision IS DISTINCT FROM OLD.reporting_snapshot_revision THEN\n                    RAISE EXCEPTION 'sold item reporting snapshots are immutable'\n                        USING ERRCODE = '23514';\n                END IF;\n                RETURN NEW;\n            END IF;\n            SELECT item.name, item.type, item.company_id\n              INTO sold_item_name, sold_item_type, sold_item_company\n              FROM menu_items item WHERE item.id = NEW.menu_item_id;\n            SELECT sale.company_id INTO sale_company\n              FROM orders sale WHERE sale.id = NEW.order_id;\n            IF sold_item_name IS NULL OR char_length(trim(sold_item_name)) < 1\n               OR sold_item_type IS NULL OR char_length(trim(sold_item_type)) < 1\n               OR sale_company IS NULL OR sold_item_company IS DISTINCT FROM sale_company THEN\n                RAISE EXCEPTION 'order line and sold item must have matching company provenance'\n                    USING ERRCODE = '23514';\n            END IF;\n            SELECT addon.menu_item_name_snapshot, addon.menu_item_type_snapshot, addon.company_id\n              INTO staged_item_name, staged_item_type, staged_company\n              FROM gaming_session_addons addon\n              JOIN gaming_sessions gs ON gs.id = addon.gaming_session_id\n               AND (gs.order_id = NEW.order_id OR gs.retained_addon_order_id = NEW.order_id)\n             WHERE addon.client_line_id = NEW.client_line_id\n               AND addon.menu_item_id = NEW.menu_item_id\n               AND addon.voided_at IS NULL\n             LIMIT 1;\n            IF staged_company IS NOT NULL THEN\n                IF staged_company IS DISTINCT FROM sale_company\n                   OR (NEW.menu_item_name_snapshot IS NOT NULL\n                       AND NEW.menu_item_name_snapshot IS DISTINCT FROM staged_item_name)\n                   OR (NEW.menu_item_type_snapshot IS NOT NULL\n                       AND NEW.menu_item_type_snapshot IS DISTINCT FROM staged_item_type) THEN\n                    RAISE EXCEPTION 'gaming add-on order-line snapshot does not match its immutable source'\n                        USING ERRCODE = '23514';\n                END IF;\n                NEW.menu_item_name_snapshot := staged_item_name;\n                NEW.menu_item_type_snapshot := staged_item_type;\n                NEW.reporting_snapshot_revision := 49;\n                RETURN NEW;\n            END IF;\n            IF TG_OP = 'INSERT' AND (\n                (NEW.menu_item_name_snapshot IS NOT NULL AND NEW.menu_item_name_snapshot IS DISTINCT FROM sold_item_name)\n                OR (NEW.menu_item_type_snapshot IS NOT NULL AND NEW.menu_item_type_snapshot IS DISTINCT FROM sold_item_type)\n            ) THEN\n                RAISE EXCEPTION 'client-supplied sold item snapshot does not match the catalogue'\n                    USING ERRCODE = '23514';\n            END IF;\n            NEW.menu_item_name_snapshot := sold_item_name;\n            NEW.menu_item_type_snapshot := sold_item_type;\n            NEW.reporting_snapshot_revision := 49;\n            RETURN NEW;\n        END $function$\n",
    "function|guard_gaming_bill_line_void||CREATE OR REPLACE FUNCTION public.guard_gaming_bill_line_void()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$\n        BEGIN\n            IF NEW.voided_at IS NOT NULL AND OLD.voided_at IS NULL\n               AND (\n                    EXISTS (SELECT 1 FROM gaming_sessions gs WHERE gs.order_id = NEW.order_id)\n                    OR EXISTS (SELECT 1 FROM gaming_sessions gs WHERE gs.retained_addon_order_id = NEW.order_id)\n               )\n               AND NOT EXISTS (\n                    SELECT 1 FROM gaming_void_receipts r\n                     WHERE r.source_order_id = NEW.order_id\n                       AND r.gaming_charge_line_id = NEW.id\n               ) THEN\n                RAISE EXCEPTION 'Gaming-linked order lines require the dedicated Owner Void workflow'\n                    USING ERRCODE = '23514';\n            END IF;\n            RETURN NULL;\n        END $function$\n",
    "function|guard_gaming_bill_void||CREATE OR REPLACE FUNCTION public.guard_gaming_bill_void()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$\n        BEGIN\n            IF NEW.status = 'void' AND OLD.status IS DISTINCT FROM 'void'\n               AND (\n                    EXISTS (SELECT 1 FROM gaming_sessions gs WHERE gs.order_id = NEW.id)\n                    OR EXISTS (SELECT 1 FROM gaming_sessions gs WHERE gs.retained_addon_order_id = NEW.id)\n               )\n               AND NOT EXISTS (\n                    SELECT 1 FROM gaming_void_receipts r\n                     WHERE r.source_order_id = NEW.id\n               ) THEN\n                RAISE EXCEPTION 'Gaming-linked orders require the dedicated Owner Void receipt'\n                    USING ERRCODE = '23514';\n            END IF;\n            IF NEW.status = 'void' AND OLD.status IS DISTINCT FROM 'void'\n               AND EXISTS (\n                    SELECT 1 FROM gaming_sessions gs\n                     WHERE gs.retained_addon_order_id = NEW.id\n               ) THEN\n                RAISE EXCEPTION 'retained Gaming add-on bills cannot use generic order Void'\n                    USING ERRCODE = '23514';\n            END IF;\n            RETURN NULL;\n        END $function$\n",
    "function|guard_gaming_retained_addon_link||CREATE OR REPLACE FUNCTION public.guard_gaming_retained_addon_link()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$\n        DECLARE\n            retained_company uuid;\n            retained_branch uuid;\n            retained_terminal uuid;\n            retained_shift uuid;\n            retained_status text;\n            retained_type text;\n            station_branch uuid;\n            shift_company uuid;\n            shift_branch uuid;\n            shift_terminal uuid;\n            shift_status text;\n            terminal_active boolean;\n            terminal_purpose text;\n        BEGIN\n            IF OLD.retained_addon_order_id IS NOT NULL\n               AND NEW.retained_addon_order_id IS DISTINCT FROM OLD.retained_addon_order_id THEN\n                RAISE EXCEPTION 'retained Gaming add-on order is one-way immutable'\n                    USING ERRCODE = '23514';\n            END IF;\n            IF NEW.retained_addon_order_id IS NULL\n               OR NEW.retained_addon_order_id IS NOT DISTINCT FROM OLD.retained_addon_order_id THEN\n                RETURN NEW;\n            END IF;\n            SELECT o.company_id, o.branch_id, o.terminal_id, o.shift_id, o.status, o.type\n              INTO retained_company, retained_branch, retained_terminal, retained_shift,\n                   retained_status, retained_type\n              FROM orders o WHERE o.id = NEW.retained_addon_order_id;\n            SELECT s.branch_id INTO station_branch\n              FROM stations s WHERE s.id = NEW.station_id;\n            SELECT sh.company_id, sh.branch_id, sh.terminal_id, sh.status\n              INTO shift_company, shift_branch, shift_terminal, shift_status\n              FROM shifts sh WHERE sh.id = retained_shift;\n            SELECT terminal.is_active, terminal.purpose\n              INTO terminal_active, terminal_purpose\n              FROM terminals terminal WHERE terminal.id = retained_terminal;\n            IF retained_company IS DISTINCT FROM NEW.company_id\n               OR retained_branch IS DISTINCT FROM station_branch\n               OR retained_status IS DISTINCT FROM 'held'\n               OR retained_type IS DISTINCT FROM 'session'\n               OR shift_company IS DISTINCT FROM retained_company\n               OR shift_branch IS DISTINCT FROM retained_branch\n               OR shift_terminal IS DISTINCT FROM retained_terminal\n               OR shift_status IS DISTINCT FROM 'open'\n               OR terminal_active IS DISTINCT FROM true\n               OR terminal_purpose IS NULL\n               OR terminal_purpose NOT IN ('cafe_pos', 'hybrid') THEN\n                RAISE EXCEPTION 'retained Gaming add-on bill has invalid company/branch scope'\n                    USING ERRCODE = '23514';\n            END IF;\n            IF OLD.order_id IS NOT NULL\n               AND (NEW.retained_addon_order_id IS DISTINCT FROM OLD.order_id\n                    OR NEW.order_id IS NOT NULL) THEN\n                RAISE EXCEPTION 'linked Gaming Void must retain the reviewed source order'\n                    USING ERRCODE = '23514';\n            END IF;\n            RETURN NEW;\n        END $function$\n",
    "function|guard_gaming_retained_order_line||CREATE OR REPLACE FUNCTION public.guard_gaming_retained_order_line()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$\n        BEGIN\n            IF (\n                EXISTS (\n                    SELECT 1 FROM gaming_void_addon_lines map\n                     WHERE map.order_line_id = OLD.id\n                )\n                OR EXISTS (\n                    SELECT 1 FROM gaming_void_receipts receipt\n                     WHERE receipt.gaming_charge_line_id = OLD.id\n                )\n            ) AND (\n                NEW.order_id IS DISTINCT FROM OLD.order_id\n                OR NEW.client_line_id IS DISTINCT FROM OLD.client_line_id\n                OR NEW.menu_item_id IS DISTINCT FROM OLD.menu_item_id\n                OR NEW.menu_item_name_snapshot IS DISTINCT FROM OLD.menu_item_name_snapshot\n                OR NEW.menu_item_type_snapshot IS DISTINCT FROM OLD.menu_item_type_snapshot\n                OR NEW.reporting_snapshot_revision IS DISTINCT FROM OLD.reporting_snapshot_revision\n                OR NEW.variant_id IS DISTINCT FROM OLD.variant_id\n                OR NEW.variant_snapshot IS DISTINCT FROM OLD.variant_snapshot\n                OR NEW.modifiers IS DISTINCT FROM OLD.modifiers\n                OR NEW.qty IS DISTINCT FROM OLD.qty\n                OR NEW.unit_price_minor IS DISTINCT FROM OLD.unit_price_minor\n                OR NEW.line_total_minor IS DISTINCT FROM OLD.line_total_minor\n                OR NEW.discount_minor IS DISTINCT FROM OLD.discount_minor\n                OR NEW.hsn_or_sac IS DISTINCT FROM OLD.hsn_or_sac\n                OR NEW.tax_rate IS DISTINCT FROM OLD.tax_rate\n                OR NEW.taxable_value_minor IS DISTINCT FROM OLD.taxable_value_minor\n                OR NEW.cgst_minor IS DISTINCT FROM OLD.cgst_minor\n                OR NEW.sgst_minor IS DISTINCT FROM OLD.sgst_minor\n                OR NEW.igst_minor IS DISTINCT FROM OLD.igst_minor\n                OR NEW.cess_minor IS DISTINCT FROM OLD.cess_minor\n                OR NEW.note IS DISTINCT FROM OLD.note\n                OR NEW.kitchen_status IS DISTINCT FROM OLD.kitchen_status\n                OR NEW.kitchen_released_at IS DISTINCT FROM OLD.kitchen_released_at\n                OR NEW.kitchen_round_no IS DISTINCT FROM OLD.kitchen_round_no\n                OR NEW.kitchen_served_at IS DISTINCT FROM OLD.kitchen_served_at\n                OR NEW.created_at IS DISTINCT FROM OLD.created_at\n                OR NEW.voided_at IS DISTINCT FROM OLD.voided_at\n                OR NEW.voided_by IS DISTINCT FROM OLD.voided_by\n                OR NEW.void_reason IS DISTINCT FROM OLD.void_reason\n            ) THEN\n                RAISE EXCEPTION 'retained Gaming add-on line evidence is immutable'\n                    USING ERRCODE = '23514';\n            END IF;\n            RETURN NEW;\n        END $function$\n",
    "function|guard_gaming_void_evidence_immutable||CREATE OR REPLACE FUNCTION public.guard_gaming_void_evidence_immutable()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$\n        BEGIN\n            RAISE EXCEPTION 'gaming Void financial evidence is immutable'\n                USING ERRCODE = '23514';\n        END $function$\n",
    "function|guard_gaming_void_order_totals||CREATE OR REPLACE FUNCTION public.guard_gaming_void_order_totals()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$\n        BEGIN\n            IF EXISTS (\n                SELECT 1 FROM gaming_void_receipts receipt\n                 WHERE receipt.source_order_id = OLD.id\n                    OR receipt.retained_order_id = OLD.id\n            ) AND (\n                NEW.subtotal_minor IS DISTINCT FROM OLD.subtotal_minor\n                OR NEW.discount_minor IS DISTINCT FROM OLD.discount_minor\n                OR NEW.manual_discount_minor IS DISTINCT FROM OLD.manual_discount_minor\n                OR NEW.points_redeemed_minor IS DISTINCT FROM OLD.points_redeemed_minor\n                OR NEW.cgst_minor IS DISTINCT FROM OLD.cgst_minor\n                OR NEW.sgst_minor IS DISTINCT FROM OLD.sgst_minor\n                OR NEW.igst_minor IS DISTINCT FROM OLD.igst_minor\n                OR NEW.cess_minor IS DISTINCT FROM OLD.cess_minor\n                OR NEW.tax_minor IS DISTINCT FROM OLD.tax_minor\n                OR NEW.round_off_minor IS DISTINCT FROM OLD.round_off_minor\n                OR NEW.tip_minor IS DISTINCT FROM OLD.tip_minor\n                OR NEW.total_minor IS DISTINCT FROM OLD.total_minor\n            ) THEN\n                RAISE EXCEPTION 'Gaming Void order totals are immutable financial evidence'\n                    USING ERRCODE = '23514';\n            END IF;\n            RETURN NEW;\n        END $function$\n",
    "function|validate_gaming_source_order_unlink||CREATE OR REPLACE FUNCTION public.validate_gaming_source_order_unlink()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$\n        BEGIN\n            IF OLD.order_id IS NOT NULL AND NEW.order_id IS NULL\n               AND NOT EXISTS (\n                    SELECT 1 FROM gaming_void_receipts receipt\n                     WHERE receipt.gaming_session_id = NEW.id\n                       AND receipt.company_id = NEW.company_id\n                       AND receipt.mode = 'linked_pos'\n                       AND receipt.source_order_id = OLD.order_id\n               ) THEN\n                RAISE EXCEPTION 'linked Gaming order cannot be detached without its immutable Owner Void receipt'\n                    USING ERRCODE = '23514';\n            END IF;\n            RETURN NULL;\n        END $function$\n",
    "function|validate_gaming_void_addon_mapping||CREATE OR REPLACE FUNCTION public.validate_gaming_void_addon_mapping()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$\n        DECLARE\n            receipt gaming_void_receipts%ROWTYPE;\n            addon gaming_session_addons%ROWTYPE;\n            line order_lines%ROWTYPE;\n        BEGIN\n            SELECT * INTO receipt FROM gaming_void_receipts WHERE id = NEW.receipt_id;\n            SELECT * INTO addon FROM gaming_session_addons WHERE id = NEW.addon_id;\n            SELECT * INTO line FROM order_lines WHERE id = NEW.order_line_id;\n            IF receipt.id IS NULL OR addon.id IS NULL OR line.id IS NULL THEN\n                RAISE EXCEPTION 'Gaming Void add-on mapping references missing evidence'\n                    USING ERRCODE = '23514';\n            END IF;\n            IF receipt.addon_count < 1\n               OR addon.company_id IS DISTINCT FROM receipt.company_id\n               OR addon.gaming_session_id IS DISTINCT FROM receipt.gaming_session_id\n               OR addon.voided_at IS NOT NULL THEN\n                RAISE EXCEPTION 'Gaming Void add-on mapping has invalid receipt or session scope'\n                    USING ERRCODE = '23514';\n            END IF;\n            IF NEW.source_order_id IS DISTINCT FROM receipt.source_order_id\n               OR NEW.retained_order_id IS DISTINCT FROM receipt.retained_order_id\n               OR line.order_id IS DISTINCT FROM receipt.retained_order_id\n               OR line.voided_at IS NOT NULL THEN\n                RAISE EXCEPTION 'Gaming Void add-on mapping has invalid retained order scope'\n                    USING ERRCODE = '23514';\n            END IF;\n            IF line.client_line_id IS DISTINCT FROM addon.client_line_id\n               OR line.menu_item_id IS DISTINCT FROM addon.menu_item_id\n               OR line.qty IS DISTINCT FROM addon.qty\n               OR line.unit_price_minor IS DISTINCT FROM addon.unit_price_minor\n               OR line.line_total_minor IS DISTINCT FROM addon.line_total_minor\n               OR line.discount_minor IS DISTINCT FROM addon.discount_minor\n               OR line.taxable_value_minor IS DISTINCT FROM addon.taxable_value_minor\n               OR line.cgst_minor IS DISTINCT FROM addon.cgst_minor\n               OR line.sgst_minor IS DISTINCT FROM addon.sgst_minor\n               OR line.igst_minor IS DISTINCT FROM addon.igst_minor\n               OR line.cess_minor IS DISTINCT FROM addon.cess_minor\n               OR line.hsn_or_sac IS DISTINCT FROM addon.hsn_or_sac\n               OR line.tax_rate IS DISTINCT FROM addon.tax_rate THEN\n                RAISE EXCEPTION 'Gaming Void add-on mapping has changed immutable financial values'\n                    USING ERRCODE = '23514';\n            END IF;\n            IF line.menu_item_name_snapshot IS DISTINCT FROM addon.menu_item_name_snapshot\n               OR line.menu_item_type_snapshot IS DISTINCT FROM addon.menu_item_type_snapshot\n               OR line.variant_id IS DISTINCT FROM addon.variant_id\n               OR line.variant_snapshot IS DISTINCT FROM addon.variant_snapshot\n               OR line.modifiers IS DISTINCT FROM addon.modifiers\n               OR line.note IS DISTINCT FROM addon.note\n               OR line.kitchen_status IS DISTINCT FROM 'served'\n               OR line.kitchen_released_at IS DISTINCT FROM addon.created_at\n               OR line.kitchen_served_at IS DISTINCT FROM addon.created_at\n               OR line.created_at IS DISTINCT FROM addon.created_at THEN\n                RAISE EXCEPTION 'Gaming Void add-on mapping has changed immutable service evidence'\n                    USING ERRCODE = '23514';\n            END IF;\n            RETURN NEW;\n        END $function$\n",
    "function|validate_gaming_void_final_state||CREATE OR REPLACE FUNCTION public.validate_gaming_void_final_state()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$\n        BEGIN\n            PERFORM assert_gaming_void_final_state(NEW.id, NEW.company_id);\n            RETURN NULL;\n        END $function$\n",
    "function|validate_gaming_void_receipt_session||CREATE OR REPLACE FUNCTION public.validate_gaming_void_receipt_session()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$\n        BEGIN\n            PERFORM assert_gaming_void_final_state(\n                NEW.gaming_session_id,\n                NEW.company_id\n            );\n            RETURN NULL;\n        END $function$\n",
    "index|gaming_sessions|uq_gaming_sessions_retained_addon_order|CREATE UNIQUE INDEX uq_gaming_sessions_retained_addon_order ON public.gaming_sessions USING btree (retained_addon_order_id) WHERE (retained_addon_order_id IS NOT NULL)",
    "index|gaming_void_addon_lines|gaming_void_addon_lines_pkey|CREATE UNIQUE INDEX gaming_void_addon_lines_pkey ON public.gaming_void_addon_lines USING btree (id)",
    "index|gaming_void_addon_lines|ix_gaming_void_addon_lines_receipt_id|CREATE INDEX ix_gaming_void_addon_lines_receipt_id ON public.gaming_void_addon_lines USING btree (receipt_id)",
    "index|gaming_void_addon_lines|uq_gaming_void_addon_line_addon|CREATE UNIQUE INDEX uq_gaming_void_addon_line_addon ON public.gaming_void_addon_lines USING btree (addon_id)",
    "index|gaming_void_addon_lines|uq_gaming_void_addon_line_order_line|CREATE UNIQUE INDEX uq_gaming_void_addon_line_order_line ON public.gaming_void_addon_lines USING btree (order_line_id)",
    "index|gaming_void_receipts|gaming_void_receipts_pkey|CREATE UNIQUE INDEX gaming_void_receipts_pkey ON public.gaming_void_receipts USING btree (id)",
    "index|gaming_void_receipts|uq_gaming_void_receipt_company_key|CREATE UNIQUE INDEX uq_gaming_void_receipt_company_key ON public.gaming_void_receipts USING btree (company_id, idempotency_key)",
    "index|gaming_void_receipts|uq_gaming_void_receipt_retained_order|CREATE UNIQUE INDEX uq_gaming_void_receipt_retained_order ON public.gaming_void_receipts USING btree (retained_order_id) WHERE (retained_order_id IS NOT NULL)",
    "index|gaming_void_receipts|uq_gaming_void_receipt_session|CREATE UNIQUE INDEX uq_gaming_void_receipt_session ON public.gaming_void_receipts USING btree (gaming_session_id)",
    "index|gaming_void_receipts|uq_gaming_void_receipt_source_order|CREATE UNIQUE INDEX uq_gaming_void_receipt_source_order ON public.gaming_void_receipts USING btree (source_order_id) WHERE (source_order_id IS NOT NULL)",
    "trigger|gaming_sessions|trg_gaming_sessions_retained_addon_link|UPDATE|BEFORE|EXECUTE FUNCTION guard_gaming_retained_addon_link()",
    "trigger|gaming_sessions|trg_gaming_sessions_validate_order_unlink|UPDATE|AFTER|EXECUTE FUNCTION validate_gaming_source_order_unlink()",
    "trigger|gaming_sessions|trg_gaming_sessions_validate_void|UPDATE|AFTER|EXECUTE FUNCTION validate_gaming_void_final_state()",
    "trigger|gaming_void_addon_lines|trg_gaming_void_addon_lines_immutable|DELETE|BEFORE|EXECUTE FUNCTION guard_gaming_void_evidence_immutable()",
    "trigger|gaming_void_addon_lines|trg_gaming_void_addon_lines_immutable|UPDATE|BEFORE|EXECUTE FUNCTION guard_gaming_void_evidence_immutable()",
    "trigger|gaming_void_addon_lines|trg_gaming_void_addon_lines_validate|INSERT|AFTER|EXECUTE FUNCTION validate_gaming_void_addon_mapping()",
    "trigger|gaming_void_receipts|trg_gaming_void_receipts_immutable|DELETE|BEFORE|EXECUTE FUNCTION guard_gaming_void_evidence_immutable()",
    "trigger|gaming_void_receipts|trg_gaming_void_receipts_immutable|UPDATE|BEFORE|EXECUTE FUNCTION guard_gaming_void_evidence_immutable()",
    "trigger|gaming_void_receipts|trg_gaming_void_receipts_validate_session|INSERT|AFTER|EXECUTE FUNCTION validate_gaming_void_receipt_session()",
    "trigger|order_lines|trg_order_lines_guard_gaming_void|UPDATE|AFTER|EXECUTE FUNCTION guard_gaming_bill_line_void()",
    "trigger|order_lines|trg_order_lines_guard_retained_gaming_evidence|UPDATE|BEFORE|EXECUTE FUNCTION guard_gaming_retained_order_line()",
    "trigger|orders|trg_orders_guard_gaming_bill_void|UPDATE|AFTER|EXECUTE FUNCTION guard_gaming_bill_void()",
    "trigger|orders|trg_orders_guard_gaming_void_totals|UPDATE|BEFORE|EXECUTE FUNCTION guard_gaming_void_order_totals()",
)
EXPECTED_0093_SCHEMA_REMOVED_ROWS: tuple[str, ...] = (
    "function|capture_order_line_reporting_snapshot||CREATE OR REPLACE FUNCTION public.capture_order_line_reporting_snapshot()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$\n        DECLARE\n            sold_item_name text;\n            sold_item_type text;\n            sold_item_company uuid;\n            sale_company uuid;\n            staged_item_name text;\n            staged_item_type text;\n            staged_company uuid;\n        BEGIN\n            IF TG_OP = 'UPDATE'\n               AND NEW.order_id IS NOT DISTINCT FROM OLD.order_id\n               AND NEW.menu_item_id IS NOT DISTINCT FROM OLD.menu_item_id THEN\n                IF NEW.menu_item_name_snapshot IS DISTINCT FROM OLD.menu_item_name_snapshot\n                   OR NEW.menu_item_type_snapshot IS DISTINCT FROM OLD.menu_item_type_snapshot\n                   OR NEW.reporting_snapshot_revision\n                        IS DISTINCT FROM OLD.reporting_snapshot_revision THEN\n                    RAISE EXCEPTION\n                        'sold item reporting snapshots are immutable'\n                        USING ERRCODE = '23514';\n                END IF;\n                RETURN NEW;\n            END IF;\n\n            SELECT item.name, item.type, item.company_id\n              INTO sold_item_name, sold_item_type, sold_item_company\n              FROM menu_items item\n             WHERE item.id = NEW.menu_item_id;\n            SELECT sale.company_id\n              INTO sale_company\n              FROM orders sale\n             WHERE sale.id = NEW.order_id;\n            IF sold_item_name IS NULL\n               OR char_length(trim(sold_item_name)) < 1\n               OR sold_item_type IS NULL\n               OR char_length(trim(sold_item_type)) < 1\n               OR sale_company IS NULL\n               OR sold_item_company IS DISTINCT FROM sale_company THEN\n                RAISE EXCEPTION\n                    'order line and sold item must have matching company provenance'\n                    USING ERRCODE = '23514';\n            END IF;\n\n            \n            SELECT addon.menu_item_name_snapshot,\n                   addon.menu_item_type_snapshot,\n                   addon.company_id\n              INTO staged_item_name, staged_item_type, staged_company\n              FROM gaming_session_addons addon\n              JOIN gaming_sessions gs\n                ON gs.id = addon.gaming_session_id\n               AND gs.order_id = NEW.order_id\n             WHERE addon.client_line_id = NEW.client_line_id\n               AND addon.menu_item_id = NEW.menu_item_id\n               AND addon.voided_at IS NULL\n             LIMIT 1;\n            IF staged_company IS NOT NULL THEN\n                IF staged_company IS DISTINCT FROM sale_company\n                   OR (NEW.menu_item_name_snapshot IS NOT NULL\n                       AND NEW.menu_item_name_snapshot IS DISTINCT FROM staged_item_name)\n                   OR (NEW.menu_item_type_snapshot IS NOT NULL\n                       AND NEW.menu_item_type_snapshot IS DISTINCT FROM staged_item_type) THEN\n                    RAISE EXCEPTION\n                        'gaming add-on order-line snapshot does not match its immutable source'\n                        USING ERRCODE = '23514';\n                END IF;\n                NEW.menu_item_name_snapshot := staged_item_name;\n                NEW.menu_item_type_snapshot := staged_item_type;\n                NEW.reporting_snapshot_revision := 49;\n                RETURN NEW;\n            END IF;\n    \n\n            IF TG_OP = 'INSERT'\n               AND (\n                    (NEW.menu_item_name_snapshot IS NOT NULL\n                     AND NEW.menu_item_name_snapshot IS DISTINCT FROM sold_item_name)\n                    OR\n                    (NEW.menu_item_type_snapshot IS NOT NULL\n                     AND NEW.menu_item_type_snapshot IS DISTINCT FROM sold_item_type)\n               ) THEN\n                RAISE EXCEPTION\n                    'client-supplied sold item snapshot does not match the catalogue'\n                    USING ERRCODE = '23514';\n            END IF;\n\n            NEW.menu_item_name_snapshot := sold_item_name;\n            NEW.menu_item_type_snapshot := sold_item_type;\n            NEW.reporting_snapshot_revision := 49;\n            RETURN NEW;\n        END\n        $function$\n",
    "function|guard_gaming_session_cancel_with_addons||CREATE OR REPLACE FUNCTION public.guard_gaming_session_cancel_with_addons()\n RETURNS trigger\n LANGUAGE plpgsql\nAS $function$\n        BEGIN\n            IF NEW.status = 'cancelled'\n               AND OLD.status IS DISTINCT FROM 'cancelled'\n               AND EXISTS (\n                    SELECT 1\n                      FROM gaming_session_addons addon\n                     WHERE addon.gaming_session_id = OLD.id\n                       AND addon.company_id = OLD.company_id\n                       AND addon.voided_at IS NULL\n               ) THEN\n                RAISE EXCEPTION\n                    'active Gaming add-ons must be voided before session cancellation'\n                    USING ERRCODE = '23514';\n            END IF;\n            RETURN NEW;\n        END\n        $function$\n",
    "trigger|gaming_sessions|trg_gaming_sessions_guard_addon_cancel|UPDATE|BEFORE|EXECUTE FUNCTION guard_gaming_session_cancel_with_addons()",
)
EXPECTED_TRIGGER_ADDED_ROWS: tuple[str, ...] = (
    "trigger_binding|gaming_sessions|trg_gaming_sessions_retained_addon_link|O|false|false|CREATE TRIGGER trg_gaming_sessions_retained_addon_link BEFORE UPDATE OF retained_addon_order_id ON gaming_sessions FOR EACH ROW EXECUTE FUNCTION guard_gaming_retained_addon_link()",
    "trigger_binding|gaming_sessions|trg_gaming_sessions_validate_order_unlink|O|true|true|CREATE CONSTRAINT TRIGGER trg_gaming_sessions_validate_order_unlink AFTER UPDATE OF order_id ON gaming_sessions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_gaming_source_order_unlink()",
    "trigger_binding|gaming_sessions|trg_gaming_sessions_validate_void|O|true|true|CREATE CONSTRAINT TRIGGER trg_gaming_sessions_validate_void AFTER UPDATE OF status, order_id, retained_addon_order_id ON gaming_sessions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_gaming_void_final_state()",
    "trigger_binding|gaming_void_addon_lines|trg_gaming_void_addon_lines_immutable|O|false|false|CREATE TRIGGER trg_gaming_void_addon_lines_immutable BEFORE DELETE OR UPDATE ON gaming_void_addon_lines FOR EACH ROW EXECUTE FUNCTION guard_gaming_void_evidence_immutable()",
    "trigger_binding|gaming_void_addon_lines|trg_gaming_void_addon_lines_validate|O|true|true|CREATE CONSTRAINT TRIGGER trg_gaming_void_addon_lines_validate AFTER INSERT ON gaming_void_addon_lines DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_gaming_void_addon_mapping()",
    "trigger_binding|gaming_void_receipts|trg_gaming_void_receipts_immutable|O|false|false|CREATE TRIGGER trg_gaming_void_receipts_immutable BEFORE DELETE OR UPDATE ON gaming_void_receipts FOR EACH ROW EXECUTE FUNCTION guard_gaming_void_evidence_immutable()",
    "trigger_binding|gaming_void_receipts|trg_gaming_void_receipts_validate_session|O|true|true|CREATE CONSTRAINT TRIGGER trg_gaming_void_receipts_validate_session AFTER INSERT ON gaming_void_receipts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_gaming_void_receipt_session()",
    "trigger_binding|order_lines|trg_order_lines_guard_gaming_void|O|true|true|CREATE CONSTRAINT TRIGGER trg_order_lines_guard_gaming_void AFTER UPDATE OF voided_at ON order_lines DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION guard_gaming_bill_line_void()",
    "trigger_binding|order_lines|trg_order_lines_guard_retained_gaming_evidence|O|false|false|CREATE TRIGGER trg_order_lines_guard_retained_gaming_evidence BEFORE UPDATE ON order_lines FOR EACH ROW EXECUTE FUNCTION guard_gaming_retained_order_line()",
    "trigger_binding|orders|trg_orders_guard_gaming_bill_void|O|true|true|CREATE CONSTRAINT TRIGGER trg_orders_guard_gaming_bill_void AFTER UPDATE OF status ON orders DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION guard_gaming_bill_void()",
    "trigger_binding|orders|trg_orders_guard_gaming_void_totals|O|false|false|CREATE TRIGGER trg_orders_guard_gaming_void_totals BEFORE UPDATE OF subtotal_minor, discount_minor, manual_discount_minor, points_redeemed_minor, cgst_minor, sgst_minor, igst_minor, cess_minor, tax_minor, round_off_minor, tip_minor, total_minor ON orders FOR EACH ROW EXECUTE FUNCTION guard_gaming_void_order_totals()",
)
EXPECTED_TRIGGER_REMOVED_ROWS: tuple[str, ...] = (
    "trigger_binding|gaming_sessions|trg_gaming_sessions_guard_addon_cancel|O|false|false|CREATE TRIGGER trg_gaming_sessions_guard_addon_cancel BEFORE UPDATE OF status ON gaming_sessions FOR EACH ROW EXECUTE FUNCTION guard_gaming_session_cancel_with_addons()",
)
PRIOR_TRIGGER_CATALOG_SHA256: str | None = (
    "05d57780194986ea57334f9cfa5313bd6ebb10f1e3755c4067b64e4d12d6f7e5"
)

# Only these five reviewed full strings may differ after pg_restore.
# Live migration post-proof never uses these aliases. Raw evidence is retained.
RESTORE_CONSTRAINT_PAIRS = (
    (
        "constraint|companies|ck_company_google_sheets_export_mode|CHECK (google_sheets_export_mode::text = ANY (ARRAY['legacy_transactions'::character varying, 'daily_collections'::character varying]::text[]))",
        "constraint|companies|ck_company_google_sheets_export_mode|CHECK (google_sheets_export_mode::text = ANY (ARRAY['legacy_transactions'::character varying::text, 'daily_collections'::character varying::text]))",
    ),
    (
        "constraint|customer_gaming_rank_awards|ck_customer_gaming_rank_award_key|CHECK (rank_key::text = ANY (ARRAY['rookie'::character varying, 'player'::character varying, 'pro'::character varying, 'legend'::character varying]::text[]))",
        "constraint|customer_gaming_rank_awards|ck_customer_gaming_rank_award_key|CHECK (rank_key::text = ANY (ARRAY['rookie'::character varying::text, 'player'::character varying::text, 'pro'::character varying::text, 'legend'::character varying::text]))",
    ),
    (
        "constraint|customer_gaming_rank_awards|ck_customer_gaming_rank_award_source|CHECK (source::text = ANY (ARRAY['legacy_baseline'::character varying, 'paid_gaming'::character varying]::text[]))",
        "constraint|customer_gaming_rank_awards|ck_customer_gaming_rank_award_source|CHECK (source::text = ANY (ARRAY['legacy_baseline'::character varying::text, 'paid_gaming'::character varying::text]))",
    ),
    (
        "constraint|points_redemptions|ck_points_redemption_kind|CHECK (redemption_kind::text = ANY (ARRAY['generic'::character varying, 'fixed_reward'::character varying, 'legacy_frozen'::character varying]::text[]))",
        "constraint|points_redemptions|ck_points_redemption_kind|CHECK (redemption_kind::text = ANY (ARRAY['generic'::character varying::text, 'fixed_reward'::character varying::text, 'legacy_frozen'::character varying::text]))",
    ),
    (
        "constraint|daily_tender_reconciliations|daily_tender_reconciliations_kind_check|CHECK (kind::text = ANY (ARRAY['applied'::character varying, 'unresolved'::character varying]::text[]))",
        "constraint|daily_tender_reconciliations|daily_tender_reconciliations_kind_check|CHECK (kind::text = ANY (ARRAY['applied'::character varying::text, 'unresolved'::character varying::text]))",
    ),
)

PRIOR_PROTECTED_SCOPES = (
    "backend",
    "android-native/app/src/main",
    "android-native/app/schemas",
    "android-native/app/build.gradle.kts",
    "android-native/app/gradle.lockfile",
    "android-native/build.gradle.kts",
    "android-native/settings.gradle.kts",
    "android-native/gradle.properties",
    "android-native/gradle/verification-metadata.xml",
    "infra/docker",
    "infra/caddy",
    "infra/nginx",
    ".dockerignore",
    ".env.production.example",
    "docker-compose.prod.yml",
    "infra/scripts/install-on-vm.sh",
    "infra/scripts/prepare-production-env.sh",
    "infra/scripts/generate-secrets.sh",
    "infra/scripts/validate-production-env.sh",
    "infra/scripts/verify-code30-2-post-cleanup-state.py",
    "infra/scripts/verify-code30-2-post-cleanup-state.sql",
    "infra/scripts/verify-build45-pending-state.sql",
    "infra/scripts/verify-build46-pending-compatibility.py",
    "infra/scripts/verify-build47-pending-compatibility.py",
    "infra/scripts/verify-build48-pending-compatibility.py",
    "infra/scripts/verify-build49-pending-compatibility.py",
    "infra/scripts/verify-build50-pending-compatibility.py",
    "infra/scripts/verify-build50-post-migration-state.py",
    "infra/scripts/verify-build51-post-migration-state.py",
    "infra/scripts/verify-build51-post-migration-state.sql",
    "infra/scripts/verify-build52-transition-state.sql",
    "ops/runtime_release_parity.py",
    "ops/verify_web_public_assets.py",
    "infra/scripts/verify-production-runtime-images.sh",
    "tests/test_caddy_dependency_security.py",
    "frontend/src",
    "frontend/public",
    "frontend/scripts",
    "frontend/.env.example",
    "frontend/package.json",
    "frontend/package-lock.json",
    "frontend/vite.config.ts",
    "infra/scripts/verify-build55-transition-state.sql",
    "infra/scripts/verify-build55-authenticated-acceptance.py",
    "infra/scripts/capture-build55-runtime.py",
)
# The deployed54 closure retains its original scope/hash. Only55 expands it.
PROTECTED_SCOPES = (
    "backend",
    "android-native/app/src/main",
    "android-native/app/schemas",
    "android-native/app/build.gradle.kts",
    "android-native/app/gradle.lockfile",
    "android-native/build.gradle.kts",
    "android-native/settings.gradle.kts",
    "android-native/gradle.properties",
    "android-native/gradle/verification-metadata.xml",
    "infra/docker",
    "infra/caddy",
    "infra/nginx",
    ".dockerignore",
    ".env.production.example",
    "docker-compose.prod.yml",
    "infra/scripts/install-on-vm.sh",
    "infra/scripts/prepare-production-env.sh",
    "infra/scripts/generate-secrets.sh",
    "infra/scripts/validate-production-env.sh",
    "infra/scripts/verify-code30-2-post-cleanup-state.py",
    "infra/scripts/verify-code30-2-post-cleanup-state.sql",
    "infra/scripts/verify-build45-pending-state.sql",
    "infra/scripts/verify-build46-pending-compatibility.py",
    "infra/scripts/verify-build47-pending-compatibility.py",
    "infra/scripts/verify-build48-pending-compatibility.py",
    "infra/scripts/verify-build49-pending-compatibility.py",
    "infra/scripts/verify-build50-pending-compatibility.py",
    "infra/scripts/verify-build50-post-migration-state.py",
    "infra/scripts/verify-build51-post-migration-state.py",
    "infra/scripts/verify-build51-post-migration-state.sql",
    "infra/scripts/verify-build52-transition-state.sql",
    "ops/runtime_release_parity.py",
    "ops/verify_web_public_assets.py",
    "infra/scripts/verify-production-runtime-images.sh",
    "tests/test_caddy_dependency_security.py",
    "frontend/src",
    "frontend/public",
    "frontend/scripts",
    "frontend/.env.example",
    "frontend/package.json",
    "frontend/package-lock.json",
    "frontend/vite.config.ts",
    "infra/scripts/verify-build55-transition-state.sql",
    "infra/scripts/verify-build55-authenticated-acceptance.py",
    "infra/scripts/capture-build55-runtime.py",
    "infra/scripts/bootstrap-verified-pip.py",
    "infra/scripts/install-operations-monitor.sh",
)
MAX_DOCUMENT_BYTES = 16 * 1024 * 1024
SHA256 = re.compile(r"[a-f0-9]{64}")
NAME = re.compile(r"[a-z][a-z0-9_]{0,62}")
POLICY_TABLE = "android_update_policy"
NEW_TABLES = frozenset(
    (POLICY_TABLE, "gaming_void_receipts", "gaming_void_addon_lines")
)


class CompatibilityError(ValueError):
    pass


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise CompatibilityError("duplicate JSON key")
        result[key] = value
    return result


def decode(raw: bytes) -> dict:
    if not raw or len(raw) > MAX_DOCUMENT_BYTES:
        raise CompatibilityError("evidence size is invalid")
    try:
        value = json.loads(
            raw,
            object_pairs_hook=_pairs,
            parse_constant=lambda _: (_ for _ in ()).throw(
                CompatibilityError("nonfinite JSON")
            ),
        )
    except (UnicodeDecodeError, ValueError, RecursionError) as exc:
        raise CompatibilityError("evidence is not strict JSON") from exc
    if not isinstance(value, dict):
        raise CompatibilityError("evidence must be an object")
    return value


def private_file(path: Path) -> bytes:
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        metadata = os.fstat(descriptor)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != 0
            or stat.S_IMODE(metadata.st_mode) not in (0o400, 0o600)
            or metadata.st_nlink != 1
            or metadata.st_size > MAX_DOCUMENT_BYTES
        ):
            raise CompatibilityError(
                "evidence must be bounded root-owned private regular data"
            )
        return os.read(descriptor, MAX_DOCUMENT_BYTES + 1)
    finally:
        os.close(descriptor)


def inventory(root: Path, scopes: tuple[str, ...]) -> dict[str, str]:
    result = {}
    for scope in scopes:
        target = root / scope
        if not target.exists() and not target.is_symlink():
            continue
        paths = [target] if not target.is_dir() else sorted(target.rglob("*"))
        if target.is_symlink():
            raise CompatibilityError("linked source")
        for path in paths:
            if path.is_symlink():
                raise CompatibilityError("linked source")
            if not path.is_file() and not path.is_dir():
                raise CompatibilityError("nonregular source entry")
            if path.is_file():
                name = path.relative_to(root).as_posix()
                if name in result:
                    raise CompatibilityError("overlapping source scopes")
                result[name] = sha(path.read_bytes())
    return result


def inventory_hash(values: dict[str, str]) -> str:
    return sha(
        "".join(
            f"{name}\0{digest}\n" for name, digest in sorted(values.items())
        ).encode()
    )


def verify_inventory(
    document: dict, head: str, *, require_production_schema: bool = True
) -> None:
    fields = {
        "schema_version",
        "kind",
        "database_revision",
        "database_identity",
        "tables",
        "sequences",
        "schema_catalog_row_count",
        "schema_catalog_sha256",
        "schema_catalog_rows",
        "gaming_sessions_legacy_projection",
        "trigger_catalog_rows",
        "trigger_catalog_row_count",
        "trigger_catalog_sha256",
    }
    if (
        set(document) != fields
        or type(document["schema_version"]) is not int
        or document["schema_version"] != 2
        or document["kind"] != "build55-full-transition-v2"
        or document["database_revision"] != head
    ):
        raise CompatibilityError("full transition evidence contract changed")
    identity = document["database_identity"]
    if not isinstance(identity, dict) or set(identity) != {
        "database_name",
        "database_oid",
        "system_identifier",
    }:
        raise CompatibilityError("database identity is missing")
    if not isinstance(identity["database_name"], str) or not re.fullmatch(
        r"[A-Za-z0-9_]{1,63}", identity["database_name"]
    ):
        raise CompatibilityError("database name is invalid")
    if (
        type(identity["database_oid"]) is not int
        or identity["database_oid"] <= 0
        or not re.fullmatch(r"[1-9][0-9]{0,19}", str(identity["system_identifier"]))
    ):
        raise CompatibilityError("database lineage is invalid")
    tables = document["tables"]
    if not isinstance(tables, dict) or not tables:
        raise CompatibilityError("full table inventory missing")
    for name, row in tables.items():
        if (
            not isinstance(name, str)
            or not NAME.fullmatch(name)
            or not isinstance(row, dict)
            or set(row) != {"row_count", "row_sha256"}
            or type(row["row_count"]) is not int
            or row["row_count"] < 0
            or not SHA256.fullmatch(str(row["row_sha256"]))
        ):
            raise CompatibilityError("table inventory is malformed")
    legacy_names = sorted(set(tables) - NEW_TABLES)
    if sha("\n".join(legacy_names).encode()) != PRIOR_TABLE_NAMES_SHA256:
        raise CompatibilityError("legacy public table inventory changed")
    if tables.get("alembic_version") != {
        "row_count": 1,
        "row_sha256": sha(json.dumps({"version_num": head}).encode()),
    }:
        raise CompatibilityError("Alembic history is not exactly one reviewed revision")
    if head == PRIOR_DATABASE_HEAD and NEW_TABLES.intersection(tables):
        raise CompatibilityError("new candidate table exists before migration")
    if head == CANDIDATE_DATABASE_HEAD and any(
        tables.get(name)
        != {
            "row_count": 0,
            "row_sha256": EMPTY_SHA256,
        }
        for name in NEW_TABLES
    ):
        raise CompatibilityError(
            "new candidate tables must be empty before normal writers"
        )
    projection = document["gaming_sessions_legacy_projection"]
    if (
        not isinstance(projection, dict)
        or set(projection)
        != {"column_present", "row_count", "row_sha256", "nonnull_count"}
        or type(projection["column_present"]) is not bool
        or projection["column_present"] != (head == CANDIDATE_DATABASE_HEAD)
        or type(projection["row_count"]) is not int
        or type(projection["nonnull_count"]) is not int
        or projection["nonnull_count"] != 0
        or not SHA256.fullmatch(str(projection["row_sha256"]))
        or "gaming_sessions" not in tables
        or projection["row_count"] != tables["gaming_sessions"]["row_count"]
    ):
        raise CompatibilityError(
            "Gaming legacy projection or new retained link changed"
        )
    if (
        head == PRIOR_DATABASE_HEAD
        and projection["row_sha256"] != tables["gaming_sessions"]["row_sha256"]
    ):
        raise CompatibilityError("predecessor Gaming projection differs from full rows")
    trigger_rows = document["trigger_catalog_rows"]
    if (
        not isinstance(trigger_rows, list)
        or not trigger_rows
        or not all(
            isinstance(row, str) and row.startswith("trigger_binding|")
            for row in trigger_rows
        )
        or trigger_rows != sorted(set(trigger_rows))
        or type(document["trigger_catalog_row_count"]) is not int
        or document["trigger_catalog_row_count"] != len(trigger_rows)
        or document["trigger_catalog_sha256"] != sha("\n".join(trigger_rows).encode())
    ):
        raise CompatibilityError("complete trigger catalogue is malformed")
    if (
        head == PRIOR_DATABASE_HEAD
        and require_production_schema
        and (
            not PRIOR_TRIGGER_CATALOG_SHA256
            or document["trigger_catalog_sha256"] != PRIOR_TRIGGER_CATALOG_SHA256
        )
    ):
        raise CompatibilityError(
            "deployed54 trigger bindings are not independently reviewed"
        )
    sequences = document["sequences"]
    expected_sequence = {
        "last_value",
        "is_called",
        "start_value",
        "increment_by",
        "min_value",
        "max_value",
        "cache_size",
        "cycle",
        "owned_by",
    }
    if not isinstance(sequences, dict) or not sequences:
        raise CompatibilityError("sequence inventory missing")
    for name, row in sequences.items():
        if (
            not isinstance(name, str)
            or not NAME.fullmatch(name)
            or not isinstance(row, dict)
            or set(row) != expected_sequence
            or type(row["is_called"]) is not bool
            or type(row["cycle"]) is not bool
            or not isinstance(row["owned_by"], str)
            or any(
                type(row[key]) is not int
                for key in expected_sequence - {"is_called", "cycle", "owned_by"}
            )
        ):
            raise CompatibilityError("sequence inventory is malformed")
    rows = document["schema_catalog_rows"]
    if (
        not isinstance(rows, list)
        or not all(isinstance(row, str) for row in rows)
        or rows != sorted(set(rows))
    ):
        raise CompatibilityError("schema catalogue is unordered or duplicated")
    if (
        type(document["schema_catalog_row_count"]) is not int
        or document["schema_catalog_row_count"] != len(rows)
        or document["schema_catalog_sha256"] != sha("\n".join(rows).encode())
    ):
        raise CompatibilityError("schema catalogue checksum changed")
    if (
        head == PRIOR_DATABASE_HEAD
        and require_production_schema
        and document["schema_catalog_sha256"] != PRIOR_SCHEMA_SHA256
    ):
        raise CompatibilityError("deployed54 schema is not the reviewed predecessor")


def verify_prior(document: dict) -> None:
    """Production admission is separate from generic preservation comparisons."""
    verify_inventory(document, PRIOR_DATABASE_HEAD)
    if document["database_identity"] != PRIOR_DATABASE_IDENTITY:
        raise CompatibilityError("not the reviewed production database identity")
    if document["schema_catalog_row_count"] != PRIOR_SCHEMA_ROW_COUNT:
        raise CompatibilityError("reviewed production schema row count changed")


def canonical_uuid(value: object) -> bool:
    try:
        return isinstance(value, str) and str(UUID(value)) == value
    except ValueError:
        return False


def utc_timestamp(value: object, *, nullable: bool = False) -> str | None:
    if value is None and nullable:
        return None
    if not isinstance(value, str) or not re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z", value
    ):
        raise CompatibilityError("fleet UTC timestamp is malformed")
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise CompatibilityError("fleet UTC timestamp is invalid") from exc
    return value


def verify_fleet(document: dict, head: str) -> None:
    expected_fields = {
        "schema_version",
        "database_revision",
        "pending_outbox_count",
        "nonzero_installations",
        "installation_row_count",
        "nonzero_installation_count",
        "all_installations_identity_sha256",
        "nonzero_installations_identity_sha256",
        "client_installation_guard_trigger_count",
        "client_installation_guard_function_sha256",
        "client_installation_guard_trigger_definition_sha256",
        "client_installation_guard_function_definition_sha256",
    }
    if (
        set(document) != expected_fields
        or type(document.get("schema_version")) is not int
        or document["schema_version"] != 1
        or document["database_revision"] != head
    ):
        raise CompatibilityError("fleet identity changed")
    if (
        document["all_installations_identity_sha256"]
        != ALL_INSTALLATIONS_IDENTITY_SHA256
    ):
        raise CompatibilityError("full installation identity set changed")
    for key in (
        "pending_outbox_count",
        "nonzero_installation_count",
        "installation_row_count",
        "client_installation_guard_trigger_count",
    ):
        if type(document[key]) is not int or document[key] < 0:
            raise CompatibilityError("fleet counters are malformed")
    if (
        document["installation_row_count"] != 13
        or document["client_installation_guard_trigger_count"] != 1
        or document["client_installation_guard_function_definition_sha256"]
        != FLEET_GUARD_SHA256
    ):
        raise CompatibilityError("fleet identity or database write guard changed")
    for key in (
        "nonzero_installations_identity_sha256",
        "client_installation_guard_function_sha256",
        "client_installation_guard_trigger_definition_sha256",
    ):
        if not isinstance(document[key], str) or not SHA256.fullmatch(document[key]):
            raise CompatibilityError("fleet guard checksum is malformed")
    rows = document["nonzero_installations"]
    if not isinstance(rows, list):
        raise CompatibilityError("raw fleet rows are malformed")
    seen, total, row_hashes = set(), 0, []
    required = {
        "row_id",
        "installation_id",
        "company_id",
        "terminal_id",
        "platform",
        "version_name",
        "version_code",
        "pending_outbox_count",
        "last_seen_at",
        "last_successful_sync_at",
        "identity_sha256",
    }
    for row in rows:
        if (
            not isinstance(row, dict)
            or set(row) != required
            or any(
                not canonical_uuid(row[key])
                for key in ("row_id", "installation_id", "company_id")
            )
            or (
                row["terminal_id"] is not None
                and not canonical_uuid(row["terminal_id"])
            )
            or row["row_id"] in seen
            or row["platform"] != "android"
            or type(row["version_code"]) is not int
            or type(row["pending_outbox_count"]) is not int
            or row["pending_outbox_count"] <= 0
        ):
            raise CompatibilityError("opaque pending fleet row changed")
        if {37: "3.1.29", 41: "3.1.33", 54: "3.1.46"}.get(row["version_code"]) != row[
            "version_name"
        ]:
            raise CompatibilityError(
                "pending installation has an unreviewed client contract"
            )
        last_sync = utc_timestamp(row["last_successful_sync_at"], nullable=True)
        last_seen = utc_timestamp(row["last_seen_at"])
        # PostgreSQL concat_ws skips a NULL last-success timestamp, but its
        # terminal expression explicitly coalesces NULL to an empty string.
        parts = [
            row["row_id"],
            row["installation_id"],
            row["company_id"],
            row["terminal_id"] or "",
            row["platform"],
            row["version_name"],
            str(row["version_code"]),
            str(row["pending_outbox_count"]),
            last_sync,
            last_seen,
        ]
        digest = sha(chr(31).join(part for part in parts if part is not None).encode())
        if row["identity_sha256"] != digest:
            raise CompatibilityError("raw pending identity checksum changed")
        seen.add(row["row_id"])
        total += row["pending_outbox_count"]
        row_hashes.append((row["row_id"], digest))
    if (
        len(rows) != document["nonzero_installation_count"]
        or len(rows) > document["installation_row_count"]
        or total != document["pending_outbox_count"]
        or document["nonzero_installations_identity_sha256"]
        != sha("\n".join(digest for _, digest in sorted(row_hashes)).encode())
    ):
        raise CompatibilityError("every nonzero reported queue must be retained")


def restore_catalogue(rows: list[str]) -> list[str]:
    aliases = {
        variant: live
        for live, restored in RESTORE_CONSTRAINT_PAIRS
        for variant in (live, restored)
    }
    identities = {
        "|".join(live.split("|")[:3]) + "|" for live, _ in RESTORE_CONSTRAINT_PAIRS
    }
    seen = set()
    normalized = []
    for row in rows:
        identity = next(
            (prefix for prefix in identities if row.startswith(prefix)), None
        )
        if identity is not None:
            if identity in seen or row not in aliases:
                raise CompatibilityError(
                    "restored reviewed CHECK is missing, duplicated or changed"
                )
            seen.add(identity)
        normalized.append(aliases.get(row, row))
    return sorted(normalized)


def exact_catalogue_delta(
    before: list[str], removed: tuple[str, ...], added: tuple[str, ...]
) -> list[str]:
    if (
        not removed
        or not added
        or len(set(removed)) != len(removed)
        or len(set(added)) != len(added)
        or not set(removed).issubset(before)
        or set(added).intersection(set(before) - set(removed))
    ):
        raise CompatibilityError(
            "schema delta is unreviewed or does not match the predecessor"
        )
    return sorted((set(before) - set(removed)) | set(added))


def verify_pair(
    before: dict, after: dict, *, mode: str, writers_started: bool = False
) -> dict:
    if mode not in {"post", "restore", "rollback"}:
        raise CompatibilityError("unknown preservation phase")
    if writers_started:
        raise CompatibilityError(
            "old database restoration is forbidden after application writers start"
        )
    after_head = CANDIDATE_DATABASE_HEAD if mode == "post" else PRIOR_DATABASE_HEAD
    # This comparator proves preservation, not deployment admission. Fresh QA
    # schemas can have different historical ordinals/deparser representations.
    # Every CLI phase separately admits its immutable production baseline first.
    verify_inventory(before, PRIOR_DATABASE_HEAD, require_production_schema=False)
    verify_inventory(after, after_head, require_production_schema=False)
    if mode == "post" and before["database_identity"] != after["database_identity"]:
        raise CompatibilityError("in-place database identity changed")
    if mode == "restore" and (
        before["database_identity"]["system_identifier"]
        != after["database_identity"]["system_identifier"]
        or after["database_identity"]["database_name"]
        == before["database_identity"]["database_name"]
        or not after["database_identity"]["database_name"].startswith(
            "code17_restore_verify_"
        )
    ):
        raise CompatibilityError(
            "restore proof must use an owned disposable database in the same cluster"
        )
    if mode == "rollback" and any(
        before["database_identity"][key] != after["database_identity"][key]
        for key in ("database_name", "system_identifier")
    ):
        raise CompatibilityError("restored database lineage changed")
    expected = dict(before["tables"])
    if mode == "post":
        expected.update(
            {name: {"row_count": 0, "row_sha256": EMPTY_SHA256} for name in NEW_TABLES}
        )
        expected["alembic_version"] = {
            "row_count": 1,
            "row_sha256": sha(json.dumps({"version_num": after_head}).encode()),
        }
        projection = after["gaming_sessions_legacy_projection"]
        if {key: projection[key] for key in ("row_count", "row_sha256")} != before[
            "tables"
        ]["gaming_sessions"]:
            raise CompatibilityError("full legacy Gaming session history changed")
        # The raw post hash includes the newly added NULL field. Keep that raw
        # evidence; compare every old field via the explicit, zero-link projection.
        expected["gaming_sessions"] = after["tables"]["gaming_sessions"]
        if (
            exact_catalogue_delta(
                before["schema_catalog_rows"],
                EXPECTED_0093_SCHEMA_REMOVED_ROWS,
                EXPECTED_POLICY_SCHEMA_ROWS + EXPECTED_0093_SCHEMA_ADDED_ROWS,
            )
            != after["schema_catalog_rows"]
        ):
            raise CompatibilityError(
                "migration differs from the exact reviewed0092/0093 schema"
            )
        if (
            exact_catalogue_delta(
                before["trigger_catalog_rows"],
                EXPECTED_TRIGGER_REMOVED_ROWS,
                EXPECTED_TRIGGER_ADDED_ROWS,
            )
            != after["trigger_catalog_rows"]
        ):
            raise CompatibilityError("migration changed reviewed trigger bindings")
    elif restore_catalogue(before["schema_catalog_rows"]) != restore_catalogue(
        after["schema_catalog_rows"]
    ):
        raise CompatibilityError("restored public schema changed")
    if mode != "post" and (
        before["trigger_catalog_rows"] != after["trigger_catalog_rows"]
        or before["gaming_sessions_legacy_projection"]
        != after["gaming_sessions_legacy_projection"]
    ):
        raise CompatibilityError(
            "restored trigger bindings or Gaming projection changed"
        )
    if expected != after["tables"] or before["sequences"] != after["sequences"]:
        raise CompatibilityError(
            "full-row Audit, finance, business, fleet or sequence history changed"
        )
    return {
        "preserved_legacy_table_count": len(before["tables"]),
        "full_audit_finance_rows_preserved": True,
        "database_identity_preserved": mode == "post",
        "logical_restore_verified": mode in {"restore", "rollback"},
    }


def environment(values: object) -> dict[str, str]:
    if not isinstance(values, list) or not all(
        isinstance(value, str) and "=" in value for value in values
    ):
        raise CompatibilityError("runtime environment is malformed")
    result = {}
    for value in values:
        key, content = value.split("=", 1)
        if not re.fullmatch(r"[A-Z_][A-Z0-9_]*", key) or key in result:
            raise CompatibilityError(
                "runtime environment has duplicate or invalid keys"
            )
        result[key] = content
    return result


PRIOR_PYTHON_VERSION = "3.14.8"
PRIOR_PYTHON_SHA256 = "c2215904f02b175596dc49351585104f4bc20341e1c47378b26a2c274360ce73"
CANDIDATE_PYTHON_VERSION = "3.14.10"
CANDIDATE_PYTHON_SHA256 = "de414a57119abef52784d8993e341d53aed635fc2b05c2607d9f98a2c5b72032"


def candidate_backend_environment(before: dict[str, str], revision: str) -> dict[str, str]:
    """Admit only this release identity and the reviewed CPython security patch."""
    if (
        before.get("APP_VERSION") != PRIOR_VERSION
        or before.get("APP_REVISION") != PRIOR_REVISION
        or before.get("PYTHON_VERSION") != PRIOR_PYTHON_VERSION
        or before.get("PYTHON_SHA256") != PRIOR_PYTHON_SHA256
        or "LANG" in before
        or not isinstance(revision, str)
        or not re.fullmatch(r"[a-f0-9]{40}", revision)
    ):
        raise CompatibilityError("reviewed prior backend Python or release identity changed")
    return {
        **before,
        "APP_VERSION": CANDIDATE_VERSION,
        "APP_REVISION": revision,
        "PYTHON_VERSION": CANDIDATE_PYTHON_VERSION,
        "PYTHON_SHA256": CANDIDATE_PYTHON_SHA256,
        "LANG": "C.UTF-8",
    }


def candidate_image_environment(image_values: object, overrides: object, *, service: str) -> dict[str, str]:
    effective = environment(image_values)
    if not isinstance(overrides, dict) or not all(
        isinstance(k, str) and isinstance(v, str) for k, v in overrides.items()
    ):
        raise CompatibilityError("candidate effective environment is malformed")
    if service == "backend" and {"PATH", "PYTHON_VERSION", "PYTHON_SHA256", "LANG"} & overrides.keys():
        raise CompatibilityError("candidate Compose masks Python image metadata")
    effective.update(overrides)
    return effective


def verify_runtime_journal(document: dict) -> dict:
    if (
        set(document) != {"schema_version", "containers", "configuration_files"}
        or type(document["schema_version"]) is not int
        or document["schema_version"] != 1
        or not isinstance(document["containers"], list)
    ):
        raise CompatibilityError("runtime journal contract changed")
    found = {}
    for row in document["containers"]:
        try:
            service = row["Config"]["Labels"]["com.docker.compose.service"]
            env = row["Config"]["Env"]
        except (TypeError, KeyError) as exc:
            raise CompatibilityError("runtime identity missing") from exc
        if (
            service not in SERVICES
            or service in found
            or row.get("Image") != PRIOR_IMAGES[service]
        ):
            raise CompatibilityError("exact prior five-image identity changed")
        environment(env)
        command = [
            row["Config"].get("Entrypoint"),
            row["Config"].get("Cmd"),
            row["Config"].get("User"),
        ]
        if (
            sha(json.dumps(command, separators=(",", ":")).encode())
            != PRIOR_COMMAND_SHA256[service]
        ):
            raise CompatibilityError("prior executable/user contract changed")
        if (
            sha(json.dumps(sorted(env), separators=(",", ":")).encode())
            != PRIOR_ENVIRONMENT_SHA256[service]
        ):
            raise CompatibilityError("effective prior runtime environment changed")
        if service == "backend" and (
            row["Config"].get("Entrypoint") != BACKEND_UVICORN_ENTRYPOINT
            or row["Config"].get("Cmd") != []
            or row["Config"].get("User") != "erp"
        ):
            raise CompatibilityError("prior operational backend command changed")
        found[service] = row
    if set(found) != set(SERVICES):
        raise CompatibilityError("all five actual prior services must be journalled")
    files = document["configuration_files"]
    if not isinstance(files, list) or len(files) != 2:
        raise CompatibilityError(
            "actual prior Compose and operational override must both be journalled"
        )
    recorded_paths = {item.get("path") for item in files}
    actual_paths = {
        path
        for row in found.values()
        for path in row["Config"]["Labels"]
        .get("com.docker.compose.project.config_files", "")
        .split(",")
    }
    if recorded_paths != actual_paths or len(recorded_paths) != 2:
        raise CompatibilityError("journal does not bind every actual Compose override")
    if {item.get("sha256") for item in files} != {
        PRIOR_COMPOSE_SHA256,
        PRIOR_OPERATIONAL_OVERRIDE_SHA256,
    } or any(
        item.get("uid") != 0
        or item.get("mode") != 0o600
        or item.get("regular_nonlinked") is not True
        for item in files
    ):
        raise CompatibilityError("actual runtime configuration file changed")
    return found


def rollback_override(document: dict) -> dict:
    found = verify_runtime_journal(document)
    backend_env = environment(found["backend"]["Config"]["Env"])
    # Only this reviewed fixed executable and one inert message setting differ
    # from the immutable54 Compose source. Never execute captured command data.
    return {
        "services": {
            "backend": {
                "entrypoint": BACKEND_UVICORN_ENTRYPOINT,
                "command": [],
                "environment": {
                    "CLIENT_UPDATE_MESSAGE": backend_env[
                        "CLIENT_UPDATE_MESSAGE"
                    ].replace("$", "$$")
                },
            }
        }
    }


def verify_pre_bindings(args) -> None:
    identity = decode(private_file(args.identity))
    expected_fields = {
        "prior_revision",
        "prior_version",
        "prior_database_head",
        "candidate_revision",
        "candidate_version",
        "candidate_database_head",
        "prior_source_archive_sha256",
        "candidate_source_archive_sha256",
        "prior_backend_manifest_sha256",
        "candidate_backend_manifest_sha256",
        "prior_images",
        "candidate_images",
    }
    if set(identity) != expected_fields:
        raise CompatibilityError("release identity contract changed")
    required = {
        "prior_revision": PRIOR_REVISION,
        "prior_version": PRIOR_VERSION,
        "prior_database_head": PRIOR_DATABASE_HEAD,
        "candidate_version": CANDIDATE_VERSION,
        "candidate_database_head": CANDIDATE_DATABASE_HEAD,
        "prior_source_archive_sha256": PRIOR_SOURCE_ARCHIVE_SHA256,
        "prior_backend_manifest_sha256": PRIOR_BACKEND_MANIFEST_SHA256,
        "candidate_backend_manifest_sha256": CANDIDATE_BACKEND_MANIFEST_SHA256,
        "prior_images": PRIOR_IMAGES,
    }
    if any(identity.get(key) != value for key, value in required.items()):
        raise CompatibilityError("unsupported or unfrozen release identity")
    revision = identity["candidate_revision"]
    if (
        not re.fullmatch(r"[a-f0-9]{40}", str(revision))
        or revision == PRIOR_REVISION
        or not SHA256.fullmatch(str(identity["candidate_source_archive_sha256"]))
        or identity["candidate_source_archive_sha256"] == PRIOR_SOURCE_ARCHIVE_SHA256
    ):
        raise CompatibilityError("candidate identity is incomplete")
    images = identity["candidate_images"]
    if (
        not isinstance(images, dict)
        or set(images) != set(SERVICES)
        or any(
            not re.fullmatch(r"sha256:[a-f0-9]{64}", str(value))
            or value == PRIOR_IMAGES[key]
            for key, value in images.items()
        )
    ):
        raise CompatibilityError(
            "five immutable candidate images were not independently attested"
        )
    for source_root, scopes, expected_hash, expected_backend in (
        (
            args.prior_source_root,
            PRIOR_PROTECTED_SCOPES,
            PRIOR_PROTECTED_SOURCE_SHA256,
            PRIOR_BACKEND_MANIFEST_SHA256,
        ),
        (
            args.candidate_source_root,
            PROTECTED_SCOPES,
            CANDIDATE_PROTECTED_SOURCE_SHA256,
            CANDIDATE_BACKEND_MANIFEST_SHA256,
        ),
    ):
        if (
            source_root.is_symlink()
            or not source_root.is_dir()
            or source_root.stat().st_uid != 0
            or stat.S_IMODE(source_root.stat().st_mode) != 0o700
        ):
            raise CompatibilityError("source snapshot must be private and root-owned")
        if inventory_hash(inventory(source_root, scopes)) != expected_hash:
            raise CompatibilityError(
                "protected source changed after independent review"
            )
        backend = inventory(source_root, ("backend",))
        manifest = "".join(
            f"{digest}\t{name.removeprefix('backend/')}\n"
            for name, digest in sorted(backend.items())
        ).encode()
        if sha(manifest) != expected_backend:
            raise CompatibilityError("backend image/source manifest changed")
    if args.prior_source_root.resolve() == args.candidate_source_root.resolve():
        raise CompatibilityError(
            "prior and candidate source snapshots must be distinct"
        )
    prior = private_file(args.prior_env)
    if prior != private_file(args.live_env):
        raise CompatibilityError("live environment changed after rollback capture")
    candidate = private_file(args.candidate_env)
    replacements = {
        b"APP_VERSION": (PRIOR_VERSION.encode(), CANDIDATE_VERSION.encode()),
        b"APP_REVISION": (PRIOR_REVISION.encode(), revision.encode()),
    }
    normalized = candidate
    for key, (old, new) in replacements.items():
        pattern = rb"(?m)^" + key + rb"=" + re.escape(new) + rb"$"
        normalized, count = re.subn(pattern, key + b"=" + old, normalized)
        if count != 1:
            raise CompatibilityError(
                "candidate environment has invalid release identity"
            )
    if normalized != prior:
        raise CompatibilityError(
            "environment bytes changed beyond reviewed release identity"
        )
    runtime = verify_runtime_journal(decode(private_file(args.runtime_journal)))
    image_records = decode(private_file(args.candidate_image_inspect))
    compose = decode(private_file(args.candidate_compose_config))
    if set(image_records) != set(SERVICES):
        raise CompatibilityError("candidate image runtime inventory is incomplete")
    for service in SERVICES:
        image = image_records[service]
        config = compose.get("services", {}).get(service)
        if (
            not isinstance(config, dict)
            or image.get("Id") != images[service]
            or config.get("image") != f"d-company-erp-{service}:{revision}"
        ):
            raise CompatibilityError("candidate Compose/image identity changed")
        labels = image.get("Config", {}).get("Labels", {})
        if (
            labels.get("org.opencontainers.image.revision") != revision
            or labels.get("org.opencontainers.image.version") != CANDIDATE_VERSION
        ):
            raise CompatibilityError(
                "candidate image labels are not the coordinated release"
            )
        if service == "backend" and "env_file" in config:
            raise CompatibilityError("candidate Compose masks Python image metadata")
        effective = candidate_image_environment(
            image.get("Config", {}).get("Env"), config.get("environment", {}), service=service,
        )
        before = environment(runtime[service]["Config"]["Env"])
        expected = candidate_backend_environment(before, revision) if service == "backend" else before
        if effective != expected:
            raise CompatibilityError(
                "effective runtime environment changed beyond reviewed identity and Python patch"
            )
        executable = [
            config.get("entrypoint", image["Config"].get("Entrypoint")),
            config.get("command", image["Config"].get("Cmd")),
            config.get("user", image["Config"].get("User")),
        ]
        if (
            sha(json.dumps(executable, separators=(",", ":")).encode())
            != PRIOR_COMMAND_SHA256[service]
        ):
            raise CompatibilityError(
                "candidate executable/user drifted from reviewed operational runtime"
            )


def verify_migration_compose(base: dict, pinned: dict, images: dict) -> None:
    from copy import deepcopy

    if set(images) != set(SERVICES) or any(
        not re.fullmatch(r"sha256:[a-f0-9]{64}", str(image))
        for image in images.values()
    ):
        raise CompatibilityError("all five migration image IDs are required")
    expected = deepcopy(base)
    if set(expected.get("services", {})) != set(SERVICES):
        raise CompatibilityError("migration Compose service inventory changed")
    for service, image in images.items():
        expected["services"][service]["image"] = image
    if pinned != expected:
        raise CompatibilityError(
            "migration override changed more than the five attested image IDs"
        )


def verify_running_runtime(
    journal: dict, actual: dict, identity: dict, *, prior: bool = False
) -> dict:
    before = verify_runtime_journal(journal)
    if set(actual) != set(SERVICES):
        raise CompatibilityError(
            "all five actual runtime records are required before ingress"
        )
    revision = identity.get("candidate_revision")
    if not prior and not re.fullmatch(r"[a-f0-9]{40}", str(revision)):
        raise CompatibilityError("candidate runtime revision is invalid")
    images = PRIOR_IMAGES if prior else identity.get("candidate_images", {})
    if set(images) != set(SERVICES):
        raise CompatibilityError("runtime image attestation is incomplete")
    for service in SERVICES:
        row = actual[service]
        config = row.get("Config", {}) if isinstance(row, dict) else {}
        if row.get("Image") != images[service]:
            raise CompatibilityError(
                "running image does not match its immutable attestation"
            )
        command = [config.get("Entrypoint"), config.get("Cmd"), config.get("User")]
        if (
            sha(json.dumps(command, separators=(",", ":")).encode())
            != PRIOR_COMMAND_SHA256[service]
        ):
            raise CompatibilityError("actual running executable/user changed")
        expected = environment(before[service]["Config"]["Env"])
        if not prior and service == "backend":
            expected = candidate_backend_environment(expected, revision)
        if environment(config.get("Env")) != expected:
            raise CompatibilityError("actual effective runtime environment changed")
    return {
        "status": "prior_runtime_verified" if prior else "candidate_runtime_verified",
        "service_count": len(SERVICES),
        "effective_environment_verified": True,
        "executable_user_verified": True,
        "tokens_recorded": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="phase", required=True)
    for mode in ("pre", "post", "rollback", "restore"):
        command = commands.add_parser(mode)
        command.add_argument("--pre-transition-state", type=Path, required=True)
        command.add_argument("--pre-fleet-state", type=Path, required=True)
        if mode == "pre":
            command.add_argument("--runtime-journal", type=Path, required=True)
            command.add_argument("--prior-source-root", type=Path, required=True)
            command.add_argument("--candidate-source-root", type=Path, required=True)
            command.add_argument("--identity", type=Path, required=True)
            command.add_argument("--prior-env", type=Path, required=True)
            command.add_argument("--candidate-env", type=Path, required=True)
            command.add_argument("--live-env", type=Path, required=True)
            command.add_argument("--candidate-image-inspect", type=Path, required=True)
            command.add_argument("--candidate-compose-config", type=Path, required=True)
        else:
            command.add_argument("--after-transition-state", type=Path, required=True)
            command.add_argument("--after-fleet-state", type=Path, required=True)
            command.add_argument("--writers-started", action="store_true")
            command.add_argument("--pre-transition-sha256", required=True)
            command.add_argument("--pre-fleet-sha256", required=True)
    runtime = commands.add_parser("runtime")
    runtime.add_argument("--runtime-journal", type=Path, required=True)
    migration = commands.add_parser("migration-runtime")
    migration.add_argument("--base-compose", type=Path, required=True)
    migration.add_argument("--pinned-compose", type=Path, required=True)
    migration.add_argument("--identity", type=Path, required=True)
    running = commands.add_parser("running-runtime")
    running.add_argument("--runtime-journal", type=Path, required=True)
    running.add_argument("--actual-runtime", type=Path, required=True)
    running.add_argument("--identity", type=Path, required=True)
    running.add_argument("--prior", action="store_true")
    args = parser.parse_args()
    try:
        if args.phase == "migration-runtime":
            identity = decode(private_file(args.identity))
            verify_migration_compose(
                decode(private_file(args.base_compose)),
                decode(private_file(args.pinned_compose)),
                identity.get("candidate_images", {}),
            )
            result = {"status": "five_migration_images_pinned"}
        elif args.phase == "running-runtime":
            result = verify_running_runtime(
                decode(private_file(args.runtime_journal)),
                decode(private_file(args.actual_runtime)),
                decode(private_file(args.identity)),
                prior=args.prior,
            )
        elif args.phase == "runtime":
            result = rollback_override(decode(private_file(args.runtime_journal)))
        else:
            if not CANDIDATE_RELEASE_FROZEN:
                raise CompatibilityError(
                    "Build55 independent source/schema freeze is incomplete"
                )
            before_raw = private_file(args.pre_transition_state)
            fleet_raw = private_file(args.pre_fleet_state)
            if args.phase != "pre" and (
                not SHA256.fullmatch(args.pre_transition_sha256)
                or not SHA256.fullmatch(args.pre_fleet_sha256)
                or sha(before_raw) != args.pre_transition_sha256
                or sha(fleet_raw) != args.pre_fleet_sha256
            ):
                raise CompatibilityError("sealed pre-migration baseline changed")
            before = decode(before_raw)
            fleet = decode(fleet_raw)
            verify_fleet(fleet, PRIOR_DATABASE_HEAD)
            # Never let post/restore/rollback bypass the production predecessor
            # pin merely because the generic pair comparator accepts fresh QA.
            verify_prior(before)
            if args.phase == "pre":
                verify_runtime_journal(decode(private_file(args.runtime_journal)))
                verify_pre_bindings(args)
                result = {
                    "status": "pre_migration_verified",
                    "pending_outbox_count": fleet["pending_outbox_count"],
                    "pre_transition_sha256": sha(before_raw),
                    "pre_fleet_sha256": sha(fleet_raw),
                }
            else:
                after = decode(private_file(args.after_transition_state))
                after_fleet = decode(private_file(args.after_fleet_state))
                expected_head = (
                    CANDIDATE_DATABASE_HEAD
                    if args.phase == "post"
                    else PRIOR_DATABASE_HEAD
                )
                verify_fleet(after_fleet, expected_head)
                expected_fleet = {**fleet, "database_revision": expected_head}
                if after_fleet != expected_fleet:
                    raise CompatibilityError(
                        "opaque fleet changed; no queue may be silently discarded"
                    )
                result = verify_pair(
                    before, after, mode=args.phase, writers_started=args.writers_started
                )
            result.update(
                {
                    "schema_version": 1,
                    "phase": args.phase,
                    "queue_drained": False,
                    "tablet_queue_contents_verified": False,
                    "telemetry_rewritten": False,
                    "historic_pending_work_classification": "unknown_preserved_work",
                }
            )
        print(json.dumps(result, sort_keys=True, separators=(",", ":")))
        return 0
    except (CompatibilityError, OSError, ValueError) as exc:
        print(f"Build55 transition refused: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
