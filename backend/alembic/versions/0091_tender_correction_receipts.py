"""Append-only owner tender receipts and a company-wide source fence.

Revision ID: 0091
Revises: 0090
"""

# SQL below uses only the fixed TABLES identifiers; long SQL literals are kept intact.
# ruff: noqa: E501, S608
from alembic import op

revision = "0091"
down_revision = "0090"
branch_labels = None
depends_on = None

# Fixed identifiers only; no caller-controlled dynamic SQL.
TABLES = (
    "orders",
    "shifts",
    "payments",
    "manual_collections",
    "finance_source_corrections",
    "membership_payments",
    "refunds",
    "pos_refund_requests",
    "pos_refund_withdrawals",
    "pos_refund_cash_handoffs",
    "pos_refund_cash_handoff_completions",
    "pos_refund_provider_payout_starts",
    "pos_refund_provider_settlements",
    "membership_refunds",
    "membership_refund_resolutions",
    "membership_refund_settlements",
    "membership_refund_cash_handoffs",
    "membership_refund_provider_actions",
    "membership_refund_completions",
    "membership_refund_attempt_recoveries",
    "membership_refund_attempt_resolutions",
    "payment_tender_corrections",
    "daily_tender_reconciliations",
)
COMMON = """
 id uuid PRIMARY KEY,
 company_id uuid NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
 branch_id uuid NOT NULL REFERENCES branches(id) ON DELETE RESTRICT,
 business_date date NOT NULL,
 business_timezone varchar(64) NOT NULL,
 recorded_by uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 reason varchar(500) NOT NULL CHECK(length(trim(reason)) >= 3),
 evidence_kind varchar(30) NOT NULL CHECK(evidence_kind = 'owner_attestation'),
 evidence_reference varchar(200),
 source_fingerprint varchar(64) NOT NULL CHECK(source_fingerprint ~ '^[a-f0-9]{64}$'),
 idempotency_key varchar(160) NOT NULL,
 request_hash varchar(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 request_path varchar(200) NOT NULL,
 request_body jsonb NOT NULL CHECK(jsonb_typeof(request_body) = 'object')
"""


