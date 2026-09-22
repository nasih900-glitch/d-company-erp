\set ON_ERROR_STOP on
\set QUIET on

BEGIN TRANSACTION READ ONLY;
SET LOCAL TIME ZONE 'UTC';
SET LOCAL bytea_output = 'hex';

\set QUIET off

WITH receipt_candidates AS (
    SELECT receipt.id,
           receipt.created_at,
           jsonb_build_object(
               'id', receipt.id,
               'actor_user_id', receipt.actor_user_id,
               'company_id', receipt.company_id,
               'action', receipt.action,
               'entity_type', receipt.entity_type,
               'entity_id', receipt.entity_id,
               'before', receipt.before,
               'after', receipt.after,
               'ip', receipt.ip,
               'user_agent', receipt.user_agent,
               'terminal_id', receipt.terminal_id,
               'request_id', receipt.request_id,
               'client_platform', receipt.client_platform,
               'client_version_code', receipt.client_version_code,
               'client_action_id', receipt.client_action_id,
               'client_reported_at', receipt.client_reported_at,
               'client_was_offline', receipt.client_was_offline,
               'synced_at', receipt.synced_at,
               'reason', receipt.reason
           ) AS payload
      FROM audit_log receipt
     WHERE receipt.company_id = '8f323fba-4358-45fe-9d3b-a8e0fae52993'
       AND (
           receipt.action = 'production_trial_cleanup'
           OR receipt.entity_type = 'ReleaseCleanup'
           OR receipt.entity_id = 'code30.1-20260920'
           OR receipt.request_id = 'code30.1-production-trial-cleanup-20260920'
           OR receipt.client_action_id = 'production-trial-cleanup-20260920'
       )
),
normal_login_suffix AS (
    SELECT audit.id, to_jsonb(audit) AS full_row
     FROM audit_log audit
     WHERE audit.id > 28202
       AND audit.id < coalesce((SELECT min(id) FROM receipt_candidates), 9223372036854775807)
       AND audit.action = 'login_success'
       AND audit.entity_type = 'User'
       AND audit.actor_user_id IS NOT NULL
       AND audit.entity_id = audit.actor_user_id::text
       AND audit.company_id = '8f323fba-4358-45fe-9d3b-a8e0fae52993'
       AND audit.before = 'null'::jsonb
       AND jsonb_typeof(audit.after) = 'object'
       AND audit.after ?& ARRAY['email', 'result', 'name', 'roles']
       AND CASE WHEN jsonb_typeof(audit.after) = 'object'
                THEN (SELECT count(*) FROM jsonb_object_keys(audit.after))
                ELSE -1 END = 4
       AND jsonb_typeof(audit.after->'email') = 'string'
       AND nullif(btrim(audit.after->>'email'), '') IS NOT NULL
       AND jsonb_typeof(audit.after->'name') = 'string'
       AND nullif(btrim(audit.after->>'name'), '') IS NOT NULL
       AND audit.after->>'result' = 'login_success'
       AND jsonb_typeof(audit.after->'roles') = 'array'
       AND jsonb_array_length(
               CASE WHEN jsonb_typeof(audit.after->'roles') = 'array'
                    THEN audit.after->'roles' ELSE '[]'::jsonb END
           ) > 0
       AND NOT EXISTS (
           SELECT 1
             FROM jsonb_array_elements(
                      CASE WHEN jsonb_typeof(audit.after->'roles') = 'array'
                           THEN audit.after->'roles' ELSE '[]'::jsonb END
                  ) role_value
            WHERE jsonb_typeof(role_value) <> 'string'
       )
       AND audit.ip IS NOT NULL
       AND audit.user_agent IS NOT NULL
       AND audit.terminal_id IS NULL
       AND audit.request_id IS NOT NULL
       AND audit.client_platform IN ('android', 'ios', 'web')
       AND audit.client_action_id IS NULL
       AND audit.client_reported_at IS NULL
       AND audit.client_was_offline IS FALSE
       AND audit.synced_at IS NULL
       AND audit.reason IS NULL
),
retained_audit_baseline AS (
    SELECT count(*) AS row_count,
           encode(
               sha256(
                   convert_to(
                       coalesce(
                           string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id),
                           ''
                       ),
                       'UTF8'
                   )
               ),
               'hex'
           ) AS row_sha256
      FROM audit_log row_data
     WHERE id <= 28202
       AND id NOT IN (
        1001, 1004, 1142, 1143, 1150, 1161, 1162, 1163, 1164, 1165,
        1166, 1167, 1168, 1217, 1232, 1233, 1234, 1235, 1236, 1237,
        1238, 1239, 1240, 28174, 28175, 28176, 28177, 28180, 28183,
        28188, 28191, 28194, 28197, 28198, 28199, 28200, 28201
     )
),
receipt_boundary AS (
    SELECT min(created_at) AS created_at FROM receipt_candidates
),
retired_installation_rows AS (
    SELECT installation.*,
           jsonb_build_object(
               'id', installation.id,
               'company_id', installation.company_id,
               'installation_id', installation.installation_id,
               'registered_by_user_id', installation.registered_by_user_id,
               'created_at', installation.created_at
           ) AS immutable_identity,
           CASE WHEN
               installation.company_id IS DISTINCT FROM
                   '8f323fba-4358-45fe-9d3b-a8e0fae52993'::uuid
               OR installation.installation_id IS DISTINCT FROM
                   'd664b4a5-d293-48a7-a96c-8c3050ed5e76'::uuid
               OR installation.registered_by_user_id IS DISTINCT FROM
                   'c2aed53f-401f-4e09-8237-11c366e612ef'::uuid
               OR installation.created_at IS DISTINCT FROM
                   '2026-09-15 10:14:46.026411+00'::timestamptz
               OR installation.platform IS DISTINCT FROM 'android'
               OR installation.distribution_channel IS DISTINCT FROM 'direct'
               OR installation.pending_outbox_count IS DISTINCT FROM 1
               OR installation.version_code < 36
               OR NOT EXISTS (
                   SELECT 1
                     FROM android_releases release
                    WHERE release.channel = installation.distribution_channel
                      AND release.version_code = installation.version_code
                      AND release.version_name = installation.version_name
               )
               OR installation.last_seen_at <
                   '2026-09-19 08:33:01.020059+00'::timestamptz
               OR installation.last_seen_at > now() + interval '24 hours'
               OR installation.last_successful_sync_at IS NULL
               OR installation.last_successful_sync_at <
                   '2026-09-19 08:25:19.033+00'::timestamptz
               OR installation.last_successful_sync_at > installation.last_seen_at
               OR installation.updated_at <
                   '2026-09-19 08:33:01.020059+00'::timestamptz
               OR installation.updated_at > now() + interval '24 hours'
               OR (
                   installation.update_state = 'failed'
                   AND installation.update_error_code IS NULL
               )
               OR (
                   installation.update_state <> 'failed'
                   AND installation.update_error_code IS NOT NULL
               )
               OR (
                   installation.last_user_id IS NOT NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM users user_row
                        WHERE user_row.id = installation.last_user_id
                          AND user_row.company_id = installation.company_id
                   )
               )
               OR (
                   installation.terminal_id IS NOT NULL
                   AND NOT EXISTS (
                       SELECT 1
                         FROM terminals terminal
                         JOIN branches branch ON branch.id = terminal.branch_id
                        WHERE terminal.id = installation.terminal_id
                          AND branch.company_id = installation.company_id
                   )
               )
               OR NOT (
                   (
                       installation.remote_support_protocol_version IS NULL
                       AND installation.remote_support_capability IS NULL
                       AND installation.remote_support_last_seen_at IS NULL
                   )
                   OR (
                       installation.remote_support_protocol_version BETWEEN 1 AND 10
                       AND installation.remote_support_capability IN (
                           'available', 'permission_required', 'unsupported'
                       )
                       AND installation.remote_support_last_seen_at <=
                           now() + interval '24 hours'
                   )
               )
           THEN 1 ELSE 0 END AS invalid_telemetry
      FROM client_installations installation
     WHERE installation.id = '92b491f1-c35b-4437-af9a-a6be68035001'
),
retired_installation AS (
    SELECT count(*) AS row_count,
           encode(
               sha256(
                   convert_to(
                       coalesce(
                           string_agg(immutable_identity::text, E'\n' ORDER BY id::text),
                           ''
                       ),
                       'UTF8'
                   )
               ),
               'hex'
           ) AS identity_sha256,
           coalesce(sum(invalid_telemetry), 0) AS invalid_telemetry_count
      FROM retired_installation_rows
),
client_installation_guard AS (
    SELECT count(*) AS trigger_count,
           max(
               encode(
                   sha256(convert_to(procedure.prosrc, 'UTF8')),
                   'hex'
               )
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
),
retained_remote_key_rows AS (
    SELECT key_row.*,
           jsonb_build_object(
               'id', key_row.id,
               'company_id', key_row.company_id,
               'client_installation_id', key_row.client_installation_id,
               'public_key_spki_sha256',
                   encode(sha256(key_row.public_key_spki), 'hex'),
               'public_key_fingerprint_sha256',
                   key_row.public_key_fingerprint_sha256,
               'enrollment_id', key_row.enrollment_id,
               'enrolled_by_user_id', key_row.enrolled_by_user_id,
               'enrolled_at', key_row.enrolled_at,
               'pending_expires_at', key_row.pending_expires_at,
               'created_at', key_row.created_at
           ) AS immutable_identity,
           CASE WHEN
               key_row.company_id IS DISTINCT FROM
                   '8f323fba-4358-45fe-9d3b-a8e0fae52993'::uuid
               OR key_row.status IS DISTINCT FROM 'expired'
               OR key_row.approval_id IS NOT NULL
               OR key_row.approved_by_user_id IS NOT NULL
               OR key_row.approved_at IS NOT NULL
               OR key_row.revocation_id IS NOT NULL
               OR key_row.revoked_by_user_id IS NOT NULL
               OR key_row.revoked_at IS NOT NULL
               OR key_row.updated_at > boundary.created_at
           THEN 1 ELSE 0 END AS invalid_evidence
      FROM remote_assistance_device_keys key_row
      CROSS JOIN receipt_boundary boundary
     WHERE key_row.client_installation_id =
               '92b491f1-c35b-4437-af9a-a6be68035001'
       AND key_row.created_at <= boundary.created_at
       AND key_row.enrolled_at <= boundary.created_at
),
retained_remote_keys AS (
    SELECT count(*) AS row_count,
           encode(
               sha256(
                   convert_to(
                       coalesce(
                           string_agg(immutable_identity::text, E'\n' ORDER BY id::text),
                           ''
                       ),
                       'UTF8'
                   )
               ),
               'hex'
           ) AS identity_sha256,
           coalesce(sum(invalid_evidence), 0) AS invalid_count
      FROM retained_remote_key_rows
),
post_cleanup_remote_key_rows AS (
    SELECT key_row.*,
           lag(key_row.pending_expires_at) OVER (
               ORDER BY key_row.enrolled_at, key_row.id
           ) AS prior_pending_expires_at
      FROM remote_assistance_device_keys key_row
      CROSS JOIN receipt_boundary boundary
     WHERE key_row.client_installation_id =
               '92b491f1-c35b-4437-af9a-a6be68035001'
       AND key_row.created_at > boundary.created_at
       AND key_row.enrolled_at > boundary.created_at
),
post_cleanup_remote_keys AS (
    SELECT count(*) AS row_count,
           count(*) FILTER (WHERE key_row.status = 'expired') AS expired_count,
           count(*) FILTER (WHERE key_row.status = 'pending') AS pending_count,
           count(*) FILTER (
               WHERE key_row.company_id IS DISTINCT FROM
                         '8f323fba-4358-45fe-9d3b-a8e0fae52993'::uuid
                  OR key_row.status NOT IN ('pending', 'expired')
                  OR key_row.approval_id IS NOT NULL
                  OR key_row.approved_by_user_id IS NOT NULL
                  OR key_row.approved_at IS NOT NULL
                  OR key_row.revocation_id IS NOT NULL
                  OR key_row.revoked_by_user_id IS NOT NULL
                  OR key_row.revoked_at IS NOT NULL
                  OR key_row.updated_at < key_row.created_at
                  OR (
                      key_row.prior_pending_expires_at IS NOT NULL
                      AND key_row.enrolled_at < key_row.prior_pending_expires_at
                  )
                  OR (
                      SELECT count(*)
                        FROM audit_log audit
                       WHERE audit.company_id = key_row.company_id
                         AND audit.entity_type = 'RemoteAssistanceDeviceKey'
                         AND audit.entity_id = key_row.id::text
                         AND audit.action = 'remote_assistance.device_key.enrolled'
                  ) <> 1
                  OR (
                      SELECT count(*)
                        FROM audit_log audit
                       WHERE audit.company_id = key_row.company_id
                         AND audit.entity_type = 'RemoteAssistanceDeviceKey'
                         AND audit.entity_id = key_row.id::text
                         AND audit.action = 'remote_assistance.device_key.enrolled'
                         AND audit.actor_user_id = key_row.enrolled_by_user_id
                         AND audit.before = 'null'::jsonb
                         AND audit.after = jsonb_build_object(
                             'device_ref', left(encode(sha256(convert_to(
                                 key_row.company_id::text || ':' ||
                                 'd664b4a5-d293-48a7-a96c-8c3050ed5e76',
                                 'UTF8'
                             )), 'hex'), 20),
                             'key_ref', left(encode(sha256(convert_to(
                                 key_row.company_id::text || ':' || key_row.id::text || ':' ||
                                 key_row.public_key_fingerprint_sha256,
                                 'UTF8'
                             )), 'hex'), 20),
                             'status', 'pending'
                         )
                         -- Both values are server-generated in one transaction,
                         -- but PostgreSQL's transaction timestamp can precede
                         -- the Python enrollment timestamp by a few milliseconds.
                         AND audit.created_at BETWEEN
                             key_row.enrolled_at - interval '1 second'
                             AND key_row.enrolled_at + interval '1 second'
                         AND audit.terminal_id =
                             '789353a8-09e4-4ef2-9fa8-ac73c426bfc8'::uuid
                         AND audit.request_id IS NOT NULL
                         AND audit.client_platform = 'android'
                         AND audit.client_version_code >= 36
                         AND EXISTS (
                             SELECT 1 FROM android_releases release
                              WHERE release.channel = 'direct'
                                AND release.version_code = audit.client_version_code
                         )
                         AND audit.client_action_id IS NULL
                         AND audit.client_reported_at IS NULL
                         AND audit.client_was_offline IS FALSE
                         AND audit.synced_at IS NULL
                         AND audit.reason IS NULL
                         AND audit.ip IS NOT NULL
                         AND audit.user_agent IS NOT NULL
                  ) <> 1
                  OR (
                      SELECT count(*)
                        FROM audit_log audit
                       WHERE audit.company_id = key_row.company_id
                         AND audit.entity_type = 'RemoteAssistanceDeviceKey'
                         AND audit.entity_id = key_row.id::text
                         AND audit.action = 'remote_assistance.device_key.expired'
                  ) <> CASE WHEN key_row.status = 'expired' THEN 1 ELSE 0 END
                  OR (
                      SELECT count(*)
                        FROM audit_log audit
                       WHERE audit.company_id = key_row.company_id
                         AND audit.entity_type = 'RemoteAssistanceDeviceKey'
                         AND audit.entity_id = key_row.id::text
                         AND audit.action = 'remote_assistance.device_key.expired'
                         AND audit.actor_user_id IS NULL
                         AND audit.before = jsonb_build_object('status', 'pending')
                         AND audit.after = jsonb_build_object(
                             'device_ref', left(encode(sha256(convert_to(
                                 key_row.company_id::text || ':' ||
                                 'd664b4a5-d293-48a7-a96c-8c3050ed5e76',
                                 'UTF8'
                             )), 'hex'), 20),
                             'key_ref', left(encode(sha256(convert_to(
                                 key_row.company_id::text || ':' || key_row.id::text || ':' ||
                                 key_row.public_key_fingerprint_sha256,
                                 'UTF8'
                             )), 'hex'), 20),
                             'status', 'expired'
                         )
                         AND audit.created_at >= key_row.pending_expires_at
                         AND audit.terminal_id =
                             '789353a8-09e4-4ef2-9fa8-ac73c426bfc8'::uuid
                         AND audit.request_id IS NOT NULL
                         AND audit.client_platform = 'android'
                         AND audit.client_version_code >= 36
                         AND EXISTS (
                             SELECT 1 FROM android_releases release
                              WHERE release.channel = 'direct'
                                AND release.version_code = audit.client_version_code
                         )
                         AND audit.client_action_id IS NULL
                         AND audit.client_reported_at IS NULL
                         AND audit.client_was_offline IS FALSE
                         AND audit.synced_at IS NULL
                         AND audit.reason IS NULL
                         AND audit.ip IS NOT NULL
                         AND audit.user_agent IS NOT NULL
                  ) <> CASE WHEN key_row.status = 'expired' THEN 1 ELSE 0 END
                  OR (
                      SELECT count(*)
                        FROM audit_log audit
                       WHERE audit.company_id = key_row.company_id
                         AND audit.entity_type = 'RemoteAssistanceDeviceKey'
                         AND audit.entity_id = key_row.id::text
                         AND audit.action NOT IN (
                             'remote_assistance.device_key.enrolled',
                             'remote_assistance.device_key.expired'
                         )
                  ) <> 0
           ) + (
               SELECT count(*)
                 FROM remote_assistance_device_keys key_row
                 CROSS JOIN receipt_boundary boundary
                WHERE key_row.client_installation_id =
                          '92b491f1-c35b-4437-af9a-a6be68035001'
                  AND (
                      (key_row.created_at <= boundary.created_at AND
                       key_row.enrolled_at > boundary.created_at)
                      OR
                      (key_row.created_at > boundary.created_at AND
                       key_row.enrolled_at <= boundary.created_at)
                  )
           ) + (
               SELECT count(*)
                 FROM (
                     SELECT audit.action, audit.request_id
                       FROM audit_log audit
                       JOIN post_cleanup_remote_key_rows post_key
                         ON post_key.id::text = audit.entity_id
                        AND post_key.company_id = audit.company_id
                      WHERE audit.entity_type = 'RemoteAssistanceDeviceKey'
                        AND audit.action IN (
                            'remote_assistance.device_key.enrolled',
                            'remote_assistance.device_key.expired'
                        )
                      GROUP BY audit.action, audit.request_id
                     HAVING audit.request_id IS NULL OR count(*) <> 1
                 ) duplicate_request
           ) AS invalid_count
      FROM post_cleanup_remote_key_rows key_row
),
post_cleanup_remote_key_audits AS (
    SELECT count(*) FILTER (
               WHERE audit.action = 'remote_assistance.device_key.enrolled'
           ) AS enrollment_count,
           count(*) FILTER (
               WHERE audit.action = 'remote_assistance.device_key.expired'
           ) AS expiration_count,
           count(*) FILTER (
               WHERE audit.action NOT IN (
                         'remote_assistance.device_key.enrolled',
                         'remote_assistance.device_key.expired'
                     )
                  OR NOT EXISTS (
                      SELECT 1 FROM post_cleanup_remote_key_rows key_row
                       WHERE key_row.id::text = audit.entity_id
                         AND key_row.company_id = audit.company_id
                  )
           ) AS invalid_count
      FROM audit_log audit
      CROSS JOIN receipt_boundary boundary
     WHERE audit.company_id = '8f323fba-4358-45fe-9d3b-a8e0fae52993'::uuid
       AND audit.entity_type = 'RemoteAssistanceDeviceKey'
       AND audit.created_at > boundary.created_at
       AND audit.after->>'device_ref' = left(encode(sha256(convert_to(
               audit.company_id::text || ':' ||
               'd664b4a5-d293-48a7-a96c-8c3050ed5e76',
               'UTF8'
           )), 'hex'), 20)
),
remote_assistance_device_key_guard AS (
    SELECT count(*) AS trigger_count,
           max(
               encode(
                   sha256(convert_to(procedure.prosrc, 'UTF8')),
                   'hex'
               )
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
     WHERE trigger_row.tgrelid = 'remote_assistance_device_keys'::regclass
       AND trigger_row.tgname = 'trg_remote_assistance_device_keys_guard'
       AND NOT trigger_row.tgisinternal
       AND trigger_row.tgenabled = 'O'
       AND procedure.proname = 'dcompany_guard_remote_assistance_device_key'
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
    'schema_version', 2,
    'database_revision', (SELECT version_num FROM alembic_version),
    'pending_outbox_count', (
        SELECT coalesce(sum(pending_outbox_count), 0) FROM client_installations
    ),
    'nonzero_installation_count', (
        SELECT count(*) FROM client_installations
         WHERE pending_outbox_count IS DISTINCT FROM 0
    ),
    'cleanup_receipt_created_at', (SELECT created_at FROM receipt_boundary),
    'retired_installation_count', (SELECT row_count FROM retired_installation),
    'retired_installation_identity_sha256',
        (SELECT identity_sha256 FROM retired_installation),
    'retired_installation_invalid_telemetry_count',
        (SELECT invalid_telemetry_count FROM retired_installation),
    'client_installation_guard_trigger_count',
        (SELECT trigger_count FROM client_installation_guard),
    'client_installation_guard_function_sha256',
        (SELECT function_sha256 FROM client_installation_guard),
    'client_installation_guard_function_definition_sha256',
        (SELECT function_definition_sha256 FROM client_installation_guard),
    'client_installation_guard_trigger_definition_sha256',
        (SELECT trigger_definition_sha256 FROM client_installation_guard),
    'retained_remote_assistance_device_key_count',
        (SELECT row_count FROM retained_remote_keys),
    'retained_remote_assistance_device_keys_identity_sha256',
        (SELECT identity_sha256 FROM retained_remote_keys),
    'retained_remote_assistance_device_key_invalid_count',
        (SELECT invalid_count FROM retained_remote_keys),
    'post_cleanup_remote_assistance_device_key_count',
        (SELECT row_count FROM post_cleanup_remote_keys),
    'post_cleanup_remote_assistance_device_key_expired_count',
        (SELECT expired_count FROM post_cleanup_remote_keys),
    'post_cleanup_remote_assistance_device_key_pending_count',
        (SELECT pending_count FROM post_cleanup_remote_keys),
    'post_cleanup_remote_assistance_device_key_invalid_count',
        (SELECT invalid_count FROM post_cleanup_remote_keys),
    'post_cleanup_remote_assistance_device_key_enrollment_audit_count',
        (SELECT enrollment_count FROM post_cleanup_remote_key_audits),
    'post_cleanup_remote_assistance_device_key_expiration_audit_count',
        (SELECT expiration_count FROM post_cleanup_remote_key_audits),
    'post_cleanup_remote_assistance_device_key_invalid_audit_count',
        (SELECT invalid_count FROM post_cleanup_remote_key_audits),
    'remote_assistance_device_key_guard_trigger_count',
        (SELECT trigger_count FROM remote_assistance_device_key_guard),
    'remote_assistance_device_key_guard_function_sha256',
        (SELECT function_sha256 FROM remote_assistance_device_key_guard),
    'remote_assistance_device_key_guard_function_definition_sha256',
        (SELECT function_definition_sha256 FROM remote_assistance_device_key_guard),
    'remote_assistance_device_key_guard_trigger_definition_sha256',
        (SELECT trigger_definition_sha256 FROM remote_assistance_device_key_guard),
    'audit_integrity', jsonb_build_object(
        'baseline_max_id', 28202,
        'retained_baseline_count', (SELECT row_count FROM retained_audit_baseline),
        'retained_baseline_sha256', (SELECT row_sha256 FROM retained_audit_baseline),
        'invalid_pre_receipt_suffix_count', (
            SELECT count(*)
              FROM audit_log audit
             WHERE audit.id > 28202
               AND audit.id < coalesce((SELECT min(id) FROM receipt_candidates), 9223372036854775807)
               AND NOT EXISTS (SELECT 1 FROM normal_login_suffix login WHERE login.id = audit.id)
        ),
        'pre_receipt_login_success_count', (
            SELECT count(*) FROM normal_login_suffix login
             WHERE login.id < (SELECT min(id) FROM receipt_candidates)
        ),
        'pre_receipt_login_success_sha256', (
            SELECT encode(
                       sha256(
                           convert_to(
                               coalesce(string_agg(full_row::text, E'\n' ORDER BY id), ''),
                               'UTF8'
                           )
                       ),
                       'hex'
                   )
              FROM normal_login_suffix login
             WHERE login.id < (SELECT min(id) FROM receipt_candidates)
        ),
        'pre_receipt_login_success_max_id', (
            SELECT max(id) FROM normal_login_suffix login
             WHERE login.id < (SELECT min(id) FROM receipt_candidates)
        )
    ),
    'surviving_deleted_targets', jsonb_build_object(
        'shifts', (SELECT count(*) FROM shifts WHERE id IN (
            'd2337cb0-9b65-4c18-9baa-29b15fd163b6',
            '208335fe-f892-478b-b575-ee35498b4648',
            '4b53348d-3a1b-4e56-b97c-cdc1bc7b3e58'
        )),
        'orders', (SELECT count(*) FROM orders WHERE id IN (
            'b293d66f-0469-47a8-82ee-887a864796c1',
            '089e8a8d-1351-4b2a-b6ce-d3e0fd402f81',
            '8fcd1dbd-da6d-4ff2-bfa2-bc6db95fd3c2'
        )),
        'order_lines', (SELECT count(*) FROM order_lines WHERE id IN (
            '3b4a620f-bd83-43ab-9394-97ed38f2e6ad',
            'c3789167-7d0a-47f1-b026-7b8681e7dd4f',
            '88285974-aa81-48e2-a4e7-ac6f523d6c58'
        )),
        'gaming_sessions', (SELECT count(*) FROM gaming_sessions WHERE id IN (
            '7bb8a1af-d497-4c70-943c-2d78ae2cad5a',
            'b0245be8-0511-4268-8cc3-be23c546d955',
            'f28a30b1-d128-42d7-9510-b5909d7b2615',
            'f0d735ab-d6b8-4958-83cb-f63968e052fd',
            '633be5cf-f204-4a54-9087-184b8ec76a44'
        )),
        'menu_items', (SELECT count(*) FROM menu_items WHERE id =
            '2f968f7c-df0b-49fe-bedb-395da7329d28'
        ),
        'idempotency_keys', (SELECT count(*) FROM idempotency_keys WHERE key IN (
            'gaming-session-start:6ab2cd8b-6683-43e0-ae7b-f60fa8601537',
            'gaming-session-stop:6ab2cd8b-6683-43e0-ae7b-f60fa8601537',
            'gaming-session-start:d628bc2a-cc7c-4068-ba4b-eaccc533eec5',
            'gaming-session-stop:d628bc2a-cc7c-4068-ba4b-eaccc533eec5',
            'gaming-session-start:6fccc695-35e8-4708-a5b1-4deb03d7c512',
            'gaming-session-stop:6fccc695-35e8-4708-a5b1-4deb03d7c512',
            'gaming-session-start:2a65a12d-139f-4c1b-be7e-e0ab6a80eff5',
            'gaming-session-stop:2a65a12d-139f-4c1b-be7e-e0ab6a80eff5',
            'gaming-session-start:cd112a65-4c1e-4c5b-bc1e-a6c9d9a25de5',
            'gaming-session-stop:cd112a65-4c1e-4c5b-bc1e-a6c9d9a25de5'
        )),
        'audit_log', (SELECT count(*) FROM audit_log WHERE id IN (
            1001, 1004, 1142, 1143, 1150, 1161, 1162, 1163, 1164, 1165,
            1166, 1167, 1168, 1217, 1232, 1233, 1234, 1235, 1236, 1237,
            1238, 1239, 1240, 28174, 28175, 28176, 28177, 28180, 28183,
            28188, 28191, 28194, 28197, 28198, 28199, 28200, 28201
        ))
    ),
    'cleanup_receipts', coalesce(
        (
            SELECT jsonb_agg(payload ORDER BY id)
              FROM receipt_candidates
        ),
        '[]'::jsonb
    )
)
;

\set QUIET on
ROLLBACK;
