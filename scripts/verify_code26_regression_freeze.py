#!/usr/bin/env python3
"""Fail closed when Code 26 through Code30.4 weakens the proven Code 25 surface.

This is deliberately release-specific.  Code 25 is the behavioural baseline;
Code 26 may add tests and narrowly change the allow-listed failure paths. Code
27 additionally carries the reviewed deployment correction and Web session
expiry repair. Code 28 hardens the release scanner path. Original Code 29
carries the reviewed image-format and Android quantity corrections; corrected
Code 29 adds coordinated identity and the reviewed installer lock path. Current
Code 29 adds only the reviewed POS-notice, inventory-layout, Web session,
refresh-lock, owner-approved pricing-card, Code 29.1 packaging-label, and the
reviewed Code29.2 customer-playtime draft. Code30 adds the reviewed saved-customer
lookup. Code30.1 adds the independently reviewed future-clock Stop recovery,
rejected-session attention correction, and customer-deletion replay fence. Its
build-35 retry changed only coordinated identity and two stale release fixtures;
build 36 carries the exact reviewed replay correction. Code30.2 adds only the
exact reviewed finance, receipt-evidence, Google Sheets mirror, Room 51,
Alembic 0078, release metadata and five-mode business-audit delta below.
Code30.3 adds the reviewed stale Gaming cleanup reconciliation at Room 52 and
Alembic 0079, atomic split tender at Alembic 0080, immutable cleanup report
identity at Alembic 0081, Web station-transfer parity,
business-day shift timing, coordinated release identity, tests and operator
documentation. Code30.4 adds reviewed PS5 friend participation and 60-to-30
package amendment at Alembic 0083-0085 and Room 53, paid non-PS5 extensions,
local app-health capture and a bounded Gaming refresh. Build 42 adds the
reviewed Android control-deck polish and coordinated release identity.
The immutable Code30.2 through Code30.4 build-54 bytes are read from their
release commits; the current working tree is checked against a separate
build-55 map covering the reviewed Gaming/workflow corrections, Android-only
older palette, protected update policy and guarded preservation profile.
Source admission does not assert signing, deployment or tablet acceptance.
No release layer
may delete, disable, reorder, or rewrite an existing test
outside the exact fixture-only normalization or exact reviewed-test hashes below.
The sole reviewed audit-reader locator migration below preserves every
credential-cleanup assertion while following the corrected UTF-8 reader.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path


CODE25_BASE = "715ba8c2671c7fbceb362ab59052a8a128b67668"
POS_NOTICE_TEST_PATH = (
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/"
    "PosEmptyCatalogueUiTest.kt"
)
POS_NOTICE_TEST_SHA256 = (
    "b56a28fda657fd04490b5dd055e24c524747dd3ba5b1b2e5f34af79f7e907ef5"
)
REFRESH_LOCKING_TEST_PATH = "backend/tests/integration/test_auth_refresh_locking.py"
REFRESH_LOCKING_TEST_SHA256 = (
    "719ba8b478d70e2a6a565f564b785df8e4ee7f89c0f92260891c4e7e808887cf"
)
REVIEWED_PRICING_TEST_SHA256 = {
    "backend/tests/unit/test_gaming_tariff_catalog.py": (
        "26ab1a7adb35ee4110b0357854c07f363c42cc191845d3d9cc10770f98836699"
    ),
    "backend/tests/integration/test_gaming_tariff_pos_e2e.py": (
        "98838882791dc32c9fac9a42932b3743611962f02a8e0b32cb0e5c427e101f1f"
    ),
    "backend/tests/integration/test_gaming_phase2_contracts.py": (
        "7d37f3fb0ae085fb61e92a24ffbb5415cca20dc4b26ae9f667ceece3be48d4db"
    ),
    "backend/tests/integration/test_captured_shift_opening.py": (
        "c2723589654819903ed6d5d3ae7d362e6389a18c9b4ea42becee947279ddf04f"
    ),
    "backend/tests/integration/test_gaming_co_owner_stop.py": (
        "bf30afe6916d6ad8c3547ae5de9ba94e6a1f4bc9f354d7743783d4d39e8b3d80"
    ),
    "frontend/src/modules/gaming/gaming-tariff.test.ts": (
        "03b1f1ca87c7e04c3d6e48470403ac383080384d1bc69a0282e3f9e4a0771e50"
    ),
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt": (
        "fe8f3202225b2ee0387c26bca1ffd3b6477434f17336b5cca7aaf5ed771893ac"
    ),
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingStationPresentationTest.kt": (
        "a9db63a7cc106918c04929b3b0c02dc6fce0b9c52695a838adce83f3125dd547"
    ),
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModelRecoveryTest.kt": (
        "5a90ad73d21081150ab012ea33774457164accfa413ef3e6da5fe0be65ce0314"
    ),
    "android-native/app/src/test/java/cloud/dcompany/erp/core/sync/GamingPackageExtensionReplayPolicyTest.kt": (
        "6292de870fa7c02ecb99bfe2d81e8e99a1561b3faee8c8f5807409cf674208f1"
    ),
}
REVIEWED_PACKAGING_TEST_SHA256 = {
    "frontend/src/modules/settings/tabs/SystemHealthTab.test.tsx": (
        "0c6e16e7c782ef96463685ea8509b7f0a61070a5e4305c1540188dc7ac7d2724"
    ),
}
REVIEWED_CODE29_2_TEST_SHA256 = {
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt": "92f604eeeac4ebf0c39d9c8a13778764b2611d2ecd8b740e458d486d3b94bff1",
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/GamingCentreFeatureProfileTest.kt": "e26fdac9a83f152af8ab7dae5b80014516f88f47f93a0e78978524ea9270c9f0",
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/FinancePresentationPolicyTest.kt": "6754029ae8b59b5c9a0c1b273f8f9e735a3be243b88f8ee527be2e36af537535",
    "backend/tests/integration/test_points_reservation_balance.py": "ef4bd05349fde10b2e1c14bc2b968e388f9a76318bceaad8e471f6a9912118a1",
    "frontend/src/lib/product-profile.test.ts": "c6f6a2e6a53967c85abda6ab9c6ff343b4cb2922e4258b4b5a97f1b64a2ba6b1",
}
REVIEWED_CODE30_TEST_SHA256 = {
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/MigrationTest.kt": "898d83ae44d48ba617a0b62eb8536a00239ddf8759b9605e08a9976c5bf59abe",
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt": "3663476bd9d05f4ad077e1660a446dcdbb6dcf73fa4451b10e6164a696c77220",
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingApiContractTest.kt": "a91358fff7ea9c147c346dd2a20941f6cac156c91f012a6bb2e1cd745fd1b3a1",
}
REVIEWED_CODE30_1_FEATURE_SHA256 = {
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/GamingDaoRecoveryTest.kt": "67be6534b8c2406417b80e7318fd513ce9df3f1b9b06ca095aae7dba96cb8c09",
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingRejectedSessionAttentionUiTest.kt": "c38975352ac4cb969768e08f51991ae7d367636c4d762882e04f6aae33c5cd11",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingDao.kt": "e1eb103fdbc7a5d81a692866ebb43301a8d060226631b0d54f6fcf672c98b392",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingEntities.kt": "fff2bcae008980284f10fb716360a5e5e4bfe5858761c410c1bdd7b861188272",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt": "32f179c36c70e30eca5e18f715da49124313a320f018f73e8f74f9cdbebad7eb",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt": "c49ccecb37b5fec8c91068621f6b05b809a9ef95c331fb014f81916491d51f6b",
    "android-native/app/src/test/java/cloud/dcompany/erp/core/db/GamingStopClockRecoveryPolicyTest.kt": "a191996d165e5cdb610f8a591aba585136a611a444e74c0e7603b791bc5c5fc6",
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingRejectedSessionAttentionTest.kt": "501cd87284160a359803d5000fb77ab0c9aeaab8ea8dc6d485350b44df8b3800",
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingStationPresentationTest.kt": "7f8a5d364aed38b56799338b07737a34aa9e76eed0b2607092815fcfc2723acd",
}
REVIEWED_CODE30_1_DELETION_REPLAY_SHA256 = {
    'android-native/app/schemas/cloud.dcompany.erp.core.db.ErpDatabase/48.json': 'ed4e5f971d18dc0f7402a65bd3f9ca4224094e157e7bd80b068a07dc6201aa7f',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/MigrationTest.kt': '69ddf22569fcf73baf9a589d524c629d129990ade6f7bd45a892d0b665fe5662',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/auth/CacheScope.kt': '7cd46297dd83875e39e68f21564bf352c7794bbd50febd33f452d09630c1eafd',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/CustomerDao.kt': '851aeee68320705ae3db67439d9440b21a63acdefb584cac9a1b762c4457044a',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/CustomerEntities.kt': 'b348b94c416790f73adb519e30566448f32702afc7228a97df1b4934b6e5b9bc',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Dao.kt': 'd5dcf35eed34f3fd3fc2ab0cc6b1e73b4d7cc77c9ad20bb2af39463222e49d56',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Entities.kt': '23199787a5c26756c429d29cb41072366b82be8bc54399e2eb1c7c74986ffe82',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingEntities.kt': 'd86a6415bc2aa8a7c682303bc24f7d28c7f59ccf593a5c652add857e08b757d1',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Migrations.kt': '4b89a5410ef53890b071b3aeb398aa174b35e11f055e4dc8c691cdd6b5ea78bd',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/ShiftResolution.kt': '2fe2e918ef1f5a5240fb02a67034cf98865e97eec27727f7971258593ec71c69',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/net/Models.kt': 'c47b7f46f3d60dbca617528d15a799a432ccea0c8b46590a556dab9e88ea7f9f',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/sync/SyncEngine.kt': '8dd7d0fe023a0ca29d7c2d86d6d09f18d325ce3b20027ae3197e73732d1c689d',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt': '40776c7078ae050e93994bb2c0a72c7a1d907659510dd225d9d8396f1f25d7d3',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersApi.kt': '6e14af04f35990d634a8927153601d261dedbae638a4acda5eaf9f686ad350cf',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersModels.kt': '4440d4df905e3853162be4bcff39037d443275641ee91d6ed6c5613015679ae7',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersViewModel.kt': '9035607b527888720ea491b9f9030e67d3be0dc325da2f63686a009c76285eb5',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingApi.kt': 'b0cd75ae324edcd5ca1203dc14948342491f68aa9141f394abb2d11abe60cbf8',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt': 'd0a23da7fd3a1b8b6a14ff7f80ac264af7ce4aa09a4cbda85a32f2517c2f266e',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/auth/CacheScopeTest.kt': 'f02ca9009e3367f163ba09098372669092ced8c2a8ca22d5485741c6f226394a',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/db/ShiftResolutionPolicyTest.kt': 'a643694d9662ea2a0fe4ce980cfd512436c6fc6f1fd86ba5d887510efc7fdfec',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/net/CustomerDirectoryEvidenceContractTest.kt': '1968295eb6485a00cdcef1281988a047c66ea88e072ce32b6e22328372f4204b',
    'backend/alembic/versions/0073_customer_deletion_replay_fence.py': 'e51b8e23c0ac0be960c32df61d3192c5148aa2adbfea860fadaa6b64c29fcf57',
    'backend/app/api/v1/customers/router.py': '219bfed3e7497789727c5fca20589c5f44f7f7aa4dbc44a3004ba8b79689cca7',
    'backend/app/api/v1/gaming/router.py': '3485f73cf572863ef4f8bdd1e132c10d84e2e2fda2a77a392842beaec246a396',
    'backend/app/api/v1/pos/router.py': 'b1530c67aee5d2af1e70f7a2300f736f5fe2b3ebccbfeef9d066b23e0d3e345d',
    'backend/app/core/roles.py': '556d305d21598cc510f8523b4bc420518b06b955613f712bedcb9621fd2d8e2a',
    'backend/app/main.py': '51f2d88341e3d21a90c69359f259a9a2470e137b6a1d86698691744d7c93469a',
    'backend/app/models/__init__.py': '282230862ac225bec3f2f8ffa4f80d9360d66b8becb9ce963cdd99191a0dda7f',
    'backend/app/models/customer.py': '25d8b7f8867eccf67b020403b17ff43af081399948bc21b2ce3e33962f443a83',
    'backend/app/models/gaming.py': '90b5b6e6e60a0de7b9113e6ac5de23c042480bd8670761aee1ab7495909931b3',
    'backend/app/models/pos.py': '98c62a3dcf31e0a8439ed5933e1770d897a3911bdf6fcd00f9a7beb649d4d673',
    'backend/app/services/customers/deletion_fence.py': 'f95200aa156b42ed587cd7d2fa15f424f82767ea5ae7cb2f3274d0202e54fabc',
    'backend/tests/integration/test_checkout_claim_contention.py': 'f79adb4c6b082bd51fcc0dd697ecc1535b86e45ba2607583c304ec435d3935a6',
    'backend/tests/integration/test_customer_delete.py': '8952d317f0a4517bb5118f1900854daf8bc8609782817e2575e3a7f508ab1c6c',
    'backend/tests/integration/test_customer_deletion_replay.py': 'f507dd21a24eae1f2a9561a8c4055f9e129f7426e34ea219a78a628b356b1216',
    'backend/tests/integration/test_customer_playtime_draft.py': '0866a4b317cd915c88ef448292a42cf885e0aa13a3138008ef6008f6b0382a5d',
    'backend/tests/integration/test_gaming_tariff_pos_e2e.py': '561bce88b6fad16faf19c0a7e106b480a110609d0ec0e0fe584c83e98e9a14cb',
    'backend/tests/unit/test_customers_router_upsert_race.py': '54347746c08f9a542d12935dd947ff4edd58baf131bffdebd6154e2eb921811e',
    'backend/tests/unit/test_gaming_reconciliation.py': '0fa654889e3f4f1cdcf28ff62f99fbb5d1c2646d71c2574bf47c19962c7c7214',
    'backend/tests/unit/test_membership_benefits.py': '3a306a748a5583af9e8c8c8861a056218ec128feaf67b4cc6d5b3775a25bff0d',
    'backend/tests/unit/test_operational_route_integrity.py': '3814a5a683b5b694064dca09b7179b366f3b686ea357314eeedce142c6997a4a',
    'frontend/src/lib/erp-api-customer-directory.test.ts': 'aaa1cd1e93ea29bd3d269efda503d4830ae3b182988e1ee5f37bc7f0e0d2b499',
    'frontend/src/lib/erp-api-pos-settlement.test.ts': 'f38bddfb347f33989b7566ec11336f79d139953c6b48a2de79381f5af3e7017d',
    'frontend/src/lib/erp-api.ts': '65747d9001ed333d93d840f119297768e6cd46e2adb1721be4b5894b64c1f36f',
    'frontend/src/lib/retry-drafts.test.ts': '05e0b325414fbb0be8d66e1d7328cf7a6f2d043e9609def59902ca7e5d4d0d15',
    'frontend/src/lib/retry-drafts.ts': '657be62ed9989faf2c83de80545a5a878ea37ba55fd529fbec905633d556801d',
    'frontend/src/modules/pos/LivePOSScreen.tsx': '03f1115fb6fb29c3e6c512d50de38bf8f888ae587444db064dcc9bbb01252b83',
}
REVIEWED_CODE30_1_RELEASE_TEST_SHA256 = {
    "backend/tests/unit/test_release_contracts.py": "be3895b1832adc5ece154682107195ba96bb39008f38faabcf9bd9541f18af10",
    "backend/tests/unit/test_remote_assistance_contract.py": "6158e31cbbb74455e247ad161c9c97da844081152fd9da79dd7e8a19a2113216",
}

REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256 = {
    "backend/tests/unit/test_release_contracts.py": "f8157a52d62a20df2c2d9180244a0b7891df773a6421a2b5e9ccbf01d441260d",
    "backend/tests/unit/test_remote_assistance_contract.py": "32fdd6b87e3bc50cb146c9ed1888592f1ba43d568a8b996692b0138eada6fd1c",
}

CODE30_1_BASE = "3b534fdc76a5463475d58f44686f91608e1e2601"
CODE30_2_BASE = "3ea84be4718a794d5a2e8efc7ac9bcacbc0cee01"
# Tag v3.1.30: the PR 16 merge commit released as Code30.3.
CODE30_3_BASE = "ad5adfb93c3488f1f931ca27da53824aa57d3dc5"
# Signed v3.1.31 / build 39: preserved as the reviewed, unoffered Code30.4
# source. The corrective v3.1.32 release is checked by a separate overlay.
CODE30_4_BASE = "5bfa54c8d960a91e99144d2b04978ea73a6196a5"
# Signed v3.1.32 / build 40: immutable base for the additive build-41 UI patch.
CODE30_4_BUILD40_BASE = "b503290a48ad3693b3913c5a77b1f7bbce219c46"
# Signed v3.1.33 / build 41: immutable base for the build-42 UI polish.
CODE30_4_BUILD41_BASE = "099daa28ea5b9cb3c4b167a66c82302896ad4acc"
# Tagged v3.1.34 / build 42: failed unsigned image-gate candidate, never retag.
CODE30_4_BUILD42_BASE = "1c1c7582eb8d6d6c509ddddc159014af10ce5f89"
# Tagged v3.1.35 / build 43: unsigned; cancelled before signing for TLS inventory.
CODE30_4_BUILD43_BASE = "8418e349eac0707ef62629f6cd3b7dc434690436"
# v3.1.36 / build 44: immutable source for the build-45 quality overlay.
CODE30_4_BUILD44_BASE = "d915a66c9dee0d22c1a7018e2e79bc8ca9e0d7ab"
# v3.1.37 / build 45: immutable source for the build-46 operational overlay.
CODE30_4_BUILD45_BASE = "ee025b5b78dff7397e16612cad80aebd1a4904d3"
# Signed v3.1.38 / build 46: immutable, unoffered source. Build 47 uses a
# separate overlay so this historical allow map never moves.
CODE30_4_BUILD46_BASE = "b743d7715d22c5ce92f79edc9a371df28930a6a1"
# Published v3.1.39 / build 47: build48 extends its frozen bytes separately.
CODE30_4_BUILD47_BASE = "986584a4e24dc6ab777ab89afe4f6fec374229c6"
# v3.1.40 / build 48: immutable source for the build-49 UI overlay.
CODE30_4_BUILD48_BASE = "4058fb29e70789a9bb3fa62b2d580330b25db8a3"
# Published v3.1.41 / build 49: build50 extends its frozen bytes separately.
CODE30_4_BUILD49_BASE = "a834b63d32c45513ab5c8fd9d4de417acc0a96c6"
# Published v3.1.42 / build 50: immutable base for the reviewed build-51 overlay.
CODE30_4_BUILD50_BASE = "29e32b440ab8dc162b6c8f5d40f8283fad53ce04"
# Frozen cancelled/unsigned Build51 source; never substitute it for deployed Build50.
CODE30_4_BUILD51_BASE = "a44397d53d94fb4c83b3fc9ffe696dbf4dce3168"
# Withheld v3.1.44 / build 52 source; build 53 extends its frozen bytes.
CODE30_4_BUILD52_BASE = "f78d567b2093671992be289133af22637e6d7b0d"
# Signed Build53 deployment refused before migration; immutable predecessor source.
CODE30_4_BUILD53_BASE = "8506c7abdf15c92029226849f42b1b71fd6878d6"
# Signed/deployed v3.1.46 / build54; immutable predecessor for build55.
CODE30_4_BUILD54_BASE = "5ad146ae076f5178acc2c8e4cb840ecbb0d0343a"
CODE30_2_FREEZE_CONTROL_PATHS = frozenset({
    "scripts/verify_code26_regression_freeze.py",
    "tests/test_code26_regression_freeze.py",
    "tests/test_code29_installer_correction.py",
})
CODE30_3_FREEZE_CONTROL_PATHS = frozenset({
    "scripts/verify_code26_regression_freeze.py",
    "tests/test_code26_regression_freeze.py",
    "tests/test_code29_installer_correction.py",
})
CODE30_4_FREEZE_CONTROL_PATHS = frozenset({
    "scripts/verify_code26_regression_freeze.py",
    "tests/test_code26_regression_freeze.py",
    "tests/test_code29_installer_correction.py",
})
CODE30_2_AUDIT_PLAN_PATH = (
    "android-native/audit-driver/plans/code26-gaming-finance-physical.json"
)

REVIEWED_CODE30_2_SHA256 = {
    '.env.example': '66fd8cd61ac04567a1dfb62d4c01544703f906891fc389bccee7237112bf5798',
    '.env.production.example': '03f61cec1976d892fcc8811415a1c28d4a2ddb159fb59fc362277a354b7c419c',
    '.github/actions/scan-production-images/action.yml': 'b53dab26ae6210b989efbec5d51ad2c5943438ab4e22f0bb33f169d7d627a74d',
    '.github/workflows/ci.yml': '3b2c72c546451f1c5f09d170c0e86fb0febe30e8239b2a682ded2e8c575ee482',
    '.github/workflows/release.yml': '5c286c572df8a571198891df341c11c49f049b2c862fa8449e17ccd941fefb6a',
    'AGENTS.md': '8f35996f40e1605fbaebe3c1aea5c9298358a2102a29db6714fdfa9d88454219',
    'PROJECT_STATE.md': 'f903c4c8fcb5718444d060f771527b495aff6905c78bc84305c7f5049ed1d556',
    'README.md': 'a7f0bc467e7ad7a2c1017ee16ca6aefffb38534013cf482c707da37b49d9dca2',
    'android-native/app/build.gradle.kts': '670d78a194343f219c0c384cf415850c5f9b79b2b21f825daccfbdba288ea06b',
    'android-native/app/schemas/cloud.dcompany.erp.core.db.ErpDatabase/49.json': '2413682411447dc1e2e592dfb76bd6930777d562195cfdbee4f9cdedc9cae907',
    'android-native/app/schemas/cloud.dcompany.erp.core.db.ErpDatabase/50.json': '1d72e3af8f5b966a7716827e5b10bf2f7a0655c65f942170c5b5b50c7133b43c',
    'android-native/app/schemas/cloud.dcompany.erp.core.db.ErpDatabase/51.json': 'a4595ce62288cf3134a9b41c86e8b2286c95198ec5fd25b87e78b524c1dce968',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/auth/CacheIsolationRoomTest.kt': '094ef0cec067a8dee1003ace2e1a86389b588e796d8c127803b6b03b62dbbb64',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/FinanceOutboxConfirmationTest.kt': 'd11c2358ee46d7257d1399d3a323b725b9b283a1f0355a837789646c0f79f214',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/MigrationTest.kt': '2089676b2851ec8b74cb9ba772e026617d9d092482584e4a15e3e6db4c6a2af1',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/ShiftCloseSafetyDaoTest.kt': 'ee3afc90b89d080a2889a00b88ae57ba7a53a3144162fabf821f7f24c12a9e5c',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/ShiftRejectedOpenRecoveryDaoTest.kt': 'af83b4a12d3f5a3747dda1616b2e882b85fb53389a8993a6fe489f7e925409b8',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/finance/ExpenseReceiptRecoveryUiTest.kt': '0a53aad7dddb9c2e707441246ab61574ed865c67aede6cb0059b27d6f4368d38',
    'android-native/app/src/main/AndroidManifest.xml': 'c4000f524a447d72bc7d8e01df58a3959ca4dcea390795264fdaa306aa9947d3',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/auth/CacheScope.kt': 'ba4c5dce0016b9dde01f685cd18102d8b7eca13882fa8cd0c2d447a93fdb37dc',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Dao.kt': '7fa9b9e7bb425cc3098c942d1a0224f4d3d6b0cbe89bf99a6d27765cd014aed9',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/FinanceDao.kt': '8132f786d1f46bbdd5905ffe4062007a460c2355f5f19c3bd2290a75c2e1b76f',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/FinanceEntities.kt': '825afbe6af3694b15ddf1f2d28ed9715d272552032597c77d37469714e22806f',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Migrations.kt': 'b234dd77e048f3668a80c68c63162103d9ad3017fee78471830a86315e173e2f',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/OutboxSafetyDao.kt': '0c564fdf7a0f80d10a43ac37e0b1d4fce4887caae8203f2b29fcd5be3a93d470',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/ShiftCloseSafetyDao.kt': 'dbd9b6e72b9924b5517fc1487864eb5b36090bcb1608a5bcd54418646fb9ced1',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/ShiftEntities.kt': '1db400d4c77007f0aa9ccb66a637600c4874de7b94d6c36ca25239dd7631ebaa',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/diagnostics/DiagnosticRuntime.kt': '9b5c0c5016dbed2849ae27d42790f22d1a340d19d84f9aa37972fe8cc64bcc5a',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/sync/ShiftMapping.kt': '566d4b71300d912723051d45bcf843232bba5df045fb7f49403768d4a2af28cc',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/sync/SyncEngine.kt': 'd78000cde7a17264cc2a15e73dd5cac0a45968b3f7299bef7b1bcb31f7e8e2e0',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/CashExpenseShiftPolicy.kt': 'b934dd5678e8ecf05999c84b0b2a54dbd415879df9c95ef8053916ecd9accf78',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/ExpensePaymentPolicy.kt': 'f8bcccca22bc564c27fb081c13b0ed09be6c334b3970b4f792faadc64ee789b6',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/ExpenseReceiptFileProvider.kt': '8f30a31561a1d32b21be8a6f1fa81e0d2bd3c5ca63bc24bda415ca6c2caeca89',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/ExpenseReceiptPicker.kt': '72a025f4c3a6cf0f9f73a4f51ce57e71c4a3f49b6e1d938bfd19854a6c68ff21',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/ExpenseReceiptRecovery.kt': '5bd22166057cfc58bf7b60148c0c9e1ce54086cc9cb6ae95b676297b09f97f28',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceApi.kt': 'c95464b3fc04a59e6fb3dcd3ccf6b289446468dbaa726ac7a044bc05e5b045c9',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceCacheMapping.kt': '44b9566e178362e9a45ac53ea34d21a67e1a89192f8baee2953d8a47ef0692eb',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceModels.kt': 'a66ca5f75c5179ec3eebf74257cb0a95eca299c39078cfc28aee0a719386ea36',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceScreen.kt': '7e35db04d4076fec0fd1ae04a92eaab24d7c735fad10b24c3ca5c60111c0ad6e',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceViewModel.kt': '2712bdd41d9fa26820139a697a1bfae4dbb7a62f89c1b40f97b5e7c2f6b55f08',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftApi.kt': '99ac35124372bf85e92e13a7a2f9a1c3c090797b38f7b64618c45a73482e9a1f',
    'android-native/app/src/main/res/xml/expense_receipt_file_paths.xml': '60aa51d686b9b96b1787aecaf0338737765e0c25b4658825d8ced4ee5f9b2b13',
    'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': '3a581f59363d824cfe3a095906e2b29b98d8d729202f8754af6e41928f4c2503',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/auth/CacheScopeTest.kt': 'eed46c2ed750d5698e34a4aea2c78f487932f3dd4eb4e114fddf1e99b1536624',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/sync/ShiftAccountingContractTest.kt': 'c4c72b9cc6790f7ef8d0e1083001f77ebb6a16a673ac21eb5dd70c43d5502f9a',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/CashExpenseShiftPolicyTest.kt': '2574bbbe631326280e899ef86496cd2d47133041869cbe64f2cc649a71b59294',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/ExpensePaymentPolicyTest.kt': 'df5bfd67bc909b17dc36fd3101afba060b4e504ef5d29348863278b27e374799',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/ExpenseReceiptPolicyTest.kt': 'bd22b3e304e19639b5726a58e8413e064c46564879ddaec2e05083ac4b37ec5a',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/ExpenseReceiptRecoveryTest.kt': '5558b10d75ad4b5908545967c28674ad37b78cf0ce5e1312ee327b4edca54a45',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/FinanceFormBusyContractTest.kt': '3b0254bfa48c55b375a61d685b6bcf786de677c687246ea1552c7d99183af9ab',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/FinancePresentationPolicyTest.kt': '1acba44fca0131c0f64a236474ef1cfc29ab266b2923f1ab02acd9ac7faac75b',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/FinanceRefreshArchitectureTest.kt': '312c7a28dac459ae58c0af624b0791c3210ab5aa80c1b3d3aa3e3654b29f5e85',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/FinanceWireContractTest.kt': 'b79a1edf5e81321b48b2a58975fdb5e0d0fe3637bf1cbb9a03e7b068f7954f97',
    'android-native/audit-driver/plans/code26-gaming-finance-physical.json': 'c5657ca804eb994724d0a216c3cdf789e4aef074a2cd3a1c203003c8d71b7bce',
    'backend/alembic/versions/0074_expense_receipt_evidence.py': '7809ee7ce3bf3b417e6315a0818aa1ea491265fb9d347f5bb48053a182b72835',
    'backend/alembic/versions/0075_google_sheets_delivery_outbox.py': '5afb8a1ca5b0f4e01cbcc195455b1dbebd7099f47c428e62ff81f506b7e2254a',
    'backend/alembic/versions/0076_google_sheets_mirror_config.py': '0d997527fa19142f3a22196f9ba0c5d3a47041e14d7de99f93f50b0034ba2772',
    'backend/alembic/versions/0077_modern_cash_expense_drawer.py': '64eec1b7d6f803c40eb972ae4302fa30dee8283736b5f3804a818a54b00bcb09',
    'backend/alembic/versions/0078_finance_cash_source_corrections.py': '92b138402555bd456c12a44755dd2253d41d0e43b68a47f7612b4c2a28dee902',
    'backend/app/__init__.py': '3a2d88986b5ede4cfbe3815d507473de0906948ad9e83c44263df57a821756cc',
    'backend/app/api/v1/finance/router.py': '9b7b1261967e6d7f71b6a3c737a842050654069c16fbd834d89474e5313c1f54',
    'backend/app/api/v1/memberships/router.py': '81010109cf22243468cd89c46fd42548d71a1cc46c4b9b94a272b20115dab41c',
    'backend/app/api/v1/pos/router.py': '08e592d0b8b3383ec0150a87d14b1158496c90d983e1b9da9e39e13b4b56019b',
    'backend/app/api/v1/router.py': '0f0e7d3373440f6640ac97e9d3e4d65b55b8d4c5f36f369ac75a7128d0932184',
    'backend/app/api/v1/settings/google_sheets.py': '06f1db71cad62abd0e6c0431f0856faf228948cba56639b43207f84cf95d8fed',
    'backend/app/api/v1/settings/router.py': '95b5dd0b65b6ee41e0c253ee80477452a19651698914c6f5660ef271c2a5e205',
    'backend/app/core/cleanup_replay_fence.py': '98197540c36d5b7e41011dab31c505d40dde254d126a07e32d5e89c4a88da6e1',
    'backend/app/core/config.py': 'aa711049503fe97da5a0bce887e574e11cbd6bccb8d98709e6abc0f206c01a90',
    'backend/app/core/idempotency.py': '0344bd3214d992138ce41d739c4f368c54ec64207b5e23add533c34bc9060466',
    'backend/app/main.py': '79971c4d9a947b3faf460100ccefe6e46240aa9d21844478189eab8dd7c5c8a3',
    'backend/app/models/__init__.py': 'ec878311f61767145e0aef178dc63fbd317d92d960412098793fc823f1ab6d7d',
    'backend/app/models/finance.py': 'db63f098495f232968e67ceb33df9ac29592eac6c3373af81df8bd0db5e8bccd',
    'backend/app/models/google_sheets_delivery.py': '6796bfa531bc11601123c8b1df5a0efa95f1cbf427fc7efca5851a666687f972',
    'backend/app/models/tenant.py': '38402bbb35fe340e4c208719a39428ee47d188e803574b6527fddfd93416653f',
    'backend/app/services/accounting/ledger.py': '79fda3b6141d9f3b23cf047d5464602dd410c0c2e2c63dbb61a8100ad094db93',
    'backend/app/services/audit/recorder.py': 'c4ec6727c898c523fe05965f16ffeafa4231a13e6cf493993d2b9af3fc665380',
    'backend/app/services/integrations/google_sheets.py': '28f84828deba7cd9372234ca9856da0dfb1c2c16f0adce1fd98801a15f42472f',
    'backend/app/services/integrations/google_sheets_mirror.py': 'f8dec72035821b7f2fe1628f921f43112687a44eca55f0b4fff0ca0f9bb02f3d',
    'backend/app/services/integrations/google_sheets_runtime.py': 'ec46a14d7f9751e8f1108e4ac068bb6e50178f3f72301950e43ffddd707c7ef8',
    'backend/app/services/integrations/google_sheets_secrets.py': '07063a73737e9f09194af502145e8dbb90de15859218a00eda355969ecfe2940',
    'backend/app/services/reports/aggregator.py': '8badfd44ddd7c48dc044b71e1628ba46ec39dc318ec8671f5a988bec2fbd8ed5',
    'backend/pyproject.toml': '27c2ecb01e484af01a6d044b8336be8a98ec0793ce0297b2889df6c954db2955',
    'backend/requirements-ci.in': '6fd9e8fc806692da3fc44f64a14cdaac29be9084d03b1bc6bd93bae2f65f9824',
    'backend/requirements-ci.lock': 'f77e16af56632cecac34a6e2011f8570b0fb05bce8caec089272a5a4e57a8fe9',
    'backend/requirements.lock': 'cd319d2a4dc1c29497d7470f4cc8136a126e44d516b731873dade51c6b2d9b81',
    'backend/scripts/physical_audit_fixture.py': '6ec73f1155a8a4e7a8026325420393dc7ea65eaa6341693cc7fec361e86eedbc',
    'backend/tests/integration/test_captured_shift_opening.py': '1ba2b8ef28fb24ee04802822e616827199ffc2e3f3d7adf43a4f6083bf560406',
    'backend/tests/integration/test_code21_cash_expense_compatibility.py': '68bde11fd81d68b0b528183943742f9cf3aecd58c0c79ea20e2846f595bad111',
    'backend/tests/integration/test_code30_finance_migration_downgrades.py': '858ca43b66e99db731ea1d32022e086868c16fa4953c5035e35d91a134d4567f',
    'backend/tests/integration/test_expense_google_sheets_mirror.py': '44abf67ff4854d440be616aa837642aeadb86ebcba9137afe3788762bbdddf9c',
    'backend/tests/integration/test_expense_receipts.py': 'f360415385d7d5daef970e7fec600c154e710225ef9bdea7bfffb4e7efeefe7b',
    'backend/tests/integration/test_finance_cash_corrections_migration.py': '74cfd8cd36f218b07139a1ccf5049ea97d3e3db1c400c3e0be91860048417f3f',
    'backend/tests/integration/test_finance_google_sheets_events.py': 'a0719b4943e3e261f588e21fe29933d2eded6cb457432a75af68dd7e3bae816f',
    'backend/tests/integration/test_finance_source_integrity.py': '2e132bf1b672446f0621b2d1c3f0f1b1f276779af0deaac9ec7fe1f7ee74d74b',
    'backend/tests/integration/test_google_sheets_settings.py': '76bb0937c9ab57d4df7e07a86f9910b7109642597ec9270967ca000df9f636a8',
    'backend/tests/integration/test_inventory_purchase_accounting.py': 'b667ba256fa4119f5a334eb3b8af5f7f54c5da283df7c4043b362ceb9cbdce4e',
    'backend/tests/integration/test_membership_migration_downgrade.py': '4cfc5a04e1fba35d57af10d2f0b8037e2350865fa5fb7a601bf7b13c2ebce78f',
    'backend/tests/integration/test_modern_cash_expense_migration.py': 'e4205c36814dc532bb69df446c307e8f76eab61e0297c55be571e4ad6d54832a',
    'backend/tests/integration/test_modern_cash_expenses.py': '59dc6cb54c042a8611a12ff9b1730dfd8202d6cc5ce1acadfda9b5372357a4e6',
    'backend/tests/integration/test_pos_refund_migration_downgrade.py': 'b7e3d6e54279a9a1b86c8f385f474c320fd0a562998f78a2c56e5ca7cc6d2638',
    'backend/tests/integration/test_production_seed_acceptance.py': 'ea7cca2c614df9b07af49a84237a17230c4267836b987798f94a18f4d6faa5df',
    'backend/tests/integration/test_report_branch_source_integrity.py': '2f68c8e5815a5b0a30bdccd342eca81a2cc25a6c1f89a92e9695f6ded58898fc',
    'backend/tests/integration/test_tip_payout_concurrency.py': 'b653d351d4c86f5c85752fbc41a24549a1c4514d39c2d010fd0d3dbd1aad2bc0',
    'backend/tests/unit/test_accounting_provenance.py': 'bfdba1611c72bff1e6da037d77d0cc55a93e8c22c3321b1894438b75134b3e8c',
    'backend/tests/unit/test_assets.py': '4d543c1280926c5f41caf9e3f00ef4cb1e975696a434fe9f45864fbf0c63db63',
    'backend/tests/unit/test_audit_secret_redaction.py': '101f6452b0891df196d9d12345643ab57ddb44f30567dfbc1540d0bbf8ef8a0f',
    'backend/tests/unit/test_capital_entries_idempotency.py': '054ff548682988c96c00b9916e5dc160a205e0ea1456bcddeffac1528536b903',
    'backend/tests/unit/test_checkout_claims.py': '40f07f0965bb6ad2fa201348ca1e6be6a2cd8ec13e8ac07c457d548be73ccf36',
    'backend/tests/unit/test_cleanup_replay_fence.py': 'd8df1132b84c0e5f66232f707e9c12509d58113e921c43ad71c6b8760752f81a',
    'backend/tests/unit/test_client_compatibility.py': 'fecc5ce4dc39d75ab485a4962f3c2c971aefdb585f043bee8db097302397a809',
    'backend/tests/unit/test_depreciation.py': '8370f2966c898f0d6c79d15ab67e86f90b058a5115925c108e80deaf8999f4c5',
    'backend/tests/unit/test_event_ticket_report_integrity.py': '0c77c9d5d1f7c1c458447b8c9144b4c370d6a0de8cbf7f0f8d68d55590e48210',
    'backend/tests/unit/test_expense_receipt_files.py': 'bec15e1718f4aa3b0361d3dee26a5ade57fe2d7130494ca357e728f6dd6d910c',
    'backend/tests/unit/test_expenses_idempotency.py': '7124464e657c889459eff66d7f195b0ee37ab812b5203f7071dcce05cc5b0999',
    'backend/tests/unit/test_finance_branch_scope.py': 'c181de828a1bfce3a259897436c0c77825089adcd326e7a2f587edb98f72342d',
    'backend/tests/unit/test_google_sheets_mirror.py': '2eb847388cf602615c601b4dda1bbe7bf92ceb90fd96e183fc088f10c7fcddab',
    'backend/tests/unit/test_google_sheets_secrets.py': '431dff200e05f859415eea6e7aadba9e3b0ebbbf0bf82cecd7e106491a8a461f',
    'backend/tests/unit/test_google_sheets_sink.py': '49845165e4d67d3ae2f4343a2eac94d933838cbc04cc3655276cfbd610add09c',
    'backend/tests/unit/test_manual_collections.py': 'a632516c4e6ea6aa3a9f16bdb3cf08f2ead5658ff1007d88524a27c7629977ef',
    'backend/tests/unit/test_membership_benefits.py': '151b40c9fe0feb4518dd381ac380bc828dd8c0a3ef03adf28cf12972ccd8d71b',
    'backend/tests/unit/test_membership_google_sheets_mirror.py': 'dfb6c42b3b4e1878fbf6047454450b1cd2171fda1bdf783973d86538ad02cd5a',
    'backend/tests/unit/test_operational_route_integrity.py': '24a98fd7d2ecbc46be304545877438f85bf9a883828fff6f08e789c1b1eb41c7',
    'backend/tests/unit/test_release_audit_fixes.py': 'd6706b64378c8a36884fcb5a36b7baf41fd10c2cd78c70722e95e67b3ae6f0b1',
    'backend/tests/unit/test_release_contracts.py': 'c64f20ed978d61e47cdfa999695c4b56c59486432de97059cc652ca5284d3638',
    'backend/tests/unit/test_remote_assistance_contract.py': '7507fd3dd7b5b9ae016bef8980bf1af33622c17fcd3726541a039b1ab7c04498',
    'backend/tests/unit/test_report_roundoff_reconciliation.py': 'b4cbf5e7c80a08eb55f7eae08e48a1f4a3c9efeec87fa1c26281211afe1b7635',
    'backend/tests/unit/test_runtime_release_parity.py': 'a019ae232496fdc50a4bcfd57aabbc4641824a4ae472118113b73c03eee43028',
    'backend/tests/unit/test_settings_timezone_validation.py': '06c4e48ef802e828e50c6736dec36476ffbd490f1d3286a21ddea103d36a763a',
    'backend/tests/unit/test_tip_payouts.py': 'ecfd81049f24179c7a12c412dda4ea680613dbc5cdf5c0a8a6ca39a81d87df99',
    'backend/tests/unit/test_tip_refund_ledger.py': 'e74d1326e92eda95d5e32985bb6807c651d6547ce92d1d7e4a631099f5d0fc8d',
    'docker-compose.prod.yml': '6e1812b5ccefb1699852b04d1935c4eee293b23674bcc5b15c5ca49fc94f5274',
    'docs/CODE30_2_PATCH_CANDIDATE.md': '10d78d1f46a9ccf1f8e2e62afc1ee3ba618193bb38bc07f280f9d4d09cd085fb',
    'docs/CODE30_2_PRODUCTION_TRIAL_CLEANUP.md': '5cf3171f0ee7060672c764ca14a73781061a69c384a557c44af3fdb7241e37f6',
    'docs/DISTRIBUTION.md': '353c82284e479a59288ee5a00575a6972c2a7c03b7844ef6e6c8847348645e9c',
    'docs/GOOGLE_SHEETS.md': '125b7bae2fc7f4c32f17b24af0f45dac179e4044bf8b32c8029ef781e8633de9',
    'docs/PLAY_INTERNAL_TESTING.md': '665f405ebefd1f569c60c2c5b832616b3912e84b2528296bcf4bd74a3a7033a1',
    'docs/SERVER_DRIVEN_ANDROID_UPDATES.md': 'd159094ad70327b50de834b246c05646c4d4e09bdb9b6d77100233a0c3c8ccae',
    'frontend/.env.example': '7a824bff9fb42f1c71cc1fc1d797991426ef488bffd1678973083c880401425d',
    'frontend/CAPACITOR.md': 'de6a9586ac0522b54e1530fe7a47b592cd516b2f418a6c66e6f59eb95ea253bd',
    'frontend/ios/App/App/DCompanyNativeApp.swift': 'f94b30ad03e3f24ac9bbf7e6e14efe928f86686eeea6fe69f0f2fab564022428',
    'frontend/package-lock.json': '95d256dbdab21ca635b2a78c2e6553eb46e7636dfd77eef24061c1922f41a712',
    'frontend/package.json': 'ec288274907b2751b9046cda34c26f872ddaeb5a4ebaae41c93197cc8f5bda93',
    'frontend/src/lib/erp-api-expense-receipts.test.ts': '8c107ca318b825c7aaa90019690a82bfa1a17a1192b3c96ccb7348956c4f9b11',
    'frontend/src/lib/erp-api.ts': '5710cb2e3e01a20fa8e35c7ebc8fa4a08fbf8650f753af0c2008a43a56d1f9b8',
    'frontend/src/lib/google-sheets-report-contract.test.ts': '833222ddacf09ef1bceae2c8e3f354387cb75d5d757c3f0cdae659edb9b7fd50',
    'frontend/src/lib/google-sheets.ts': '8dd4bb32eacf0c8e1f4739e675c6aa9216aa55f9cdaff3a6ed6b495216d51d8b',
    'frontend/src/lib/ios-finance-expense-contract.test.ts': '24eb80144bbf2beb16a394de22b8d06d14db3ca8a1ea2ea404a38992cd2e4fc6',
    'frontend/src/lib/manual-collections.test.ts': 'a645155eb91f2258a2e741b97f6990134a416b0100cfd7de5aade2275185e74b',
    'frontend/src/lib/manual-collections.ts': 'f267a294daa6c57038c4edf6f1cd9b7aa10f1fbd5bc23db9472d4a2461ff63fc',
    'frontend/src/modules/finance/ExpenseReceiptPicker.test.tsx': '3fc98abcd975e95f576d65efd1da07838f95cd82eaf746318a2bb2f71a949059',
    'frontend/src/modules/finance/FinanceScreen.tsx': 'a95f431fa000252df330a900ebdcb38286670af3be92bb66c2a3318195ee4d3d',
    'frontend/src/modules/finance/ManualCollectionsTab.tsx': 'cd81af5d34397f368670b53baec56316c0fef83ff282c124c0781a87f6498d69',
    'frontend/src/modules/finance/TipPayoutsTab.tsx': '6b790bcf91613fb4d18a6b1111d721f10a9c513d596227c8955f019fd8ac910c',
    'frontend/src/modules/finance/expense-payment-policy.test.ts': 'd57163996b9f1f73c744fa8ce22b032115e9d1d4b371e9fdb5208b97d81dcdb2',
    'frontend/src/modules/finance/expense-payment-policy.ts': 'daa31c555e034be7b6fc0b56513b8f91713c050bbf00d3b439881dafae65e82f',
    'frontend/src/modules/finance/expense-receipts.test.ts': '9e1f6b29d3e647079f7516baa65ca9f21f39c2c78e723a999aeb8ea9e9cdab95',
    'frontend/src/modules/finance/expense-receipts.ts': 'ff20036bd5edde4c2bb4d36d53bb389e526ad445aeb55d262fd3a802fca9aa5c',
    'frontend/src/modules/pos/POSScreen.tsx': 'caebdc75d17494130cbf99c3ac20efc88b6548e5a7c82f7884f3e8b060e518e9',
    'frontend/src/modules/reports/ReportsScreen.tsx': 'd1e6f9e6afb9c13c005692eefd1d9a75daecbe541de8f7e9ed19b3747765c971',
    'frontend/src/modules/settings/apps-script.txt': 'cbf401b2b7612b049d338a38ab4e53f0e78d7afda2d92782c3477f7b94d1f6d0',
    'frontend/src/modules/settings/tabs/SheetsTab.tsx': '7cafb1ea0fdcf193f7629dc2dda5e5302988f50390a7db4207f99c004e015d61',
    'infra/docker/backend.Dockerfile': '6ea2f833c7a5eff6f2427a433f99b447ebabd901fdc6bc077a54ae1a39548016',
    'infra/docker/caddy.Dockerfile': 'e5f0486572636a283a81b5ed2415936cd06836537d394644b801b0b9de054bee',
    'infra/docker/frontend.Dockerfile': 'e49dd25cb75dbbf962a118cd328a0d174fc0f60b29263662d225297fe843475c',
    'infra/docker/postgres.Dockerfile': '1e627f5ba7378f3d478a44eafbec6ef9c983523ce19934256de8cd44748a9ee6',
    'infra/docker/redis.Dockerfile': '58889cfedb7886bc873e41c490ab605914cc7e2a0d25c0aa8b6ff393c7a9aa58',
    'infra/docker/zlib/bbc2ccf3-gzvprintf-return.patch': '7d00ee29be5e636d30da2890961e83e35cee0b152333ecd97a46ae2e71eb5d47',
    'infra/docker/zlib/build-patched-zlib.sh': '78691bfd52786075e8b1cb18468631e19ff486d4bffeab864e3795851baaf285',
    'infra/docker/zlib/cve-2026-85091-followup.patch': '96040ee84d0d187905283912dbd3f7b66ac2033976a2ceefe9b8cca63143d9c2',
    'infra/docker/zlib/cve-2026-85091.patch': '110ff14375733173d8aa54574473424fbd7dfe4b81f1ca34a759c6fe14b15b14',
    'infra/docker/zlib/e3dc0a85-null-guard.patch': '183bc8b9dd078a41a62de5c2d905d9b0196b45bc46f100d7e4147ec228207c74',
    'infra/docker/zlib/verify-patched-zlib.sh': '3861a94f4afeec339e80370fd7e975b2e46147c163a1bcb9e780b4dc29d35f11',
    'infra/scripts/cleanup-code30-production-trial-data.sh': '5b9fa8cf3654ef31e14aa2736af979059e3255e3adb31a3525a0fb1d16523128',
    'infra/scripts/cleanup-code30-production-trial-data.sql': '262a3c25969b551b8d903ed2b098e7304c3be70deaf1fcd7c30466c11b557e2b',
    'infra/scripts/generate-secrets.sh': '51f1c4f51dd0e4087321783ba2885c75cf873ab114a92fbdc89396839bd363aa',
    'infra/scripts/generate-zlib-vex.py': '3db0ab0c518c4cd5c51da997d61b8680aa7a10fb60fd91225fd299a39bb3d742',
    'infra/scripts/install-on-vm.sh': '34a3d17aef06b85ff83d5a19157c9c48b5671c300418c1947d25f5fd6cebd3e1',
    'infra/scripts/run-hardened-image-scanners.sh': 'f08782e27aa43b7d45c2c7e0c9e00f3c16e119b255b17e9e9a8597c8d45cbd9e',
    'infra/scripts/validate-production-env.sh': '9ad57de9b9f14292a70c3b877954f01bef7e40eb5b34cbc46dec9089ef9ac76b',
    'infra/scripts/verify-code30-2-emulator-quarantine.py': '872fe1fa068de5c4f329ffaf5cc573df8d8938a72d9e370903dc5d9bf8c15585',
    'infra/scripts/verify-code30-2-post-cleanup-state.py': '656513f46cec9d4acf128a9010a7fd020ab4f330581636d019224524fc855ef2',
    'infra/scripts/verify-code30-2-post-cleanup-state.sql': '7f04d3f18c0f2813c314751636197529af904aa34f2fb49c7de14112ca7dcd66',
    'infra/scripts/verify-production-runtime-images.sh': '9602119873f07a0fa0a163eb32b3ad58806281e14dea92d2c9c5ab45a204261c',
    'infra/scripts/verify-zlib-vex.py': '6dab14083e7313cdfe0262f3daec430ffd1de17e3736d15e70a45133bcc101ed',
    'infra/security/vex/zlib-cve-2026-85091.openvex.json': '5008f90d2b7bcdedd955d30c35618aae1f2c25c81fe3190d6adefdccab882bc7',
    'integrations/google-sheets/Code.gs': 'cbf401b2b7612b049d338a38ab4e53f0e78d7afda2d92782c3477f7b94d1f6d0',
    'ops/runtime_release_parity.py': 'b9d8348e91785e24eb4e49d7c768b23f96197c978a1c62607d27afa3edcbdf8f',
    'releases/android/README.md': '790179dffcf20aa3834b85f0cf26dd0953bdeecfb19a4bb93be3fc8a4356e786',
    'releases/evidence/code30-2-emulator-quarantine.json': '379c6368936d03223e19482cc840c2a9d2483dc9a96909fba22cd9f59911eec8',
    'scripts/analyze_code26_physical_evidence.py': '26c94d9abf88cd5211fccdcf3dddd3fe16edc8a653f8c18d0f0d1ff921e20e57',
    'scripts/run_code26_physical_business_audit.sh': 'a778888fde9676de966363f6574f733e214c5d8052f26b31dd55e733bfb17986',
    'tests/test_android_release_pipeline.py': '4a42d04a662c0da55a2969e3a37ebac4556253d80880c92f8af3c91492b98dc5',
    'tests/test_android_release_version.py': 'c3b15d5ef03e2dac384dd9ab2801d9166ac3439cae0607a9d621b84ea8c380fb',
    'tests/test_android_runtime_parity.py': 'bf06d06c8673404e8b7760a0974a876ca4b8517ff898ab57276f5146712cf1fb',
    'tests/test_caddy_dependency_security.py': '523d90bd475ef1bd71e1e820f4b5053dc26f2d1a3581ddabafee39d257797b8e',
    'tests/test_ci_docker_connection.py': '617fff5d994cdfe2e41ed823c7abe2c373e9ad363d9c553535d9d4d8a5d59b45',
    'tests/test_code26_physical_audit_lane.py': 'eaa3104cbe3faca655dcf866b05008e1a8acde3d2f456c8cb3a9b99d638befed',
    'tests/test_code30_2_emulator_quarantine_evidence.py': '5612c7dc5c7b628313103eea10d127822dbe322f945241a0477e23c9ffae94bd',
    'tests/test_code30_2_freeze_path_safety.py': '2eea4de59f8e4c842daefcf60416a36b23c966a39171a6671a4e80ec642ca22b',
    'tests/test_code30_2_post_cleanup_installer_guard.py': 'ab8f1ce3f7885038088a1d24cb4725eff3fac0ae2c996e11cbae58d86506f8e4',
    'tests/test_docker_context_safety.py': '26d848d57fb31e026c4f1c1130dcdee13dec71e037ab51af1f2c4697393ab390',
    'tests/test_hardened_scanner_runtime.py': '5efeeb8f4b514ba593a3dc5573877689df6fb496e5cf6a620973b36ce0eff1c6',
    'tests/test_production_installer_safety.py': 'f56fe08679aa450551d76f4c93399d28be04321d8f3aff2b66a67d542d05ea88',
    'tests/test_production_trial_cleanup_safety.py': '84acc4184f901cae6f1a0f2b6bcdf94effa4f4c655a84124a192f602189399b1',
    'tests/test_zlib_security_remediation.py': 'e44842f9bad30b0d852bc88229537163ea690b71b42f33637ae5ec97213a081d',
}
REVIEWED_CODE30_2_PRODUCTION_PATHS = frozenset(
    path
    for path in REVIEWED_CODE30_2_SHA256
    if path.startswith(("backend/app/", "frontend/src/", "android-native/app/src/main/"))
)

# Generated only after the complete Code30.3 source, tests and documentation
# are final. Freeze-control files are deliberately excluded to avoid a
# self-referential hash cycle and are protected by the exact delta inventory.
REVIEWED_CODE30_3_SHA256 = {
    '.env.production.example': '2d27c01669a55f951340e0b6396a2f8f21e0c3135ce9095c2e417bf0148fbc2b',
    'AGENTS.md': '5528afa7ed65d18289156759974694c82b1de9d6eff5921d917d7bc0b14d25c8',
    'PROJECT_STATE.md': '3374b2bd03ca9583eae66db807735c6575d0da84649f60a32a26c65423379a39',
    'README.md': '4e4b7aaf033df3b479e8aafad13778731ceb2849d1403b72df2409345fe60f80',
    'android-native/app/build.gradle.kts': '611f1734facfa6b068f2c67d6473ee5d30f2b4f5b0cda78399e6ea9fc39133a4',
    'android-native/app/schemas/cloud.dcompany.erp.core.db.ErpDatabase/52.json': '9a8fc37ba4dc9af70ee71cf55571d7bca85a3b2a9230de44df6375dd9766bb19',
    'android-native/app/src/androidTest/assets/cloud.dcompany.erp.core.db.ErpDatabase/52.json': '9a8fc37ba4dc9af70ee71cf55571d7bca85a3b2a9230de44df6375dd9766bb19',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/GamingCleanupProtocolBridgeTest.kt': '32c951d3ce7d350673f2f7c5d92e54d8f65a644d8e1730fa9313b05bad531779',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/GamingDaoRecoveryTest.kt': 'b8722b402c648256994c743be1b29d8ca39194ea47a027fd0f6a216dc0145dab',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/HeldOrderDaoRecoveryTest.kt': '5c79c91383bad93ab310ce972cb48febaa0439a8fda27e30ab426e2cfc4e2346',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/MigrationTest.kt': '33a66476e72ec8659a99e6c4671d3fb68cf9707491ec4656dcaba455205f8e25',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/PosEmptyCatalogueUiTest.kt': '69f6cec4228ba56eb08d11aea1bb5c10ab55c777958a10964717494cf0af3a06',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt': 'd3337be5d19a43fa68e43f89fa285a8fbd85a66ad1e4def7dbc050d5951e7127',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/inventory/InventoryAdjustmentImeUiTest.kt': 'bf229b13147fddeda84ec1875331cc2a5eb79db027067621f8ed3029ca7c9c4e',
    'android-native/app/src/main/java/cloud/dcompany/erp/MainActivity.kt': 'ca1270de27bcb1f554451e05bcc05ee492ee5a725b5eea4388023d8a9261a881',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/checkout/SplitPaymentPlan.kt': '0e1d78a34f3ff09a6cf2a8b508db8c8e1bbd838aaf5d85f24ff745e2f8e9fa1f',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/checkout/SplitPaymentSettlementPolicy.kt': '69cc13a137b26f11e1a7edec9ccbaf7766f34c988c02e06da21294b548595a8d',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Dao.kt': '925196944d0a9f8e7cc99d3bdb24a6e95caa9180560d6fab87f080d821c8ad25',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingCleanupEvidence.kt': '21efe186f6f9ffb98a3bff34deb83fa262c31712ed9044d76f4ee36cbecdb72b',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingDao.kt': 'a0d481c51201b54b105a17b4ab9c61034cc3fbcd0c91357f117948b860df8537',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingEntities.kt': '06d3b4e09c9191fc60a9bccdc510a065dab6517ec9372496a849dda66582dad5',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/HeldOrderEntities.kt': 'f0d2d25b0c8e04f2ddee38fa422e9f08bd73ca54ab5d432884a75c6959aa2201',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Migrations.kt': '6fd389016c4de445eb82f188a9e7a797b06d0f5de4164930d149b4af5379dd4a',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/PosReceipt.kt': '8e42c994aa69dfa5017cd26181a05ef024509fc606bd6e8d2ffbd1b84378d505',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/ShiftCloseSafetyDao.kt': '9ae0b93167a170f4af513a58eee18eab5fab5863b85d0f3a37e5844b702e0403',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/ShiftDao.kt': 'e766cf3ecf76d75b917fd6ee7bedc8432a067de8eb537d1574af9f48423ab143',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/net/ErpApi.kt': '0f513ec44f128f165df45ea57a920e12cf349cf7ce3c789dff62bc1ae20a5de5',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/net/Models.kt': '5a5a862f0c897442620a38f8497431b3ddd6be21e76701cd87af607090b7a426',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/remote/RemoteAssistanceCoordinator.kt': '41a20a28b2d21ee4757cffae3bc02d6648795beeee0b3fb143b658f4c28ae4f1',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/remote/RemoteDeviceProof.kt': '292a73ec519ab4ab438845b1fe91e599dd9425a0f055052134105b9be83eb601',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/sync/SyncEngine.kt': 'e7fa16020cec907fb324d48e4dedbd307ec8aed93ec73163a4c507a1bcf83420',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt': '2ce7aa5de8392926f5027b818a8cc0ed373fd0d6a1d2dfa46e5a4eba9e312e1f',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt': '1158e6cebb252e3f4be6748568e6ba58b23c4f9b253cb44f801682f1ada8ffa3',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingApi.kt': '9af537642c480638a2a35859af0725277b30af322573c5c00163fa1ef8f0c1b2',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': 'dfbe430a5d3319d7f9b12a75e894365da6416b7f97b050ea4c6d37bbc946f231',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt': 'f14fccfa5069c88608c1671ca589f735654b87d9ed2fbb40eda28fbcd4c537c6',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftApi.kt': 'c89e8ed00bf7afc51c3128c22a5de80bc0c5c0c90b0f9cb0c77cda09eebb456c',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftBusinessDayPresentation.kt': '913e869598145c737f1dfaac27388247c4fe99cc8d1fd1293031676422a57eb5',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftScreen.kt': 'afb82838b6e5f78e0081b57f1580e90353f09386c8f1b5065c3026fcac58424a',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftViewModel.kt': '7d0514b53890903cbe94146bcb997ce2a99038ae28826a64beb8306750263c0b',
    'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': 'b79ab5ce6af73a4dde844492d2c18edb9980ded1c97cf50874d074e857564f96',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/checkout/SplitPaymentPlanTest.kt': '8315a5be264da56d18b25cdbea4c75a45f303fa31190115c432dbf9355787d7b',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/checkout/SplitPaymentSettlementPolicyTest.kt': '9af9cc97dab2e1ecf109171dec01b54fa1ca11196e89e97ece8e17c3d4b5c4a4',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/db/PosReceiptMappingTest.kt': '882bca6613ff4fcc77c1b43fa618c628b852e9f308edb05bb7c72190aa1b858a',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/net/PaymentBundleApiContractTest.kt': '741ef9187d31e3db52ea67327885c218313d06d1ffc57c029f84766942555a40',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/remote/RemoteDeviceProofTest.kt': 'e438561899eae8fbc294091c53cc91ca6fd621db250cde9e3f5656b35ff7f3b5',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/sync/GamingSessionResolutionTest.kt': '681fe07ecef67f933460ce5228489157d39126e21bef4d63919ee14d44cb811e',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/HeldPaymentFeedbackTest.kt': '8537e40bfc24bdb54109d026a7b3fe13527afc5b94f749764aa5dd8528ed4971',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/PosReceiptPrintTest.kt': '48d1d96c46cacdfc53ec513f39bbbf399e804e5de2d2604cdb7d75335f69b211',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingStationPresentationTest.kt': '28e4cfbedcf4c423846b2a9ff6bd02909af65e6b9f2b04f798c6330f992d162d',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/shift/ShiftBusinessDayPresentationTest.kt': '7f9549a377b607336848979fc5c6efc088ddf62567e9e133464028ff1c984cd9',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/shift/ShiftClosePresentationTest.kt': '6f63ecc420822b8fbfc030311a817f4f8cf0f8229ca48d2aacd6a7a437c48d5d',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/shift/ShiftResultDismissalTest.kt': 'ed666a1689bffcce9069b50f3811890d4c58f6b1e29affff30da79d6ca0b3a46',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/shift/ShiftStaffFeedbackTest.kt': '196596b3c56575e3f7b7a05c57de77097d9570f80831d1debfc60cf4285f8694',
    'android-native/audit-driver/plans/code26-gaming-finance-physical.json': '40c4177e7d65d53dc8bf83baae58a45185c3eeb755de3225a622b27ca62cf6ca',
    'backend/alembic/versions/0079_client_gaming_cleanup_reconciliation.py': '34a6e9c2e3be8bb97caec261fb116bdfb7ac88a53f2c7be0092a03d0b97508a6',
    'backend/alembic/versions/0080_atomic_split_payment_bundles.py': '9d69cf68e9b8df826683d8b0b1b7f34bf3479efa61d8e75befec6dcd076bf799',
    'backend/alembic/versions/0081_gaming_cleanup_report_app_identity.py': '4510f6ff361364cdb6a25f33f20efe5bf661d6709ee9ed5d074e21b01d4823cb',
    'backend/alembic/versions/0082_shift_opening_clock_skew.py': '8924c012660a871a65de0135b22fe0247c0f09cfd299c8bdd7c9a928ef74cb5f',
    'backend/app/__init__.py': '25136e08c5ccf41aa0de1345e00f459dbf4bc416ea3fd8dfaf0264b4fabb24cd',
    'backend/app/api/v1/client_diagnostics/router.py': 'f194c83e0d7411f3aad09ddd0175c0fd57388116950a27d62614befd5e4252d2',
    'backend/app/api/v1/client_installations/router.py': '8e9799a85429e9358cd7ba11bb6447c7978fd3ec29de84ca87530cf51c42fb0f',
    'backend/app/api/v1/pos/router.py': '4ae20b886dceb6152f7729868584a8fdc726c17154569769894deb79096ac0a3',
    'backend/app/core/cleanup_replay_fence.py': 'dadfb679fdef79ac6817a40b3cbd6b5b54b56a340d94e1fc051e5465d63881d0',
    'backend/app/models/__init__.py': 'b9992ad9cfb1fc8559d628a57b656407ac5623903fd8dd5a9c6fb8bff03dad66',
    'backend/app/models/client_gaming_cleanup_reconciliation.py': 'b775e0056f117bf32dddff080dc68fe65141c73d0c07af7b7605840f53534339',
    'backend/app/models/pos.py': '7d27eb13cd5b86b0e7d32367811e48465ba34cd0e26f48312446ea35d7ae4485',
    'backend/app/services/audit/recorder.py': 'fc67db3928051f034fc1a349fd7f5578d0c46a31461309876a5ddc65cf4cea06',
    'backend/app/services/pos/shift_capture.py': 'bbf6c2a6ac6d9e46f8efb3af667b6bcc902389d9ed3fa13f6edc24d66383153b',
    'backend/pyproject.toml': '34356d2a7dbabf09381284a9a1ff33e42ceceec670a699e56fde51532dcda544',
    'backend/tests/integration/test_captured_shift_migration.py': '465bbe56fee84ea25f9532f7fcb61dcc8c0ad41503d78c6ad67d5e119b18f4ca',
    'backend/tests/integration/test_captured_shift_opening.py': 'b7929678aa8de871f3640759900ed0cfda62bc0c9ca23b5fabe91a73840fffd3',
    'backend/tests/integration/test_client_diagnostics_system_health.py': '9f6cc99dd3c146e9a84e2e90304b451f2907c6baad66f7f674653a2a76f4c57b',
    'backend/tests/integration/test_code30_post_cleanup_state_query.py': 'fe01fbf1710a5c5649768f9f2f782f075931766e0a3bf3d408e1b95271e6a02b',
    'backend/tests/integration/test_gaming_cleanup_http.py': '394cbf4e9eb843b7188f3b8b9c86ad4df5f06bd15c81cb5151d4cf5dde601c44',
    'backend/tests/integration/test_gaming_cleanup_migration_0079.py': 'dfa2a503d22ace6257f9bbbcca5acbec2d9ab4f2d06aa9b851969bded60d9d73',
    'backend/tests/integration/test_pos_split_payment_bundle.py': '3a04b5cdf86560d1f88d3e8dfd0969bfb2f1af076999a31a5446f1c3ea9b211d',
    'backend/tests/integration/test_production_business_quiescence_query.py': 'af021ac7966adb994cad6639872e59c70affebf905b4e6483d42c254f599003f',
    'backend/tests/integration/test_remote_assistance_device_auth.py': '76194b0ab5ac3dfd540dbfeddf955e5fb7e0524215b96471042fdec5d824b21a',
    'backend/tests/integration/test_versioned_cleanup_shift_replay.py': '52a37cadcf6b9394d0646a8d2abe65866c7476e07690a0053974a1055f9032cd',
    'backend/tests/unit/test_audit_secret_redaction.py': '45f66df05b6b7b7137c0b294fd4bdbb49cbc24f95d9c8860525345174ebd6c2a',
    'backend/tests/unit/test_gaming_cleanup_migration.py': 'b3dbfbfa7c9b871b5f251c310aa931a56b06131d113a3e0468a82f7d74b494ef',
    'backend/tests/unit/test_gaming_cleanup_reconciliation.py': '3b8365a7a5c7c927ed5efdcdb143265093062a8eb82d981fe5895f9c35094afb',
    'backend/tests/unit/test_operational_route_integrity.py': '6dd18e20006c99d244669129317424070e5c8aceadaf084d5116d4d9a9d04883',
    'backend/tests/unit/test_pos_payment_contract.py': '711f9186f2077a7940108313d2d65b98b61cdd3bdf1800838370cfddced45732',
    'backend/tests/unit/test_release_contracts.py': 'bab77017922c612b39ffebdfcd0d170abd761e0e606f961ba7c89f19d9f74664',
    'backend/tests/unit/test_remote_assistance_contract.py': '10a77a261b70a7d7cf3fe9dc9127973e2b3f9087170d0e8ee03f28b37dc0f251',
    'backend/tests/unit/test_shift_capture.py': '377943070b70dd736add1b6d90f2b6f37d551cbec36cd9c20f02a3b46eb01fff',
    'backend/tests/unit/test_shift_recovery_scope.py': 'e3246be8d42b0145369f5c271c4544ed40808969ae36134eaf25517ef4ee2782',
    'backend/tests/unit/test_versioned_cleanup_replay_fence.py': '2c54af9f1d18436d6026a385a3260e87a65ec3954fdf2b6e18b77ea45a41b4c4',
    'docker-compose.prod.yml': 'e1792fcc15346bbc73cd13d39e38421bdc0f4ae8f0157744f430aa0b6e6bb8e7',
    'docs/CODE30_3_PATCH_CANDIDATE.md': '78e4ebc11df6cf4a53365db86381d6cf9d27c8339ef7a39f3b0f18d300da11e7',
    'docs/DISTRIBUTION.md': '9685e5e29ef6985db194cf574bc9cf47473728d2f276d73ab05dccee8a570e46',
    'docs/PLAY_INTERNAL_TESTING.md': '746be6f15eaed7e9c90d6263cf9924440cba5a66fa6366498f5f4fd5cc808a84',
    'docs/SERVER_DRIVEN_ANDROID_UPDATES.md': 'a139651ac49a994abaffc789c3987d9eff45783eec2611f40d639cd7d77ee4d4',
    'frontend/.env.example': '125f63dbbb4cf54bd48dc617fd39e8b6721c9827fe2194f0a5fca7ef8cfc110a',
    'frontend/package-lock.json': '6591b6902f3b8603ee1dfa70c9936e6c2622425ba0d00f8cc053b3fcf05f556c',
    'frontend/package.json': '057f99c90de1883555f99a17a3df42c6342c65cb296e72e46582efda6f7dd2cc',
    'frontend/src/lib/erp-api-gaming.test.ts': '4b6fb01291375da34043161fd980f745ece636030926839367eaf3b046e658d4',
    'frontend/src/lib/erp-api-pos-checkout-claim.test.ts': '468f46ee98f8ba58b5ac3b9b7995bc99e4bdd9acc8869bb7bf5d9d4d33247b15',
    'frontend/src/lib/erp-api-shifts.test.ts': '29bfc2781aeaa562c5b0839f42f2f1970011a39db3827d259ba7377e9af83c9a',
    'frontend/src/lib/erp-api-system-health.test.ts': 'f5b6b5f266922c4ac322d100e9cb5656c5ac4059dd337494bcdc69f549c20734',
    'frontend/src/lib/erp-api.ts': '915b936e8b8e61f98f96b22f2e898e2fb45928e26cc8437952fcda85cb60eb80',
    'frontend/src/lib/retry-drafts.test.ts': '4f98bf6c0e63ffeb98b852fba2fa1205a885fe6e15f854b79978bdeb4f3ea9d4',
    'frontend/src/lib/retry-drafts.ts': 'd826dc9138817eb26254c8ad89018bebeba69117efe86992b340c1119a4212a7',
    'frontend/src/modules/gaming/GamingCleanupRecoveryPanel.test.ts': '3f2acf1bca90afb40a7928f8bedfdcc63b684fe1f4883e4eb590166c8dcd02c5',
    'frontend/src/modules/gaming/GamingCleanupRecoveryPanel.tsx': '84ff54538f91b65929f8a8cde36a4f904d19cbd5a5af63812e65f5c59fc46067',
    'frontend/src/modules/gaming/GamingScreen.tsx': '310f7da1f872f1328df2a5ff10251745108662b995f986e669ef3dfc875b81e0',
    'frontend/src/modules/gaming/GamingTransfer.test.tsx': '9b0b701fbd8dcfadf1e88a4b0bf0f161eabe8b64c516ba511e85df2d6a3777b9',
    'frontend/src/modules/gaming/GamingTransfer.tsx': 'a908696fbe607a38bb54c4722d6a37cffcf1dd0f6bc84ed83759b7881288df71',
    'frontend/src/modules/gaming/gaming-reconciliation.test.ts': 'f6aa7ecfa8ac40dfcd98acd55970e873325931ceb58ab0e9c89ff2ef77c5ea24',
    'frontend/src/modules/gaming/gaming-write-access.test.tsx': '3f3d4341b789c8aaa57963585def8c33d558ed83b231f1be24cdf505e9ae02cd',
    'frontend/src/modules/gaming/gaming-write-controls.tsx': 'f608397870270cae35f5b981c3cc3996d72c9f7e589ecde349f25b177b0ac1b9',
    'frontend/src/modules/pos/LivePOSScreen.tsx': '085fc3a9cd694e1166aa2d91ec72df229b80cb61a0b4b90a5f8137501bf7784d',
    'frontend/src/modules/pos/LiveReceipt.test.tsx': '97c23fe5f104f55f0be70d71aa83b5056bc509d76a9f81c5e44838478f8a1a4a',
    'frontend/src/modules/pos/LiveReceipt.tsx': '3ecad94760c70d876eca712947d441b6c7ffe5e483b7ab16e6945b8d0918decd',
    'frontend/src/modules/pos/OrdersAndShiftsScreen.test.tsx': '43d0c8eef53bb81f718e2cddcc451ac846aaefa4b10b32e702b0f9be5c1c306e',
    'frontend/src/modules/pos/OrdersAndShiftsScreen.tsx': 'd8a0be1e1d1d386092af2dee59fcc28154ab3291f5948521ce2bdb0bc2996339',
    'frontend/src/modules/pos/PosMoneyInput.test.tsx': 'e09d9bfb01180dbbec2c9aa348cde6b5014fdd60a165f2f8ccf17fb5c19c39dd',
    'frontend/src/modules/pos/PosMoneyInput.tsx': '08fb6a936bd557eac6f03691864eea85ef5d837fb09e5f3f81084ede85c11e23',
    'frontend/src/modules/pos/ShiftRecoveryCandidates.test.tsx': '428d1f63329ff63d6c7b4cb0ca715fc8d81e0a070cdfc5d6b59df2d93a7f1003',
    'frontend/src/modules/pos/shift-business-days.test.ts': '74c8c03b24a2b8edd1a91122526e0b8bbf60dd433d331f87052cd0938df8f5fe',
    'frontend/src/modules/pos/shift-business-days.ts': '18acf4b57566a392105e2d32c0b487b0397ddc54d7f4e63ad1f916fa8632fc80',
    'frontend/src/modules/pos/split-payment-receipt.test.ts': '59fa458c1eebb99ad16b25390137d655f1ca0a319c6cdb8445264a523fdd04f1',
    'frontend/src/modules/pos/split-payment-receipt.ts': '1ca9631ff837420529d381ec0128f7b14b3f5efd48c050362b10eed34b280bd0',
    'frontend/src/modules/settings/tabs/DevicesUpdatesTab.test.tsx': '2c2ffc490aef5c41f3d5615bd9484f724573a52087d2860a606a5adab1c3e8b7',
    'frontend/src/modules/settings/tabs/DevicesUpdatesTab.tsx': 'fe7903d28ba0d8178d292cf73dff4304597a6e76f6f8596ba28d62a47917379b',
    'frontend/src/modules/settings/tabs/SystemHealthTab.test.tsx': '061dc6bf9a2556314f39584d06604c38e36816b2f75b9136db723f3c9cae8add',
    'frontend/src/modules/settings/tabs/SystemHealthTab.tsx': 'de073ffbf2c490149ff2fd8215523b3ee717050870432d3629ebf6ef525be438',
    'infra/scripts/cleanup-code30-3-trial-data.sh': '86d23b2b6a660e7fbd8bc60278c5f07b3d2faa70951a0302b15576ab3c80c329',
    'infra/scripts/cleanup-code30-3-trial-data.sql': '07e7c62f4241d37864c17c54edd52da0cb05492bd23b465cc9f70aba1addc90d',
    'infra/scripts/install-on-vm.sh': '342b1b95d3e3a45dce744df60a52a384baf675a4561f3654ab410518cd093349',
    'infra/scripts/verify-code30-2-post-cleanup-state.py': '082505999c3967f2a2a39b2fcfb333d2ddc4fbf935f5d863cdace9a032a43943',
    'infra/scripts/verify-code30-2-post-cleanup-state.sql': '6cb51504a99a489b2fc9e4a501f05124a224d6c637a145c60184c100c2272116',
    'infra/scripts/verify-production-business-quiescence.py': '5a73d1c857fe1059eabc1ea7e96918484075e6b5cd384b09705ed0c786259eaa',
    'infra/scripts/verify-production-business-quiescence.sql': '2bfddd29da35f3ebfaa4570c4d9df92096f45dd28073b9e07026b7c87ad20b4c',
    'protocol-fixtures/gaming_cleanup_full_flow_v1.json': '55c12884d82408ae412b1825721ea71f8f02d214dfba730a49e2f077103f7be1',
    'releases/android/README.md': 'f69738fc8d5498a03254a3db28468f8e32c691d7c08b035de369219516020118',
    'scripts/analyze_code26_physical_evidence.py': 'f1f010ef89710a56e7cde8a83aebfd88abde2c3e7b482846df978a4edc3597f1',
    'scripts/run_android_instrumentation_ci.sh': '6dde37c6062f6708cbb1b972c763b76680540d9e19f310b58293873e83f45d3b',
    'scripts/run_code26_physical_business_audit.sh': '2f1833630a4e20b41db252f1d2a83ab73920c9b3591667e82d8a92bb2ee9f99c',
    'scripts/verify_android_instrumentation_shards.py': '0cc074c0a65e4b18754fb4077d2f0198f001bfa0cb11917f94292014b31cf669',
    'tests/test_android_instrumentation_shards.py': '7fc9b7984d1abb008ab4e6255aeee7fdd5f8ae71bab27352cf8d1af90601888a',
    'tests/test_android_release_version.py': '20e146e4b53d42785cf17c81bce6b324f503467485b58b2129031f5d28b2e8ca',
    'tests/test_code26_physical_audit_lane.py': '5de12c5b22587dbd2c8309bb5ad46d242118869981a7c187ea30b45ff0f887ce',
    'tests/test_code30_2_post_cleanup_installer_guard.py': 'de3b8c2676d3efdb3e998069b9b62b19c69447d7b8ba3fb7d1eb51659ec1221f',
    'tests/test_production_installer_safety.py': '14cbb1dfd6906426756427eb6741eec6a45ba4d6905eecfe90f17e0673c718f8',
}
REVIEWED_CODE30_3_PRODUCTION_PATHS = frozenset(
    path
    for path in REVIEWED_CODE30_3_SHA256
    if path.startswith(("backend/app/", "frontend/src/", "android-native/app/src/main/"))
)
# Generated only after the complete Code30.4 source, tests and documentation
# are final. Freeze-control files are excluded to avoid a self-hash cycle and
# are protected by the exact delta inventory.
REVIEWED_CODE30_4_SHA256 = {
    '.env.production.example': 'f1391f80a3c3bf1a1b07f8bd8987d696ce1f097b3093d386dcd3e8c3568ab3bf',
    'PROJECT_STATE.md': 'a44e64af117727e8736c961afa7d9b42bb182bcd7c1cc73f7f9f4f1b49119c26',
    'android-native/app/build.gradle.kts': '48941fcde2790aa84830257e13f1b43173027a78eb31b2b257b86fc6ea264b2b',
    'android-native/app/schemas/cloud.dcompany.erp.core.db.ErpDatabase/53.json': 'ae270ada72977f1ea7ed17dd088d661a83f0746a4a161e5c593b0aceeebd257b',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/auth/CacheIsolationRoomTest.kt': 'a84fdb737d1a134f1fae2f7d8788c25d7a27844ea32a13d1306a9850a5c560d9',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/GamingSessionActionDaoTest.kt': '734b77edc83a78f6257313b94e3e87fcee99fa571d83b57f0b91f3bf0a719e00',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/MigrationTest.kt': '4042820261b66e353d77b1aad87fbfa159df097911d7dbdbe6c1946d8012588b',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt': 'd71237be59fee834482f18adb51120f84c1d2317e7eaded7eabf1aab0d5a117e',
    'android-native/app/src/main/java/cloud/dcompany/erp/MainActivity.kt': '7315325f1e44fd73fb427e051c0142984bddd0839c9e6ffd3ae5b8809adb65b4',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/auth/CacheScope.kt': 'e384f4ce5263c8a19e04eeffa116d88bda60dad3726b7cfeb87370de3cee6968',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Dao.kt': 'cbc2190c82710c3f16ef711c77fd36c143f6432d1eab627ab1ecf537213f1e21',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingDao.kt': '16a0020b40d2afad43dbeb530055cffe0f36875ff71924120095f7f6ee4df0c5',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingEntities.kt': 'ebed486415a0bd8ad233bf0afb407e55307638845dc3fdbae92034a7b26e4500',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Migrations.kt': 'a801cb9e7c191ddece727f4f4960fa31a195c5eb6931d643c63c8426b64d916d',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/OutboxSafetyDao.kt': '700886984acbc5e350e35554583486ed1edf0e1c8584534d3bdddde7e634ad4f',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/ShiftCloseSafetyDao.kt': 'be0d0fd5120ca068b7237c856179de92c35900c56471759cfb4aae1cf80a901e',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/diagnostics/AppHealthMetrics.kt': '3579919ed46e02be7409b401a79081e66601e389fb4c0e0fce184f620df01287',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/diagnostics/AppHealthRecorder.kt': 'c407ca12aac3d66d092807210f2e5a69406d5bf75789b2f90c1ceb888240701c',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/sync/SyncEngine.kt': '24821025d1db709db620dbca7cda0eb9cd68ca11156c8d7a43d01c3d85fd8f1a',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingApi.kt': '9545bf611bc68d768f6477e4331fcad44b6f624dd5dd39c7851dd5c1dae4c818',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': '5e96f56a73c95fd4cbee4ccb082e8d940f763abcf09f772ea1be749783b2b1ae',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt': '3899fa3a2e92643ba3f8c16eff83db1dab4df3b23841c3ac03456d50f668ac2f',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SettingsScreen.kt': '3928c11fefc4fc950d939b42c44e5fe0684434f084d15eae083e0f7b6ac4ba2b',
    'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': 'e4b068e965e63f5514cfa9f35df79509d21574d878b060e0ba6f4244ed231b9d',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/auth/CacheScopeTest.kt': '29bb2c48a0628ebfc2ff965f432a58cbc6eac3926e7b9c307b93205fa637a8f6',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/diagnostics/AppHealthMetricsTest.kt': 'e1d03cff0300fbd9351c17aa2d67679336621287cb45b091404451e874099d58',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/sync/BoundedGamingPullTest.kt': 'b535e22b1c5342e39a1bfd32f1bcf51ebd0d5a9568b0bef9272771b041d3e7f8',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/sync/GamingPackageExtensionReplayPolicyTest.kt': '5e1cf9a48763e9dd7d5174d5e792b76cab17bc0f1762e17e3c9973e58ed10d85',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/sync/OutboxSchedulingCoverageTest.kt': 'f1d0e890ac0a8c85d96ae57cb03939eed16ff62ca9cad064c644081e3c1e344a',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingApiContractTest.kt': '10853f97449a09772daec286815e19587f5c3b129014ccece1ea454bf99730c9',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingParticipantAmendmentPolicyTest.kt': '089abdcbe9f080d1cd0313087d4b3eb7372ae258127143dea157c3818ece2dd9',
    'backend/alembic/versions/0083_gaming_session_participants.py': '0271c8cc6141d6bc8355549886d2e82db9a496c823203456c6a80ca845248b20',
    'backend/alembic/versions/0084_guard_gaming_participant_revision.py': 'd848ba475b750a6515b24de97218e98f389943d6f53342f7caca8161abb33c67',
    'backend/alembic/versions/0085_gaming_package_amendments.py': '00bcce0d520c6f76c1380e428f8cd5957d94dfa273723f10cb7ca25cac4cb8ba',
    'backend/app/__init__.py': 'cb070ea71366606d9dda7e5c21cb9e046dd391025c9e9392916e1522590be1a3',
    'backend/app/api/v1/customers/router.py': 'aa6f74a294713250f3441d566eba7971cf0254e632c6ed9b858dff9ec20ee68b',
    'backend/app/api/v1/gaming/router.py': '346d4385f6093f49bceee7a7037acc9eca96946e94c41bfaa19d39f7dfced224',
    'backend/app/api/v1/pos/router.py': '3c66a676615aba73af8fb298969c7501520726ba1478c016dfd69a1eaeb8e8a8',
    'backend/app/models/__init__.py': 'd5270d7c9673bab020a8d48d0b350a6729a877d80ea2191325ef6b6a6c9e0660',
    'backend/app/models/gaming.py': 'f86ee229a06e7d46d3035e818aa556f22ea9ff29eb25d7be701c59a881ecae1f',
    'backend/app/services/audit/recorder.py': '03a9f7a53ed17702025d6e5d0e9efe9c67c4e0a9fea7844186648949dcd35d80',
    'backend/app/services/customers/playtime.py': '963c6b32397299584aa8ab99d51223d003d88dbd9113e2f7e2616a0a24f28e55',
    'backend/app/services/gaming/pause_clock.py': '55d1daa8cbf2fbb4cf7f52171569164ceee70de8c886b20a14541b360b429cd6',
    'backend/app/services/gaming/tariff_catalog.py': '0267a707d9184329d9f40f4c4403d974488ee25055863467bd7f1d93b338cc32',
    'backend/pyproject.toml': 'b9c74eebd093efa7739878c44fa15f1f23d079adaf29c8ba9ec72155588f79a9',
    'backend/scripts/physical_audit_fixture.py': 'c1eeedb902d95c4538cf944d009734858f69ec9a44572ac86aade2439a4b9b1f',
    'backend/tests/integration/test_gaming_package_amendments.py': '01e142316348fcf43f2085a96c846260ee4c81640a166520b71d011e4c5d3fd9',
    'backend/tests/integration/test_gaming_session_participants.py': 'f57ced695a54e08b72dbb47d4e8d90efa4738c823b00a159b7e550e8652f3038',
    'backend/tests/integration/test_gaming_tariff_pos_e2e.py': '682f62f111f3419fc8e7301111d2f2b979f149bc45005272f16db800a69ccf6e',
    'backend/tests/integration/test_pos_split_payment_bundle.py': 'e94a2b59deaca284ee3c382ffbb62a18dadaf6591639966333d5026ff51820c0',
    'backend/tests/unit/test_gaming_reconciliation.py': '8d418206d67a2d3c37197a2c091d8c2c4090ae16e6ffc30fc72beb06c66923ea',
    'backend/tests/unit/test_gaming_tariff_catalog.py': 'a7fc0ff2f4b74667e49731e2d827f5ee0936a4d1aa3706f3d02932eb651cf7d7',
    'backend/tests/unit/test_gaming_timer.py': '78a282c4a8e9d6ded564449eff7a0455758095b72317fe06f4e0f78f9e69f267',
    'backend/tests/unit/test_operational_route_integrity.py': 'c201cb29e356a0e3d9c984009c696833441a2e3bf6eef77356441bc0e87f8576',
    'backend/tests/unit/test_release_contracts.py': '749cf7929ccf5190f4059ca735c72eac8ed04d85a97dbea7a541276f99246411',
    'backend/tests/unit/test_remote_assistance_contract.py': '3a937ecfe375c2f8a2b53e14d7011d00b613fdd000df012439217bba96ea352a',
    'docker-compose.prod.yml': '2d64621ad3d3e950f617e4ac24bd43f88671cac72c68afefad45560afc23d2cc',
    'docs/CODE30_4_APP_HEALTH.md': '645c536db40b7d0fb99b8e775df469f7f0708435dd7f4aa43915ec2820f191ea',
    'docs/CODE30_4_GAMING_EXTENSION_PATCH.md': '8354ca356b743f6878ecb316e217113cb1f527ab5a8456d88271915d6f3856f4',
    'docs/CODE30_4_GAMING_PARTICIPANTS_PATCH.md': 'f0df2cd09c7d07634ee263f04721b1a79513af2b991958b348744177273707be',
    'docs/CODE30_4_RELEASE_RUNBOOK.md': '86be95a5f8a11c77e452810a33d1298da15024b74d2e97b25bcf6da9f0fd21eb',
    'frontend/.env.example': '82cbbcfa636d45210fc8e22f6493865dc1cd39fd8d2ed163e3dd5c8b47a3bda7',
    'frontend/package-lock.json': '8ec1bc2a329ba4905933d1ae03ef635bed0387a833013e326f3293b6459be6ad',
    'frontend/package.json': '9d8de114b6f8c6322bf94e5a8841fabaf9bf8290a81f9e658daf9e83a5850f21',
    'frontend/src/lib/erp-api-gaming.test.ts': 'ecf3fe77aea4fa444a06e6136692eb378f1df09e8b95fd9e5ca925168219a9e1',
    'frontend/src/lib/erp-api.ts': 'c7f3a1d842ca8ed1e6aa801420016b6a9abf4b9592064edfdc61fabf814502d0',
    'frontend/src/modules/gaming/GamingCustomerPicker.tsx': 'e1db1bef4a69b6730954a6fcea456b3fa227d676ac83738d33ffa22b05de6c3f',
    'frontend/src/modules/gaming/GamingScreen.tsx': '5b2b99384ed4f97ba62ad7a95613d8b5a0c880c92c6e730c46b284e82fbef9cd',
    'frontend/src/modules/gaming/GamingStopConfirmation.test.tsx': 'a0289c0da73a54161fc3337a2286517179e47f05d342f2905602c1574b58008d',
    'frontend/src/modules/gaming/GamingStopConfirmation.tsx': 'b53f34ccb77e5d294f2f77b22ecfe8cfd9513e6fbd8403f7730f7d146f849830',
    'frontend/src/modules/gaming/gaming-session-action.test.ts': 'a41aa85fa24c8123a052674782445c55368294fcddb44167dd7b5850f5cfdf4f',
    'frontend/src/modules/gaming/gaming-session-action.ts': '0d2fc517a51ac2e070cfabdc7c818fe0ca4b0e0016db43b43852dd439cbd8d43',
    'frontend/src/modules/gaming/gaming-tariff.test.ts': 'e2781a997cdb8f0199ae218f5f12804a9d1cbafc7f9044e38edce3a9be0e0f3b',
    'frontend/src/modules/gaming/gaming-write-access.test.tsx': '07458241182c610f95325203e8fd3984f15bb9f17c44f473c4dcdf435069a03c',
    'frontend/src/modules/gaming/gaming-write-controls.tsx': 'f662630c540dd455a26f5990c1ed741ca2d1ee3195579d255482d1927b03296c',
    'frontend/src/modules/gaming/paid-extension-attempt.test.ts': '250384017f4be56b8516ab55cce8dff45ce8b98c05789d70eebc571810e5afa8',
    'frontend/src/modules/gaming/paid-extension-attempt.ts': '99adef48e6956b41af07018adf460db13fd88e6f4be1646dd06fe9aa68335fe2',
    'frontend/src/modules/gaming/session-pause.test.ts': '0aba5c844cc0b739f73e5aee71ff62dfcfa289e87cbb231811371b0138e095e1',
    'frontend/src/modules/gaming/session-pause.ts': '124b12534cf63201072375a625a19fa7d4b733c699d14042c3406a1c3a614332',
    'frontend/src/modules/pos/OrdersAndShiftsScreen.test.tsx': 'e4bfe67954a9c9b2a3262cface34a8f1343953c9970181086a1b6f84809a2d7a',
    'frontend/src/modules/pos/OrdersAndShiftsScreen.tsx': '71e528ada5ca63ea3b75f4221f4f3c3ed46aab511166596587b43247186b8f3e',
    'infra/scripts/install-on-vm.sh': 'e95052a91f36827f98603b22f6c6429baf173243dd08da788e0403c8546ad4ee',
    'scripts/analyze_code26_physical_evidence.py': '8d76e405a955dfa396860fad489d7f82c74ad4052feceb27162642d853417776',
    'tests/test_code26_physical_audit_lane.py': 'fa41a4c3b56595ea95fa8df33c3a9f22653cf1586f5c25e6642bc860cf844baa',
    'tests/test_production_installer_safety.py': 'e381fdc8d5f4c6f8995391823862902f7631689c3046047f1f492c7ff100e7af',
}
# Only the exact corrective v3.1.32 / build-40 changes belong here. The
# signed v3.1.31 bytes above remain immutable and are checked at CODE30_4_BASE.
REVIEWED_CODE30_4_HOTFIX_SHA256 = {
    '.env.production.example': 'f05447a5b3efc87ef9272741e1eeb5c8c3ad2eb9cd42e6ef5f1322e35a67456c',
    'android-native/app/build.gradle.kts': 'ea96303198d7156fa6f24dc14654f02b2a453379d73d509eb39d818785aa2d42',
    'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': 'c84e000fae07d02b638a4b8e5dae59d71e1a9e66a2c684ad6d3514db23e2031a',
    'backend/app/__init__.py': 'a51866a1dec711af47ba97015b9a7e7087544adc045b2a3a2148cc8501ba8a17',
    'backend/app/api/v1/gaming/router.py': 'c252e3bdb83b71c993245b55043822a5dafdb8ab4b34c86ba76ef988b1a28481',
    'backend/pyproject.toml': 'ad3150a51c64c0a0a2760fd4bc0cb65a51be35875284d5093f92cd99cbcb6d12',
    'backend/tests/integration/test_gaming_phase2_contracts.py': 'f35833a8a504930e53e790f20fbc6bf5a3a48315b99be7994eb2c01b6ddcc767',
    'backend/tests/unit/test_release_contracts.py': 'bf13cd9dac8d8c0baac70b2bd0f144ed6dd3cc85d0dfb65cfbbec765086d42cf',
    'backend/tests/unit/test_remote_assistance_contract.py': 'ed89e8f9526bca6c551b7e57927b9b088d471cace334a44664905cd1002d7e81',
    'docker-compose.prod.yml': 'e4005e66fc60864e7a41ac5f2d80e562013b3c24d833d4df88357fb86f9f23ab',
    'docs/CODE30_4_RELEASE_RUNBOOK.md': '91fccea37f9428cbc12e62ae4a7b97b2525637c25b504f7dbf11c8a4b514b5ae',
    'frontend/.env.example': '898d3e84f10e26bb35693b6b7dd10c48c5e137a7001d187705c2e3ceb15a92b1',
    'frontend/package-lock.json': '4c1e8798fd3e3214e61ae49451f310c9b49bb4baf00176018788544954863212',
    'frontend/package.json': '7aeb3d78f46dfa42dde0b0c2f8dcf2428cc0eb68d933d97860c15dc2824ca9c0',
    'scripts/analyze_code26_physical_evidence.py': '90a4dbafa924c905b77c9e816040fb70d0ceacd10c22e5a0952d49883e4a0c0e',
    'tests/test_code26_physical_audit_lane.py': '03fc2280153696bad5898916d1ee6b6d63a045b413b300fd80389392c8a0ad33',
}
# The v3.1.33 / build-41 control-deck UI and coordinated identity are a
# separate exact-byte overlay. The CI AVD correction sets the physical
# 1280x800 framebuffer before boot after three hosted-emulator losses;
# changing Android's logical display after boot did not shrink the underlying
# Pixel C buffer. This changes no application runtime code or earlier release.
REVIEWED_CODE30_4_BUILD41_SHA256 = {
    '.env.production.example': 'f27985296627c02601b59cdb8af541fa43f5a3f12a75ed781ad095b534200ffd',
    '.github/workflows/ci.yml': '778829761e31de5da3729a13aa90bcc57de24019e1097ad88ccc3bda8cb95e62',
    '.github/workflows/release.yml': '1e5d6b8aa34cd993de7a9e71850c68b965bffd54dda391fa5e15bc549c13c16c',
    'android-native/app/build.gradle.kts': '6ae336925ebfbe7321f717dbe2eff8ebeef6c5b476ad5d3af48efa6d5388baad',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/GamingCentreNavigationUiTest.kt': 'f399e7d8e24c52cded9b9401e5e50331559b9d7111538da6eaee3b4f4ea6c2d4',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/ControlDeckFullShellScreenshotsUiTest.kt': 'd1acd617346b9c7323963e61e7b442089a4abc8318453883dedadc9fe9ad87e1',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/PosEmptyCatalogueUiTest.kt': 'a6bf2c8d0a1f9ccb35bd40696132dfbe38ffc34b061ee4cec4c3c90329e0437a',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/customers/CustomersTableUiTest.kt': 'cf3ee71c3cfaf1aac684c0b104516fc256b789654677486c77becbe9a843d61f',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingBoardUiTest.kt': '75b88c1f0b17b95342a1c6a8510ea1f24c56d161c419530d80a38716c91378e8',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt': '0e07ed4fbe0d078eeaaba62181d8b4515806e787de9939e0c84910915a82ba10',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/inventory/InventoryAdjustmentImeUiTest.kt': 'a9906287710fc69daa4d2db829198eb778c24788f19cb9a47749477725a546c1',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/shift/ShiftCompactUiTest.kt': 'f0c66462b3b0b1a3f3bfa66a02f49b72c8271350d0a2284cab8d2b0b7b518db2',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/shift/ShiftOverviewUiTest.kt': '6e1c5e074d2d1deff5a72baae63885ee5fa83222ea2c02197a3b690f4c7e7f58',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/Nav.kt': '4d919d3019ac0291839344c983a187d844831f76dc8783032287634d4b9c2b5a',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/GoldAtmosphere.kt': '22820204c0a9f493437ea135595a2622010782ef12de4159b9ddf6892b07075e',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt': '5bb05d64d6db5eddb45438e8fa67807ff3ee8c39dd30386040eb9699de03de87',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomerPlaytimeViewModel.kt': '255f68ae0ffa884cb843605459355a7f56a07b4502bca9dd33c189a3fc421f09',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersApi.kt': '416d0966db5bba917fb91454929a6f581927875b5b8ffb9ae1e915d7fa7f4c17',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersModels.kt': 'aaf04196fbb2ef548634ab4e806787035ac5ecdefcc7066c64cdcb28757869a9',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersScreen.kt': 'd4e927f643237f61dccfa0696107777f47d148d4cd6a38b23638d519a12ed341',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDial.kt': 'e837b28082e3ae959bd54fbb85f6baf595e726e66124fded491d084c66a5c337',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': 'd61f4ef5e58a07a0118ec979a75fa403c35eeb1c96ea131bde1c2ef6ae48664f',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftScreen.kt': '03b585c84deed079b21e40df64fd3f0aa45d594c112b22fa1ce5fcb26f127faa',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/theme/Theme.kt': 'af8ae871add96e618922389178925c1136c0782a46865ab9f5ab210bbf0a35ce',
    'android-native/app/src/main/res/drawable-nodpi/station_console.webp': 'e3a5e30a55fe4f8c03556fc946d477ce0e4f8a2b3c9c482c4433a28d6cff9eba',
    'android-native/app/src/main/res/drawable-nodpi/station_racing.webp': 'a744c38ee35fec4bbd005a7306a608e814baeac34398e6c1bc32e9929869df7b',
    'android-native/app/src/main/res/drawable-nodpi/station_shisha.webp': 'd681f2c11280efcc348ba03508f0bb0d4889c2c891b1c78f35c2a7e4de624105',
    'android-native/app/src/main/res/drawable-nodpi/station_streaming.webp': 'ae83b4f74ae0053bedb3756ed447b175ec3438f00afa33c00a195eb89c198906',
    'android-native/app/src/main/res/drawable-nodpi/station_vr.webp': '115cd5adb7dc76351501a5a3e895d5580e9325d6a05aceedde7b59d084fc271e',
    'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': '5282eab883d1cc8f28d6a01cafe89fcd07c3bce70675f7d9cd6c5ed6f999c68e',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/AndroidPerformanceOwnershipContractTest.kt': 'ee837c5112c22d1b66c727933fc1989f93b180a88c0762e1906196a31e5a9b9e',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/PremiumFoundationContractTest.kt': 'ed712bb26b9068b7037d64d98dab27cbda42261265f91fea0a295a06193b33d0',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/PosViewportPolicyTest.kt': '75416913e65d5d486c96182227e74580e426436ec24bd9d2e6c90e7c916747f1',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/customers/CustomerPlaytimePagingTest.kt': 'a9654780a88fddca1b045bf36d552645d394237680d49b2dafe89d343d99dfef',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDialHitTest.kt': 'f86006bcacd7aca7165f81f5e4d1128ee45434013a0490569b11edf154b46be1',
    'backend/app/__init__.py': 'b8289bd6045515e0efa056d7d33d9b5e54b3b97d44735bc895985c6a9f6afd0b',
    'backend/app/api/v1/customers/router.py': 'a4d0d967b74d8c6099772f8ef96c2bae443de7702f3a9b011b5b0df3ffa70747',
    'backend/app/services/customers/playtime.py': '990e94f289905adecc49d9d7ca46da148613798e9dd042a39eeb5b69d5061d1a',
    'backend/pyproject.toml': '8fbf7cec9126400ae4750ba7f0275e542490c919e726676ac2863eeaab8af177',
    'backend/tests/integration/test_customer_playtime_draft.py': 'ecd27f14d7a1b384733fb611949bfee8dd3c699b8c2ed2c70efebce5e6b320d4',
    'backend/tests/unit/test_release_contracts.py': '906704e7e7d886e6dee2413ed2136a2af361c1d18017e5f69dbc4d97bff6796d',
    'backend/tests/unit/test_remote_assistance_contract.py': 'b2c6a7e705640deca8b62a8ff76de76be74554abd8cd38095a11f7855d5fe340',
    'docker-compose.prod.yml': '907d7e29a7765117dc8c4c547b52a28e437ad06dd351e71561754e19f2bed299',
    'docs/CODE30_4_CONTROL_DECK_UI.md': '47cc2c82b6001cddcbf92190c4bb557df5e4c7e7bca325ed216af4db54f9cd1c',
    'docs/design/code30-4-gaming-eight-stations.png': 'f5deec69d789118f17c9a46204a32a62730b4fd3c183a45df2728f6ed2976417',
    'docs/design/code30-4-pos-shift-customers.png': '87b4c5a903ce4147e216fced5cd25aa9009d128ea22bb161048f8dce0ad3618d',
    'docs/design/code30-4-same-screen-stations-slider-final-mock.png': 'e1b06485662100930ade36151fec8d992a51c8170d9a48ae7a88718f22d57ed3',
    'docs/design/code30-4-same-screen-stations-slider-preview.png': 'e60bc7ed9464f98730f76ea8ee5cca6eb97bad3ffedbe6ef5f854f5f99175794',
    'docs/design/code30-4-session-payment.png': 'c5f307d1d508cae2e91ba0673903552e4169be97f9f56f832c84c4cb474b9860',
    'frontend/.env.example': 'e9990a4d5b45f85c777ffe1ecd2687195159b296bb98af3042141784cf0c7772',
    'frontend/package-lock.json': '033e9c8b6d8a67786c6bab571976284a875618e1b1943e5acb8b840b95562f09',
    'frontend/package.json': '228100da3f0223e9ccd082bf668c88876eba3b12dc10ca408b4a7f4fecbb4b52',
    'infra/docker/zlib/build-patched-zlib.sh': 'ec2109d2d0cf9183b40f1a2ff07db5880f99f8557accf94578ed1b937da51b21',
    'scripts/analyze_code26_physical_evidence.py': '7dca0ce2bb16ba6f1bb0b58a004501131309b4cef6b8753747394aa0947de336',
    'scripts/configure_ci_android_avd.py': '89e4b4316cc5d0b959fbf9e6c72d4b3ba8db34fff0eb9add97db97631fbdeb8a',
    'scripts/run_android_instrumentation_ci.sh': 'd78ad3a289623c249014737555ff5414ddc62be6c3c450e2a285d37e0cbfc4ff',
    'scripts/verify_android_instrumentation_shards.py': 'c5c6371bf3897aa2a7548dda716a7930894e17e6ef917e2d787d1b41fad826c0',
    'tests/test_android_instrumentation_shards.py': '1b323085241fc5a1c623f0dd52fbe78aa77983dc8c2931e056acd1df344210dc',
    'scripts/run_code26_physical_business_audit.sh': 'cf833c24cfbc69b5717a77823395635d72eea3e4297521a49481d8e9fed0e9f5',
    'tests/test_android_release_pipeline.py': 'e1e194de0ace91a17847254effc74ba884534098980ab9467f623b651c630f93',
    'tests/test_code26_physical_audit_lane.py': 'b3ef51afcbb482bf7402e5340f9e6094687b5e2970c8a6210686cb6fb359f0ff',
    'tests/test_code29_installer_correction.py': 'f77d22ad26f2f400f51768d0d8c30c4b169e3d9a308af7fdda2deca051686a0c',
    'tests/test_zlib_security_remediation.py': '29a673305e0d18159b95bf987ac1603e987037446d109a4f0905e467830f2cc4',
}
# Build 42 changes only these exact reviewed bytes relative to signed build 41.
# Freeze-control files are enumerated separately to avoid self-referential hashes.
REVIEWED_CODE30_4_BUILD42_SHA256 = {
    'infra/scripts/install-on-vm.sh': '043da9a875429c5613fad0b4416427220b582655f07ecb063061f308cb6188c7',
    'infra/scripts/verify-build42-pending-compatibility.py': '2cf7387757d2ddf550c36edebf22121112f1f5b005674c6fd8686674bcaf3af6',
    'tests/test_build42_pending_compatibility.py': 'e2b49d76cb5a10d3354b5eed9f366755753662bb3a0b5c0cc0a4e40dd453fea0',
    '.github/workflows/ci.yml': '8b69ebd08e837747fccfeca98937e0581934263536d2a6d9cb54d5b4e6a15fe5',
    '.github/workflows/release.yml': '4a109c790435d1b4be25c8f9e4a90d11b65059af9b18031b9cd56886c03e5e81',
    '.env.production.example': 'e28e66ad6f21f31866de67a9e8ddcc4d5bd15db6c5e7ea1ef80191050792735c',
    'android-native/app/build.gradle.kts': 'bd67f512b4550707066fde8a3c3ae377d5509c0296c64aef2940acecf330e80b',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/ShiftCloseSafetyDaoTest.kt': '45f729632aa9ecaa85861c0c87c1041c4b2ec2ce9849e06fdf7588a6e30c4e29',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/update/UpdatePersistenceTest.kt': '39205128e17d65aef0da04861aa158b421608acd50b112f15bc9733525441d2e',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/ControlDeckFullShellScreenshotsUiTest.kt': '26cf5f27218a77864c8529604410896509a50a15a4c39a688e1a73f9da335e99',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/customers/CustomersTableUiTest.kt': '4e28d00f69441fa07a7799c21d89e7d5a63ded4a25c9302026c0ba554e80f35a',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingBoardUiTest.kt': 'fd0eccaa2ad8a77903f8a38a87f3cf2efa513524767aabead88d8bf2b1e53cf2',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt': '13814dc8f7ac58e70631d1161f9875bef2d67fb3f234ae85d815c418b33e4b4b',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/shift/ShiftCloseOriginFeedbackUiTest.kt': '37b3786be3d9871c2721b12351c2ed21c8e73b62d4635e03b65a9cfbe7aed5f4',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/shift/ShiftCompactUiTest.kt': 'efdb01edca6b86d2464e60c0777f323dc3b088c8dfa7e4dc06e5433b2e21ffe9',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/shift/ShiftOverviewUiTest.kt': '43cd53cceddddb6f2c417a3021262072a6805ce0e90b0c58395fd1022c4866ce',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/ShiftDao.kt': '58e49a729f5cff87f2f25facc451ef0586aaff891f721d8b312fdf54c756e35d',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/update/UpdateTelemetry.kt': '6ebc9b1358f8f0e1967a3c64258b7a63e3c8a6a084d5a7e12977d830463487e3',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/OperationalComponents.kt': 'ccbd4092310bbef883ca9f6642af1f1dcbfbb63bfd45b3cb6a7f464593e334c4',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt': '3c2587cea88a49b2df1a0cfd4273e0c218b4d8dc1c2f5f4bae8b310c839e1422',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomerPlaytimeViewModel.kt': '94355c52236ed15603c883e35a0b4bb699f0593dcdbea8f9c056bd10b0a6dafd',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersScreen.kt': '046dc59952864c9e1eec2211c925cef598b6452a9dd8b4820cb6f2ecc0df2b12',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingAlarmPermissionCard.kt': 'cb3eb882099b964f02aab313b529d62a5fbbf119c2f536b9963880e9b652d7a9',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDial.kt': 'e5da9406b6b3c03b9910e00a09acaaf2961991b0cc90bd64296982fb02ceef84',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': '622b2d772dc18da7c1c5ecf306c361f92bf9d287d7e7e7166a539127b578c6c7',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftCloseOriginPolicy.kt': '2fff1263f375be2e85c753dc371221cc1a89c9ed46c8249f14c4864ead0b4798',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftScreen.kt': 'a4b2a8344019d1e134c8ee649fe597adbe4d7c8ac8dabaf7eb607925cfac6900',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftViewModel.kt': 'ab87661e4adc86a7a8a172df0e6138edabcb90bcb857eee5d9f9b6c83467f1c2',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/theme/ControlDeckBackdrop.kt': '8f76f05cc07235a0ac2d1b231fcdf4468da76cf565571b454446450b46a8b6b3',
    'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': 'c0d608109228ecf4e8f2b7bc0d8b11e3b401c2dd10288e7e73be016ce453764a',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDialHitTest.kt': '529fc593ba9a89f75503f4dfbc26ccc2edff8f17557089bf15517d47d6711ba1',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/shift/ShiftCloseOriginPolicyTest.kt': '07f8550c0b952f53d8c70a839daf48405c3c746991f58b17d143e84d43c7c3b7',
    'backend/app/__init__.py': '73b4bb40fc02638a698c129f4468e4da076070a192c671865a0e2daf1d21a609',
    'backend/app/api/v1/pos/router.py': '834ffb264be9c95167be9f0a034f45f305de69d6596a6da3bf5151c1b15b20ab',
    'backend/pyproject.toml': '1ba13b735c70e7d834a1128e01d648dc70a87d1880206f96d217d5f3e5690d1d',
    'backend/requirements-ci.lock': '4cb457d7f8910a8f4c594c6185c97bc7db59355d44b33d56fddbd510b35fe89d',
    'backend/requirements.lock': 'a797cf23e682ad5af7cd5ce7a280be5eee67ba967cc92f905d8b91a9913f4fe5',
    'backend/requirements.txt': 'ca3949448edd2e018141675d38a154a89ecb74f6e80f24e805c57bf35f00476d',
    'backend/tests/integration/test_captured_shift_opening.py': 'c7f84593127066b0c88aa230b3a9db56d50161ead83a2e5e5a5d3971c37cb06b',
    'backend/tests/unit/test_release_contracts.py': '7192b7183954c20996826f8d7ae8f53b0329c2a2c9c55cf8e583490dc2370a33',
    'backend/tests/unit/test_remote_assistance_contract.py': '3ea070ed11f63cd7bca8401013cf39e605d86c6afa4e5d22c0238c560929c674',
    'backend/tests/unit/test_shift_recovery_scope.py': '0d21960e1996b24055788086b51e37352fc2195df971d06f34ef83c59b537f59',
    'docker-compose.prod.yml': 'c9b473a4ca5ee91b2a7ffea3c459dc55940dfdf818b226418367b17ed9296908',
    'docs/CODE30_4_CONTROL_DECK_UI.md': 'dc81511e154e0c027cbc516c1d0fc06861fd653e174d5350ce4ace0da37f63d9',
    'frontend/.env.example': '51d391e35b13bfc009b70bff0ddd134d50bd038f603184f25de025e5a59ee349',
    'frontend/package-lock.json': '33d258c0d93e5d5b18fe96f8fdddc95f98f28b834f47016dac38fd7c31a99fa1',
    'frontend/package.json': 'b37d5393ba4686ae3fb251fc2debff48b8b1f80ef0f0b93dd6b3a2b3ab2b5309',
    'scripts/analyze_code26_physical_evidence.py': 'fa764129bc898a9f7ecbd1f4e5240d5985d57cb84f0510b2031b52b1194471fa',
    'scripts/capture_android_startup_diagnostics.py': 'baccdb179d1c15424bfda9a4179836b4e307c4f49232fa975a910836a2ece7e6',
    'scripts/run_android_instrumentation_ci.sh': '53ac300a9767602a2196f3a56de58a3ad521eac5b920eff87f399032a9c975b4',
    'scripts/run_code26_physical_business_audit.sh': 'f95f0e3c02d54ac7d025be934c81d8ac6a5a0058036a049f678c14e0e6d76280',
    'tests/test_android_startup_diagnostics.py': '0bd0b9fbbf22bbc9eed14bbe91aa9f36645f25a5446f022a7f3e97d4f3214a37',
    'tests/test_android_release_pipeline.py': '9952dab31fb34a71553259bb90ec46b4b8538e06843d1d44b54bd974ed9b9492',
    'tests/test_code26_physical_audit_lane.py': '0952d83ff073b196307544aa3b51ffedeb095a48f1057f5a3f519976d0e930b6',
}
# Build 43 changes only the security pins, coordinated identity and exact
# compatibility proof; all prior reviewed inventories remain immutable.
REVIEWED_CODE30_4_BUILD43_SHA256 = {'.env.production.example': 'aefbf2484379764568b5179b274e8cb988f283e76d6706c09aeede6a5bb37adb',
 'android-native/app/build.gradle.kts': '363d86c3ee75fe4f4fc411fa7041f060447b61303db4daa6faabdfbe688e8413',
 'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': '0560c67ae0b8ad1e5028e5e0a9f983970565228b9901b13de5ebec7689a8c12e',
 'backend/app/__init__.py': '462c804e97e4567886bcef7f440af5dc5d5e9d8753b9b395436595dcd49e2b93',
 'backend/pyproject.toml': '21747a63c4870b6030c41e6b8b64d22a5b713e7c398012f41331e600d348bc35',
 'backend/tests/unit/test_release_contracts.py': '497c08e080ba515b7b1b83035ecfe7ba8d3050f8c677401f9e8af5678a0593a5',
 'backend/tests/unit/test_remote_assistance_contract.py': 'ecf7daa09a498782e1ec756035f886cc7e1199865a6247fec95f4729f412d0a7',
 'docker-compose.prod.yml': '448cb60434417cccc51d5529b31a86d032f30de2c84b23e66a63d3c2e6608681',
 'docs/CODE30_4_CONTROL_DECK_UI.md': '81d894d29426d92f44616275cbe61e0454fee91850e2ff7db11ec9158e4d6f92',
 'frontend/.env.example': '52f0b42763ef71e3174fedfec2ec75a74c8683f032717e86088b833012f545f2',
 'frontend/package-lock.json': '839bde54b97f6c702949758f62c1091e7902de47f12496ad23dbffe20b02f9ab',
 'frontend/package.json': 'f3ce6ee9c5762ee44afb1737e2b0f23c94d5738c0a4213b4d80cf8da998f1f6f',
 'infra/docker/caddy.Dockerfile': '5fe8c0adce4b2f69404fcf9462c58383e7f9015528be08fe5cd465da3ed4cde8',
 'infra/docker/postgres.Dockerfile': 'd7f13eb61d042f9227af43f7e446b5f5f7b9f5160a59ff21e13f62725d5d971e',
 'infra/scripts/install-on-vm.sh': 'e5336268acbadf35db6413393fc3774d2e5a79387c22ae85e9ed95d7c8d7dc9d',
 'infra/scripts/verify-build42-pending-compatibility.py': '349e37bfa13da9c289560ebeda3aec859aa52899d339f503055815ff0e7bfdf3',
 'scripts/analyze_code26_physical_evidence.py': '4e64d6c271b8a9c16b3113a39cb10059edf83c274a4161dbade7500fe613e20f',
 'scripts/run_code26_physical_business_audit.sh': '98faee84b814af5fecb522f60a76e38c2ec4b4798baa398fa65ffd52d3c3aafa',
 'tests/test_build42_pending_compatibility.py': '8a6d68af176a2a36e9af624728dbcb98e6bc9a38fdce7b92d708c2171ab85473',
 'tests/test_caddy_dependency_security.py': '3985e08b45e71c124ebf69d9ca94bfe0f6fd1d25239f72716801434eff38fe87',
 'tests/test_code26_physical_audit_lane.py': 'f1fb068246f291a15d97962adb6f3d916e0f88a36f727c6a346491b4e1de6e85'}
REVIEWED_CODE30_4_BUILD44_SHA256 = {'.env.production.example': '0ae533abb6d8e815894ce1faad9d7931333f0c53f857495e0690ae12765918b2',
 'android-native/app/build.gradle.kts': 'b40cf6a658ec06a2cbd5a6c38e201edb2f8d2814486747e018ef8620ceec6612',
 'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': '0b49f00b5e44ec80983cebffe9534af4c3f5afc7a03368bba9ce1b50eda8dbc2',
 'backend/app/__init__.py': '2f617d2094a97d4a631689803a90c604d904fdafbb2710401793bb4495af1b8e',
 'backend/pyproject.toml': '812c0509f3a5c6814b2292244d11791999dfbd283386cc96bfe1fc74d94fdccd',
 'backend/tests/unit/test_release_contracts.py': '9f79452c5892e32310b0d3390c32557cec71d372048fc24bf61546498681da10',
 'backend/tests/unit/test_remote_assistance_contract.py': 'ffd60daaf5e9cdd0bacf666cbd93c671a7380fefcf83c092dd686ae416d8d3ba',
 'docker-compose.prod.yml': '70dfd8854ec352dd5730a290794a4dcb1d3af69eb27dc865b7e1a59e3743d601',
 'docs/CODE30_4_CONTROL_DECK_UI.md': '2762a25df5318e1aded0f0e77897ac91ee0c3a19c5b2c9335948519ad72563a7',
 'frontend/.env.example': 'b8392686cf34e9220ecb2c14a138bede3958d6312f1f330438cf7be917bdacfa',
 'frontend/package-lock.json': 'f1f280bb40d37725939e5cf5dd044b195588186351cfed9f8b7274c54a2ef85b',
 'frontend/package.json': 'f9bec3e3acbe0965d8de4f0c370ec6043d166a56358cd4039ac046393f11291e',
 'infra/docker/backend.Dockerfile': 'a293cc747ea6b600465fd4d9207e6a6ecf901456b308e0297f051ec8dccca490',
 'infra/docker/frontend.Dockerfile': '03a58d90e273e684d26793c23adce116ef9cd256f9b948cd6b023a5de199f0e6',
 'infra/scripts/install-on-vm.sh': '35928a4a081be5220e813cb1b293c1ebfe174844774f91367ee07b17e6de821b',
 'infra/scripts/verify-build42-pending-compatibility.py': 'bdeeb01d20a27d21230554ccedee5c6cd1fa6617f761a4f44e0e9033c15cc7b4',
 'scripts/analyze_code26_physical_evidence.py': '023ede3e43ab697053ff775b30787fc6d941e68cfb2a1585adedd8aae56be91d',
 'scripts/run_code26_physical_business_audit.sh': 'd387a801e45137768fffa99d4cb34cad59f6f71937300cad064e6e1f6097b5e5',
 'tests/test_build42_pending_compatibility.py': '7a433d007aeb9c77dfae48f25522610caf9eb6f235406766d4d0f927d0dc775c',
 'tests/test_caddy_dependency_security.py': 'a9e103ab73957337d91af06b20abf1c95c465ef8a544a021c4540fbfdbe1658c',
 'tests/test_code26_physical_audit_lane.py': 'ca5a3244b9d1ae376b93bcb59ea6388eeac3a8d00d1b80d74e97942aa9659609'}
# v3.1.37 / build 45 immutable overlay. The three freeze controls remain
# outside the map and are checked from its Git object.
REVIEWED_CODE30_4_BUILD45_SHA256 = {
    '.env.production.example': 'e7c0181f0c08afb8b51e720f893517b583e2c4485da33e68950f475bee453225',
    '.github/actions/scan-production-images/action.yml': '5e2d9a223681f10a70b62e2dc0424915760cc885de8fee18b75839d8bb7d4cf1',
    '.github/workflows/ci.yml': '6664ba0afe510fdf0f42d806678d6f5c83f1c39ea4028113ceb62656bf42fde3',
    '.github/workflows/release.yml': '17e8ee7961fd629ac91f32f5cb40618b179ecbb2fd0b39ab7922cca43687535e',
    'PROJECT_STATE.md': 'c35269b6d537067d02bfac364f7f663119bc59deafb833007574e60163cbc54c',
    'android-native/app/build.gradle.kts': 'ab45fd0caf781298fe2bf143c12913c3fc1eb254d8f39b6a8a41c1e505fcabf7',
    'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': '03602f784f5214918831d45403ff933f0ed0f760420b2c9fd95cb989e8e90e06',
    'backend/alembic/versions/0086_google_sheets_daily_collections.py': '9c9705b0e0f7d5b5d46071b959fffe2b3dc30433287f8c5cf2a553291ff54976',
    'backend/app/__init__.py': '2c72f9e68ac9e6c8c08e2c42fd7cd3ddd0875d091d0c5b478b1acdef95e88d64',
    'backend/app/api/v1/pos/router.py': '8d5e2932bd8ec9973d42257de243467757c40767a5d4e27a1925797b4990f907',
    'backend/app/api/v1/settings/google_sheets.py': 'b6b1329a6581249f89c74ae76df2f2f2732bbcc15d0fb019777c8062e9df4149',
    'backend/app/models/tenant.py': 'ba20d20a545584f1354bce8465586b6a9bfb92056b7bd2e155a704d238988ffb',
    'backend/app/services/auth/retention.py': '54ddb7a7658633c52ce5beb9d7c26297ab6570f38ba1fe0e54a901d0ccbfb1b2',
    'backend/app/services/integrations/google_sheets_collection_summary.py': 'c7c0d98795453b0b31e4eec94073ab9784d2c646d254553ffaf38a02e48da521',
    'backend/app/services/integrations/google_sheets_collections.py': 'ef5149007ce434bbba36dd9694a0a11e0e3ea8f9fcc7d7245b14dcdc553f3a46',
    'backend/app/services/integrations/google_sheets_mirror.py': '6eb49a28eb55371475db48b905ce5397cf2aba29e810c950339f7003599daaef',
    'backend/app/services/integrations/google_sheets_runtime.py': '7637dc3eb4d505ab606dad40e299ab9713b49a6399cb8917a9b683fa53a751d5',
    'backend/app/workers/support_attachments.py': '387c8ee71b7afba0ef78981a5e144231f95321e7b9972bc504c7484f0ad3c566',
    'backend/pyproject.toml': '59cd0704990c4d89832fb2578756bcc9304cd4355f43b0c9a23406a876addc54',
    'backend/requirements-ci.lock': 'f221f5f1ccf3fcc654699b0f7f14fef563403676c125cb0f35063fab3ef90ccd',
    'backend/requirements.lock': 'b09c0fbb7d7e21490baeeb9c97a76fa92d4579a2e96a8b2a64f04761cffcdd7c',
    'backend/requirements.txt': '7fb347e8806e2f320f11f20921160af817ffbf1360fe25f745461d7607df64e1',
    'backend/tests/integration/test_auth_refresh_retention.py': 'a29b93c4b638621f3b5c9fdb74564233a8106e6689a52220c0fbd5eec8a02dda',
    'backend/tests/integration/test_gaming_cleanup_migration_0079.py': '2b788f674218e689f7c81ecac6f550843f7ccfd9ebc7bdde9bdd80bbf698ab92',
    'backend/tests/integration/test_google_sheets_collections.py': '5be1dac362c8b0823630062c3350b3baaa4be1959fc9d839ccc273cf7eb9d065',
    'backend/tests/integration/test_google_sheets_daily_collection_settings.py': '0f2f7fed9b1b6ba1dfddd809bd5f08d7f3b823fed9a37572ed83ea666c32ba08',
    'backend/tests/integration/test_google_sheets_daily_collection_summary.py': 'e62dc08bd493492b0e3d947c5affe3d8a1e7e6a0a04a12c9bca3f69b903f9249',
    'backend/tests/integration/test_pos_tenant_lock_scope.py': 'c9475a3d151459a13290d335083560718204d4ec4cc31c5e98dc4ececedbc44c',
    'backend/tests/unit/test_google_sheets_mirror.py': '8249ce81e4073b0838e7be05a93ed9710bdef93a759301cbec93332c495a8c77',
    'backend/tests/unit/test_release_contracts.py': '5db99562b3084de52931017cd2b947b0dfe4949966f1668fc770e2d1ec2b7df4',
    'backend/tests/unit/test_remote_assistance_contract.py': '3910a435cd581e89f2a9e2b9f3540e63bacb598f236fc57dbe67cbfbe2019073',
    'backend/tests/unit/test_support_retention_auth_worker.py': '0378985d258d92d4a8143f7d4189b537bfa5d1ffbb7964eb9bab855b1e0c7303',
    'docker-compose.prod.yml': '325efdc85c95e7409e2f23d4ae565be0993fa575a582e694144948d363e42ee6',
    'docs/GOOGLE_SHEETS.md': '588ca9163a6d5fdc51df93459fcf6d2e48d19dc7a698bae6c3ea355086cff5a1',
    'docs/SUPPORT_REPORTING.md': 'ed9544629cdbe4b13ab2fc102f4a302dde5a0329de3237ba39d7da7cd8d94dd6',
    'docs/V3_1_37_RELEASE_CANDIDATE.md': '96946757d7b85edd516ec043b68ae2be9cddf46fdce5b6ceb5a3e3dbc1f1ff9d',
    'frontend/.env.example': '19d955d551c8d4a32040c0b6a405d390ca267c431f2d642e86ea9cc12629bc32',
    'frontend/braces-compat/LICENSE': '4ccd8f1107089316c85cd61bd99b43b25341d4ad8f4d480ea8c1e14a14755d90',
    'frontend/braces-compat/index.cjs': 'bb48b516be4dfdba4e3800fd430a733c90e4cda44e82482246cd63e9a65bbc07',
    'frontend/braces-compat/package.json': '4542de40a7df013648388ad57d3b96b6b1fcb65557150ce04a7d853b3eef478d',
    'frontend/package-lock.json': '3fc23b4ae42fad5ee6c9bc53a8fb9c6e7666a6422a91de4a4e1ce8252e274204',
    'frontend/package.json': 'cb6cb1015267804b63cf405d8a0625f7bd759ac76b119411f5efa988f909f1a7',
    'frontend/pnpm-lock.yaml': '6d3949e41368ccd2d461cf46c9896ee9b1271076266039ee689d682f1ac429d2',
    'frontend/scripts/verify-braces-depth-guard.mjs': '58de72b05c22aac9b03e57216f070e73cd53637d35135025f809fc2a0a423403',
    'frontend/src/lib/api-session-renewal.test.ts': '5fc8a7186962b0643871fa765075411091a899bdcc0c961563cf150e324be327',
    'frontend/src/lib/erp-api.ts': '3f4943dfbbfc8ca9b4d40613d563724416f27b1cc7d7db6d6af6d38a6cb6ca11',
    'frontend/src/lib/google-sheets-daily-receiver.test.ts': '3ff2f084f0f1418ca3e3bfe10439a30cd9f36fdc6b1ae2f7091ae0fb2ca1f56f',
    'frontend/src/lib/google-sheets-report-contract.test.ts': '2aaefb0730e877f6df95abd2f44866c31835984ba006b6ff5e319c5839b2db97',
    'frontend/src/modules/gaming/GamingCleanupRecoveryPanel.tsx': '81b798585a4747a842246e298da4c3ffcf77ccd0fa67b5ce5c4d11e416fa4bc0',
    'frontend/src/modules/gaming/GamingCleanupRecoveryPresentation.test.ts': 'cebd542c3ace60283d09efca0b4449f10a74eb4ed67a583be8df160b157a2740',
    'frontend/src/modules/gaming/GamingScreen.tsx': 'eea3c74f2c2e25b13eaa8ac015bab4b48b05f45397c5b420d37a3374d0d8c3c0',
    'frontend/src/modules/gaming/GamingSecondPulse.test.tsx': 'a7bdb47e8ac0dd01498ec1da8367b2e8b9e5e2948f0c502d5cc07e8fa710aa17',
    'frontend/src/modules/gaming/GamingSecondPulse.tsx': '3ca42755c1329e5cd479b7ed4e5f156cd3d93a904dafe1476cc8303891dd3ef3',
    'frontend/src/modules/gaming/GamingShiftContextFreshness.test.ts': 'c7dd59c9b39f62bb35dd969e76d6c5c9f59e71e999a14581558a291125dab1ab',
    'frontend/src/modules/gaming/LegacyPauseResolution.test.tsx': '039904c204c088954eb143f2c4a1fcad10739f8f7b4ea514ef1303f726de1009',
    'frontend/src/modules/gaming/gaming-board-freshness.test.ts': 'd6666b819c9034aaa7fc5e60c2cc744135f89fa884e2688331984a4c4152d6ae',
    'frontend/src/modules/gaming/gaming-board-freshness.ts': 'c0a27fd30b8c0deee9a82b68c8b24eaf264fcd4a885f79569d69ba81eb83431f',
    'frontend/src/modules/pos/OrdersAndShiftsScreen.tsx': '6ee060e220d5cdb3911511c79b676aeac11d88568b41c0f75d9acd48c620aa19',
    'frontend/src/modules/pos/ShiftBusinessTimezonePresentation.test.tsx': '0c866e683e662ef93bbd4a9f378dc77e4c58c63ee788f2c7b0561dcee919fc35',
    'frontend/src/modules/settings/apps-script.txt': 'ce7a5b390b4b47deac709579f588ae8f9d5ae87922eb61c2c03b5579c3d5f9b6',
    'frontend/src/modules/settings/tabs/SheetsTab.test.tsx': 'bc76fca21de268173e59befd76cf2361e6d788dfdff19ae1492ae31082be2985',
    'frontend/src/modules/settings/tabs/SheetsTab.tsx': 'da78a6c9823754b67ae5b294d3ea7e0cc2d73f300826e63930cce84d616240fd',
    'frontend/vendor/dcompany-braces-depth-guard/LICENSE': '35bdd8a44339719441900fb50fbefc5e2dca1ca662cbaed7a687de842c8b70f2',
    'frontend/vendor/dcompany-braces-depth-guard/NOTICE.md': '1829a9d696cd094d23485143d0c8b7dd2cdcd4eca2eaf007f48cf8809adf6e87',
    'frontend/vendor/dcompany-braces-depth-guard/PROVENANCE.json': 'a5d7bf2afe1c976f2c1b5203e5be46629a531a03310eec15c4dea7fb65e7e492',
    'frontend/vendor/dcompany-braces-depth-guard/UPSTREAM_README.md': 'f06ecffb78d40201813ecf840cd06b280a5a56ee85a7f9a6224df3bc1453c53d',
    'frontend/vendor/dcompany-braces-depth-guard/index.js': '332ea07c7b006361aad12aa994ca75dc1db8e8382b884909e2f38f10b85c88a4',
    'frontend/vendor/dcompany-braces-depth-guard/lib/compile.js': '20ea98b7f04c8969ca478db458e3e08e0f3b47cc9f810a75336370bfedbf9436',
    'frontend/vendor/dcompany-braces-depth-guard/lib/constants.js': 'f9fb688959232eee3e6ad7906a5b0e3234815db49ee857ef86983d65b917dc7c',
    'frontend/vendor/dcompany-braces-depth-guard/lib/expand.js': '447c6ef5dc95088ae0e40e759a4d50c6b8f6aa7f46e09e7dcede5ea397faba3f',
    'frontend/vendor/dcompany-braces-depth-guard/lib/parse.js': '43d983a546dce1ed446dbe8535438a717de9e85040ab701edae3a5a50acdc1d4',
    'frontend/vendor/dcompany-braces-depth-guard/lib/stringify.js': 'dd47ae5c9ac1f1a0e65de7160e25500a9dd724279a65fe96011c7f1b7b7ed36c',
    'frontend/vendor/dcompany-braces-depth-guard/lib/utils.js': '34b39e1b7d634c5460c30b1fe271dd337cfc383709b3659c31e5d84b12e92e61',
    'frontend/vendor/dcompany-braces-depth-guard/package.json': 'e534865b77df5d26f16acc3e95153cb0d41081f4e4760d1a5c299656e19fca5c',
    'frontend/vendor/dcompany-braces-depth-guard/patches/dcompany-ancestor-depth-guard-v1.patch': '2e775aca3719326fdc772d2a835484fe874415b6a810a145cab2b89d4c20f208',
    'infra/cron/dcompany-erp-alerts': 'f295d5b563d6acd5849348269195f13920d93c98c4659497e639ddd8601a18d4',
    'infra/docker/backend.Dockerfile': '56c28b83ce357924919b58413a6569fe95ed34dfb5dd2584ca158319e0977561',
    'infra/docker/caddy-build/go.mod': '039f8288d60e807cec75b0e9e24674befd8a07ffa1a1b36ea40fcb263a5a350e',
    'infra/docker/caddy-build/go.sum': 'a99b9d50e840864e9a746ba7d1c8afb66da4d96cbd024d18e3dabd795eb05083',
    'infra/docker/caddy.Dockerfile': 'd9aa16cfa9ec0da96b31f15481b8d4cf50a4caf2e8048f8b300f75fc4c2659f3',
    'infra/docker/frontend.Dockerfile': '2bbabcb5d448b73b8a71a619ca8ab53b3eb5cdc2421725024e916bd04072cebf',
    'infra/docker/postgres.Dockerfile': 'b7cc0f0a33b0292a746e57b79760c9534ac64f0aef4b34e99d13d18f8278aa8d',
    'infra/docker/redis.Dockerfile': 'e0286e4955b981e8155a4a188b8b4261bf8b5fa2ce477b835f41768303490945',
    'infra/scripts/install-on-vm.sh': 'b0cdc19b6e8dde2c52c08e9f78e286a0b13db73e0ee10973b75fa9ab97d49ffd',
    'infra/scripts/run-hardened-image-scanners.sh': 'a2169c9cd0f68b87b2b036a8444ea9ddea8f876b0eb519331839c6d7cf8c4d5e',
    'infra/scripts/verify-build45-pending-compatibility.py': 'bf42dacc6c8f4eb92c9c65646b8ac0210490d0b78779bca8731245047f7fb51b',
    'infra/scripts/verify-build45-pending-state.sql': '18571143f9355d75702d57a3c5616a9feffc6288c42d12d9969201225615dfda',
    'infra/scripts/verify-postgres16-image-compatibility.sh': '2ec4e93e08d9d08e4f56d464814eb6bc75dba72b5bb7e3776ce6440317d51449',
    'infra/scripts/verify-zlib-vex.py': '898a472c9c5da9c231a1ee98022ce6b55d574b0cb951c058b9c3d7a45e6064a6',
    'integrations/google-sheets/Code.gs': 'ce7a5b390b4b47deac709579f588ae8f9d5ae87922eb61c2c03b5579c3d5f9b6',
    'ops/backup-requirements.lock': '302371a2a06d97adea0f01f3beb0cc5ef09dee408a4194b12eef2eeb93a745bd',
    'tests/test_android_release_pipeline.py': 'da43787780d9813417669514fb14ff7dc0414807cff159ed04358159128e0062',
    'tests/test_build42_pending_compatibility.py': 'e1b1875978c75d0c4e670204e3375cb00ed9407d45362e5aa086a3a5d4f21f92',
    'tests/test_build45_pending_compatibility.py': '11292d8763a618f608f1bbaefe0fa8751c17992d2024c5b62652e7a6df51f423',
    'tests/test_caddy_dependency_security.py': 'b1452951bac634182dc532d7af2a4133834b882e593c70a44adfc9cc9da98305',
    'tests/test_ci_docker_connection.py': 'a3bd96ff82ad9072d4aaec9f010ae8ba02bd25ebfa3ffb988ff3858fcfaa3706',
    'tests/test_code26_physical_audit_lane.py': '5972f637eee17072d7ba5220cc276cab5f1668f69fe37a95bbb0bf773a133cef',
    'tests/test_docker_context_safety.py': 'd7313564073232132a04b3950b01b7c0adb331c24bf3036ec83606ac2f1415e5',
    'tests/test_hardened_scanner_runtime.py': 'b1b0c5bea3362cc3808f462a2d32be01b38c42aff35011ad17527cde3eb71f21',
    'tests/test_production_installer_safety.py': 'c9f49a641fd36e72c1aa985438ae83d0b40afcef5c4b9b3de7e006a656693d30',
    'tests/test_zlib_security_remediation.py': '11429c56d36f9763810ab1ad6f5d66c3d729cfb115edaec79412caf1ca7c1184',
}
# v3.1.38 / build 46 current-tree overlay. The three freeze controls remain
# unhashed by their own map to avoid a self-referential digest cycle.
REVIEWED_CODE30_4_BUILD46_SHA256 = {
    '.env.production.example': 'a38f3083f713917f7e3874e81b5ba759454d105b227406771df549d247d60988',
    'android-native/app/build.gradle.kts': '78349f2db2d350d0899616953afa2194c4882043b30175e9c4675c0d908184b8',
    'android-native/app/schemas/cloud.dcompany.erp.core.db.ErpDatabase/54.json': 'afc89eab7d769766611438f2bc5eb92165e15baffdaf431215eb0b3e20e997a3',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/LoyaltyActionRecoveryDaoTest.kt': '2295cc71fa787cf07ce3bc114b9eee104166eaa61445dcab942222beb1f4f936',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/MigrationTest.kt': '5cd1eadcf1078a92bc36aa952f60a40caa8773c4d13cf689923575e3ca983991',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/GamingCentreNavigationUiTest.kt': '88e9166f19774ee43823ed7996c776048b95d293338aef63136d332dd3f9757f',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/PhysicalSmoothnessUiTest.kt': 'fce979980c082473ce109c95c719aaac3a6d4d06c5ba03cc6fb76e6ae93bac46',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/CanonicalReceiptHistoryDialogUiTest.kt': '7a560f6ea74bbfd0c6b92a7708fa5c3a60d77b6dbe1ca229cfc49cb173e8db90',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/ControlDeckFullShellScreenshotsUiTest.kt': '37dbd5ea57afddd1d8c6afc111f2cb84be4a9859862d5f1f1db550bb028ca838',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/PosEmptyCatalogueUiTest.kt': '29c4afed33727246fdea8632efc7a37994bbf6fdd6e558d9d2c92ec1dad459d3',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingBoardUiTest.kt': 'a3485abe520e10e2863e6992c1c460d0b680d274896ff52384dc6a6017a27d4d',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt': 'b839870c3db249e43c1feafc9a862c852aec841bb73cdc165872442013a5edb8',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/inventory/InventoryAdjustmentImeUiTest.kt': 'de7c05fbd4d83cca505322bce7dd4ad15004f546d55f670022fd931e7a3e36e2',
    'android-native/app/src/main/java/cloud/dcompany/erp/MainActivity.kt': '3ed37c673c87972dff855ea79bbc8d32ed14c41e16cb8386a5ee0165bc1ecb0c',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/auth/CacheScope.kt': 'edbdf3c3bc51b05ebb4a0afee2b5c414cf2140b45b465f9ee8d452bc3d2513d3',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/CustomerDao.kt': 'efa3bae5af8b30a311377d18648bbf5acf2eaa39f017db6f092a06ebb5652907',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/CustomerEntities.kt': '409c00c91c275523c8533228b629de64a67c5025c1e8f441fe1c1878d738f7a4',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Dao.kt': '701c829a3b2c75f461750179e223497dd4246eb6fc6faccc6359367527b48c1e',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/LoyaltyEntities.kt': 'dd682fc18e550780c78a267ca617903d38709629e1146e84ebed2797f2a2ad15',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Migrations.kt': '8ddb7b79f4e84f9ce698a3c6d2af5a736f72b2eccc6ad84c6eebdfbfcb41da33',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/OutboxSafetyDao.kt': 'c238f2cb46e3eee2de5437e99ba425b50043edf9f4f3802dda63e013bd2f8dad',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/ShiftCloseSafetyDao.kt': '595e645a41219edadb0afd7f838b0f08582e317bc3565c02d12126bd09118d82',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/net/ErpApi.kt': 'c638e3099762b90ed049230af43463a006177b079cfc3e56afc9e7c904e0fc65',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/net/Models.kt': '951d60dbe54c04c44caff719102f5fd67e31ee23df056dad86769880f095440e',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/net/ReceiptHistoryModels.kt': '0713b569ddd6d3f01fabbee4571b0f983ebdea81db2069a6d1518f80276e708e',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/sync/SyncEngine.kt': '8f322b3503ef4f0760b7e1b7e6765c419ce449e8aef90be152e602ec16698e7b',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/Nav.kt': 'bde673c127be9371b6db7d4239aa68b74b09a743043075a7f150e450a8c58455',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/OperationalComponents.kt': 'e5ba4f2bb36843041afeabca0857b223cc0a5de8c302825943182711013559c8',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/PremiumComponents.kt': '7c0388e1200035aafca3a66315a72bd14377540dc623c7e5113c08be63061548',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/PricingUnlockDialog.kt': '06773d5a0ec307f5e0c7382cc4134104e0d24f2af311e472b804003477b2c22a',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/Primitives.kt': 'b8fe543d8974ad731b1c446647681261774d352ae73b7846e4e6176c1488c2a0',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/ViewOnlyNotice.kt': '3128ae2d1bea430cbdf4169ee0b534e220e404fe436b63c37b67821e3d358990',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/VoidReasonInput.kt': '6b178276ce3ed7ac08a18f44c22860ef416208fdba3ce1be9ec4dd764f198475',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/remote/RemoteAssistanceUi.kt': 'a7bb9f03c406c34179353424c7fce5aced9b33056ee77c3d45a44c7678edb5c4',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/CanonicalReceiptHistoryDialog.kt': '70e16ac11d5ad1cd49c2815588fbd2315dcfe5860d1bb688c95893f323961094',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/LoginScreen.kt': '5cb7e43038a9c97ced2437dd6ba8223777e88644b4f677a4cd3cf744d6970797',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PasswordRecoveryDialog.kt': '910edf156503778bd74323342fa8fb9efca1d6fec2c25e63e774158c2d9da069',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PointsActionReplay.kt': 'aa4ce3caf05a41b999c2f217c17e70a2e565bffdbce22b00ac5c9977ad2a5373',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt': '27438786302032e3f2f78867c22a548864140f948e325f4d6b5ea9ee70f2f7a5',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt': '1440feaa267991da115684b25279a936b6c1b2f386991bb5952d5fef9e7c9068',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/RewardEligibilityPolicy.kt': '8a2d6c7e5286801927dec3a8ad82e861b6ad8faf98e6cb584cab00ba8b503608',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/TerminalSelectionScreen.kt': '01817c388b450463c9d062467aa683ff046fd2e084922818a7b59e664ffac6c4',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/accesscontrol/AccessControlScreen.kt': 'babc6ea2ad4743592defd409c3680fa559df6d0e7c1683cfc28a93f572e6c651',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/analytics/AnalyticsScreen.kt': '69176a34be850af30a26f4e5be63bcc8bdff33c77abde8682d220506823b04b6',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/audit/AuditLogScreen.kt': '683106415fcc1f3d30fdc297b5993493455cc078218aff08d1ed0f5603389030',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersModels.kt': '78cda732fcd8b252422dc61595c5ff2a560984e38a881c62472ad29dfa2eae1f',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersScreen.kt': '94142c8b31c44eb65901595aa7522c71555553490980ce53e387605f9d203af8',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersViewModel.kt': '347011ba8e720894361e93705b475ed0a0d02d1e7dd962e845855d91362f81d4',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/events/EventsScreen.kt': 'decffa1863d8bed3caa87f211c0c2659e59a4231722ae518cdb18ea11ecb3575',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceScreen.kt': 'a904b8c306fb1de63c17c8ced074def7d243f29cb25b710937046b0ab4e9659d',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDial.kt': 'd105fd9c5f9e5dc9cd16b85194836595c75c57e4c4ee4f31263ace22e950776c',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': '9fc613a7873aeca27f1c0cd0128cd14f74bc7a1a0c7255b2e4211ff8f1518b24',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt': '207e67253219d09e56dedb66183c30ac32ab20b826b6ec8e7e5f97c738be19a8',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/PauseSessionDialog.kt': '0cbc6e06cb696e23694387a8866816822e84787519a48894fd521a954f1eb0ea',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/inventory/InventoryScreen.kt': '293dfa15cac61236e23a670137f5f012186ad74797da2619744bd3de172b2586',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/kitchen/KitchenScreen.kt': '3f0a470399258e5e70e7c6f1cec0277a8bd533d3102ce9eba80959e323fe9283',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/memberships/MembershipsScreen.kt': '9d98473ffb7d03f71b4fda2953368bf167acc4d3f7bb4feb256ac3a195d849ab',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/menu/MenuScreen.kt': '76aaf1412e6db3fbbdec57048a92deaed71bac98d887862349f3b734cee39e74',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/refunds/RefundsScreen.kt': 'ee527769a487f821beda5d383aab6aaaac4950ae6f9a3e06c7b48f30d6047062',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/refunds/RefundsViewModel.kt': '941081ac9f0c087cdc7297e83cec13128e00e750a6310d9f972a144bef50316c',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/reports/ReportsScreen.kt': '086fd40947d2ed4412492457c88d26db30c4528d36e5ffda60f8e4c29a31b0ad',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/reservations/ReservationsScreen.kt': 'c7dd1d7a1f5aaa07b702ec45786ed151ec520752344d9e3e993737c60a3e0028',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/BugReportDialog.kt': 'b66b1f09bb1cafda54870348d94cc4970fe0fd423ef2d726c41b5bbd8928d070',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/HelpScreen.kt': '25ae4b873bd1633367ce574835835c85199536ba85ef2a7a7a0dea5ce59e3fd0',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SettingsApi.kt': '6c5cc6c7f1404afc884e8365fa1445391eea93b4893f876e879e7e5a9a15a375',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SettingsModels.kt': '20052d2ddd195067fdccd771d79406c778d21155a0bb4abd5c56e0116b0225cc',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SettingsScreen.kt': 'd5d042eddfae34d53281a0145ea619f1a9957d11039921e17081ea62db52f89c',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SettingsViewModel.kt': '78500fc12db4c49d0e46c20940e3a92a92d983352780e4a2198c525246253bb5',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SupportInboxScreen.kt': 'e39bf2afaecee9ba0c971932d42a9081be3149ddc96e7ef910c8f782f1cb83ed',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftScreen.kt': '33cb2c25c125cb39bc4b3b5b06acaf05c8ba2a38ab2a83aff48fb29b33a8be98',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/staff/StaffScreen.kt': 'a18418cf4b8ef01d1f78962e00a3af0a48a1d992301a0bbcab41c93f611fadd7',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/tables/TablesScreen.kt': 'd136e69b2b0a857f9401c613b9931dfc74405e5f0d11d5e52a11d0b361966ba6',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/theme/AppearanceStore.kt': 'fbea0d06d70b304da0efe4514e74e82b78e84d335ea6e09462062a86aa321444',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/theme/Theme.kt': 'bd2ce71654cd8e047929aece3bf6edb9e8e9093614effb74edc0e7ca6d612326',
    'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': '9aad07e0f500e7f879107f20085ee029ac1df169c3ee63563ba5def7c696c240',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/auth/CacheScopeTest.kt': 'dd7bcdb4e32c24140f332975fb5c514157c4707afb39236faca7fd1847436ac5',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/db/CanonicalReceiptMappingTest.kt': '0f5cdc5b2e54ead9bb9b71808568912a03f04d66660cab10dd132ca059e1b003',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/AndroidInteractionContractTest.kt': '3e706146bd4f875685526c39f523d7d82e2617bf9123279a37c95b3d8db43ca7',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/LoyaltyProgramContractTest.kt': 'ddb893e6038ce08e1ac0510f0b0535d3a0b855f23b929214edd28ef5503d7f47',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDialHitTest.kt': 'ea43c32926d3adc3862b0da6a6edb32b19b911bb60fe139667f79eb3dae4c2b5',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingParticipantAmendmentPolicyTest.kt': '36e5d78025149db3d95b11fe9dc04ea88e19b3dc5be6c92ebf538db99ff9e313',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/refunds/RefundRailPolicyTest.kt': '25a04bb7fcf0a0a5c42c52ad34f9d99c8b2ac07fdaf23f3ca58b349cf7f5e24b',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/theme/AppearancePreferenceTest.kt': 'fcb32378f7985d353d7780ccede9b5eac91012316d50cdd1d9b7d723fb04bad4',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/theme/ThemeContrastTest.kt': '6bb6ba6313eb777c0151686a11df6b94d9651b51464109637b5fd55592fef931',
    'backend/alembic/versions/0087_versioned_loyalty_program.py': '68c77313226ad21166c9e0c5ad4fa7346bc53298bdf00a507df9fc77c66d0182',
    'backend/app/__init__.py': '43d8580bf3b3f2e8b7c3c4a1fe6e7daaf33ed37a13dd48b8f18655b7f1eb8fdb',
    'backend/app/api/v1/customers/router.py': 'bf3e7f194c09bc0c77fb0bfba5c1e1b09778425337efdee670850a7b823d26d7',
    'backend/app/api/v1/gaming/router.py': '41810dd8033c217c73e74274219e7a8f2cc8df698edd37e111a947cfa8e79d97',
    'backend/app/api/v1/pos/router.py': 'd40869c7f846894841aef36e2b9d6e2712303c4e6034a914dcee9ee3f163f36f',
    'backend/app/api/v1/settings/router.py': 'd00bcb6eed339387cc9eca2d321cd68b1ff053344cff03fcae18fc42a0b020fc',
    'backend/app/models/__init__.py': '3815112b63d4a7295857b5b3bbe5c7b6efc107ea06d716162c1778b5c3e0eeed',
    'backend/app/models/customer.py': '12da87050a96bedd95e98f01aed0153bf2d9ad619c59a7dd9601f34bb066e17f',
    'backend/app/services/customers/identity.py': 'bf94c62726f429f136ceacc18cb435092401dc98c3a38b17285eaf9a2bdb4d95',
    'backend/app/services/pos/loyalty_program.py': '56fdf4ca1fc8830bbff31f226240ec1739bd8a41ac6c39969f86170cc4de0fdf',
    'backend/app/services/pos/points.py': '4e0307afe27b6823c2f338e8057cd4890ac599cff48a8075b5a2e98c208cd368',
    'backend/pyproject.toml': 'ef6911dd308f38126ae2f678831f805c2e2dc90580f700acbd8a398c782a418b',
    'backend/scripts/seed.py': 'f2d756d9fdc6f0d71cc2e55d0dadd04f097199d1b042454ba875b70dbff469bb',
    'backend/tests/conftest.py': '3cf97d5ab5833567e55b93c37d32864aef04e205a7fe7f912363a7f5df8f8f0a',
    'backend/tests/integration/test_cafe_workflow_postgres.py': 'b455ce18c06df1b0534f20c9dc96be62d2bee25ac44dc765ad7c280a45c3f1ce',
    'backend/tests/integration/test_customer_playtime_draft.py': '934da2a41ba40b8e5643c36c731935fffeb51fec1c39eae3d274fcdd48edc92a',
    'backend/tests/integration/test_gaming_session_participants.py': '266cd8ff870cd8fca9acb945a018900573aeca80f1dda763608b70bb6b63ec35',
    'backend/tests/integration/test_loyalty_program_settings.py': 'c3016d9ad97466199103432e4480d55aabb5f6fcd6464e6d61d16ec0706bffce',
    'backend/tests/integration/test_points_reservation_balance.py': '688810d6ffc526e9ad2693f3f593b33a6f80948a665744c9c6b137adbdf9e588',
    'backend/tests/integration/test_pos_receipt_history.py': 'fe7d02fdd964803baa875e650f6a20e5ad31963434048cec1e3449964efa7bef',
    'backend/tests/integration/test_pos_refund_settlement.py': 'bfa4f8225606d8228a7561f9bc92e33a5470cb1c30d8b400ff45edc037cdf100',
    'backend/tests/integration/test_pos_split_payment_bundle.py': '7946e3f46c33b80a8789f1c2abd106b459749ab55ed6f308f210ed6e797b4f01',
    'backend/tests/integration/test_production_seed_acceptance.py': '5c5baee4767c847eedf5c1e6573f24f91a9f343a3e17986e5eada96d36ab8307',
    'backend/tests/unit/test_checkout_claims.py': 'f94a75d5e3c470fdd17ff027de9855d9641c3b7093e4f9a3b005525f4c551a76',
    'backend/tests/unit/test_customers_router_upsert_race.py': '59db7762897c384d22a374bd820b23e671481858aba3f4d79a8209a2251a3e1a',
    'backend/tests/unit/test_loyalty_program.py': 'f661ee5d9c88ea1aa8f77e2b4e078a1f2f2a3c98fdf0e765aca923af122a1220',
    'backend/tests/unit/test_membership_benefits.py': 'd35dfb6de753f841da9d2f1eb4160183326a366d31dd8e3b390bb531f8bb9578',
    'backend/tests/unit/test_operational_route_integrity.py': '78f1ff4b43aa396a9af829a2884240b48f8f75cf531a80d48c57fd2683fe3b93',
    'backend/tests/unit/test_release_contracts.py': 'c851496b1f8c5796d33603e2ddaf0b9522f68d1569fdbc3a3c294b25df90129f',
    'backend/tests/unit/test_remote_assistance_contract.py': '39412e833b03c77e296e54d91601816eee91fccf10f38d7aa12dc36d4debb9fa',
    'docker-compose.prod.yml': '9d074fbcefdcce0192ae837515f91c681bbb2fbf874cfc16f65bf13e36e4ce97',
    'docs/V3_1_38_RELEASE_CANDIDATE.md': '0481a1026b566fdd1765a4999a11a40b0b70f4415c26de4dbe9855f00a58ebe6',
    'frontend/.env.example': 'd8274e0a58ea6a1d2675fdbac4d08f96070deda4f9fc705e74034435b7082968',
    'frontend/package-lock.json': '1b7108e6cc42c625ca201bcc2698efd76d785c0f88f8c3279e292613e97a681e',
    'frontend/package.json': 'd306ed32dbc2d6dd3b39f722b4d72f83df5747e07fa18e3d72a9fa40a513609f',
    'frontend/public/brand/dcompany-app-mark.png': '40ae02d5f61217704a15805b50b1e11a8322a7c2ae600a0eed8183e42fc16639',
    'frontend/src/components/brand/DCompanyBrandMark.test.tsx': 'b781388b71f1a0df8199a936a8b3cb94270e2270006b4f7ecefa5031043a7bde',
    'frontend/src/components/brand/DCompanyBrandMark.tsx': '261c34fbe34c157b539d3b7ffddde295f1a30d68dfbf2c59574013be1987492f',
    'frontend/src/components/layout/AppShell.navigation.test.tsx': 'f4a836585a6020a85a573065b6877d5282b73931779293fe7aa28ea5d43989cd',
    'frontend/src/components/layout/AppShell.tsx': 'f071141e19d2b7f166362e4119698b84e1e7dcfb33c4fc35ac71198eb6751c79',
    'frontend/src/lib/appearance.test.ts': 'f195a0b6fcd34704e0181873b185dfa9bdcb2efbf3fc672ce5d62f96127a248e',
    'frontend/src/lib/appearance.ts': '6e18aec258d983d4a0346bb034064a4a277f3e7a7be17de9ff81023ecfd7dd36',
    'frontend/src/lib/erp-api-gaming.test.ts': 'c6c01036821d47ac61da0e3855d98ce2ad8fd752a163e547bc38814a59c5ff53',
    'frontend/src/lib/erp-api-receipt-history.test.ts': 'dabcae4501ed139ded12cb72c77fe068b48dabfd15afb1e0c9eb059cc85a8ce3',
    'frontend/src/lib/erp-api.ts': '1ba6c16bfa0b52e698ad1e669171de9cbe93a2a6993fcdc778c2ef154ef14751',
    'frontend/src/lib/money-input.test.ts': '92cdcd2cfcd023c245a70f3bcedb2b95720a766f9986b82dea561db7c02b6645',
    'frontend/src/lib/money-input.ts': '5d3ecf35202c88fe274c95752a0b2b33de63262581b6bc28ee7ad82325515791',
    'frontend/src/lib/product-profile.ts': '48772a016d59686f0cc2b34b402f9ec1afb4ac6b9a788c74dd58a15511449b4d',
    'frontend/src/lib/retry-drafts.test.ts': '8383e0a600ef90c91be4176a834dd82bc5bf7f0e8fab5e06e82934be3c7403e5',
    'frontend/src/lib/retry-drafts.ts': 'b13d495d08141b419395a494a0b651c5cadc1e387e05b4dcbe80d7d518872e3a',
    'frontend/src/modules/auth/Login.tsx': '179e605d9c4a99661b41b6dc1ee7ea7a9300e72cafa55e074e3fe3d2c1c8a578',
    'frontend/src/modules/customers/CustomersScreen.tsx': '506cefbcd2df98535cffa558623ae023c020853c51951c90b9acb70215d49098',
    'frontend/src/modules/customers/customer-points-program.test.ts': '2b464eee5e8e5800a59ab4dd41a5aa22a1029c17b6676065f933041a202faee1',
    'frontend/src/modules/customers/customer-points-program.ts': '6510a3098ec550904f40514f1a2b379f6b68bd80b6af2f5d2566737da5bdd13c',
    'frontend/src/modules/gaming/GamingAddonControls.test.tsx': '5480c048aed1cb6b034b5971d14fadd91e8fda977740bf34a48d5fa4c84e0cbe',
    'frontend/src/modules/gaming/GamingAddonControls.tsx': 'd6606736b6011637256328b886624d67930a7b8fbd50fa18b801a922be40a083',
    'frontend/src/modules/gaming/GamingCleanupRecoveryPanel.tsx': '72c75ceb970ccee69ed85606d2613788bce625bb771a2f7fd03d31d3c033b094',
    'frontend/src/modules/gaming/GamingCustomerPicker.tsx': 'e0b2dae544dbae7502100544f436516fcc656f313e327f3ce05f4b94aa7108e7',
    'frontend/src/modules/gaming/GamingScreen.tsx': 'b1189c245bb2e58e1fa93f1cb2e93f36843b8bf6ee479d3b7ec8df9300bc7e9f',
    'frontend/src/modules/gaming/gaming-pos-navigation.test.ts': 'f89cb624cb8dd67faecd3e6b31cd10f0007b077355332a943ac1ba0e5de5b5d2',
    'frontend/src/modules/gaming/gaming-pos-navigation.ts': '5e6a06706e47e520d6c17f6ee320d1730ffc217b28fe2a7b19d44d8ca0a0cb44',
    'frontend/src/modules/gaming/gaming-session-action.test.ts': '81b7019203e9a8c3ad96559cdea129cfad564f7dbc977a470d13c73f009ec970',
    'frontend/src/modules/gaming/gaming-session-action.ts': '3cde17b7a30138a01c21688bf29ed497d26d3d8e2981e49e1706e600e235effe',
    'frontend/src/modules/gaming/gaming-tariff.test.ts': 'b44bcef3d241022a5602217983d54d0b16afbdbfaf22e8ba820c2e982248cd1f',
    'frontend/src/modules/gaming/gaming-write-access.test.tsx': '3c1fc0aff167c58dca69c2ed36ff970c2fdd71d1681addb9f4d4f4ac3b17b481',
    'frontend/src/modules/pos/LivePOSScreen.tsx': '840374f365ea08abff046f396bdf928fc5d3bddd942d2e74a307bc1e84f84f01',
    'frontend/src/modules/pos/OrdersAndShiftsScreen.test.tsx': 'afcfc9158f4ca00cdcdb05e55a5850f5592a8e385a25c3b7827d4c8baad45b92',
    'frontend/src/modules/pos/OrdersAndShiftsScreen.tsx': '7f698179ed9137433ab3094d7015fdf8f71c82f165483e96fbaf31e9b358919e',
    'frontend/src/modules/pos/incoming-gaming-bill.test.ts': 'b9dc5027758e37e633aa7217de078540ae21f9e02ed477d6b1a0beb9e55c4663',
    'frontend/src/modules/pos/incoming-gaming-bill.ts': '7174f434b583065a42024cbdcf3142519939c73c2141ceaf95704e099733213c',
    'frontend/src/modules/pos/reward-review-policy.test.ts': '5b4797970a697eb0559273da3fba00b426df9ccac05f4000deadbd283ad1a3be',
    'frontend/src/modules/pos/reward-review-policy.ts': '077fb3465e2cf620fb3dda574a2202a1c4f8476a086177cc1a620f46e3b9f37a',
    'frontend/src/modules/pos/shift-business-days.test.ts': '4c8132c719a8017e8d29a0e6d69d37867f50dbd3d999c8479eea4e56bcf9ada7',
    'frontend/src/modules/public/PublicMenuScreen.tsx': '6b96b1803bf02a7fa203d0c573fea2c330988de3deeef85fc66f6fa1de557c7f',
    'frontend/src/modules/refunds/RefundsScreen.tsx': '557734a05d31f8c14c7035f3cc8f82b147b12fb10aee573510151c8abc9a6f48',
    'frontend/src/modules/refunds/receipt-refund-handoff.test.ts': 'c6db656f7067f598b976f1ef5d10ff1a384eff316657592105f9a22d72775f32',
    'frontend/src/modules/settings/SettingsScreen.tsx': 'bd4e784aa09a03abdba4fe0e272d7b1ced1e3701b16e468ee4f79cf583ae4aa8',
    'frontend/src/modules/settings/tabs/LoyaltyProgramTab.test.ts': 'd7516ca54d528ae08b07a98b5e30ac16d6a4056dc7d46152465dd3d71b2432b6',
    'frontend/src/modules/settings/tabs/LoyaltyProgramTab.tsx': '60721062ad9930b197aea14ab18d0cb15c0db21ee05ca270db95e2dc3f40bc3a',
    'frontend/src/styles/index.css': 'baad0c5d205c13d5413464825150541955e7e9d3eb0cfb8db0c87f6ad65299c9',
    'frontend/tailwind.config.ts': '310b14e43611db4a5f0b334fd989732da553d19c01c5426abb1f49984f9ab74d',
    'infra/scripts/install-on-vm.sh': 'db0e152890909c81591404f609d5196b418ba4d848ce76f8404d07ac81cdcfe8',
    'infra/scripts/verify-build46-pending-compatibility.py': 'bbeedb3e26f72aac290588ecef5c2ae69a559f81774220bf91dcc1d567b5adf4',
    'tests/test_build45_pending_compatibility.py': 'f4188c244be2cb70c7af04522b59d9a3eb19616f322148070aba4c39341ca4ae',
    'tests/test_build46_pending_compatibility.py': '8725f17627ce6ca74da08465cb2fe7ff8c8d9da560ef0ba961d2114361505a73',
    'tests/test_code26_physical_audit_lane.py': 'b8f9c9609e81c6a6e22306d1e30b48a757e7e8a198e009476b98f939ff244fdb',
}
# v3.1.39 / build 47 immutable published overlay. Reviewed delta from
# immutable build 46 after all release-only controls are frozen.
REVIEWED_CODE30_4_BUILD47_SHA256 = {
    '.env.production.example': '9929b2add6a0b4f812fc0ecb8b3ffee15678de011744f465a0eb05177838f1ef',
    'android-native/app/build.gradle.kts': 'e552ec3c7aa546aa5d8c0586762df8da7089c1eef718d82641d02b38da1364a1',
    'android-native/app/schemas/cloud.dcompany.erp.core.db.ErpDatabase/55.json': '3b92dc210319110452bd3870fd0073191a49a926229c947fae7ce2f4124b2bbe',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/MigrationTest.kt': '6293198e894fb5cf7506363ae7cd6d50255773cb7731d601c7188c016e7e3b11',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/GamingCentreNavigationUiTest.kt': '1ff7206ccad2c2d14aaa74b351ef35bc5dea1e7c33bf542411b01f82cdc7d3ea',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/ControlDeckFullShellScreenshotsUiTest.kt': '098419f9dddfb9a5fe558f7c338af55b8ceb03ef0fa0f8a1206dd8041e3b83c6',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/PosEmptyCatalogueUiTest.kt': '69f541df2e4d76950931f89d15b13f137bb53d10c8d24a8a8f65bf78f2c11659',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingBoardUiTest.kt': '750009abf5aea8e14a1c00ad83a1a4c59c2127b0eabc50d6e562d85b7015c2b3',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt': '2a85f7a7dab9f574852b3e74b7acd435c81128683e7ccfe61c3e3bbe74718d9b',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/shift/ShiftOverviewUiTest.kt': '6d734a95f1428ddc5e25212722502cd84c3c9e372b846ab5199e5761a4e17c9b',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/checkout/HeldOrderClaimPolicy.kt': '53784f221c6962dbec68141b59b010fdcde1a76ad5f65d2c6c07243b909db054',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Dao.kt': 'e1ba06e1c2c7424e0ecc3d176604841addf5856d0341caefd225920c01c03ca8',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingEntities.kt': '2ad4e6c116abba81232b06f2648c49dea010680cbe96628cc136259447842934',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Migrations.kt': 'd20593cabc6c7b3b79ce29aa3f5de59f2e2e8530afee08e77d5608293968caa7',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/sync/SyncEngine.kt': '9990cd7904ef065cdb8029a0c3a650b4b7b78222c39d206025592e179dc72c98',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/Nav.kt': '12e90a426d4a0141a6240fb8d97430da323de5e0727bd4299198af45e2845ffb',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/PremiumComponents.kt': '70cab183962b61f897c3c0603381583389044a1bcf6c035e6cff1b3bc18da425',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt': '8d5468282a654dbf6269f1a07d822c09f2c60018b3f16a546978fef2a2e55e75',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt': 'c6d31d91c57c592f89c23c4a1f442b24d79e204da8ab66b1f100c2b46a173f86',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersScreen.kt': '0890ef7e9d0abd810a21ae4b3935d72c395c89de0edc8030c7c05c9e32042a5d',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersViewModel.kt': 'd6c3ee86136174418877cc86c6c41b4690e1fbeecea267637e13cead8e3896e7',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingApi.kt': '84ba28522fceaf2153938e787c02faaa95ec28152f5b7760cadbf2c6eadc3bbb',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDial.kt': '97e41a79366e41bd2b33d2e50cdbf548a7e7ce479fdad082ec2e313cf51d587e',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': 'd506db0a9e5da4a4545f12ee343196be32f2af282c8da55cd491ca6b2d2e6430',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt': 'ab5aae07576ef0b990c91e3926c0ab1a560fb600beb334c59db90d3864c8bef2',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftScreen.kt': 'f826ea2fecb696974a9cb872981d44d91a53e2c16f018cea0ef3dc74752555db',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftViewModel.kt': '454736a427be72d70a33c91e65a357e316e95351c423c430bb041de6cd4bd7ac',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/theme/Theme.kt': '17a3fd5c2945275ba833b05c505fd5dcc830c51aeda277c2abded75d3357690f',
    'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': 'e8225e7e7b89da3061e872c54b72c84b9eb9547450c0da6edae23408eae61070',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/checkout/HeldCheckoutInteractionPolicyTest.kt': '437a847dcd77758178dc7071c7862711e14abd9ae9b60a1d64ba6e7eaec45b56',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/PremiumFoundationContractTest.kt': 'c72259a80d2025314ed0418b3f97908044071df8449399d3ccf8fadf3b9b33c5',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/PosViewportPolicyTest.kt': 'a775d6f9f3b40cec986c17b1ba3b7426e241c6bcdbfbaa0bf74ad83f14212f8b',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/customers/CustomerEditorLifecyclePolicyTest.kt': 'edcc096c982aad0cf13a57945e3c1f42728d914514148da63dba77481f1f8778',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingParticipantAmendmentPolicyTest.kt': '860a748e3b7f53d754ff99b6dffe03d34099437290e70eeda2438efa2274f3d0',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/theme/ThemeContrastTest.kt': '5c2282334ed1219a3b60897615a823e26a322b351aa1b626ca776be73f05eeff',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/shift/ShiftBusinessDayPresentationTest.kt': '7ad1043223d512edd657d689f686de51e11255d9fba825111e2445e502d44846',
    'backend/alembic/versions/0088_anonymous_gaming_participants.py': '706caac623cda77097ed60c94e3296b2ec4369640ece09188f17dbec3ad8a063',
    'backend/app/__init__.py': '0179a24d9ffac910922004640232557a581a1c2cc5267129de4c190cb9a12638',
    'backend/app/api/v1/gaming/router.py': 'e4553c4eddf5efc8e788ac408b2e8943ee62eab532269db4248365c2e15b23e0',
    'backend/app/core/errors.py': '714892695c55622b8649caf539b53ba787c7c719d501ca9e28c3c85679a93e54',
    'backend/app/models/gaming.py': '0e3c34516ff5847bddb32e23cb415d9b4e7190c982427ffdaa61659696b865c5',
    'backend/pyproject.toml': '7353a5886bfebdee842b1456c158eee64289d544e90c89d15904380a2dd1b070',
    'backend/tests/integration/test_gaming_session_participants.py': 'd2b178661e831a4be3c967fbe4015a655df9e1f509aafa4d4dbe6542b0d23859',
    'backend/tests/unit/test_release_contracts.py': '06374e9fce50563f73ab59189dbc5174e0d4e8fde8b1268ebba6e422f3cb3944',
    'backend/tests/unit/test_remote_assistance_contract.py': '381573d32b0f2d1a16658e27b3eec5e848513cb6b9a1890f6551aaadff112345',
    'docker-compose.prod.yml': '3233dbcb271691a88d411e1ee49fd28a8b3de1b1a32578f7b159fbc75f17ead7',
    'docs/V3_1_39_RELEASE_CANDIDATE.md': '85e5b741469a204a801c21f34388841270f13e9995a8fc8908e16c40fa8beb3a',
    'frontend/.env.example': '34d330628f0a57da3e67dfdbf09f2bdd4d27ec0c7990d252c022e2eb5055c64a',
    'frontend/package-lock.json': '1975397a294754b38316bc61576a3bbbe8f11e8550d72ffd931ff1e8ccd8b507',
    'frontend/package.json': '8286f8b3a0e6b49cb6b03a43d41cd885d851e56a2c6b3151e48c405f2fb235a0',
    'frontend/src/components/layout/AppShell.tsx': 'c9dc17ce41d33bc616a8f12498695c9e9e50a29a003ef38249eb70cc43960c0b',
    'frontend/src/components/ui/Modal.tsx': 'ef1dc6d246e74ca3415504d653dd58fca6a0a306f990c7fd1143d5096c613b18',
    'frontend/src/components/ui/Notifications.tsx': '14739c1d602311fb5199e744b3e45b261ca6016a9182ecc78be8fa1545e02340',
    'frontend/src/lib/erp-api.ts': '24971e7a4c1128df9c5d0ae340948637aa0685c63134f3c1a943fd43ca248f84',
    'frontend/src/modules/customers/CustomersScreen.tsx': '49274bd292f2a1b470454e61d5db4cf589b4d719d57cb7de3d0c0afde5cfe2af',
    'frontend/src/modules/gaming/GamingAddonControls.tsx': 'd27720297859000e386990c3480daab04627ae3c0e26bb355c91b22aa5262b35',
    'frontend/src/modules/gaming/GamingCustomerPicker.tsx': '32b532233d01c3eb218f03810cf9e9d3a7a791431ef74e23f2ff78e66e78ef0b',
    'frontend/src/modules/gaming/GamingScreen.tsx': '215e2eff9edd32c5fd8af8f76277973ed0fc264fd4780a7fdf1a02e0bd26f4d6',
    'frontend/src/modules/gaming/gaming-session-action.test.ts': '9c258227754649362d9defe683e8317f9adacd28acdac7ac19a1c857693007d6',
    'frontend/src/modules/gaming/gaming-session-action.ts': '1796003b3af7c9fca7b62b2975a410bdfea0bfb236d08e5f93b007d6387ef0e3',
    'frontend/src/modules/gaming/gaming-tariff.test.ts': '3ba56c3fdc8a7db447815953972eff478798a9c447b360b414fc79e3feb52084',
    'frontend/src/modules/pos/LivePOSScreen.tsx': 'ea39cb8d97ad4f4aaafc8968bfda03198f16b797db41845e0dd549059a7732e7',
    'frontend/src/modules/pos/pos-draft-policy.test.ts': '4017fb29b32e15dcd790f34aec79cb8e52549b5dba609e8eef044317800162dc',
    'frontend/src/modules/pos/pos-draft-policy.ts': '5cb6be707f597f0fcf1bb9909fa2c08b244b1db910a8d3aa67b9cfb7b15848be',
    'frontend/src/styles/index.css': '0ffa8c2083c0f1b9eba5d800109779029ea3845571c201e5f9d98cb3d25b7638',
    'infra/scripts/install-on-vm.sh': '0fac1424b01246881ce1ba9c0c1feb88d2afeed0537907ef75f12c1044cd9b60',
    'infra/scripts/verify-build47-pending-compatibility.py': 'bdadff290dccda6fb6c2fbdbc0a6a4ce47746f27650e72f859bf8dd5d40a9e1f',
    'tests/test_build46_pending_compatibility.py': '99d739c4ec431da18cd2020efc69b8a4a09cc9b6adb050f3b3e2bb2132016a99',
    'tests/test_build47_pending_compatibility.py': '285042e99812b737b09e61c0bd044d51c651d2f0bb9453904dbf5edd26f2905e',
    'tests/test_code26_physical_audit_lane.py': '30a67a0e7d8fc2bb57ffd1024981cbb69f5e6b3c322a8ed86f32b33c4d7065d7',
}

# v3.1.40 / build 48 current-tree overlay from immutable published build47.
REVIEWED_CODE30_4_BUILD48_SHA256 = {
    '.env.production.example': 'a88add6adeaf382d82a4d37fb5dfefaa9261a1d1b9b87c6cf5546bb441464136',
    'android-native/app/build.gradle.kts': 'd14c842fc2cc2f3af504aeaac5a70c6a8ab88c71a5da9256656670948648265f',
    'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': 'c8f0fb32810b786a9d22bf443bd188fa4601b8106e2b30118e5e5187c32d2123',
    'backend/app/__init__.py': '2efdc80135802c98c4e719d64998763f936c3dda20ec3a1b1fe306a3f5099f5f',
    'backend/pyproject.toml': '7df5af14d95342b97f41de1c817e9c6389c33db8612f31c169118860d1444f69',
    'backend/tests/unit/test_release_contracts.py': '0b34a572ed5fe40e0bc8994f9de04b1ce3144da8160b8fb87c09cc6f5f093b33',
    'backend/tests/unit/test_remote_assistance_contract.py': '133f8025f376c0a016b4f6ac3f851f61bd355da37a088214fd6dca3525e0352d',
    'docker-compose.prod.yml': '8f00c97f3d31a67f886371cce0d82e31f1d010e02a2acf1fe8989fd2e66fa64f',
    'docs/V3_1_40_RELEASE_CANDIDATE.md': 'b57ed09aabd1e88ab7bc9f3611521d76162d3fda55e442a942c7bb49fab2aa58',
    'frontend/.env.example': 'eca0c37e41d4e72ac544804d899857f84723d7d2152c1043ba1b7e65d1221045',
    'frontend/package-lock.json': '122d6fd26b6f076cd6d6cb5bf23fcbb866e2ce01a0112157944583dd5a22059e',
    'frontend/package.json': '614a93c7e539a98c5e025e6585fb5c33964c4c09917fa024a4ce48ede157a2ca',
    'frontend/scripts/verify-web-build.mjs': '0d9cef48010a9b375185d5e03f69bc31ba76c080f1866129865f0c2fafee1a2e',
    'frontend/src/modules/gaming/GamingCustomerDisclosure.test.tsx': 'eed21c581f36bbd7732f8bb98a925c7109ab6900006f48cdfe0c114c51bea96b',
    'frontend/src/modules/gaming/GamingCustomerPicker.tsx': 'c93311cc5f63c564e2c63ebb1eb8e124ec264fd33e8fd182c4adb3a2fd8877f1',
    'frontend/src/modules/gaming/GamingScreen.tsx': '2a8de53fcac4a322adfba114adf0fd49dda3409017b244c7388ed380ae5aeb6e',
    'frontend/src/modules/gaming/gaming-station-presentation.test.ts': '2b535517896ac59e878550e215869e6c731ec3eff1132155484ab3109f9e9a0e',
    'frontend/src/modules/gaming/gaming-station-presentation.ts': 'bc4b0ecce3d14bcb7879c9a91f61bad4443f0772c8abbda3083346fbc37351ac',
    'infra/docker/frontend.Dockerfile': 'e7ff195698694d7bd026042d4306cd94c156f52ae22ed97598e88fb3a0efa89a',
    'infra/nginx/frontend.conf': '1e935a53ce1fa472187018781000a9b3c079d5141a273ad376fbb0f02ca9fabb',
    'infra/scripts/install-on-vm.sh': 'd86d1210e213668bd370fcde9607de45f705b40c9c2c3762b4fbee3e1a25260c',
    'infra/scripts/verify-build48-pending-compatibility.py': '54aac713f88da7084e243004570357bbb96785fc1d5e29cf50d79d38308f6e0f',
    'infra/scripts/verify-production-runtime-images.sh': 'af3496868680e15fb52ec012d9314a2693111e500a7cd84b7eae521e64445dde',
    'ops/verify_web_public_assets.py': 'cd5f2d5978f438b54d019075535ac670cf4d34ad9c94d56ee33c0d78d6661b69',
    'tests/test_build47_pending_compatibility.py': 'faf2838474253d7046b987c022f091d7f75249bbf71ab70e01c4367774053b88',
    'tests/test_build48_pending_compatibility.py': '3212599b1da850dbff4b1f5b09d5d221a1e770e9b5ee08e5cc5fd9ab3ba97c6f',
    'tests/test_caddy_dependency_security.py': '9628ffb3e33e6b332da719219362c9c8a738ab69108a7751c88afe10dd3d642a',
    'tests/test_code26_physical_audit_lane.py': 'bb151192dad3193bd4abdb55f1565aa6ee3d7af0cb170b505a0feb4e3ce4bcab',
    'tests/test_web_public_assets.py': '0441694c90094fb7920dd580f6450a34c3d76841cb5b28715520d530bb3faf13',
}

# v3.1.41 / build 49 current-tree overlay from immutable build48. Historical
# maps above are release records and must never be regenerated from this tree.
REVIEWED_CODE30_4_BUILD49_SHA256 = {
    '.env.production.example': '2413bffac46a6df2fb8773f62354f28151620fd44a4152c99423f63beac563cd',
    'android-native/app/build.gradle.kts': '62e88f925f7e00c66a92b595414d9de821d9780a2e04ad92dc88473123296024',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/ControlDeckFullShellScreenshotsUiTest.kt': 'a641a3658d710c91b4ea21e68abe2641c2f33d240f2d62627bd91a722bdf0a55',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/PosEmptyCatalogueUiTest.kt': '794c25551cae42a3d5693a4e675b9da5858c3f14722262490bea02930d4ed994',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/customers/CustomersTableUiTest.kt': '58f5f3a603c43de9059b48b71d9921ffd2fdf70183dc2bfe86d5f0d237766361',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingBoardUiTest.kt': 'c9861778d8b27d82e56dc05a36d34bfc8b0c8d0108ec5280822e9833886876c7',
    'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/shift/ShiftOverviewUiTest.kt': 'b79fb1603a6b94399b6c94fdc9d517966db8337a3d83d73491768c2228f3b115',
    'android-native/app/src/main/java/cloud/dcompany/erp/core/diagnostics/DiagnosticNormalization.kt': '199012608f7a0980d0ea7d038924fff556a9087d147f084e8facbfc43e795ef1',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt': 'b127227423a446060e5106c05c9fd015284be46793e58d50e98d41ee1afc05dd',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersScreen.kt': '9b3e334b98f7bb99f787e57605cd1bffca849bf275450cb55731ecbcff06b3fa',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceScreen.kt': '9cb09602f71c1bb6a0101f61eadcd237591a2704ea4194977e11778db84513c9',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDial.kt': 'a8022b298f88515101fd2ec776ce08e89bfab8ef174269612ab30a193e79c99c',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': 'e31d273eae8f9740318644319c736e1e56e84b73e155daa5e0af6ca49355aa9e',
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftScreen.kt': '63eff091800e3327d4d991af2b4da27e0cce5953f1d3d9b99c816b8966311526',
    'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': '30e86afff1f549c02b8a5e10b98b757ec4a70e671fa7ceab2dd5ef42fe00f836',
    'android-native/app/src/test/java/cloud/dcompany/erp/core/diagnostics/DiagnosticNormalizationTest.kt': '03a2ba3b63ec4363a06890b7f39f5ccd5fa55599439692ee09113fbe90dea48f',
    'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingParticipantAmendmentPolicyTest.kt': 'd3fe17fcb19cd24d663c12c96b6de50bed23bda7dfc7801183b66e1bdb49b7a7',
    'backend/app/__init__.py': '5119c15a2366648ebac4b8fd4bcf6d972a263e451388bf218285691908f96e81',
    'backend/pyproject.toml': '8d6eb01615a266f3d5bc09285381926836e0eeb096b2e389cc7fd3c38f59b3f9',
    'backend/tests/unit/test_release_contracts.py': '33f75898c646aab8ecf07e15cea2c22cec6f00a4e8919246e01799ee436051c7',
    'backend/tests/unit/test_remote_assistance_contract.py': '34ab23ad96ae2fc4ffcc8a3e045fd8593c26ceec7c7fc3e87be5280a27fc622d',
    'docker-compose.prod.yml': '7dfd9362177d89f1711de847c52c8aa6cda75c0207d4df2e16a878b2c8acb529',
    'docs/V3_1_41_RELEASE_CANDIDATE.md': 'fda906597974f2333b4dbe8872af197d552d0bfb75e997c87a9050d4764e7320',
    'frontend/.env.example': 'a16192f5d67b9bea58512fbb39a028596107cd6d50e7613ddc78f516e4bd4372',
    'frontend/package-lock.json': '15c389c2600ea13f3f3dc19ee0ca37abe3685334f3054cf0aba526a4a02ed5dc',
    'frontend/package.json': '4d5e5dfe4b80ba7d79e5d00a6d97af8811c8eced6c31548cecdc1050deaf076a',
    'frontend/src/components/ui/Modal.test.tsx': '57c2222bbb6837c830b6a5954df8cc11ce3726b0edccc8711192a0e9e2cbcef1',
    'frontend/src/components/ui/Modal.tsx': 'bf7ceed5316c3efffeab5efa336fe49db0e8147cca947d43731532f806b0bbf2',
    'frontend/src/lib/erp-api-customer-directory.test.ts': '7e66ef97c69c656cbe8f4a43053d55e93d5decac58a06569483811d474db702f',
    'frontend/src/lib/erp-api.ts': 'e88b63995a105591d32db02cb81482b3e0be0b6762914302dfa77eb5dc91f280',
    'frontend/src/modules/customers/CustomerPlaytimePanel.tsx': '2d77508a4a495a1e251c7cff1949fdbf67552d42504f040849215ffc7283c51e',
    'frontend/src/modules/customers/CustomersScreen.tsx': '357eab579143f42e515cf28820accb79ed9dad57a072e94cb6449d84bc22a2b1',
    'frontend/src/modules/gaming/GamingAddonControls.test.tsx': '1723bc7b092a9bbfde27a64434c90d676892ef8b670b7fc3c7e04891c6a7d72b',
    'frontend/src/modules/gaming/GamingScreen.tsx': '70f0553c48710e1803d958c99add2b88b8b3627e061a6f824ea8ec9e3e8e7b31',
    'frontend/src/modules/gaming/GamingSecondPulse.test.tsx': '46258fc67c8259ace2c5a4394640a97e5dd525f57409f0a1518bce4972351845',
    'frontend/src/modules/gaming/GamingSecondPulse.tsx': 'a32b3e36d1852e0b2ff1220deff78c87fb317e30f0597aae80d94c53d0f73eaa',
    'frontend/src/modules/inventory/InventoryScreen.tsx': 'bf67b18db149bff97eb013c821551db6056781c59eb3b52508178337af52fa39',
    'frontend/src/modules/pos/LivePOSScreen.tsx': 'ac261d352313c5c230e7af492cf8657c6d936c9e1e2bb24158d76d0c0e8f067d',
    'frontend/src/modules/pos/OrdersAndShiftsScreen.tsx': '1f86e5334cfa36dff97ef9e586a5b532c67980ea18008bb912d005f441ba94cf',
    'frontend/src/modules/pos/receipt-history.test.ts': 'aaf84b411ceb61089b03c7e4fd989f04ce3c613dc0be252161b60f628ad6142f',
    'frontend/src/modules/pos/receipt-history.ts': 'da7b493ed6f927e4e3032b03b6e9fb6767047993e996c76773cd768fe8e90830',
    'frontend/src/modules/reports/ReportsScreen.tsx': '86ad2c192baa8680df74810caf9bbd54282a78d461d9f0feb15a8bd1d92351f5',
    'frontend/src/modules/settings/SettingsScreen.tsx': '6ad155ec2c5871a39e0d078e5bbf39f431240819a4cf72b7b6d5be72f998b8d9',
    'frontend/src/styles/index.css': '39b653435769c46b606676b985ee7cd3b7e29f6bf52a5edd32ea3ab7232c158b',
    'infra/scripts/install-on-vm.sh': '3ee444429f46b5ca9e397dce6b46a07307d2e8192a8662baa1be4c6652494db3',
    'infra/scripts/verify-build49-pending-compatibility.py': 'ba2a1b8158efa667977910fd259b08a55fad0f950ac69ddf99f46c47a5b61f1c',
    'tests/test_build48_pending_compatibility.py': '3bd40cd9ea64486ff66987b87e3d994ea03fd2dce31d87d08662150a52201f31',
    'tests/test_build49_pending_compatibility.py': '091d1ec9f42cec1aacd8006a046509bac2b5f6a0de2110a8e089e576c0beff73',
    'tests/test_code26_physical_audit_lane.py': '79601a9f2f6685b4f6665978fe96d6392d4a55a21e2d3b8fd23494ab84452c5d',
}

# v3.1.42 / build 50 current-tree overlay from immutable build49. Historical
# maps above are release records and must never be regenerated from this tree.
REVIEWED_CODE30_4_BUILD50_SHA256 = {'.env.production.example': 'bb3067963b7b927a67f90a26f5d69ff5a4d704e907c2bb953482206ab1660084',
 'android-native/app/build.gradle.kts': 'bf7f779d78ddde6c7c5425e86c902d043289e50a2d439e4273fe50e0b9e70304',
 'android-native/app/gradle.lockfile': '2250fddebcb8a0e4ab45b8dd08a927fdb2f43f0002eb267459d00d9cefdad546',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingBoardUiTest.kt': '5f4ecfc3700e33fc9bc86a21f37f4a54cef2f1a838bb94c22d7a620e1cd8a2ba',
 'android-native/app/src/main/java/cloud/dcompany/erp/MainActivity.kt': '7e9ade895857bbda1af55045df2cf2f654affdfbc97d4bad510cbbdbd4736b20',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/sync/RealtimeClient.kt': 'e07dde267b1715810478205ca2c1f843a60400452a47cf4e67b78d2b2146691e',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/update/AppUpdate.kt': 'a9e67fb0a22000dff10bc6cb97ada0c2505e6da3c5d61ea2902c83e92b3235b0',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PasswordRecoveryDialog.kt': '39e7e36e4280f9ea2b6f04d1b70c738c9ba7154c94e592fbdf091a33967b83b3',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDial.kt': 'b4aeeefd13a53e96a8965cce0c4c574e4c7bdc6672a27132bdba1db5fded6cf6',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': 'bd4d8ca2867378c1730df5a79d3cc1621552a35dff358d14bd2ee2c787e142a3',
 'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': '6952e5dbd778c355a8978db1ca890166ac4230c908e17c19ae434209037aa034',
 'android-native/app/src/test/java/cloud/dcompany/erp/core/sync/RealtimeSilenceTrackerTest.kt': '63774bff783489e22a2180fdc32b551c4fa7677210adc950dbc45c7e84825f8e',
 'android-native/app/src/test/java/cloud/dcompany/erp/core/update/VerifiedApkDownloaderTest.kt': '4caa50960f0709d5d08cfebdc0314cac17bac1f62f149dd98772cd52526b68ae',
 'android-native/gradle/verification-metadata.xml': 'cb8ed35219d49d241b25ae103f0f32587057a7a82f1ffb072c151da0c4af57b7',
 'backend/alembic/env.py': '2057d252d99bbcc3563ec3278eaf8a80d4ade7feba514e00ba325b755813e822',
 'backend/alembic/versions/0089_expand_client_installation_user_capacity.py': '1a30f663f4efc2d9287183318051be9227147793341335cc9806961e90fd7cb5',
 'backend/app/__init__.py': 'ebb4297c434e9c80fe8200cb069d6a93dfc41e11ffde0dededa812ef77615cf4',
 'backend/app/models/client_update.py': '127d46013a5054d4904017a4edc07634171eb603037f0634f93aa5c296964f15',
 'backend/pyproject.toml': 'eac4e43769ce61a5e3897f424a8585e3a9542bd77e071e0ac86e2a791a3f6d76',
 'backend/tests/integration/test_client_installation_capacity_migration.py': 'b15b6e66cc6b9eb0ad8779c8a8878b07646c6eba11e1da90d7024dcbece85816',
 'backend/tests/integration/test_code30_post_cleanup_state_query.py': 'b9e799e54161cf1bebcc212763415a26eebcb99caadd5218a4c96cbe9b3c80e5',
 'backend/tests/unit/test_alembic_env.py': 'f8f95981ea5984da9027f0e05956018b9473d24c6efb478aafc366ef419be271',
 'backend/tests/unit/test_release_contracts.py': '2978cfad70a5a6e9b4d1c511ed0044ed07a36bf09d3fd317b153930b716a6258',
 'backend/tests/unit/test_remote_assistance_contract.py': '880a143e80bbcc4f1e382f0b3e1fdd1632f4acea677ac702fdaae95c8cdc75fe',
 'docker-compose.prod.yml': '71431f5ceb9831fce34e216381da17b025792f91758bcbcad73491890fb1cfc1',
 'docs/V3_1_42_RELEASE_CANDIDATE.md': '3c16751909876f6bbec0be60204df0c72bb0e2dcec897a53d22f9fff15e0bb4b',
 'frontend/.env.example': '84f13d803b5c46ca1795d9e8aad62e6f5e2b7dd1573a54661c2c19c02faf9698',
 'frontend/package-lock.json': '81328db01b7cb462ef5cecc1ca077d3a165a198798333478ecf4da116772ff5c',
 'frontend/package.json': '3077691b3a06731ffeacafac239afd374c185b1110fec7062abf6a3064bf1a1f',
 'frontend/src/modules/auth/Login.tsx': '5b04083b2ed1de635181971af5144758b70f1dbabde3ec1d82ed0162af6d6949',
 'frontend/src/modules/staff/StaffScreen.tsx': '10e883b640dd8fc4b256cb34c7ac548603335c84fd520a3c1c643340c0d0daaa',
 'infra/scripts/install-on-vm.sh': 'cddccacf0e484f23b8ddab7695dc832d241e7667b48cc07187244a20795c6e70',
 'infra/scripts/verify-build50-pending-compatibility.py': 'af7ad6b43fe579a497b68ea09443bb8f38513d1bd014819b97f452f38ab1973b',
 'infra/scripts/verify-build50-post-migration-state.py': '0d393c31824d3595b3f8cec7666e06fb7acc2f2d7fbe412eb1d56ef98d122e81',
 'tests/test_build49_pending_compatibility.py': 'ac8b4a4b096e6cd4a05cf8141ed3211a250fc892daf832f36414ef462c049521',
 'tests/test_build50_pending_compatibility.py': 'a61fcfba7a65c4c00171c9a504f4a82bb5b518f4f8a95d7cd1f510df6e7ab621',
 'tests/test_build50_post_migration_state.py': '67849774d5e2413e8758a88f608ed456acee0285fd34db649b8ed2073311c618',
 'tests/test_code26_physical_audit_lane.py': '3c13a10df7cae661b653a27b1cba222686955e64a569b1372fbb3490c3b50ade'}

# v3.1.43 / build 51: separately reviewed, additive overlay from immutable build50.
# Historical maps above remain byte-for-byte release records.
REVIEWED_CODE30_4_BUILD51_SHA256 = {'.env.production.example': 'bc7f3fd80d9072e9ee2518c950bd7f7fac42ee2495fdde7114fb635cbaaaac22',
 'android-native/BUILD51_CLIENT_CONTRACT.md': 'fa2808bf87ff0fb01147959a651d276bbcf3d7a4fbaa1213cd104fd9175f4554',
 'android-native/app/build.gradle.kts': '331950afd56051b5cd2974adf7cd1a900888fdb7ce5b61f7102de0b94bd2dd1b',
 'android-native/app/schemas/cloud.dcompany.erp.core.db.ErpDatabase/56.json': 'e478b64882aa43fd69b058a30b5a543033a07e65f65ec0eff4e805915b32b03e',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/MigrationTest.kt': '3cdb0b8551666196e74c44f95c4a4ee37ec8e4ae8eca9e8ca1430ffec2dc9b1e',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/GamingCentreNavigationUiTest.kt': 'b53da69dcb021126210bf13de36b43135036c17871328ab680d72c37567b0143',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/CanonicalReceiptHistoryDialogUiTest.kt': 'dd699b47975ffa95905e7080c36c43dc7b83fb4b274fcba468a010b9b4284278',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/LoginNativeResponsivenessUiTest.kt': 'b4d8325fe0d4e2ae533e42bf4ebd4a5165b41086464a3cdbd964806077e77f56',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/LoginWorkspaceUiTest.kt': 'c04ef97d63d3958d46dd540acd1d2d6cbd1c3dec02b39a9b3581f4d06cb0289e',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/finance/FinanceStatusViewportUiTest.kt': '23dda1f720367a1207f8af66c8692df890bbc838b696b91c40cf4c26fb9709c8',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/finance/TenderJournalPersistenceTest.kt': 'f66e52e6ddf50eea5abb77ba7a5b5513c21faed952c69522392987583d2f6933',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingBoardUiTest.kt': 'c398ee2d526f1ae68bec42d898074139b731939e1dc28f1c3abb443c0843ff20',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt': '20598d5585d56e786906dde07ec07a8ec26b1fb6690dbba5b322d5846f7e2a1e',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDraftRecreationUiTest.kt': '8393efaf448fe14abf8eae5348fe64e0c36a16971a5b68fc97add3c1c13a0df4',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingInlineShiftWorkspaceUiTest.kt': 'd2d9cde08d1440a2e61324c6a873bf1ba0c32ff90ed55d401cfd70961e2d93f5',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingNextActionUiTest.kt': '1e27e47514d5f7d6afc368dec0157ecaff440478d4dfb04c7552b6845fee4e2b',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/settings/DeviceHealthPanelUiTest.kt': 'ec64242e5db6d3b917e40f9cc76c630bbda3ebf7086937b58a3982b3178d3f9c',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/shift/InlineShiftOpeningUiTest.kt': 'e485a2207097ccbadbfcfe7d553c6f07647c6bbccc97d30dee0fbfa7f651b8f5',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/shift/ShiftOverviewUiTest.kt': '0de68e963c1f0a7553298134f1b7e9ab730f409740e4420e01ca1ccdb1978d7e',
 'android-native/app/src/main/java/cloud/dcompany/erp/MainActivity.kt': '4687f35cb2edc36c16aee99d0ee5095e52e714cc1bef488fac5d64eb92794af6',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Dao.kt': 'da040a1d9d58cd2d9adb99dac0f1c393c304dc39f7642e01397e4be437b11875',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Migrations.kt': '447265ce3066be26453df5786537d087abdae3689554a1bb137953c2d9234987',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/db/ShiftEntities.kt': '8aba4414d7e9a6b96553c41dcc01db7b7a3efc4b7bb660818f49c7d1ee413afb',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/db/ShiftHistory.kt': '01b4e2cd7cf2bcf8c3bb76ddde6f6e45ab50c9f8a16435d94c720ec63adf59f8',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/net/ApiClient.kt': '005c05d5f11c0de6b1d68c8df474d7369e2bf660efecfc1b6d0af4c5e48fa74d',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/net/Dtos.kt': '3dbd31696ae074fe63743c5e5c4f2404d21a02eeeb6b44ce7f8874f1302c873a',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/net/ErpApi.kt': '548701442c89abdae046298cdc741824387a1b9749ee10e99cd2a38637210e6e',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/net/Models.kt': '81a8f47602a240036405f9ca9305b8df528bcf2809b7ae808654e6b759eb3a07',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/net/ReceiptHistoryModels.kt': '004a4c6e133b3606dfc4628e7d739e7df3d634e429823237dc37eb35b2fec251',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/net/ReceiptInvestigationModels.kt': 'd913cdf8fd66a251362f3c6f135a857169d30c2e772400f4a69a8d114f902c01',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/sync/OutboxWorkStatus.kt': '228770fc8175175d575c833da70144ee25f1ff5e2cbd3a2ffd3ca6f0bc509596',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/sync/ShiftMapping.kt': '842ab317edf9f14968446695e1abc9b998b10eb3a8f13bd57f5d13d0861ecf8e',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/Nav.kt': '72013637276103298dd709beabb824b4af84dcee4fdabb5c819680110f046b3b',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/AppearanceControl.kt': '95126194de6cf34cd73a43f8574d14e7d1fa255236d19d3c3fca0769d2a15ccf',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/OwnerVerificationInput.kt': '9af2100e9e35d7618102f76da0edebc76f261f8641a3c47ad887fe4f96d572f5',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/CanonicalReceiptHistoryDialog.kt': 'edf1bc2c6cc3ba1f6eed60f28f2ac3277230d73285fd44509c85cd4c69fac71c',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/LoginScreen.kt': '4737afbb6f0e7d8f159b94972761c647a83a73df53fad758f4c40f2d4e5bc229',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt': '10d12bba65b994b035f93ced915d2cac6627dfc2f522faa16d0a0105d6970bb8',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt': '1367710cfe40c191fc950ad967f70f5395e0b463fd5eefd36817e14b479b9c03',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/ReceiptFilterControls.kt': 'fb1060473ed252126c1c72b0277eeca7336ba1910c4fbb9256f76813330b94ff',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/ReceiptHistorySearch.kt': 'ee2370f6f521556a8e0d6b6f649aee26a4ce0d0804da3be2292837087b3c58c2',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/ReceiptInvestigationPanel.kt': '87e8c0637433ec838a0636d70017fad7a1c16779fad69d99575713baecbd6085',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/audit/AuditLogApi.kt': '9b0ae3fcabe1da3806ba38fb7d6ad0451d7d09e15bf4146fc5bf181b36c5202d',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/audit/AuditLogPresentation.kt': '95916b1cfd61b30fc01f15d17942392a2e0d1ec280213f5b4afdbb4c93bac88d',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/audit/AuditLogScreen.kt': '25fd7a4defbfe8694ee97fa9b60b8a5dd89af7381b5b2ac0c3b385f97a96ce6d',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/audit/AuditLogViewModel.kt': '916f115daf77ab172fa95b892e7b2adafd40067b9dd68d1880341461fc231215',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FileTenderJournalStorage.kt': '25e319f534d356e908b4cc71563c7848a72a16efd59d2128493384dc59988396',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceApi.kt': 'da95a9ee59f3af1bc0604dfdf34af14c5d55e06e39c2f42274dc561eeb5c1b58',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceScreen.kt': '2975e4130772c62b2eb3d8642bf5e85b256c5e2aede7bab5f772fcafd559e120',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/TenderCorrectionRefresh.kt': 'dd829da31e65861d8e0b716784c8eb3a727ad7fe8679916f8d81d81218e7791e',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/TenderJournal.kt': 'a04d911ea68f2632f1f547702449c11c6295f0b9215d5087470dff41c6830618',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/TenderModels.kt': 'cb2d0e6e2a5d66811076c4d169b34a31ac6398413356ede188bf47d154929921',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/TenderWorkspace.kt': '214f00041a370f537e35c179cc7ab439778bba8785857e07237a02cec79ac5f9',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/TenderWorkspaceViewModel.kt': 'b6fa868aeb0f0e40db5dd879369fbbbf3793f951e9229a64f066690e4e17177c',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': '033d04338cd9382c91e13312601d356e8a14bb11db8d9da51ed3221625b00807',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt': '4e7566f543d6edc37de2319df806a2573d2fd29ad0c57f074066e03cbdcefa45',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/menu/MenuScreen.kt': '3b8bc213bfa8708472e30fdf884b52466e99421d99895ab748f1285a16aba1f3',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/refunds/RefundsScreen.kt': '70ec430fe07b2de520876703d75a234887ede4aac3a7e752c6da6e72c6a283d3',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/refunds/RefundsViewModel.kt': 'a700b7de053e9996854556b9f5cad2b30226dd3c21f07142c7c51894a59e2931',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/reports/ReportsModels.kt': '93dba7ddb1a85acc471d1ba337642911a188c2cda35f3e9306058012646254e0',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/reports/ReportsScreen.kt': '85078523395fcdd0be860b96368f2fb6d17deaf565f5f60d818bb177677dee34',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/reports/ReportsViewModel.kt': '5131b164f638ae56628e1e8f870e20bcdaf1058a9a55d2ddf7c37b55b4e8d730',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/reports/TenderReclassification.kt': 'b5334aa2fc56bdb372ac8defa45073cbd23493b5203182aa2b5975eac5ed760c',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/DeviceHealthApi.kt': '3b1c9646edb8ebbef95df9537b18b3c1162fe3460146fa40b36584cc827b1f8b',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/DeviceHealthPanel.kt': '129ef9a44bf5a624c190c9c54b2abac9c32775308eef536d9c24728b60a0c408',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/DeviceHealthViewModel.kt': 'b20376327efad81a651d5e87e86e20293178e8a719c19f0e7cf9432ed787f33b',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SettingsScreen.kt': '89047c4f2d9440ffdda2efc5b9ad76200ebcf3f68a8008bc2183b1fc1a2ea53c',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SettingsViewModel.kt': 'd989cbb9385054fe96c777528fafb318d1cd5b282bc399ea78b9fcb4f35abff6',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/InlineShiftOpeningWorkspace.kt': '9dfd43f5a12b80acc2886abf49e60d875258b7e0b34ca616b6040a1d59ca5a51',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftApi.kt': 'a3a4340953695fcf0b677ec14e2c98e8cc25e1c09e933a0eb4137ea62f8cc479',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftScreen.kt': '2c5e25087b3b81690705c5fc009aa603323a2683991c2cda0c7f4f4200dd30e4',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftViewModel.kt': '0ca4c97c75c216458700da21810ba79fc1644b71753e6845c77b834e16229012',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/theme/LaunchAppearance.kt': '10d2b718caf0a277c8b957187adb5372c8b386970d3ebac6b2314526b9a40033',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/theme/Theme.kt': 'a921693a91d07e00832e097a382f188295746ebf1294f7b175a32dd03f1f17e0',
 'android-native/app/src/main/res/values-night/colors.xml': 'aa0545f1e112788c46fe15ea24ea21ce41e7204d9312281f6ec62657cc1e7c7a',
 'android-native/app/src/main/res/values-v27/themes.xml': 'd562dbc3092eb4f15cd735277e196abbc532cab819a3ff7b0bfb4e4b90c5266b',
 'android-native/app/src/main/res/values-v31/launch_themes.xml': 'e604e930df239865e61a31c059b9b0a5db00918781ae0061b1a50dac6f82603e',
 'android-native/app/src/main/res/values/colors.xml': 'f58bb62c43e94b9bb78a0395791ccb998b2d850f2027ecb80a8ae38d3c376248',
 'android-native/app/src/main/res/values/themes.xml': '3f82d5f99f7e680a449e77804948e85df132b2578bf9e401a86983d0d37cbb12',
 'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': '602fc16bbc420d4f387dffadb6ff0d35a2c96380b3195e2478aa73295c096ecd',
 'android-native/app/src/test/java/cloud/dcompany/erp/core/db/CanonicalReceiptMappingTest.kt': '2723a7e3bfbcdc9790000dc7a9b1fdaf3373d53410624b492fdd5ed302599191',
 'android-native/app/src/test/java/cloud/dcompany/erp/core/sync/OutboxWorkStatusTest.kt': 'e72ea4278a031b2fe9a1d35cb948d4cf5bf0066ba9c7bfcf1942bb2fc42b2b57',
 'android-native/app/src/test/java/cloud/dcompany/erp/core/sync/TenderReconstructionContractTest.kt': 'fcfc74ff605d4936acee05d3bc346cd37c245118d261270d7cea4a0ecc5be6ed',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/PremiumFoundationContractTest.kt': '5d7541530c959ee9b7e06c4537666698ee1ed5df6abbc5b919c57dbdd647254d',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/WorkspaceLeaseFeedbackContractTest.kt': 'ea9a8e5d0620fc9381bd78b27d9b19b8f2e55baacae2bfcec162236c3874165c',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/components/OwnerVerificationInputTest.kt': '23123f337ca831d53c276c2c6798ee5a2e1cd779b5c8e5797e369bc3b15e0fae',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/CanonicalReceiptPresentationTest.kt': '06efaf809b073ea03b49cd837fa528fa5a45c6c1a549eb5be621f2ace4fb8111',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/ReceiptHistorySearchTest.kt': '536ca8363ffa1701cd3b1274c289c1b269301c4344c7c609fbbd02f4bae5a055',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/audit/AuditEventsApiContractTest.kt': 'a03f3e698c21da01722aea4021b8aa8c10151e781098dba2f92ade8c8bcc058c',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/audit/AuditLogPresentationTest.kt': 'df53f7e4bfaceecf67d76c39cabb689afc0ddfa90a715bee022062cae9e36194',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/audit/AuditSnapshotStateTest.kt': '9a0cc942194970437d2b651a040a966518c3e7a00bedaa8a7329106cacd75239',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/audit/SemanticAuditPresentationTest.kt': '5ada7bc81fcc16691fe1fc6af9eb81452f3f3a8d87ff189642af0e209c524962',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/FileTenderJournalStorageTest.kt': 'ac700aba54aa744875be8ff13e20a4082816e2c1a24001eb5ab1c2c5c6a54706',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/TenderApiContractTest.kt': '699b1dafb88eb4f2d31fd1b885ae63d8afb2179832adf547cde938ce486c93e7',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/TenderJournalTest.kt': 'ab1053145759adaf3e7f509f79bb5b6097b43e6f7d7ce6eedc54e5ea8a946740',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/TenderWorkspaceStateTest.kt': 'f1f532e1894322142d77b94bbcf0eb966b59058ce182f3af29d59e1a4ae2e0b3',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingCustomerDraftLifetimeTest.kt': 'cffd28ddc54812b71eef9f0989207257bab440d5a4bafae6fe1d77bb3783c4b5',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingDraftAuthorityTest.kt': '30f728c520656817775ba568a0503776ea9b011be757398b2e1bace0d378481f',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingParticipantAmendmentPolicyTest.kt': 'c0f46ba10f9a1c6e0022e6703d04ff02bccbfa181052c6dca40559c9e0289b71',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingStationPresentationTest.kt': '5872c926fe17f49f861787411ee2535c48472381d70b6a287afab3bc915e4c91',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/reports/ReportsPresentationPolicyTest.kt': '67593e2d8dfb978ad0d2d945c111cb3820057927d17402accaff3a10b84cc9d6',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/settings/DeviceHealthApiTest.kt': '3cddf556177bf293822141da87c30ace91d2c1ac8a6998394acfef71a47ed88f',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/settings/DeviceHealthViewModelTest.kt': '30428dbe706121ce72e46953457e0687adba6775e4076bace0dcce97f50cec91',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/shift/InlineShiftOpeningStatusTest.kt': '219ea9b55861ef5872870ff9b0742bbf5ed90d0b713f53d35f8de8dc69145780',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/shift/ShiftClosePresentationTest.kt': 'd09cc052f95d14377bc293ae4a32e06846dee4712e8fc6b96f6cc1f325fb4977',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/shift/ShiftOpeningCaptureTest.kt': '1585b8f6d1ac2d57a0adb583d8e19037f5fc313aa140c48d6fabc2afd89580a2',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/shift/ShiftOpeningNoticeTest.kt': 'd57c4b6801b2f7a051398e936af694295dea284fdb7d882a6f0278c48fc17cc6',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/shift/ShiftResultContextTest.kt': '0ecb4828d4081da7d42762ebf78284ffa007b1900872da5ad0c9b9bbd3be5ef5',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/theme/LaunchAppearanceTest.kt': 'ae4b89a62c19df8a2bd999527c970597a7032650226acd458f6482c8211fffe6',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/theme/ThemeContrastTest.kt': '3085851d7e9ba2f98bd1d7d7233eda4f69eccc47187893008a2bcc71958db06e',
 'backend/alembic/versions/0090_semantic_audit_events.py': '6fad3d990aeaeebcf9d616921f0c762e353c5fe1074e57f0351a60ffa6163ff3',
 'backend/alembic/versions/0091_tender_correction_receipts.py': '8b23a568c099588d1edc5e8e7af84a475963382892e69e6e7a48ed6ca08dfbfb',
 'backend/app/__init__.py': 'a08cbabfae1a4c07ef076750edd4228bf76bb8a356ed565491720a59039dc3a1',
 'backend/app/api/v1/admin/router.py': 'c2a144f84725f9e64fc155e3fe6d3dd0fd8419f0eb5af020cf1fa1b0343a13a0',
 'backend/app/api/v1/auth/router.py': '43a0554f99387b7344f582bc635e58822b4eaeaf5d0dff8a485453eaceb6b35d',
 'backend/app/api/v1/customers/router.py': '69ccdddba04f3c97ab9a375e1312fa639df1fb2633e6e271a3f7788fd3c3e6a9',
 'backend/app/api/v1/finance/tender_corrections.py': 'd93f4d6feef660c6ecb799c2ba62f7d145fa69583ba17163a677f18c736d64f0',
 'backend/app/api/v1/gaming/router.py': '6370e869dc566c140e97b2b0f15fcd6f005aa076f25d8a76cf469c7910d439ca',
 'backend/app/api/v1/pos/router.py': 'ae37e45fbef1e79d17ee422a29ba45656e1a920096c8da21fa0d99a4bdbc6044',
 'backend/app/api/v1/reports/router.py': '14225f1196aa42c9a1a199ffd5fb1dc8273b4c52c7390d99f4a4ab52e7128a95',
 'backend/app/api/v1/router.py': '00697462823579ec5f79809f3d9c5e4d7a0c811a28390680f73ac61a5fb53eb5',
 'backend/app/api/v1/staff/router.py': '42f160341f5d1be9ce30fc36260922e9192a58e36f7027969ee3ceb3a049219d',
 'backend/app/core/middleware.py': 'ee5f16a96b8dad07f7ccb7d56624239c233fbf2d62bbf34b131341fd4098beb1',
 'backend/app/core/permissions.py': '279fe66f878b0d62a79eb244081d7cf5e84abeddd0731776d18f18510ed18ff0',
 'backend/app/core/security.py': '095885b4ec6fc0aed0c3d1a0067c666c52e44d41599c47f9c16ce0ec4a9c5394',
 'backend/app/models/__init__.py': 'fca3c888a9c600372a39ae548cb0dc91c1361d996f8e38353a659c8ae44a868d',
 'backend/app/models/audit.py': 'e4a8e71920fc6508ea0f1d3fa22b5f231e2da860cd105bff34bd0c4fb3cc33e0',
 'backend/app/models/tender_correction.py': '868b1be32367d24bb7a254d985f1e09d66499a84782cc53576cba4c0547f405d',
 'backend/app/services/accounting/ledger.py': '3bc959d7c81173cc91fbad624b2bf9c4ec705e04269085b41e892cdaff24b37b',
 'backend/app/services/audit/catalogue.py': 'b7deff49f73d5b7756331bd019f8d3f42e339244757e8b17dec15e71c724b340',
 'backend/app/services/audit/events.py': '7fe579e26aa81115caaff18821b947630db92b51166747cb00bfe758dfef656a',
 'backend/app/services/audit/query.py': 'a348ad1f66cd24cbc1342adf8ec633c9b96518c57acce41b2dad9430cd3f4dd7',
 'backend/app/services/audit/recorder.py': 'b9be6e463434b02acc2427787898ddaa531826e19433c2bff59a80702297ff25',
 'backend/app/services/audit/snapshots.py': '87a66a1474b6ec81282c0b01bb5b11d05582cc115ab5d6d27753b55a7ad63224',
 'backend/app/services/auth/otp.py': '6ad97beef7f116b5b5fcddae6baf71dd0451d73b81d641ca73c81fe42d05f93e',
 'backend/app/services/finance/tender_corrections.py': 'd07d64faa160c82669a75bd66050cc937b3321e4c82353156a3f01209afdf314',
 'backend/app/services/integrations/google_sheets_collection_summary.py': 'ccbd812def11976d697792801ce6c6393c79ace0fe37decae55826d2e5605bba',
 'backend/app/services/integrations/google_sheets_collections.py': '6d9b4c272efb8186c8fa306fab8609bc9d3e56b0f21fbe5169f4bbbed26fdf79',
 'backend/app/services/realtime.py': 'be20050fed4744c6b0e4ec5a5be437e1bc8a746cbf180d4a29a976c9e350a63a',
 'backend/app/services/reports/aggregator.py': '66224b8765e5fc3a773e41250ee692cb72f860d0e04588f563476c85cd419cb1',
 'backend/docs/AUDIT_EVENTS_API.md': '6fed8162998d5a5d31d1fb1758bd7ff442dc7d680fcc557189a0b23ebf0c8ee1',
 'backend/docs/TENDER_CORRECTIONS_API.md': '55b5e5f1cdb193af5ba5488f3c64ded27e8d0b83e74991615d26b675379d49d8',
 'backend/pyproject.toml': 'cb27f9cf458f53517eb5026854fc96768b18ec95ec0218cfd45b0bcc193fe84d',
 'backend/tests/integration/historical_seed.py': 'edf6a5fcc128db540260a2a45292fd578a2307d45996407488587a8491210abf',
 'backend/tests/integration/test_audit_business_events.py': '12e9bdcadca4d63e8fabf0b03ffb9e4bad2203dde4a873ff475a1b14f52c2e67',
 'backend/tests/integration/test_audit_semantic_downgrade.py': '967a8be64031f32a99a29b2c0810398e7d64df40c1185d593ad57a18e66c20be',
 'backend/tests/integration/test_auth_otp.py': '0a5be81203e8c813609737ca4fa54f2aae3d24764c65c92bb759d58f77d6a947',
 'backend/tests/integration/test_build51_history_bridge.py': 'c6c7e572fbdcd53b120789769f7accd83ef2306ce6c077731dcb21264d00d97b',
 'backend/tests/integration/test_checkout_claim_contention.py': '1a517efee95b819deb3c794cca49d32a284903a4f75cf1b088cf37723938cb3f',
 'backend/tests/integration/test_gaming_cleanup_migration_0079.py': 'd7efc8e3d6472a83b7f68ba75c22116918233665727bbe1bb752398849cef8ee',
 'backend/tests/integration/test_gaming_package_amendments.py': '928638d824dd51705e451a2533b500bb7ed5c5fe8609024d72a2b3fe75a062fe',
 'backend/tests/integration/test_gaming_phase2_contracts.py': 'a57ccc461a7fd969f1ac44d86fcfdc7fe401c8688ba66335e8c6e83e3dc54081',
 'backend/tests/integration/test_gaming_session_participants.py': '298794f23f014051a6b39fee7a3c744cae39bae6523bc368e4b1443d3b51b9b0',
 'backend/tests/integration/test_google_sheets_settings.py': 'e871eca08a6f854812df822c803b13dc5ab53f931f0fa09ae27651870b50be7d',
 'backend/tests/integration/test_membership_migration_downgrade.py': '6cb781232d9516a7982f6a0e10fb45807a189e67f64b759e36eca7bb7be68a93',
 'backend/tests/integration/test_membership_subscribe_concurrency.py': '405dc124655f9b576dd7c6bb8410d3c3e413d71530932a587a61080613323c9f',
 'backend/tests/integration/test_pos_refund_migration_downgrade.py': 'fe43523cce9fb2e67e7a813625ffd53d83b6479e27632b68a75d98835902bd07',
 'backend/tests/integration/test_pos_refund_settlement.py': '6cffc1ce8b194fb120d738d58bf58d41f69be894e620867cf94f5e63d18a6186',
 'backend/tests/integration/test_pos_tenant_lock_scope.py': '1591823b2c620e1aca5127c75a20a9cfbef23f87b8bb6efbc43faf7fa46bae74',
 'backend/tests/integration/test_shift_close_count_validation.py': 'b80fafcdd8b3297e1cf37809d3f76f2492990a1822f93b446e7f0c85ab5ecaa6',
 'backend/tests/integration/test_tender_correction_edges.py': '7440991869d9da3753415a491edb23756bd9a0d4dd47116c884d4c8233b71a98',
 'backend/tests/integration/test_tender_correction_migration.py': '878025211250e037e7444f645ad839ca71165b966c0048eef90e7ca7a7f7c8c7',
 'backend/tests/integration/test_tender_corrections.py': 'a178534838a526d12e9fd887223127e8a17b5470328e1fdd408625d4cd73f88e',
 'backend/tests/unit/test_accounting_provenance.py': '49b17c491c4cc43a2c8be183ba6938c4305e68eefca5f71ad3655907e9769983',
 'backend/tests/unit/test_audit_business_catalogue.py': '4f73f4ae44217aa89804efe62bd806a77b90d4ff3cc1d9cf4dc538247834994b',
 'backend/tests/unit/test_audit_request_context.py': '2a9063fb7de3e148b0e7a90b42627d6636c0a655873e2b17a3ab68e94216ef7a',
 'backend/tests/unit/test_depreciation.py': '07b03e613e65c2e8af7b7d7af138995c2b5ea08c3940ff4b8e3914a3882d1017',
 'backend/tests/unit/test_event_ticket_report_integrity.py': '7af76470d90c31a9b423a16efd2d54c4c9e04bb6e3e3afe744c6ffa1824dc8a2',
 'backend/tests/unit/test_manual_collections.py': '43d94e86178e4a22b7eaedd700859ddf932a3629212847122ca954ee91cb9f3a',
 'backend/tests/unit/test_operational_route_integrity.py': '27881e5caf71eb15c21b59e1abf0f0d6f4e58fd09c420fb53a07f23bfce63f1b',
 'backend/tests/unit/test_permissions.py': '72c71c6a291a669998325f1418362d97c79d9150be8903785ff9353e50bc06f2',
 'backend/tests/unit/test_realtime_resource_map.py': 'bbc7361ef3bcd0466f6fb7736774689d585f966b2674075c53187daf60832321',
 'backend/tests/unit/test_release_contracts.py': 'b203bb755055de37099d8f730a0589f907d306db31b0270e3e921f109ca47749',
 'backend/tests/unit/test_remote_assistance_contract.py': '2f67075945cd1ddf6903cae5ea353a01f2624c3341794254b1fd7635e89d8cf9',
 'backend/tests/unit/test_report_roundoff_reconciliation.py': 'ce33a26278832e2857b675ed1206b2acb7927a80369295c67fa5fac4eb859c4f',
 'backend/tests/unit/test_shift_open_terminal_locking.py': 'febc35e22bde2415c718510a0ded65e3fcb1c97853e6658f1d9091b94ad4c0b4',
 'backend/tests/unit/test_staff_role_security.py': '23dd8fe95e76985c8611748c0aca3c44c56dc0411de911ee550a1d93a5edfbc5',
 'backend/tests/unit/test_tip_payouts.py': '5d3576362ec59de498a3006402380b4cf0a686ec3574974f2f80e7f1d3c7ad64',
 'backend/tests/unit/test_tip_refund_ledger.py': '174027721cf0d868cb88e8ba3dc2b6f964c41b1ff4a8b2902132fdd1981bb006',
 'docker-compose.prod.yml': '12a1b839608cc8cba4f13bc9fce3df6b5d773341560ded6716fd1e284f6f8111',
 'docs/V3_1_43_RELEASE_FREEZE_REVIEW.md': '13fc6b02139e3a0c8d2049c34a156eed605a95ae7fe0d05801d08f1cba98c245',
 'frontend/.env.example': 'f946c9a44f9c62235b45e41aea4756d25816c5e0a8590581a3ffb75ce376da5a',
 'frontend/BUILD51_OWNER_WORKSPACES.md': '262754c983aa3d508fbdceb7cd608232d33b48c5fc1ec5028b7d7742d3d7a89b',
 'frontend/package-lock.json': '6a8c94399172a1f54c1985ab1e5179cd771530567dee4a68ce9f8198bcd008a3',
 'frontend/package.json': '5f00cb764a62b50fdacba3441e7c8df579613ff9b00a1217220ea09abcb5ca15',
 'frontend/src/components/layout/AppShell.appearance.test.tsx': 'ab07f4d3e3867f2319bc25d0ec22e756d5315677e8e9fc8a0eb5ef166e003d18',
 'frontend/src/components/layout/AppShell.tsx': 'edfd7211cc95fda1aa1625f0a44eea37e9c681366a7ea5caf6cd1c65778b3670',
 'frontend/src/components/ui/Modal.tsx': 'c6e7721597addd4b0100f18d82126284b7068add0771e4bfb77fae049ff5f57c',
 'frontend/src/lib/api.ts': '1526f20b262b6586a8202dc1c3d50b7b7d370a318659f4d2ee0df4151f19a97e',
 'frontend/src/lib/client-report-evidence.test.ts': '71090b9a0e9bfd8c2a60ac2cb06897914bb326b8d5067f8550e57d3b8724ae71',
 'frontend/src/lib/client-report-evidence.ts': '6028a691f6b34eb2a2078b3038f318c508d304f9c80645155cb0abf6c0141c0f',
 'frontend/src/lib/erp-api.ts': 'f6ce3e2a3226d13e0aa86572d0932dcd13af28ef8dfc60d272c8be9aaf4b806c',
 'frontend/src/lib/owner-workspace.ts': '771b15dda2df82b8975ed5b79ec8a771ab6fd7a483e01bdea0ea4fd3988cf6d1',
 'frontend/src/modules/audit/AuditScreen.test.tsx': 'a117d7baf5b0a316c0d3f462c15f7b2a102e61cc3a4c64db0f817dd3d5f29925',
 'frontend/src/modules/audit/AuditScreen.tsx': '4d3fd4ef35eef10216e6e8be760c616f646344322b4ae55aece5ce201a1994c6',
 'frontend/src/modules/audit/audit-presentation.test.ts': '5ccbc82d01aafd393666c8700d93a45c01fcf3ff61fcf5fe02906e46d8c645c4',
 'frontend/src/modules/audit/audit-presentation.ts': '0fa80d3050778bedb5ff843153492ec418c17edc5e4de6f36586b76eb4b83336',
 'frontend/src/modules/auth/AuthContext.tsx': '8cdbcd3c94281755bfbe0737f7bcd4cc9cf7adf1e004ed4effce6d3519cea782',
 'frontend/src/modules/finance/FinanceScreen.tsx': 'b595b6e3ea263d3a2017c48914f15bd540452e3cd7558abec25511eb73a27031',
 'frontend/src/modules/finance/TenderAdjustments.tsx': 'ec7005dbda587c27c2518e30e5b6bc9a71b45d124eb8ad01fefe2d2e90c82012',
 'frontend/src/modules/finance/TenderCorrections.test.tsx': '1144e84b4e35b5cc5725269e9e786e99f2e7ecb65e2b983cddf5b20bbfd55100',
 'frontend/src/modules/finance/TenderCorrections.tsx': '6ecb28c7ab8a172e188b2be25f996b9117c74519db1af72cdadea433e183e364',
 'frontend/src/modules/finance/tender-journal.test.ts': '843f3f337e9f19bff0dea0b6159b6672c1bfdb9374cc63a818a2add63ec1a82a',
 'frontend/src/modules/finance/tender-journal.ts': 'b03cb79efddc091fb19b5a70529a4407563d67b3ecbd6a95482fc4f952697f01',
 'frontend/src/modules/gaming/GamingInlineShift.test.tsx': '6300abf8601bc97231f0ba36d2df0c0f523ddd74b0e59beed164e1d098eb203e',
 'frontend/src/modules/gaming/GamingPlayerLabels.test.ts': 'a3f941a52dc6dc7822b1a34ff49ab00548a5b53365afc69ab4d8e4abd3fada34',
 'frontend/src/modules/gaming/GamingScreen.tsx': '04707ff630adf19018b8fd512dd36098af7201409fcc666888e46eb1bcb11fa8',
 'frontend/src/modules/menu/ItemFormTax.test.tsx': 'c5570674fbc3366b48548bd87323d8bc20cdd2b1eefbe7d8ae9278ee355b480a',
 'frontend/src/modules/menu/MenuScreen.tsx': 'a414ad681e9a349db956189b8da7d947c6a7fe2048a627ae7e37c3e77d111739',
 'frontend/src/modules/pos/CloseShiftWorkspace.test.tsx': '3b8191769d7c6dec9be1465911b45449d2a223845d2e201205dfd16105296cec',
 'frontend/src/modules/pos/LivePOSScreen.tsx': '6320f2c3fada5d2836eafc85e6de6d7157b7969e0692eca9aca84f38a4d6a69c',
 'frontend/src/modules/pos/OrdersAndShiftsScreen.tsx': '878377b1f34a77562408db94a083abf90bc54e78045aacbe68faf443aaa23114',
 'frontend/src/modules/pos/ShiftAmbiguity.test.tsx': 'f812fa186270070f5b238ace0f338a67cabd1db9912f04a25e25014a196aad42',
 'frontend/src/modules/pos/receipt-search.test.ts': '3a9c8e9eb8b11ae3bbf11091e25b2f2d8e4fa42e2b520b9c63466330a6bb32c2',
 'frontend/src/modules/pos/receipt-search.ts': '39c057223c6b27144e2f0f6b821a16e723732e5b6f75efaa8f0de70bf4360983',
 'frontend/src/modules/refunds/RefundsScreen.tsx': 'dff60a6a90b40b39a5e3b4c6d73e83b43c1879d9bf6c70d8411ac93d32861f86',
 'frontend/src/modules/reports/ReportsScreen.tsx': 'fa67753d8d18e7cfdce12b80add640f60858e01d9d4df6b19bb9fe7858e1c81b',
 'frontend/src/modules/settings/tabs/DevicesUpdatesTab.tsx': 'aaacfa34f6e308529c5710db3ea972da8e5db170295d6f7885afcb113cacb576',
 'frontend/src/modules/settings/tabs/IncidentReports.test.tsx': 'eab732c5ad6ba4fc726d00f02f6478cd2ef7947e1366f0501dee83e8be77b263',
 'frontend/src/modules/settings/tabs/IncidentReports.tsx': '2440697a212f117f1cf7738faab3ab287cf66c79e53c976ae72d80569aa90ebf',
 'frontend/src/modules/settings/tabs/ReportedQueueSummary.test.tsx': 'd29bc8e9169cd56c0c507ce2df50a47703cecaf8d656ae9e6b25a0e8dd44114b',
 'frontend/src/modules/settings/tabs/SystemHealthTab.test.tsx': '491057e6775fac4a9dc241daaf8d3723961d8a5a7fb0869e198ffa8fe244c3ff',
 'frontend/src/modules/settings/tabs/SystemHealthTab.tsx': '9997d58a048a0477c26fd71808f637bd4fec304d1438dfc48fc90e15613761c5',
 'frontend/src/modules/settings/tabs/build51-evidence.test.tsx': 'b6bdf58ed0d2f277f75213af542862e9e8d342eb97aeb4c02f6534ba02988417',
 'frontend/src/styles/index.css': 'd8723e968309303e6d8868699ffd42799e92528ca87192eb2262670132940538',
 'infra/scripts/BUILD51_HISTORY_BRIDGE.md': 'c87fa3697e5ec11c06f604528941924e7e8dfe13cb3fb075408394574bb4b752',
 'infra/scripts/install-on-vm.sh': 'ab066d510d8cac2f4df581e6ed50c30c16bd2840ed7a762b136f18440b9d2471',
 'infra/scripts/verify-build51-post-migration-state.py': 'de464d28e260233e7f8f4869363538635dac846ac57b7e96d1c1d2de16a88696',
 'infra/scripts/verify-build51-post-migration-state.sql': '7ba32fb5fef8eda83866835a57b087b6ec54836f0bcb7991de65da18912f686f',
 'tests/test_build50_pending_compatibility.py': '2f26b80c5f14a7453eb1b32dfdacfc91f3b75af3bd0fb4d643a0140e9a82aacc',
 'tests/test_build51_post_migration_state.py': 'c9ce78ec1c99c95cd94e65d68e7429cdce47c085f8f6ea6c7f33a31eadde45ba',
 'tests/test_code26_physical_audit_lane.py': '545806cbbde2a8c25cce55ab21dbd33e5792a0758345fb393a5dcdbbbfdaa4a4'}

# Additive successor only. Every earlier literal map remains immutable.
REVIEWED_CODE30_4_BUILD52_SHA256 = {'.env.production.example': 'b01a5dd8e17ae2d1eea9f78bb19466b2075e95612d34f137182d6f839d246044',
 'android-native/app/build.gradle.kts': '6bdf0947cab9f5b3488d8596eebe9627f3df997d136a36a1c5ea258783336a44',
 'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': '1bffb4347f6ad04c9c0246e173d3279f2154a38c6aee4459ae23489c7f4d9a0a',
 'backend/app/__init__.py': '152c3bb48ac84d04b3cb7202e6f451b8a99647d1ece8488aa70bff2d51c69fb6',
 'backend/pyproject.toml': '0d250462254880d814900abd9e53a9f4e292594f199d37ba9b8bf41ffeff225c',
 'backend/tests/integration/test_build52_transition_state_query.py': 'd14d54bac4c7b6526213f5a0570bbc2a48d11eb527ef87e10111966a9b71fc35',
 'backend/tests/unit/test_release_contracts.py': '074f97ec92ff093a17f9c20a5dcbb7730d37f20de34bbd141ca0e5ddee506a8c',
 'backend/tests/unit/test_remote_assistance_contract.py': '70f8877e0580a7233b2e4a07b84a7d36021bbfd77c116bc5ce9fe24d82f41a02',
 'docker-compose.prod.yml': '19363d1a1e9da82600be65e6bf0ba2f9e534cf58a942f2e037df06debde75b16',
 'docs/V3_1_44_RELEASE_CANDIDATE.md': '7d56fd3d74bbac7ccac56d5840fc23ed4ec328aebaec632b2ae01786b5bbdded',
 'frontend/.env.example': '203678e582dddae696e774e4132e745e183cc5d4ebe0bba7280beb495cefa605',
 'frontend/package-lock.json': '598c1b51752d009a66b0298940df8c038b7ca3c1e02680929da582a5206a4972',
 'frontend/package.json': 'c1a7e086e73d4506039e0f80ae2a05012ba133adbd143e53805ef6c890b0ebfe',
 'infra/scripts/install-on-vm.sh': 'f430fc504287a615e79d3e63567bbf5f29e57ec90e4d7266482534f2af94c4d8',
 'infra/scripts/verify-build52-pending-compatibility.py': 'fd850d3d08341c93166dbf89ead46f57c93c5f12670221afe2fee8f2a5b69dd2',
 'infra/scripts/verify-build52-transition-state.sql': '92b28d16bbafb83a7b6cba3cae0f6d89caefd6f5fd4b5403c789ec474205b349',
 'infra/scripts/verify-production-runtime-images.sh': '22e444d780b18d81335cc95f202e5e2ab357aa2abd9bdc3fc71d7468893b9fb3',
 'tests/test_build52_pending_compatibility.py': '319482a81bee66824eb75d1fb3c03a88990a8ad7b054558976f4e896a9f59b99',
 'tests/test_code26_physical_audit_lane.py': '95e34c30816dabd8a8b8b3af7f0ba37671440c876485761ac0d35d084fb9ec33'}

# Build53 combines the reviewed product correction with coordinated release
# identity and its exact Build50 compatibility profile. Older maps never move.
REVIEWED_CODE30_4_BUILD53_SHA256 = {
 '.env.production.example': 'e0ed5765b365743f2419af0af1ebcfe0c4231fdc11b5dcc0717fb94e2e1c168f',
 'android-native/app/build.gradle.kts': '3b73a6ead5044d34f9fdc572d0667b33b32b7dcd46e5e80fab9ee5dd20337b52',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/PosEmptyCatalogueUiTest.kt': '05107a6c00d80994e108e0ccd6b220b42f93aa55e227e776f2d12c17f5df58d6',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingBoardUiTest.kt': '3421b119761fd86662d8c6c0eb3c795bb98e02b1c08275f2a8b4ad0d33fa05c3',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingRejectedSessionAttentionUiTest.kt': '3f3d5ac543b17815a7422aaf8f935ad23b227d0a7df5b48c746da0d9ea195929',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/shift/ShiftCompactUiTest.kt': '9bc9bee8185584684ec20fd0674319bc5dc0066c580f285b919440a3be8986ed',
 'android-native/app/src/main/java/cloud/dcompany/erp/MainActivity.kt': '00865fabf5ce52fb7ff441c20974a9494e1968267e555e8bbe1c0896641e49a9',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt': 'caa10835975e8b7bdbae88a51a494368933f122fcae410f46d7dfb4ef66cb9ac',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt': '5c0af5eeaf7dba5b35cb18ca8ea1fd8bdf38d25ef3d221f63313a0c3ff5a07f5',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/audit/AuditLogApi.kt': '2b292421d7672fffb751a0d8a6e0da6fe6ba8df76f708f0fd161d34dbf597e39',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/audit/AuditLogPresentation.kt': '5a1c7db72d2977d666e8243dd123442cadda4a71e1defde277874dcbf45f3a82',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/audit/AuditLogScreen.kt': 'e2fda3b0b3ddc6319875cab49f8b251e0ac36b43f06a28658ba487b99d49c617',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingAlarmPermissionCard.kt': '65e3992eb482b859cab86424372b96ac1955982c620f74bc49288840a4343281',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': 'c749be1623383dced8f64fa3d60a4c8543b3311cf06af93ffa40bf32db822594',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftScreen.kt': 'cb591f86fc89fe802bf238d076937858aa94b42fbaf65b9427f4e71eac0b8c8b',
 'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': '6fb0adf30500f653609ce87c198d0ddded9e888cefc12ec30ba99d22aeab8007',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/OptionalUpdateInteractionContractTest.kt': 'e2cd0b86dc8eba3fd7b79490a551dc4148a7d2ac5e02e6cb7db606838417fa06',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/HeldPaymentFeedbackTest.kt': '153cb59bfe5902890942b7654d22ef2dd2a33d3fb0862e98e064c62335602990',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/audit/AuditLogPresentationTest.kt': '0a091e402f3d26b554a2572af0d96772ef270643657587b4cd2285ba02a77f51',
 'backend/app/__init__.py': '93e54ab9aac7da107787fe11dfd2e79566c070cec2759b5bdf1ecf6981cea014',
 'backend/app/api/v1/admin/router.py': '38ce58d44e3e36d9a6ec7166a36ec74e753e3a727a3fc2140e549a9c88505c77',
 'backend/app/api/v1/pos/router.py': '2ee913f90c9cf59d013cff7f7451e4839aca22af2368fbe2376be311aa92a669',
 'backend/app/services/audit/catalogue.py': 'dc6574973a63d4d498ed5376b7408be8d1a5f52b43ac8cbe2c2a5f5a772a5536',
 'backend/app/services/audit/query.py': '7763f7d1925c7c5047e582f775490896fdbc21390ee2d99c765df52d4e3539f1',
 'backend/app/services/audit/recorder.py': 'f9140def155ae7a7f12f656b44ec08cef9abf167db2e41bf2a7939b3156b0d01',
 'backend/pyproject.toml': '92aa6a20966de3709f3f3b8c66e301aad876ae9668f39809b38701cc53cccce0',
 'backend/tests/integration/test_audit_business_events.py': '3d2ed06a3d9030b7947772d75794ddc38c61b96e7825b157cb415fa89a70e71a',
 'backend/tests/integration/test_pos_split_payment_bundle.py': 'd91113a119bf01afeb052e53b830dad8fdce3177fb97cbae06d245360f18b94e',
 'backend/tests/unit/test_audit_business_catalogue.py': 'e90b7015ab5745eb39805e38a3fb7464466465e53bd3d850dc13d9f4755040af',
 'backend/tests/unit/test_checkout_claims.py': '8439967c3b562d166ee145e2fefdae048af11b4acce98474ea4ebf21535f640c',
 'backend/tests/unit/test_operational_route_integrity.py': 'e76c122b77aa1e2081e6f917d78db944a3dcec6b30d96a906bdc78aa5a63e15c',
 'backend/tests/unit/test_release_contracts.py': '2a4726bff2b9121b666e4e7eabef89012ace8742698914814ca04188a7e452b4',
 'backend/tests/unit/test_remote_assistance_contract.py': 'ad8a5acb917097a92916cc5cea11b830e1d3bc37556e6439baf2e92250aaa8db',
 'docker-compose.prod.yml': '6caa0ff6099e50e050cae397bbefb34e9122636eacde6d861f526115a2d835ac',
 'docs/V3_1_45_RELEASE_CANDIDATE.md': '91fe6c021a71388207a2335138ed2838c72761294780c6eeb0930008d024173b',
 'frontend/.env.example': '13dc223d2d80cb9869f0ef498b59fbfb69deb33a345e2d95c8a25724c8dc306e',
 'frontend/package-lock.json': 'c62dab972dc2ce1278c272abeef6917de24dfb38b19751cb018d5b6a1a2c7c3d',
 'frontend/package.json': 'ebe8fa8912ab82783378b24f2db10e3d33ee5cede619852f7354b393cf174e1b',
 'frontend/src/components/support/SupportLauncher.tsx': '433afd403ff97d412215ae357ddbde8a64de72228e2d3b34e78618f790bdabbe',
 'frontend/src/lib/erp-api.ts': 'c8ffd5cff9784628e4a02bd639c4fcea3969dda057b8fbe678c4100a8b56dfc0',
 'frontend/src/modules/audit/AuditScreen.tsx': '58b599cf252ad5b9628af9970618c1f644a02dff10c19950dd1b6f303be0975c',
 'frontend/src/modules/audit/audit-presentation.test.ts': 'fe60f0399a088c7d0ec4450815508618e50d714fde9ede5fb8acb585498176d8',
 'frontend/src/modules/audit/audit-presentation.ts': '35b42cdff8bfd69070153c5a96a46cd8522c31cba92cb354cc69b4452c3fe2f3',
 'infra/scripts/install-on-vm.sh': 'edc29d2c0f6624796ad6bdc850f4298f1dfc011dd0c51939db5423bd4b84e0d8',
 'infra/scripts/verify-build53-pending-compatibility.py': 'b0cfe1462ba0684b4668040a4113a49997920ea7efec2f1284dcb6cc23930adf',
 'infra/scripts/verify-production-runtime-images.sh': '2ec5c2efc4c33e5468e10b472506e3776065fb4d1c9d12486dca6a18091c5946',
 'tests/test_build52_pending_compatibility.py': '50fdbd09d3cf5b197491c73edb8a5524111ab5397d3f14fcd9f2845dd8f8c688',
 'tests/test_build53_pending_compatibility.py': '9b6075f56b8e133133890abeda3ee60a19d04d5f11755098a1e57cddf0296f43',
 'tests/test_code26_physical_audit_lane.py': '99ae6845d349169ca3bd56d65dd01ec4466e4793633b68ace20ecd706af3ed8d',
}

# Build54 is an installer-profile repair with coordinated release identity.
REVIEWED_CODE30_4_BUILD54_SHA256 = {
 '.env.production.example': '2904d9f1a2ab292b70fad4d5286b2541a8b2ab8d8ad1f6981f8aabacb50d1fac',
 'android-native/app/build.gradle.kts': '14382ee56e66f0ce3ced7abd5595c9ed0771a655296a0094719004b6e63fb0f6',
 'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': '1dbb7ce4c86416a969bd006bc6772d7c1956428eb0d20a3de332d384eeb0b60a',
 'backend/app/__init__.py': '2be8c46099859888fdde962f7eb7364592a5222aeb40a4f975480c311731dae9',
 'backend/pyproject.toml': '96a0ab2f6513fc039c92c3d7b1a35ff8328e7e93296668d17303304d425e595f',
 'backend/tests/unit/test_release_contracts.py': '2e72266566cdbfdcd372ea701ff19c70eaeacdce371bd360a8be94e2d16af4c3',
 'backend/tests/unit/test_remote_assistance_contract.py': 'a2a2f941fbc27d40a50a5e6b1cc47a291dd1259d579d9b9bff883581a9180e7a',
 'docker-compose.prod.yml': '4f999db66a4acbbf2306fbecc7640794f600ee85055923dca2ca0eabbb7d9bc9',
 'docs/V3_1_46_RELEASE_CANDIDATE.md': '90d1b82267d37c69650528894eb565ca52ad18474ce317d6cbba2b7e6a34a775',
 'frontend/.env.example': 'e9f379a4306dcd6f5c2384aff1ea25ff635dab21ac0f7eba7f9cca1c09001571',
 'frontend/package-lock.json': '06e7d3764cde095e2b1f43090727e2ed00ae69d010e10b532f159d4a33e35feb',
 'frontend/package.json': 'bb3111f7495ed1e576aa6d66fe41cb19d89385d6ffd4ef6c192e9ce16dea731a',
 'infra/scripts/install-on-vm.sh': '2d5e4ca6ad96f5f3cef201f49939b30868627fc27fbc4c8ad02c16f6b1dd9482',
 'infra/scripts/verify-build54-pending-compatibility.py': '2bb6be2095aa961e672fb67461d5c204b3d6e222a0af3dc1af9546fd5c67129c',
 'infra/scripts/verify-production-runtime-images.sh': '6b49c2529396c1a493fefdef4ff8e2375b9e4a28b567de98293d8a48954bcd28',
 'tests/test_build53_pending_compatibility.py': 'aac6a7dc3570d94d725dc9e9579d8bbbe537267e8478dd5fe58232a142ba5e2a',
 'tests/test_build54_pending_compatibility.py': 'ad680cd5bc678982b28d1ed596ac7089e924f40cde74cf916ab81960cea24c7f',
 'tests/test_code26_physical_audit_lane.py': '65998d03ee6f60e6744f2d1ec719cfe951864a0c2a32cd86c99b06fc40ef5665',
}

# Build55 is a separate reviewed candidate overlay. This does not admit a
# deployment or replace any historical map/signing/physical-tablet evidence.
REVIEWED_CODE30_4_BUILD55_SHA256 = {'.env.production.example': 'b45aa3dc9492aa07c83ac9ba7960a93b67bd14b4eedee8753ced79d5722a58d5',
 'PROJECT_STATE.md': '4413405f5d85df1c5f6796b1d02bc6b121c2b96dec0a76ddf50365dc72049bc1',
 'README.md': '17757126db2b6105350847bf3b8c7e747cfe1cb557ffde70de1dd8a87f9e5c10',
 'android-native/app/build.gradle.kts': 'cd86d0c541e9b684e7063729645532565478a6f6b5dcd53cc309b69884c7c4d1',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/GamingSessionAddonDaoTest.kt': 'f31f845f8f8136da4efc50fdae0aecbc06380eb687b7ec66f3243dded2b785ec',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/ShiftCloseSafetyDaoTest.kt': '3817213d1be5f1b070049ed040f10db6477b5661a669009a48d20a4f1a0335c1',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/core/net/ClientPolicyPersistenceTest.kt': '72943cb3f42f9b70ffcc15281e16bfd0c584820a7ab38bfb5e09cec9315c314a',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/OptionalUpdateBannerUiTest.kt': '9152e694860c9b887c65fee69a7e25fef7acc85f56fced40d0c8dee64602eae2',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/PosEmptyCatalogueUiTest.kt': 'c68c1f53fcdcdc9617d2e83180b6d1637c0d59cf2521a6d17d05381ebdc632da',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingBoardUiTest.kt': '2eff07461032a2b9101c01f8c9df47d951a27aaac186210ee22907754719ceed',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt': '5cb1aa5a4e3ace3a6f03a3a45109c8416e0bcd0d6ff0478927238312026c15d6',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDraftRecreationUiTest.kt': '9c91a39b6e329d01be11c3e0cf77dd88e6d2566432f2224995e0d8e51c96d9b8',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingInlineShiftWorkspaceUiTest.kt': 'eb650740d3629d5fbb911777add886d68b70b44849c9d403782e29ae947db456',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingRejectedSessionAttentionUiTest.kt': 'ffa53bd3413aa99d7ff176f8ebf4b1ac740d26438f38834aa5eca0f29ef28f0d',
 'android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/shift/ShiftOverviewUiTest.kt': 'f7537b87185f6aae42a7a9356ab01fbcc20608a812b5b3029babb49edd33ae19',
 'android-native/app/src/main/java/cloud/dcompany/erp/DCompanyApp.kt': 'cf983855629ef350fd595a8eb43be7b8c6cecef94ab3f0caaa416936eac0a238',
 'android-native/app/src/main/java/cloud/dcompany/erp/MainActivity.kt': '534e42f1894748fc7f39c729bb605915e0c43c0e2760ceec257f6537368668cc',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingDao.kt': 'f466ab3ccffe9c30fd5d2c695e0c84bcccf943f1db674983876d90936a3f26b7',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/db/ShiftCloseSafetyDao.kt': 'fcc97e2daef5249c2406765ce4128a1543933ea54687c24c0ed0fa5b5d4e5e87',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/net/ClientCompatibility.kt': '4be5aece9d7c4871be27b38cb6479147f30ff390e93e095f0b8feb2fce8727ac',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/net/ClientUpdateRequirementStore.kt': 'bac6e8db48564d844ced608074578aec7ee937fd8cff31be29dcd1704eefa5a6',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/net/Dtos.kt': '7a464d2baf20453e137abcc3e931f5312d53b7ebbced3b94c1b7a4f118663bb0',
 'android-native/app/src/main/java/cloud/dcompany/erp/core/net/ForegroundCompatibilityPoller.kt': '89ce867d330ff7aa64f5b31f588590c88276d8f473180c37d47f2e6449383ffa',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt': 'c916d9fb5760b10d68b53d9f8b3dab055fa362bf10fc2a2990b0d7b221c43a7f',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/audit/AuditLogPresentation.kt': '0b0610cdbeabc42c1f00f135e5d079b34ac483c1ba96e11eb00ea7f85df9e353',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersScreen.kt': 'eb607baeae1c62c86cbae0d0d497aeaa7c7ef7737faafdf1851094a034abb63e',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingAlarmPermissionCard.kt': '988d73f6c036510859ddd4a721505995ef3fd5e74fdbb3b868d95058e35db458',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingApi.kt': 'cc245f39f4879396d6d3e4fd2623335923d77f08dbea9675741774a2af7d71ac',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDial.kt': '6c46e9fa71d17d9875e663ca86b6faaf4e33613bb70e54f9e367495205f40ec8',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': '6e404a81aef3e0f2e04d29a1a4286fb5721691d8e0e6b6675bf2a9e4293674b6',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt': '4779b956a64de32705ca3db7c488849080f546c5f13c31ef2395a9cb2d76d2c4',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/AndroidUpdatePolicy.kt': 'd2bf5a4fc449146579272791b87170445d642b757f2db4c8a3db86d921339ab2',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/DeviceHealthPanel.kt': '8efb513d8de5e18ff404b59e370d0c8583d25065040e669e27f08a37fc574130',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SettingsScreen.kt': 'a81605419df19ff67985c5057c62bb2147caf829a2ac5e49aaf4bcca3b2053de',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftCloseReviewPresentation.kt': 'aa9eb9353b4094c63e38ef1cb9640b75cac0ae330ff7823fdbe567f4dabedd25',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftScreen.kt': 'ee8942c460f601acf54955649aa2f399e80d8e1e0e4f4164c8974aa7e5fceb0b',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftViewModel.kt': 'f4548849e626b493bb0658485d465a2fb689b4b9e2ef6ae3ec60cd7864a26a5d',
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/theme/Theme.kt': '6768f52eb06a5d82317c1cdbede86d160af9f477e334654f147279fb77ce9215',
 'android-native/app/src/main/res/values-night/colors.xml': '27317dd7a62f68e64c483e78d8e02ad6c23ecdd521965c623e83ff6904e77d77',
 'android-native/app/src/main/res/values-v31/launch_themes.xml': '14d838e0a803452808e3aa1309b4b2ff7e57a309015bece89b44b39e5b1b9875',
 'android-native/app/src/main/res/values/colors.xml': '69b8d2499e49710410e33ee358cf07ae5a30c94f31a22972a087d80dc9c9fc2b',
 'android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt': 'be71330e492e057345f07bbaaf0b07855b94b13ffd2fc96506ce582bfa48486f',
 'android-native/app/src/test/java/cloud/dcompany/erp/core/net/ClientCompatibilityTest.kt': 'a7197144e4c0fddc500b195ae8c657b4eb9b32b60d6f0eef654f6a19d290ab20',
 'android-native/app/src/test/java/cloud/dcompany/erp/core/net/ClientPolicyMonotonicTest.kt': 'd820f1ad1d362c9a6e75fff1070d590e490ee8b6fa84d4b8030f9cebcf4c1033',
 'android-native/app/src/test/java/cloud/dcompany/erp/core/net/ForegroundCompatibilityPollerTest.kt': 'a47f7e9aec7cb7309fa85959527e3f7913d787136a05cfe23fb62b65c84dbf66',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/AndroidPerformanceOwnershipContractTest.kt': '12f94b32ff0b35b0b44a2c53cf8e223f08ef1da7beae603cf98c4171ac274c00',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/OptionalUpdateInteractionContractTest.kt': 'e63e689b4ef81f7f89f356e82b80240b8cf847953d4cf3816e8c7eafb0ba9d4f',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/PremiumFoundationContractTest.kt': '5799d8cfb6f0b1b7972eb5708f2129efd161fec8067d1163a8f7db6d4c08c6d9',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/PosReceiptPrintTest.kt': 'e331836b2effdf4e151240d34d0c18e115bdb72aa7189672bc27b427fe503056',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/audit/SemanticAuditPresentationTest.kt': '35ff2d4af1853af9f870bbafb23cda5fdcdd58144f6d7a9d76c58f325f2062da',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDialHitTest.kt': '814398f6e1dd49e878359ba94c1f1327b5481a7b54dfe59c088433440391862d',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingParticipantAmendmentPolicyTest.kt': '05516bae8d1c686d3876a3b3c58f052701ab437c8950122938bdc7c31864d14d',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModelRecoveryTest.kt': '87f16cdcd80fb2cdcdbb4be941f5b63400619fbdfe00e4cdfa118f902bb9dacd',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/settings/AndroidUpdatePolicyViewModelTest.kt': 'a6ab543eb40183b47a90e9f556687dd9b2084c635a632757346edc62132196e1',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/shift/ShiftCloseReviewPresentationTest.kt': 'a62bbda88daa9f7f39352fe45e23f3e2f3513c3bc485267c7230e17fe42af9dc',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/theme/LaunchAppearanceTest.kt': '3578ea9971b14192f5c257c32973edaebc3a57fdf84091a469a452c315b53448',
 'android-native/app/src/test/java/cloud/dcompany/erp/ui/theme/ThemeContrastTest.kt': 'f34c8be9c4c4fcae57ff1fc5386754a147eea3ebda0965f615f6530730ff8533',
 'backend/alembic/versions/0092_android_update_policy.py': '788375ba2354f1f4ad5c9cacfe696022e7ffb7e2e7b7ca9e31cd41e48e7ae477',
 'backend/app/__init__.py': 'a02f7b68b18f91b52bfd121da83f32af5b31cb9382edaf653e6b1d73344ac1aa',
 'backend/app/api/v1/client_updates/router.py': 'ef875b833be5af3a7a8726518a4fb78cac15bcad4dd46a9a53168ec7189ac721',
 'backend/app/api/v1/gaming/router.py': 'c0964766588be29c6ce652e5358c9dd874d114a01649c256a17bcd3578b759f4',
 'backend/app/api/v1/public/router.py': 'af2331e48f7ab202140579d71921519d72e72f6a3734467f3863a90b6916cbd8',
 'backend/app/core/middleware.py': '945ba916e341be6f6bfe4814c48ae0d816191e9070c4f3d60d9b66629bf9255e',
 'backend/app/main.py': '9f72611cf14ffe149de53493f61f1140d58ed0b4a9db427d327df70fa91f9fa2',
 'backend/app/models/__init__.py': '002553bbbe5c0f43d2f2bbfc592ca3ad42d6c481166ab8cda687cdba3ec1ab28',
 'backend/app/models/client_update.py': '30e4dc949f33261b3d097834db7a6a0fc6cc13d1e8d015a1d5175ce936082c0a',
 'backend/app/services/audit/catalogue.py': '47d6685342ace7f64f5eba1d72702593daac2bea8ae9a2a9c792ad01af9f0e3d',
 'backend/app/services/client_updates/policy.py': '0862e7f44a6c04f70af5078093ce8e85a05a62e7f18dfe835cce86f51cf4fdb2',
 'backend/pyproject.toml': '19cf01583da18b8cec0eb4aac7f5340b18653c478cd27f6a0eee987ee180ea67',
 'backend/tests/integration/test_android_update_policy.py': 'fe4cc44ddad198ebfacbb848b8d230f0f209ffe8417291bcebb707043065e3e2',
 'backend/tests/integration/test_android_update_policy_migration.py': '127429bbfd0138246024d8bcd06f75662d906e7d4b533ce757a8ececd75d039c',
 'backend/tests/integration/test_audit_business_events.py': '5724ada0510314695d0c844e81ee7748118487f1a696154c3a968fc5ebc23a09',
 'backend/tests/integration/test_build51_history_bridge.py': '17a86e5a7ada89c0511ea7847f340acdbaa2adc2a6eaef79ef442d3feb729d1a',
 'backend/tests/integration/test_build52_transition_state_query.py': '8ed39e673fb07bde740fc579b33c5bc8a60682e0d6a0f08d1b1cf2cd76ec5c6b',
 'backend/tests/integration/test_build55_transition_state_query.py': '36e1eeb042eb11f7203b877e2f55940f5842736c86dbdef07c3b2e9d1722e749',
 'backend/tests/integration/test_gaming_participant_void.py': 'edb6af934d0c2aaa6a7ce686c70459b74a8edcbb90c83bddcfddd09eddbdf4ca',
 'backend/tests/integration/test_tender_correction_migration.py': '3568620720787d92e71455daef2ac22879c6f51600b6fa8e88b8f06124aeb3ee',
 'backend/tests/unit/test_android_update_policy.py': '0d9c0ca03789e6e1efc333c31f3e755d0721e154419bfa6f57bebef46f171fc0',
 'backend/tests/unit/test_audit_business_catalogue.py': '9b93d156415ad44f1a49aba0a900ff4f3e1f95f73d5e2d3483e3ad0276021a94',
 'backend/tests/unit/test_client_compatibility.py': '9710b9136c58bf962814a9b2f7b03b53ab596bb888104844eff686379eeca70c',
 'backend/tests/unit/test_gaming_reconciliation.py': '3d56b0522a545c46a654b6f06ca33ca541c4a78762a875a75d2439944cc244ee',
 'backend/tests/unit/test_operational_route_integrity.py': '569480d93aee33c47172aa95ad25860dd92dc712c2a307849ee92b51eaf81d16',
 'backend/tests/unit/test_release_contracts.py': '0e335e02943780c4a24034714f759b65be1c805f65ec672c5b68b3c2b7e0bf2e',
 'backend/tests/unit/test_remote_assistance_contract.py': 'aee97434ba24ee30641d71a1895428fa025976fca5b6e1a26b900f3d8201e9a1',
 'backend/tests/unit/test_runtime_release_parity.py': 'dce44ab147f28bd95da7fe2ba8380abef9214c3340a07190acbc2dbaaf866e28',
 'docker-compose.prod.yml': '07c6c619e450a85a60aac2c5269bd9c194b3487744908a1115eeb023e5f4629c',
 'docs/BUILD55_ACCEPTANCE.md': 'a09edb810141a10f75b9fdad9624e5a36dee8f9c7f608d880f5e32732c359a97',
 'docs/BUILD55_GUARDED_DEPLOYMENT.md': 'd826ee7d6cb6f0c456754ca1f34a11b7c46449b855073d01c2dc48d663c155cc',
 'docs/SERVER_DRIVEN_ANDROID_UPDATES.md': '1bb9745df4b5778eeb5fc82397c21376a427090f8a9056f1aa4f9b34c1ee0c1c',
 'docs/V3_1_47_RELEASE_CANDIDATE.md': '52bb659a8f6ae49d17eb4033609df2121dc52acabc311b9a29f1d8e1a9c7ce6b',
 'frontend/.env.example': '25ecc546c19ab44b9638397be1944adffeb12fe673c9228cc2e1496349d0115f',
 'frontend/package-lock.json': '01e096f2d803cf7aa7969aacba7cae50335df4c3ff3832d6f8a1725bca1d9bf8',
 'frontend/package.json': 'bb3abab9171cd8fb3440ad4f485dcb5e8e5399bab4365398b1bbd9575ca2a359',
 'frontend/src/lib/erp-api.ts': '97c90fc1a0b444ccf1e307e3e2b739506410dadaa7f02a9c1e989877ddb04449',
 'frontend/src/modules/audit/audit-presentation.test.ts': '4066609b294c7535b63e6e76e335ad5f7b8b317c4618b86fe8c8506c7c9133de',
 'frontend/src/modules/audit/audit-presentation.ts': 'a77f388154268f1e863aa1e94b3fdbcf9b897ca78ff4a0fae64e13886fb75918',
 'frontend/src/modules/gaming/GamingCancellation.test.ts': '138769912918ab3f93ee14c8b55bfe4354c5a7c7f1fac42c25835c6bf69e2b0d',
 'frontend/src/modules/gaming/GamingCancellation.ts': '7fe102c751e0889aae42534461fff68d1e9ff6b2b3c9079e5c1218ff62a8c474',
 'frontend/src/modules/gaming/GamingInlineShift.test.tsx': '390e30e268363428ca7e811bcff7a9a282d0554128245d6b294c83926233c792',
 'frontend/src/modules/gaming/GamingScreen.tsx': '38eae86dc82975ed4d4d14e74c683dc5f7138c67865c816f47c18af8f4cb2eb7',
 'frontend/src/modules/settings/tabs/AndroidUpdatePolicyPanel.test.tsx': '4e030444b5bd4e9db67a0b57216a6b6a6dfc83fa29c160ac8e6138b9e7fed20f',
 'frontend/src/modules/settings/tabs/AndroidUpdatePolicyPanel.tsx': 'f8270db937469511028c3e3de05eca5dbb45a7416b38f770baa65a8ed49882bc',
 'frontend/src/modules/settings/tabs/DevicesUpdatesTab.tsx': '3219df29ac30de2ae5fdf18fe1b073d20b3e2ad4a25e271ba8190c0dbbd3f547',
 'infra/scripts/capture-build55-runtime.py': '0dd1b4474229b5a57ab640a8399f9231bc0ff90f03cb6780823e61016e07a00a',
 'infra/scripts/install-on-vm.sh': '2dc40a5bc93a53aaa278407e70a237c755b9954776bd2fd51c2eb7e96f5afc4b',
 'infra/scripts/verify-build55-authenticated-acceptance.py': 'f4d59c7f967b6cc7cc4176bf3f6729fbd70f321007185697dd9fcea84401b417',
 'infra/scripts/verify-build55-pending-compatibility.py': '17cadea993efc6290e099214f6538325a9b1b1d6a80e4c7f287a96fb074347a6',
 'infra/scripts/verify-build55-transition-state.sql': '0e7a5b6a0aa636ad9792c4855d437528b7f8c54cefa178e5662d4f3c474fe0b1',
 'tests/test_build53_pending_compatibility.py': '68301cf1e8eafe8b3c3258f956c41022a53edf00296f169d98c5383d438e182f',
 'tests/test_build54_pending_compatibility.py': '7e80fa9dc3d27f9975af59856b33bfce944f88720fb2e9661384cdf6ba45cab2',
 'tests/test_build55_authenticated_acceptance.py': '8d8e8239ac42d019e9b2c9b919dfae20ebb77e14c89a4952fa4134837f824923',
 'tests/test_build55_pending_compatibility.py': 'dfd3e28518987816e3c6ed527a0c4d904a5f235fa9132960cc33928cc584b52c',
 'tests/test_code26_physical_audit_lane.py': '9706544d6bde4b4f07e717dd5be6bdd6bd3a5bca5207e19c20111b218e774fbe'}

REVIEWED_CODE30_4_PRODUCTION_PATHS = frozenset(
    path
    for path in (
        REVIEWED_CODE30_4_SHA256
        | REVIEWED_CODE30_4_HOTFIX_SHA256
        | REVIEWED_CODE30_4_BUILD41_SHA256
        | REVIEWED_CODE30_4_BUILD42_SHA256
        | REVIEWED_CODE30_4_BUILD43_SHA256
        | REVIEWED_CODE30_4_BUILD44_SHA256
        | REVIEWED_CODE30_4_BUILD45_SHA256
        | REVIEWED_CODE30_4_BUILD46_SHA256
        | REVIEWED_CODE30_4_BUILD47_SHA256
        | REVIEWED_CODE30_4_BUILD48_SHA256
        | REVIEWED_CODE30_4_BUILD49_SHA256
        | REVIEWED_CODE30_4_BUILD50_SHA256
        | REVIEWED_CODE30_4_BUILD51_SHA256
        | REVIEWED_CODE30_4_BUILD52_SHA256
        | REVIEWED_CODE30_4_BUILD53_SHA256
        | REVIEWED_CODE30_4_BUILD54_SHA256
        | REVIEWED_CODE30_4_BUILD55_SHA256
    )
    if path.startswith(("backend/app/", "frontend/src/", "android-native/app/src/main/"))
)


def _current_code30_4_sha256(path: str, fallback: str | None = None) -> str | None:
    return REVIEWED_CODE30_4_BUILD55_SHA256.get(path) or REVIEWED_CODE30_4_BUILD54_SHA256.get(path) or REVIEWED_CODE30_4_BUILD53_SHA256.get(path) or REVIEWED_CODE30_4_BUILD52_SHA256.get(path) or REVIEWED_CODE30_4_BUILD51_SHA256.get(path) or REVIEWED_CODE30_4_BUILD50_SHA256.get(path) or REVIEWED_CODE30_4_BUILD49_SHA256.get(path) or REVIEWED_CODE30_4_BUILD48_SHA256.get(path) or REVIEWED_CODE30_4_BUILD47_SHA256.get(path) or REVIEWED_CODE30_4_BUILD46_SHA256.get(path) or REVIEWED_CODE30_4_BUILD45_SHA256.get(path) or REVIEWED_CODE30_4_BUILD44_SHA256.get(path) or REVIEWED_CODE30_4_BUILD43_SHA256.get(path) or REVIEWED_CODE30_4_BUILD42_SHA256.get(
        path,
        REVIEWED_CODE30_4_BUILD41_SHA256.get(
            path,
            REVIEWED_CODE30_4_HOTFIX_SHA256.get(
                path, REVIEWED_CODE30_4_SHA256.get(path, fallback)
            ),
        ),
    )

# Separate from the signed Code30.3 release inventory: these are reviewed
# post-release maintenance tools only. Their exact bytes must be pinned before
# they can coexist with the immutable v3.1.30 application in this checkout.
REVIEWED_POST_CODE30_3_MAINTENANCE_SHA256 = {
    'docs/CODE30_3_COMBINED_CLEANUP_HANDOFF.md': 'd9477f880b06bd58e8ae3478b01a8c3f72478e8455587edaf6478fa96c734717',
    'infra/scripts/apply-code30-3-combined-cleanup.sh': 'c45dcc346f4bee884632e597f7ef3a081423d25f8c8bf25ed07f0c137c29c417',
    'infra/scripts/apply-code30-3-combined-cleanup.sql': 'fe4f9617577f5c9c8e3b94d9e41cfa4507ed15604699b6de8c7c915761e772ea',
    'infra/scripts/code30-3-combined-cleanup-body.sql': '76f7cbb82366705a5a2b2bc4346b0c7a6f9735ddb8ba0acc2965cea67ee799db',
    'infra/scripts/code30-3-combined-cleanup-lock.py': 'a8099996145c8d4a0a9a5c4d5fce577e9fca3f330c40d1a7b9af313afb8d2f89',
    'infra/scripts/code30-3-combined-cleanup-runtime.sh': 'e2a9fcdd22883ecb0096db9b9e733db3a0c4b3b3dfc7a0a6d5d6e17a38499c25',
    'infra/scripts/generate-code30-3-combined-cleanup-candidate.py': 'ed1e86fc08c072e81a5622f8cc8451ca2f9e17dfa9c575a897c6e35ebd746cd4',
    'infra/scripts/postcheck-code30-3-combined-cleanup.sh': 'f598cbd1ad91bf80fb13b6bce3a9c3e2fb5a6d5c7e0dd36166f8cc070c0076c4',
    'infra/scripts/postcheck-code30-3-combined-cleanup.sql': '9bf08364de1c12158f6829d4c269d49eea5d31bfa4d65641286f8e5bc3cf7668',
    'infra/scripts/prepare-code30-3-combined-cleanup.py': 'c597cdac38b1b6ae09acfa0b7c1f066b585aebafeae1e1cc0d397f2904c8e983',
    'infra/scripts/rehearse-code30-3-combined-cleanup.sh': 'a0d31c840662cbd38d19b81da09ab79d92d3e28581586a991cbe187afd7f53ab',
    'infra/scripts/rehearse-code30-3-combined-cleanup.sql': '661afcb57c2f53d8904bfa6ffe4b890e1c18cb0fb2ad91f3c4cae8e7a61b7200',
    'tests/test_code30_3_combined_cleanup_preparation.py': 'bea67ede3c8d2e59fcc6fdbdb7a95dc608513fcfe4502d2edc23468b4e63970c',
}

RELEASE_IDENTITY_TESTS = {
    "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt",
    "backend/tests/unit/test_client_compatibility.py",
    "backend/tests/unit/test_release_audit_fixes.py",
    "backend/tests/unit/test_release_contracts.py",
    "backend/tests/unit/test_remote_assistance_contract.py",
    "backend/tests/unit/test_runtime_release_parity.py",
    "tests/test_android_runtime_parity.py",
}

REVIEWED_WEB_AUTH_PATHS = frozenset({
    "frontend/src/lib/api.ts",
    "frontend/src/lib/realtime.ts",
    "frontend/src/lib/api-cookie-session-renewal.test.ts",
    "frontend/src/lib/api-session-renewal.test.ts",
    "frontend/src/lib/realtime-auth-renewal.test.ts",
    "frontend/src/lib/realtime-lifecycle.test.ts",
})

REVIEWED_PRICING_PRODUCTION_PATHS = frozenset({
    "backend/app/api/v1/gaming/router.py",
    "backend/app/services/gaming/tariff_catalog.py",
    "frontend/src/modules/gaming/GamingScreen.tsx",
    "frontend/src/modules/gaming/gaming-tariff.test.ts",
})

REVIEWED_PACKAGING_UI_PATHS = frozenset({
    "frontend/src/modules/settings/tabs/DevicesUpdatesTab.tsx",
    "frontend/src/modules/settings/tabs/SystemHealthTab.tsx",
    "frontend/src/modules/settings/tabs/SystemHealthTab.test.tsx",
    "frontend/src/modules/remote-assistance/DeviceListPanel.tsx",
    "frontend/src/modules/remote-assistance/DeviceDetailPanel.tsx",
})

REVIEWED_CODE29_2_PRODUCTION_PATHS = frozenset({
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/Dao.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingDao.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingEntities.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/Migrations.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/sync/SyncEngine.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/FeatureProfile.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomerPlaytimeViewModel.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersApi.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersModels.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersScreen.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceModels.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingApi.kt",
    "backend/app/api/v1/customers/router.py",
    "backend/app/api/v1/pos/router.py",
    "backend/app/models/__init__.py",
    "backend/app/models/customer.py",
    "backend/app/models/gaming.py",
    "backend/app/services/audit/recorder.py",
    "backend/app/services/customers/__init__.py",
    "backend/app/services/customers/identity.py",
    "backend/app/services/customers/playtime.py",
    "backend/app/services/pos/customer_identity.py",
    "backend/app/services/pos/membership_benefits.py",
    "backend/app/services/pos/points.py",
    "backend/app/services/pos/pricing.py",
    "frontend/src/app/App.tsx",
    "frontend/src/lib/erp-api.ts",
    "frontend/src/lib/product-profile.test.ts",
    "frontend/src/lib/product-profile.ts",
    "frontend/src/modules/customers/CustomerPlaytimePanel.tsx",
    "frontend/src/modules/customers/CustomersScreen.tsx",
    "frontend/src/modules/customers/customer-access.test.ts",
    "frontend/src/modules/customers/customer-access.ts",
    "frontend/src/modules/customers/customer-playtime.test.ts",
    "frontend/src/modules/customers/customer-playtime.ts",
})
REVIEWED_CODE30_PRODUCTION_PATHS = frozenset({
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/CustomerDao.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingCustomerSearch.kt",
    "frontend/src/modules/gaming/GamingCustomerPicker.test.ts",
    "frontend/src/modules/gaming/GamingCustomerPicker.tsx",
})
REVIEWED_CODE30_1_PRODUCTION_PATHS = frozenset({
    path
    for path in REVIEWED_CODE30_1_FEATURE_SHA256
    if path.startswith("android-native/app/src/main/")
})
REVIEWED_CODE30_1_DELETION_REPLAY_PRODUCTION_PATHS = frozenset({
    path
    for path in REVIEWED_CODE30_1_DELETION_REPLAY_SHA256
    if path.startswith(("backend/app/", "frontend/src/", "android-native/app/src/main/"))
})

ALLOWED_PRODUCTION_PATHS = {
    "backend/app/__init__.py",
    "backend/app/services/auth/refresh_sessions.py",
    "android-native/app/src/main/java/cloud/dcompany/erp/DCompanyApp.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/PersistedStartupState.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/alarm/AlarmReceiver.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/alarm/AlarmRescheduleWorker.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/alarm/OperationalAlarmRuntime.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/auth/CacheScope.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/diagnostics/DiagnosticOutbox.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/diagnostics/DiagnosticRuntime.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/money/MoneyInput.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/net/ApiClient.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/quantity/QuantityInput.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/sync/BackgroundSyncWorker.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/SessionViewModel.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/components/Primitives.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/components/QuantityField.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceScreen.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/inventory/InventoryScreen.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/BugReportOutbox.kt",
} | REVIEWED_WEB_AUTH_PATHS | REVIEWED_PRICING_PRODUCTION_PATHS | REVIEWED_PACKAGING_UI_PATHS | REVIEWED_CODE29_2_PRODUCTION_PATHS | REVIEWED_CODE30_PRODUCTION_PATHS | REVIEWED_CODE30_1_PRODUCTION_PATHS | REVIEWED_CODE30_1_DELETION_REPLAY_PRODUCTION_PATHS | REVIEWED_CODE30_2_PRODUCTION_PATHS | REVIEWED_CODE30_3_PRODUCTION_PATHS | REVIEWED_CODE30_4_PRODUCTION_PATHS

PRODUCTION_PREFIXES = (
    "backend/app/",
    "frontend/src/",
    "android-native/app/src/main/",
)

DISABLING_PATTERNS = (
    re.compile(r"@pytest\.mark\.(?:skip|skipif|xfail)\b"),
    re.compile(r"@unittest\.(?:skip|skipIf|skipUnless)\b"),
    re.compile(r"\bpytest\.skip\s*\("),
    re.compile(r"@Ignore\b"),
    re.compile(r"\bAssume\.assume\w*\s*\("),
    re.compile(r"\b(?:describe|it|test)\.skip\s*\("),
)


@dataclass(frozen=True)
class RegressionFreezeReport:
    baseline_commit: str
    baseline_test_files: int
    preserved_test_files: int
    changed_production_files: tuple[str, ...]
    allowed_production_files: tuple[str, ...]


class RegressionFreezeError(RuntimeError):
    """Raised when the release no longer preserves its baseline."""


def _git(root: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout


def _git_bytes(root: Path, *args: str) -> bytes:
    completed = subprocess.run(
        ["git", *args],
        cwd=root,
        check=True,
        capture_output=True,
    )
    return completed.stdout


def _is_baseline_test(path: str) -> bool:
    if path.startswith(("backend/tests/", "tests/")):
        return path.endswith(".py")
    if path.startswith(
        (
            "android-native/app/src/test/",
            "android-native/app/src/androidTest/",
        )
    ):
        return path.endswith((".kt", ".java"))
    if path.startswith("frontend/src/"):
        name = Path(path).name
        return any(marker in name for marker in (".test.", ".spec."))
    return False


def _normalise_release_identity(path: str, text: str) -> str:
    if path not in RELEASE_IDENTITY_TESTS:
        return text
    normalised = text
    for current, baseline in (
        ("3.1.47", "3.1.14"),
        ("3.1.46", "3.1.14"),
        ("3.1.45", "3.1.14"),
        ("3.1.44", "3.1.14"),
        ("3.1.43", "3.1.14"),
        ("3.1.42", "3.1.14"),
        ("3.1.41", "3.1.14"),
        ("3.1.40", "3.1.14"),
        ("3.1.39", "3.1.14"),
        ("3.1.38", "3.1.14"),
        ("3.1.37", "3.1.14"),
        ("3.1.36", "3.1.14"),
        ("3.1.35", "3.1.14"),
        ("3.1.34", "3.1.14"),
        ("3.1.33", "3.1.14"),
        ("3.1.32", "3.1.14"),
        ("3.1.31", "3.1.14"),
        ("3.1.30", "3.1.14"),
        ("3.1.29", "3.1.14"),
        ("3.1.28", "3.1.14"),
        ("3.1.27", "3.1.14"),
        ("3.1.26", "3.1.14"),
        ("3.1.25", "3.1.14"),
        ("3.1.24", "3.1.14"),
        ("3.1.23", "3.1.14"),
        ("3.1.22", "3.1.14"),
        ("3.1.21", "3.1.14"),
        ("3.1.20", "3.1.14"),
        ("3.1.19", "3.1.14"),
        ("3.1.18", "3.1.14"),
        ("3.1.17", "3.1.14"),
        ("3.1.16", "3.1.14"),
        ("3.1.15", "3.1.14"),
        ("Code30.4", "Code25"),
        ("code30.4", "code25"),
        ("CODE30.4", "CODE25"),
        ("Code 30.4", "Code 25"),
        ("code 30.4", "code 25"),
        ("Code 30 point 4", "Code 25"),
        ("code 30 point 4", "code 25"),
        ("CODE30_POINT_4", "CODE25"),
        ("code30_point_4", "code25"),
        ("Code30.3", "Code25"),
        ("code30.3", "code25"),
        ("CODE30.3", "CODE25"),
        ("Code 30.3", "Code 25"),
        ("code 30.3", "code 25"),
        ("Code 30 point 3", "Code 25"),
        ("code 30 point 3", "code 25"),
        ("CODE30_POINT_3", "CODE25"),
        ("code30_point_3", "code25"),
        ("Code30.2", "Code25"),
        ("code30.2", "code25"),
        ("CODE30.2", "CODE25"),
        ("Code 30.2", "Code 25"),
        ("code 30.2", "code 25"),
        ("Code 30 point 2", "Code 25"),
        ("code 30 point 2", "code 25"),
        ("CODE30_POINT_2", "CODE25"),
        ("code30_point_2", "code25"),
        ("Code 30.1", "Code 25"),
        ("code 30.1", "code 25"),
        ("CODE30.1", "CODE25"),
        ("code30.1", "code25"),
        ("Code 30 point 1", "Code 25"),
        ("code 30 point 1", "code 25"),
        ("CODE30_POINT_1", "CODE25"),
        ("code30_point_1", "code25"),
        ("Code 30", "Code 25"),
        ("code 30", "code 25"),
        ("CODE30", "CODE25"),
        ("code30", "code25"),
        ("Code 29", "Code 25"),
        ("code 29", "code 25"),
        ("CODE29", "CODE25"),
        ("code29", "code25"),
        ("Code 28", "Code 25"),
        ("code 28", "code 25"),
        ("CODE28", "CODE25"),
        ("code28", "code25"),
        ("Code 27", "Code 25"),
        ("code 27", "code 25"),
        ("CODE27", "CODE25"),
        ("code27", "code25"),
        ("Code 26", "Code 25"),
        ("code 26", "code 25"),
        ("CODE26", "CODE25"),
        ("code26", "code25"),
    ):
        normalised = normalised.replace(current, baseline)
    normalised = re.sub(
        r"version_code\s*=\s*(?:26|27|28|29|30|31|32|33|34|35|36|37|38|39|40|41|42|43|44|45|46|47|48|49|50|51|52|53|54|55)\b", "version_code=25", normalised
    )
    normalised = re.sub(
        r"assertEquals\((?:26|27|28|29|30|31|32|33|34|35|36|37|38|39|40|41|42|43|44|45|46|47|48|49|50|51|52|53|54|55),\s*BuildConfig\.VERSION_CODE\)",
        "assertEquals(25, BuildConfig.VERSION_CODE)",
        normalised,
    )
    return normalised


def _normalise_pos_notice_dynamic_state_host(path: str, text: str) -> str:
    if path != POS_NOTICE_TEST_PATH:
        return text
    if hashlib.sha256(text.encode("utf-8")).hexdigest() != POS_NOTICE_TEST_SHA256:
        return text
    replacements = (
        ("                        state = state.value,", "                        state = state,"),
        (
            "                        onDismissNotice = onDismissNotice,",
            "                        onDismissNotice = {},",
        ),
    )
    if any(text.count(current) != 1 for current, _ in replacements):
        return text
    for current, baseline in replacements:
        text = text.replace(current, baseline)
    return text


def _missing_ordered_lines(baseline: str, candidate: str) -> list[str]:
    candidate_iter = iter(candidate.splitlines())
    missing: list[str] = []
    for line in baseline.splitlines():
        if any(current == line for current in candidate_iter):
            continue
        missing.append(line)
        break
    return missing


def _normalise_audit_reader_locator(path: str, text: str) -> str:
    if path != "tests/test_android_audit_isolation.py":
        return text
    # Only this implementation locator changes. All cleanup assertions and
    # their ordering remain subject to the full baseline comparison.
    return text.replace(
        "        plan_read = source.index('JSONObject(readInstructionFile(planPath))', outer_try)",
        '        plan_read = source.index(\'JSONObject(device.executeShellCommand("cat $planPath"))\', outer_try)',
    )


def _normalise_realtime_api_mock(path: str, text: str) -> str:
    if path != "frontend/src/lib/realtime-lifecycle.test.ts":
        return text
    # Production now requires the session-generation and renewal exports. This
    # changes only the existing test's API fixture; all four lifecycle cases
    # and their assertions remain byte-for-byte subject to the baseline.
    return text.replace(
        "vi.mock('./api', () => ({ BASE_URL: '/api/v1', readAccessToken: () => 'test-token', readSessionGeneration: () => 0, renewSessionAccessToken: async () => 'test-token' }));",
        "vi.mock('./api', () => ({ BASE_URL: '/api/v1', readAccessToken: () => 'test-token' }));",
    )


def _normalise_web_auth_freeze_assertion(path: str, text: str) -> str:
    if path != "tests/test_code26_regression_freeze.py":
        return text
    return text.replace(
        '    assert {path for path in report.changed_production_files if path.startswith("frontend/src/")} == REVIEWED_WEB_AUTH_PATHS | {"frontend/src/modules/gaming/GamingScreen.tsx", "frontend/src/modules/gaming/gaming-tariff.test.ts"} | REVIEWED_PACKAGING_UI_PATHS',
        '    assert "frontend/src" not in "\\n".join(report.changed_production_files)',
    )


def _normalise_android_release_pipeline(path: str, text: str) -> str:
    if path != "tests/test_android_release_pipeline.py":
        return text
    replacements = (
        (
            '            "needs: [coordinated-release-gates, production-image-gates, android-instrumentation]",',
            '            "needs: [coordinated-release-gates, android-instrumentation]",',
            2,
        ),
        (
            '        image_start = workflow.index("  production-image-gates:")\n'
            '        instrumentation_start = workflow.index("  android-instrumentation:")\n'
            '        coordinated_job = workflow[coordinated_start:image_start]\n'
            '        image_job = workflow[image_start:instrumentation_start]',
            '        instrumentation_start = workflow.index("  android-instrumentation:")\n'
            '        coordinated_job = workflow[coordinated_start:instrumentation_start]',
            1,
        ),
        (
            '        self.assertNotIn("docker buildx build", coordinated_job)\n'
            '        self.assertNotIn("scan-production-images", coordinated_job)\n'
            '        self.assertIn("needs: coordinated-release-gates", image_job)\n'
            '        self.assertIn("caddy validate --config /etc/caddy/Caddyfile", image_job)\n'
            '        self.assertIn("verify-postgres16-image-compatibility.sh", image_job)\n'
            '        self.assertIn("verify-production-runtime-images.sh", image_job)\n'
            '        self.assertIn("scan-production-images", image_job)\n'
            '        self.assertNotIn("run: python -m pytest tests", image_job)\n'
            '        self.assertNotIn("run: pytest", image_job)\n'
            '        self.assertNotIn("npm run test", image_job)',
            '        self.assertIn(\n'
            '            "caddy validate --config /etc/caddy/Caddyfile", coordinated_job\n'
            '        )',
            1,
        ),
    )
    normalised = text
    for current, baseline, expected_count in replacements:
        if normalised.count(current) != expected_count:
            return text
        normalised = normalised.replace(current, baseline)
    return normalised


def _disable_counts(text: str) -> tuple[int, ...]:
    return tuple(len(pattern.findall(text)) for pattern in DISABLING_PATTERNS)


def _code30_2_delta_paths(root: Path) -> set[str]:
    changed = set(
        _git(root, "diff", "--name-only", CODE30_1_BASE, CODE30_2_BASE).splitlines()
    )
    return {path for path in changed if path}


def _code30_3_delta_paths(root: Path) -> set[str]:
    changed = set(
        _git(root, "diff", "--name-only", CODE30_2_BASE, CODE30_3_BASE).splitlines()
    )
    return {path for path in changed if path}


def _code30_4_delta_paths(root: Path) -> set[str]:
    changed = set(_git(root, "diff", "--name-only", CODE30_3_BASE).splitlines())
    changed.update(
        _git(root, "ls-files", "--others", "--exclude-standard").splitlines()
    )
    return {path for path in changed if path}


def _code30_4_build42_delta_paths(root: Path) -> set[str]:
    return set(_git(root, "diff", "--name-only", CODE30_4_BUILD41_BASE, CODE30_4_BUILD42_BASE).splitlines())


def _code30_4_build43_delta_paths(root: Path) -> set[str]:
    return set(_git(root, "diff", "--name-only", CODE30_4_BUILD42_BASE, CODE30_4_BUILD43_BASE).splitlines())


def _code30_4_build44_delta_paths(root: Path) -> set[str]:
    return set(_git(root, "diff", "--name-only", CODE30_4_BUILD43_BASE, CODE30_4_BUILD44_BASE).splitlines())


def _code30_4_build45_delta_paths(root: Path) -> set[str]:
    return set(_git(root, "diff", "--name-only", CODE30_4_BUILD44_BASE, CODE30_4_BUILD45_BASE).splitlines())


def _code30_4_build46_delta_paths(root: Path) -> set[str]:
    return set(_git(root, "diff", "--name-only", CODE30_4_BUILD45_BASE, CODE30_4_BUILD46_BASE).splitlines())


def _code30_4_build47_delta_paths(root: Path) -> set[str]:
    return set(_git(root, "diff", "--name-only", CODE30_4_BUILD46_BASE, CODE30_4_BUILD47_BASE).splitlines())


def _code30_4_build48_delta_paths(root: Path) -> set[str]:
    return set(_git(root, "diff", "--name-only", CODE30_4_BUILD47_BASE, CODE30_4_BUILD48_BASE).splitlines())


def _code30_4_build49_delta_paths(root: Path) -> set[str]:
    return set(_git(root, "diff", "--name-only", CODE30_4_BUILD48_BASE, CODE30_4_BUILD49_BASE).splitlines())


def _code30_4_build50_delta_paths(root: Path) -> set[str]:
    return set(_git(root, "diff", "--name-only", CODE30_4_BUILD49_BASE, CODE30_4_BUILD50_BASE).splitlines())


def _code30_4_build51_delta_paths(root: Path) -> set[str]:
    return set(_git(root, "diff", "--name-only", CODE30_4_BUILD50_BASE, CODE30_4_BUILD51_BASE).splitlines())


def _code30_4_build52_delta_paths(root: Path) -> set[str]:
    return set(_git(root, "diff", "--name-only", CODE30_4_BUILD51_BASE, CODE30_4_BUILD52_BASE).splitlines())


def _code30_4_build53_delta_paths(root: Path) -> set[str]:
    return set(_git(root, "diff", "--name-only", CODE30_4_BUILD52_BASE, CODE30_4_BUILD53_BASE).splitlines())


def _code30_4_build54_delta_paths(root: Path) -> set[str]:
    return set(_git(root, "diff", "--name-only", CODE30_4_BUILD53_BASE, CODE30_4_BUILD54_BASE).splitlines())


def _code30_4_build55_delta_paths(root: Path) -> set[str]:
    changed = set(_git(root, "diff", "--name-only", CODE30_4_BUILD54_BASE).splitlines())
    changed.update(_git(root, "ls-files", "--others", "--exclude-standard").splitlines())
    return {path for path in changed if path}


def _is_canonical_regular_file(path: Path) -> bool:
    try:
        return (
            path.is_file()
            and not path.is_symlink()
            and path.resolve(strict=True) == path.absolute()
        )
    except OSError:
        return False


def _verify_code30_2_audit_plan(root: Path, errors: list[str]) -> None:
    try:
        plan = json.loads(
            _git(root, "show", f"{CODE30_2_BASE}:{CODE30_2_AUDIT_PLAN_PATH}")
        )
    except (subprocess.CalledProcessError, UnicodeError, json.JSONDecodeError) as exc:
        errors.append(f"Code30.2 business audit plan is unreadable: {exc}")
        return

    steps = plan.get("steps")
    if not isinstance(steps, list):
        errors.append("Code30.2 business audit plan has no steps list")
        return
    if plan.get("name") != (
        "Code30.2 3.1.29 current-tariff Gaming and Finance emulator acceptance"
    ):
        errors.append("Code30.2 business audit plan identity changed")
    if len(steps) != 390 or plan.get("expected_sessions") != 16:
        errors.append("Code30.2 business audit plan must retain 390 steps and 16 sessions")

    rendered = json.dumps(steps, sort_keys=True, ensure_ascii=False)
    category_markers = {
        "shift": (
            "Capture shift opening offline",
            "Request shift close",
            "Confirm shift close",
            "Closer attribution persisted",
        ),
        "gaming": (
            "standard-single-session-30m",
            "standard-single-session-60m",
            "standard-dual-session-30m",
            "standard-dual-session-60m",
            "standard-simdrive-session-15m",
            "standard-simdrive-session-30m",
            "standard-simdrive-session-60m",
            "vr-games-session-15m",
            "vr-games-session-30m",
            "vr-games-session-60m",
            "vr-racing-session-15m",
            "vr-racing-session-30m",
            "vr-racing-session-60m",
            "standard-single-extension-30m",
            "standard-single-extension-60m",
            "standard-dual-extension-30m",
            "standard-dual-extension-60m",
        ),
        "POS": (
            "request POS handoff",
            "open one held bill",
            "continue verified bill",
        ),
        "payment": (
            "submit cash once",
            "submit UPI once",
            "All sixteen receipts loaded",
        ),
        "offline": (
            "Disconnect before opening shift",
            "Reconnect for ordered replay",
            "Post-close disconnect",
            "Post-close reconnect",
        ),
        "restart": (
            "Force-stop and restart actual ERP offline",
            "Restart after complete business day",
        ),
    }
    for category, markers in category_markers.items():
        missing = [marker for marker in markers if marker not in rendered]
        if missing:
            errors.append(
                f"Code30.2 business audit lost {category} coverage: {', '.join(missing)}"
            )

    try:
        runner = _git(
            root,
            "show",
            f"{CODE30_2_BASE}:scripts/run_code26_physical_business_audit.sh",
        )
    except (subprocess.CalledProcessError, UnicodeError) as exc:
        errors.append(f"Code30.2 business audit cleanup runner is unreadable: {exc}")
        return
    for marker in (
        "cleanup() {",
        "trap cleanup EXIT INT TERM",
        'rm -f "$CREDENTIAL_FILE"',
        'dropdb --force --if-exists "$DB_NAME"',
        "write_source_recheck",
    ):
        if marker not in runner:
            errors.append(f"Code30.2 business audit lost cleanup coverage: {marker}")


def _verify_code30_2_exact_delta(root: Path, errors: list[str]) -> None:
    expected_paths = set(REVIEWED_CODE30_2_SHA256) | set(
        CODE30_2_FREEZE_CONTROL_PATHS
    )
    current_paths = _code30_2_delta_paths(root)
    for path in sorted(expected_paths - current_paths):
        errors.append(f"reviewed Code30.2 delta path disappeared: {path}")
    for path in sorted(current_paths - expected_paths):
        errors.append(f"unreviewed path entered the Code30.2 delta: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_2_SHA256.items():
        try:
            candidate_bytes = _git_bytes(root, "show", f"{CODE30_2_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(
                f"reviewed Code30.2 file is absent from its immutable base: {path}"
            )
            continue
        actual_sha256 = hashlib.sha256(candidate_bytes).hexdigest()
        if actual_sha256 != expected_sha256:
            errors.append(f"reviewed Code30.2 file differs from its approved bytes: {path}")

    for path in CODE30_2_FREEZE_CONTROL_PATHS:
        try:
            _git(root, "cat-file", "-e", f"{CODE30_2_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(
                f"Code30.2 freeze control is absent from its immutable base: {path}"
            )

    _verify_code30_2_audit_plan(root, errors)


def _verify_code30_3_exact_delta(root: Path, errors: list[str]) -> None:
    expected_paths = set(REVIEWED_CODE30_3_SHA256) | set(
        CODE30_3_FREEZE_CONTROL_PATHS
    )
    current_paths = _code30_3_delta_paths(root)
    for path in sorted(expected_paths - current_paths):
        errors.append(f"reviewed Code30.3 delta path disappeared: {path}")
    for path in sorted(current_paths - expected_paths):
        errors.append(f"unreviewed path entered the Code30.3 delta: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_3_SHA256.items():
        try:
            candidate_bytes = _git_bytes(root, "show", f"{CODE30_3_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(
                f"reviewed Code30.3 file is absent from its immutable base: {path}"
            )
            continue
        actual_sha256 = hashlib.sha256(candidate_bytes).hexdigest()
        if actual_sha256 != expected_sha256:
            errors.append(f"reviewed Code30.3 file differs from its approved bytes: {path}")

    for path in CODE30_3_FREEZE_CONTROL_PATHS:
        try:
            _git(root, "cat-file", "-e", f"{CODE30_3_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(
                f"Code30.3 freeze control is absent from its immutable base: {path}"
            )


def _verify_code30_4_exact_delta(root: Path, errors: list[str]) -> None:
    # The unoffered signed candidate remains auditable at its immutable Git
    # object. Only the narrow corrective overlay may differ in this checkout.
    for path, expected_sha256 in REVIEWED_CODE30_4_SHA256.items():
        try:
            original_bytes = _git_bytes(root, "show", f"{CODE30_4_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"signed Code30.4 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(original_bytes).hexdigest() != expected_sha256:
            errors.append(f"signed Code30.4 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_HOTFIX_SHA256.items():
        try:
            signed_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD40_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"signed Code30.4 build-40 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(signed_bytes).hexdigest() != expected_sha256:
            errors.append(f"signed Code30.4 build-40 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD41_SHA256.items():
        try:
            signed_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD41_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"signed Code30.4 build-41 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(signed_bytes).hexdigest() != expected_sha256:
            errors.append(f"signed Code30.4 build-41 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD42_SHA256.items():
        try:
            tagged_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD42_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"tagged Code30.4 build-42 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(tagged_bytes).hexdigest() != expected_sha256:
            errors.append(f"tagged Code30.4 build-42 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD43_SHA256.items():
        try:
            tagged_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD43_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"tagged Code30.4 build-43 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(tagged_bytes).hexdigest() != expected_sha256:
            errors.append(f"tagged Code30.4 build-43 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD44_SHA256.items():
        try:
            released_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD44_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"Code30.4 build-44 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(released_bytes).hexdigest() != expected_sha256:
            errors.append(f"Code30.4 build-44 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD45_SHA256.items():
        try:
            released_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD45_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"Code30.4 build-45 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(released_bytes).hexdigest() != expected_sha256:
            errors.append(f"Code30.4 build-45 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD46_SHA256.items():
        try:
            released_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD46_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"Code30.4 build-46 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(released_bytes).hexdigest() != expected_sha256:
            errors.append(f"Code30.4 build-46 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD47_SHA256.items():
        try:
            released_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD47_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"Code30.4 build-47 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(released_bytes).hexdigest() != expected_sha256:
            errors.append(f"Code30.4 build-47 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD48_SHA256.items():
        try:
            released_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD48_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"Code30.4 build-48 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(released_bytes).hexdigest() != expected_sha256:
            errors.append(f"Code30.4 build-48 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD49_SHA256.items():
        try:
            released_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD49_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"Code30.4 build-49 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(released_bytes).hexdigest() != expected_sha256:
            errors.append(f"Code30.4 build-49 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD50_SHA256.items():
        try:
            released_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD50_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"Code30.4 build-50 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(released_bytes).hexdigest() != expected_sha256:
            errors.append(f"Code30.4 build-50 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD51_SHA256.items():
        try:
            frozen_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD51_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"Code30.4 build-51 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(frozen_bytes).hexdigest() != expected_sha256:
            errors.append(f"Code30.4 build-51 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD52_SHA256.items():
        try:
            frozen_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD52_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"Code30.4 build-52 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(frozen_bytes).hexdigest() != expected_sha256:
            errors.append(f"Code30.4 build-52 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD53_SHA256.items():
        try:
            frozen_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD53_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"Code30.4 build-53 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(frozen_bytes).hexdigest() != expected_sha256:
            errors.append(f"Code30.4 build-53 file differs from reviewed bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_4_BUILD54_SHA256.items():
        try:
            frozen_bytes = _git_bytes(root, "show", f"{CODE30_4_BUILD54_BASE}:{path}")
        except subprocess.CalledProcessError:
            errors.append(f"Code30.4 build-54 file absent from immutable base: {path}")
            continue
        if hashlib.sha256(frozen_bytes).hexdigest() != expected_sha256:
            errors.append(f"Code30.4 build-54 file differs from reviewed bytes: {path}")

    expected_build42_paths = set(REVIEWED_CODE30_4_BUILD42_SHA256) | set(
        CODE30_4_FREEZE_CONTROL_PATHS
    )
    current_build42_paths = _code30_4_build42_delta_paths(root)
    for path in sorted(expected_build42_paths - current_build42_paths):
        errors.append(f"reviewed Code30.4 build-42 delta path disappeared: {path}")
    for path in sorted(current_build42_paths - expected_build42_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-42 delta: {path}")

    expected_build43_paths = set(REVIEWED_CODE30_4_BUILD43_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    current_build43_paths = _code30_4_build43_delta_paths(root)
    for path in sorted(expected_build43_paths - current_build43_paths):
        errors.append(f"reviewed Code30.4 build-43 delta path disappeared: {path}")
    for path in sorted(current_build43_paths - expected_build43_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-43 delta: {path}")

    expected_build44_paths = set(REVIEWED_CODE30_4_BUILD44_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    current_build44_paths = _code30_4_build44_delta_paths(root)
    for path in sorted(expected_build44_paths - current_build44_paths):
        errors.append(f"reviewed Code30.4 build-44 delta path disappeared: {path}")
    for path in sorted(current_build44_paths - expected_build44_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-44 delta: {path}")

    expected_build45_paths = set(REVIEWED_CODE30_4_BUILD45_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    current_build45_paths = _code30_4_build45_delta_paths(root)
    for path in sorted(expected_build45_paths - current_build45_paths):
        errors.append(f"reviewed Code30.4 build-45 delta path disappeared: {path}")
    for path in sorted(current_build45_paths - expected_build45_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-45 delta: {path}")

    expected_build46_paths = set(REVIEWED_CODE30_4_BUILD46_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    current_build46_paths = _code30_4_build46_delta_paths(root)
    for path in sorted(expected_build46_paths - current_build46_paths):
        errors.append(f"reviewed Code30.4 build-46 delta path disappeared: {path}")
    for path in sorted(current_build46_paths - expected_build46_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-46 delta: {path}")

    expected_build47_paths = set(REVIEWED_CODE30_4_BUILD47_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    current_build47_paths = _code30_4_build47_delta_paths(root)
    for path in sorted(expected_build47_paths - current_build47_paths):
        errors.append(f"reviewed Code30.4 build-47 delta path disappeared: {path}")
    for path in sorted(current_build47_paths - expected_build47_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-47 delta: {path}")

    expected_build48_paths = set(REVIEWED_CODE30_4_BUILD48_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    current_build48_paths = _code30_4_build48_delta_paths(root)
    for path in sorted(expected_build48_paths - current_build48_paths):
        errors.append(f"reviewed Code30.4 build-48 delta path disappeared: {path}")
    for path in sorted(current_build48_paths - expected_build48_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-48 delta: {path}")

    expected_build49_paths = set(REVIEWED_CODE30_4_BUILD49_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    current_build49_paths = _code30_4_build49_delta_paths(root)
    for path in sorted(expected_build49_paths - current_build49_paths):
        errors.append(f"reviewed Code30.4 build-49 delta path disappeared: {path}")
    for path in sorted(current_build49_paths - expected_build49_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-49 delta: {path}")

    expected_build50_paths = set(REVIEWED_CODE30_4_BUILD50_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    current_build50_paths = _code30_4_build50_delta_paths(root)
    for path in sorted(expected_build50_paths - current_build50_paths):
        errors.append(f"reviewed Code30.4 build-50 delta path disappeared: {path}")
    for path in sorted(current_build50_paths - expected_build50_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-50 delta: {path}")

    expected_build51_paths = set(REVIEWED_CODE30_4_BUILD51_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    current_build51_paths = _code30_4_build51_delta_paths(root)
    for path in sorted(expected_build51_paths - current_build51_paths):
        errors.append(f"reviewed Code30.4 build-51 delta path disappeared: {path}")
    for path in sorted(current_build51_paths - expected_build51_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-51 delta: {path}")

    expected_build52_paths = set(REVIEWED_CODE30_4_BUILD52_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    current_build52_paths = _code30_4_build52_delta_paths(root)
    for path in sorted(expected_build52_paths - current_build52_paths):
        errors.append(f"reviewed Code30.4 build-52 delta path disappeared: {path}")
    for path in sorted(current_build52_paths - expected_build52_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-52 delta: {path}")

    expected_build53_paths = set(REVIEWED_CODE30_4_BUILD53_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    current_build53_paths = _code30_4_build53_delta_paths(root)
    for path in sorted(expected_build53_paths - current_build53_paths):
        errors.append(f"reviewed Code30.4 build-53 delta path disappeared: {path}")
    for path in sorted(current_build53_paths - expected_build53_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-53 delta: {path}")

    expected_build54_paths = set(REVIEWED_CODE30_4_BUILD54_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    current_build54_paths = _code30_4_build54_delta_paths(root)
    for path in sorted(expected_build54_paths - current_build54_paths):
        errors.append(f"reviewed Code30.4 build-54 delta path disappeared: {path}")
    for path in sorted(current_build54_paths - expected_build54_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-54 delta: {path}")

    expected_build55_paths = set(REVIEWED_CODE30_4_BUILD55_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    current_build55_paths = _code30_4_build55_delta_paths(root)
    for path in sorted(expected_build55_paths - current_build55_paths):
        errors.append(f"reviewed Code30.4 build-55 delta path disappeared: {path}")
    for path in sorted(current_build55_paths - expected_build55_paths):
        errors.append(f"unreviewed path entered the Code30.4 build-55 delta: {path}")

    # Post-release Code30.3 maintenance tools are pinned by their own map.
    expected_paths = set(REVIEWED_CODE30_4_SHA256) | set(
        CODE30_4_FREEZE_CONTROL_PATHS
    ) | set(REVIEWED_POST_CODE30_3_MAINTENANCE_SHA256) | set(
        REVIEWED_CODE30_4_HOTFIX_SHA256
    ) | set(
        REVIEWED_CODE30_4_BUILD41_SHA256
    ) | set(
        REVIEWED_CODE30_4_BUILD42_SHA256
    ) | set(REVIEWED_CODE30_4_BUILD43_SHA256) | set(REVIEWED_CODE30_4_BUILD44_SHA256) | set(REVIEWED_CODE30_4_BUILD45_SHA256) | set(REVIEWED_CODE30_4_BUILD46_SHA256) | set(REVIEWED_CODE30_4_BUILD47_SHA256) | set(REVIEWED_CODE30_4_BUILD48_SHA256) | set(REVIEWED_CODE30_4_BUILD49_SHA256) | set(REVIEWED_CODE30_4_BUILD50_SHA256) | set(REVIEWED_CODE30_4_BUILD51_SHA256) | set(REVIEWED_CODE30_4_BUILD52_SHA256) | set(REVIEWED_CODE30_4_BUILD53_SHA256) | set(REVIEWED_CODE30_4_BUILD54_SHA256) | set(REVIEWED_CODE30_4_BUILD55_SHA256)
    current_paths = _code30_4_delta_paths(root)
    for path in sorted(expected_paths - current_paths):
        errors.append(f"reviewed Code30.4 delta path disappeared: {path}")
    for path in sorted(current_paths - expected_paths):
        errors.append(f"unreviewed path entered the Code30.4 delta: {path}")

    current_hashes = (
        REVIEWED_CODE30_4_SHA256
        | REVIEWED_CODE30_4_HOTFIX_SHA256
        | REVIEWED_CODE30_4_BUILD41_SHA256
        | REVIEWED_CODE30_4_BUILD42_SHA256
        | REVIEWED_CODE30_4_BUILD43_SHA256
        | REVIEWED_CODE30_4_BUILD44_SHA256
        | REVIEWED_CODE30_4_BUILD45_SHA256
        | REVIEWED_CODE30_4_BUILD46_SHA256
        | REVIEWED_CODE30_4_BUILD47_SHA256
        | REVIEWED_CODE30_4_BUILD48_SHA256
        | REVIEWED_CODE30_4_BUILD49_SHA256
        | REVIEWED_CODE30_4_BUILD50_SHA256
        | REVIEWED_CODE30_4_BUILD51_SHA256
        | REVIEWED_CODE30_4_BUILD52_SHA256
        | REVIEWED_CODE30_4_BUILD53_SHA256
        | REVIEWED_CODE30_4_BUILD54_SHA256
        | REVIEWED_CODE30_4_BUILD55_SHA256
    )
    for path, expected_sha256 in current_hashes.items():
        # These files define the freeze itself and cannot carry a current-tree
        # hash of their own contents. Their signed bytes are checked above.
        if path in CODE30_4_FREEZE_CONTROL_PATHS:
            continue
        candidate_path = root / path
        if not _is_canonical_regular_file(candidate_path):
            errors.append(
                f"reviewed Code30.4 file was removed, linked, or non-regular: {path}"
            )
            continue
        actual_sha256 = hashlib.sha256(candidate_path.read_bytes()).hexdigest()
        if actual_sha256 != expected_sha256:
            errors.append(f"reviewed Code30.4 file differs from its approved bytes: {path}")

    for path in CODE30_4_FREEZE_CONTROL_PATHS:
        if not _is_canonical_regular_file(root / path):
            errors.append(
                f"Code30.4 freeze control was removed, linked, or non-regular: {path}"
            )

    _verify_post_code30_3_maintenance_bytes(root, errors)


def _verify_post_code30_3_maintenance_bytes(root: Path, errors: list[str]) -> None:
    for path, expected_sha256 in REVIEWED_POST_CODE30_3_MAINTENANCE_SHA256.items():
        candidate_path = root / path
        if not _is_canonical_regular_file(candidate_path):
            errors.append(
                f"reviewed post-Code30.3 maintenance file was removed, linked, or non-regular: {path}"
            )
            continue
        if hashlib.sha256(candidate_path.read_bytes()).hexdigest() != expected_sha256:
            errors.append(
                f"reviewed post-Code30.3 maintenance file differs from its approved bytes: {path}"
            )


def verify_repository(root: Path, baseline: str = CODE25_BASE) -> RegressionFreezeReport:
    root = root.resolve()
    _git(root, "cat-file", "-e", f"{baseline}^{{commit}}")
    baseline_paths = _git(root, "ls-tree", "-r", "--name-only", baseline).splitlines()
    test_paths = sorted(path for path in baseline_paths if _is_baseline_test(path))
    if not test_paths:
        raise RegressionFreezeError("Code 25 baseline contains no test files")

    errors: list[str] = []
    _git(root, "cat-file", "-e", f"{CODE30_1_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_2_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_3_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD40_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD41_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD42_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD43_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD44_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD45_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD46_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD47_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD48_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD49_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD50_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD51_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD52_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD53_BASE}^{{commit}}")
    _git(root, "cat-file", "-e", f"{CODE30_4_BUILD54_BASE}^{{commit}}")
    _verify_code30_2_exact_delta(root, errors)
    _verify_code30_3_exact_delta(root, errors)
    _verify_code30_4_exact_delta(root, errors)
    for path in test_paths:
        candidate_path = root / path
        if not candidate_path.is_file():
            errors.append(f"baseline test file was removed: {path}")
            continue
        baseline_text = _git(root, "show", f"{baseline}:{path}")
        candidate_text = candidate_path.read_text(encoding="utf-8")
        baseline_normalised = _normalise_release_identity(path, baseline_text)
        candidate_normalised = _normalise_release_identity(path, candidate_text)
        candidate_normalised = _normalise_pos_notice_dynamic_state_host(
            path, candidate_normalised
        )
        candidate_normalised = _normalise_audit_reader_locator(path, candidate_normalised)
        candidate_normalised = _normalise_realtime_api_mock(path, candidate_normalised)
        candidate_normalised = _normalise_web_auth_freeze_assertion(
            path, candidate_normalised
        )
        candidate_normalised = _normalise_android_release_pipeline(
            path, candidate_normalised
        )
        reviewed_test_sha256 = (
            REVIEWED_CODE30_4_BUILD55_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD54_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD53_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD52_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD51_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD50_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD49_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD48_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD47_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD46_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD45_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD44_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD43_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD42_SHA256.get(path)
            or REVIEWED_CODE30_4_BUILD41_SHA256.get(path)
            or REVIEWED_CODE30_4_HOTFIX_SHA256.get(path)
            or REVIEWED_CODE30_4_SHA256.get(path)
            or REVIEWED_CODE30_3_SHA256.get(path)
            or REVIEWED_CODE30_2_SHA256.get(path)
            or REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(path)
            or REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.get(path)
            or REVIEWED_CODE30_1_RELEASE_TEST_SHA256.get(path)
            or REVIEWED_CODE30_1_FEATURE_SHA256.get(path)
            or REVIEWED_CODE30_TEST_SHA256.get(path)
            or REVIEWED_CODE29_2_TEST_SHA256.get(path)
            or REVIEWED_PRICING_TEST_SHA256.get(path)
            or REVIEWED_PACKAGING_TEST_SHA256.get(path)
        )
        reviewed_test_bytes_match = reviewed_test_sha256 is not None and (
            hashlib.sha256(candidate_path.read_bytes()).hexdigest()
            == reviewed_test_sha256
        )
        if reviewed_test_sha256 is not None and not reviewed_test_bytes_match:
            errors.append(f"reviewed test differs from its approved bytes: {path}")
        if not reviewed_test_bytes_match and _missing_ordered_lines(
            baseline_normalised, candidate_normalised
        ):
            errors.append(f"baseline test was rewritten or reordered: {path}")
        baseline_disables = _disable_counts(baseline_text)
        candidate_disables = _disable_counts(candidate_text)
        if any(after > before for before, after in zip(baseline_disables, candidate_disables)):
            errors.append(f"baseline test gained a skip/xfail/ignore path: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_1_FEATURE_SHA256.items():
        candidate_path = root / path
        current_expected_sha256 = REVIEWED_CODE30_3_SHA256.get(
            path,
            REVIEWED_CODE30_2_SHA256.get(
                path,
                REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(path, expected_sha256),
            ),
        )
        current_expected_sha256 = _current_code30_4_sha256(path, current_expected_sha256)
        if not candidate_path.is_file():
            errors.append(f"reviewed Code30.1 file was removed: {path}")
        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != current_expected_sha256:
            errors.append(f"reviewed Code30.1 file differs from its approved bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.items():
        candidate_path = root / path
        current_expected_sha256 = REVIEWED_CODE30_3_SHA256.get(
            path, REVIEWED_CODE30_2_SHA256.get(path, expected_sha256)
        )
        current_expected_sha256 = _current_code30_4_sha256(path, current_expected_sha256)
        if not candidate_path.is_file():
            errors.append(f"reviewed Code30.1 deletion-replay file was removed: {path}")
        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != current_expected_sha256:
            errors.append(
                f"reviewed Code30.1 deletion-replay file differs from its approved bytes: {path}"
            )

    for path, expected_sha256 in REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.items():
        candidate_path = root / path
        current_expected_sha256 = REVIEWED_CODE30_3_SHA256.get(
            path, REVIEWED_CODE30_2_SHA256.get(path, expected_sha256)
        )
        current_expected_sha256 = _current_code30_4_sha256(path, current_expected_sha256)
        if not candidate_path.is_file():
            errors.append(f"reviewed Code30.1 build-36 release test was removed: {path}")
        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != current_expected_sha256:
            errors.append(
                f"reviewed Code30.1 build-36 release test differs from its approved bytes: {path}"
            )

    for path, expected_sha256 in REVIEWED_CODE30_1_RELEASE_TEST_SHA256.items():
        candidate_path = root / path
        current_expected_sha256 = REVIEWED_CODE30_3_SHA256.get(
            path,
            REVIEWED_CODE30_2_SHA256.get(
                path,
                REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.get(path, expected_sha256),
            ),
        )
        current_expected_sha256 = _current_code30_4_sha256(path, current_expected_sha256)
        if not candidate_path.is_file():
            errors.append(f"reviewed Code30.1 release test was removed: {path}")
        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != current_expected_sha256:
            errors.append(
                f"reviewed Code30.1 release test differs from its approved bytes: {path}"
            )

    refresh_locking_test = root / REFRESH_LOCKING_TEST_PATH
    if not refresh_locking_test.is_file():
        errors.append(f"reviewed test file was removed: {REFRESH_LOCKING_TEST_PATH}")
    elif hashlib.sha256(refresh_locking_test.read_bytes()).hexdigest() != (
        REFRESH_LOCKING_TEST_SHA256
    ):
        errors.append(
            f"reviewed test file differs from its approved bytes: {REFRESH_LOCKING_TEST_PATH}"
        )

    changed = set(
        _git(
            root,
            "diff",
            "--name-only",
            baseline,
            "--",
            *PRODUCTION_PREFIXES,
        ).splitlines()
    )
    changed.update(
        path
        for path in _git(root, "ls-files", "--others", "--exclude-standard").splitlines()
        if path.startswith(PRODUCTION_PREFIXES)
    )
    changed = {path for path in changed if path}
    deleted = {
        fields[-1]
        for line in _git(
            root,
            "diff",
            "--name-status",
            baseline,
            "--",
            *PRODUCTION_PREFIXES,
        ).splitlines()
        if (fields := line.split("\t")) and fields[0].startswith("D")
    }
    if deleted:
        errors.extend(f"production source was deleted: {path}" for path in sorted(deleted))
    unexpected = changed - ALLOWED_PRODUCTION_PATHS
    if unexpected:
        errors.extend(
            f"production source is outside the frozen Code30.4 scope: {path}"
            for path in sorted(unexpected)
        )

    if errors:
        raise RegressionFreezeError("\n".join(errors))

    return RegressionFreezeReport(
        baseline_commit=baseline,
        baseline_test_files=len(test_paths),
        preserved_test_files=len(test_paths),
        changed_production_files=tuple(sorted(changed)),
        allowed_production_files=tuple(sorted(ALLOWED_PRODUCTION_PATHS)),
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--baseline", default=CODE25_BASE)
    args = parser.parse_args()
    try:
        report = verify_repository(args.root, args.baseline)
    except (RegressionFreezeError, subprocess.CalledProcessError) as exc:
        print(json.dumps({"passed": False, "error": str(exc)}, indent=2))
        return 1
    print(json.dumps({"passed": True, **asdict(report)}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
