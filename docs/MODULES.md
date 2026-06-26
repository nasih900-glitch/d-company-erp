# Modules — one-paragraph orientation

**POS** (`backend/app/api/v1/pos`, `frontend/src/modules/pos`) — Billing for dine-in, takeaway, delivery. Orders, lines (with variants, modifiers, notes), payments (cash/card/UPI/QR/wallet), refunds (with manager override), shifts (open/close + cash reconciliation). Idempotency required.

**Tables** (`api/v1/tables`) — Floor plan, table status, reservations. EXCLUDE constraint on overlapping reservations enforced at DB. Floor layouts stored as JSONB for the drag-and-drop editor.

**Menu** (`api/v1/menu`) — Categories, items, variants (S/M/L), modifiers (add-ons), availability windows. Each item links to one or more recipes.

**Inventory** (`api/v1/inventory`) — Ingredients in base units (ml/g/unit). Recipes deduct on sale (FIFO oldest non-expired batch). Stock movements typed as sale/waste/damage/transfer/adjustment/grn. Suppliers, purchase orders, goods-received-notes.

**Gaming** (`api/v1/gaming`) — Stations (PS5/VR/simulator/projector), sessions (start/pause/stop, billable_minutes math), bookings (overlap-protected by DB constraint), tournaments. Revenue posts as `Revenue — Gaming`.

**Finance** (`api/v1/finance`) — Double-entry journal as source of truth. Chart of accounts. Expenses (optionally linked to OCR extraction). Partners + capital + profit allocation. Assets + depreciation.

**OCR** (`api/v1/ocr`) — Upload receipt/invoice → object storage → worker extracts vendor/date/amount/lines → verification queue → approve creates expense + journal entry. Duplicate detection by hash + (vendor, date, amount).

**Staff** (`api/v1/staff`) — Users, roles (RBAC), attendance (clock in/out), payroll (period totals: base + tips + bonus − deductions).

**Analytics** (`api/v1/analytics`) — KPI dashboard, top/slow items, peak hours, food-vs-gaming, partner balance. Power BI export hook ready (CSV endpoint stub).

**Admin** (`api/v1/admin`) — Audit log read, system info. Restricted to `owner` and `auditor`.

Cross-cutting: **Auth/RBAC**, **Multi-tenancy** (company → branch → terminal), **Audit**, **Idempotency**, **Offline POS sync** (frontend IndexedDB outbox), **Eventing** (in-process bus, Redis-backed in V2).

See per-module READMEs in `docs/modules/` for endpoint lists and TODO ladders.
