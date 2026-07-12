# Opsora Public ERP Product Plan

Working product name: `Opsora`

Tagline: `The business operating system for hospitality, retail, and service teams.`

Important: `Opsora` is the working product name. Before public launch, complete a trademark search, domain search, App Store name check, and company/legal review.

## Why This Must Be Separate From D Company

`D Company` is one real venue. A public ERP must not look like software made only for one cafe. It needs generic branding, self-service onboarding, clear pricing, configurable modules, and a privacy/compliance story that applies to many businesses.

## Product Positioning

Opsora is a configurable ERP for cafes, restaurants, lounges, game zones, retail counters, and service businesses. It should let any business create an account, configure its own branches, products, services, taxes, staff, roles, reports, and hardware integrations.

## Core Modules

- Company onboarding: business profile, country, tax region, currency, branches, opening hours.
- POS and billing: cart, discounts, GST/tax split, receipts, refunds, table orders, takeaway, delivery.
- Catalog: food, drinks, desserts, services, sessions, bundles, variants, add-ons, price rules.
- Sessions: PS5, VR, simulator, streaming rooms, billiards, karaoke, coworking desks, or any timed service.
- Inventory: ingredients, finished goods, stock movement, purchase orders, suppliers, wastage, reorder alerts.
- Staff and permissions: owner, manager, cashier, kitchen, inventory, accountant, auditor, custom roles.
- Customers and CRM: memberships, loyalty, credits, purchase history, communication preferences.
- Finance: daily/weekly/monthly/quarterly/half-year/yearly P&L, expenses, cash drawer, tax summaries.
- Reports and analytics: revenue, margin, top items, staff performance, stock usage, session utilization.
- Audit and security: login logs, create/update/delete tracking, protected audit unlock, exportable evidence.
- Hardware: receipt printers, barcode scanners, cash drawer, payment terminal SDKs, kitchen display screens.
- Offline mode: local queue, offline POS, conflict resolution, background sync, device status.
- AI automation: sales summaries, anomaly alerts, low-stock forecasting, purchase suggestions, report emails.
- Integrations: email, WhatsApp/SMS, accounting exports, payment gateways, printer SDKs, OCR receipt scanning.

## App Store Strategy

Opsora can be public only if it is not invitation-only for one business. The app should support:

- Public signup or a clearly available demo mode.
- Generic onboarding for any eligible business.
- Clear pricing and subscription terms.
- Privacy labels that match the real data collection.
- No D Company-specific hardcoded content.
- No hidden private-only features that reviewers cannot inspect.

If Opsora is sold only to selected businesses, distribute it through Apple Business Manager as a custom app instead of the public App Store.

## Architecture Direction

- Use multi-tenant backend data with `organization_id` on every tenant-owned record.
- No hardcoded `D Company`, Nilambur, staff names, owner accounts, passwords, or business-specific defaults.
- Create tenant templates for cafe, restaurant, gaming lounge, retail, and service business.
- Separate public marketing website from authenticated ERP app.
- Add a tenant-safe demo company for App Review and sales demos.
- Store secrets in server environment variables only, never in GitHub or app bundles.
- Keep public mobile app and staff/admin web app sharing APIs, but not sharing customer-specific branding.

## Build Phases

### Phase 1: Public Foundation

- New app name, icon, bundle ID, privacy/support pages, and App Store metadata.
- Public landing/signup/demo flow.
- Tenant creation and branch setup.
- Generic sample data.

### Phase 2: Configurable ERP Core

- Configurable catalog, sessions, pricing, taxes, staff roles, and receipts.
- POS, inventory, reports, audit log, customers, and settings in one organized navigation model.
- Owner-level setup screens for taxes, prices, modules, and permissions.

### Phase 3: Offline and Hardware

- Offline POS database and sync queue.
- Receipt printer integration.
- Barcode scanner support.
- Payment terminal integration.
- Kitchen display and customer display modes.

### Phase 4: Automation

- Scheduled P&L emails.
- Daily shift alerts.
- Low stock alerts.
- AI report summaries.
- GST/tax export checks.

### Phase 5: Marketplace

- Integrations marketplace.
- Industry templates.
- Import/export tools.
- Multi-location enterprise controls.

## Brutally Honest Boundary

Opsora cannot honestly be called a complete public ERP until it has public onboarding, tenant isolation, configurable modules, production-grade billing, privacy/legal pages, support processes, and clean demo data. The current D Company codebase is a strong starting point for one business, but public SaaS requires productization, not just renaming.
