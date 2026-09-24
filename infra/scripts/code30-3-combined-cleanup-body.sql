-- Shared, hash-pinned Code30.3 combined cleanup transaction body.
-- Included byte-for-byte by both the restore rehearsal and production apply wrappers.
-- The caller must already be inside a SERIALIZABLE transaction and must create
-- c3c_execution(execution_mode, expected_database_name).

DO $$
BEGIN
    IF (SELECT count(*) FROM c3c_execution) <> 1
       OR (SELECT execution_mode FROM c3c_execution) NOT IN ('rehearsal', 'apply')
       OR (SELECT expected_database_name FROM c3c_execution) IS DISTINCT FROM current_database()
       OR ((SELECT execution_mode FROM c3c_execution) = 'rehearsal'
           AND current_database() !~ '^code30_combined_restore_[0-9]+_[0-9]+$')
       OR ((SELECT execution_mode FROM c3c_execution) = 'apply'
           AND current_database() <> 'erp') THEN
        RAISE EXCEPTION 'cleanup wrapper mode or database identity is invalid';
    END IF;
END
$$;

DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT format('%I.%I', schemaname, tablename) AS name
               FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename
    LOOP
        EXECUTE format('LOCK TABLE %s IN ACCESS EXCLUSIVE MODE NOWAIT', r.name);
    END LOOP;
END
$$;

CREATE TEMP TABLE c3c_manifest (document jsonb NOT NULL) ON COMMIT DROP;
INSERT INTO c3c_manifest VALUES (pg_read_file(:'manifest_container_path')::jsonb);
CREATE TEMP TABLE c3c_inputs (
    backup_sha256 text NOT NULL,
    maintenance_sql_sha256 text NOT NULL,
    expected_state_fingerprint text NOT NULL,
    tablet_replay_evidence_sha256 text NOT NULL
) ON COMMIT DROP;
INSERT INTO c3c_inputs VALUES (
    :'backup_sha256', :'maintenance_sql_sha256', :'expected_state_fingerprint',
    encode(sha256(pg_read_binary_file(:'tablet_replay_evidence_container_path')), 'hex')
);
CREATE TEMP TABLE c3c_tablet_replay_evidence (document jsonb NOT NULL) ON COMMIT DROP;
INSERT INTO c3c_tablet_replay_evidence
VALUES (pg_read_file(:'tablet_replay_evidence_container_path')::jsonb);

DO $$
DECLARE revision text;
BEGIN
    SELECT version_num INTO revision FROM alembic_version;
    IF revision IS DISTINCT FROM '0082' THEN
        RAISE EXCEPTION 'combined cleanup backup must be at migration 0082, found %', revision;
    END IF;
    IF (SELECT document->>'tagged_app_source_git_sha' FROM c3c_manifest)
         <> 'ad5adfb93c3488f1f931ca27da53824aa57d3dc5' THEN
        RAISE EXCEPTION 'manifest is not bound to the immutable v3.1.30 merge';
    END IF;
    IF (SELECT document->>'backup_sha256' FROM c3c_manifest)
         <> (SELECT backup_sha256 FROM c3c_inputs)
       OR (SELECT document->>'maintenance_sql_sha256' FROM c3c_manifest)
         <> (SELECT maintenance_sql_sha256 FROM c3c_inputs)
    THEN
        RAISE EXCEPTION 'backup or maintenance SQL provenance does not match the manifest';
    END IF;
    IF (SELECT document->>'tablet_replay_evidence_sha256' FROM c3c_manifest)
         <> (SELECT tablet_replay_evidence_sha256 FROM c3c_inputs) THEN
        RAISE EXCEPTION 'tablet replay evidence hash does not match the manifest';
    END IF;
END
$$;

