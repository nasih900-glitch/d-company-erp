#!/usr/bin/env python3
"""Fail closed when Code 26 through Code30.3 weakens the proven Code 25 surface.

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
documentation.
The immutable Code30.2 bytes are read from their release commit; the current
working tree is checked against a separate Code30.3 map. Neither release layer
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
    'frontend/src/modules/pos/OrdersAndShiftsScreen.tsx': 'b7a6480c47e1d484820d53e3eea8caeaba60b89e527448aa441991faecda5017',
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
} | REVIEWED_WEB_AUTH_PATHS | REVIEWED_PRICING_PRODUCTION_PATHS | REVIEWED_PACKAGING_UI_PATHS | REVIEWED_CODE29_2_PRODUCTION_PATHS | REVIEWED_CODE30_PRODUCTION_PATHS | REVIEWED_CODE30_1_PRODUCTION_PATHS | REVIEWED_CODE30_1_DELETION_REPLAY_PRODUCTION_PATHS | REVIEWED_CODE30_2_PRODUCTION_PATHS | REVIEWED_CODE30_3_PRODUCTION_PATHS

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
        r"version_code\s*=\s*(?:26|27|28|29|30|31|32|33|34|35|36|37|38)\b", "version_code=25", normalised
    )
    normalised = re.sub(
        r"assertEquals\((?:26|27|28|29|30|31|32|33|34|35|36|37|38),\s*BuildConfig\.VERSION_CODE\)",
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
    changed = set(_git(root, "diff", "--name-only", CODE30_2_BASE).splitlines())
    changed.update(
        _git(root, "ls-files", "--others", "--exclude-standard").splitlines()
    )
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
        candidate_path = root / path
        if not _is_canonical_regular_file(candidate_path):
            errors.append(
                f"reviewed Code30.3 file was removed, linked, or non-regular: {path}"
            )
            continue
        actual_sha256 = hashlib.sha256(candidate_path.read_bytes()).hexdigest()
        if actual_sha256 != expected_sha256:
            errors.append(f"reviewed Code30.3 file differs from its approved bytes: {path}")

    for path in CODE30_3_FREEZE_CONTROL_PATHS:
        if not _is_canonical_regular_file(root / path):
            errors.append(
                f"Code30.3 freeze control was removed, linked, or non-regular: {path}"
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
    _verify_code30_2_exact_delta(root, errors)
    _verify_code30_3_exact_delta(root, errors)
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
            REVIEWED_CODE30_3_SHA256.get(path)
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
        if not candidate_path.is_file():
            errors.append(f"reviewed Code30.1 file was removed: {path}")
        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != current_expected_sha256:
            errors.append(f"reviewed Code30.1 file differs from its approved bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.items():
        candidate_path = root / path
        current_expected_sha256 = REVIEWED_CODE30_3_SHA256.get(
            path, REVIEWED_CODE30_2_SHA256.get(path, expected_sha256)
        )
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
            f"production source is outside the frozen Code30.3 scope: {path}"
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
