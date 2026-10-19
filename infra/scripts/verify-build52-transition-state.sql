\set ON_ERROR_STOP on
\set QUIET on

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL TIME ZONE 'UTC';
SET LOCAL bytea_output = 'hex';
SET LOCAL search_path = pg_catalog, public;

-- PostgreSQL forbids even temporary DDL in a read-only transaction. Generate
-- one SELECT from the trusted catalog and execute that SELECT with psql's
-- \gexec. Both the generator and generated statement run inside the same
-- repeatable-read, read-only snapshot. Generated relations are always
-- explicitly schema-qualified so a public table cannot shadow pg_catalog.
WITH preserved_sql AS (
    SELECT string_agg(
        CASE WHEN tablename = 'audit_log' THEN format(
            'SELECT %L::text AS table_name, count(*)::bigint AS row_count, encode(sha256(convert_to(coalesce(string_agg((to_jsonb(row_data) - ARRAY[''action_code'',''category'',''stage'',''outcome'',''operation_id'',''source_reference'',''actor_kind'',''target_user_id'',''business_details'',''catalogue_version'']::text[])::text, E''\n'' ORDER BY id), ''''), ''UTF8'')), ''hex'') AS row_sha256 FROM %I.%I row_data',
            tablename,
            'public',
            tablename
        ) ELSE format(
            'SELECT %L::text AS table_name, count(*)::bigint AS row_count, encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E''\n'' ORDER BY to_jsonb(row_data)::text), ''''), ''UTF8'')), ''hex'') AS row_sha256 FROM %I.%I row_data',
            tablename,
            'public',
            tablename
        ) END,
        E'\nUNION ALL\n' ORDER BY tablename
    ) AS statement
      FROM pg_tables
     WHERE schemaname = 'public'
       AND tablename <> 'alembic_version'
       AND tablename NOT IN (
           'collection_source_guards',
           'payment_tender_corrections',
           'daily_tender_reconciliations'
       )
), sequence_sql AS (
    SELECT string_agg(
        format(
            'SELECT %L::text AS sequence_name, %s::bigint AS start_value, %s::bigint AS min_value, %s::bigint AS max_value, %s::bigint AS increment_by, %s::bigint AS cache_size, %L::boolean AS cycle, %L::text AS owned_by, sequence_state.last_value::bigint AS last_value, sequence_state.is_called AS is_called FROM %I.%I sequence_state',
            sequence_relation.relname,
            sequence_definition.seqstart,
            sequence_definition.seqmin,
            sequence_definition.seqmax,
            sequence_definition.seqincrement,
            sequence_definition.seqcache,
            sequence_definition.seqcycle::text,
            CASE
                WHEN owned_relation.relname IS NULL THEN NULL
                ELSE owned_relation.relname || '.' || owned_column.attname
            END,
            'public',
            sequence_relation.relname
        ),
        E'\nUNION ALL\n' ORDER BY sequence_relation.relname
    ) AS statement
      FROM pg_class sequence_relation
      JOIN pg_namespace sequence_namespace
        ON sequence_namespace.oid = sequence_relation.relnamespace
      JOIN pg_sequence sequence_definition
        ON sequence_definition.seqrelid = sequence_relation.oid
      LEFT JOIN pg_depend ownership
        ON ownership.classid = 'pg_class'::regclass
       AND ownership.objid = sequence_relation.oid
       AND ownership.objsubid = 0
       AND ownership.deptype IN ('a', 'i')
      LEFT JOIN pg_class owned_relation
        ON owned_relation.oid = ownership.refobjid
      LEFT JOIN pg_attribute owned_column
        ON owned_column.attrelid = ownership.refobjid
       AND owned_column.attnum = ownership.refobjsubid
     WHERE sequence_namespace.nspname = 'public'
       AND sequence_relation.relkind = 'S'
), finance_names(table_name) AS (
    VALUES
        ('daily_tender_reconciliations'::text),
        ('payment_tender_corrections'::text)
), finance_sql AS (
    SELECT string_agg(
        CASE WHEN to_regclass('public.' || table_name) IS NULL THEN format(
            'SELECT %L::text AS table_name, ''absent''::text AS state, NULL::bigint AS row_count, NULL::text AS row_sha256',
            table_name
        ) ELSE format(
            'SELECT %L::text AS table_name, ''present''::text AS state, count(*)::bigint AS row_count, encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E''\n'' ORDER BY id), ''''), ''UTF8'')), ''hex'') AS row_sha256 FROM %I.%I row_data',
            table_name,
            'public',
            table_name
        ) END,
        E'\nUNION ALL\n' ORDER BY table_name
    ) AS statement
      FROM finance_names
)
SELECT format(
$generated$
WITH build52_table_inventory AS (
%s
), build52_sequence_inventory AS (
%s
), build52_finance_history AS (
%s
)
SELECT jsonb_build_object(
    'schema_version', 1,
    'kind', 'build52-quiesced-transition-v1',
    'database_revision', (SELECT version_num FROM alembic_version),
    'database_identity', jsonb_build_object(
        'system_identifier', (SELECT system_identifier::text FROM pg_control_system()),
        'database_name', current_database(),
        'database_oid', (
            SELECT oid::text FROM pg_database WHERE datname = current_database()
        )
    ),
    'table_inventory', (
        SELECT coalesce(
            jsonb_agg(
                jsonb_build_object(
                    'table_name', table_name,
                    'row_count', row_count,
                    'row_sha256', row_sha256
                )
                ORDER BY table_name
            ),
            '[]'::jsonb
        )
          FROM build52_table_inventory
    ),
    'sequence_inventory', (
        SELECT coalesce(
            jsonb_agg(to_jsonb(sequence_row) ORDER BY sequence_name),
            '[]'::jsonb
        )
          FROM build52_sequence_inventory sequence_row
    ),
    'reserved_schema', jsonb_build_object(
        'audit_semantic_columns', (
            SELECT coalesce(
                jsonb_agg(
                    jsonb_build_object(
                        'column_name', attribute.attname,
                        'data_type', format_type(attribute.atttypid, attribute.atttypmod),
                        'not_null', attribute.attnotnull,
                        'default', pg_get_expr(column_default.adbin, column_default.adrelid)
                    ) ORDER BY attribute.attnum
                ),
                '[]'::jsonb
            )
              FROM pg_attribute attribute
              LEFT JOIN pg_attrdef column_default
                ON column_default.adrelid = attribute.attrelid
               AND column_default.adnum = attribute.attnum
             WHERE attribute.attrelid = to_regclass('public.audit_log')
               AND attribute.attnum > 0
               AND NOT attribute.attisdropped
               AND attribute.attname = ANY(ARRAY[
                   'action_code','category','stage','outcome','operation_id',
                   'source_reference','actor_kind','target_user_id',
                   'business_details','catalogue_version'
               ]::text[])
        ),
        'collection_source_guards', jsonb_build_object(
            'state', CASE
                WHEN to_regclass('public.collection_source_guards') IS NULL
                THEN 'absent' ELSE 'present' END,
            'columns', (
                SELECT coalesce(
                    jsonb_agg(
                        jsonb_build_object(
                            'column_name', attribute.attname,
                            'data_type', format_type(attribute.atttypid, attribute.atttypmod),
                            'not_null', attribute.attnotnull,
                            'default', pg_get_expr(column_default.adbin, column_default.adrelid)
                        ) ORDER BY attribute.attnum
                    ),
                    '[]'::jsonb
                )
                  FROM pg_attribute attribute
                  LEFT JOIN pg_attrdef column_default
                    ON column_default.adrelid = attribute.attrelid
                   AND column_default.adnum = attribute.attnum
                 WHERE attribute.attrelid = to_regclass('public.collection_source_guards')
                   AND attribute.attnum > 0
                   AND NOT attribute.attisdropped
            )
        )
    ),
    'finance_history', (
        SELECT jsonb_object_agg(
            table_name,
            jsonb_build_object(
                'state', state,
                'row_count', row_count,
                'row_sha256', row_sha256
            )
            ORDER BY table_name
        )
          FROM build52_finance_history
    )
)
$generated$,
    preserved_sql.statement,
    sequence_sql.statement,
    finance_sql.statement
)
FROM preserved_sql CROSS JOIN sequence_sql CROSS JOIN finance_sql
\gexec

\set QUIET on
ROLLBACK;
