# Android precision-refinement design QA

- Source visual truth: `/tmp/codex-remote-attachments/019f6149-4483-7750-9466-b04063d78502/8D369ABB-4F08-47B5-ACA4-2FF03554B80F/1-Pasted-Image-1.jpg`
- Final Gaming screenshot: `/tmp/d-company-erp-ui-audit/refinement/gaming-release-final.png`
- Final combined comparison: `/tmp/d-company-erp-ui-audit/refinement/gaming-reference-comparison-v2.png`
- Gaming before/after: `/tmp/d-company-erp-ui-audit/refinement/gaming-before-after.png`
- POS before/after: `/tmp/d-company-erp-ui-audit/refinement/pos-before-after.png`
- Portrait Gaming: `/tmp/d-company-erp-ui-audit/refinement/portrait-gaming-final.png`
- Portrait Menu: `/tmp/d-company-erp-ui-audit/refinement/portrait-menu-final-signed.png`
- Android viewport: 1280 x 800 dp at density 2.0 on API 35; portrait QA at 800 x 1280 dp.
- Density normalization: the source and final Gaming artifacts were normalized to 800 px high and placed in one comparison input. The source is 3:2 while the target tablet is 16:10, so neither artifact was cropped or stretched.
- State: authenticated owner account; production-backed dashboard with five stopped/unbilled sessions, three available stations and an open POS shift. No production record was changed for visual QA.

## Final visual assessment

The implemented screen preserves the reference hierarchy without copying unsupported decoration: restrained navigation, contextual header, factual metrics, a single payment queue, counted type filters and a dense four-column station grid. It uses current production counts instead of the illustrative values in the reference. Semantic state uses gold only for primary/payment attention, green for available/active, red for destructive or rejected operations and blue for informational states.

The previous density, button-crowding and undersized-brand findings are closed. Cards expose two operational rows at the target viewport, `Void` and `Send to POS` remain single-line with touch-safe targets, and the brand lock-up is legible. Portrait QA confirms the metrics reflow into complete rows and the station grid remains usable without clipped controls.

## Operational-state evidence

The live account did not have an active session, so no fake production session was created for screenshots. Presentation policy is instead regression-tested in `GamingStationPresentationTest` for active, overtime, paused, start failure, stop rejection, payment due, POS-send pending/rejected, zero-value cancellation, disabled-station lifecycle precedence and customer identity. The UI now:

- freezes paused presentation as `Timer paused` instead of fabricating elapsed time without an authoritative pause timestamp;
- keeps billable session actions visible even if a station is subsequently disabled;
- shows the actual stop/POS rejection reason and a clear retry action;
- retains customer name/phone on active and payment-due cards and in the payment queue.

## Intentional deviations from the example

- The example uses illustrative hardware cut-outs. The app uses existing licensed brand artwork and Material icons because the product has no licensed station-image set. This reduces load cost and avoids fake assets.
- Unsupported example actions and metrics such as transfer, maintenance, time extension, fabricated current amount and illustrative revenue are omitted until backed by authoritative product logic.
- Native Android typography is retained for consistent rendering, accessibility and platform familiarity.

## Screen-set evidence

- Core modules: `/tmp/d-company-erp-ui-audit/refinement/pos-final-settled.png`, `gaming-release-final.png`, `tables-final.png`, `customers-final.png`, `kitchen-final.png`, `shift.png`, `menu.png`, `staff.png`, `stock.png`, `reports.png`, `analytics.png`, `settings.png`.
- Secondary modules: `/tmp/d-company-erp-ui-audit/refinement/secondary-modules-final.png`.
- Protected modules: `/tmp/d-company-erp-ui-audit/refinement/audit-final.png`, `/tmp/d-company-erp-ui-audit/refinement/access-final.png`.
- Input proof: `/tmp/d-company-erp-ui-audit/refinement/customer-dialog-typed.png`.
- Signed clean-install login: `/tmp/d-company-erp-ui-audit/refinement/clean-install-login-final.png`.

## Final result

pass — no open P0/P1/P2 visual defect in the reviewed Android tablet states. Physical-device acceptance remains a separate release-evidence boundary.
