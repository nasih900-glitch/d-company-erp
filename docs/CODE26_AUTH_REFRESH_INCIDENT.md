# Code 26 long-shift authentication correction

## Reproduced failure

The isolated eight-real-hour trial on source
`6977d3d54f8d002fedbf7dd7fe57da0332bb4f7f` lost its Android login about three
hours and 39 minutes after the day started. Ordinary ERP and remote-support
requests received access-token expiry concurrently. They submitted the same
single-use refresh token twice; the server accepted one rotation and rejected
the second about 19 milliseconds later as reuse. Revoking that token family
was the correct server security response.

The next Gaming navigation exposed the sign-in screen before starting another
session. Six previously completed synthetic sessions, orders and payments
remained reconciled. Their native payment/session outboxes contained no
pending business actions. Mutation workers were stopped and failed evidence
was preserved. No production business records were involved or cleaned up.

## Narrow change

`ApiClient.init()` now constructs one refresh coordinator after constructing
its dedicated refresh Retrofit client. A single published session pair holds
the matching TokenStore and coordinator. Both ordinary and remote client
builders snapshot that pair, and each AuthInterceptor captures it. The
refresh closure also captures its own Retrofit instance.

This does not merge the ordinary and remote network clients, change refresh
lifetimes, weaken server reuse revocation, change billing or finance, or
change package/version identity. Existing TokenStore lease checks still make
logout and explicit new login win over an old in-flight request. Remote
enrollment nonce replay is still prohibited; remote pricing authority is
still stripped; the refresh dispatcher remains separate.

`ApiClient.kt` is the only newly allowed production path in the Code 26
regression freeze. All 491 Code 25 test/support files remain mandatory, with
only the previously documented audit-reader locator normalization.

## Focused verification

- The new Python source-wiring regression failed on the original wiring and
  passes on the corrected wiring.
- A new Android instrumentation class drives two actual AuthInterceptor
  instances with controlled simultaneous 401 responses. It checks one shared
  refresh, the winner's token on both retries, and safe reuse for a delayed
  same-lineage 401. A separate case rejects borrowing credentials from a newer
  explicit login.
- Those two checks plus the existing SessionRefreshCoordinatorTest and
  TokenStoreSecurityTest passed together: **15 instrumentation tests** on a
  fresh API-35 emulator. The app used a loopback-invalid test endpoint and
  both IPv4 and IPv6 outbound traffic for its UID were blocked before the
  first instrumentation launch. This is synthetic-interceptor evidence, not
  a physical-tablet or live-server exchange claim.
- Full Android JVM tests and debug app/test assembly passed. Independent
  read-only verifier and security reviews found no blocking defect in this
  diff. Ordinary/remote client wiring is additionally guarded structurally;
  a new long-running live-client test remains required.

## Remaining release evidence

The original interrupted trial is not repaired by relogin or added hours.
The replacement must use a fresh isolated shop, shift, app installation and
browser profile, with one frozen source/harness identity. It must fail on an
unexpected token-family revocation or loss of recent authenticated Android
requests, rather than treating server readiness alone as login health.

Fresh full CI, physical workflow revalidation, the uninterrupted day trial,
final source audit, protected signing, same-key upgrade/data preservation,
matching production deployment and owner-visible staging remain separate
gates. This correction alone does not authorize a tablet update offer.
