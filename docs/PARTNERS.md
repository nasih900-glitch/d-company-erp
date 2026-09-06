# Partner access — Code17 safe provisioning

Partners use individual ERP identities; never share the protected owner login.
Choose the least-privileged role that matches the work they actually perform.
Prefer the authenticated **Staff** workflow because it records the acting owner
and applies the same role policy as the API.

## Create a new partner identity

If the authenticated Staff workflow is unavailable, an authorized VM operator
may create a new, non-protected account from an interactive, non-recorded
terminal:

```bash
cd /opt/d-company-erp
docker compose -f docker-compose.prod.yml --env-file .env exec backend \
  python -m scripts.create_user \
  --email partner@example.com \
  --name "Partner Name" \
  --role partner
```

The command reads and confirms a temporary password with terminal echo
disabled. It never prints the secret or accepts it in argv. Transfer the
password through an approved password-manager/secret channel separately from
the login URL, require a change after first login, and never include it in
email, chat history, tickets, or shell logs.

The console command is create-only. It refuses any existing email and cannot
create the internal `super_owner`/`co_owner` roles. It never overwrites a
password or role, so it cannot silently demote the protected owner. If the
deployment contains more than one company, pass the exact tenant UUID with
`--company-id`.

## Select a role

- `partner`: business/finance visibility and explicitly granted partner work;
  no protected Audit/Access Control authority.
- `auditor`: read-only operational and finance review.
- `owner`: broad business operations; grant only after a separate approval.

Role permissions remain server-authoritative. Review the effective access in
the protected Access Control screen before issuing credentials; do not rely on
an old role table copied into a message or document.

## Change access or recover credentials

Use authenticated **Staff** role management for an existing user. Use the
centrally approved OTP password-reset flow for a forgotten partner password.
Do not rerun `scripts.create_user`; an existing identity is intentionally a
hard error.

`scripts.reset_owner_password` is an emergency local-console tool only for the
single configured `SEED_OWNER_EMAIL` protected super owner. It is not a general
partner reset mechanism.

Every partner should sign in with their own account, install only the approved
client, and sign out/revoke access promptly when the relationship changes.
