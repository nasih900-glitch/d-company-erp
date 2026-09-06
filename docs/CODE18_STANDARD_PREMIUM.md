# Code 18 standard-premium Android direction

Status: selected visual direction, carried unchanged into Android `3.1.12` /
code `23` after the signed code-21 predecessor.

## Outcome

Code 18 keeps the verified Code 17 navigation, operational density, touch targets,
offline behaviour and business workflows. It changes only the shared Android
presentation system so the app reads as a calm, premium business ERP rather than
an entertainment or gaming-themed product.

Gaming remains an operational module. It is not the global visual identity.

## Visual reference

The structural reference is the verified Code 17 tablet layout recorded in the
release audit. Code 18 preserves that layout and hierarchy while applying the
tokens below; the source tree does not depend on a private local evidence path.

## Core palette

| Role | Value |
|---|---|
| App background | `#08131B` |
| Primary surface | `#0F1D26` |
| Elevated surface | `#162832` |
| Brand accent | `#C6A15B` |
| Primary text | `#F4F6F7` |
| Secondary text | `#9BA8B0` |

Additional surface, border, disabled and semantic tokens must be derived in the
central theme and retain WCAG AA contrast for normal text. Success, warning,
error and information colours stay independent of the brand accent and always
appear with text or icon semantics.

## Rules

- No neon, glow, glassmorphism, decorative gradients or entertainment artwork.
- Do not use controllers, game imagery or esports styling as global decoration.
- Use muted gold only for the primary action, selected navigation, focus and
  restrained brand identity.
- Preserve a neutral sans-serif hierarchy and tabular numerals for money,
  timers, quantities and reports.
- Keep minimum 48 dp operational touch targets.
- Do not change permissions, navigation routes, API calls, financial logic,
  offline queues or remote-assistance authority.
- Do not advertise or activate the Code 23 build through the server
  update channel until
  the signed artifact and upgrade proof have passed.

## Acceptance

- Login, navigation shell and shared cards/fields/actions use the same tokens.
- No page introduces an independent colour language.
- Focus, pressed, disabled, error, loading and offline states remain explicit.
- Tablet landscape has no clipping, overflow or reduced tap targets.
- Android unit, connected UI, lint and signed build gates pass.
- A signed Code 21 to Code 23 replacement install preserves package identity and
  retained app state on the emulator.
- Physical Redmi Pad 2 / HyperOS remains a separate final acceptance gate.
