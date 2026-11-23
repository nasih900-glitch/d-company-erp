\set ON_ERROR_STOP on

-- Strictly read-only inventory of every public table, sequence value and
-- definition, and the public schema catalogue. Full-row JSON intentionally
-- includes audit_log semantic columns and every collection_source_guards
-- column. The catalogue generates one SELECT; \gexec executes it without
-- temporary DDL or PL/pgSQL writes.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL timezone = 'UTC';
SET LOCAL bytea_output = 'hex';
SET LOCAL search_path = pg_catalog, public;

\set grn_transition '{}'
\if :{?build72_grn_observation}
-- Project 0103's deterministic legacy records from retained source rows on
-- both sides of migration. The migrated functions can lock rows and cannot
-- serve as an independent, read-only reference for their own output.
WITH receipts AS (
    SELECT g.id, p.company_id, p.branch_id, p.supplier_id, g.received_by,
           g.idempotency_key, g.request_hash, g.created_at, g.journal_entry_id,
           k.terminal_id, k.terminal_id IS NOT NULL AS terminal_known,
           to_jsonb(g) - 'updated_at' AS grn,
           jsonb_build_object('id', p.id, 'company_id', p.company_id,
               'branch_id', p.branch_id, 'supplier_id', p.supplier_id,
               'po_number', p.po_number, 'total_minor', p.total_minor,
               'created_by', p.created_by, 'expected_at', p.expected_at) AS po
      FROM public.grns g
      JOIN public.purchase_orders p ON p.id = g.purchase_order_id
      LEFT JOIN public.idempotency_keys k
        ON k.key = g.idempotency_key AND k.request_hash = g.request_hash
       AND k.user_id IS NOT DISTINCT FROM g.received_by
     WHERE g.idempotency_key IS NOT NULL
), sources AS (
    SELECT r.*, jsonb_build_object(
           'grn', r.grn, 'po', r.po,
           'lines', (SELECT jsonb_agg(to_jsonb(l) - 'updated_at' ORDER BY l.created_at, l.id)
                       FROM public.grn_lines l WHERE l.grn_id = r.id),
           'po_lines', (SELECT jsonb_agg(to_jsonb(l) - 'updated_at' ORDER BY l.id)
                          FROM public.purchase_order_lines l WHERE l.purchase_order_id = (r.po->>'id')::uuid),
           'batches', (SELECT jsonb_agg(to_jsonb(b) - 'qty_on_hand' - 'updated_at' ORDER BY b.id)
                         FROM public.batches b WHERE b.grn_id = r.id),
           'ingredients', (SELECT jsonb_agg(jsonb_build_object('id', i.id,
                               'company_id', i.company_id, 'base_unit', i.base_unit) ORDER BY i.id)
                             FROM public.ingredients i WHERE i.id IN
                               (SELECT l.ingredient_id FROM public.grn_lines l WHERE l.grn_id = r.id)),
           'movements', (SELECT jsonb_agg(to_jsonb(m) - 'updated_at' ORDER BY m.id)
                           FROM public.stock_movements m WHERE m.ref_id = r.id),
           'journal', (SELECT to_jsonb(j) - 'updated_at' FROM public.journal_entries j
                        WHERE j.id = r.journal_entry_id),
           'journal_lines', (SELECT coalesce(jsonb_agg(to_jsonb(l) - 'updated_at' ORDER BY l.id), '[]')
                               FROM public.journal_lines l WHERE l.journal_entry_id = r.journal_entry_id)
           ) AS source
      FROM receipts r
), expected_rows AS (
    SELECT jsonb_build_object('id', s.id, 'action_key', s.idempotency_key,
               'company_id', s.company_id, 'branch_id', s.branch_id,
               'actor_user_id', s.received_by, 'terminal_id', s.terminal_id,
               'terminal_known', s.terminal_known, 'request_hash', s.request_hash,
               'outcome', 'accepted', 'accepted_grn_id', s.id, 'original_wire_json', NULL,
               'cancellation_reason', NULL, 'recovery_session_id', NULL, 'decided_at', s.created_at)::text AS decision,
           jsonb_build_object('id', s.id, 'profile', 'legacy', 'original_wire_json', NULL,
               'terminal_id', s.terminal_id, 'terminal_known', s.terminal_known, 'source', s.source,
               'response', jsonb_build_object('ok', true, 'id', s.grn->>'id',
                   'purchase_order_id', s.po->>'id', 'batches_created', jsonb_array_length(s.source->'lines'),
                   'batch_ids', (SELECT jsonb_agg(l.line->>'batch_id' ORDER BY l.ordinality)
                                  FROM jsonb_array_elements(s.source->'lines') WITH ORDINALITY AS l(line, ordinality)),
                   'line_ids', (SELECT jsonb_agg(l.line->>'id' ORDER BY l.ordinality)
                                 FROM jsonb_array_elements(s.source->'lines') WITH ORDINALITY AS l(line, ordinality)),
                   'total_minor', (s.po->>'total_minor')::bigint,
                   'supplier_invoice_no', s.grn->'supplier_invoice_no', 'journal_entry_id', s.grn->'journal_entry_id',
                   'accounting_status', CASE WHEN s.journal_entry_id IS NULL THEN 'no_balance' ELSE 'posted' END))::text AS proof,
           (to_jsonb(jsonb_populate_record(NULL::public.audit_log, jsonb_build_object(
               'company_id', s.company_id, 'actor_user_id', s.received_by,
               'action', 'grn_command_accepted', 'entity_type', 'GrnCompletionProof', 'entity_id', s.id,
               'after', jsonb_build_object('purpose', 'inventory_grn_receive')))) - 'id' - 'created_at')::text AS audit
      FROM sources s
), expected AS (
    SELECT count(*)::bigint AS row_count,
           encode(sha256(convert_to(coalesce(string_agg(decision, chr(10) ORDER BY decision), ''), 'UTF8')), 'hex') AS decisions,
           encode(sha256(convert_to(coalesce(string_agg(proof, chr(10) ORDER BY proof), ''), 'UTF8')), 'hex') AS proofs,
           encode(sha256(convert_to(coalesce(string_agg(audit, chr(10) ORDER BY audit), ''), 'UTF8')), 'hex') AS audits
      FROM expected_rows
), boundary AS (
    -- The verifier independently anchors this boundary to the stopped sequence.
    SELECT CASE WHEN row_count > 0 AND to_regclass('public.grn_completion_proofs') IS NOT NULL
                THEN (SELECT last_value::numeric FROM public.audit_log_id_seq) - row_count + 1 END AS first_id
      FROM expected
), audit_rows AS (
    SELECT a.id, a.created_at, to_jsonb(a)::text AS full_row,
           (to_jsonb(a) - 'id' - 'created_at')::text AS material,
           b.first_id IS NOT NULL AND a.id >= b.first_id AS appended
      FROM public.audit_log a CROSS JOIN boundary b
), audit_inventory AS (
    SELECT count(*) FILTER (WHERE NOT appended)::bigint AS prefix_count,
           max(id) FILTER (WHERE NOT appended) AS prefix_high_water,
           encode(sha256(convert_to(coalesce(string_agg(full_row, chr(10) ORDER BY full_row)
               FILTER (WHERE NOT appended), ''), 'UTF8')), 'hex') AS prefix_sha256,
           count(*) FILTER (WHERE appended)::bigint AS append_count,
           min(id) FILTER (WHERE appended) AS first_id,
           max(id) FILTER (WHERE appended) AS last_id,
           count(DISTINCT created_at) FILTER (WHERE appended) AS timestamp_count,
           coalesce(bool_and(created_at <= transaction_timestamp()) FILTER (WHERE appended), true) AS timestamps_observed,
           encode(sha256(convert_to(coalesce(string_agg(material, chr(10) ORDER BY material)
               FILTER (WHERE appended), ''), 'UTF8')), 'hex') AS append_sha256
      FROM audit_rows
)
SELECT jsonb_build_object('legacy_grn_backfill', jsonb_build_object(
    'row_count', e.row_count, 'decision_sha256', e.decisions, 'proof_sha256', e.proofs, 'audit_sha256', e.audits,
    'audit_prefix', jsonb_build_object('row_count', a.prefix_count, 'row_sha256', a.prefix_sha256,
                                      'high_water', a.prefix_high_water),
    'audit_append', jsonb_build_object('row_count', a.append_count, 'row_sha256', a.append_sha256,
                                      'first_id', a.first_id, 'last_id', a.last_id,
                                      'timestamp_count', a.timestamp_count, 'timestamps_observed', a.timestamps_observed)
)) AS grn_transition
FROM expected e CROSS JOIN audit_inventory a
\gset
\endif

