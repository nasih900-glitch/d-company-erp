\set ON_ERROR_STOP on
\set QUIET on

BEGIN TRANSACTION READ ONLY;
SET LOCAL TIME ZONE 'UTC';
SET LOCAL bytea_output = 'hex';

\set QUIET off

WITH receipt_candidates AS (
    SELECT receipt.id,
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
retired_installation AS (
    SELECT count(*) AS row_count,
           encode(
               sha256(
                   convert_to(
                       coalesce(
                           string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text),
                           ''
                       ),
                       'UTF8'
                   )
               ),
               'hex'
           ) AS row_sha256
      FROM client_installations row_data
     WHERE id = '92b491f1-c35b-4437-af9a-a6be68035001'
),
retained_remote_keys AS (
    SELECT count(*) AS row_count,
           encode(
               sha256(
                   convert_to(
                       coalesce(
                           string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text),
                           ''
                       ),
                       'UTF8'
                   )
               ),
               'hex'
           ) AS row_sha256
      FROM remote_assistance_device_keys row_data
     WHERE client_installation_id = '92b491f1-c35b-4437-af9a-a6be68035001'
)
SELECT jsonb_build_object(
    'schema_version', 1,
    'database_revision', (SELECT version_num FROM alembic_version),
    'pending_outbox_count', (
        SELECT coalesce(sum(pending_outbox_count), 0) FROM client_installations
    ),
    'nonzero_installation_count', (
        SELECT count(*) FROM client_installations
         WHERE pending_outbox_count IS DISTINCT FROM 0
    ),
    'retired_installation_count', (SELECT row_count FROM retired_installation),
    'retired_installation_row_sha256', (SELECT row_sha256 FROM retired_installation),
    'remote_assistance_device_key_count', (SELECT row_count FROM retained_remote_keys),
    'remote_assistance_device_keys_sha256', (SELECT row_sha256 FROM retained_remote_keys),
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