DO $$
DECLARE m jsonb := (SELECT document FROM c3c_manifest);
DECLARE e jsonb := (SELECT document FROM c3c_tablet_replay_evidence);
DECLARE later_shift_id uuid := (m->'evidence'->>'later_shift_id')::uuid;
DECLARE pinned_installation_id uuid;
DECLARE final_business_at timestamptz;
BEGIN
    SELECT opening_client_installation_id INTO pinned_installation_id
      FROM shifts WHERE id = later_shift_id AND status = 'closed' AND closed_at IS NOT NULL;
    IF pinned_installation_id IS NULL
       OR pinned_installation_id IS DISTINCT FROM (e->>'installation_id')::uuid
       OR EXISTS (SELECT 1 FROM orders WHERE shift_id = later_shift_id
                   AND (status NOT IN ('paid', 'void')
                        OR (status = 'paid' AND (closed_at IS NULL OR invoice_issued_at IS NULL))))
       OR EXISTS (SELECT 1 FROM gaming_sessions WHERE shift_id = later_shift_id
                   AND (status NOT IN ('ended', 'cancelled')
                        OR (status = 'ended' AND end_at IS NULL)
                        OR (status = 'cancelled' AND cancelled_at IS NULL))) THEN
        RAISE EXCEPTION 'tablet replay evidence is not bound to the closed later shift and final orders';
    END IF;
    SELECT max(event_at) INTO final_business_at FROM (
        SELECT closed_at AS event_at FROM shifts WHERE id = later_shift_id
        UNION ALL
        SELECT CASE WHEN status = 'paid' THEN greatest(closed_at, invoice_issued_at)
                    ELSE updated_at END
          FROM orders WHERE shift_id = later_shift_id
        UNION ALL
        SELECT p.paid_at FROM payments p JOIN orders o ON o.id = p.order_id
         WHERE o.shift_id = later_shift_id AND o.status = 'paid'
        UNION ALL
        SELECT l.voided_at FROM order_lines l JOIN orders o ON o.id = l.order_id
         WHERE o.shift_id = later_shift_id AND o.status = 'void' AND l.voided_at IS NOT NULL
        UNION ALL
        SELECT CASE WHEN status = 'ended' THEN greatest(end_at, sent_to_pos_at, updated_at)
                    ELSE greatest(cancelled_at, updated_at) END
          FROM gaming_sessions WHERE shift_id = later_shift_id
    ) events;
    IF final_business_at IS NULL
       OR final_business_at IS DISTINCT FROM (e->>'final_business_closed_at')::timestamptz THEN
        RAISE EXCEPTION 'tablet replay threshold differs from final shift/order settlement state';
    END IF;
    IF e->>'disposition' = 'synced' THEN
        IF (e->>'pending_outbox_count')::integer <> 0
           OR (e->>'last_successful_sync_at')::timestamptz
                < final_business_at
           OR (SELECT count(*) FROM client_installations
                WHERE company_id = (m->>'company_id')::uuid
                  AND installation_id = (e->>'installation_id')::uuid
                  AND pending_outbox_count = 0
                  AND last_successful_sync_at = (e->>'last_successful_sync_at')::timestamptz
                  AND last_seen_at = (e->>'observed_at')::timestamptz) <> 1 THEN
            RAISE EXCEPTION 'current tablet lacks exact post-closure zero-pending sync evidence';
        END IF;
    ELSE
        RAISE EXCEPTION 'only exact post-closure synced tablet replay evidence is currently accepted';
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION pg_temp.c3c_table_digest(target regclass)
RETURNS TABLE (row_count bigint, row_sha256 text)
LANGUAGE plpgsql AS $$
BEGIN
    RETURN QUERY EXECUTE format(
        'SELECT count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(t)::text, '
        'E''\n'' ORDER BY to_jsonb(t)::text), ''''), ''UTF8'')), ''hex'') FROM %s t',
        target
    );
END
$$;

CREATE TEMP TABLE c3c_target (
    table_name text NOT NULL,
    id uuid NOT NULL,
    row_sha256 text NOT NULL,
    PRIMARY KEY (table_name, id)
) ON COMMIT DROP;
INSERT INTO c3c_target
SELECT table_entry.key, (row_entry.value->>'id')::uuid, row_entry.value->>'row_sha256'
  FROM c3c_manifest m
 CROSS JOIN LATERAL jsonb_each(m.document->'targets') AS table_entry
 CROSS JOIN LATERAL jsonb_array_elements(table_entry.value) AS row_entry;

CREATE TEMP TABLE c3c_retained_rows (LIKE c3c_target INCLUDING ALL) ON COMMIT DROP;
INSERT INTO c3c_retained_rows
SELECT table_entry.key, (row_entry.value->>'id')::uuid, row_entry.value->>'row_sha256'
  FROM c3c_manifest m
 CROSS JOIN LATERAL jsonb_each(m.document->'retained_rows') AS table_entry
 CROSS JOIN LATERAL jsonb_array_elements(table_entry.value) AS row_entry;

CREATE TEMP TABLE c3c_reviewed_rows ON COMMIT DROP AS
SELECT * FROM c3c_target UNION ALL SELECT * FROM c3c_retained_rows;

DO $$
BEGIN
    IF (SELECT count(*) FROM c3c_reviewed_rows WHERE table_name = 'shifts') <> 6
       OR (SELECT count(*) FROM c3c_target WHERE table_name = 'shifts')
            <> (SELECT CASE document->>'later_cohort_decision'
                  WHEN 'delete' THEN 6 WHEN 'retain' THEN 5 ELSE -1 END
                  FROM c3c_manifest)
       OR EXISTS (SELECT 1 FROM c3c_target WHERE table_name NOT IN (
            'gaming_session_extensions', 'gaming_sessions', 'order_lines',
            'payments', 'orders', 'shifts'))
    THEN
        RAISE EXCEPTION 'manifest target table set or six-shift count changed';
    END IF;
END
$$;

-- The immutable original five-shift cohort remains exactly the 223 rows and
-- six aggregate hashes reviewed in the tagged cleanup SQL. New rows may only
-- extend the later explicitly classified cohort.
CREATE TEMP TABLE c3c_original_shifts (id uuid PRIMARY KEY) ON COMMIT DROP;
INSERT INTO c3c_original_shifts
SELECT value::uuid
  FROM c3c_manifest m
 CROSS JOIN LATERAL jsonb_array_elements_text(m.document->'evidence'->'original_five_shift_ids');
CREATE TEMP TABLE c3c_original_target (table_name text NOT NULL, id uuid NOT NULL,
                                       PRIMARY KEY (table_name, id)) ON COMMIT DROP;
INSERT INTO c3c_original_target SELECT 'shifts', id FROM c3c_original_shifts;
INSERT INTO c3c_original_target
SELECT 'orders', o.id FROM orders o JOIN c3c_target t ON t.table_name = 'orders' AND t.id = o.id
 WHERE o.shift_id IN (SELECT id FROM c3c_original_shifts);
INSERT INTO c3c_original_target
SELECT 'payments', p.id FROM payments p JOIN c3c_target t ON t.table_name = 'payments' AND t.id = p.id
 WHERE p.shift_id IN (SELECT id FROM c3c_original_shifts)
    OR p.order_id IN (SELECT id FROM c3c_original_target WHERE table_name = 'orders');
INSERT INTO c3c_original_target
SELECT 'gaming_sessions', g.id FROM gaming_sessions g JOIN c3c_target t ON t.table_name = 'gaming_sessions' AND t.id = g.id
 WHERE g.shift_id IN (SELECT id FROM c3c_original_shifts)
    OR g.order_id IN (SELECT id FROM c3c_original_target WHERE table_name = 'orders');
INSERT INTO c3c_original_target
SELECT 'order_lines', l.id FROM order_lines l JOIN c3c_target t ON t.table_name = 'order_lines' AND t.id = l.id
 WHERE l.order_id IN (SELECT id FROM c3c_original_target WHERE table_name = 'orders');
INSERT INTO c3c_original_target
SELECT 'gaming_session_extensions', x.id FROM gaming_session_extensions x
 JOIN c3c_target t ON t.table_name = 'gaming_session_extensions' AND t.id = x.id
 WHERE x.gaming_session_id IN (SELECT id FROM c3c_original_target WHERE table_name = 'gaming_sessions');

CREATE TEMP TABLE c3c_expected_original (
    table_name text PRIMARY KEY, row_count bigint NOT NULL, row_sha256 text NOT NULL
) ON COMMIT DROP;
INSERT INTO c3c_expected_original VALUES
    ('gaming_session_extensions', 12, '64db447aa9658479f8caf545b5afe2d4d10783a3350c002d341932a9bd5689a6'),
    ('gaming_sessions', 61, 'aaf62d66e40556dcca8a2a0f55d5b26b3dbb8a63813d4c94d5630055cda5aa2e'),
    ('order_lines', 49, 'ba8545e88af71f3fde0c26377724d6e2532aa59ebb52eb30c7cd0855ee5e9ec2'),
    ('orders', 49, '6aac359a52a303b0c71f6721fa33b7e08d1cb9afd2b46a9a10beb52f3a014e25'),
    ('payments', 47, 'ab636f53707c25072ab6598f0db857a184087c3588900f7683c084d16949193e'),
    ('shifts', 5, '1d4c8f58789ff72bcc6d73ae2baa13b2f64f02ff7e3060b1d16eaadfa602e275');
CREATE OR REPLACE FUNCTION pg_temp.c3c_original_digest(target_name text)
RETURNS TABLE (row_count bigint, row_sha256 text)
LANGUAGE plpgsql AS $$
BEGIN
    RETURN QUERY EXECUTE format(
        'SELECT count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(x)::text, '
        'E''\n'' ORDER BY x.id), ''''), ''UTF8'')), ''hex'') FROM %I x '
        'WHERE x.id IN (SELECT id FROM c3c_original_target WHERE table_name = %L)',
        target_name, target_name
    );