# Frozen collection projection shared by direct-SQL validation and all Sheet
# readers. Money, unsupported rails, legacy scope and readiness have one source.
SNAPSHOT_SQL = """
    WITH inputs AS (
      SELECT CAST(p_company AS uuid) AS company_id,
             CAST(p_branch AS uuid) AS branch_id,
             CAST(p_zone AS text) AS timezone_name,
             CAST(p_day AS date) AS start_date,
             CAST(p_day AS date) AS end_date,
             CAST((p_day::timestamp AT TIME ZONE p_zone) AS timestamptz) AS start_utc,
             CAST(((p_day+1)::timestamp AT TIME ZONE p_zone) AS timestamptz) AS end_exclusive_utc
    ), days AS (
      SELECT series.day::date AS day
      FROM inputs i
      CROSS JOIN LATERAL generate_series(
        i.start_date, i.end_date, INTERVAL '1 day'
      ) AS series(day)
    ), scoped_shifts AS (
      SELECT s.id, s.status,
             (s.opened_at AT TIME ZONE i.timezone_name)::date AS day
      FROM shifts s
      CROSS JOIN inputs i
      WHERE s.company_id = i.company_id
        AND s.branch_id = i.branch_id
        AND s.opened_at >= i.start_utc
        AND s.opened_at < i.end_exclusive_utc
    ), movements AS (
      SELECT s.day, p.method AS rail, p.amount_minor AS amount_minor
      FROM payments p
      JOIN scoped_shifts s ON s.id = p.shift_id
      JOIN orders o ON o.id = p.order_id AND o.shift_id = s.id
      CROSS JOIN inputs i
      WHERE o.company_id = i.company_id AND o.branch_id = i.branch_id

      UNION ALL
      SELECT s.day, p.method, p.amount_minor
      FROM membership_payments p
      JOIN scoped_shifts s ON s.id = p.shift_id
      CROSS JOIN inputs i
      WHERE p.company_id = i.company_id AND p.branch_id = i.branch_id

      UNION ALL
      SELECT s.day, r.settlement_method,
             CASE WHEN r.settlement_method = 'store_credit'
                  THEN 0 ELSE -r.amount_minor END
      FROM refunds r
      JOIN scoped_shifts s ON s.id = r.settlement_shift_id
      JOIN orders o ON o.id = r.order_id
      LEFT JOIN pos_refund_requests q
        ON q.id = r.request_id
       AND q.company_id = r.company_id
       AND q.branch_id = r.branch_id
       AND q.shift_id = r.settlement_shift_id
       AND q.order_id = r.order_id
       AND q.settlement_method = r.settlement_method
      CROSS JOIN inputs i
      WHERE r.company_id = i.company_id AND r.branch_id = i.branch_id
        AND o.company_id = i.company_id AND o.branch_id = i.branch_id
        AND (r.request_id IS NULL OR q.id IS NOT NULL)

      UNION ALL
      SELECT s.day, r.method, -r.amount_minor
      FROM membership_refund_settlements r
      JOIN scoped_shifts s ON s.id = r.shift_id
      JOIN membership_refunds q
        ON q.id = r.refund_id
       AND q.company_id = r.company_id
       AND q.branch_id = r.branch_id
       AND q.shift_id = r.shift_id
       AND q.payment_id = r.payment_id
       AND q.method = r.method
       AND q.amount_minor = r.amount_minor
      JOIN membership_payments p
        ON p.id = r.payment_id
       AND p.company_id = r.company_id
      CROSS JOIN inputs i
      WHERE r.company_id = i.company_id AND r.branch_id = i.branch_id

      UNION ALL
      SELECT m.business_date, m.method, m.amount_minor
      FROM manual_collections m
      LEFT JOIN shifts s
        ON s.id = m.shift_id
       AND s.company_id = m.company_id
       AND s.branch_id = m.branch_id
      CROSS JOIN inputs i
      WHERE m.company_id = i.company_id AND m.branch_id = i.branch_id
        AND m.business_date BETWEEN i.start_date AND i.end_date
        AND m.voided_at IS NULL
        AND (m.shift_id IS NULL OR s.id IS NOT NULL)

      UNION ALL
      SELECT s.day, 'cash', -c.amount_minor
      FROM finance_source_corrections c
      JOIN scoped_shifts s ON s.id = c.settlement_shift_id
      JOIN manual_collections m ON m.id = c.manual_collection_id
      JOIN shifts original_shift
        ON original_shift.id = c.original_shift_id
       AND original_shift.company_id = c.company_id
       AND original_shift.branch_id = c.branch_id
      CROSS JOIN inputs i
      WHERE c.company_id = i.company_id AND c.branch_id = i.branch_id
        AND c.source_type = 'manual_collection'
        AND m.company_id = i.company_id AND m.branch_id = i.branch_id
        AND m.shift_id = c.original_shift_id
        AND m.method = 'cash'
      UNION ALL
      SELECT c.business_date, 'cash', -c.amount_minor FROM payment_tender_corrections c CROSS JOIN inputs i
      WHERE c.company_id=i.company_id AND c.branch_id=i.branch_id AND c.business_date BETWEEN i.start_date AND i.end_date
      UNION ALL
      SELECT c.business_date, 'upi', c.amount_minor FROM payment_tender_corrections c CROSS JOIN inputs i
      WHERE c.company_id=i.company_id AND c.branch_id=i.branch_id AND c.business_date BETWEEN i.start_date AND i.end_date
      UNION ALL
      SELECT c.business_date, 'cash', c.cash_delta_minor FROM daily_tender_reconciliations c CROSS JOIN inputs i
      WHERE c.company_id=i.company_id
    AND c.branch_id=i.branch_id
    AND c.business_date BETWEEN i.start_date
    AND i.end_date
    AND c.kind='applied'
      UNION ALL
      SELECT c.business_date, 'upi', c.upi_delta_minor FROM daily_tender_reconciliations c CROSS JOIN inputs i
      WHERE c.company_id=i.company_id
    AND c.branch_id=i.branch_id
    AND c.business_date BETWEEN i.start_date
    AND i.end_date
    AND c.kind='applied'
    ), rails AS (
      SELECT day, COALESCE(rail, 'unknown') AS rail,
             SUM(amount_minor)::bigint AS net_minor,
             COUNT(*)::bigint AS movement_count
      FROM movements
      GROUP BY day, COALESCE(rail, 'unknown')
    ), daily AS (
      SELECT day,
             COALESCE(SUM(net_minor) FILTER (WHERE rail = 'cash'), 0)::bigint
               AS cash_minor,
             COALESCE(SUM(net_minor) FILTER (WHERE rail = 'upi'), 0)::bigint
               AS upi_minor,
             COALESCE(
               SUM(net_minor) FILTER (WHERE rail IN ('cash', 'upi')),
               0
             )::bigint AS total_minor,
             COALESCE(SUM(movement_count), 0)::bigint AS movement_count,
             COALESCE(
               jsonb_object_agg(rail, net_minor)
                 FILTER (WHERE rail NOT IN ('cash', 'upi', 'store_credit')),
               '{}'::jsonb
             ) AS unsupported_rails
      FROM rails
      GROUP BY day
    ), shift_counts AS (
      SELECT day, COUNT(*)::bigint AS shift_count,
             0::bigint AS open_shift_count
      FROM scoped_shifts
      GROUP BY day
    ), open_shift_days AS (
      SELECT day, id
      FROM scoped_shifts
      WHERE status = 'open'
      UNION
      SELECT m.business_date, s.id
      FROM manual_collections m
      JOIN shifts s
        ON s.id = m.shift_id
       AND s.company_id = m.company_id
       AND s.branch_id = m.branch_id
      CROSS JOIN inputs i
      WHERE m.company_id = i.company_id
        AND m.branch_id = i.branch_id
        AND m.business_date BETWEEN i.start_date AND i.end_date
        AND m.method = 'cash'
        AND m.voided_at IS NULL
        AND s.status = 'open'
    ), open_shift_counts AS (
      SELECT day, COUNT(DISTINCT id)::bigint AS open_shift_count
      FROM open_shift_days
      GROUP BY day
    ), legacy_refunds AS (
      SELECT (COALESCE(r.settled_at, r.created_at)
                AT TIME ZONE i.timezone_name)::date AS day,
             COUNT(*)::bigint AS legacy_refund_count
      FROM refunds r
      JOIN orders o ON o.id = r.order_id
      CROSS JOIN inputs i
      WHERE o.company_id = i.company_id AND o.branch_id = i.branch_id
        AND (r.company_id IS NULL OR r.company_id = i.company_id)
        AND (r.branch_id IS NULL OR r.branch_id = i.branch_id)
        AND r.settlement_shift_id IS NULL
        AND COALESCE(r.settled_at, r.created_at) >= i.start_utc
        AND COALESCE(r.settled_at, r.created_at) < i.end_exclusive_utc
      GROUP BY (COALESCE(r.settled_at, r.created_at)
                  AT TIME ZONE i.timezone_name)::date
    )
    SELECT d.day,
           COALESCE(a.cash_minor, 0)::bigint AS cash_minor,
           COALESCE(a.upi_minor, 0)::bigint AS upi_minor,
           COALESCE(a.total_minor, 0)::bigint AS total_minor,
           COALESCE(a.movement_count, 0)::bigint AS movement_count,
           COALESCE(s.shift_count, 0)::bigint AS shift_count,
           COALESCE(o.open_shift_count, 0)::bigint AS open_shift_count,
           COALESCE(l.legacy_refund_count, 0)::bigint AS legacy_refund_count,
           COALESCE(a.unsupported_rails, '{}'::jsonb) AS unsupported_rails
    FROM days d
    LEFT JOIN daily a ON a.day = d.day
    LEFT JOIN shift_counts s ON s.day = d.day
    LEFT JOIN open_shift_counts o ON o.day = d.day
    LEFT JOIN legacy_refunds l ON l.day = d.day
    ORDER BY d.day
    """


