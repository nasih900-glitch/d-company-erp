\set ON_ERROR_STOP on

BEGIN ISOLATION LEVEL SERIALIZABLE;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL idle_in_transaction_session_timeout = '120s';
-- Whole-row JSON fingerprints include timestamptz and bytea fields. Pin their
-- textual rendering so role/database session settings cannot change a hash.
SET LOCAL TIME ZONE 'UTC';
SET LOCAL bytea_output = 'hex';

CREATE TEMP TABLE _cleanup_inputs (
    apply boolean NOT NULL,
    expected_state_fingerprint text NOT NULL,
    backup_sha256 text NOT NULL,
    quarantine_evidence_sha256 text NOT NULL,
    source_git_sha text NOT NULL,
    backend_image_id text NOT NULL,
    executor_name text NOT NULL
) ON COMMIT DROP;

INSERT INTO _cleanup_inputs VALUES (
    :'cleanup_apply'::boolean,
    :'expected_state_fingerprint',
    :'backup_sha256',
    :'quarantine_evidence_sha256',
    :'source_git_sha',
    :'backend_image_id',
    :'executor_name'
);

DO $guard$
DECLARE
    input _cleanup_inputs%ROWTYPE;
BEGIN
    SELECT * INTO STRICT input FROM _cleanup_inputs;
    IF input.apply AND (
        input.expected_state_fingerprint !~ '^[0-9a-f]{64}$'
        OR input.backup_sha256 !~ '^[0-9a-f]{64}$'
        OR input.backup_sha256 = repeat('0', 64)
        OR input.quarantine_evidence_sha256 IS DISTINCT FROM
           '379c6368936d03223e19482cc840c2a9d2483dc9a96909fba22cd9f59911eec8'
        OR input.source_git_sha !~ '^[0-9a-f]{40}$'
        OR input.backend_image_id !~ '^sha256:[0-9a-f]{64}$'
        OR input.executor_name !~ '^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,99}$'
    ) THEN
        RAISE EXCEPTION 'cleanup evidence or operator identity is invalid';
    END IF;
    IF NOT pg_try_advisory_xact_lock(
        hashtextextended('d-company:production-trial-cleanup:code30.1-20260920', 0)
    ) THEN
        RAISE EXCEPTION 'another production trial cleanup holds the advisory lock';
    END IF;
END
$guard$;

-- A fresh writer or even an overlapping report must make this one-time
-- maintenance transaction fail immediately. This also closes dependency-check
-- races without disabling any database integrity trigger.
DO $lock_all$
DECLARE
    lock_list text;
BEGIN
    SELECT string_agg(format('%I.%I', n.nspname, c.relname), ', ' ORDER BY n.nspname, c.relname)
      INTO lock_list
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind IN ('r', 'p');
    IF lock_list IS NULL THEN
        RAISE EXCEPTION 'no public tables were found';
    END IF;
    EXECUTE 'LOCK TABLE ' || lock_list || ' IN ACCESS EXCLUSIVE MODE NOWAIT';
END
$lock_all$;

DO $schema_guard$
DECLARE
    revision text;
BEGIN
    SELECT version_num INTO STRICT revision FROM alembic_version;
    IF revision IS DISTINCT FROM '0078' THEN
        RAISE EXCEPTION 'expected exact database migration 0078, found %', revision;
    END IF;
    IF to_regclass('public.google_sheets_deliveries') IS NULL THEN
        RAISE EXCEPTION 'migration 0078 schema is incomplete: Google Sheets delivery ledger is absent';
    END IF;
END
$schema_guard$;

