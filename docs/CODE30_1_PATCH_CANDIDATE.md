# Code30.1 targeted patch candidate

Code30.1 keeps the existing Code30 application and includes two Android fixes found during the isolated September 14 gaming and billing trial. Its technical identity is v3.1.26, installation build 34, Room schema 47 and backend migration head 0072. Earlier tags and signed APK bytes remain immutable. The higher integer build allows Android to install this patch over Code30 build 33.

A Stop explicitly rejected because its captured time is in the future can now be retried after the clock is corrected. Recovery keeps the same session and retry identity and preserves the original rejected timestamp. Stops with uncertain network outcomes retain their original captured time. No other rejection or completed session is rewritten.

Gaming attention now includes rejected Start and Stop actions. Review selects the affected station without automatically retrying an action. Rejected Send remains represented once in payment attention.

The frozen functional patch passed 1,066 JVM tests and 53 Room/Compose tests on the isolated API-35 emulator. Nine complete synthetic gaming bills reconciled to INR 599, including actual 15-minute expiry, paid extension, paused-time exclusion, an offline Stop across restart, a lost payment acknowledgement followed by recovery, hourly rounding, an additional controller, and all five pricing-card modes. The trial shift closed with zero cash variance; all nine stations were available. Backend focused regression tests (147) and Web tests (502), typecheck, lint and build passed. Full tagged CI, protected signing, signed upgrade verification and release staging remain separate gates and must be recorded from actual results.

The physical Redmi Pad 2 is unavailable. Emulator evidence does not establish its keyboard, alarm, battery or printer behavior, or constitute a whole-day physical shop trial. Rewards and WhatsApp remain inactive. Production Android offer/minimum-version policy and the pause feature flag are unchanged by this patch. This document does not claim deployment, an offered update or physical installation.

Trial and customer cleanup is tracked in the workspace audit handover. Production legacy financial history, staff, prices and settings must be preserved. Never restore a trial archive into an active system without applying the verified cleanup again.
