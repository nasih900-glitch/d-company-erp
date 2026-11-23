# Module: STAFF

See `docs/MODULES.md` for the module orientation.

## Endpoints

Staff-directory endpoints are in `backend/app/api/v1/staff/router.py`; individual
attendance and draft terms are in `backend/app/api/v1/staff/work_time.py`.

## Hours and pay estimates

`GET /staff/time-summary` returns individual recorded time. Only an Owner with
`staff.payroll.write` receives salary terms and the additive `pay_estimate`
projection. Ordinary staff receive neither salary nor estimate data. A
branch-scoped Owner sees an explicitly labelled selected-branch estimate;
that is not a complete company payroll total.

The estimate uses **paid presence, including breaks**, under the existing
monthly amount and daily-presence schedule. Each completed visit belongs to
its company-local **clock-in workday** and arrival month. An overnight visit
stays together; it is not charged again in the departure month. Multiple
disjoint visits starting on the same workday share one regular-time cap.
Recorded calendar-month attendance remains a separate projection, so its
month-edge totals can differ from arrival-workday pay-estimate hours.

The denominator is the actual selected month's scheduled days multiplied by
planned paid-presence time. Base time is capped per scheduled arrival workday;
extra time never offsets another workday's shortage. Base amount and optional
1.25×/1.5× overtime previews use exact integer timestamp/money arithmetic and
round once per monthly component. Displayed hourly rates are references only;
they are never multiplied back into pay. Overtime defaults off and resets on
person, month, authorization or verified-snapshot changes.

Open visits are provisional and excluded. Missing/full-month-inapplicable
terms, unconfirmed breaks, invalid/future/overlapping entries and unusually
long visits over 24 elapsed hours require review before an estimate is shown.
No attendance is inferred. Estimates outside the exact JSON money range are
withheld rather than approximated. Older servers without this field display
an unavailable message instead of a fabricated amount.

These are Owner comparison estimates, not statutory wage determinations or
approved payroll. Top-level `payable_minor` and `overtime_pay_minor` remain
null. Previewing or selecting overtime writes no policy, payment, expense,
ledger entry, attendance change or Google Sheets data.