END
$$;
DO $$
DECLARE mismatch text;
BEGIN
    SELECT string_agg(e.table_name, ', ' ORDER BY e.table_name) INTO mismatch
      FROM c3c_expected_original e
      CROSS JOIN LATERAL pg_temp.c3c_original_digest(e.table_name) d
     WHERE d.row_count <> e.row_count OR d.row_sha256 <> e.row_sha256;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'immutable original 223-row cohort changed: %', mismatch;
    END IF;
END
$$;

-- New or unknown foreign-key dependants must not be removed by cascade or
-- left dangling. Only another explicitly deleted target row may reference a
-- deleted target row.
DO $$
DECLARE r record; n bigint; bad text := '';
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint c
         WHERE c.contype = 'f' AND c.confrelid::regclass::text IN
               (SELECT DISTINCT table_name FROM c3c_target)
           AND array_length(c.conkey, 1) <> 1
    ) THEN
        RAISE EXCEPTION 'composite foreign key into a cleanup target requires separate review';
    END IF;
    FOR r IN
        SELECT c.conrelid::regclass::text AS child, a.attname AS col,
               c.confrelid::regclass::text AS parent
          FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
         WHERE c.contype = 'f' AND array_length(c.conkey, 1) = 1
           AND c.confrelid::regclass::text IN (SELECT DISTINCT table_name FROM c3c_target)
    LOOP
        IF r.child IN (SELECT DISTINCT table_name FROM c3c_target) THEN
            EXECUTE format(
                'SELECT count(*) FROM %I x WHERE x.%I IN '
                '(SELECT id FROM c3c_target WHERE table_name = %L) '
                'AND x.id NOT IN (SELECT id FROM c3c_target WHERE table_name = %L)',
                r.child, r.col, r.parent, r.child
            ) INTO n;
        ELSE
            EXECUTE format(
                'SELECT count(*) FROM %I x WHERE x.%I IN '
                '(SELECT id FROM c3c_target WHERE table_name = %L)',
                r.child, r.col, r.parent
            ) INTO n;
        END IF;
        IF n > 0 THEN
            bad := bad || format('%s.%s->%s:%s ', r.child, r.col, r.parent, n);
        END IF;
    END LOOP;
    IF bad <> '' THEN
        RAISE EXCEPTION 'unexpected dependency on a cleanup target: %', bad;
    END IF;
END
$$;

CREATE TEMP TABLE c3c_actual_target (
    table_name text NOT NULL,
    id uuid NOT NULL,
    row_sha256 text NOT NULL,
    PRIMARY KEY (table_name, id)
) ON COMMIT DROP;
INSERT INTO c3c_actual_target
SELECT 'gaming_session_extensions', x.id,
       encode(sha256(convert_to(to_jsonb(x)::text, 'UTF8')), 'hex')
  FROM gaming_session_extensions x JOIN c3c_reviewed_rows t ON t.table_name = 'gaming_session_extensions' AND t.id = x.id
UNION ALL
SELECT 'gaming_sessions', x.id, encode(sha256(convert_to(to_jsonb(x)::text, 'UTF8')), 'hex')
  FROM gaming_sessions x JOIN c3c_reviewed_rows t ON t.table_name = 'gaming_sessions' AND t.id = x.id
UNION ALL
SELECT 'order_lines', x.id, encode(sha256(convert_to(to_jsonb(x)::text, 'UTF8')), 'hex')
  FROM order_lines x JOIN c3c_reviewed_rows t ON t.table_name = 'order_lines' AND t.id = x.id
UNION ALL
SELECT 'payments', x.id, encode(sha256(convert_to(to_jsonb(x)::text, 'UTF8')), 'hex')
  FROM payments x JOIN c3c_reviewed_rows t ON t.table_name = 'payments' AND t.id = x.id
UNION ALL
SELECT 'orders', x.id, encode(sha256(convert_to(to_jsonb(x)::text, 'UTF8')), 'hex')
  FROM orders x JOIN c3c_reviewed_rows t ON t.table_name = 'orders' AND t.id = x.id
UNION ALL
SELECT 'shifts', x.id, encode(sha256(convert_to(to_jsonb(x)::text, 'UTF8')), 'hex')
  FROM shifts x JOIN c3c_reviewed_rows t ON t.table_name = 'shifts' AND t.id = x.id;

