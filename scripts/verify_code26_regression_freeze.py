#!/usr/bin/env python3
"""Fail closed when Code 26 through Code30.2 weakens the proven Code 25 surface.

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
Alembic 0078, release metadata and five-mode business-audit delta below. None
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
CODE30_2_FREEZE_CONTROL_PATHS = frozenset({
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
    'AGENTS.md': '8f35996f40e1605fbaebe3c1aea5c9298358a2102a29db6714fdfa9d88454219',
    'PROJECT_STATE.md': '557010d3137adfc4d5b5ad7abca34990ac845106cd639b108bd00acda7e79320',
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
    'android-native/audit-driver/plans/code26-gaming-finance-physical.json': '8d7231df396ef2653f31818f17b18ccb1a7c8d3d539750db06585c367379bb1b',
    'backend/alembic/versions/0074_expense_receipt_evidence.py': '7809ee7ce3bf3b417e6315a0818aa1ea491265fb9d347f5bb48053a182b72835',
    'backend/alembic/versions/0075_google_sheets_delivery_outbox.py': '5afb8a1ca5b0f4e01cbcc195455b1dbebd7099f47c428e62ff81f506b7e2254a',
    'backend/alembic/versions/0076_google_sheets_mirror_config.py': '0d997527fa19142f3a22196f9ba0c5d3a47041e14d7de99f93f50b0034ba2772',
    'backend/alembic/versions/0077_modern_cash_expense_drawer.py': '64eec1b7d6f803c40eb972ae4302fa30dee8283736b5f3804a818a54b00bcb09',
    'backend/alembic/versions/0078_finance_cash_source_corrections.py': '92b138402555bd456c12a44755dd2253d41d0e43b68a47f7612b4c2a28dee902',
    'backend/app/__init__.py': '3a2d88986b5ede4cfbe3815d507473de0906948ad9e83c44263df57a821756cc',
    'backend/app/api/v1/finance/router.py': '9b7b1261967e6d7f71b6a3c737a842050654069c16fbd834d89474e5313c1f54',
    'backend/app/api/v1/memberships/router.py': '81010109cf22243468cd89c46fd42548d71a1cc46c4b9b94a272b20115dab41c',
    'backend/app/api/v1/pos/router.py': '046716fe8b6013ceb14a8cdec19143239e0fdc6fcdf533042a69dc9324e1f797',
    'backend/app/api/v1/router.py': '0f0e7d3373440f6640ac97e9d3e4d65b55b8d4c5f36f369ac75a7128d0932184',
    'backend/app/api/v1/settings/google_sheets.py': '06f1db71cad62abd0e6c0431f0856faf228948cba56639b43207f84cf95d8fed',
    'backend/app/api/v1/settings/router.py': '95b5dd0b65b6ee41e0c253ee80477452a19651698914c6f5660ef271c2a5e205',
    'backend/app/core/config.py': 'aa711049503fe97da5a0bce887e574e11cbd6bccb8d98709e6abc0f206c01a90',
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
    'backend/tests/unit/test_remote_assistance_contract.py': '57631f7c0340745730ab0350cf94b9a6b1806c49aa42014a97e3cee7cdaa2453',
    'backend/tests/unit/test_report_roundoff_reconciliation.py': 'b4cbf5e7c80a08eb55f7eae08e48a1f4a3c9efeec87fa1c26281211afe1b7635',
    'backend/tests/unit/test_runtime_release_parity.py': 'a019ae232496fdc50a4bcfd57aabbc4641824a4ae472118113b73c03eee43028',
    'backend/tests/unit/test_settings_timezone_validation.py': '06c4e48ef802e828e50c6736dec36476ffbd490f1d3286a21ddea103d36a763a',
    'backend/tests/unit/test_tip_payouts.py': 'ecfd81049f24179c7a12c412dda4ea680613dbc5cdf5c0a8a6ca39a81d87df99',
    'backend/tests/unit/test_tip_refund_ledger.py': 'e74d1326e92eda95d5e32985bb6807c651d6547ce92d1d7e4a631099f5d0fc8d',
    'docker-compose.prod.yml': '09b5a7108ed58db0d0e4f59c20dc1aceaea9ff9f4067785d33e3964e0d157a9f',
    'docs/CODE30_2_PATCH_CANDIDATE.md': '00ef46901caee193cb9d86ad50d463c26b0968b896eb4144532c728dbe67ea69',
    'docs/DISTRIBUTION.md': '0c3f327e168c4994846dedc909b03604e7a1226a66d5423a2d176716197215ce',
    'docs/GOOGLE_SHEETS.md': '125b7bae2fc7f4c32f17b24af0f45dac179e4044bf8b32c8029ef781e8633de9',
    'docs/PLAY_INTERNAL_TESTING.md': '665f405ebefd1f569c60c2c5b832616b3912e84b2528296bcf4bd74a3a7033a1',
    'docs/SERVER_DRIVEN_ANDROID_UPDATES.md': 'd74380a766f26abe372ba13bc70c109bfe8eb104cf638fd01810900edde716e8',
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
    'infra/scripts/generate-secrets.sh': '51f1c4f51dd0e4087321783ba2885c75cf873ab114a92fbdc89396839bd363aa',
    'infra/scripts/validate-production-env.sh': '9ad57de9b9f14292a70c3b877954f01bef7e40eb5b34cbc46dec9089ef9ac76b',
    'integrations/google-sheets/Code.gs': 'cbf401b2b7612b049d338a38ab4e53f0e78d7afda2d92782c3477f7b94d1f6d0',
    'releases/android/README.md': '790179dffcf20aa3834b85f0cf26dd0953bdeecfb19a4bb93be3fc8a4356e786',
    'scripts/analyze_code26_physical_evidence.py': '26c94d9abf88cd5211fccdcf3dddd3fe16edc8a653f8c18d0f0d1ff921e20e57',
    'scripts/run_code26_physical_business_audit.sh': '9b48ba90aff9effcbb3eec88efc5462ddced855c2749ca0056cf74616efb6a41',
    'tests/test_android_release_version.py': 'c3b15d5ef03e2dac384dd9ab2801d9166ac3439cae0607a9d621b84ea8c380fb',
    'tests/test_code26_physical_audit_lane.py': 'dc41c08ccd7aeed7c925c280a2f78c48588807d1d28819b6537c165cc7b39f3d',
    'tests/test_production_installer_safety.py': '218a18d355de99986638ca9e1907e64ad9c67fd53b8b6607f852031dda1d8d90',
}

REVIEWED_CODE30_2_PRODUCTION_PATHS = frozenset(
    path
    for path in REVIEWED_CODE30_2_SHA256
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
} | REVIEWED_WEB_AUTH_PATHS | REVIEWED_PRICING_PRODUCTION_PATHS | REVIEWED_PACKAGING_UI_PATHS | REVIEWED_CODE29_2_PRODUCTION_PATHS | REVIEWED_CODE30_PRODUCTION_PATHS | REVIEWED_CODE30_1_PRODUCTION_PATHS | REVIEWED_CODE30_1_DELETION_REPLAY_PRODUCTION_PATHS | REVIEWED_CODE30_2_PRODUCTION_PATHS

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
        r"version_code\s*=\s*(?:26|27|28|29|30|31|32|33|34|35|36|37)\b", "version_code=25", normalised
    )
    normalised = re.sub(
        r"assertEquals\((?:26|27|28|29|30|31|32|33|34|35|36|37),\s*BuildConfig\.VERSION_CODE\)",
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
    changed = set(_git(root, "diff", "--name-only", CODE30_1_BASE).splitlines())
    changed.update(
        _git(root, "ls-files", "--others", "--exclude-standard").splitlines()
    )
    return {path for path in changed if path}


def _verify_code30_2_audit_plan(root: Path, errors: list[str]) -> None:
    plan_path = root / CODE30_2_AUDIT_PLAN_PATH
    try:
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
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
    if len(steps) != 389 or plan.get("expected_sessions") != 16:
        errors.append("Code30.2 business audit plan must retain 389 steps and 16 sessions")

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

    runner_path = root / "scripts/run_code26_physical_business_audit.sh"
    try:
        runner = runner_path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
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
        candidate_path = root / path
        if not candidate_path.is_file():
            errors.append(f"reviewed Code30.2 file was removed: {path}")
            continue
        actual_sha256 = hashlib.sha256(candidate_path.read_bytes()).hexdigest()
        if actual_sha256 != expected_sha256:
            errors.append(f"reviewed Code30.2 file differs from its approved bytes: {path}")

    for path in CODE30_2_FREEZE_CONTROL_PATHS:
        if not (root / path).is_file():
            errors.append(f"Code30.2 freeze control was removed: {path}")

    _verify_code30_2_audit_plan(root, errors)


def verify_repository(root: Path, baseline: str = CODE25_BASE) -> RegressionFreezeReport:
    root = root.resolve()
    _git(root, "cat-file", "-e", f"{baseline}^{{commit}}")
    baseline_paths = _git(root, "ls-tree", "-r", "--name-only", baseline).splitlines()
    test_paths = sorted(path for path in baseline_paths if _is_baseline_test(path))
    if not test_paths:
        raise RegressionFreezeError("Code 25 baseline contains no test files")

    errors: list[str] = []
    _git(root, "cat-file", "-e", f"{CODE30_1_BASE}^{{commit}}")
    _verify_code30_2_exact_delta(root, errors)
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
            REVIEWED_CODE30_2_SHA256.get(path)
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
        current_expected_sha256 = REVIEWED_CODE30_2_SHA256.get(
            path,
            REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(path, expected_sha256),
        )
        if not candidate_path.is_file():
            errors.append(f"reviewed Code30.1 file was removed: {path}")
        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != current_expected_sha256:
            errors.append(f"reviewed Code30.1 file differs from its approved bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.items():
        candidate_path = root / path
        current_expected_sha256 = REVIEWED_CODE30_2_SHA256.get(path, expected_sha256)
        if not candidate_path.is_file():
            errors.append(f"reviewed Code30.1 deletion-replay file was removed: {path}")
        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != current_expected_sha256:
            errors.append(
                f"reviewed Code30.1 deletion-replay file differs from its approved bytes: {path}"
            )

    for path, expected_sha256 in REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.items():
        candidate_path = root / path
        current_expected_sha256 = REVIEWED_CODE30_2_SHA256.get(path, expected_sha256)
        if not candidate_path.is_file():
            errors.append(f"reviewed Code30.1 build-36 release test was removed: {path}")
        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != current_expected_sha256:
            errors.append(
                f"reviewed Code30.1 build-36 release test differs from its approved bytes: {path}"
            )

    for path, expected_sha256 in REVIEWED_CODE30_1_RELEASE_TEST_SHA256.items():
        candidate_path = root / path
        current_expected_sha256 = REVIEWED_CODE30_2_SHA256.get(
            path,
            REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.get(path, expected_sha256),
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
            f"production source is outside the frozen Code 26 scope: {path}"
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
