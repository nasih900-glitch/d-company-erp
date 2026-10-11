\set ON_ERROR_STOP on
\set QUIET on

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL TIME ZONE 'UTC';
SET LOCAL bytea_output = 'hex';

\set QUIET off

WITH installation_rows AS (
    SELECT installation.*,
           concat_ws(
               E'\x1f',
               installation.id::text,
               installation.installation_id::text,
               installation.company_id::text,
               coalesce(installation.terminal_id::text, ''),
               installation.platform,
               installation.version_name,
               installation.version_code::text,
               installation.pending_outbox_count::text,
               to_char(
                   installation.last_successful_sync_at AT TIME ZONE 'UTC',
                   'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
               ),
               to_char(
                   installation.last_seen_at AT TIME ZONE 'UTC',
                   'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
               )
           ) AS nonzero_identity_projection,
           concat_ws(
               E'\x1f',
               installation.id::text,
               installation.installation_id::text,
               installation.company_id::text,
               installation.created_at::text
           ) AS immutable_identity_projection
      FROM client_installations installation
),
nonzero_rows AS (
    SELECT installation.*,
           encode(
               sha256(convert_to(installation.nonzero_identity_projection, 'UTF8')),
               'hex'
           ) AS identity_sha256
      FROM installation_rows installation
     WHERE installation.pending_outbox_count IS DISTINCT FROM 0
),
fleet AS (
    SELECT count(*) AS installation_row_count,
           coalesce(sum(installation.pending_outbox_count), 0) AS pending_outbox_count,
           count(*) FILTER (
               WHERE installation.pending_outbox_count IS DISTINCT FROM 0
           ) AS nonzero_installation_count,
           encode(
               sha256(
                   convert_to(
                       coalesce(
                           string_agg(
                               installation.immutable_identity_projection,
                               E'\n' ORDER BY installation.id::text
                           ),
                           ''
                       ),
                       'UTF8'
                   )
               ),
               'hex'
           ) AS all_installations_identity_sha256
      FROM installation_rows installation
),
nonzero_fleet AS (
    SELECT coalesce(
               jsonb_agg(
                   jsonb_build_object(
                       'row_id', installation.id,
                       'installation_id', installation.installation_id,
                       'company_id', installation.company_id,
                       'terminal_id', installation.terminal_id,
                       'platform', installation.platform,
                       'version_name', installation.version_name,
                       'version_code', installation.version_code,
                       'pending_outbox_count', installation.pending_outbox_count,
                       'last_successful_sync_at', to_char(
                           installation.last_successful_sync_at AT TIME ZONE 'UTC',
                           'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
                       ),
                       'last_seen_at', to_char(
                           installation.last_seen_at AT TIME ZONE 'UTC',
                           'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'
                       ),
                       'identity_sha256', installation.identity_sha256
                   )
                   ORDER BY installation.id::text
               ),
               '[]'::jsonb
           ) AS installations,
           encode(
               sha256(
                   convert_to(
                       coalesce(
                           string_agg(
                               installation.identity_sha256,
                               E'\n' ORDER BY installation.id::text
                           ),
                           ''
                       ),
                       'UTF8'
                   )
               ),
               'hex'
           ) AS identity_sha256
      FROM nonzero_rows installation
),
client_installation_guard AS (
    SELECT count(*) AS trigger_count,
           max(
               encode(sha256(convert_to(procedure.prosrc, 'UTF8')), 'hex')
           ) AS function_sha256,
           max(
               encode(
                   sha256(convert_to(pg_get_functiondef(procedure.oid), 'UTF8')),
                   'hex'
               )
           ) AS function_definition_sha256,
           max(
               encode(
                   sha256(convert_to(pg_get_triggerdef(trigger_row.oid, true), 'UTF8')),
                   'hex'
               )
           ) AS trigger_definition_sha256
      FROM pg_trigger trigger_row
      JOIN pg_proc procedure ON procedure.oid = trigger_row.tgfoid
      JOIN pg_namespace procedure_namespace
        ON procedure_namespace.oid = procedure.pronamespace
      JOIN pg_language procedure_language
        ON procedure_language.oid = procedure.prolang
      JOIN pg_roles procedure_owner
        ON procedure_owner.oid = procedure.proowner
     WHERE trigger_row.tgrelid = 'client_installations'::regclass
       AND trigger_row.tgname = 'trg_client_installations_scope'
       AND NOT trigger_row.tgisinternal
       AND trigger_row.tgenabled = 'O'
       AND procedure.proname = 'dcompany_validate_client_installation_scope'
       AND pg_get_function_identity_arguments(procedure.oid) = ''
       AND procedure_namespace.nspname = 'public'
       AND procedure_language.lanname = 'plpgsql'
       AND procedure_owner.rolname = 'erp'
       AND procedure.prokind = 'f'
       AND procedure.prorettype = 'trigger'::regtype
       AND procedure.pronargs = 0
       AND procedure.provolatile = 'v'
       AND NOT procedure.prosecdef
       AND NOT procedure.proleakproof
       AND NOT procedure.proisstrict
       AND NOT procedure.proretset
       AND procedure.proparallel = 'u'
       AND procedure.proconfig IS NULL
)
SELECT jsonb_build_object(
    'schema_version', 1,
    'database_revision', (SELECT version_num FROM alembic_version),
    'installation_row_count', fleet.installation_row_count,
    'pending_outbox_count', fleet.pending_outbox_count,
    'nonzero_installation_count', fleet.nonzero_installation_count,
    'all_installations_identity_sha256', fleet.all_installations_identity_sha256,
    'nonzero_installations_identity_sha256', nonzero_fleet.identity_sha256,
    'nonzero_installations', nonzero_fleet.installations,
    'client_installation_guard_trigger_count', client_installation_guard.trigger_count,
    'client_installation_guard_function_sha256', client_installation_guard.function_sha256,
    'client_installation_guard_function_definition_sha256',
        client_installation_guard.function_definition_sha256,
    'client_installation_guard_trigger_definition_sha256',
        client_installation_guard.trigger_definition_sha256
)
  FROM fleet
 CROSS JOIN nonzero_fleet
 CROSS JOIN client_installation_guard;

\set QUIET on
ROLLBACK;