DO $$
BEGIN
    IF EXISTS ((SELECT * FROM c3c_reviewed_rows EXCEPT SELECT * FROM c3c_actual_target)
               UNION ALL
               (SELECT * FROM c3c_actual_target EXCEPT SELECT * FROM c3c_reviewed_rows)) THEN
        RAISE EXCEPTION 'a pinned target or retained row is absent or its whole-row hash changed';
    END IF;
END
$$;

CREATE TEMP TABLE c3c_all_pre (
    table_name text PRIMARY KEY,
    row_count bigint NOT NULL,
    row_sha256 text NOT NULL
) ON COMMIT DROP;
DO $$
DECLARE r record; d record;
BEGIN
    FOR r IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename
    LOOP
        SELECT * INTO d FROM pg_temp.c3c_table_digest(format('public.%I', r.tablename)::regclass);
        INSERT INTO c3c_all_pre VALUES (r.tablename, d.row_count, d.row_sha256);
    END LOOP;
END
$$;

CREATE TEMP TABLE c3c_state ON COMMIT DROP AS
SELECT encode(sha256(convert_to(string_agg(
           table_name || ':' || row_count || ':' || row_sha256,
           E'\n' ORDER BY table_name), 'UTF8')), 'hex') AS state_fingerprint,
       (SELECT max(id) FROM audit_log) AS audit_max_id,
       (SELECT row_count FROM c3c_all_pre WHERE table_name = 'audit_log') AS audit_count,
       (SELECT row_sha256 FROM c3c_all_pre WHERE table_name = 'audit_log') AS audit_sha256
  FROM c3c_all_pre;

CREATE TEMP TABLE c3c_expected_all ON COMMIT DROP AS
SELECT entry.key AS table_name,
       (entry.value->>'row_count')::bigint AS row_count,
       entry.value->>'row_sha256' AS row_sha256
  FROM c3c_manifest m
 CROSS JOIN LATERAL jsonb_each(m.document->'expected_full_table_digests') entry;

DO $$
BEGIN
    IF EXISTS ((SELECT * FROM c3c_all_pre EXCEPT SELECT * FROM c3c_expected_all)
               UNION ALL
               (SELECT * FROM c3c_expected_all EXCEPT SELECT * FROM c3c_all_pre)) THEN
        RAISE EXCEPTION 'restored database does not match the owner-reviewed full-table digests';
    END IF;
    IF (SELECT state_fingerprint FROM c3c_state)
         <> (SELECT expected_state_fingerprint FROM c3c_inputs) THEN
        RAISE EXCEPTION 'locked database fingerprint differs from the rehearsed fingerprint';
    END IF;
END
$$;

-- The allowlist must be the complete dependent graph of the six shifts.
DO $$
BEGIN
    IF EXISTS (
        (SELECT id FROM shifts WHERE opened_at >= '2026-09-20T00:00:00Z'
         EXCEPT SELECT id FROM c3c_reviewed_rows WHERE table_name = 'shifts')
        UNION ALL
        (SELECT id FROM c3c_reviewed_rows WHERE table_name = 'shifts'
         EXCEPT SELECT id FROM shifts WHERE opened_at >= '2026-09-20T00:00:00Z')
    ) OR EXISTS (
        SELECT 1 FROM orders o
         WHERE coalesce(o.shift_id IN (SELECT id FROM c3c_target WHERE table_name = 'shifts'), false)
            <> (o.id IN (SELECT id FROM c3c_target WHERE table_name = 'orders'))
    ) OR EXISTS (
        SELECT 1 FROM payments p
         WHERE coalesce(p.shift_id IN (SELECT id FROM c3c_target WHERE table_name = 'shifts')
                    OR p.order_id IN (SELECT id FROM c3c_target WHERE table_name = 'orders'), false)
            <> (p.id IN (SELECT id FROM c3c_target WHERE table_name = 'payments'))
    ) OR EXISTS (
        SELECT 1 FROM gaming_sessions g
         WHERE coalesce(g.shift_id IN (SELECT id FROM c3c_target WHERE table_name = 'shifts')
                    OR g.order_id IN (SELECT id FROM c3c_target WHERE table_name = 'orders'), false)
            <> (g.id IN (SELECT id FROM c3c_target WHERE table_name = 'gaming_sessions'))
    ) OR EXISTS (
        SELECT 1 FROM order_lines l
         WHERE coalesce(l.order_id IN (SELECT id FROM c3c_target WHERE table_name = 'orders'), false)
            <> (l.id IN (SELECT id FROM c3c_target WHERE table_name = 'order_lines'))
    ) OR EXISTS (
        SELECT 1 FROM gaming_session_extensions x
         WHERE coalesce(x.gaming_session_id IN (SELECT id FROM c3c_target WHERE table_name = 'gaming_sessions'), false)
            <> (x.id IN (SELECT id FROM c3c_target WHERE table_name = 'gaming_session_extensions'))
    ) THEN
        RAISE EXCEPTION 'six-shift dependency graph differs from the pinned manifest';
    END IF;
    IF EXISTS (
        SELECT 1 FROM orders o
         WHERE coalesce(o.shift_id IN (SELECT id FROM c3c_retained_rows WHERE table_name = 'shifts'), false)
            <> (o.id IN (SELECT id FROM c3c_retained_rows WHERE table_name = 'orders'))
    ) OR EXISTS (
        SELECT 1 FROM payments p
         WHERE coalesce(p.shift_id IN (SELECT id FROM c3c_retained_rows WHERE table_name = 'shifts')
                    OR p.order_id IN (SELECT id FROM c3c_retained_rows WHERE table_name = 'orders'), false)
            <> (p.id IN (SELECT id FROM c3c_retained_rows WHERE table_name = 'payments'))
    ) OR EXISTS (
        SELECT 1 FROM gaming_sessions g
         WHERE coalesce(g.shift_id IN (SELECT id FROM c3c_retained_rows WHERE table_name = 'shifts')
                    OR g.order_id IN (SELECT id FROM c3c_retained_rows WHERE table_name = 'orders'), false)
            <> (g.id IN (SELECT id FROM c3c_retained_rows WHERE table_name = 'gaming_sessions'))
    ) OR EXISTS (
        SELECT 1 FROM order_lines l
         WHERE coalesce(l.order_id IN (SELECT id FROM c3c_retained_rows WHERE table_name = 'orders'), false)
            <> (l.id IN (SELECT id FROM c3c_retained_rows WHERE table_name = 'order_lines'))
    ) OR EXISTS (
        SELECT 1 FROM gaming_session_extensions x
         WHERE coalesce(x.gaming_session_id IN (SELECT id FROM c3c_retained_rows WHERE table_name = 'gaming_sessions'), false)
            <> (x.id IN (SELECT id FROM c3c_retained_rows WHERE table_name = 'gaming_session_extensions'))
    ) THEN
        RAISE EXCEPTION 'retained later-shift dependency graph differs from the pinned manifest';
    END IF;
    IF EXISTS (SELECT 1 FROM refunds WHERE order_id IN (SELECT id FROM c3c_target WHERE table_name = 'orders')) THEN
        RAISE EXCEPTION 'a refund depends on a cleanup order';
    END IF;
