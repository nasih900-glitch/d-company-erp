-- Production wrapper for the shared, hash-pinned Code30.3 cleanup body.
-- It must be invoked only by apply-code30-3-combined-cleanup.sh with ingress/backend stopped.
\set ON_ERROR_STOP on
\set QUIET on

SELECT set_config('c3c.manifest_path', :'manifest_container_path', false);

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
INSERT INTO c3c_execution VALUES ('apply', :'expected_database_name');

DO $$
BEGIN
    IF current_database() <> 'erp'
       OR current_database() IS DISTINCT FROM (SELECT expected_database_name FROM c3c_execution) THEN
        RAISE EXCEPTION 'combined cleanup apply database identity is invalid';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_stat_activity
         WHERE datname = current_database() AND pid <> pg_backend_pid()
           AND backend_type = 'client backend'
    ) THEN
        RAISE EXCEPTION 'another client connection is attached to the cleanup database';
    END IF;
    IF NOT pg_try_advisory_xact_lock(hashtext('dcompany-code30.3-combined-cleanup')) THEN
        RAISE EXCEPTION 'another combined cleanup transaction holds the advisory lock';
    END IF;
END
$$;

\ir code30-3-combined-cleanup-body.sql

COMMIT;
\echo CODE30_3_COMBINED_CLEANUP_COMMITTED

DO $$
DECLARE cleanup_id text := (pg_read_file(current_setting('c3c.manifest_path'))::jsonb)->>'cleanup_id';
BEGIN
    IF (SELECT count(*) FROM audit_log
         WHERE action = 'verified_trial_cleanup'
           AND entity_type = 'TrialCleanupReceipt'
           AND entity_id = cleanup_id
           AND after->>'receipt_version' = '2') <> 1 THEN
        RAISE EXCEPTION 'committed cleanup receipt is absent or duplicated';
    END IF;
END
$$;

\set QUIET off
SELECT jsonb_build_object(
    'mode', 'apply committed',
    'cleanup_id', a.entity_id,
    'tagged_app_source_git_sha', a.after->>'source_git_sha',
    'maintenance_sql_sha256', a.after->'evidence'->>'maintenance_sql_sha256',
    'state_fingerprint', a.before->>'state_fingerprint',
    'receipt_audit_id', a.id
)::text
FROM audit_log a
WHERE a.action = 'verified_trial_cleanup'
  AND a.entity_type = 'TrialCleanupReceipt'
  AND a.entity_id = (pg_read_file(current_setting('c3c.manifest_path'))::jsonb)->>'cleanup_id';
