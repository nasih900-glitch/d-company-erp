# Handoff — switching from Claude to Codex (or any other AI assistant)

Hi Codex. This project was built in Claude. Below is everything you need to take it from here.

---

## Step 1 — Read these two files in order

1. **`AGENTS.md`** (at repo root) — project rules, conventions, file map, common commands. This file is specifically formatted for AI coding assistants and is what you'd normally read first in any new repo.
2. **`PROJECT_STATE.md`** (at repo root) — the running status snapshot: what's built, what's deployed, what's pending, recent changes.

Together those two files contain ~95% of the context you need.

---

## Step 2 — Verify the system is healthy

```bash
cd backend && python -m pytest tests/ -q
# expect locally: 39 passed, 3 skipped if Postgres is not running

cd ../frontend && npx tsc --noEmit
# expect: no output (clean)

npx vite build
# expect: ✓ built in ~3s

# Verify production droplet
curl -s https://dcompany.duckdns.org/healthz
# expect: {"status":"ok"}
```

If any of those fail, that's where to start.

---

## Step 3 — Ask the user what's next

Some common ways the user has framed work:

- **"Add WhatsApp alerts"** → see `docs/DEFERRED_FEATURES.md` section 1. Needs Twilio credentials.
- **"Wire Razorpay"** → see deferred features section 6. User has test keys in progress.
- **"Fix [a screen] — [a bug]"** → reproduce on the live URL, find the screen file under `frontend/src/modules/`, fix the backend if needed (look up the route in `app/api/v1/{module}/router.py`), verify tests, deploy.
- **"Add [a feature]"** → ask for clarification first. The user prefers verifying scope upfront over half-built features.
- **"Do a deep diagnostic"** → run the strict audit script the previous agent built (see the commit before this handoff).

---

## Step 4 — Conventions to keep

These are documented in `AGENTS.md` but worth repeating:

- **Money is `int` minor units (paise) always.** Variable suffix `_minor`. Never `float`.
- **Multi-tenant** — every model has `company_id`; every query filters by it.
- **Permissions** — `Depends(requires("perm.string"))`. Don't use a permission not declared in `app/core/permissions.py`.
- **Migrations required** — any new column on any model needs an `alembic/versions/000N_*.py` migration.
- **Audit log auto-records** — to add a new model to the trail, append to `TRACKED` in `app/services/audit/recorder.py`.
- **Token refresh** is automatic via the axios interceptor in `frontend/src/lib/api.ts`. Don't try to handle 401s in components.
- **Demo mode is permanently OFF.** Don't add new `if (LIVE_MODE)` branches.

---

## Step 5 — Deploy

The user runs deploys manually from their Mac. The deploy command is in `AGENTS.md` under "Common commands → Deploy to droplet". Backend changes typically need `--force-recreate backend`, frontend changes need `--force-recreate frontend`. Both if both changed.

---

## What I (the previous agent) built across all sessions

A complete production ERP. The repo at this commit contains:
- 94 backend routes across 20 modules
- 22 frontend screens
- 58 tables across 6 alembic migrations
- 35 passing tests
- Full audit logging
- Multi-revenue tracking (Food, Gaming, Hookah, Events, Delivery §9(5))
- Kerala GST compliance + GSTR-1/3B CSV exports
- Customer DB + loyalty + memberships
- Kitchen Display System
- Recipe-driven inventory deduction with FIFO
- Accounting reports (TB, BS, GL, COA)
- Business insights (growth, top items, heatmap, losses)
- Public menu for QR ordering
- Automated daily/weekly/monthly/quarterly/half-yearly/yearly P&L email infrastructure (awaits SMTP)
- Google Sheets sync to user's existing sheet's "Operations" tab

Production URL: <https://dcompany.duckdns.org>
Droplet: configured by the owner. Do not commit live IP addresses, SSH key names, or credentials.

---

## What's pending (user-facing TODOs)

From `PROJECT_STATE.md` "What's pending":

### Critical
- SMTP/report recipient setup is required before automated P&L emails can actually send

### Need user input or credentials
- WhatsApp alerts → Twilio or Meta WhatsApp Business creds
- Automated P&L email → SMTP creds + real report recipient
- Razorpay subscription billing → test keys (user is signing up)

### Hardening
- Subscription expiry worker
- Weekly counters reset worker
- IP/device rate limiting on `/auth/login` (account lockout is already implemented)
- Multi-terminal `SELECT FOR UPDATE` on inventory FIFO
- Full double-entry journal posting (TB/BS currently derived)

### Polish
- POS ad-hoc discount UI

---

## How to talk to the user

Their style preferences (collected over many sessions):

- **Direct and concise.** Don't preamble or apologize.
- **Prose over bullet lists** for most replies. Lists only when actually a list (instructions, options).
- **Verify before claiming "done".** Compile + tests + build before saying it works.
- **Honest about gaps.** They've explicitly asked "have you thoroughly checked?" and want real findings, not reassurance.
- **No emojis** unless they used one first.

They're based in the UK, the café is in Kerala. Partners: Nasih (the user), Shemeer, Rafi. They've invested ₹19.23L against a ₹20L budget. The café is in construction, opening soon. They are NOT a developer — they understand high-level technical concepts but don't read code. Show progress, not implementation details.

Current production roles: Nasih has protected owner access. Shemeer, Rafi, and Sameer are owner business users. `Inventory`, `Audit Log`, `Staff`, and `Settings` are protected for Nasih only; normal owners keep broad business access outside those protected controls.

Good luck.

— Previous agent (Claude)