END
$$;

CREATE TEMP TABLE c3c_fence ON COMMIT DROP AS
SELECT entry->>'action_key' AS action_key,
       entry->>'request_hash' AS request_hash,
       (entry->>'user_id')::uuid AS user_id,
       (entry->>'terminal_id')::uuid AS terminal_id,
       (entry->>'source_entity_id')::uuid AS source_entity_id
  FROM c3c_manifest m
 CROSS JOIN LATERAL jsonb_array_elements(m.document->'replay_fence') entry;

DO $$
BEGIN
    IF (SELECT count(*) FROM c3c_fence)
         <> (SELECT count(*) FROM c3c_target WHERE table_name = 'shifts')
       OR EXISTS (
           SELECT 1 FROM shifts s JOIN c3c_target t ON t.table_name = 'shifts' AND t.id = s.id
           LEFT JOIN c3c_fence f ON f.source_entity_id = s.id
           WHERE s.opening_action_id IS NULL OR s.opening_request_hash IS NULL
              OR f.action_key IS DISTINCT FROM s.opening_action_id
              OR f.request_hash IS DISTINCT FROM s.opening_request_hash
              OR f.user_id IS DISTINCT FROM s.opened_by
              OR f.terminal_id IS DISTINCT FROM s.terminal_id
       ) THEN
        RAISE EXCEPTION 'replay fence is not one-to-one with every deleted keyed shift';
    END IF;
END
$$;

-- Receipt-facing money, invoice and delivered Sheets evidence must be derived
-- from the exact deletion target, not trusted merely because it is well-formed.
DO $$
DECLARE m jsonb := (SELECT document FROM c3c_manifest);
DECLARE paid_total bigint; payment_total bigint; invoice_numbers jsonb; sheet_event_ids jsonb;
BEGIN
    SELECT coalesce(sum(total_minor), 0) INTO paid_total
      FROM orders WHERE status = 'paid'
       AND id IN (SELECT id FROM c3c_target WHERE table_name = 'orders');
    SELECT coalesce(sum(amount_minor), 0) INTO payment_total
      FROM payments WHERE id IN (SELECT id FROM c3c_target WHERE table_name = 'payments');
    SELECT coalesce(jsonb_agg(invoice_no ORDER BY invoice_no), '[]'::jsonb) INTO invoice_numbers
      FROM orders WHERE status = 'paid' AND invoice_no IS NOT NULL
       AND id IN (SELECT id FROM c3c_target WHERE table_name = 'orders');
    SELECT coalesce(jsonb_agg(event_id::text ORDER BY event_id::text), '[]'::jsonb) INTO sheet_event_ids
      FROM google_sheets_deliveries
     WHERE event_type = 'pos.order.paid' AND source_type = 'pos_order' AND status = 'delivered'
       AND source_id IN (SELECT id::text FROM c3c_target WHERE table_name = 'orders');
    IF paid_total <> (m->'evidence'->>'paid_total_minor')::bigint
       OR payment_total <> paid_total
       OR invoice_numbers <> m->'evidence'->'retired_invoice_numbers'
       OR sheet_event_ids <> m->'evidence'->'google_sheets_event_ids_owner_deletes' THEN
        RAISE EXCEPTION 'target money, invoice or delivered Sheets evidence differs from the manifest';
    END IF;
    IF (SELECT coalesce(sum(total_minor), 0) FROM orders
         WHERE status = 'paid' AND id IN
           (SELECT id FROM c3c_original_target WHERE table_name = 'orders')) <> 750000
       OR (SELECT coalesce(sum(amount_minor), 0) FROM payments WHERE id IN
           (SELECT id FROM c3c_original_target WHERE table_name = 'payments')) <> 750000
       OR (SELECT count(*) FROM orders WHERE status = 'paid' AND id IN
           (SELECT id FROM c3c_original_target WHERE table_name = 'orders')) <> 47 THEN
        RAISE EXCEPTION 'immutable original cohort no longer reconciles to 47 paid / 750000';
    END IF;
END
$$;

