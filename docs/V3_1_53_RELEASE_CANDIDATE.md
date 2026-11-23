# v3.1.53 / Android 60 release candidate

This unsigned successor adds an Owner-only breakdown of each person's recorded
hours, estimated base pay and separate optional overtime previews. Its immutable
base is signed, unoffered Build 59 / v3.1.52 at
`226f1dc3e29d6a521153338cda9fc67f31b2087d`. Preserve that source, tag, signed
artifacts and all prior evidence. Never relabel its APK as Build 60.

Android advances to 60 / 3.1.53. Room remains 58; Alembic remains 0094. No new
database migration, payment posting, ledger mutation or Sheets activation is
introduced. Existing liquid-glass presentation, dial mechanics, attendance
idempotency and authorization remain inherited behavior requiring regression.

## Hours and pay review

The existing person and month selectors open one staff member's history. A new
additive `pay_estimate` projection is returned only to an Owner with payroll
permission. Ordinary staff retain their own attendance access without salary
disclosure. A selected-branch sign-in is explicitly labelled as branch-scoped;
it is not presented as a company-wide payroll total.

The monthly reference is divided by the selected month's actual scheduled days
and planned daily **paid presence**, including breaks. Closed visits are dated
by company-local arrival; an overnight visit stays together in its arrival
month, without being charged again in its departure month. Multiple disjoint
visits starting on the same workday share one daily base-time cap. Extra hours
are kept separate so they cannot hide another workday's shortage. Recorded
calendar-month attendance and arrival-workday estimate totals remain explicitly
different projections at month boundaries.

The UI defaults to no overtime. Optional 1.25× and 1.5× comparisons show their
amount separately and reset when the person, month, authority or verified
snapshot changes. Calculations use exact timestamp/money integers and round
once per monthly component; the displayed rounded hourly rate is not used to
recompute earnings. Web money formatting preserves each supported minor unit.

Open visits remain provisional and excluded. Missing or midmonth-changing
salary terms, unverified breaks, future/invalid/overlapping entries, continuous
visits over 24 elapsed hours and unsupported exact money ranges require review.
No attendance or salary policy is fabricated. Real staff policies must be
configured through the normal Owner-authorized interface; current live setup
and historic actual hours are not proven by synthetic tests.

These are comparison estimates, not statutory wage determinations or approved
payroll. The existing `payable_minor` and `overtime_pay_minor` remain null. An
overtime preview records no payment, expense, deduction, policy or Sheet data.

## Acceptance boundary

Follow [BUILD60_GUARDED_DEPLOYMENT.md](BUILD60_GUARDED_DEPLOYMENT.md). Focused
source tests and independent review do not establish a released update.
Fresh complete regression, exact-source CI, original-certificate signing,
signed-upgrade verification, guarded production acceptance, byte-verified
staging and an Owner-authorized optional offer remain separate gates. The
physical Redmi Pad 2 is unavailable; emulator proof is not hardware proof.
Build 60 is not yet tagged, signed, deployed, staged or offered.