CREATE OR REPLACE FUNCTION pg_temp.cleanup_table_snapshot(target regclass)
RETURNS TABLE(row_count bigint, row_sha256 text)
LANGUAGE plpgsql
AS $snapshot$
BEGIN
    RETURN QUERY EXECUTE format(
        'SELECT count(*), encode(sha256(convert_to(coalesce(string_agg(row_json, chr(10) ORDER BY row_json), ''''), ''UTF8'')), ''hex'') '
        'FROM (SELECT to_jsonb(source_row)::text AS row_json FROM %s AS source_row) AS canonical_rows',
        target
    );
END
$snapshot$;

CREATE TEMP TABLE _cleanup_all_pre (
    table_name text PRIMARY KEY,
    row_count bigint NOT NULL,
    row_sha256 text NOT NULL
) ON COMMIT DROP;

DO $snapshot_all$
DECLARE
    table_row record;
    snapshot_row record;
BEGIN
    FOR table_row IN
        SELECT c.oid::regclass AS relation, c.relname AS table_name
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relkind IN ('r', 'p')
         ORDER BY c.relname
    LOOP
        SELECT * INTO STRICT snapshot_row
          FROM pg_temp.cleanup_table_snapshot(table_row.relation);
        INSERT INTO _cleanup_all_pre VALUES (
            table_row.table_name,
            snapshot_row.row_count,
            snapshot_row.row_sha256
        );
    END LOOP;
END
$snapshot_all$;

CREATE TEMP TABLE _cleanup_uuid_targets (
    target_table text NOT NULL,
    id uuid NOT NULL,
    PRIMARY KEY (target_table, id)
) ON COMMIT DROP;

INSERT INTO _cleanup_uuid_targets (target_table, id) VALUES
    ('shifts', 'd2337cb0-9b65-4c18-9baa-29b15fd163b6'),
    ('shifts', '208335fe-f892-478b-b575-ee35498b4648'),
    ('shifts', '4b53348d-3a1b-4e56-b97c-cdc1bc7b3e58'),
    ('orders', 'b293d66f-0469-47a8-82ee-887a864796c1'),
    ('orders', '089e8a8d-1351-4b2a-b6ce-d3e0fd402f81'),
    ('orders', '8fcd1dbd-da6d-4ff2-bfa2-bc6db95fd3c2'),
    ('order_lines', '3b4a620f-bd83-43ab-9394-97ed38f2e6ad'),
    ('order_lines', 'c3789167-7d0a-47f1-b026-7b8681e7dd4f'),
    ('order_lines', '88285974-aa81-48e2-a4e7-ac6f523d6c58'),
    ('gaming_sessions', '7bb8a1af-d497-4c70-943c-2d78ae2cad5a'),
    ('gaming_sessions', 'b0245be8-0511-4268-8cc3-be23c546d955'),
    ('gaming_sessions', 'f28a30b1-d128-42d7-9510-b5909d7b2615'),
    ('gaming_sessions', 'f0d735ab-d6b8-4958-83cb-f63968e052fd'),
    ('gaming_sessions', '633be5cf-f204-4a54-9087-184b8ec76a44'),
    ('menu_items', '2f968f7c-df0b-49fe-bedb-395da7329d28'),
    ('remote_assistance_device_keys', '0099a04b-f0ed-4284-81e2-5fbb1eba3655'),
    ('remote_assistance_device_keys', '116b9c91-9a2f-405e-a4be-72c8f4ffdac8'),
    ('remote_assistance_device_keys', '2f82d105-9ac2-4e73-b9fc-b0333db1197c'),
    ('remote_assistance_device_keys', '33887ae0-9444-4d78-92aa-839eedd34d3a'),
    ('remote_assistance_device_keys', '3ec91218-31d1-4e24-af81-b4ab42935ed0'),
    ('remote_assistance_device_keys', '40a1fa7e-aac8-42a7-957c-9216e76e589d'),
    ('remote_assistance_device_keys', '487489be-00c4-45bc-a367-5ce662f529ae'),
    ('remote_assistance_device_keys', '4f8db2da-3984-43e8-aae9-7595f5d65bb6'),
    ('remote_assistance_device_keys', '4fc7b867-8fd4-4fdc-a23a-d4c59232e571'),
    ('remote_assistance_device_keys', '647879a0-0603-488a-9b02-332189800e5e'),
    ('remote_assistance_device_keys', '67013f30-d451-4254-bac3-0b59f95743e0'),
    ('remote_assistance_device_keys', '7fc430b1-89d8-4d51-ab28-756170123435'),
    ('remote_assistance_device_keys', '801de2c8-08f3-4a45-bf9f-c5a1580f27a1'),
    ('remote_assistance_device_keys', '879c2ec9-2d35-4845-b5e2-76a27b256273'),
    ('remote_assistance_device_keys', '8bd1b096-cb9b-4d42-b691-861dc0657b26'),
    ('remote_assistance_device_keys', '8c166c7c-a3d6-4bc8-b4d5-db69b54137e9'),
    ('remote_assistance_device_keys', '9c7b56ac-e812-43ea-90bf-d0efb67643ae'),
    ('remote_assistance_device_keys', 'a447cf68-294d-46d3-bddd-6758a4e9390a'),
    ('remote_assistance_device_keys', 'ae297e13-bf19-4fb4-a103-b5f4cedeef2c'),
    ('remote_assistance_device_keys', 'b537da93-5271-4da4-be89-9219af413fbe'),
    ('remote_assistance_device_keys', 'b842325b-8177-4aa9-aa1f-635a79ab174f'),
    ('remote_assistance_device_keys', 'be2eef6b-5469-49ba-8183-ed28270f8a3b'),
    ('remote_assistance_device_keys', 'ccf0fbf0-7146-44c3-a8c5-f4275432f25a'),
    ('remote_assistance_device_keys', 'd0f524a7-34b7-4983-85b6-e727140d4868'),
    ('remote_assistance_device_keys', 'd205dec2-ba20-4511-a54c-8a0c6d166e47'),
    ('remote_assistance_device_keys', 'ec2f17a7-8bd4-446b-aa63-e34b3aa44110'),
    ('remote_assistance_device_keys', 'ed111e9e-d0bf-48ca-a6e7-9b99c2e9e43c'),
    ('remote_assistance_device_keys', 'f394573c-f5b5-4ce7-889f-b0e283c8cbb2'),
    ('remote_assistance_device_keys', 'fbc6ed29-10fc-496a-b98b-ca94dfedaabc'),
    ('client_installations', '92b491f1-c35b-4437-af9a-a6be68035001');

CREATE TEMP TABLE _cleanup_idempotency_targets (
    key text PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO _cleanup_idempotency_targets (key) VALUES
    ('gaming-session-start:6ab2cd8b-6683-43e0-ae7b-f60fa8601537'),
    ('gaming-session-stop:6ab2cd8b-6683-43e0-ae7b-f60fa8601537'),
    ('gaming-session-start:d628bc2a-cc7c-4068-ba4b-eaccc533eec5'),
    ('gaming-session-stop:d628bc2a-cc7c-4068-ba4b-eaccc533eec5'),
    ('gaming-session-start:6fccc695-35e8-4708-a5b1-4deb03d7c512'),
    ('gaming-session-stop:6fccc695-35e8-4708-a5b1-4deb03d7c512'),
    ('gaming-session-start:2a65a12d-139f-4c1b-be7e-e0ab6a80eff5'),
    ('gaming-session-stop:2a65a12d-139f-4c1b-be7e-e0ab6a80eff5'),
    ('gaming-session-start:cd112a65-4c1e-4c5b-bc1e-a6c9d9a25de5'),
    ('gaming-session-stop:cd112a65-4c1e-4c5b-bc1e-a6c9d9a25de5');

CREATE TEMP TABLE _cleanup_audit_targets (
    id bigint PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO _cleanup_audit_targets (id) VALUES
    (1001), (1004), (1142), (1143), (1150), (1161), (1162), (1163),
    (1164), (1165), (1166), (1167), (1168), (1217), (1232), (1233),
    (1234), (1235), (1236), (1237), (1238), (1239), (1240), (28174),
    (28175), (28176), (28177), (28180), (28183), (28188), (28191),
    (28194), (28197), (28198), (28199), (28200), (28201);

CREATE TEMP TABLE _cleanup_expected_full (
    table_name text PRIMARY KEY,
    row_count bigint NOT NULL,
    row_sha256 text NOT NULL
) ON COMMIT DROP;

INSERT INTO _cleanup_expected_full VALUES
    ('client_installations', 7, '49c31a308d7980d4b30c0831c03f4e559d27990b175139aa8d3514d7affcc266'),
    ('gaming_sessions', 9, '72894ce31664b065b73bdcdb7711ffc59d6d76831783f2c837691fc09c44289f'),
    ('idempotency_keys', 47, 'f91749ce9c33b2b09c42f566581341fe25d43e80f52925d69dabf18245198743'),
    ('menu_items', 5, '8617ab23715f3ddb053bbf8dfd97024d046477d0b26ff3377fc9020efeefe0d5'),
    ('order_lines', 7, '9953dc55eddd7aae7f99160f5ee9bf8fc32c53aaea26fd5d92f737ac8b4ad26a'),
    ('orders', 7, '383bc2351b2396ee9006474d59738e06af3cd7c5ad7949e611a01d58d5c0c402'),
    ('remote_assistance_device_keys', 379, '153459629d8c52fbd2c6b7ae40e80530b52a2da69108fd823e607c1478bffb78'),
    ('shifts', 10, '4c36b350e4f76ff53e867f98fefaf936df81676eb86f41a7cc9102a7fb169aed');

DO $full_snapshot_guard$
DECLARE
    mismatch jsonb;
BEGIN
    SELECT jsonb_agg(to_jsonb(diff) ORDER BY diff.table_name)
      INTO mismatch
      FROM (
          SELECT expected.table_name,
                 expected.row_count AS expected_count,
                 actual.row_count AS actual_count,
                 expected.row_sha256 AS expected_sha256,
                 actual.row_sha256 AS actual_sha256
            FROM _cleanup_expected_full expected
            FULL JOIN _cleanup_all_pre actual USING (table_name)
           WHERE expected.table_name IS NOT NULL
             AND (actual.table_name IS NULL
                  OR actual.row_count IS DISTINCT FROM expected.row_count
                  OR actual.row_sha256 IS DISTINCT FROM expected.row_sha256)
      ) diff;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'audited full-table snapshot changed: %', mismatch;
    END IF;
END
$full_snapshot_guard$;

-- audit_log is append-only. The 1,271 reviewed rows form an immutable prefix,
-- while successful sign-ins can legitimately append after the read-only audit
-- that produced this cleanup. Accept only that narrow, attributable suffix;
-- every baseline row and every cleanup-related audit target remains frozen.
CREATE TEMP TABLE _cleanup_audit_baseline_pre (
    row_count bigint NOT NULL,
    row_sha256 text NOT NULL
) ON COMMIT DROP;

INSERT INTO _cleanup_audit_baseline_pre
SELECT count(*),
       encode(
           sha256(
               convert_to(
                   coalesce(
                       string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY to_jsonb(row_data)::text),
                       ''
                   ),
                   'UTF8'
               )
           ),
           'hex'
       )
  FROM audit_log row_data
 WHERE id <= 28202;

DO $audit_baseline_guard$
BEGIN
    IF (SELECT row_count FROM _cleanup_audit_baseline_pre) <> 1271
       OR (SELECT row_sha256 FROM _cleanup_audit_baseline_pre) IS DISTINCT FROM
          'e2164c700b9ffc67edc1e63623baf943a89b4dd9cd7392a87a12a054aba7cc82' THEN
        RAISE EXCEPTION 'immutable audit-log baseline prefix changed';
    END IF;
END
$audit_baseline_guard$;

CREATE TEMP TABLE _cleanup_login_suffix_pre (
    row_count bigint NOT NULL,
    row_sha256 text NOT NULL,
    max_id bigint
) ON COMMIT DROP;

INSERT INTO _cleanup_login_suffix_pre
SELECT count(*),
       encode(
           sha256(
               convert_to(
                   coalesce(
                       string_agg(to_jsonb(audit)::text, E'\n' ORDER BY audit.id),
                       ''
                   ),
                   'UTF8'
               )
           ),
           'hex'
       ),
       max(audit.id)
  FROM audit_log audit
 WHERE audit.id > 28202;

DO $audit_suffix_guard$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM audit_log audit
          LEFT JOIN users actor ON actor.id = audit.actor_user_id
         WHERE audit.id > 28202
           AND NOT (
               audit.action = 'login_success'
               AND audit.entity_type = 'User'
               AND audit.entity_id = audit.actor_user_id::text
               AND audit.company_id = '8f323fba-4358-45fe-9d3b-a8e0fae52993'
               AND actor.id IS NOT NULL
               AND actor.company_id = audit.company_id
               AND actor.status = 'active'
               AND actor.deleted_at IS NULL
               AND audit.before = 'null'::jsonb
               AND jsonb_typeof(audit.after) = 'object'
               AND audit.after ?& ARRAY['email', 'result', 'name', 'roles']
               AND CASE WHEN jsonb_typeof(audit.after) = 'object'
                        THEN (SELECT count(*) FROM jsonb_object_keys(audit.after))
                        ELSE -1 END = 4
               AND audit.after->>'email' = actor.email
               AND audit.after->>'name' = actor.name
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
           )
    ) THEN
        RAISE EXCEPTION 'audit-log suffix contains an unreviewed or malformed action';
    END IF;
END
$audit_suffix_guard$;

CREATE TEMP TABLE _cleanup_target_pre (
    table_name text PRIMARY KEY,
    row_count bigint NOT NULL,
    row_sha256 text NOT NULL
) ON COMMIT DROP;

INSERT INTO _cleanup_target_pre
SELECT 'shifts', count(*),
       encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM shifts row_data
 WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'shifts')
UNION ALL
SELECT 'orders', count(*),
       encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM orders row_data
 WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'orders')
UNION ALL
SELECT 'order_lines', count(*),
       encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM order_lines row_data
 WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'order_lines')
UNION ALL
SELECT 'gaming_sessions', count(*),
       encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM gaming_sessions row_data
 WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'gaming_sessions')
UNION ALL
SELECT 'menu_items', count(*),
       encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM menu_items row_data
 WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'menu_items')
UNION ALL
SELECT 'idempotency_keys', count(*),
       encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY key), ''), 'UTF8')), 'hex')
  FROM idempotency_keys row_data
 WHERE key IN (SELECT key FROM _cleanup_idempotency_targets)
UNION ALL
SELECT 'audit_log', count(*),
       encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id), ''), 'UTF8')), 'hex')
  FROM audit_log row_data
 WHERE id IN (SELECT id FROM _cleanup_audit_targets)
UNION ALL
SELECT 'client_installations', count(*),
       encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM client_installations row_data
 WHERE id IN (
     SELECT id FROM _cleanup_uuid_targets
      WHERE target_table = 'client_installations'
 )
UNION ALL
SELECT 'remote_assistance_device_keys', count(*),
       encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM remote_assistance_device_keys row_data
 WHERE id IN (
     SELECT id FROM _cleanup_uuid_targets
      WHERE target_table = 'remote_assistance_device_keys'
 );

CREATE TEMP TABLE _cleanup_expected_target (
    table_name text PRIMARY KEY,
    row_count bigint NOT NULL,
    row_sha256 text NOT NULL
) ON COMMIT DROP;

INSERT INTO _cleanup_expected_target VALUES
    ('shifts', 3, '93c060904692477a228862f431fa0c992ebc25e3fef8f5af2f2a732fb2b64b15'),
    ('orders', 3, '0e445d22547b647b02057f67290ac2fafbdae962a7656e5e26bbf23d93aaf45e'),
    ('order_lines', 3, '53a1ec3a79f7057bc71c1cf12f864df7efb96912e9c75ccdcdae171d48003652'),
    ('gaming_sessions', 5, 'c612890089bfbc6ed45012fe098a59e9e0c9a57381f921a3e5509976dd68e2b4'),
    ('menu_items', 1, '7c4126a1d946ab6a098f1b92cd5b77db611a72d197afc35d8d20ce52b3f7118f'),
    ('idempotency_keys', 10, 'e8b3123348e4622ff592ecccec3560310f4c2fa732dd9263112ee209e55c9403'),
    ('audit_log', 37, 'ef7cb3f79870b68da91cf0249c0105f1ac1c8451e539ab9c20df68b8db8473e4'),
    ('client_installations', 1, '530ff69bd8e040457755bc0268216c8347442ebae858d5ce1152fb1fbfc65a71'),
    ('remote_assistance_device_keys', 29, '301905b96650f3f06fc6b3378a1450cc000159a9909f33af3b951a665eca2696');

DO $target_snapshot_guard$
DECLARE
    mismatch jsonb;
BEGIN
    SELECT jsonb_agg(to_jsonb(diff) ORDER BY diff.table_name)
      INTO mismatch
      FROM (
          SELECT expected.table_name,
                 expected.row_count AS expected_count,
                 actual.row_count AS actual_count,
                 expected.row_sha256 AS expected_sha256,
                 actual.row_sha256 AS actual_sha256
            FROM _cleanup_expected_target expected
            FULL JOIN _cleanup_target_pre actual USING (table_name)
           WHERE expected.table_name IS NULL OR actual.table_name IS NULL
              OR actual.row_count IS DISTINCT FROM expected.row_count
              OR actual.row_sha256 IS DISTINCT FROM expected.row_sha256
      ) diff;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'audited cleanup target snapshot changed: %', mismatch;
    END IF;
END
$target_snapshot_guard$;

-- Preserve the exact retired Code30.1 test installation row. Its one stale
-- saved-action report cannot be attributed to a local AVD, so maintenance must
-- not clear it or change heartbeat/sync telemetry. The immutable receipt below
-- records this historical snapshot separately from the 18-AVD quarantine.
CREATE TEMP TABLE _cleanup_installation_pre (
    id uuid PRIMARY KEY,
    pending_outbox_count integer NOT NULL,
    full_row jsonb NOT NULL
) ON COMMIT DROP;

INSERT INTO _cleanup_installation_pre
SELECT id, pending_outbox_count, to_jsonb(row_data)
  FROM client_installations row_data
 WHERE id IN (
     SELECT id FROM _cleanup_uuid_targets
      WHERE target_table = 'client_installations'
 );

DO $installation_pre_guard$
BEGIN
    IF (SELECT count(*) FROM _cleanup_installation_pre) <> 1
       OR (SELECT pending_outbox_count FROM _cleanup_installation_pre) <> 1 THEN
        RAISE EXCEPTION 'retired Code30.1 test installation outbox evidence changed';
    END IF;
END
$installation_pre_guard$;

CREATE TEMP TABLE _cleanup_scalar_pre (
    name text PRIMARY KEY,
    value bigint NOT NULL
) ON COMMIT DROP;

INSERT INTO _cleanup_scalar_pre VALUES
    ('payments', (SELECT count(*) FROM payments)),
    ('refunds', (SELECT count(*) FROM refunds)),
    ('customers', (SELECT count(*) FROM customers)),
    ('active_or_paused_sessions', (SELECT count(*) FROM gaming_sessions WHERE status IN ('active', 'paused'))),
    ('open_or_held_orders', (SELECT count(*) FROM orders WHERE status IN ('open', 'held'))),
    ('open_shifts', (SELECT count(*) FROM shifts WHERE status = 'open')),
    ('pending_tablet_outbox', (SELECT coalesce(sum(pending_outbox_count), 0) FROM client_installations)),
    ('google_sheets_deliveries', (SELECT count(*) FROM google_sheets_deliveries)),
    ('google_sheets_unresolved', (SELECT count(*) FROM google_sheets_deliveries WHERE status IN ('pending', 'leased', 'quarantined')));

DO $scalar_guard$
DECLARE
    mismatch jsonb;
BEGIN
    WITH expected(name, value) AS (
        VALUES
            ('payments', 3::bigint),
            ('refunds', 0::bigint),
            ('customers', 0::bigint),
            ('active_or_paused_sessions', 0::bigint),
            ('open_or_held_orders', 0::bigint),
            ('open_shifts', 1::bigint),
            ('pending_tablet_outbox', 1::bigint),
            ('google_sheets_deliveries', 0::bigint),
            ('google_sheets_unresolved', 0::bigint)
    )
    SELECT jsonb_agg(to_jsonb(diff) ORDER BY diff.name)
      INTO mismatch
      FROM (
          SELECT expected.name, expected.value AS expected_value, actual.value AS actual_value
            FROM expected
            FULL JOIN _cleanup_scalar_pre actual USING (name)
           WHERE expected.name IS NULL OR actual.name IS NULL
              OR actual.value IS DISTINCT FROM expected.value
      ) diff;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'production counts changed from the audited cleanup snapshot: %', mismatch;
    END IF;
END
$scalar_guard$;

-- Human-readable business invariants complement the opaque full-row hashes.
DO $business_guard$
DECLARE
    cutoff timestamptz := '2026-09-06 00:00:00 Asia/Kolkata'::timestamptz;
BEGIN
    IF EXISTS (
        SELECT 1 FROM shifts
         WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'shifts')
           AND (company_id IS DISTINCT FROM '8f323fba-4358-45fe-9d3b-a8e0fae52993'
                OR opened_at < cutoff
                OR opening_float_minor <> 0 OR expected_minor <> 0
                OR status NOT IN ('open', 'closed'))
    ) OR (SELECT count(*) FROM shifts
           WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'shifts')
             AND status = 'open') <> 1 THEN
        RAISE EXCEPTION 'target shifts no longer match verified test-only semantics';
    END IF;

    IF EXISTS (
        SELECT 1 FROM orders
         WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'orders')
           AND (company_id IS DISTINCT FROM '8f323fba-4358-45fe-9d3b-a8e0fae52993'
                OR opened_at < cutoff OR status IS DISTINCT FROM 'void'
                OR invoice_no IS NOT NULL OR invoice_issued_at IS NOT NULL
                OR customer_id IS NOT NULL OR customer_name IS NOT NULL OR customer_phone IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'target orders are no longer void, unissued, customer-free test rows';
    END IF;

    IF EXISTS (
        SELECT 1 FROM gaming_sessions
         WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'gaming_sessions')
           AND (company_id IS DISTINCT FROM '8f323fba-4358-45fe-9d3b-a8e0fae52993'
                OR start_at < cutoff OR status NOT IN ('ended', 'cancelled')
                OR customer_id IS NOT NULL OR customer_name IS NOT NULL OR customer_phone IS NOT NULL)
    ) OR (SELECT count(*) FROM gaming_sessions
           WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'gaming_sessions')
             AND status = 'cancelled') <> 2 THEN
        RAISE EXCEPTION 'target gaming sessions no longer match verified completed/cancelled test rows';
    END IF;

    IF EXISTS (
        SELECT 1 FROM order_lines line
        JOIN orders parent_order ON parent_order.id = line.order_id
         WHERE line.id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'order_lines')
           AND (parent_order.id NOT IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'orders')
                OR line.created_at < cutoff OR line.voided_at IS NULL)
    ) THEN
        RAISE EXCEPTION 'target order lines are no longer voided children of target orders';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM menu_items
         WHERE id = '2f968f7c-df0b-49fe-bedb-395da7329d28'
           AND company_id = '8f323fba-4358-45fe-9d3b-a8e0fae52993'
           AND sku = 'SESSION-SIMULATOR'
           AND type = 'gaming'
           AND is_available = false
           AND created_at >= cutoff
    ) THEN
        RAISE EXCEPTION 'temporary SESSION-SIMULATOR helper item changed';
    END IF;

    IF EXISTS (
        SELECT 1 FROM idempotency_keys
         WHERE key IN (SELECT key FROM _cleanup_idempotency_targets)
           AND (created_at < cutoff OR response_status NOT IN (200, 201))
    ) THEN
        RAISE EXCEPTION 'target replay receipts changed from the verified session actions';
    END IF;

    IF EXISTS (
        SELECT 1 FROM audit_log
         WHERE id IN (SELECT id FROM _cleanup_audit_targets)
           AND (company_id IS DISTINCT FROM '8f323fba-4358-45fe-9d3b-a8e0fae52993'
                OR created_at < cutoff)
    ) THEN
        RAISE EXCEPTION 'target audit rows changed company or predate the no-business-data cutoff';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM client_installations
         WHERE id = '92b491f1-c35b-4437-af9a-a6be68035001'
           AND installation_id = 'd664b4a5-d293-48a7-a96c-8c3050ed5e76'
           AND company_id = '8f323fba-4358-45fe-9d3b-a8e0fae52993'
           AND version_name = '3.1.28' AND version_code = 36
           AND pending_outbox_count = 1
           AND last_seen_at = '2026-09-19 08:33:01.020059+00'::timestamptz
           AND last_successful_sync_at = '2026-09-19 08:25:19.033+00'::timestamptz
           AND updated_at = '2026-09-19 08:33:01.020059+00'::timestamptz
    ) THEN
        RAISE EXCEPTION 'retired Code30.1 test installation telemetry changed';
    END IF;

    IF (SELECT count(*) FROM remote_assistance_device_keys
         WHERE id IN (
             SELECT id FROM _cleanup_uuid_targets
              WHERE target_table = 'remote_assistance_device_keys'
         )) <> 29
       OR (SELECT count(*) FROM remote_assistance_device_keys
            WHERE client_installation_id =
                  '92b491f1-c35b-4437-af9a-a6be68035001') <> 29
       OR EXISTS (
           SELECT 1 FROM remote_assistance_device_keys
            WHERE id IN (
                SELECT id FROM _cleanup_uuid_targets
                 WHERE target_table = 'remote_assistance_device_keys'
            )
              AND (
                  company_id IS DISTINCT FROM '8f323fba-4358-45fe-9d3b-a8e0fae52993'
                  OR client_installation_id IS DISTINCT FROM
                     '92b491f1-c35b-4437-af9a-a6be68035001'
                  OR status IS DISTINCT FROM 'expired'
                  OR approved_at IS NOT NULL
                  OR revoked_at IS NOT NULL
                  OR created_at < cutoff
              )
       ) THEN
        RAISE EXCEPTION 'expired remote-assistance keys no longer match the retired Code30.1 test installation';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM users actor
          JOIN terminals terminal
            ON terminal.id = '789353a8-09e4-4ef2-9fa8-ac73c426bfc8'
          JOIN branches terminal_branch
            ON terminal_branch.id = terminal.branch_id
           AND terminal_branch.company_id = actor.company_id
         WHERE actor.id = '7016c42c-11c9-48fa-a30a-5d66a8c970b7'
           AND actor.company_id = '8f323fba-4358-45fe-9d3b-a8e0fae52993'
           AND actor.status = 'active'
           AND actor.deleted_at IS NULL
           AND terminal.is_active = true
    ) THEN
        RAISE EXCEPTION 'cleanup audit actor or terminal is no longer active in the expected company';
    END IF;

    IF EXISTS (
        SELECT 1 FROM payments
         WHERE order_id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'orders')
            OR shift_id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'shifts')
    ) OR EXISTS (
        SELECT 1 FROM refunds
         WHERE order_id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'orders')
            OR settlement_shift_id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'shifts')
    ) THEN
        RAISE EXCEPTION 'a payment or refund now depends on a cleanup target';
    END IF;

    IF EXISTS (
        SELECT 1 FROM audit_log audit
         WHERE audit.entity_id IN (
             SELECT id::text FROM _cleanup_uuid_targets
              WHERE target_table NOT IN (
                  'client_installations', 'remote_assistance_device_keys'
              )
         )
           AND audit.id NOT IN (SELECT id FROM _cleanup_audit_targets)
    ) THEN
        RAISE EXCEPTION 'an audit row referring to a target is outside the reviewed allowlist';
    END IF;

    IF EXISTS (
        SELECT 1 FROM idempotency_keys receipt
         WHERE receipt.key NOT IN (SELECT key FROM _cleanup_idempotency_targets)
           AND EXISTS (
               SELECT 1 FROM _cleanup_uuid_targets target
                WHERE target.target_table NOT IN (
                          'client_installations', 'remote_assistance_device_keys'
                      )
                  AND coalesce(receipt.response_body::text, '') LIKE '%' || target.id::text || '%'
           )
    ) THEN
        RAISE EXCEPTION 'an idempotency receipt referring to a target is outside the reviewed allowlist';
    END IF;
END
$business_guard$;

-- Discover every current foreign key into a row that will be deleted. Unknown
-- constraints are allowed only when their matching child count is zero.
CREATE TEMP TABLE _cleanup_fk_observed (
    constraint_name text PRIMARY KEY,
    child_table text NOT NULL,
    parent_table text NOT NULL,
    matching_rows bigint NOT NULL
) ON COMMIT DROP;

DO $dependency_scan$
DECLARE
    fk record;
    join_clause text;
    matching bigint;
    target_ids text[];
BEGIN
    FOR fk IN
        SELECT con.oid,
               con.conname,
               con.conrelid,
               con.confrelid,
               child.relname AS child_table,
               parent.relname AS parent_table,
               con.conkey,
               con.confkey
          FROM pg_constraint con
          JOIN pg_class child ON child.oid = con.conrelid
          JOIN pg_class parent ON parent.oid = con.confrelid
          JOIN pg_namespace child_ns ON child_ns.oid = child.relnamespace
          JOIN pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace
         WHERE con.contype = 'f'
           AND child_ns.nspname = 'public'
           AND parent_ns.nspname = 'public'
           AND parent.relname IN (
               'shifts', 'orders', 'order_lines', 'gaming_sessions',
               'menu_items'
           )
         ORDER BY parent.relname, child.relname, con.conname
    LOOP
        SELECT array_agg(id::text ORDER BY id::text)
          INTO target_ids
          FROM _cleanup_uuid_targets
         WHERE target_table = fk.parent_table;

        SELECT string_agg(
                   format('child_row.%I = parent_row.%I', child_attr.attname, parent_attr.attname),
                   ' AND ' ORDER BY columns.ordinality
               )
          INTO STRICT join_clause
          FROM unnest(fk.conkey, fk.confkey) WITH ORDINALITY
               AS columns(child_attnum, parent_attnum, ordinality)
          JOIN pg_attribute child_attr
            ON child_attr.attrelid = fk.conrelid
           AND child_attr.attnum = columns.child_attnum
          JOIN pg_attribute parent_attr
            ON parent_attr.attrelid = fk.confrelid
           AND parent_attr.attnum = columns.parent_attnum;

        EXECUTE format(
            'SELECT count(*) FROM %s child_row JOIN %s parent_row ON %s '
            'WHERE parent_row.id::text = ANY($1)',
            fk.conrelid::regclass,
            fk.confrelid::regclass,
            join_clause
        ) INTO matching USING target_ids;

        INSERT INTO _cleanup_fk_observed VALUES (
            fk.conname, fk.child_table, fk.parent_table, matching
        );
    END LOOP;
END
$dependency_scan$;

CREATE TEMP TABLE _cleanup_fk_allowed (
    constraint_name text PRIMARY KEY,
    matching_rows bigint NOT NULL
) ON COMMIT DROP;

INSERT INTO _cleanup_fk_allowed VALUES
    ('orders_shift_id_fkey', 3),
    ('gaming_sessions_shift_id_fkey', 5),
    ('gaming_sessions_order_id_fkey', 3),
    ('order_lines_order_id_fkey', 3),
    ('order_lines_menu_item_id_fkey', 1);

DO $dependency_guard$
DECLARE
    mismatch jsonb;
BEGIN
    SELECT jsonb_agg(to_jsonb(diff) ORDER BY diff.constraint_name)
      INTO mismatch
      FROM (
          SELECT observed.constraint_name,
                 observed.child_table,
                 observed.parent_table,
                 coalesce(allowed.matching_rows, 0) AS allowed_rows,
                 observed.matching_rows
            FROM _cleanup_fk_observed observed
            LEFT JOIN _cleanup_fk_allowed allowed USING (constraint_name)
           WHERE observed.matching_rows IS DISTINCT FROM coalesce(allowed.matching_rows, 0)
          UNION ALL
          SELECT allowed.constraint_name, NULL, NULL, allowed.matching_rows, NULL
            FROM _cleanup_fk_allowed allowed
            LEFT JOIN _cleanup_fk_observed observed USING (constraint_name)
           WHERE observed.constraint_name IS NULL
      ) diff;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'cleanup dependency graph changed: %', mismatch;
    END IF;
END
$dependency_guard$;

-- Save every retained row in each intentionally changed table. Postconditions
-- compare these exact hashes so genuine pre-cutoff business rows cannot move.
CREATE TEMP TABLE _cleanup_retained_pre (
    table_name text PRIMARY KEY,
    row_count bigint NOT NULL,
    row_sha256 text NOT NULL
) ON COMMIT DROP;

INSERT INTO _cleanup_retained_pre
SELECT 'shifts', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM shifts row_data WHERE id NOT IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'shifts')
UNION ALL
SELECT 'orders', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM orders row_data WHERE id NOT IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'orders')
UNION ALL
SELECT 'order_lines', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM order_lines row_data WHERE id NOT IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'order_lines')
UNION ALL
SELECT 'gaming_sessions', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM gaming_sessions row_data WHERE id NOT IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'gaming_sessions')
UNION ALL
SELECT 'menu_items', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM menu_items row_data WHERE id NOT IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'menu_items')
UNION ALL
SELECT 'idempotency_keys', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY key), ''), 'UTF8')), 'hex')
  FROM idempotency_keys row_data WHERE key NOT IN (SELECT key FROM _cleanup_idempotency_targets)
UNION ALL
SELECT 'audit_log', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id), ''), 'UTF8')), 'hex')
  FROM audit_log row_data WHERE id NOT IN (SELECT id FROM _cleanup_audit_targets)
UNION ALL
SELECT 'remote_assistance_device_keys', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM remote_assistance_device_keys row_data
UNION ALL
SELECT 'client_installations', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM client_installations row_data;

CREATE TEMP TABLE _cleanup_state (
    state_fingerprint text PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO _cleanup_state
SELECT encode(
    sha256(
        convert_to(
            jsonb_build_object(
                'schema_revision', '0078',
                'full_tables', (
                    SELECT jsonb_object_agg(table_name, jsonb_build_object('count', row_count, 'sha256', row_sha256))
                      FROM _cleanup_all_pre
                ),
                'targets', (
                    SELECT jsonb_object_agg(table_name, jsonb_build_object('count', row_count, 'sha256', row_sha256))
                      FROM _cleanup_target_pre
                ),
                'scalars', (
                    SELECT jsonb_object_agg(name, value) FROM _cleanup_scalar_pre
                ),
                'foreign_keys', (
                    SELECT jsonb_object_agg(constraint_name, matching_rows) FROM _cleanup_fk_observed
                )
            )::text,
            'UTF8'
        )
    ),
    'hex'
);

DO $apply_fingerprint_guard$
DECLARE
    input _cleanup_inputs%ROWTYPE;
    current_fingerprint text;
BEGIN
    SELECT * INTO STRICT input FROM _cleanup_inputs;
    SELECT state_fingerprint INTO STRICT current_fingerprint FROM _cleanup_state;
    IF input.apply
       AND input.expected_state_fingerprint IS DISTINCT FROM current_fingerprint THEN
        RAISE EXCEPTION
            'state fingerprint mismatch; run a fresh dry run after the verified backup and review it before applying';
    END IF;
END
$apply_fingerprint_guard$;

-- Preserve the immutable identity of every deleted retryable action.  This is
-- the permanent fence consulted by the backend after the ordinary receipts
-- and shift rows have been removed.
CREATE TEMP TABLE _cleanup_replay_fence (
    action_type text NOT NULL,
    action_key text PRIMARY KEY,
    request_hash text NOT NULL,
    user_id uuid NOT NULL,
    terminal_id uuid NOT NULL,
    source_entity_id uuid
) ON COMMIT DROP;

INSERT INTO _cleanup_replay_fence
SELECT 'idempotency', key, request_hash, user_id, terminal_id, NULL::uuid
  FROM idempotency_keys
 WHERE key IN (SELECT key FROM _cleanup_idempotency_targets)
UNION ALL
SELECT 'shift_open', opening_action_id, opening_request_hash, opened_by,
       terminal_id, id
  FROM shifts
 WHERE id IN (
     SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'shifts'
 );

DO $replay_fence_guard$
DECLARE
    fence_hash text;
BEGIN
    IF (SELECT count(*) FROM _cleanup_replay_fence) <> 13
       OR (SELECT count(*) FROM _cleanup_replay_fence WHERE action_type = 'idempotency') <> 10
       OR (SELECT count(*) FROM _cleanup_replay_fence WHERE action_type = 'shift_open') <> 3
       OR EXISTS (
           SELECT 1 FROM _cleanup_replay_fence
            WHERE request_hash !~ '^[0-9a-f]{64}$'
               OR action_key !~ '^(gaming-session-(start|stop):|shift-open:).+'
       ) THEN
        RAISE EXCEPTION 'cleanup replay fence does not contain the exact 13 durable action identities';
    END IF;

    SELECT encode(
               sha256(
                   convert_to(
                       string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY action_key),
                       'UTF8'
                   )
               ),
               'hex'
           )
      INTO STRICT fence_hash
      FROM _cleanup_replay_fence row_data;
    IF fence_hash !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'cleanup replay fence could not be fingerprinted';
    END IF;
END
$replay_fence_guard$;

CREATE TEMP TABLE _cleanup_receipt_payload (
    before_data jsonb NOT NULL,
    after_data jsonb NOT NULL
) ON COMMIT DROP;

INSERT INTO _cleanup_receipt_payload
SELECT
    jsonb_build_object(
        'schema_revision', '0078',
        'state_fingerprint', state.state_fingerprint,
        'backup_sha256', input.backup_sha256,
        'quarantine_evidence_sha256', input.quarantine_evidence_sha256,
        'counts', (SELECT jsonb_object_agg(name, value) FROM _cleanup_scalar_pre),
        'audit_log', jsonb_build_object(
            'baseline_max_id', 28202,
            'baseline_count', (SELECT row_count FROM _cleanup_audit_baseline_pre),
            'baseline_sha256', (SELECT row_sha256 FROM _cleanup_audit_baseline_pre),
            'login_success_suffix_count', (SELECT row_count FROM _cleanup_login_suffix_pre),
            'login_success_suffix_sha256', (SELECT row_sha256 FROM _cleanup_login_suffix_pre),
            'login_success_suffix_max_id', (SELECT max_id FROM _cleanup_login_suffix_pre)
        )
    ),
    jsonb_build_object(
        'source_git_sha', input.source_git_sha,
        'backend_image_id', input.backend_image_id,
        'executor', input.executor_name,
        'executed_at', transaction_timestamp(),
        'deleted_counts', jsonb_build_object(
            'audit_log', 37, 'idempotency_keys', 10,
            'gaming_sessions', 5, 'order_lines', 3, 'orders', 3,
            'menu_items', 1, 'shifts', 3
        ),
        'deleted_shift_ids', (SELECT jsonb_agg(id ORDER BY id) FROM _cleanup_uuid_targets WHERE target_table = 'shifts'),
        'deleted_order_ids', (SELECT jsonb_agg(id ORDER BY id) FROM _cleanup_uuid_targets WHERE target_table = 'orders'),
        'deleted_order_line_ids', (SELECT jsonb_agg(id ORDER BY id) FROM _cleanup_uuid_targets WHERE target_table = 'order_lines'),
        'deleted_gaming_session_ids', (SELECT jsonb_agg(id ORDER BY id) FROM _cleanup_uuid_targets WHERE target_table = 'gaming_sessions'),
        'deleted_menu_item_ids', (SELECT jsonb_agg(id ORDER BY id) FROM _cleanup_uuid_targets WHERE target_table = 'menu_items'),
        'retired_test_installation', jsonb_build_object(
            'client_installation_id', '92b491f1-c35b-4437-af9a-a6be68035001',
            'installation_id', 'd664b4a5-d293-48a7-a96c-8c3050ed5e76',
            'version_name', '3.1.28',
            'version_code', 36,
            'pending_outbox_count', 1,
            'pending_outbox_count_unchanged', true,
            'last_seen_at', '2026-09-19T08:33:01.020059+00:00',
            'last_successful_sync_at', '2026-09-19T08:25:19.033+00:00',
            'updated_at', '2026-09-19T08:33:01.020059+00:00',
            'maintenance_mutated_installation', false,
            'retained_remote_assistance_device_key_count', 29,
            'retained_remote_assistance_device_keys_sha256',
                '301905b96650f3f06fc6b3378a1450cc000159a9909f33af3b951a665eca2696',
            'client_was_offline', NULL,
            'synced_at', NULL
        ),
        'local_avd_quarantine', jsonb_build_object(
            'evidence_sha256', input.quarantine_evidence_sha256,
            'avd_count', 18,
            'direct_installation_identity_link_proven', false
        ),
        'deleted_idempotency_keys', (SELECT jsonb_agg(key ORDER BY key) FROM _cleanup_idempotency_targets),
        'deleted_audit_ids', (SELECT jsonb_agg(id ORDER BY id) FROM _cleanup_audit_targets),
        'replay_fence', (
            SELECT jsonb_agg(
                       jsonb_build_object(
                           'action_type', action_type,
                           'action_key', action_key,
                           'request_hash', request_hash,
                           'user_id', user_id,
                           'terminal_id', terminal_id,
                           'source_entity_id', source_entity_id
                       )
                       ORDER BY action_key
                   )
              FROM _cleanup_replay_fence
        ),
        'expected_post_counts', jsonb_build_object(
            'shifts', 7, 'orders', 4, 'order_lines', 4,
            'gaming_sessions', 4, 'menu_items', 4,
            'client_installations', 7,
            'remote_assistance_device_keys', 379,
            'payments', 3, 'refunds', 0, 'customers', 0,
            'idempotency_keys', 37,
            'audit_log', (SELECT row_count - 37 + 1 FROM _cleanup_all_pre WHERE table_name = 'audit_log'),
            'active_sessions', 0, 'open_orders', 0, 'open_shifts', 0,
            'pending_tablet_outbox', 1,
            'google_sheets_unresolved', 0
        )
    )
  FROM _cleanup_inputs input
 CROSS JOIN _cleanup_state state;

DO $receipt_payload_guard$
DECLARE
    payload _cleanup_receipt_payload%ROWTYPE;
BEGIN
    SELECT * INTO STRICT payload FROM _cleanup_receipt_payload;
    IF jsonb_array_length(payload.after_data->'replay_fence') <> 13
       OR payload.before_data #>> '{audit_log,baseline_max_id}' IS DISTINCT FROM '28202'
       OR payload.before_data #>> '{audit_log,baseline_count}' IS DISTINCT FROM '1271'
       OR payload.before_data #>> '{audit_log,baseline_sha256}' IS DISTINCT FROM
          'e2164c700b9ffc67edc1e63623baf943a89b4dd9cd7392a87a12a054aba7cc82'
       OR payload.before_data #>> '{audit_log,login_success_suffix_count}' IS DISTINCT FROM
          (SELECT row_count::text FROM _cleanup_login_suffix_pre)
       OR payload.before_data #>> '{audit_log,login_success_suffix_sha256}' IS DISTINCT FROM
          (SELECT row_sha256 FROM _cleanup_login_suffix_pre)
       OR payload.after_data->>'source_git_sha' !~ '^[0-9a-f]{40}$'
       OR payload.after_data->>'backend_image_id' !~ '^sha256:[0-9a-f]{64}$'
       OR payload.after_data #>> '{retired_test_installation,pending_outbox_count}' IS DISTINCT FROM '1'
       OR payload.after_data #>> '{retired_test_installation,pending_outbox_count_unchanged}' IS DISTINCT FROM 'true'
       OR payload.after_data #>> '{retired_test_installation,maintenance_mutated_installation}' IS DISTINCT FROM 'false'
       OR payload.after_data #>> '{retired_test_installation,retained_remote_assistance_device_key_count}' IS DISTINCT FROM '29'
       OR payload.after_data #>> '{retired_test_installation,retained_remote_assistance_device_keys_sha256}' IS DISTINCT FROM
          '301905b96650f3f06fc6b3378a1450cc000159a9909f33af3b951a665eca2696'
       OR payload.after_data #> '{retired_test_installation,client_was_offline}' IS DISTINCT FROM 'null'::jsonb
       OR payload.after_data #> '{retired_test_installation,synced_at}' IS DISTINCT FROM 'null'::jsonb
       OR payload.after_data #>> '{local_avd_quarantine,evidence_sha256}' IS DISTINCT FROM
          '379c6368936d03223e19482cc840c2a9d2483dc9a96909fba22cd9f59911eec8'
       OR payload.after_data #>> '{local_avd_quarantine,avd_count}' IS DISTINCT FROM '18'
       OR payload.after_data #>> '{local_avd_quarantine,direct_installation_identity_link_proven}' IS DISTINCT FROM 'false' THEN
        -- Dry-run sentinel evidence is structurally valid but deliberately
        -- cannot be committed because this INSERT is behind cleanup_apply.
        IF (SELECT apply FROM _cleanup_inputs) THEN
            RAISE EXCEPTION 'durable cleanup receipt payload is malformed';
        END IF;
    END IF;
END
$receipt_payload_guard$;

CREATE TEMP TABLE _cleanup_audit_receipt (id bigint PRIMARY KEY) ON COMMIT DROP;

-- A dry run deliberately skips this statement entirely.  It therefore does
-- not consume audit_log_id_seq or create an audit row even temporarily.
\if :cleanup_apply
WITH payload AS (SELECT * FROM _cleanup_receipt_payload),
     inserted AS (
         INSERT INTO audit_log (
             actor_user_id, company_id, action, entity_type, entity_id,
             before, after, ip, user_agent, terminal_id, request_id,
             client_platform, client_version_code, client_action_id,
             client_reported_at, client_was_offline, synced_at, reason
         )
         SELECT
             '7016c42c-11c9-48fa-a30a-5d66a8c970b7'::uuid,
             '8f323fba-4358-45fe-9d3b-a8e0fae52993'::uuid,
             'production_trial_cleanup',
             'ReleaseCleanup',
             'code30.1-20260920',
             payload.before_data,
             payload.after_data,
             NULL,
             'cleanup-code30-production-trial-data/2',
             '789353a8-09e4-4ef2-9fa8-ac73c426bfc8'::uuid,
             'code30.1-production-trial-cleanup-20260920',
             NULL,
             NULL,
             'production-trial-cleanup-20260920',
             NULL,
             NULL,
             NULL,
             'Owner-authorized removal of verified post-cutoff test rows after backup restore proof; one unattributed Code30.1 test-installation saved-action report and its 29 remote-assistance keys remain unchanged as historical evidence, all local AVDs were separately wiped, and no direct AVD identity link is claimed.'
           FROM payload
         RETURNING id
     )
INSERT INTO _cleanup_audit_receipt SELECT id FROM inserted;
\endif

CREATE TEMP TABLE _cleanup_deleted_counts (
    table_name text PRIMARY KEY,
    row_count bigint NOT NULL
) ON COMMIT DROP;

WITH deleted AS (
    DELETE FROM audit_log WHERE id IN (SELECT id FROM _cleanup_audit_targets) RETURNING 1
)
INSERT INTO _cleanup_deleted_counts SELECT 'audit_log', count(*) FROM deleted;

WITH deleted AS (
    DELETE FROM idempotency_keys WHERE key IN (SELECT key FROM _cleanup_idempotency_targets) RETURNING 1
)
INSERT INTO _cleanup_deleted_counts SELECT 'idempotency_keys', count(*) FROM deleted;

WITH deleted AS (
    DELETE FROM gaming_sessions
     WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'gaming_sessions')
     RETURNING 1
)
INSERT INTO _cleanup_deleted_counts SELECT 'gaming_sessions', count(*) FROM deleted;

WITH deleted AS (
    DELETE FROM order_lines
     WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'order_lines')
     RETURNING 1
)
INSERT INTO _cleanup_deleted_counts SELECT 'order_lines', count(*) FROM deleted;

WITH deleted AS (
    DELETE FROM orders
     WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'orders')
     RETURNING 1
)
INSERT INTO _cleanup_deleted_counts SELECT 'orders', count(*) FROM deleted;

WITH deleted AS (
    DELETE FROM menu_items
     WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'menu_items')
     RETURNING 1
)
INSERT INTO _cleanup_deleted_counts SELECT 'menu_items', count(*) FROM deleted;

WITH deleted AS (
    DELETE FROM shifts
     WHERE id IN (SELECT id FROM _cleanup_uuid_targets WHERE target_table = 'shifts')
     RETURNING 1
)
INSERT INTO _cleanup_deleted_counts SELECT 'shifts', count(*) FROM deleted;

DO $delete_count_guard$
DECLARE
    mismatch jsonb;
BEGIN
    WITH expected(table_name, row_count) AS (
        VALUES
            ('audit_log', 37::bigint),
            ('idempotency_keys', 10::bigint),
            ('gaming_sessions', 5::bigint),
            ('order_lines', 3::bigint),
            ('orders', 3::bigint),
            ('menu_items', 1::bigint),
            ('shifts', 3::bigint)
    )
    SELECT jsonb_agg(to_jsonb(diff) ORDER BY diff.table_name)
      INTO mismatch
      FROM (
          SELECT expected.table_name,
                 expected.row_count AS expected_count,
                 actual.row_count AS actual_count
            FROM expected
            FULL JOIN _cleanup_deleted_counts actual USING (table_name)
           WHERE expected.table_name IS NULL OR actual.table_name IS NULL
              OR actual.row_count IS DISTINCT FROM expected.row_count
      ) diff;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'one or more cleanup mutations affected an unexpected row count: %', mismatch;
    END IF;
END
$delete_count_guard$;

CREATE TEMP TABLE _cleanup_retained_post (
    table_name text PRIMARY KEY,
    row_count bigint NOT NULL,
    row_sha256 text NOT NULL
) ON COMMIT DROP;

INSERT INTO _cleanup_retained_post
SELECT 'shifts', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex') FROM shifts row_data
UNION ALL
SELECT 'orders', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex') FROM orders row_data
UNION ALL
SELECT 'order_lines', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex') FROM order_lines row_data
UNION ALL
SELECT 'gaming_sessions', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex') FROM gaming_sessions row_data
UNION ALL
SELECT 'menu_items', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex') FROM menu_items row_data
UNION ALL
SELECT 'idempotency_keys', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY key), ''), 'UTF8')), 'hex') FROM idempotency_keys row_data
UNION ALL
SELECT 'audit_log', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id), ''), 'UTF8')), 'hex')
  FROM audit_log row_data WHERE id NOT IN (SELECT id FROM _cleanup_audit_receipt)
UNION ALL
SELECT 'remote_assistance_device_keys', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM remote_assistance_device_keys row_data
UNION ALL
SELECT 'client_installations', count(*), encode(sha256(convert_to(coalesce(string_agg(to_jsonb(row_data)::text, E'\n' ORDER BY id::text), ''), 'UTF8')), 'hex')
  FROM client_installations row_data;

DO $retained_guard$
DECLARE
    mismatch jsonb;
BEGIN
    SELECT jsonb_agg(to_jsonb(diff) ORDER BY diff.table_name)
      INTO mismatch
      FROM (
          SELECT before.table_name,
                 before.row_count AS before_count,
                 after.row_count AS after_count,
                 before.row_sha256 AS before_sha256,
                 after.row_sha256 AS after_sha256
            FROM _cleanup_retained_pre before
            FULL JOIN _cleanup_retained_post after USING (table_name)
           WHERE before.table_name IS NULL OR after.table_name IS NULL
              OR after.row_count IS DISTINCT FROM before.row_count
              OR after.row_sha256 IS DISTINCT FROM before.row_sha256
      ) diff;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'a retained business/system row changed during cleanup: %', mismatch;
    END IF;
END
$retained_guard$;

DO $quarantine_post_guard$
DECLARE
    key_count bigint;
    key_hash text;
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM client_installations post
          JOIN _cleanup_installation_pre pre USING (id)
         WHERE post.pending_outbox_count = 1
           AND to_jsonb(post) = pre.full_row
    ) OR (SELECT count(*) FROM _cleanup_installation_pre) <> 1 THEN
        RAISE EXCEPTION 'retired Code30.1 test installation historical snapshot changed during cleanup';
    END IF;

    SELECT count(*),
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
           )
      INTO STRICT key_count, key_hash
      FROM remote_assistance_device_keys row_data
     WHERE id IN (
         SELECT id FROM _cleanup_uuid_targets
          WHERE target_table = 'remote_assistance_device_keys'
     );
    IF key_count <> 29
       OR key_hash IS DISTINCT FROM
          '301905b96650f3f06fc6b3378a1450cc000159a9909f33af3b951a665eca2696' THEN
        RAISE EXCEPTION 'retained remote-assistance key evidence changed during cleanup';
    END IF;
END
$quarantine_post_guard$;

CREATE TEMP TABLE _cleanup_all_post (
    table_name text PRIMARY KEY,
    row_count bigint NOT NULL,
    row_sha256 text NOT NULL
) ON COMMIT DROP;

DO $snapshot_all_post$
DECLARE
    table_row record;
    snapshot_row record;
BEGIN
    FOR table_row IN
        SELECT c.oid::regclass AS relation, c.relname AS table_name
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relkind IN ('r', 'p')
         ORDER BY c.relname
    LOOP
        SELECT * INTO STRICT snapshot_row
          FROM pg_temp.cleanup_table_snapshot(table_row.relation);
        INSERT INTO _cleanup_all_post VALUES (
            table_row.table_name,
            snapshot_row.row_count,
            snapshot_row.row_sha256
        );
    END LOOP;
END
$snapshot_all_post$;

DO $unrelated_table_guard$
DECLARE
    mismatch jsonb;
BEGIN
    SELECT jsonb_agg(to_jsonb(diff) ORDER BY diff.table_name)
      INTO mismatch
      FROM (
          SELECT before.table_name,
                 before.row_count AS before_count,
                 after.row_count AS after_count,
                 before.row_sha256 AS before_sha256,
                 after.row_sha256 AS after_sha256
            FROM _cleanup_all_pre before
            FULL JOIN _cleanup_all_post after USING (table_name)
           WHERE coalesce(before.table_name, after.table_name) NOT IN (
               'audit_log', 'gaming_sessions', 'idempotency_keys',
               'menu_items', 'order_lines', 'orders',
               'shifts'
           )
             AND (before.table_name IS NULL OR after.table_name IS NULL
                  OR after.row_count IS DISTINCT FROM before.row_count
                  OR after.row_sha256 IS DISTINCT FROM before.row_sha256)
      ) diff;
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'an unrelated production table changed during cleanup: %', mismatch;
    END IF;
END
$unrelated_table_guard$;

DO $postcondition_guard$
DECLARE
    receipt_count bigint;
    apply_mode boolean;
    expected_audit_count bigint;
BEGIN
    SELECT apply INTO STRICT apply_mode FROM _cleanup_inputs;
    SELECT row_count - 37 + CASE WHEN apply_mode THEN 1 ELSE 0 END
      INTO STRICT expected_audit_count
      FROM _cleanup_all_pre
     WHERE table_name = 'audit_log';
    IF (SELECT count(*) FROM shifts) <> 7
       OR (SELECT count(*) FROM shifts WHERE status = 'open') <> 0
       OR (SELECT count(*) FROM orders) <> 4
       OR (SELECT count(*) FROM orders WHERE status IN ('open', 'held')) <> 0
       OR (SELECT count(*) FROM order_lines) <> 4
       OR (SELECT count(*) FROM gaming_sessions) <> 4
       OR (SELECT count(*) FROM gaming_sessions WHERE status IN ('active', 'paused')) <> 0
       OR (SELECT count(*) FROM menu_items) <> 4
       OR (SELECT count(*) FROM client_installations) <> 7
       OR (SELECT count(*) FROM remote_assistance_device_keys) <> 379
       OR (SELECT count(*) FROM payments) <> 3
       OR (SELECT count(*) FROM refunds) <> 0
       OR (SELECT count(*) FROM customers) <> 0
       OR (SELECT count(*) FROM idempotency_keys) <> 37
       OR (SELECT count(*) FROM audit_log) <> expected_audit_count
       OR (SELECT coalesce(sum(pending_outbox_count), 0) FROM client_installations) <> 1
       OR (SELECT count(*) FROM google_sheets_deliveries WHERE status IN ('pending', 'leased', 'quarantined')) <> 0 THEN
        RAISE EXCEPTION 'post-cleanup counts do not match the reviewed final state';
    END IF;

    IF EXISTS (
        SELECT 1 FROM _cleanup_uuid_targets target
         WHERE (target.target_table = 'shifts' AND EXISTS (SELECT 1 FROM shifts WHERE id = target.id))
            OR (target.target_table = 'orders' AND EXISTS (SELECT 1 FROM orders WHERE id = target.id))
            OR (target.target_table = 'order_lines' AND EXISTS (SELECT 1 FROM order_lines WHERE id = target.id))
            OR (target.target_table = 'gaming_sessions' AND EXISTS (SELECT 1 FROM gaming_sessions WHERE id = target.id))
            OR (target.target_table = 'menu_items' AND EXISTS (SELECT 1 FROM menu_items WHERE id = target.id))
    ) OR EXISTS (
        SELECT 1 FROM idempotency_keys WHERE key IN (SELECT key FROM _cleanup_idempotency_targets)
    ) OR EXISTS (
        SELECT 1 FROM audit_log WHERE id IN (SELECT id FROM _cleanup_audit_targets)
    ) THEN
        RAISE EXCEPTION 'one or more allowlisted deletion targets survived cleanup';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM client_installations
         WHERE id = '92b491f1-c35b-4437-af9a-a6be68035001'
           AND installation_id = 'd664b4a5-d293-48a7-a96c-8c3050ed5e76'
           AND pending_outbox_count = 1
           AND last_seen_at = '2026-09-19 08:33:01.020059+00'::timestamptz
           AND last_successful_sync_at = '2026-09-19 08:25:19.033+00'::timestamptz
           AND updated_at = '2026-09-19 08:33:01.020059+00'::timestamptz
    ) OR (SELECT count(*) FROM remote_assistance_device_keys
           WHERE client_installation_id =
                 '92b491f1-c35b-4437-af9a-a6be68035001') <> 29 THEN
        RAISE EXCEPTION 'retired Code30.1 test installation evidence does not match the reviewed final state';
    END IF;

    SELECT count(*) INTO receipt_count
      FROM audit_log receipt
     WHERE receipt.id IN (SELECT id FROM _cleanup_audit_receipt)
       AND receipt.actor_user_id = '7016c42c-11c9-48fa-a30a-5d66a8c970b7'
       AND receipt.company_id = '8f323fba-4358-45fe-9d3b-a8e0fae52993'
       AND receipt.terminal_id = '789353a8-09e4-4ef2-9fa8-ac73c426bfc8'
       AND receipt.action = 'production_trial_cleanup'
       AND receipt.entity_type = 'ReleaseCleanup'
       AND receipt.entity_id = 'code30.1-20260920'
       AND receipt.client_was_offline IS NULL
       AND receipt.synced_at IS NULL;
    IF receipt_count <> (CASE WHEN apply_mode THEN 1 ELSE 0 END) THEN
        RAISE EXCEPTION 'durable production cleanup receipt is absent or malformed';
    END IF;
END
$postcondition_guard$;

SELECT jsonb_build_object(
    'mode', CASE WHEN input.apply THEN 'apply' ELSE 'dry_run_rolled_back' END,
    'state_fingerprint', state.state_fingerprint,
    'schema_revision', '0078',
    'cleanup_audit_id', (SELECT id FROM _cleanup_audit_receipt),
    'deleted_counts', (SELECT jsonb_object_agg(table_name, row_count) FROM _cleanup_deleted_counts),
    'updated_counts', '{}'::jsonb,
    'post_counts', jsonb_build_object(
        'shifts', (SELECT count(*) FROM shifts),
        'open_shifts', (SELECT count(*) FROM shifts WHERE status = 'open'),
        'orders', (SELECT count(*) FROM orders),
        'open_orders', (SELECT count(*) FROM orders WHERE status IN ('open', 'held')),
        'order_lines', (SELECT count(*) FROM order_lines),
        'gaming_sessions', (SELECT count(*) FROM gaming_sessions),
        'active_sessions', (SELECT count(*) FROM gaming_sessions WHERE status IN ('active', 'paused')),
        'client_installations', (SELECT count(*) FROM client_installations),
        'remote_assistance_device_keys', (SELECT count(*) FROM remote_assistance_device_keys),
        'payments', (SELECT count(*) FROM payments),
        'refunds', (SELECT count(*) FROM refunds),
        'customers', (SELECT count(*) FROM customers),
        'pending_tablet_outbox', (SELECT coalesce(sum(pending_outbox_count), 0) FROM client_installations),
        'google_sheets_unresolved', (SELECT count(*) FROM google_sheets_deliveries WHERE status IN ('pending', 'leased', 'quarantined'))
    ),
    'unrelated_tables_unchanged', true,
    'retained_rows_unchanged', true,
    'retired_test_installation_security_evidence_unchanged', true
)::text
FROM _cleanup_inputs input
CROSS JOIN _cleanup_state state;

\if :cleanup_apply
COMMIT;
\else
ROLLBACK;
\endif