def _snapshot_function_sql():
    return (
        """
CREATE FUNCTION dcompany_collection_day_snapshot(p_company uuid,p_branch uuid,p_day date,p_zone text)
RETURNS jsonb LANGUAGE sql STABLE SET timezone='UTC' AS $$
SELECT to_jsonb(snapshot) FROM (
"""
        + SNAPSHOT_SQL
        + """
) snapshot
$$;
CREATE FUNCTION dcompany_tender_totals(p_company uuid,p_branch uuid,p_day date,p_zone text)
RETURNS TABLE(cash_minor bigint,upi_minor bigint) LANGUAGE sql VOLATILE AS $$
SELECT (s->>'cash_minor')::bigint,(s->>'upi_minor')::bigint
FROM dcompany_collection_day_snapshot(p_company,p_branch,p_day,p_zone) s
$$;
"""
    )


def upgrade():
    op.execute(
        "CREATE TABLE collection_source_guards(company_id uuid PRIMARY KEY REFERENCES companies(id) ON DELETE RESTRICT, generation bigint NOT NULL DEFAULT 0 CHECK(generation >= 0))"
    )
    op.execute("INSERT INTO collection_source_guards(company_id) SELECT id FROM companies")
    op.execute(
        "CREATE TABLE payment_tender_corrections ("
        + COMMON
        + """,
 payment_id uuid NOT NULL UNIQUE REFERENCES payments(id) ON DELETE RESTRICT,
 order_id uuid NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
 shift_id uuid NOT NULL REFERENCES shifts(id) ON DELETE RESTRICT,
 invoice_no varchar(32) NOT NULL,
 original_method varchar(20) NOT NULL CHECK(original_method = 'cash'),
 effective_method varchar(20) NOT NULL CHECK(effective_method = 'upi'),
 amount_minor bigint NOT NULL CHECK(amount_minor > 0),
 original_tendered_minor bigint, original_change_minor bigint,
 accounting_at timestamptz NOT NULL,
 UNIQUE(company_id, idempotency_key)
 )"""
    )
    op.execute(
        "CREATE TABLE daily_tender_reconciliations ("
        + COMMON
        + """,
 kind varchar(20) NOT NULL CHECK(kind IN ('applied', 'unresolved')),
 before_cash_minor bigint NOT NULL, before_upi_minor bigint NOT NULL,
 actual_cash_minor bigint NOT NULL CHECK(actual_cash_minor >= 0),
 actual_upi_minor bigint NOT NULL CHECK(actual_upi_minor >= 0),
 cash_delta_minor bigint NOT NULL, upi_delta_minor bigint NOT NULL,
 total_difference_minor bigint NOT NULL,
 CHECK((kind = 'applied' AND cash_delta_minor < 0 AND upi_delta_minor = -cash_delta_minor AND total_difference_minor = 0)
 OR (kind = 'unresolved' AND cash_delta_minor = 0 AND upi_delta_minor = 0 AND total_difference_minor <> 0)),
 UNIQUE(company_id, idempotency_key)
 )"""
    )
    for table in ("payment_tender_corrections", "daily_tender_reconciliations"):
        op.execute(
            f"CREATE INDEX ix_{table}_scope ON {table}(company_id, branch_id, business_date)"
        )
        op.execute(
            f"CREATE TRIGGER immutable_tender_receipt BEFORE UPDATE OR DELETE ON {table} FOR EACH ROW EXECUTE FUNCTION guard_audit_log_append_only()"
        )
        op.execute(
            f"CREATE TRIGGER immutable_tender_truncate BEFORE TRUNCATE ON {table} FOR EACH STATEMENT EXECUTE FUNCTION guard_audit_log_append_only()"
        )
    op.execute("""
CREATE FUNCTION dcompany_collection_touch(p_company uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 IF p_company IS NULL THEN RETURN; END IF;
 INSERT INTO collection_source_guards(company_id,generation) VALUES(p_company,1)
 ON CONFLICT(company_id) DO UPDATE SET generation=collection_source_guards.generation+1;
END $$;
CREATE FUNCTION dcompany_collection_lock(p_company uuid) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE v bigint;
BEGIN
 INSERT INTO collection_source_guards(company_id,generation) VALUES(p_company,0) ON CONFLICT DO NOTHING;
 SELECT generation INTO STRICT v FROM collection_source_guards WHERE company_id=p_company FOR UPDATE;
 RETURN v;
END $$;
CREATE FUNCTION dcompany_collection_company(j jsonb) RETURNS uuid LANGUAGE plpgsql STABLE AS $$
DECLARE v uuid;
BEGIN
 v := NULLIF(j->>'company_id','')::uuid;
 IF v IS NOT NULL THEN RETURN v; END IF;
 IF j ? 'order_id' THEN SELECT company_id INTO v FROM orders WHERE id=(j->>'order_id')::uuid;
 ELSIF j ? 'refund_request_id' THEN SELECT company_id INTO v FROM pos_refund_requests WHERE id=(j->>'refund_request_id')::uuid;
 ELSIF j ? 'refund_id' THEN SELECT company_id INTO v FROM membership_refunds WHERE id=(j->>'refund_id')::uuid;
 END IF;
 RETURN v;
END $$;
CREATE FUNCTION dcompany_collection_fence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a uuid; b uuid; v uuid;
BEGIN
 IF TG_OP <> 'INSERT' THEN a:=dcompany_collection_company(to_jsonb(OLD)); END IF;
 IF TG_OP <> 'DELETE' THEN b:=dcompany_collection_company(to_jsonb(NEW)); END IF;
 FOR v IN SELECT DISTINCT x FROM unnest(ARRAY[a,b]) x WHERE x IS NOT NULL ORDER BY x LOOP
  PERFORM dcompany_collection_touch(v);
 END LOOP;
 RETURN NULL;
END $$;
""")
    # The whole-company generation intentionally invalidates even an unrelated
    # branch preview. Normal financial writes do not acquire another parent
    # from this AFTER trigger; deadlock retries cover multistatement callers.
    for table in TABLES:
        op.execute(
            f"CREATE TRIGGER zz_collection_fence AFTER INSERT OR UPDATE OR DELETE ON {table} FOR EACH ROW EXECUTE FUNCTION dcompany_collection_fence()"
        )
    source_parts = []
    for table in TABLES:
        company = (
            "t.company_id"
            if table != "payments"
            else "(SELECT o.company_id FROM orders o WHERE o.id=t.order_id)"
        )
        if table == "refunds":
            company = (
                "COALESCE(t.company_id,(SELECT o.company_id FROM orders o WHERE o.id=t.order_id))"
            )
        source_parts.append(
            f"SELECT '{table}' AS kind, t.id::text AS id, to_jsonb(t) AS fact FROM {table} t WHERE {company}=p_company"
        )
    op.execute(
        """
CREATE FUNCTION dcompany_collection_fingerprint(p_company uuid,p_branch uuid,p_day date,p_zone text)
RETURNS text LANGUAGE sql VOLATILE SET timezone='UTC' AS $$
 SELECT encode(sha256(convert_to(jsonb_build_object(
  'contract',1,'company',p_company,'branch',p_branch,'day',p_day,'zone',p_zone,
  'generation',g.generation,
  'configuration',jsonb_build_object('timezone',c.timezone,'currency',c.currency,'units',c.currency_minor_units,
   'company_deleted_at',c.deleted_at,'branch_timezone',b.timezone,'branch_deleted_at',b.deleted_at,'export_mode',c.google_sheets_export_mode,
   'configuration_id',c.google_sheets_configuration_id,'summary_branch',c.google_sheets_summary_branch_id,
   'summary_start',c.google_sheets_summary_start_date,'summary_zone',c.google_sheets_summary_timezone),
  'sources',(SELECT COALESCE(jsonb_agg(jsonb_build_object('kind',kind,'id',id,'fact',fact) ORDER BY kind,id),'[]'::jsonb)
   FROM ("""
        + " UNION ALL ".join(source_parts)
        + """ ) facts)
 )::text,'UTF8')),'hex') FROM companies c JOIN collection_source_guards g ON g.company_id=c.id
 JOIN branches b ON b.company_id=c.id AND b.id=p_branch WHERE c.id=p_company
$$;
__SNAPSHOT_FUNCTION__
CREATE FUNCTION dcompany_tender_config_fence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE company_uuid uuid;
BEGIN
 company_uuid:=CASE WHEN TG_TABLE_NAME='companies' THEN (to_jsonb(NEW)->>'id')::uuid ELSE (to_jsonb(NEW)->>'company_id')::uuid END;
 PERFORM dcompany_collection_lock(company_uuid);
 IF TG_TABLE_NAME='branches' AND (to_jsonb(NEW)->>'company_id') IS DISTINCT FROM (to_jsonb(OLD)->>'company_id') THEN
  RAISE EXCEPTION 'branch company identity cannot be reinterpreted by tender settings' USING ERRCODE='23514';
 END IF;
 IF (TG_TABLE_NAME='companies'
    AND (to_jsonb(NEW)->>'timezone',to_jsonb(NEW)->>'currency',to_jsonb(NEW)->>'currency_minor_units',COALESCE(to_jsonb(NEW)->>'google_sheets_summary_timezone',to_jsonb(NEW)->>'timezone')) IS DISTINCT FROM (to_jsonb(OLD)->>'timezone',to_jsonb(OLD)->>'currency',to_jsonb(OLD)->>'currency_minor_units',COALESCE(to_jsonb(OLD)->>'google_sheets_summary_timezone',to_jsonb(OLD)->>'timezone')))
 OR (TG_TABLE_NAME='branches' AND NEW.timezone IS DISTINCT FROM OLD.timezone) THEN
  IF EXISTS(SELECT 1 FROM payment_tender_corrections WHERE company_id=company_uuid)
    OR EXISTS(SELECT 1 FROM daily_tender_reconciliations WHERE company_id=company_uuid) THEN
   RAISE EXCEPTION 'tender evidence requires its captured timezone and currency; explicit period migration required' USING ERRCODE='23514';
  END IF;
 END IF;
 PERFORM dcompany_collection_touch(company_uuid);
 RETURN NEW;
END $$;
CREATE TRIGGER zz_tender_company_configuration BEFORE UPDATE OF timezone,currency,currency_minor_units,deleted_at,google_sheets_configuration_id,google_sheets_export_mode,google_sheets_summary_branch_id,google_sheets_summary_start_date,google_sheets_summary_timezone ON companies FOR EACH ROW EXECUTE FUNCTION dcompany_tender_config_fence();
CREATE TRIGGER zz_tender_branch_configuration BEFORE UPDATE OF timezone,company_id,deleted_at ON branches FOR EACH ROW EXECUTE FUNCTION dcompany_tender_config_fence();
CREATE FUNCTION dcompany_validate_tender_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p payments%ROWTYPE; o orders%ROWTYPE; s shifts%ROWTYPE; c companies%ROWTYPE; actual_cash bigint; actual_upi bigint; zone text; snapshot jsonb;
BEGIN
 IF TG_TABLE_NAME='payment_tender_corrections' THEN
  SELECT * INTO STRICT o FROM orders WHERE id=NEW.order_id FOR UPDATE;
  SELECT * INTO STRICT s FROM shifts WHERE id=o.shift_id FOR UPDATE;
  SELECT * INTO STRICT p FROM payments WHERE id=NEW.payment_id FOR UPDATE;
 END IF;
 PERFORM dcompany_collection_lock(NEW.company_id);
 IF EXISTS(SELECT 1 FROM payment_tender_corrections WHERE company_id=NEW.company_id
    AND idempotency_key=NEW.idempotency_key)
    OR EXISTS(SELECT 1 FROM daily_tender_reconciliations WHERE company_id=NEW.company_id
    AND idempotency_key=NEW.idempotency_key) THEN
  RAISE EXCEPTION 'tender action already recorded; recover its immutable receipt' USING ERRCODE='23505';
 END IF;
 SELECT * INTO STRICT c FROM companies WHERE id=NEW.company_id;
 zone:=COALESCE(c.google_sheets_summary_timezone,c.timezone);
 IF NEW.business_timezone IS DISTINCT FROM zone
    OR NOT EXISTS(SELECT 1 FROM branches WHERE id=NEW.branch_id
    AND company_id=NEW.company_id
    AND deleted_at IS NULL)

    OR NOT EXISTS(SELECT 1 FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE u.id=NEW.recorded_by
    AND u.company_id=NEW.company_id
    AND u.status='active'
    AND u.deleted_at IS NULL
    AND r.company_id=u.company_id
    AND r.code='super_owner')
 OR c.deleted_at IS NOT NULL OR NEW.business_date > (clock_timestamp() AT TIME ZONE zone)::date
 OR (TG_TABLE_NAME='daily_tender_reconciliations' AND NEW.business_date = (clock_timestamp() AT TIME ZONE zone)::date) THEN
  RAISE EXCEPTION 'tender receipt scope, actor or closed business date is invalid' USING ERRCODE='23514';
 END IF;
 IF NEW.source_fingerprint IS DISTINCT FROM dcompany_collection_fingerprint(NEW.company_id,NEW.branch_id,NEW.business_date,zone) THEN
  RAISE EXCEPTION 'tender preview is stale; refresh and review the source again' USING ERRCODE='23514';
 END IF;
 IF TG_TABLE_NAME='payment_tender_corrections' THEN
  IF p.order_id<>o.id
    OR p.shift_id<>s.id
    OR NEW.shift_id<>s.id
    OR o.company_id<>NEW.company_id
    OR o.branch_id<>NEW.branch_id
    OR s.company_id<>NEW.company_id
    OR s.branch_id<>NEW.branch_id
    OR s.status<>'closed'
    OR s.closed_at IS NULL
    OR o.status<>'paid'
    OR o.invoice_no IS NULL
 
    OR p.method<>'cash'
    OR NEW.original_method<>'cash'
    OR NEW.effective_method<>'upi'
    OR NEW.amount_minor<>p.amount_minor
    OR NEW.invoice_no<>o.invoice_no
 
    OR NEW.original_tendered_minor IS DISTINCT FROM p.tendered_minor
    OR NEW.original_change_minor IS DISTINCT FROM p.change_minor
    OR NEW.accounting_at IS DISTINCT FROM p.paid_at
    OR NEW.business_date<>(s.opened_at AT TIME ZONE zone)::date THEN
   RAISE EXCEPTION 'payment tender correction must preserve the complete original closed-shift cash leg' USING ERRCODE='23514';
  END IF;
  IF EXISTS(SELECT 1 FROM refunds WHERE order_id=o.id)
    OR EXISTS(SELECT 1 FROM pos_refund_requests q WHERE q.order_id=o.id
    AND NOT EXISTS(SELECT 1 FROM pos_refund_withdrawals w WHERE w.refund_request_id=q.id)) THEN
   RAISE EXCEPTION 'settled or unresolved refund prevents tender correction' USING ERRCODE='23514';
  END IF;
  IF EXISTS(SELECT 1 FROM daily_tender_reconciliations r WHERE r.company_id=NEW.company_id
    AND r.branch_id=NEW.branch_id
    AND r.business_date=NEW.business_date
    AND r.kind='applied') THEN
   RAISE EXCEPTION 'unallocated reconciliation requires explicit replacement before correcting another bill' USING ERRCODE='23514';
  END IF;
 ELSE
  IF EXISTS(SELECT 1 FROM shifts WHERE company_id=NEW.company_id
    AND branch_id=NEW.branch_id
    AND status='open'
    AND (opened_at AT TIME ZONE zone)::date=NEW.business_date) THEN
   RAISE EXCEPTION 'close and sync the business day before attesting actuals' USING ERRCODE='23514';
  END IF;
  snapshot:=dcompany_collection_day_snapshot(NEW.company_id,NEW.branch_id,NEW.business_date,zone);
  IF (snapshot->>'open_shift_count')::bigint > 0
    OR (snapshot->>'legacy_refund_count')::bigint > 0
    OR EXISTS(SELECT 1 FROM jsonb_each_text(snapshot->'unsupported_rails') kv WHERE kv.key NOT IN ('card','qr','wallet')
    AND kv.value::bigint<>0) THEN
   RAISE EXCEPTION 'daily collection readiness or legacy rail evidence requires review' USING ERRCODE='23514';
  END IF;
  SELECT cash_minor,upi_minor INTO actual_cash,actual_upi FROM dcompany_tender_totals(NEW.company_id,NEW.branch_id,NEW.business_date,zone);
  IF NEW.before_cash_minor<>actual_cash
    OR NEW.before_upi_minor<>actual_upi
    OR NEW.total_difference_minor<>(NEW.actual_cash_minor+NEW.actual_upi_minor-actual_cash-actual_upi) THEN
   RAISE EXCEPTION 'daily actuals do not match the reviewed source totals' USING ERRCODE='23514';
  END IF;
  IF NEW.kind='applied'
    AND (NEW.cash_delta_minor<>NEW.actual_cash_minor-actual_cash
    OR NEW.upi_delta_minor<>NEW.actual_upi_minor-actual_upi) THEN
   RAISE EXCEPTION 'daily reconciliation must be an exact zero-revenue cash to UPI transfer' USING ERRCODE='23514';
  END IF;
 END IF;
 NEW.recorded_at:=clock_timestamp();
 RETURN NEW;
END $$;
CREATE FUNCTION dcompany_validate_effective_refund_rail() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE effective text; methods integer;
BEGIN
 PERFORM 1 FROM orders WHERE id=NEW.order_id FOR UPDATE;
 PERFORM 1 FROM shifts WHERE id=NEW.shift_id FOR UPDATE;
 PERFORM dcompany_collection_lock(NEW.company_id);
 IF NEW.mode='original' THEN
  SELECT count(DISTINCT COALESCE(c.effective_method,p.method)),min(COALESCE(c.effective_method,p.method)) INTO methods,effective
  FROM payments p LEFT JOIN payment_tender_corrections c ON c.payment_id=p.id WHERE p.order_id=NEW.order_id;
  IF methods<>1 OR NEW.settlement_method IS DISTINCT FROM effective THEN
   RAISE EXCEPTION 'original refund must use the effective corrected payment method' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER zz_effective_refund_rail BEFORE INSERT ON pos_refund_requests FOR EACH ROW EXECUTE FUNCTION dcompany_validate_effective_refund_rail();
CREATE TRIGGER validate_tender_receipt BEFORE INSERT ON payment_tender_corrections FOR EACH ROW EXECUTE FUNCTION dcompany_validate_tender_receipt();
CREATE TRIGGER validate_tender_receipt BEFORE INSERT ON daily_tender_reconciliations FOR EACH ROW EXECUTE FUNCTION dcompany_validate_tender_receipt();
""".replace("__SNAPSHOT_FUNCTION__", _snapshot_function_sql())
    )


