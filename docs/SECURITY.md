# Security

## Authentication

- Passwords hashed with **Argon2id** (memory-hard, modern).
- Access token: JWT, 15 min default, signed HS256 in dev / RS256 in prod.
- Refresh token: 7 days default, rotated on every use, single-use `jti`.
- Failed login policy: 5 failures → 15 min lock (configurable in `Settings`).

## Authorization

- RBAC declared in `backend/app/core/permissions.py`.
- Permissions are `(module, action)` strings; roles are sets of permissions.
- Endpoints declare `tenant = Depends(requires("pos.refund"))`.
- **Manager override** for refunds/voids/large discounts: a second user enters credentials at the till; both user IDs are recorded.

## Multi-tenancy

- Every business row carries `company_id` (and `branch_id` where relevant).
- Tenant context resolved from the JWT into `TenantContext`; every repository scope-checks on it.
- Future: Postgres row-level security policies for defense-in-depth.

## Audit

- Every mutation writes an `audit_log` row with actor, action, entity, before/after, IP, UA.
- Append-only. No updates. No deletes (except by retention sweeper, never per-row).
- Auditor role has read-only access via `/api/v1/admin/audit`.

## Secrets

- Never committed. `.env` is git-ignored; `.env.example` is the documented template.
- Production secrets live in the platform's secret manager (AWS Secrets Manager, GCP Secret Manager, Doppler).
- JWT keys are rotated quarterly with a 24h grace window (dual-key verification).

## Transport

- TLS everywhere in production. HSTS recommended at the edge.
- Internal service-to-service: VPC-private, no public ingress for DB/Redis.

## Input validation

- Pydantic v2 on every request payload.
- SQLAlchemy bound parameters everywhere — no string SQL.

## Rate limiting

- Header-based rate-limit middleware lands in V2 (per-IP + per-user buckets, Redis-backed).
- Login endpoint already enforces per-account lockout.

## Reporting issues

Security issues: email `security@dcompany` (private). Do not file public issues.
