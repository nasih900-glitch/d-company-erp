\set ON_ERROR_STOP on

-- One selected installation, its newest terminal shift and all close evidence
-- share one MVCC snapshot. No shift, audit row or device report is changed.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL timezone = 'UTC';
WITH selected AS (
    SELECT * FROM client_installations WHERE installation_id = :'selected'::uuid
)
SELECT jsonb_build_object(
    'installation_id', c.installation_id, 'company_id', c.company_id,
    'terminal_id', c.terminal_id, 'platform', c.platform,
    'version_code', c.version_code, 'version_name', c.version_name,
    'pending_outbox_count', c.pending_outbox_count,
    'last_successful_sync_at', to_char(c.last_successful_sync_at, 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
    'last_seen_at', to_char(c.last_seen_at, 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
    'observed_at', to_char(clock_timestamp(), 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
    'close_fence', (
        WITH terminal_shifts AS (
            SELECT * FROM shifts WHERE company_id = c.company_id AND terminal_id = c.terminal_id
        ), latest AS (
            SELECT * FROM terminal_shifts ORDER BY created_at DESC, id DESC LIMIT 1
        )
        SELECT jsonb_build_object(
            'schema_version', 1,
            'latest_shift_count', (SELECT count(*) FROM terminal_shifts WHERE created_at = s.created_at),
            'later_shift_count', (SELECT count(*) FROM terminal_shifts other WHERE other.id <> s.id
                AND (other.opened_at > s.opened_at OR other.opening_received_at > s.opening_received_at)),
            'normal_close_count', (SELECT count(*) FROM audit_log WHERE entity_id = s.id::text AND action = 'shift_closed'),
            'recovery_close_count', (SELECT count(*) FROM audit_log WHERE entity_id = s.id::text AND action = 'shift_android_origin_recovery_close'),
            'shift', jsonb_build_object(
                'id', s.id, 'company_id', s.company_id, 'terminal_id', s.terminal_id,
                'status', s.status, 'closed_by', s.closed_by,
                'opened_at', to_char(s.opened_at, 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                'created_at', to_char(s.created_at, 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                'opening_received_at', to_char(s.opening_received_at, 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                'closed_at', to_char(s.closed_at, 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                'opening_protocol_revision', s.opening_protocol_revision,
                'opening_client_platform', s.opening_client_platform,
                'opening_client_installation_id', s.opening_client_installation_id,
                'opening_action_id', s.opening_action_id
            ),
            'normal_close', (
                SELECT jsonb_build_object(
                    'id', a.id, 'action', a.action, 'entity_type', a.entity_type, 'entity_id', a.entity_id,
                    'company_id', a.company_id, 'terminal_id', a.terminal_id, 'actor_user_id', a.actor_user_id,
                    'client_platform', a.client_platform, 'client_version_code', a.client_version_code,
                    'client_action_id', a.client_action_id, 'action_code', a.action_code,
                    'stage', a.stage, 'outcome', a.outcome,
                    'created_at', to_char(a.created_at, 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                    'before_status', a.before->>'status', 'after_status', a.after->>'status',
                    'after_closed_by', a.after->>'closed_by'
                ) FROM audit_log a WHERE a.entity_id = s.id::text AND a.action = 'shift_closed'
                ORDER BY a.id DESC LIMIT 1
            )
        ) FROM latest s
    )
) FROM selected c;
COMMIT;