-- Full business quiescence: no transaction may discard unfinished or unsent work.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM shifts WHERE status <> 'closed')
       OR EXISTS (SELECT 1 FROM orders WHERE status NOT IN ('paid', 'void', 'refunded'))
       OR EXISTS (SELECT 1 FROM gaming_sessions WHERE status NOT IN ('ended', 'cancelled'))
       OR EXISTS (SELECT 1 FROM google_sheets_deliveries WHERE status <> 'delivered')
       OR EXISTS (SELECT 1 FROM order_lines l JOIN orders o ON o.id = l.order_id
                   WHERE l.kitchen_released_at IS NOT NULL AND l.voided_at IS NOT NULL
                     AND l.kitchen_void_acknowledged_at IS NULL)
       OR EXISTS (SELECT 1 FROM pos_refund_requests r WHERE NOT EXISTS
                    (SELECT 1 FROM refunds x WHERE x.request_id = r.id) AND NOT EXISTS
                    (SELECT 1 FROM pos_refund_withdrawals x WHERE x.refund_request_id = r.id))
       OR EXISTS (SELECT 1 FROM membership_payment_requests r WHERE NOT EXISTS
                    (SELECT 1 FROM membership_payments x WHERE x.request_id = r.id) AND NOT EXISTS
                    (SELECT 1 FROM membership_payment_request_resolutions x WHERE x.request_id = r.id))
       OR EXISTS (SELECT 1 FROM membership_refunds r WHERE NOT EXISTS
                    (SELECT 1 FROM membership_refund_settlements x WHERE x.refund_id = r.id) AND NOT EXISTS
                    (SELECT 1 FROM membership_refund_resolutions x WHERE x.refund_id = r.id))
       OR EXISTS (SELECT 1 FROM membership_refund_attempt_recoveries r WHERE NOT EXISTS
                    (SELECT 1 FROM membership_refund_attempt_resolutions x WHERE x.recovery_id = r.id))
    THEN
        RAISE EXCEPTION 'business state is not fully quiescent';
    END IF;
END
$$;

DO $$
DECLARE m jsonb := (SELECT document FROM c3c_manifest);
BEGIN
    IF EXISTS (SELECT 1 FROM audit_log
                WHERE action = 'verified_trial_cleanup' OR entity_type = 'TrialCleanupReceipt') THEN
        RAISE EXCEPTION 'a v2 cleanup receipt already exists; combined transaction is no longer possible';
    END IF;
    IF (SELECT count(*) FROM users WHERE id = (m->>'actor_user_id')::uuid
          AND company_id = (m->>'company_id')::uuid AND status = 'active' AND deleted_at IS NULL) <> 1
       OR (SELECT count(*) FROM terminals t JOIN branches b ON b.id = t.branch_id
            WHERE t.id = (m->>'terminal_id')::uuid AND t.is_active
              AND b.company_id = (m->>'company_id')::uuid) <> 1 THEN
        RAISE EXCEPTION 'receipt actor or terminal is not active in the reviewed company';
    END IF;
    IF m->'evidence'->'invoice_counter'->>'id'
         <> '9f78c3b5-c5f4-425d-9def-4b34298a2931'
       OR (m->'evidence'->'invoice_counter'->>'last_seq')::bigint < 70
       OR (SELECT last_seq FROM in_invoice_counters WHERE id = (m->'evidence'->'invoice_counter'->>'id')::uuid)
         IS DISTINCT FROM (m->'evidence'->'invoice_counter'->>'last_seq')::bigint
       OR (SELECT count(*) FROM in_invoice_counters) <> 1 THEN
        RAISE EXCEPTION 'invoice counter differs from the fresh reviewed manifest';
    END IF;
    IF EXISTS (SELECT 1 FROM c3c_target t WHERE
          (t.table_name = 'shifts' AND EXISTS (SELECT 1 FROM shifts x WHERE x.id = t.id AND x.company_id <> (m->>'company_id')::uuid))
       OR (t.table_name = 'orders' AND EXISTS (SELECT 1 FROM orders x WHERE x.id = t.id AND x.company_id <> (m->>'company_id')::uuid))
       OR (t.table_name = 'payments' AND EXISTS (SELECT 1 FROM payments x JOIN orders o ON o.id = x.order_id WHERE x.id = t.id AND o.company_id <> (m->>'company_id')::uuid))
       OR (t.table_name = 'gaming_sessions' AND EXISTS (SELECT 1 FROM gaming_sessions x WHERE x.id = t.id AND x.company_id <> (m->>'company_id')::uuid))
       OR (t.table_name = 'gaming_session_extensions' AND EXISTS (SELECT 1 FROM gaming_session_extensions x WHERE x.id = t.id AND x.company_id <> (m->>'company_id')::uuid))) THEN
        RAISE EXCEPTION 'target rows cross the reviewed tenant boundary';
    END IF;
END
$$;

CREATE TEMP TABLE c3c_triggers_pre ON COMMIT DROP AS
SELECT tgrelid::regclass::text AS table_name, tgname, tgenabled,
       pg_get_triggerdef(tg.oid, true) AS definition,
       encode(sha256(convert_to(pg_get_triggerdef(tg.oid, true), 'UTF8')), 'hex') AS definition_sha256,
       encode(sha256(convert_to(pg_get_functiondef(tgfoid), 'UTF8')), 'hex') AS function_sha256
  FROM pg_trigger tg
 WHERE NOT tgisinternal AND tgrelid::regclass::text IN
       ('gaming_session_extensions', 'gaming_sessions', 'order_lines', 'payments', 'orders', 'shifts');

CREATE TEMP TABLE c3c_expected_triggers ON COMMIT DROP AS
SELECT entry->>'table_name' AS table_name, entry->>'trigger_name' AS tgname,
       entry->>'enabled' AS tgenabled, entry->>'definition_sha256' AS definition_sha256,
       entry->>'function_sha256' AS function_sha256
  FROM c3c_manifest m CROSS JOIN LATERAL jsonb_array_elements(m.document->'expected_triggers') entry;

DO $$
BEGIN
    IF EXISTS ((SELECT table_name, tgname, tgenabled, definition_sha256, function_sha256 FROM c3c_expected_triggers
                EXCEPT
                SELECT table_name, tgname, tgenabled::text, definition_sha256, function_sha256 FROM c3c_triggers_pre WHERE
                  (table_name, tgname) IN (
                    ('gaming_session_extensions','trg_gaming_session_extensions_immutable'),
                    ('order_lines','trg_order_lines_paid_source_integrity'),
                    ('orders','trg_orders_paid_source_integrity'),
                    ('payments','trg_payments_immutable')))
               UNION ALL
               (SELECT table_name, tgname, tgenabled::text, definition_sha256, function_sha256 FROM c3c_triggers_pre WHERE
                  (table_name, tgname) IN (
                    ('gaming_session_extensions','trg_gaming_session_extensions_immutable'),
                    ('order_lines','trg_order_lines_paid_source_integrity'),
                    ('orders','trg_orders_paid_source_integrity'),
                    ('payments','trg_payments_immutable'))
                EXCEPT SELECT table_name, tgname, tgenabled, definition_sha256, function_sha256 FROM c3c_expected_triggers)) THEN
        RAISE EXCEPTION 'guarded trigger definitions or functions differ from the reviewed manifest';
    END IF;
