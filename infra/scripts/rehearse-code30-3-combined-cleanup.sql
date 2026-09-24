-- Restore-only wrapper for the shared Code30.3 combined cleanup transaction body.
-- The included body is byte-identical to production apply; this wrapper always rolls back.
\set ON_ERROR_STOP on
\set QUIET on

BEGIN ISOLATION LEVEL SERIALIZABLE;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '300s';
SET LOCAL TIME ZONE 'UTC';
SET LOCAL bytea_output = 'hex';
SET LOCAL DateStyle = 'ISO, YMD';
SET LOCAL IntervalStyle = 'postgres';
SET LOCAL extra_float_digits = 1;

CREATE TEMP TABLE c3c_execution (
    execution_mode text NOT NULL,
    expected_database_name text NOT NULL
) ON COMMIT DROP;
INSERT INTO c3c_execution VALUES ('rehearsal', :'expected_database_name');

DO $$
BEGIN
    IF current_database() !~ '^code30_combined_restore_[0-9]+_[0-9]+$'
       OR current_database() IS DISTINCT FROM (SELECT expected_database_name FROM c3c_execution) THEN
        RAISE EXCEPTION 'combined cleanup rehearsal is restricted to its named disposable restore database';
    END IF;
    IF NOT pg_try_advisory_xact_lock(hashtext('dcompany-code30.3-combined-cleanup')) THEN
        RAISE EXCEPTION 'another combined cleanup transaction holds the advisory lock';
    END IF;
END
$$;

\ir code30-3-combined-cleanup-body.sql

\set QUIET off
SELECT jsonb_build_object(
    'mode', 'restore rehearsal (rolled back)',
    'cleanup_id', (SELECT document->>'cleanup_id' FROM c3c_manifest),
    'tagged_app_source_git_sha', 'ad5adfb93c3488f1f931ca27da53824aa57d3dc5',
    'maintenance_sql_sha256', :'maintenance_sql_sha256',
    'state_fingerprint', (SELECT state_fingerprint FROM c3c_state),
    'deleted_counts', (SELECT jsonb_object_agg(table_name, row_count) FROM c3c_deleted),
    'receipt_audit_id', (SELECT id FROM c3c_receipt)
)::text;
\set QUIET on

ROLLBACK;
