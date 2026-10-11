#!/usr/bin/env python3
"""Verify one exact build-44 -> build-45 unresolved-pending server transition.

This proof preserves the two reported pending counters. It does not inspect a
tablet queue, classify its work, retire an installation, or authorize an app
offer. Run it only from the installer's root-private immutable candidate tree.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import types


class CompatibilityError(ValueError):
    pass


BUILD41_REVISION = "099daa28ea5b9cb3c4b167a66c82302896ad4acc"
PRIOR_REVISION = "d915a66c9dee0d22c1a7018e2e79bc8ca9e0d7ab"
PRIOR_VERSION = "3.1.36"
PRIOR_DATABASE_HEAD = "0085"
CANDIDATE_VERSION = "3.1.37"
CANDIDATE_DATABASE_HEAD = "0086"
PRIOR_SOURCE_ARCHIVE_SHA256 = (
    "019ee6ad421d302ff6304dfe0bd345d3b2325d251bf2bbef94662e81887f2939"
)
PRIOR_BACKEND_MANIFEST_SHA256 = (
    "618d33547fb3c12bb6c48b8291d7d4e2eeaca511690f6b5bedd8e43ca7e09201"
)
# Filled from the stable reviewed closure after the installer and query bytes
# are final. The verifier itself and its tests are deliberately outside this
# inventory so its constants cannot make the policy self-referential.
PRIOR_SOURCE_SHA256 = "57ed23384c64cfe70f5475378ca25da819899f4a50c18f651dbd6de7f73e2b9a"
CANDIDATE_SOURCE_SHA256 = "bed9500f9321f881104080f8104d7d4e752a87851152dcaccd830c7fbce85392"
CANDIDATE_BACKEND_MANIFEST_SHA256 = (
    "8deacbab0ad0daee1809070e10906b29e36b841e05b5b653daa48e05a19e86a0"
)
BUILD41_ANDROID_SHA256 = "58da4fd1d608a06a779a661e3b3a94f7e0ed5025f02e77f71d777a035d36f4c1"
BUILD44_ANDROID_SHA256 = "cb18f04b26d25cddbfeaa9911e3a5c4456cd0fa493e41e28162c7b9e46263cf5"
CANDIDATE_ANDROID_SHA256 = "50c9bef8b25e9fe393e73745f0814f047ad74a200ea0dc05eaf4b50ba7dfc039"

HISTORICAL_VERIFIER = "infra/scripts/verify-code30-2-post-cleanup-state.py"
MAX_DOCUMENT_BYTES = 1024 * 1024
EXPECTED_INSTALLATION_ROWS = 10
EXPECTED_PENDING = 2
EXPECTED_NONZERO = 2
EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256 = (
    "4a954e216ec9851e237758f8806affc5fbe0c4c8bd389e8e765c40d434eafc55"
)
EXPECTED_NONZERO_FLEET_SHA256 = (
    "5a1100a5695e997be523eb7a948d06db0fd79fed1a4f3a5f48c3286c86ea5128"
)
EXPECTED_FLEET_ROWS = {
    "1461683b87a9509241bbc1144486bde38d63f110e8b0062e58db26c43e1ce944": (
        "historical_cleanup_evidence",
        "3.1.29",
        37,
    ),
    "6c1ba1c1c5a9391c2db9fb4065294ebec9f442c4b12f9a71da57afbcfd98a56a": (
        "unknown_preserved_work",
        "3.1.33",
        41,
    ),
}

# Full backend plus executable build/startup/config inputs and the complete
# Android production source/schema closure. New query bytes are bound here;
# this verifier is excluded as the explicit non-self-referential freeze edge.
PROTECTED_SCOPES = (
    "backend",
    "android-native/app/src/main",
    "android-native/app/schemas",
    "android-native/app/build.gradle.kts",
    "android-native/app/gradle.lockfile",
    "android-native/build.gradle.kts",
    "android-native/settings.gradle.kts",
    "android-native/gradle.properties",
    "android-native/gradle/verification-metadata.xml",
    "infra/docker",
    "infra/caddy",
    "infra/nginx",
    ".dockerignore",
    ".env.production.example",
    "docker-compose.prod.yml",
    "infra/scripts/install-on-vm.sh",
    "infra/scripts/prepare-production-env.sh",
    "infra/scripts/generate-secrets.sh",
    "infra/scripts/validate-production-env.sh",
    "infra/scripts/verify-code30-2-post-cleanup-state.py",
    "infra/scripts/verify-code30-2-post-cleanup-state.sql",
    "infra/scripts/verify-build45-pending-state.sql",
    "ops/runtime_release_parity.py",
)
ANDROID_SCOPES = (
    "android-native/app/src/main",
    "android-native/app/schemas",
    "android-native/app/build.gradle.kts",
    "android-native/app/gradle.lockfile",
    "android-native/build.gradle.kts",
    "android-native/settings.gradle.kts",
    "android-native/gradle.properties",
    "android-native/gradle/verification-metadata.xml",
)

# Exact before/after content hashes, including explicit additions/deletions.
# These maps are generated only after the bounded source delta is stable.
REVIEWED_CHANGES: dict[str, tuple[str | None, str | None]] = {
    ".env.production.example": (
        "0ae533abb6d8e815894ce1faad9d7931333f0c53f857495e0690ae12765918b2",
        "e7c0181f0c08afb8b51e720f893517b583e2c4485da33e68950f475bee453225",
    ),
    "android-native/app/build.gradle.kts": (
        "b40cf6a658ec06a2cbd5a6c38e201edb2f8d2814486747e018ef8620ceec6612",
        "ab45fd0caf781298fe2bf143c12913c3fc1eb254d8f39b6a8a41c1e505fcabf7",
    ),
    "backend/alembic/versions/0086_google_sheets_daily_collections.py": (
        None,
        "9c9705b0e0f7d5b5d46071b959fffe2b3dc30433287f8c5cf2a553291ff54976",
    ),
    "backend/app/__init__.py": (
        "2f617d2094a97d4a631689803a90c604d904fdafbb2710401793bb4495af1b8e",
        "2c72f9e68ac9e6c8c08e2c42fd7cd3ddd0875d091d0c5b478b1acdef95e88d64",
    ),
    "backend/app/api/v1/pos/router.py": (
        "834ffb264be9c95167be9f0a034f45f305de69d6596a6da3bf5151c1b15b20ab",
        "8d5e2932bd8ec9973d42257de243467757c40767a5d4e27a1925797b4990f907",
    ),
    "backend/app/api/v1/settings/google_sheets.py": (
        "06f1db71cad62abd0e6c0431f0856faf228948cba56639b43207f84cf95d8fed",
        "b6b1329a6581249f89c74ae76df2f2f2732bbcc15d0fb019777c8062e9df4149",
    ),
    "backend/app/models/tenant.py": (
        "38402bbb35fe340e4c208719a39428ee47d188e803574b6527fddfd93416653f",
        "ba20d20a545584f1354bce8465586b6a9bfb92056b7bd2e155a704d238988ffb",
    ),
    "backend/app/services/auth/retention.py": (
        None,
        "54ddb7a7658633c52ce5beb9d7c26297ab6570f38ba1fe0e54a901d0ccbfb1b2",
    ),
    "backend/app/services/integrations/google_sheets_collection_summary.py": (
        None,
        "c7c0d98795453b0b31e4eec94073ab9784d2c646d254553ffaf38a02e48da521",
    ),
    "backend/app/services/integrations/google_sheets_collections.py": (
        None,
        "ef5149007ce434bbba36dd9694a0a11e0e3ea8f9fcc7d7245b14dcdc553f3a46",
    ),
    "backend/app/services/integrations/google_sheets_mirror.py": (
        "f8dec72035821b7f2fe1628f921f43112687a44eca55f0b4fff0ca0f9bb02f3d",
        "6eb49a28eb55371475db48b905ce5397cf2aba29e810c950339f7003599daaef",
    ),
    "backend/app/services/integrations/google_sheets_runtime.py": (
        "ec46a14d7f9751e8f1108e4ac068bb6e50178f3f72301950e43ffddd707c7ef8",
        "7637dc3eb4d505ab606dad40e299ab9713b49a6399cb8917a9b683fa53a751d5",
    ),
    "backend/app/workers/support_attachments.py": (
        "61b953564236a8626761cb70a831945c59ba5da13d4cfcc572c907c1a722b92f",
        "387c8ee71b7afba0ef78981a5e144231f95321e7b9972bc504c7484f0ad3c566",
    ),
    "backend/pyproject.toml": (
        "812c0509f3a5c6814b2292244d11791999dfbd283386cc96bfe1fc74d94fdccd",
        "59cd0704990c4d89832fb2578756bcc9304cd4355f43b0c9a23406a876addc54",
    ),
    "backend/requirements-ci.lock": (
        "4cb457d7f8910a8f4c594c6185c97bc7db59355d44b33d56fddbd510b35fe89d",
        "f221f5f1ccf3fcc654699b0f7f14fef563403676c125cb0f35063fab3ef90ccd",
    ),
    "backend/requirements.lock": (
        "a797cf23e682ad5af7cd5ce7a280be5eee67ba967cc92f905d8b91a9913f4fe5",
        "b09c0fbb7d7e21490baeeb9c97a76fa92d4579a2e96a8b2a64f04761cffcdd7c",
    ),
    "backend/requirements.txt": (
        "ca3949448edd2e018141675d38a154a89ecb74f6e80f24e805c57bf35f00476d",
        "7fb347e8806e2f320f11f20921160af817ffbf1360fe25f745461d7607df64e1",
    ),
    "backend/tests/integration/test_auth_refresh_retention.py": (
        None,
        "a29b93c4b638621f3b5c9fdb74564233a8106e6689a52220c0fbd5eec8a02dda",
    ),
    "backend/tests/integration/test_gaming_cleanup_migration_0079.py": (
        "dfa2a503d22ace6257f9bbbcca5acbec2d9ab4f2d06aa9b851969bded60d9d73",
        "2b788f674218e689f7c81ecac6f550843f7ccfd9ebc7bdde9bdd80bbf698ab92",
    ),
    "backend/tests/integration/test_google_sheets_collections.py": (
        None,
        "5be1dac362c8b0823630062c3350b3baaa4be1959fc9d839ccc273cf7eb9d065",
    ),
    "backend/tests/integration/test_google_sheets_daily_collection_settings.py": (
        None,
        "0f2f7fed9b1b6ba1dfddd809bd5f08d7f3b823fed9a37572ed83ea666c32ba08",
    ),
    "backend/tests/integration/test_google_sheets_daily_collection_summary.py": (
        None,
        "e62dc08bd493492b0e3d947c5affe3d8a1e7e6a0a04a12c9bca3f69b903f9249",
    ),
    "backend/tests/integration/test_pos_tenant_lock_scope.py": (
        None,
        "c9475a3d151459a13290d335083560718204d4ec4cc31c5e98dc4ececedbc44c",
    ),
    "backend/tests/unit/test_google_sheets_mirror.py": (
        "2eb847388cf602615c601b4dda1bbe7bf92ceb90fd96e183fc088f10c7fcddab",
        "8249ce81e4073b0838e7be05a93ed9710bdef93a759301cbec93332c495a8c77",
    ),
    "backend/tests/unit/test_release_contracts.py": (
        "9f79452c5892e32310b0d3390c32557cec71d372048fc24bf61546498681da10",
        "5db99562b3084de52931017cd2b947b0dfe4949966f1668fc770e2d1ec2b7df4",
    ),
    "backend/tests/unit/test_remote_assistance_contract.py": (
        "ffd60daaf5e9cdd0bacf666cbd93c671a7380fefcf83c092dd686ae416d8d3ba",
        "3910a435cd581e89f2a9e2b9f3540e63bacb598f236fc57dbe67cbfbe2019073",
    ),
    "backend/tests/unit/test_support_retention_auth_worker.py": (
        None,
        "0378985d258d92d4a8143f7d4189b537bfa5d1ffbb7964eb9bab855b1e0c7303",
    ),
    "docker-compose.prod.yml": (
        "70dfd8854ec352dd5730a290794a4dcb1d3af69eb27dc865b7e1a59e3743d601",
        "325efdc85c95e7409e2f23d4ae565be0993fa575a582e694144948d363e42ee6",
    ),
    "infra/docker/backend.Dockerfile": (
        "a293cc747ea6b600465fd4d9207e6a6ecf901456b308e0297f051ec8dccca490",
        "56c28b83ce357924919b58413a6569fe95ed34dfb5dd2584ca158319e0977561",
    ),
    "infra/docker/caddy-build/go.mod": (
        "acc5e1635e81939ec159d9f55edeb4c9fc680643f8ce8c184dc98092ac25f2ce",
        "039f8288d60e807cec75b0e9e24674befd8a07ffa1a1b36ea40fcb263a5a350e",
    ),
    "infra/docker/caddy-build/go.sum": (
        "efb54e7849e7d606b739962d897e1e38bb6d3ceb5f780a6a032fd480c4c52570",
        "a99b9d50e840864e9a746ba7d1c8afb66da4d96cbd024d18e3dabd795eb05083",
    ),
    "infra/docker/caddy.Dockerfile": (
        "5fe8c0adce4b2f69404fcf9462c58383e7f9015528be08fe5cd465da3ed4cde8",
        "d9aa16cfa9ec0da96b31f15481b8d4cf50a4caf2e8048f8b300f75fc4c2659f3",
    ),
    "infra/docker/frontend.Dockerfile": (
        "03a58d90e273e684d26793c23adce116ef9cd256f9b948cd6b023a5de199f0e6",
        "2bbabcb5d448b73b8a71a619ca8ab53b3eb5cdc2421725024e916bd04072cebf",
    ),
    "infra/docker/postgres.Dockerfile": (
        "d7f13eb61d042f9227af43f7e446b5f5f7b9f5160a59ff21e13f62725d5d971e",
        "b7cc0f0a33b0292a746e57b79760c9534ac64f0aef4b34e99d13d18f8278aa8d",
    ),
    "infra/docker/redis.Dockerfile": (
        "58889cfedb7886bc873e41c490ab605914cc7e2a0d25c0aa8b6ff393c7a9aa58",
        "e0286e4955b981e8155a4a188b8b4261bf8b5fa2ce477b835f41768303490945",
    ),
    "infra/scripts/install-on-vm.sh": (
        "35928a4a081be5220e813cb1b293c1ebfe174844774f91367ee07b17e6de821b",
        "b0cdc19b6e8dde2c52c08e9f78e286a0b13db73e0ee10973b75fa9ab97d49ffd",
    ),
    "infra/scripts/verify-build45-pending-state.sql": (
        None,
        "18571143f9355d75702d57a3c5616a9feffc6288c42d12d9969201225615dfda",
    ),
}
REVIEWED_ANDROID_41_TO_44: dict[str, tuple[str | None, str | None]] = {
    "android-native/app/build.gradle.kts": (
        "6ae336925ebfbe7321f717dbe2eff8ebeef6c5b476ad5d3af48efa6d5388baad",
        "b40cf6a658ec06a2cbd5a6c38e201edb2f8d2814486747e018ef8620ceec6612",
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/ShiftDao.kt": (
        "e766cf3ecf76d75b917fd6ee7bedc8432a067de8eb537d1574af9f48423ab143",
        "58e49a729f5cff87f2f25facc451ef0586aaff891f721d8b312fdf54c756e35d",
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/core/update/UpdateTelemetry.kt": (
        "edccdea1e5889ac904a99b984dd5d9354c0b24d8224f485ecf0140ba83271622",
        "6ebc9b1358f8f0e1967a3c64258b7a63e3c8a6a084d5a7e12977d830463487e3",
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/components/OperationalComponents.kt": (
        "4d33263db6f837dfc657d5a97e3790ba2b0375ccd6c97d374a76e954c6b23a4c",
        "ccbd4092310bbef883ca9f6642af1f1dcbfbb63bfd45b3cb6a7f464593e334c4",
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt": (
        "5bb05d64d6db5eddb45438e8fa67807ff3ee8c39dd30386040eb9699de03de87",
        "3c2587cea88a49b2df1a0cfd4273e0c218b4d8dc1c2f5f4bae8b310c839e1422",
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomerPlaytimeViewModel.kt": (
        "255f68ae0ffa884cb843605459355a7f56a07b4502bca9dd33c189a3fc421f09",
        "94355c52236ed15603c883e35a0b4bb699f0593dcdbea8f9c056bd10b0a6dafd",
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersScreen.kt": (
        "d4e927f643237f61dccfa0696107777f47d148d4cd6a38b23638d519a12ed341",
        "046dc59952864c9e1eec2211c925cef598b6452a9dd8b4820cb6f2ecc0df2b12",
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingAlarmPermissionCard.kt": (
        "ddb7637890edc7bd86fda6ebb912e950429b8dfab8fe9318b1617a04e8d90dec",
        "cb3eb882099b964f02aab313b529d62a5fbbf119c2f536b9963880e9b652d7a9",
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDial.kt": (
        "e837b28082e3ae959bd54fbb85f6baf595e726e66124fded491d084c66a5c337",
        "e5da9406b6b3c03b9910e00a09acaaf2961991b0cc90bd64296982fb02ceef84",
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt": (
        "d61f4ef5e58a07a0118ec979a75fa403c35eeb1c96ea131bde1c2ef6ae48664f",
        "622b2d772dc18da7c1c5ecf306c361f92bf9d287d7e7e7166a539127b578c6c7",
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftCloseOriginPolicy.kt": (
        None,
        "2fff1263f375be2e85c753dc371221cc1a89c9ed46c8249f14c4864ead0b4798",
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftScreen.kt": (
        "03b585c84deed079b21e40df64fd3f0aa45d594c112b22fa1ce5fcb26f127faa",
        "a4b2a8344019d1e134c8ee649fe597adbe4d7c8ac8dabaf7eb607925cfac6900",
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftViewModel.kt": (
        "7d0514b53890903cbe94146bcb997ce2a99038ae28826a64beb8306750263c0b",
        "ab87661e4adc86a7a8a172df0e6138edabcb90bcb857eee5d9f9b6c83467f1c2",
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/theme/ControlDeckBackdrop.kt": (
        None,
        "8f76f05cc07235a0ac2d1b231fcdf4468da76cf565571b454446450b46a8b6b3",
    ),
}


def _regular_path(path: Path, *, directory: bool = False) -> None:
    for ancestor in (path, *path.parents):
        if ancestor.is_symlink():
            raise CompatibilityError("linked source/evidence path")
    try:
        mode = path.stat().st_mode
    except FileNotFoundError as exc:
        raise CompatibilityError("source/evidence path is missing") from exc
    expected = stat.S_ISDIR(mode) if directory else stat.S_ISREG(mode)
    if not expected:
        raise CompatibilityError("source/evidence path has the wrong type")


def _inventory(root: Path, scopes: tuple[str, ...]) -> dict[str, str]:
    _regular_path(root, directory=True)
    files: dict[str, str] = {}
    for scope in scopes:
        path = root / scope
        if not path.exists() and not path.is_symlink():
            continue
        if path.is_symlink():
            raise CompatibilityError("linked source/evidence path")
        if path.is_dir():
            _regular_path(path, directory=True)
            found = False
            for directory, dirs, names in os.walk(path, followlinks=False):
                current = Path(directory)
                for name in dirs:
                    _regular_path(current / name, directory=True)
                for name in names:
                    item = current / name
                    _regular_path(item)
                    relative = item.relative_to(root).as_posix()
                    if relative in files:
                        raise CompatibilityError("protected source scopes overlap")
                    files[relative] = hashlib.sha256(item.read_bytes()).hexdigest()
                    found = True
            if not found:
                raise CompatibilityError("unexpected empty protected source directory")
        else:
            _regular_path(path)
            if scope in files:
                raise CompatibilityError("protected source scopes overlap")
            files[scope] = hashlib.sha256(path.read_bytes()).hexdigest()
    return files


def _inventory_sha256(files: dict[str, str]) -> str:
    return hashlib.sha256(
        "".join(
            f"{path}\0{digest}\n" for path, digest in sorted(files.items())
        ).encode()
    ).hexdigest()


def _changes(
    before: dict[str, str], after: dict[str, str]
) -> dict[str, tuple[str | None, str | None]]:
    return {
        path: (before.get(path), after.get(path))
        for path in sorted(before.keys() | after.keys())
        if before.get(path) != after.get(path)
    }


def _verify_source_transition(
    before_root: Path,
    after_root: Path,
    *,
    scopes: tuple[str, ...],
    before_sha256: str,
    after_sha256: str,
    reviewed_changes: dict[str, tuple[str | None, str | None]],
) -> dict[str, int | str]:
    before = _inventory(before_root, scopes)
    after = _inventory(after_root, scopes)
    if _inventory_sha256(before) != before_sha256:
        raise CompatibilityError("prior source is not the reviewed immutable predecessor")
    if _changes(before, after) != reviewed_changes:
        raise CompatibilityError("candidate source has unreviewed runtime bytes or paths")
    if _inventory_sha256(after) != after_sha256:
        raise CompatibilityError("candidate protected source digest changed")
    return {
        "prior_protected_source_sha256": before_sha256,
        "candidate_protected_source_sha256": after_sha256,
        "protected_path_count": len(after),
        "reviewed_changed_path_count": len(reviewed_changes),
    }


def _verify_sources(prior: Path, candidate: Path, **overrides) -> dict[str, int | str]:
    return _verify_source_transition(
        prior,
        candidate,
        scopes=overrides.get("scopes", PROTECTED_SCOPES),
        before_sha256=overrides.get("prior_sha256", PRIOR_SOURCE_SHA256),
        after_sha256=overrides.get("candidate_sha256", CANDIDATE_SOURCE_SHA256),
        reviewed_changes=overrides.get("reviewed_changes", REVIEWED_CHANGES),
    )


def _verify_android_history(build41: Path, build44: Path, candidate: Path, **overrides) -> dict:
    scopes = overrides.get("scopes", ANDROID_SCOPES)
    first = _verify_source_transition(
        build41,
        build44,
        scopes=scopes,
        before_sha256=overrides.get("build41_sha256", BUILD41_ANDROID_SHA256),
        after_sha256=overrides.get("build44_sha256", BUILD44_ANDROID_SHA256),
        reviewed_changes=overrides.get(
            "build41_to_44_changes", REVIEWED_ANDROID_41_TO_44
        ),
    )
    build44_files = _inventory(build44, scopes)
    candidate_files = _inventory(candidate, scopes)
    if _changes(build44_files, candidate_files) != overrides.get(
        "build44_to_45_changes",
        {
            path: hashes
            for path, hashes in REVIEWED_CHANGES.items()
            if path.startswith("android-native/")
        },
    ):
        raise CompatibilityError("candidate Android compatibility closure changed")
    candidate_sha256 = overrides.get("candidate_sha256", CANDIDATE_ANDROID_SHA256)
    if _inventory_sha256(candidate_files) != candidate_sha256:
        raise CompatibilityError("candidate Android compatibility digest changed")
    return {
        "build41_android_sha256": first["prior_protected_source_sha256"],
        "build44_android_sha256": first["candidate_protected_source_sha256"],
        "candidate_android_sha256": candidate_sha256,
        "android_protected_path_count": len(candidate_files),
    }


def _backend_manifest_sha256(root: Path) -> str:
    files = _inventory(root, ("backend",))
    manifest = "".join(
        f"{digest}\t{path.removeprefix('backend/')}\n"
        for path, digest in sorted(files.items())
    ).encode()
    return hashlib.sha256(manifest).hexdigest()


def _validate_identity(
    *,
    prior_revision: str,
    prior_version: str,
    prior_db_head: str,
    candidate_revision: str,
    candidate_version: str,
    candidate_db_head: str,
    prior_image_id: str,
    candidate_image_id: str,
    prior_backend_manifest_sha256: str,
    candidate_backend_manifest_sha256: str,
    prior_source_archive_sha256: str,
    candidate_source_archive_sha256: str,
) -> None:
    if (
        prior_revision,
        prior_version,
        prior_db_head,
        candidate_version,
        candidate_db_head,
    ) != (
        PRIOR_REVISION,
        PRIOR_VERSION,
        PRIOR_DATABASE_HEAD,
        CANDIDATE_VERSION,
        CANDIDATE_DATABASE_HEAD,
    ):
        raise CompatibilityError("unsupported release or database transition")
    if not re.fullmatch(r"[0-9a-f]{40}", candidate_revision):
        raise CompatibilityError("candidate requires a full source revision")
    if candidate_revision in {prior_revision, BUILD41_REVISION}:
        raise CompatibilityError("candidate revision is not distinct")
    for image_id in (prior_image_id, candidate_image_id):
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", image_id):
            raise CompatibilityError("backend image requires an immutable image ID")
    if prior_image_id == candidate_image_id:
        raise CompatibilityError("prior and candidate backend images must be distinct")
    if prior_backend_manifest_sha256 != PRIOR_BACKEND_MANIFEST_SHA256:
        raise CompatibilityError("prior backend source manifest changed")
    if candidate_backend_manifest_sha256 != CANDIDATE_BACKEND_MANIFEST_SHA256:
        raise CompatibilityError("candidate backend source manifest changed")
    if prior_source_archive_sha256 != PRIOR_SOURCE_ARCHIVE_SHA256:
        raise CompatibilityError("prior source archive attestation changed")
    if (
        not re.fullmatch(r"[0-9a-f]{64}", candidate_source_archive_sha256)
        or candidate_source_archive_sha256 == prior_source_archive_sha256
    ):
        raise CompatibilityError("candidate source archive attestation is invalid")


def _load_historical_verifier(candidate: Path) -> types.ModuleType:
    path = candidate / HISTORICAL_VERIFIER
    module = types.ModuleType("build45_historical_cleanup_verifier")
    module.__file__ = str(path)
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    return module


def _decode_json(raw: bytes, historical, *, label: str) -> object:
    if not raw or len(raw) > MAX_DOCUMENT_BYTES:
        raise CompatibilityError(f"{label} document size is invalid")

    def invalid_constant(_value):
        raise CompatibilityError(f"non-finite {label} value")

    try:
        return json.loads(
            raw,
            object_pairs_hook=historical._reject_duplicate_keys,
            parse_constant=invalid_constant,
        )
    except historical.StateError as exc:
        raise CompatibilityError(f"duplicate {label} key") from exc
    except (ValueError, UnicodeDecodeError) as exc:
        raise CompatibilityError(f"invalid {label} JSON") from exc


def _verify_scoped_history(document: object, historical) -> dict:
    if not isinstance(document, dict):
        raise CompatibilityError("historical state must be an object")
    if document.get("database_revision") != PRIOR_DATABASE_HEAD:
        raise CompatibilityError("historical state has the wrong database revision")
    if (
        type(document.get("pending_outbox_count")) is not int
        or type(document.get("nonzero_installation_count")) is not int
        or document["pending_outbox_count"] != EXPECTED_PENDING
        or document["nonzero_installation_count"] != EXPECTED_NONZERO
    ):
        raise CompatibilityError("historical state aggregates changed")
    scoped = deepcopy(document)
    scoped["pending_outbox_count"] = 1
    scoped["nonzero_installation_count"] = 1
    try:
        receipt_id = historical.verify_state(scoped)
    except historical.StateError as exc:
        raise CompatibilityError("retained historical cleanup proof failed") from exc
    return {
        "historical_cleanup_receipt_id": receipt_id,
        "historical_proof_scope": "exact_retained_legacy_installation_only",
    }


def _row_projection(row: dict) -> str:
    values = (
        row["row_id"],
        row["installation_id"],
        row["company_id"],
        row["terminal_id"] or "",
        row["platform"],
        row["version_name"],
        str(row["version_code"]),
        str(row["pending_outbox_count"]),
        row["last_successful_sync_at"],
        row["last_seen_at"],
    )
    if not all(isinstance(value, str) for value in values):
        raise CompatibilityError("fleet row contains a malformed value")
    return "\x1f".join(values)


def _verify_fleet(document: object, historical) -> dict:
    expected_keys = {
        "schema_version",
        "database_revision",
        "installation_row_count",
        "pending_outbox_count",
        "nonzero_installation_count",
        "all_installations_identity_sha256",
        "nonzero_installations_identity_sha256",
        "nonzero_installations",
        "client_installation_guard_trigger_count",
        "client_installation_guard_function_sha256",
        "client_installation_guard_function_definition_sha256",
        "client_installation_guard_trigger_definition_sha256",
    }
    if not isinstance(document, dict) or set(document) != expected_keys:
        raise CompatibilityError("fleet state shape changed")
    if (
        type(document["schema_version"]) is not int
        or document["schema_version"] != 1
        or document["database_revision"] != PRIOR_DATABASE_HEAD
        or type(document["installation_row_count"]) is not int
        or document["installation_row_count"] != EXPECTED_INSTALLATION_ROWS
        or type(document["pending_outbox_count"]) is not int
        or document["pending_outbox_count"] != EXPECTED_PENDING
        or type(document["nonzero_installation_count"]) is not int
        or document["nonzero_installation_count"] != EXPECTED_NONZERO
        or document["all_installations_identity_sha256"]
        != EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256
    ):
        raise CompatibilityError("fleet state aggregate or identity changed")
    if (
        type(document["client_installation_guard_trigger_count"]) is not int
        or document["client_installation_guard_trigger_count"] != 1
        or document["client_installation_guard_function_sha256"]
        != historical.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_SHA256
        or document["client_installation_guard_function_definition_sha256"]
        != historical.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_DEFINITION_SHA256
        or document["client_installation_guard_trigger_definition_sha256"]
        != historical.EXPECTED_CLIENT_INSTALLATION_GUARD_TRIGGER_DEFINITION_SHA256
    ):
        raise CompatibilityError("installation-scope database guard changed")
    rows = document["nonzero_installations"]
    if not isinstance(rows, list) or len(rows) != EXPECTED_NONZERO:
        raise CompatibilityError("fleet must contain exactly two nonzero installations")
    row_keys = {
        "row_id",
        "installation_id",
        "company_id",
        "terminal_id",
        "platform",
        "version_name",
        "version_code",
        "pending_outbox_count",
        "last_successful_sync_at",
        "last_seen_at",
        "identity_sha256",
    }
    seen_ids: set[str] = set()
    seen_digests: set[str] = set()
    identities = []
    prior_sort_key = ""
    for row in rows:
        if not isinstance(row, dict) or set(row) != row_keys:
            raise CompatibilityError("fleet row shape changed")
        if (
            not isinstance(row["row_id"], str)
            or row["row_id"] <= prior_sort_key
            or row["row_id"] in seen_ids
            or type(row["version_code"]) is not int
            or type(row["pending_outbox_count"]) is not int
            or row["pending_outbox_count"] != 1
        ):
            raise CompatibilityError("fleet row ordering, identity, or counter changed")
        prior_sort_key = row["row_id"]
        seen_ids.add(row["row_id"])
        digest = hashlib.sha256(_row_projection(row).encode()).hexdigest()
        if row["identity_sha256"] != digest or digest in seen_digests:
            raise CompatibilityError("fleet row digest changed or is duplicated")
        seen_digests.add(digest)
        expected = EXPECTED_FLEET_ROWS.get(digest)
        if expected is None or (row["version_name"], row["version_code"]) != expected[1:]:
            raise CompatibilityError("unreviewed nonzero installation")
        identities.append(
            {
                "classification": expected[0],
                "installation_identity_sha256": digest,
                "version_name": row["version_name"],
                "version_code": row["version_code"],
                "pending_outbox_count": 1,
            }
        )
    if seen_digests != set(EXPECTED_FLEET_ROWS):
        raise CompatibilityError("required nonzero installation is missing")
    fleet_digest = hashlib.sha256("\n".join(sorted(seen_digests, key=lambda item: next(
        row["row_id"] for row in rows if row["identity_sha256"] == item
    ))).encode()).hexdigest()
    if (
        document["nonzero_installations_identity_sha256"] != fleet_digest
        or fleet_digest != EXPECTED_NONZERO_FLEET_SHA256
    ):
        raise CompatibilityError("nonzero fleet aggregate digest changed")
    return {
        "installation_row_count": EXPECTED_INSTALLATION_ROWS,
        "raw_pending_outbox_count": EXPECTED_PENDING,
        "raw_nonzero_installation_count": EXPECTED_NONZERO,
        "nonzero_installations_identity_sha256": fleet_digest,
        "installations": identities,
    }


def _verify_environment(prior: bytes, live: bytes, candidate: bytes, candidate_revision: str) -> None:
    if prior != live:
        raise CompatibilityError("live environment changed after rollback snapshot")

    def without_release(raw: bytes, version: str, revision: str) -> list[bytes]:
        expected = {b"APP_VERSION": version.encode(), b"APP_REVISION": revision.encode()}
        seen: set[bytes] = set()
        retained = []
        for line in raw.splitlines(keepends=True):
            match = re.match(rb"\s*(?:export\s+)?(APP_VERSION|APP_REVISION)\s*=", line)
            if match:
                key = match[1]
                if key in seen or line.rstrip(b"\r\n") != key + b"=" + expected[key]:
                    raise CompatibilityError("environment release identity is ambiguous")
                seen.add(key)
            else:
                retained.append(line)
        if seen != expected.keys():
            raise CompatibilityError("environment release identity is missing")
        return retained

    if without_release(prior, PRIOR_VERSION, PRIOR_REVISION) != without_release(
        candidate, CANDIDATE_VERSION, candidate_revision
    ):
        raise CompatibilityError("non-release environment bytes changed")


def _json_document(raw: bytes) -> object:
    if not raw or len(raw) > MAX_DOCUMENT_BYTES:
        raise CompatibilityError("runtime configuration document size is invalid")

    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise CompatibilityError("duplicate runtime configuration key")
            result[key] = value
        return result

    def invalid_constant(_value):
        raise CompatibilityError("non-finite runtime configuration value")

    try:
        return json.loads(raw, object_pairs_hook=unique_pairs, parse_constant=invalid_constant)
    except (ValueError, UnicodeDecodeError) as exc:
        raise CompatibilityError("invalid runtime configuration JSON") from exc


def _docker_environment(document: object, expected_image_id: str) -> dict[str, str]:
    if (
        not isinstance(document, dict)
        or set(document) != {"image_id", "environment"}
        or document["image_id"] != expected_image_id
        or not isinstance(document["environment"], list)
    ):
        raise CompatibilityError("runtime environment image binding is invalid")
    environment: dict[str, str] = {}
    for item in document["environment"]:
        if not isinstance(item, str) or "=" not in item:
            raise CompatibilityError("invalid Docker environment entry")
        key, value = item.split("=", 1)
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) or key in environment:
            raise CompatibilityError("invalid or duplicate Docker environment key")
        environment[key] = value
    return environment


def _verify_effective_environment(
    prior_container: object,
    candidate_image: object,
    candidate_compose: object,
    prior_image_id: str,
    candidate_image_id: str,
    candidate_revision: str,
) -> int:
    before = _docker_environment(prior_container, prior_image_id)
    after = _docker_environment(candidate_image, candidate_image_id)
    try:
        backend = candidate_compose["services"]["backend"]
        overrides = backend["environment"]
        if (
            backend["image"] != f"d-company-erp-backend:{candidate_revision}"
            or "env_file" in backend
            or not isinstance(overrides, dict)
        ):
            raise CompatibilityError("candidate Compose environment source is unexpected")
    except (TypeError, KeyError) as exc:
        raise CompatibilityError("candidate Compose backend environment is missing") from exc
    for key, value in overrides.items():
        if (
            not isinstance(key, str)
            or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key)
            or not isinstance(value, str)
        ):
            raise CompatibilityError("candidate Compose environment must contain strings")
    if {"PATH", "PYTHON_VERSION", "PYTHON_SHA256"} & overrides.keys():
        raise CompatibilityError("candidate Compose masks reviewed image metadata")
    after.update(overrides)
    for values, version, revision in (
        (before, PRIOR_VERSION, PRIOR_REVISION),
        (after, CANDIDATE_VERSION, candidate_revision),
    ):
        if values.pop("APP_VERSION", None) != version or values.pop("APP_REVISION", None) != revision:
            raise CompatibilityError("effective runtime release identity changed")
    if before.pop("PYTHON_VERSION", None) != "3.14.7" or after.pop(
        "PYTHON_VERSION", None
    ) != "3.14.8":
        raise CompatibilityError("reviewed Python image metadata is missing")
    prior_python_sha = before.pop("PYTHON_SHA256", None)
    candidate_python_sha = after.pop("PYTHON_SHA256", None)
    if (
        prior_python_sha
        != "3b48dac8fb59f62eaa67ac83c1eb12bda1b7a08406dd286e252c11a66be27f81"
        or candidate_python_sha
        != "c2215904f02b175596dc49351585104f4bc20341e1c47378b26a2c274360ce73"
        or prior_python_sha == candidate_python_sha
    ):
        raise CompatibilityError("reviewed Python image digest metadata changed")
    if before != after:
        raise CompatibilityError("effective backend environment changed")
    return len(before) + 4


def _private_file(path: Path) -> None:
    _regular_path(path)
    if path.stat().st_uid != 0 or stat.S_IMODE(path.stat().st_mode) != 0o600:
        raise CompatibilityError("environment/evidence must be root-owned mode 0600")


def _private_root(path: Path) -> None:
    _regular_path(path, directory=True)
    if path.stat().st_uid != 0 or stat.S_IMODE(path.stat().st_mode) != 0o700:
        raise CompatibilityError("source archive root must be root-owned mode 0700")


def _validate_source_roots(
    build41: Path, prior: Path, candidate: Path, executable: Path
) -> None:
    for root in (build41, prior, candidate):
        _private_root(root)
    if len({build41, prior, candidate}) != 3:
        raise CompatibilityError("source archives must be distinct")
    if executable.absolute().parents[2] != candidate:
        raise CompatibilityError("verifier must execute from the candidate archive")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in (
        "build41-source-root",
        "prior-source-root",
        "candidate-source-root",
        "raw-historical-state",
        "raw-fleet-state",
        "prior-env",
        "live-env",
        "candidate-env",
        "prior-runtime-env",
        "candidate-image-env",
        "candidate-compose-config",
    ):
        parser.add_argument(f"--{name}", type=Path, required=True)
    for name in (
        "prior-revision",
        "prior-version",
        "prior-db-head",
        "candidate-revision",
        "candidate-version",
        "candidate-db-head",
        "prior-image-id",
        "candidate-image-id",
        "prior-backend-manifest-sha256",
        "candidate-backend-manifest-sha256",
        "prior-source-archive-sha256",
        "candidate-source-archive-sha256",
    ):
        parser.add_argument(f"--{name}", required=True)
    args = parser.parse_args()
    try:
        _validate_identity(
            prior_revision=args.prior_revision,
            prior_version=args.prior_version,
            prior_db_head=args.prior_db_head,
            candidate_revision=args.candidate_revision,
            candidate_version=args.candidate_version,
            candidate_db_head=args.candidate_db_head,
            prior_image_id=args.prior_image_id,
            candidate_image_id=args.candidate_image_id,
            prior_backend_manifest_sha256=args.prior_backend_manifest_sha256,
            candidate_backend_manifest_sha256=args.candidate_backend_manifest_sha256,
            prior_source_archive_sha256=args.prior_source_archive_sha256,
            candidate_source_archive_sha256=args.candidate_source_archive_sha256,
        )
        _validate_source_roots(
            args.build41_source_root,
            args.prior_source_root,
            args.candidate_source_root,
            Path(__file__),
        )
        source_proof = _verify_sources(args.prior_source_root, args.candidate_source_root)
        android_proof = _verify_android_history(
            args.build41_source_root,
            args.prior_source_root,
            args.candidate_source_root,
        )
        if _backend_manifest_sha256(args.prior_source_root) != args.prior_backend_manifest_sha256:
            raise CompatibilityError("prior backend manifest is not canonical")
        if _backend_manifest_sha256(args.candidate_source_root) != args.candidate_backend_manifest_sha256:
            raise CompatibilityError("candidate backend manifest is not canonical")
        for path in (
            args.raw_historical_state,
            args.raw_fleet_state,
            args.prior_env,
            args.live_env,
            args.candidate_env,
            args.prior_runtime_env,
            args.candidate_image_env,
            args.candidate_compose_config,
        ):
            _private_file(path)
        historical = _load_historical_verifier(args.candidate_source_root)
        historical_raw = args.raw_historical_state.read_bytes()
        fleet_raw = args.raw_fleet_state.read_bytes()
        history = _verify_scoped_history(
            _decode_json(historical_raw, historical, label="historical state"),
            historical,
        )
        fleet = _verify_fleet(
            _decode_json(fleet_raw, historical, label="fleet state"),
            historical,
        )
        _verify_environment(
            args.prior_env.read_bytes(),
            args.live_env.read_bytes(),
            args.candidate_env.read_bytes(),
            args.candidate_revision,
        )
        effective_count = _verify_effective_environment(
            _json_document(args.prior_runtime_env.read_bytes()),
            _json_document(args.candidate_image_env.read_bytes()),
            _json_document(args.candidate_compose_config.read_bytes()),
            args.prior_image_id,
            args.candidate_image_id,
            args.candidate_revision,
        )
        report = {
            "schema_version": 1,
            "status": "pending_carried_unresolved",
            "transition": {
                "prior_revision": args.prior_revision,
                "prior_version": args.prior_version,
                "prior_database_head": args.prior_db_head,
                "candidate_revision": args.candidate_revision,
                "candidate_version": args.candidate_version,
                "candidate_database_head": args.candidate_db_head,
                "prior_image_id": args.prior_image_id,
                "candidate_image_id": args.candidate_image_id,
                "prior_source_archive_sha256": args.prior_source_archive_sha256,
                "candidate_source_archive_sha256": args.candidate_source_archive_sha256,
                "prior_backend_manifest_sha256": args.prior_backend_manifest_sha256,
                "candidate_backend_manifest_sha256": args.candidate_backend_manifest_sha256,
            },
            "source_proof": source_proof,
            "android_compatibility_proof": android_proof,
            "historical_cleanup_proof": history,
            "fleet": fleet,
            "raw_historical_state_sha256": hashlib.sha256(historical_raw).hexdigest(),
            "raw_fleet_state_sha256": hashlib.sha256(fleet_raw).hexdigest(),
            "effective_environment_key_count": effective_count,
            "queue_drained": False,
            "pending_not_drained": True,
            "tablet_queue_contents_verified": False,
            "telemetry_rewritten": False,
            "installation_retired": False,
            "build41_work_classification": "unknown_preserved_work",
            "server_contract_compatibility_only": True,
        }
        sys.stdout.write(json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n")
        return 0
    except (CompatibilityError, OSError) as exc:
        print(f"build45 compatibility refused: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
