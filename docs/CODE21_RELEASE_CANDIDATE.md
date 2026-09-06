# D Company ERP 3.1.10 (code 21) release candidate

Code 21 is the corrected immutable release identity after Code 20 failed safely
inside its isolated signing job. Codes 18, 19, and 20 produced no authorised
signed APK and their tags must never be moved or reused.

Code 21 preserves the tested standard-premium application, backend, database,
and web behaviour. Its release-only correction resolves Android SDK tooling
from the hosted runner's explicit trusted SDK root in both pre-sign and
post-sign verification. It never relies on PATH lookup, fails if the executable
is absent, and captures permission output before testing it so an analyzer
failure cannot be mistaken for a safe permission result.

Only a completely green `v3.1.10` workflow may produce the signed direct APK and
matching `release-manifest.json` used for staging. Production backend/web must
be deployed and smoke-tested before the staged row is activated. Keep the
compatibility minimum at code `8`, update code `14` in place, and do not clear
tablet data or an offline outbox.

The isolated signing job remains gated by the `android-release-signing` GitHub
Environment, which permits only `v*` tags and requires explicit repository-owner
approval before signing secrets become available.
