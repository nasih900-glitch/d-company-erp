-- Independent, read-only Code30.3 combined cleanup postcheck.
\set ON_ERROR_STOP on
\set QUIET on

SELECT set_config('c3c.manifest_path', :'manifest_container_path', false);
SELECT set_config('c3c.expected_database', :'expected_database_name', false);
SELECT set_config('c3c.maintenance_sql_sha256', :'maintenance_sql_sha256', false);
SELECT set_config('c3c.expected_state_fingerprint', :'expected_state_fingerprint', false);

BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '300s';
SET LOCAL TIME ZONE 'UTC';
SET LOCAL bytea_output = 'hex';
SET LOCAL DateStyle = 'ISO, YMD';
SET LOCAL IntervalStyle = 'postgres';
SET LOCAL extra_float_digits = 1;

DO $$
BEGIN
    IF current_database() <> 'erp' OR current_database() IS DISTINCT FROM current_setting('c3c.expected_database') THEN
        RAISE EXCEPTION 'combined cleanup postcheck requires canonical production database erp';
    END IF;
END
$$;

DO $$
DECLARE m jsonb := pg_read_file(current_setting('c3c.manifest_path'))::jsonb;
DECLARE table_entry record; row_entry jsonb; found_hash text;
BEGIN
    IF m->>'tagged_app_source_git_sha' <> 'ad5adfb93c3488f1f931ca27da53824aa57d3dc5'
       OR m->>'maintenance_sql_sha256' <> current_setting('c3c.maintenance_sql_sha256') THEN
        RAISE EXCEPTION 'postcheck provenance differs from the reviewed manifest';
    END IF;
    FOR table_entry IN SELECT key, value FROM jsonb_each(m->'targets')
    LOOP
        FOR row_entry IN SELECT value FROM jsonb_array_elements(table_entry.value)
        LOOP
            EXECUTE format('SELECT encode(sha256(convert_to(to_jsonb(x)::text, ''UTF8'')), ''hex'') FROM %I x WHERE id = $1',
                           table_entry.key)
               INTO found_hash USING (row_entry->>'id')::uuid;
            IF found_hash IS NOT NULL THEN
                RAISE EXCEPTION 'deleted target still exists: %.%', table_entry.key, row_entry->>'id';
            END IF;
        END LOOP;
    END LOOP;
    FOR table_entry IN SELECT key, value FROM jsonb_each(m->'retained_rows')
    LOOP
        FOR row_entry IN SELECT value FROM jsonb_array_elements(table_entry.value)
        LOOP
            EXECUTE format('SELECT encode(sha256(convert_to(to_jsonb(x)::text, ''UTF8'')), ''hex'') FROM %I x WHERE id = $1',
                           table_entry.key)
               INTO found_hash USING (row_entry->>'id')::uuid;
            IF found_hash IS DISTINCT FROM row_entry->>'row_sha256' THEN
                RAISE EXCEPTION 'retained row is absent or changed: %.%', table_entry.key, row_entry->>'id';
            END IF;
        END LOOP;
    END LOOP;
END
$$;

DO $$
DECLARE m jsonb := pg_read_file(current_setting('c3c.manifest_path'))::jsonb;
BEGIN
    IF (SELECT array_agg(tablename::text ORDER BY tablename::text)
          FROM pg_tables WHERE schemaname = 'public')
         IS DISTINCT FROM
       (SELECT array_agg(key ORDER BY key) FROM jsonb_object_keys(m->'expected_full_table_digests') key) THEN
        RAISE EXCEPTION 'public table inventory differs from the reviewed manifest';
    END IF;
END
$$;

DO $$
DECLARE m jsonb := pg_read_file(current_setting('c3c.manifest_path'))::jsonb;
DECLARE current_count bigint; current_hash text;
BEGIN
    SELECT count(*), encode(sha256(convert_to(coalesce(string_agg(
             to_jsonb(x)::text, E'\n' ORDER BY to_jsonb(x)::text), ''), 'UTF8')), 'hex')
      INTO current_count, current_hash FROM google_sheets_deliveries x;
    IF current_count IS DISTINCT FROM
         (m->'expected_full_table_digests'->'google_sheets_deliveries'->>'row_count')::bigint
       OR current_hash IS DISTINCT FROM
         m->'expected_full_table_digests'->'google_sheets_deliveries'->>'row_sha256' THEN
        RAISE EXCEPTION 'Google Sheets delivery ledger changed during cleanup';
    END IF;
    IF (SELECT last_seq FROM in_invoice_counters
         WHERE id = (m->'evidence'->'invoice_counter'->>'id')::uuid)
         IS DISTINCT FROM (m->'evidence'->'invoice_counter'->>'last_seq')::bigint
       OR (SELECT count(*) FROM in_invoice_counters) <> 1 THEN
        RAISE EXCEPTION 'invoice counter changed during cleanup';
    END IF;
    IF EXISTS (
        SELECT value #>> '{}' AS event_id
          FROM jsonb_array_elements(m->'evidence'->'google_sheets_event_ids_owner_deletes')
        EXCEPT
        SELECT event_id::text FROM google_sheets_deliveries
         WHERE status = 'delivered'
    ) THEN
        RAISE EXCEPTION 'a pinned delivered Sheets event is missing';
    END IF;
