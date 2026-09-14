#!/usr/bin/env python3
"""Fail closed when Code 26 through current Code 29 weakens the proven Code 25 regression surface.

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
build 36 carries the exact reviewed replay correction. None may delete, disable,
reorder, or rewrite an existing test
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
} | REVIEWED_WEB_AUTH_PATHS | REVIEWED_PRICING_PRODUCTION_PATHS | REVIEWED_PACKAGING_UI_PATHS | REVIEWED_CODE29_2_PRODUCTION_PATHS | REVIEWED_CODE30_PRODUCTION_PATHS | REVIEWED_CODE30_1_PRODUCTION_PATHS | REVIEWED_CODE30_1_DELETION_REPLAY_PRODUCTION_PATHS

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
        r"version_code\s*=\s*(?:26|27|28|29|30|31|32|33|34|35|36)\b", "version_code=25", normalised
    )
    normalised = re.sub(
        r"assertEquals\((?:26|27|28|29|30|31|32|33|34|35|36),\s*BuildConfig\.VERSION_CODE\)",
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


def verify_repository(root: Path, baseline: str = CODE25_BASE) -> RegressionFreezeReport:
    root = root.resolve()
    _git(root, "cat-file", "-e", f"{baseline}^{{commit}}")
    baseline_paths = _git(root, "ls-tree", "-r", "--name-only", baseline).splitlines()
    test_paths = sorted(path for path in baseline_paths if _is_baseline_test(path))
    if not test_paths:
        raise RegressionFreezeError("Code 25 baseline contains no test files")

    errors: list[str] = []
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
            REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(path)
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
        current_expected_sha256 = REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(
            path, expected_sha256
        )
        if not candidate_path.is_file():
            errors.append(f"reviewed Code30.1 file was removed: {path}")
        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != current_expected_sha256:
            errors.append(f"reviewed Code30.1 file differs from its approved bytes: {path}")

    for path, expected_sha256 in REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.items():
        candidate_path = root / path
        if not candidate_path.is_file():
            errors.append(f"reviewed Code30.1 deletion-replay file was removed: {path}")
        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != expected_sha256:
            errors.append(
                f"reviewed Code30.1 deletion-replay file differs from its approved bytes: {path}"
            )

    for path, expected_sha256 in REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.items():
        candidate_path = root / path
        if not candidate_path.is_file():
            errors.append(f"reviewed Code30.1 build-36 release test was removed: {path}")
        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != expected_sha256:
            errors.append(
                f"reviewed Code30.1 build-36 release test differs from its approved bytes: {path}"
            )

    for path, expected_sha256 in REVIEWED_CODE30_1_RELEASE_TEST_SHA256.items():
        candidate_path = root / path
        current_expected_sha256 = REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.get(
            path, expected_sha256
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
