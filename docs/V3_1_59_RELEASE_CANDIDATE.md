# Build 66 / 3.1.59 candidate

Build 66 adds weekly and monthly staff attendance cards, shared clock-in and
clock-out with the other staff member's own password, and an online cashier
handover on a shared tablet. Staff can see attendance days and recorded clock-in
and clock-out times in scope. The API reserves schedule, calculated hours and
pay details for an authorized Owner. Clocking a staff member and handing over
the cashier role remain separate actions.

A verified handover keeps the same open cash shift, existing sales and running
games. The incoming cashier signs in before taking over. An authorized Owner
can return a linked handover to the previous cashier from the web app. Pending
work or an unverified handover can hold the switch until it is reconciled.

## Local verification

On an isolated debug emulator with synthetic staff, a handover without a cash
count kept the shift, cash records, game deadline and two alarms through a
process restart. The incoming cashier completed a cash sale, and the former
cashier clocked out with their own password while the incoming cashier remained
signed in. A normal Owner browser session completed a linked return. A second
handover completed during a live countdown.

An intermittent startup session-verification error recovered with Retry.
Four subsequent retained-session process restarts on the diagnostic build
succeeded without Retry and recorded zero app crashes, app ANRs or restore
diagnostic errors. The cause remains unverified. Repeat the scenario with the
signed upgrade.

The guarded PostgreSQL 16 rehearsal accepted the synthetic 0094→0095
transition, a separately restored backup, unchanged existing table and sequence
digests, and an empty new handover ledger. A separate fresh-source restore
comparison was refused when it differed beyond the reviewed catalogue
renderings. Production-source backup rehearsal remains a gate before
maintenance.

## Release status

This source candidate awaits canonical CI, signing, signed upgrade and
deployment acceptance. The emulator and isolated database checks establish
local behavior. Physical Redmi acceptance remains outstanding. Coordinate the
optional offer with the staff privacy change because the Build 65 staff clock
screen uses the summary endpoint that Build 66 reserves for Owners. The
deployment must keep the existing update floor and offer policy until the
separate rollout decision. See [the guarded deployment](BUILD66_GUARDED_DEPLOYMENT.md).
