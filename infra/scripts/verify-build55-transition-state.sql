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

WITH table_terms AS (
    SELECT string_agg(
        format(
            '(%L, (SELECT count(*)::bigint FROM public.%I), '
            ' (SELECT encode(sha256(convert_to(coalesce(string_agg(row_text, E''\\n'' ORDER BY row_text), ''''), ''UTF8'')), ''hex'') '
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
), catalogue AS (
    SELECT count(*)::bigint AS row_count,
           encode(sha256(convert_to(coalesce(string_agg(item, E'\n' ORDER BY item COLLATE "C"), ''), 'UTF8')), 'hex') AS sha256,
           coalesce(jsonb_agg(item ORDER BY item COLLATE "C"), '[]'::jsonb) AS rows
      FROM catalogue_rows
)
SELECT jsonb_build_object(
    'schema_version', 1,
    'kind', 'build55-full-transition-v1',
    'database_revision', (SELECT version_num FROM public.alembic_version),
    'database_identity', jsonb_build_object(
        'database_name', current_database(),
        'database_oid', (SELECT oid::bigint FROM pg_database WHERE datname = current_database()),
        'system_identifier', (SELECT system_identifier::text FROM pg_control_system())
    ),
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
);
$query$,
        table_terms.statement,
        sequence_terms.statement
    ) AS statement
      FROM table_terms CROSS JOIN sequence_terms
)
SELECT statement FROM generated
\gexec

COMMIT;