END
$$;

CREATE TEMP TABLE c3c_retained_pre (
    table_name text PRIMARY KEY, row_count bigint NOT NULL, row_sha256 text NOT NULL
) ON COMMIT DROP;
CREATE OR REPLACE FUNCTION pg_temp.c3c_retained_digest(target_name text)
RETURNS TABLE (row_count bigint, row_sha256 text)
LANGUAGE plpgsql AS $$
BEGIN
    RETURN QUERY EXECUTE format(
        'SELECT count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(x)::text, '
        'E''\n'' ORDER BY to_jsonb(x)::text), ''''), ''UTF8'')), ''hex'') '
        'FROM %I x WHERE NOT EXISTS (SELECT 1 FROM c3c_target t WHERE t.table_name = %L AND t.id = x.id)',
        target_name, target_name
    );
END
$$;
DO $$
DECLARE name text; d record;
BEGIN
    FOREACH name IN ARRAY ARRAY['gaming_session_extensions','gaming_sessions','order_lines','payments','orders','shifts']
    LOOP
        SELECT * INTO d FROM pg_temp.c3c_retained_digest(name);
        INSERT INTO c3c_retained_pre VALUES (name, d.row_count, d.row_sha256);
    END LOOP;
END
$$;

ALTER TABLE gaming_session_extensions DISABLE TRIGGER trg_gaming_session_extensions_immutable;
ALTER TABLE order_lines DISABLE TRIGGER trg_order_lines_paid_source_integrity;
ALTER TABLE orders DISABLE TRIGGER trg_orders_paid_source_integrity;
ALTER TABLE payments DISABLE TRIGGER trg_payments_immutable;

CREATE TEMP TABLE c3c_deleted (table_name text PRIMARY KEY, row_count bigint NOT NULL) ON COMMIT DROP;
WITH gone AS (DELETE FROM gaming_session_extensions WHERE id IN
    (SELECT id FROM c3c_target WHERE table_name = 'gaming_session_extensions') RETURNING 1)
INSERT INTO c3c_deleted SELECT 'gaming_session_extensions', count(*) FROM gone;
WITH gone AS (DELETE FROM gaming_sessions WHERE id IN
    (SELECT id FROM c3c_target WHERE table_name = 'gaming_sessions') RETURNING 1)
INSERT INTO c3c_deleted SELECT 'gaming_sessions', count(*) FROM gone;
WITH gone AS (DELETE FROM order_lines WHERE id IN
    (SELECT id FROM c3c_target WHERE table_name = 'order_lines') RETURNING 1)
INSERT INTO c3c_deleted SELECT 'order_lines', count(*) FROM gone;
WITH gone AS (DELETE FROM payments WHERE id IN
    (SELECT id FROM c3c_target WHERE table_name = 'payments') RETURNING 1)
INSERT INTO c3c_deleted SELECT 'payments', count(*) FROM gone;
WITH gone AS (DELETE FROM orders WHERE id IN
    (SELECT id FROM c3c_target WHERE table_name = 'orders') RETURNING 1)
INSERT INTO c3c_deleted SELECT 'orders', count(*) FROM gone;
WITH gone AS (DELETE FROM shifts WHERE id IN
    (SELECT id FROM c3c_target WHERE table_name = 'shifts') RETURNING 1)
INSERT INTO c3c_deleted SELECT 'shifts', count(*) FROM gone;

SET CONSTRAINTS ALL IMMEDIATE;
ALTER TABLE payments ENABLE TRIGGER trg_payments_immutable;
ALTER TABLE orders ENABLE TRIGGER trg_orders_paid_source_integrity;
ALTER TABLE order_lines ENABLE TRIGGER trg_order_lines_paid_source_integrity;
ALTER TABLE gaming_session_extensions ENABLE TRIGGER trg_gaming_session_extensions_immutable;

DO $$
BEGIN
    IF EXISTS (SELECT table_name, count(*) FROM c3c_target GROUP BY table_name
               EXCEPT SELECT table_name, row_count FROM c3c_deleted)
       OR EXISTS (SELECT table_name, row_count FROM c3c_deleted
                  EXCEPT SELECT table_name, count(*) FROM c3c_target GROUP BY table_name) THEN
        RAISE EXCEPTION 'deleted counts differ from the manifest';
    END IF;
    IF EXISTS (
        ((SELECT table_name, tgname, tgenabled, definition,
                definition_sha256, function_sha256 FROM c3c_triggers_pre)
        EXCEPT
        (SELECT tgrelid::regclass::text, tgname, tgenabled, pg_get_triggerdef(oid, true),
                encode(sha256(convert_to(pg_get_triggerdef(oid, true), 'UTF8')), 'hex'),
                encode(sha256(convert_to(pg_get_functiondef(tgfoid), 'UTF8')), 'hex')
           FROM pg_trigger WHERE NOT tgisinternal AND tgrelid::regclass::text IN
             ('gaming_session_extensions','gaming_sessions','order_lines','payments','orders','shifts')))
        UNION ALL
        ((SELECT tgrelid::regclass::text, tgname, tgenabled, pg_get_triggerdef(oid, true),
                encode(sha256(convert_to(pg_get_triggerdef(oid, true), 'UTF8')), 'hex'),
                encode(sha256(convert_to(pg_get_functiondef(tgfoid), 'UTF8')), 'hex')
           FROM pg_trigger WHERE NOT tgisinternal AND tgrelid::regclass::text IN
             ('gaming_session_extensions','gaming_sessions','order_lines','payments','orders','shifts'))
        EXCEPT
        (SELECT table_name, tgname, tgenabled, definition,
                definition_sha256, function_sha256 FROM c3c_triggers_pre))
    ) THEN
        RAISE EXCEPTION 'triggers were not restored byte-identically';
    END IF;
END
$$;

CREATE TEMP TABLE c3c_receipt (id bigint PRIMARY KEY) ON COMMIT DROP;
DO $$
DECLARE m jsonb := (SELECT document FROM c3c_manifest); receipt_id bigint;
DECLARE receipt_state_fingerprint text; deleted_counts jsonb; deleted_shift_ids jsonb;
BEGIN
    SELECT s.state_fingerprint INTO receipt_state_fingerprint FROM c3c_state s;
    SELECT jsonb_object_agg(table_name, row_count ORDER BY table_name) INTO deleted_counts FROM c3c_deleted;
    SELECT jsonb_agg(id::text ORDER BY id::text) INTO deleted_shift_ids
      FROM c3c_target WHERE table_name = 'shifts';
    INSERT INTO audit_log (
        actor_user_id, company_id, action, entity_type, entity_id, before, after,
        ip, user_agent, terminal_id, request_id, client_platform,
        client_version_code, client_action_id, client_reported_at,
        client_was_offline, synced_at, reason
    ) VALUES (
        (m->>'actor_user_id')::uuid, (m->>'company_id')::uuid,
        'verified_trial_cleanup', 'TrialCleanupReceipt', m->>'cleanup_id',
        jsonb_build_object('schema_revision', '0082', 'state_fingerprint', receipt_state_fingerprint,
                           'backup_sha256', m->>'backup_sha256'),
        jsonb_build_object(
            'receipt_version', 2, 'cleanup_id', m->>'cleanup_id',
            'source_git_sha', m->>'tagged_app_source_git_sha',
            'executor', 'combined-cleanup-maintenance', 'executed_at', clock_timestamp(),
            'deleted_counts', deleted_counts, 'deleted_shift_ids', deleted_shift_ids,
            'replay_fence', m->'replay_fence',
            'evidence', m->'evidence' || jsonb_build_object(
                'deletion_cohort', jsonb_build_object(
                    'decision', m->>'later_cohort_decision',
                    'deleted_rows', m->'targets',
                    'retained_later_rows', m->'retained_rows'),
                'classification_evidence_sha256', m->>'classification_evidence_sha256',
                'operator_reviewed_at', m->>'operator_reviewed_at',
                'operator_review_evidence_sha256', m->>'operator_review_evidence_sha256',
                'tablet_replay_evidence_sha256', m->>'tablet_replay_evidence_sha256',
                'maintenance_source_git_sha', m->>'maintenance_source_git_sha',
                'maintenance_sql_sha256', m->>'maintenance_sql_sha256',
                'expected_post_table_digests', (SELECT jsonb_object_agg(
                    table_name, jsonb_build_object(
                        'row_count', row_count, 'row_sha256', row_sha256)
                    ORDER BY table_name) FROM c3c_retained_pre),
                'tagged_app_source_git_sha', m->>'tagged_app_source_git_sha')
        ),
        NULL, 'code30-3-combined-cleanup/1', (m->>'terminal_id')::uuid,
        m->>'cleanup_id', NULL, NULL, NULL, NULL, NULL, NULL,
        'Owner-classified and operator-reviewed Code30.3 trial-cohort maintenance'
    ) RETURNING id INTO receipt_id;
    INSERT INTO c3c_receipt VALUES (receipt_id);
END
$$;

DO $$
DECLARE mismatch text; s c3c_state;
BEGIN
    SELECT * INTO s FROM c3c_state;
    SELECT string_agg(p.table_name, ', ') INTO mismatch
      FROM c3c_retained_pre p CROSS JOIN LATERAL pg_temp.c3c_retained_digest(p.table_name) d
     WHERE d.row_count <> p.row_count OR d.row_sha256 <> p.row_sha256;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'a retained row changed in a cleaned table: %', mismatch;
    END IF;
    SELECT string_agg(p.table_name, ', ') INTO mismatch
      FROM c3c_all_pre p
      CROSS JOIN LATERAL pg_temp.c3c_table_digest(format('public.%I', p.table_name)::regclass) d
     WHERE p.table_name NOT IN (SELECT DISTINCT table_name FROM c3c_target)
       AND p.table_name <> 'audit_log'
       AND (d.row_count <> p.row_count OR d.row_sha256 <> p.row_sha256);
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'an unrelated table changed during rehearsal: %', mismatch;
    END IF;
    IF (SELECT count(*) FROM audit_log a JOIN c3c_receipt r ON r.id = a.id
         WHERE a.action = 'verified_trial_cleanup' AND a.entity_type = 'TrialCleanupReceipt'
           AND a.entity_id = (SELECT document->>'cleanup_id' FROM c3c_manifest)) <> 1 THEN
        RAISE EXCEPTION 'single combined v2 receipt was not produced';
    END IF;
    IF (SELECT count(*) FROM audit_log WHERE id <= s.audit_max_id) <> s.audit_count
       OR (SELECT encode(sha256(convert_to(coalesce(string_agg(
              to_jsonb(a)::text, E'\n' ORDER BY to_jsonb(a)::text), ''), 'UTF8')), 'hex')
             FROM audit_log a WHERE a.id <= s.audit_max_id) <> s.audit_sha256
       OR (SELECT count(*) FROM audit_log WHERE id > s.audit_max_id) <> 1 THEN
        RAISE EXCEPTION 'preexisting audit history changed or receipt count is not exactly one';
    END IF;
    IF (SELECT last_seq FROM in_invoice_counters
          WHERE id = ((SELECT document FROM c3c_manifest)->'evidence'->'invoice_counter'->>'id')::uuid)
         IS DISTINCT FROM (((SELECT document FROM c3c_manifest)->'evidence'->'invoice_counter'->>'last_seq')::bigint) THEN
        RAISE EXCEPTION 'invoice counter moved during rehearsal';
    END IF;
END
$$;
