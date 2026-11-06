\set ON_ERROR_STOP on

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '60s';
SET LOCAL lock_timeout = '5s';
SET LOCAL timezone = 'UTC';
SET LOCAL bytea_output = 'hex';
SET LOCAL search_path = pg_catalog, public;

WITH legacy_shifts AS (
    SELECT (to_jsonb(t) - 'operator_user_id' - 'operator_revision')::text AS row_text,
           (to_jsonb(t)->>'operator_user_id' IS NOT NULL
            OR to_jsonb(t)->>'operator_revision' IS NOT NULL) AS has_custody
      FROM public.shifts t
)
SELECT jsonb_build_object(
    'database_revision', (SELECT version_num FROM public.alembic_version),
    'database_identity', jsonb_build_object(
        'database_name', current_database(),
        'database_oid', (SELECT oid::bigint FROM pg_database WHERE datname = current_database()),
        'system_identifier', (SELECT system_identifier::text FROM pg_control_system())
    ),
    'custody_columns', (SELECT count(*) FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'shifts'
          AND column_name IN ('operator_user_id', 'operator_revision')),
    'nonnull_count', count(*) FILTER (WHERE has_custody),
    'row_count', count(*),
    'row_sha256', encode(sha256(convert_to(coalesce(
        string_agg(row_text, E'\n' ORDER BY row_text), ''), 'UTF8')), 'hex')
)
FROM legacy_shifts;

COMMIT;
