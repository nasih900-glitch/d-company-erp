# Isolated build58 failure diagnostics

This branch is an experiment, not a release candidate. Its push-only workflow
checks out immutable application source `6eb61bf097afb7d6e306f3878a64f266adf451a1`
separately from these tools. It cannot sign, deploy, activate an update, access
production credentials, or satisfy a release gate.

The emulator comparison builds one debug APK pair and gives identical bytes to
both version arms. The failed tagged run did not retain its APK bytes; these
are rebuilt experimental bytes. Raw AndroidJUnitRunner invocation differs from
the release UTP driver and must not be described as an equivalent release pass.
Each host runs both SDKs sequentially; the second host reverses their order.
Each SDK executes the same full34-test GamingBoard class once. Both retain the
same assertions, physical tablet
display, CPU count, navigation mode and requested graphics options. The parent
owns and waits for its emulator process, records periodic resource observations,
and never attaches a tracer or uploads cores, environment, AVD data or keys.

The backend experiment runs unchanged tests with an external observer. It does
not change any timeout, request, assertion, transaction or production source.
Function/line-only task observations and read-only database wait observations
are diagnostic evidence; they are not proof of the hosted failure's cause.
