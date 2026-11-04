# 3.1.55 / Android Build 62 candidate

This is an unsigned, unoffered successor to immutable 3.1.54 / Build 61. Room 58 and Alembic 0094 remain unchanged. Application UI/business source files (screens, dial, attendance/pay estimates, POS, Gaming, permissions and billing) are inherited unchanged. Generated release identity and the targeted ProfileInstaller runtime dependency below change compiled artifacts; compiled DEX bytes are not claimed unchanged. This document is a source scope, not a deployment or device acceptance receipt.

## Concrete packaging correction

Build 61's isolated canonical reproduction gate rejected its Play AAB before signing. A retained producer AAB and an explicitly reconstructed hash-equivalent failed AAB establish a secondary DEX record permutation: swapping only two complete secondary DEX records and updating their names/central offsets recreates the recorded failed whole-file SHA256 exactly. That reconstructed archive is evidence qualified as reconstructed, not a downloaded original failed artifact. A subsequent diagnostic rebuild matched all seven original producer files; it does not erase the earlier failure.

AGP 8.7.3's APK and bundle packaging can enumerate secondary DEX inputs differently. Its compiled baseline profiles use the APK DEX names. Build 62 adds a public `SingleArtifact.BUNDLE` transform that uses the same release variant's `SingleArtifact.APK` metadata and exactly one unsigned, unfiltered compiler output. Main DEX must already agree. A unique complete SHA256 bijection maps only `base/dex/classesN.dex` names to the compiler APK names. The original compressed streams, payloads, headers/extras/comments and non-DEX paths are preserved; records are ordered deterministically and only required name lengths/central offsets change.

The transform internally decodes exact profile bytes with official pinned AGP 8.7.3 profgen. Profile 010 names, whole-DEX CRC32 and method counts, plus metadata 002 names/type counts, must match the reference APK. Metadata002 has no checksum. DEX header SHA1/Adler32 checks are separate integrity checks. Signed/transparency archives, unsupported modules or ZIP features, invalid descriptors/CRC/local-central records, duplicate/unsafe paths and ambiguous/missing code fail closed. A partial transform is never published as the final artifact.

Java-only build logic uses the public Gradle Worker API with isolated, strictly locked/verified official tool JARs. It does not change the root AGP plugin version or use internal AGP task mutation. The canonical Play AAB is `android-native/app/build/outputs/bundle/release/app-release.aab`. AGP owns this final public artifact path; it relocates the untransformed input to its signReleaseBundle intermediate. The transform separately retains raw AGP input and an entry-level audit. Both handoff consumers verify the final output SHA256 against that audit, so the historical filename is not assumed to mean raw bytes.

## API35 profile installation

An owned offline API35 emulator returned `UNSUPPORTED_ART_VERSION` with inherited ProfileInstaller 1.3.1, despite valid generated APK profile/code mapping. Build 62 explicitly pins official ProfileInstaller 1.4.1 (the 1.4.x line adds API35+ support), with narrowly updated verified/locked artifacts. No other AndroidX upgrade is intended. Actual newly compiled 62 APK installation, successful profile-install result and profile compilation remain required; predecessor fixture startup does not satisfy that gate.

## Unchanged acceptance boundaries

Producer and independent builder both consume the final canonical BUNDLE. The seven-file comparison remains byte-exact; all seven results are checked before returning a mismatch. Available raw/canonical outputs, metadata, manifests and the entry audit are retained before validation can fail. Protected signing remains dependent on the complete coordinated, image, Android UI, unsigned build and independent reproduction gates, with the original signer and no Gradle execution after key admission.

Build 61/tag3.1.54 and all earlier source freeze maps remain immutable. Build 62 has its own source inventory and overlay. Prior Build 54 runtime/schema and rollback guards remain unchanged. No new migration, staff policy, Sheet operation, live acceptance, deployment or update offer is authorized by this source correction.

## Required release evidence

Focused Java archive/profile/negative controls, actual transform execution, idempotence and preserved compressed-stream proofs are prerequisites. Official bundletool validation, universal and target-device APK generation, generated DEX/profile verification and owned-emulator smoke are also required; structural validate alone is insufficient. Fresh candidate cold builds must match every final handoff byte. Full source/history checks, backend/web/JVM/UI/image gates, signing admission, signed upgrade matrix and guarded production acceptance still apply.

The Redmi Pad2 is unavailable for physical testing. Emulator coverage must state that limitation. No bug-free, crash-free, installed or offered claim is made here.