-- chr(10) avoids an extra escape layer in the generated SQL: the full-row
-- digest must use the same newline separator as the direct Gaming projection.
WITH table_terms AS (
    SELECT string_agg(
        format(
            '(%L, (SELECT count(*)::bigint FROM public.%I), '
            ' (SELECT encode(sha256(convert_to(coalesce(string_agg(row_text, chr(10) ORDER BY row_text), ''''), ''UTF8'')), ''hex'') '
            ' FROM (SELECT to_jsonb(t)::text AS row_text FROM public.%I AS t) AS rows))',
            c.relname, c.relname, c.relname
        ), E',\n' ORDER BY c.relname
    ) AS statement
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
), sequence_terms AS (
    SELECT string_agg(
        format(
            '(%L, (SELECT last_value::bigint FROM public.%I), '
            ' (SELECT is_called FROM public.%I), %s::bigint, %s::bigint, %s::bigint, %s::bigint, %s::bigint, %L::boolean, %L)',
            c.relname, c.relname, c.relname, s.seqstart, s.seqincrement,
            s.seqmin, s.seqmax, s.seqcache, s.seqcycle,
            coalesce(owned.owner_name, '')
        ), E',\n' ORDER BY c.relname
    ) AS statement
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_sequence s ON s.seqrelid = c.oid
      LEFT JOIN LATERAL (
          SELECT format('%I.%I.%I', owner_ns.nspname, owner_table.relname, owner_col.attname) AS owner_name
            FROM pg_depend dependency
            JOIN pg_class owner_table ON owner_table.oid = dependency.refobjid
            JOIN pg_namespace owner_ns ON owner_ns.oid = owner_table.relnamespace
            JOIN pg_attribute owner_col
              ON owner_col.attrelid = owner_table.oid
             AND owner_col.attnum = dependency.refobjsubid
           WHERE dependency.classid = 'pg_class'::regclass
             AND dependency.objid = c.oid
             AND dependency.deptype IN ('a', 'i')
           ORDER BY dependency.deptype
           LIMIT 1
      ) owned ON true
     WHERE n.nspname = 'public' AND c.relkind = 'S'
), generated AS (
    SELECT format(
$query$
WITH table_inventory(table_name, row_count, row_sha256) AS (
    VALUES %s
), sequence_inventory(
    sequence_name, last_value, is_called, start_value, increment_by,
    min_value, max_value, cache_size, cycle, owned_by
) AS (
    VALUES %s
), catalogue_rows AS (
    SELECT 'column|' || table_name || '|' || ordinal_position || '|' || column_name
           || '|' || data_type || '|' || is_nullable || '|'
           || coalesce(column_default, '') || '|'
           || coalesce(character_maximum_length::text, '') || '|'
           || coalesce(character_octet_length::text, '') || '|'
           || coalesce(numeric_precision::text, '') || '|'
           || coalesce(numeric_precision_radix::text, '') || '|'
           || coalesce(numeric_scale::text, '') || '|'
           || coalesce(datetime_precision::text, '') || '|'
           || coalesce(interval_type, '') || '|'
           || coalesce(interval_precision::text, '') || '|'
           || coalesce(character_set_schema, '') || '|'
           || coalesce(character_set_name, '') || '|'
           || coalesce(collation_schema, '') || '|'
           || coalesce(collation_name, '') || '|'
           || coalesce(domain_schema, '') || '|'
           || coalesce(domain_name, '') || '|'
           || coalesce(udt_schema, '') || '|'
           || coalesce(udt_name, '') || '|'
           || coalesce(is_identity, '') || '|'
           || coalesce(identity_generation, '') || '|'
           || coalesce(identity_start, '') || '|'
           || coalesce(identity_increment, '') || '|'
           || coalesce(identity_maximum, '') || '|'
           || coalesce(identity_minimum, '') || '|'
           || coalesce(identity_cycle, '') || '|'
           || coalesce(is_generated, '') || '|'
           || coalesce(generation_expression, '') || '|'
           || coalesce(is_updatable, '') AS item
      FROM information_schema.columns WHERE table_schema = 'public'
    UNION ALL
    SELECT 'constraint|' || conrelid::regclass::text || '|' || conname || '|'
           || pg_get_constraintdef(oid, true)
      FROM pg_constraint WHERE connamespace = 'public'::regnamespace
    UNION ALL
    SELECT 'index|' || tablename || '|' || indexname || '|' || indexdef
      FROM pg_indexes WHERE schemaname = 'public'
    UNION ALL
    SELECT 'trigger|' || event_object_table || '|' || trigger_name || '|'
           || event_manipulation || '|' || action_timing || '|' || action_statement
      FROM information_schema.triggers WHERE trigger_schema = 'public'
    UNION ALL
    SELECT 'policy|' || tablename || '|' || policyname || '|' || permissive || '|'
           || roles::text || '|' || cmd || '|' || coalesce(qual, '') || '|'
           || coalesce(with_check, '')
      FROM pg_policies WHERE schemaname = 'public'
    UNION ALL
    SELECT 'function|' || p.proname || '|' || pg_get_function_identity_arguments(p.oid)
           || '|' || pg_get_functiondef(p.oid)
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.prokind IN ('f', 'p')
), trigger_binding_rows AS (
    SELECT 'trigger_binding|' || c.relname || '|' || t.tgname || '|'
           || t.tgenabled::text || '|' || t.tgdeferrable::text || '|'
           || t.tginitdeferred::text || '|' || pg_get_triggerdef(t.oid, true) AS item
      FROM pg_trigger t
      JOIN pg_class c ON c.oid = t.tgrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND NOT t.tgisinternal
), trigger_bindings AS (
    SELECT count(*)::bigint AS row_count,
           encode(sha256(convert_to(coalesce(string_agg(item, E'\n' ORDER BY item COLLATE "C"), ''), 'UTF8')), 'hex') AS sha256,
           coalesce(jsonb_agg(item ORDER BY item COLLATE "C"), '[]'::jsonb) AS rows
      FROM trigger_binding_rows
), gaming_legacy AS (
    SELECT count(*)::bigint AS row_count,
           count(*) FILTER (WHERE to_jsonb(t)->>'retained_addon_order_id' IS NOT NULL)::bigint AS nonnull_count,
           encode(sha256(convert_to(coalesce(string_agg(
               (to_jsonb(t) - 'retained_addon_order_id')::text, E'\n'
               ORDER BY (to_jsonb(t) - 'retained_addon_order_id')::text), ''), 'UTF8')), 'hex') AS sha256
      FROM public.gaming_sessions t
), catalogue AS (
    SELECT count(*)::bigint AS row_count,
           encode(sha256(convert_to(coalesce(string_agg(item, E'\n' ORDER BY item COLLATE "C"), ''), 'UTF8')), 'hex') AS sha256,
           coalesce(jsonb_agg(item ORDER BY item COLLATE "C"), '[]'::jsonb) AS rows
      FROM catalogue_rows
)
SELECT jsonb_build_object(
    'schema_version', 2,
    'kind', 'build55-full-transition-v2',
    'database_revision', (SELECT version_num FROM public.alembic_version),
    'database_identity', jsonb_build_object(
        'database_name', current_database(),
        'database_oid', (SELECT oid::bigint FROM pg_database WHERE datname = current_database()),
        'system_identifier', (SELECT system_identifier::text FROM pg_control_system())
    ),
    'gaming_sessions_legacy_projection', jsonb_build_object(
        'column_present', EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'gaming_sessions'
              AND column_name = 'retained_addon_order_id'),
        'row_count', (SELECT row_count FROM gaming_legacy),
        'row_sha256', (SELECT sha256 FROM gaming_legacy),
        'nonnull_count', (SELECT nonnull_count FROM gaming_legacy)
    ),
    'trigger_catalog_row_count', (SELECT row_count FROM trigger_bindings),
    'trigger_catalog_sha256', (SELECT sha256 FROM trigger_bindings),
    'trigger_catalog_rows', (SELECT rows FROM trigger_bindings),
    'schema_catalog_row_count', (SELECT row_count FROM catalogue),
    'schema_catalog_sha256', (SELECT sha256 FROM catalogue),
    'schema_catalog_rows', (SELECT rows FROM catalogue),
    'tables', (SELECT jsonb_object_agg(table_name, jsonb_build_object(
        'row_count', row_count, 'row_sha256', row_sha256) ORDER BY table_name)
        FROM table_inventory),
    'sequences', (SELECT jsonb_object_agg(sequence_name, jsonb_build_object(
        'last_value', last_value, 'is_called', is_called, 'start_value', start_value,
        'increment_by', increment_by, 'min_value', min_value, 'max_value', max_value,
        'cache_size', cache_size, 'cycle', cycle, 'owned_by', owned_by)
        ORDER BY sequence_name) FROM sequence_inventory)
) || %L::jsonb;
$query$,
        table_terms.statement,
        sequence_terms.statement,
        :'grn_transition'
    ) AS statement
      FROM table_terms CROSS JOIN sequence_terms
)
SELECT statement FROM generated
\gexec

COMMIT;