END
$$;

DO $$
DECLARE m jsonb := pg_read_file(current_setting('c3c.manifest_path'))::jsonb;
BEGIN
    IF EXISTS (
        ((SELECT entry->>'table_name', entry->>'trigger_name', entry->>'enabled',
                entry->>'definition_sha256', entry->>'function_sha256'
           FROM jsonb_array_elements(m->'expected_triggers') entry)
        EXCEPT
        (SELECT tgrelid::regclass::text, tgname, tgenabled::text,
                encode(sha256(convert_to(pg_get_triggerdef(oid, true), 'UTF8')), 'hex'),
                encode(sha256(convert_to(pg_get_functiondef(tgfoid), 'UTF8')), 'hex')
           FROM pg_trigger WHERE NOT tgisinternal))
        UNION ALL
        ((SELECT tgrelid::regclass::text, tgname, tgenabled::text,
                encode(sha256(convert_to(pg_get_triggerdef(oid, true), 'UTF8')), 'hex'),
                encode(sha256(convert_to(pg_get_functiondef(tgfoid), 'UTF8')), 'hex')
           FROM pg_trigger WHERE NOT tgisinternal
             AND (tgrelid::regclass::text, tgname) IN (
               ('gaming_session_extensions','trg_gaming_session_extensions_immutable'),
               ('order_lines','trg_order_lines_paid_source_integrity'),
               ('orders','trg_orders_paid_source_integrity'),
               ('payments','trg_payments_immutable')))
        EXCEPT
        (SELECT entry->>'table_name', entry->>'trigger_name', entry->>'enabled',
                entry->>'definition_sha256', entry->>'function_sha256'
           FROM jsonb_array_elements(m->'expected_triggers') entry))
    ) THEN
        RAISE EXCEPTION 'guarded trigger state, definition, or function changed';
    END IF;
END
$$;

DO $$
DECLARE m jsonb := pg_read_file(current_setting('c3c.manifest_path'))::jsonb;
DECLARE receipt audit_log%ROWTYPE; expected_counts jsonb; expected_shift_ids jsonb;
DECLARE expected_evidence jsonb;
BEGIN
    IF (SELECT count(*) FROM audit_log
         WHERE action = 'verified_trial_cleanup' OR entity_type = 'TrialCleanupReceipt') <> 1 THEN
        RAISE EXCEPTION 'expected exactly one v2 cleanup receipt globally';
    END IF;
    SELECT * INTO STRICT receipt FROM audit_log
     WHERE action = 'verified_trial_cleanup' AND entity_type = 'TrialCleanupReceipt';
    SELECT jsonb_object_agg(key, jsonb_array_length(value) ORDER BY key)
      INTO expected_counts FROM jsonb_each(m->'targets');
    SELECT jsonb_agg(entry->>'id' ORDER BY entry->>'id') INTO expected_shift_ids
      FROM jsonb_array_elements(m->'targets'->'shifts') entry;
    expected_evidence := m->'evidence' || jsonb_build_object(
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
        'tagged_app_source_git_sha', m->>'tagged_app_source_git_sha');
    IF receipt.entity_id IS DISTINCT FROM m->>'cleanup_id'
       OR receipt.request_id IS DISTINCT FROM m->>'cleanup_id'
       OR receipt.actor_user_id IS DISTINCT FROM (m->>'actor_user_id')::uuid
       OR receipt.terminal_id IS DISTINCT FROM (m->>'terminal_id')::uuid
       OR receipt.client_action_id IS NOT NULL
       OR receipt.client_was_offline IS NOT NULL
       OR receipt.synced_at IS NOT NULL
       OR receipt.user_agent IS DISTINCT FROM 'code30-3-combined-cleanup/1'
       OR receipt.before IS DISTINCT FROM jsonb_build_object(
            'schema_revision', '0082',
            'state_fingerprint', current_setting('c3c.expected_state_fingerprint'),
            'backup_sha256', m->>'backup_sha256')
       OR jsonb_typeof(receipt.after) IS DISTINCT FROM 'object'
       OR (SELECT array_agg(key ORDER BY key) FROM jsonb_object_keys(receipt.after) key)
          IS DISTINCT FROM ARRAY['cleanup_id','deleted_counts','deleted_shift_ids','evidence','executed_at',
                   'executor','receipt_version','replay_fence','source_git_sha']
       OR receipt.after->>'receipt_version' IS DISTINCT FROM '2'
       OR receipt.after->>'cleanup_id' IS DISTINCT FROM m->>'cleanup_id'
       OR receipt.after->>'source_git_sha' IS DISTINCT FROM 'ad5adfb93c3488f1f931ca27da53824aa57d3dc5'
       OR receipt.after->>'executor' IS DISTINCT FROM 'combined-cleanup-maintenance'
       OR (receipt.after->>'executed_at')::timestamptz IS NULL
       OR receipt.after->'deleted_counts' IS DISTINCT FROM expected_counts
       OR receipt.after->'deleted_shift_ids' IS DISTINCT FROM expected_shift_ids
       OR receipt.after->'replay_fence' IS DISTINCT FROM m->'replay_fence'
       OR jsonb_typeof(receipt.after->'evidence'->'expected_post_table_digests')
            IS DISTINCT FROM 'object'
       OR (SELECT array_agg(key ORDER BY key) FROM jsonb_object_keys(
             receipt.after->'evidence'->'expected_post_table_digests') key)
            IS DISTINCT FROM ARRAY['gaming_session_extensions','gaming_sessions',
              'order_lines','orders','payments','shifts']
       OR ((receipt.after->'evidence') - 'expected_post_table_digests')
            IS DISTINCT FROM expected_evidence THEN
        RAISE EXCEPTION 'v2 cleanup receipt does not match the reviewed manifest';
    END IF;
    IF jsonb_array_length(receipt.after->'replay_fence')
         <> jsonb_array_length(m->'targets'->'shifts')
       OR EXISTS (
          SELECT 1 FROM jsonb_array_elements(receipt.after->'replay_fence') f
           WHERE f->>'action_type' <> 'shift_open'
              OR NOT ((f->>'source_entity_id') = ANY(
                    SELECT entry->>'id' FROM jsonb_array_elements(m->'targets'->'shifts') entry))
       ) THEN
        RAISE EXCEPTION 'receipt replay fences do not cover every deleted shift';
    END IF;