def downgrade():
    op.execute(
        "LOCK TABLE payment_tender_corrections, daily_tender_reconciliations IN ACCESS EXCLUSIVE MODE"
    )
    op.execute(
        """DO $$ BEGIN IF EXISTS(SELECT 1 FROM payment_tender_corrections) OR EXISTS(SELECT 1 FROM daily_tender_reconciliations) THEN RAISE EXCEPTION 'cannot discard tender evidence; retain correction-aware readers'; END IF; END $$;"""
    )
    op.execute(
        "DROP TRIGGER zz_effective_refund_rail ON pos_refund_requests; DROP FUNCTION dcompany_validate_effective_refund_rail()"
    )
    op.execute(
        "DROP TRIGGER zz_tender_company_configuration ON companies; DROP TRIGGER zz_tender_branch_configuration ON branches"
    )
    for table in TABLES:
        op.execute(f"DROP TRIGGER zz_collection_fence ON {table}")
    op.execute(
        "DROP FUNCTION dcompany_validate_tender_receipt() CASCADE; DROP FUNCTION dcompany_tender_config_fence(); DROP FUNCTION dcompany_tender_totals(uuid,uuid,date,text); DROP FUNCTION dcompany_collection_day_snapshot(uuid,uuid,date,text); DROP FUNCTION dcompany_collection_fingerprint(uuid,uuid,date,text); DROP FUNCTION dcompany_collection_fence(); DROP FUNCTION dcompany_collection_company(jsonb); DROP FUNCTION dcompany_collection_lock(uuid); DROP FUNCTION dcompany_collection_touch(uuid)"
    )
    op.execute(
        "DROP TABLE payment_tender_corrections; DROP TABLE daily_tender_reconciliations; DROP TABLE collection_source_guards"
    )