END
$$;

DO $$
DECLARE m jsonb := pg_read_file(current_setting('c3c.manifest_path'))::jsonb;
DECLARE receipt audit_log%ROWTYPE; table_entry record;
DECLARE expected jsonb; actual_count bigint; actual_hash text;
BEGIN
    SELECT * INTO STRICT receipt FROM audit_log
     WHERE action = 'verified_trial_cleanup' AND entity_type = 'TrialCleanupReceipt';
    FOR table_entry IN SELECT key, value FROM jsonb_each(m->'expected_full_table_digests')
    LOOP
        IF table_entry.key IN (
            'gaming_session_extensions', 'gaming_sessions', 'order_lines',
            'payments', 'orders', 'shifts'
        ) THEN
            expected := receipt.after->'evidence'->'expected_post_table_digests'->table_entry.key;
            EXECUTE format(
                'SELECT count(*), encode(sha256(convert_to(coalesce(string_agg('
                'to_jsonb(x)::text, E''\n'' ORDER BY to_jsonb(x)::text), ''''), '
                '''UTF8'')), ''hex'') FROM %I x', table_entry.key)
              INTO actual_count, actual_hash;
        ELSIF table_entry.key = 'audit_log' THEN
            expected := table_entry.value;
            SELECT count(*), encode(sha256(convert_to(coalesce(string_agg(
                     to_jsonb(a)::text, E'\n' ORDER BY to_jsonb(a)::text), ''), 'UTF8')), 'hex')
              INTO actual_count, actual_hash FROM audit_log a WHERE a.id <> receipt.id;
        ELSE
            expected := table_entry.value;
            EXECUTE format(
                'SELECT count(*), encode(sha256(convert_to(coalesce(string_agg('
                'to_jsonb(x)::text, E''\n'' ORDER BY to_jsonb(x)::text), ''''), '
                '''UTF8'')), ''hex'') FROM %I x', table_entry.key)
              INTO actual_count, actual_hash;
        END IF;
        IF expected IS NULL
           OR actual_count IS DISTINCT FROM (expected->>'row_count')::bigint
           OR actual_hash IS DISTINCT FROM expected->>'row_sha256' THEN
            RAISE EXCEPTION 'post-cleanup table digest differs: %', table_entry.key;
        END IF;
    END LOOP;
END
$$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM shifts WHERE status <> 'closed')
       OR EXISTS (SELECT 1 FROM orders WHERE status NOT IN ('paid', 'void', 'refunded'))
       OR EXISTS (SELECT 1 FROM gaming_sessions WHERE status IN ('active', 'paused'))
       OR NOT EXISTS (SELECT 1 FROM stations WHERE is_active)
       OR EXISTS (SELECT 1 FROM stations s JOIN gaming_sessions g ON g.station_id = s.id
                   WHERE s.is_active AND g.status IN ('active', 'paused'))
       OR EXISTS (SELECT 1 FROM google_sheets_deliveries WHERE status <> 'delivered') THEN
        RAISE EXCEPTION 'post-cleanup business state or station availability is unsafe';
    END IF;
END
$$;

\set QUIET off
SELECT jsonb_build_object(
    'mode', 'read-only postcheck',
    'cleanup_id', (pg_read_file(current_setting('c3c.manifest_path'))::jsonb)->>'cleanup_id',
    'state_fingerprint', current_setting('c3c.expected_state_fingerprint'),
    'receipt_audit_id', (SELECT id FROM audit_log
       WHERE action = 'verified_trial_cleanup' AND entity_type = 'TrialCleanupReceipt'),
    'status', 'accepted'
)::text;
\set QUIET on
COMMIT;
