from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path

import pytest

from scripts.verify_code26_regression_freeze import (
    CODE30_2_BASE,
    CODE30_2_FREEZE_CONTROL_PATHS,
    CODE30_3_BASE,
    CODE30_3_FREEZE_CONTROL_PATHS,
    CODE30_4_FREEZE_CONTROL_PATHS,
    REVIEWED_CODE30_2_PRODUCTION_PATHS,
    REVIEWED_CODE30_2_SHA256,
    REVIEWED_CODE30_3_PRODUCTION_PATHS,
    REVIEWED_CODE30_3_SHA256,
    REVIEWED_CODE30_4_PRODUCTION_PATHS,
    REVIEWED_CODE30_4_SHA256,
)


ROOT = Path(__file__).resolve().parents[1]
ORIGINAL_CODE29 = "0949620b4632ebd6accdfa62a203be8d85b31a24"
CADDY_SHA256 = "951a0136950bb9edf60ff5cec6ca2df0a041b27ded9e610f0aab49b168739dac"
LOCK_VERIFIER_SHA256 = "338cea91b56c2d85be109d259af1d77aa6d4ceaab4b13b379876c02e4485c029"
HISTORICAL_CODE29_GUARD_SHA256 = (
    "2d761a871369d891849e0170c85533a0269d209f7949b376300eb0994bd5fc15"
)
HISTORICAL_CADDY_GUARD_SHA256 = (
    "523d90bd475ef1bd71e1e820f4b5053dc26f2d1a3581ddabafee39d257797b8e"
)
CODE30_2_FREEZE_CONSTANTS_SHA256 = (
    "600af68b8844e6c15565823299ac0ea78a58b199afe48d6ae811adbd090a7377"
)
CODE30_2_FREEZE_HELPERS_SHA256 = (
    "e2456433325653bf328cf98d20bfd9fc3880cdafedb2065e6695d4f7814a4b33"
)
CODE30_2_FREEZE_SCRIPT_SHA256 = (
    "a83b6ad36ac931366a609b61adb89685368e725524b0e3a14ad63f259814f693"
)
CODE30_2_FREEZE_TEST_SHA256 = (
    "303c44945334417d810b27c07982a8582220f08642183b4107dcda8e67568a14"
)
CODE30_3_FREEZE_SCRIPT_SHA256 = "fc217427931f0dc9a6fb711f8475b5bced2770145f94d42c3dfd104cc5829c81"
CODE30_4_FREEZE_SCRIPT_SHA256 = "c57dd0d3276d0a3f8fbd4ab94c7abebf04af2f68fab62e28e497bda2127a3f18"
OPERATOR_RECORD_SHA256 = {
    "docs/CODE29_RELEASE_CANDIDATE.md": "8f15d3f031daef79ecd1a5680b8bf9f527598d558a004ea198225919898821e4",
    "docs/DISTRIBUTION.md": "601007e7eaca4b700c82e5e70ffd139a6ff9c9921e5c53581284491808710b1e",
    "docs/SERVER_DRIVEN_ANDROID_UPDATES.md": "5808e0f8e5d9eb0829c5e3e570bc3304028d2f828e858eb75c2cce3440afe583",
}
REVIEWED_UI_SHA256 = {
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt": (
        "c2473b6b54f430d5cfcad724fcd7f51451ea3a5064839dd91c6e2000cd6a1f95"
    ),
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/PosEmptyCatalogueUiTest.kt": (
        "b56a28fda657fd04490b5dd055e24c524747dd3ba5b1b2e5f34af79f7e907ef5"
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/inventory/InventoryScreen.kt": (
        "ef0b0be225b11243a918cf503a4ef0df59fd5504150cdbf65f64bad67c03c79e"
    ),
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/inventory/InventoryLoadedWorkspaceUiTest.kt": (
        "69d099d8bfa6df2d6db7a23d4f9ac70dd6a30d8729bd3ec3c5a1e586a5ab91af"
    ),
}
REVIEWED_WEB_SESSION_SHA256 = {
    "frontend/src/lib/api.ts": "9818163fcf287f512da6b7a74dc322fcf6a24ecd4bacb710077d33bfc9163084",
    "frontend/src/lib/api-cookie-session-renewal.test.ts": (
        "a0e8f10b66e0992a4ff0562c901c5f322bc8e3231c56cb9b150d897918cbd6ea"
    ),
    "frontend/src/lib/api-session-renewal.test.ts": (
        "03bde0984b2a6e09657d4f93bd5ea3b1e6be37d7b22345f235f6733b1b856129"
    ),
}
REVIEWED_BACKEND_REFRESH_LOCK_SHA256 = {
    "backend/app/services/auth/refresh_sessions.py": (
        "6c13788eb0198bece160f7275b9c9a9f6bfc59ac382d96bbeaf682ccca561465"
    ),
    "backend/tests/integration/test_auth_refresh_locking.py": (
        "719ba8b478d70e2a6a565f564b785df8e4ee7f89c0f92260891c4e7e808887cf"
    ),
}
REVIEWED_PRICING_CARD_SHA256 = {
    "backend/app/api/v1/gaming/router.py": (
        "4f10b4ecbfc82cbcc82f1bff2eb14e5835c6eea60a34b31438215213b880f177"
    ),
    "backend/app/services/gaming/tariff_catalog.py": (
        "c050f6df3a19681c46af9d649cbd112ea135960052393f6f50d4d9565ea97839"
    ),
    "backend/scripts/ensure_gaming_tariff.py": (
        "33eed139be67dc8800065b23c3abe19f6f885ca38a1aa3b7e4e6e0e0048619c1"
    ),
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
    "frontend/src/modules/gaming/GamingScreen.tsx": (
        "01ca0860071ef0cc555556e4c5f92f06e0d35ad707a8fbf5d21f17f111d5f422"
    ),
    "frontend/src/modules/gaming/gaming-tariff.test.ts": (
        "03b1f1ca87c7e04c3d6e48470403ac383080384d1bc69a0282e3f9e4a0771e50"
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt": (
        "7059c4f228680263cc7dda5822d54db000b64a5b735e438f514db04dad9d4160"
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt": (
        "8c38bccc106338cd761df36fd396dfe153cb6e6d670b733a1bce2a7c8fb9a6f1"
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
REVIEWED_PACKAGING_LABEL_SHA256 = {
    "frontend/src/modules/settings/tabs/DevicesUpdatesTab.tsx": (
        "dcdce6cb8ae599bf4b2dd67ca79131df1ea657383b752c0679b79202965172a8"
    ),
    "frontend/src/modules/settings/tabs/SystemHealthTab.tsx": (
        "a3976e9634de7536ead6b0a0b58444fe51636a3ee83848aed5427a5841c14367"
    ),
    "frontend/src/modules/settings/tabs/SystemHealthTab.test.tsx": (
        "0c6e16e7c782ef96463685ea8509b7f0a61070a5e4305c1540188dc7ac7d2724"
    ),
    "frontend/src/modules/remote-assistance/DeviceListPanel.tsx": (
        "d425456084f4160d31b77480dc55eca9beb6d4a0216bef8328f04046ca11b99f"
    ),
    "frontend/src/modules/remote-assistance/DeviceDetailPanel.tsx": (
        "a902ea672d9464a9cd53b7c73ed926f03745d83beaab3a4be7a41c9d3607efea"
    ),
}

REVIEWED_CODE29_2_FEATURE_SHA256 = {
    "android-native/app/schemas/cloud.dcompany.erp.core.db.ErpDatabase/46.json": "088dd8ad4e255ce63d0295845fd1447b7299844dcae8611c392f0d0467008ff5",
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/GamingDaoRecoveryTest.kt": "7c665885527d83edcdd9fef2e0afa8eafea0b80eaaf118d8bc37fe5013d91a90",
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/MigrationTest.kt": "5be51fb92b8cd5d79dbd3b616cab0f218968934795e1425e5fefc1819ea0ac22",
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt": "92f604eeeac4ebf0c39d9c8a13778764b2611d2ecd8b740e458d486d3b94bff1",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/Dao.kt": "075340c5323d14caf95293c4b07c976c25585e60204417c233e1e9f00c89d649",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingDao.kt": "43bb1421f015ac7a42fe5d34e93cf2e590af2a46b2c13b51cd249b7fe5a366cd",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingEntities.kt": "ffee5200af6a9639026b0066b3907c37cc7c74bca3da2ebc7d01ada77157a910",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/Migrations.kt": "3d48cafa9006fcb4cca6324f4b6685e3a63e7d392d7a380181bde91d32c01784",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/sync/SyncEngine.kt": "37ea7669170889ee63b7149d8b1e928d4ddbc4eef83e08306552fa5621d97d28",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/FeatureProfile.kt": "d90368aa525bc1d34d8003d57f1ce039fc84ecaa2144436228e9d9213b850dbe",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomerPlaytimeViewModel.kt": "6601fe52d74c31015ccc47d8fffdb57c0a6b10745eda5c8291075594c868d7ac",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersApi.kt": "c860a98a677081f23d534fd01bb77cdf8d5d2a1bf7532b5c29a42e55a1f7d088",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersModels.kt": "5a6c5929ac180945d7f031859f94e9590f42f31ef018bfcbf1186695832845db",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersScreen.kt": "d6fc3d35e5d0eb461aaf0aed0de71a2d058f269738d759abf98ada092dd458d1",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceModels.kt": "c32e72c310759bbe222ba5547c154dcb421d5c35fad7bfd8bf41dee8f52ac053",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingApi.kt": "d4ebb1e42817061eee12fb5773f0edca2a0ead77fc573cad76be5d993dc20610",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt": "d41fa196b944f3368575009ec4cb9f672dd72b6d727305df559bb25ac363f6ef",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt": "b04f41989cf85cfc1443d7982de3fd3ce33f39fcb822eb7f30c291c95b8cf1ab",
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/GamingCentreFeatureProfileTest.kt": "e26fdac9a83f152af8ab7dae5b80014516f88f47f93a0e78978524ea9270c9f0",
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/FinancePresentationPolicyTest.kt": "6754029ae8b59b5c9a0c1b273f8f9e735a3be243b88f8ee527be2e36af537535",
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingApiContractTest.kt": "abb862ce3a2e1ca060617e5f0350a2f568159002ca11e428d0387128ad5a1c4c",
    "backend/alembic/versions/0072_customer_gaming_playtime_draft.py": "5778dce99714ffc57fdfa573522ca3f94019ca17f053beb843a0c5313df04e30",
    "backend/app/api/v1/customers/router.py": "748822ee9fc6ac0378684ff2de6c900101b8841e16fb01f921d816505a0d138c",
    "backend/app/api/v1/gaming/router.py": "0303f3bb7c31e8d8fcac3de9804601f4d913cbce9a67c7164a5a20ede4bd301c",
    "backend/app/api/v1/pos/router.py": "cc5b4ebbd4bce3c145130ccbce80c133b8e1fdc806da78ce13bc27496a815580",
    "backend/app/models/__init__.py": "a5ca3c6b63f4dd2ac2327843495c2d8f736b8c7bdaac21ec3f2d7817f065ed96",
    "backend/app/models/customer.py": "04feda7fdba4be398230367f613645231ce2f1079ddb68c09ea9ad25a695d35a",
    "backend/app/models/gaming.py": "300bb1451ecad91563fe7836b552c85ebce08d5f664fa91ebbeb8d5bfcb4cad4",
    "backend/app/services/audit/recorder.py": "333c377aea607382dc8b550470c08eee2ca596005813756dd5374748f5f65f05",
    "backend/app/services/customers/__init__.py": "2c115b931f7c0472f3fdb59220572ea87ebc56d9ef409be0fce293091be1ad9a",
    "backend/app/services/customers/identity.py": "8658c0b06251788936021ed63c37a21dec346106b3f1e1aebaf32f8f305e5b24",
    "backend/app/services/customers/playtime.py": "878a9936b38641e4ba16e44504ee14e25ef41bb827a5edc0b29158bf182a8fef",
    "backend/app/services/pos/customer_identity.py": "e98d3934ea553fa072ed53ba5f9ce2ddce3d18094ec11141721bceaf99df5f64",
    "backend/app/services/pos/membership_benefits.py": "d5ce957545848aef590d9e5ae3def7b08a7022848b04f26f8d4ac184854867ba",
    "backend/app/services/pos/points.py": "c098a827d4839bb8d50c28fb7c5460a186814fa2be9b16a8a8160ff1c751fd83",
    "backend/app/services/pos/pricing.py": "6b76b2bf77a3cb0408a9dceea56e407c83ea87dc96d61f666006c7e589cb8a45",
    "backend/tests/integration/test_customer_playtime_draft.py": "43a9545d4fbcae27440e0cc302d20109b3abf2d412152e4488fe9e0d462b111b",
    "backend/tests/integration/test_customer_playtime_migration.py": "c3a8542191ff2fd04e4ca4adcec91cb4a6f7e844c77dd696e3475bddbe155ebe",
    "backend/tests/integration/test_points_reservation_balance.py": "ef4bd05349fde10b2e1c14bc2b968e388f9a76318bceaad8e471f6a9912118a1",
    "backend/tests/integration/test_pos_refund_settlement.py": "212dfd56951fc9c980d5a0b20bf19e33c68219512d2acf23f7ace699fc51fdec",
    "backend/tests/unit/test_customer_playtime_draft.py": "e724622e885a0e256ad4909505e2ff9dbcbce15e75bed7be42ee9ea9f76df408",
    "backend/tests/unit/test_gaming_reconciliation.py": "57db9b7609889be1e7bef2e61d403a7e693ae3f7d06eee5c2a1d463ef7d6fa51",
    "backend/tests/unit/test_operational_route_integrity.py": "11abe0c4e0777c45c05e58f2ee39bcf8f9e81fd21f64c6759da5d414c550843f",
    "docs/CUSTOMER_PLAYTIME_DRAFT.md": "a79e5e21f83fb5d5d66856b88625d0be0ec52bbc228fcf8c5a2cfaa7cd21264d",
    "frontend/src/app/App.tsx": "455638bb2cc83e2aa63abb98b0bf1054a3c2e03c76391fec3cefdf46334c6fa7",
    "frontend/src/lib/erp-api.ts": "599cabd0c406812ee18c34671aa8613d33ad94be2450823636a555f3740cc383",
    "frontend/src/lib/product-profile.test.ts": "c6f6a2e6a53967c85abda6ab9c6ff343b4cb2922e4258b4b5a97f1b64a2ba6b1",
    "frontend/src/lib/product-profile.ts": "40adb273de1d386f952ccc817e6b321460204e93f3f0920cfbd95d644ea481b4",
    "frontend/src/modules/customers/CustomerPlaytimePanel.tsx": "b74fea77d222da4b61cb519bb57f76eb84dca84a2a97e8218324433768bc3993",
    "frontend/src/modules/customers/CustomersScreen.tsx": "190efa7dad02559ee378775a8d8299979a2897ae858e68a967f9efc88d775120",
    "frontend/src/modules/customers/customer-access.test.ts": "9133d3ccef407e93828276155029018727b0be7b0f78e0c350c097c264a40bbc",
    "frontend/src/modules/customers/customer-access.ts": "889681f95a15b41552e7fd2addda2333a0ff0a40e32e7306d54e56a99b8a0b0e",
    "frontend/src/modules/customers/customer-playtime.test.ts": "71002c50ef34785a2992b50f2d033dc5ffad2d3d665a6649da7fa470106f449d",
    "frontend/src/modules/customers/customer-playtime.ts": "b4b879c0eff0f00314c6c27a21b5c6968ee7223d1df6b0c9836c8ab67278649b",
    "frontend/src/modules/gaming/GamingScreen.tsx": "f21c3d6936f1d84db891f1087da3a1d680ba96dd0b5e3fc82364871197f6b7e9",
}
REVIEWED_CODE30_FEATURE_SHA256 = {
    "android-native/app/schemas/cloud.dcompany.erp.core.db.ErpDatabase/47.json": "4343888a27a56edd0a0ba2307a7ff269990b64558fbd895840bbbb20bca52057",
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/MigrationTest.kt": "898d83ae44d48ba617a0b62eb8536a00239ddf8759b9605e08a9976c5bf59abe",
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt": "3663476bd9d05f4ad077e1660a446dcdbb6dcf73fa4451b10e6164a696c77220",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/CustomerDao.kt": "e5a36d1bdcbba83ce7b1c557830396db5f05b996f9ae6bc9b605f92c9dc1e1f9",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/Dao.kt": "93257a72c347398abe2a5859102a139b311b2cb869a99127dcbff17654ab9c29",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/GamingEntities.kt": "520dbdcc79b3a4f028a4350370ab7b840498e798ac743d5285afc8d29d50ec53",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/db/Migrations.kt": "221d45efaf731a5eb77c576e9d1440ffe90374b75ce2da276625fe72acbd4f9a",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/sync/SyncEngine.kt": "505b192e9765b1f4fd7429727aa0e6a4bbd7d1e0202206666790065be5f16bf9",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingApi.kt": "9279123af258022a65e42d95dccd720915fdda473468707e471d1ed4f08c82c6",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingCustomerSearch.kt": "c41599dc2fb4d19fef26d2d8d087224d016abd398b9300b88d980b09f149415a",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt": "b80b033d4a8ae25252dd826e313652482c8ee0cfffd9e77b85042074dc6de465",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt": "8bdc5b836805c7405007cd8261dcc97fa71a409909f4bec2badb660fd8283971",
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingApiContractTest.kt": "a91358fff7ea9c147c346dd2a20941f6cac156c91f012a6bb2e1cd745fd1b3a1",
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingCustomerSearchTest.kt": "335fd894ca07e092177ff0f12bd39093313b3d8fd338d11eef55f95fc46ceb2d",
    "backend/app/api/v1/gaming/router.py": "10d6a0f2508e516ce26687d9babde517d94b57fc79b778c536df40f32008f722",
    "backend/app/services/customers/identity.py": "61bf633142010cf773a633fcf7095daf52eab795360d1a93985fe1c9596813a7",
    "backend/tests/integration/test_gaming_customer_selection.py": "c7843c8d7fd2ad3771fedb75256ce638881979c81fcbfafa1928cd31f2b5ffe3",
    "frontend/src/lib/erp-api.ts": "7f8a349baf5a3993f7390d7febf4f7de23a89d468139ababeeb3605b5c814246",
    "frontend/src/modules/gaming/GamingCustomerPicker.test.ts": "8d604cc52f28c041cc5c5b541dc288ea739ba691b062db0856950ca59c1f818c",
    "frontend/src/modules/gaming/GamingCustomerPicker.tsx": "e68934a406f7ac952a199d716a838ad473e672bb4be10e794d3e670af0fa391b",
    "frontend/src/modules/gaming/GamingScreen.tsx": "b4a8a8c85f27bcc3875ab23ce4707f2375b45dd548568365628998a25e08e4ab",
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
CODE30_1_RELEASE_METADATA_PATHS = {
    "PROJECT_STATE.md",
    "docs/CODE30_1_PATCH_CANDIDATE.md",
}
CODE30_RELEASE_METADATA_PATHS = {
    "docs/CODE30_RELEASE_CANDIDATE.md",
}

CODE29_2_RELEASE_METADATA_PATHS = {
    "AGENTS.md",
    "README.md",
    "docs/CODE29_2_RELEASE_CANDIDATE.md",
}

EXPECTED_CORRECTION_PATHS = {
    ".env.production.example",
    ".github/workflows/ci.yml",
    ".github/workflows/release.yml",
    "android-native/app/build.gradle.kts",
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/PosEmptyCatalogueUiTest.kt",
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt",
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/inventory/InventoryLoadedWorkspaceUiTest.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/inventory/InventoryScreen.kt",
    "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt",
    "android-native/app/src/test/java/cloud/dcompany/erp/core/sync/GamingPackageExtensionReplayPolicyTest.kt",
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingStationPresentationTest.kt",
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModelRecoveryTest.kt",
    "android-native/audit-driver/plans/code26-gaming-finance-physical.json",
    "backend/app/__init__.py",
    "backend/app/api/v1/gaming/router.py",
    "backend/app/services/gaming/tariff_catalog.py",
    "backend/app/services/auth/refresh_sessions.py",
    "backend/pyproject.toml",
    "backend/scripts/ensure_gaming_tariff.py",
    "backend/tests/integration/test_auth_refresh_locking.py",
    "backend/tests/integration/test_captured_shift_opening.py",
    "backend/tests/integration/test_gaming_co_owner_stop.py",
    "backend/tests/integration/test_gaming_phase2_contracts.py",
    "backend/tests/integration/test_gaming_tariff_pos_e2e.py",
    "backend/tests/unit/test_client_compatibility.py",
    "backend/tests/unit/test_gaming_tariff_catalog.py",
    "backend/tests/unit/test_release_audit_fixes.py",
    "backend/tests/unit/test_release_contracts.py",
    "backend/tests/unit/test_remote_assistance_contract.py",
    "backend/tests/unit/test_runtime_release_parity.py",
    "docker-compose.prod.yml",
    "docs/CODE29_RELEASE_CANDIDATE.md",
    "docs/DISTRIBUTION.md",
    "docs/SERVER_DRIVEN_ANDROID_UPDATES.md",
    "frontend/.env.example",
    "frontend/package-lock.json",
    "frontend/package.json",
    "frontend/src/lib/api-cookie-session-renewal.test.ts",
    "frontend/src/lib/api-session-renewal.test.ts",
    "frontend/src/lib/api.ts",
    "frontend/src/modules/remote-assistance/DeviceDetailPanel.tsx",
    "frontend/src/modules/remote-assistance/DeviceListPanel.tsx",
    "frontend/src/modules/gaming/GamingScreen.tsx",
    "frontend/src/modules/gaming/gaming-tariff.test.ts",
    "frontend/src/modules/settings/tabs/DevicesUpdatesTab.tsx",
    "frontend/src/modules/settings/tabs/SystemHealthTab.test.tsx",
    "frontend/src/modules/settings/tabs/SystemHealthTab.tsx",
    "infra/scripts/install-on-vm.sh",
    "scripts/analyze_code26_physical_evidence.py",
    "scripts/run_code26_physical_business_audit.sh",
    "scripts/verify_code26_regression_freeze.py",
    "tests/test_android_release_version.py",
    "tests/test_android_runtime_parity.py",
    "tests/test_caddy_dependency_security.py",
    "tests/test_code26_physical_audit_lane.py",
    "tests/test_code26_regression_freeze.py",
    "tests/test_code29_deployment_identity.py",
    "tests/test_code29_installer_correction.py",
    "tests/verify_production_installer_lock_metadata.py",
}


def _git(*arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout


def _original(path: str) -> str:
    return _git("show", f"{ORIGINAL_CODE29}:{path}")


def _current(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def _historical_bytes(ref: str, path: str) -> bytes:
    return subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    ).stdout


def _replace_exact(source: str, replacements: tuple[tuple[str, str, int], ...]) -> str:
    for old, new, expected_count in replacements:
        assert source.count(old) == expected_count
        source = source.replace(old, new)
    return source


def _normalise_code30_2_freeze_script_to_code30_1(source: str) -> str:
    constants_start = source.index('CODE30_1_BASE = "')
    constants_end = source.index("RELEASE_IDENTITY_TESTS = {")
    constants = source[constants_start:constants_end]
    assert hashlib.sha256(constants.encode("utf-8")).hexdigest() == (
        CODE30_2_FREEZE_CONSTANTS_SHA256
    )
    source = source[:constants_start] + source[constants_end:]

    helpers_start = source.index("def _code30_2_delta_paths")
    helpers_end = source.index("def verify_repository")
    helpers = source[helpers_start:helpers_end]
    assert hashlib.sha256(helpers.encode("utf-8")).hexdigest() == (
        CODE30_2_FREEZE_HELPERS_SHA256
    )
    source = source[:helpers_start] + source[helpers_end:]

    return _replace_exact(
        source,
        (
            (
                '"""Fail closed when Code 26 through Code30.2 weakens the proven Code 25 surface.',
                '"""Fail closed when Code 26 through current Code 29 weakens the proven Code 25 regression surface.',
                1,
            ),
            (
                "build 36 carries the exact reviewed replay correction. Code30.2 adds only the\n"
                "exact reviewed finance, receipt-evidence, Google Sheets mirror, Room 51,\n"
                "Alembic 0078, release metadata and five-mode business-audit delta below. None\n"
                "may delete, disable, reorder, or rewrite an existing test\n",
                "build 36 carries the exact reviewed replay correction. None may delete, disable,\n"
                "reorder, or rewrite an existing test\n",
                1,
            ),
            (
                " | REVIEWED_CODE30_2_PRODUCTION_PATHS\n\nPRODUCTION_PREFIXES",
                "\n\nPRODUCTION_PREFIXES",
                1,
            ),
            ('        ("3.1.29", "3.1.14"),\n', "", 1),
            (
                '        ("Code30.2", "Code25"),\n'
                '        ("code30.2", "code25"),\n'
                '        ("CODE30.2", "CODE25"),\n'
                '        ("Code 30.2", "Code 25"),\n'
                '        ("code 30.2", "code 25"),\n'
                '        ("Code 30 point 2", "Code 25"),\n'
                '        ("code 30 point 2", "code 25"),\n'
                '        ("CODE30_POINT_2", "CODE25"),\n'
                '        ("code30_point_2", "code25"),\n',
                "",
                1,
            ),
            (
                "(?:26|27|28|29|30|31|32|33|34|35|36|37)",
                "(?:26|27|28|29|30|31|32|33|34|35|36)",
                2,
            ),
            (
                '    _git(root, "cat-file", "-e", f"{CODE30_1_BASE}^{{commit}}")\n'
                "    _verify_code30_2_exact_delta(root, errors)\n",
                "",
                1,
            ),
            ("            REVIEWED_CODE30_2_SHA256.get(path)\n            or ", "            ", 1),
            (
                "        current_expected_sha256 = REVIEWED_CODE30_2_SHA256.get(\n"
                "            path,\n"
                "            REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(path, expected_sha256),\n"
                "        )\n",
                "        current_expected_sha256 = REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(\n"
                "            path, expected_sha256\n"
                "        )\n",
                1,
            ),
            (
                "        current_expected_sha256 = REVIEWED_CODE30_2_SHA256.get(path, expected_sha256)\n",
                "",
                2,
            ),
            (
                "        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != current_expected_sha256:\n"
                "            errors.append(\n"
                '                f"reviewed Code30.1 deletion-replay file differs from its approved bytes: {path}"\n',
                "        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != expected_sha256:\n"
                "            errors.append(\n"
                '                f"reviewed Code30.1 deletion-replay file differs from its approved bytes: {path}"\n',
                1,
            ),
            (
                "        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != current_expected_sha256:\n"
                "            errors.append(\n"
                '                f"reviewed Code30.1 build-36 release test differs from its approved bytes: {path}"\n',
                "        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != expected_sha256:\n"
                "            errors.append(\n"
                '                f"reviewed Code30.1 build-36 release test differs from its approved bytes: {path}"\n',
                1,
            ),
            (
                "        current_expected_sha256 = REVIEWED_CODE30_2_SHA256.get(\n"
                "            path,\n"
                "            REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.get(path, expected_sha256),\n"
                "        )\n",
                "        current_expected_sha256 = REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.get(\n"
                "            path, expected_sha256\n"
                "        )\n",
                1,
            ),
        ),
    )


def _changed_paths() -> set[str]:
    changed = set(_git("diff", "--name-only", ORIGINAL_CODE29).splitlines())
    changed.update(_git("ls-files", "--others", "--exclude-standard").splitlines())
    return {path for path in changed if path}


def _assert_exact_text(path: str, actual: str, expected: str) -> None:
    assert actual == expected, f"unexpected corrected Code 29 content: {path}"


def _assert_sha256(path: str, content: bytes, expected: str) -> None:
    assert hashlib.sha256(content).hexdigest() == expected, (
        f"unexpected corrected Code 29 content: {path}"
    )


def _current_reviewed_sha256(path: str, fallback: str) -> str:
    return REVIEWED_CODE30_4_SHA256.get(
        path,
        REVIEWED_CODE30_3_SHA256.get(
            path, REVIEWED_CODE30_2_SHA256.get(path, fallback)
        ),
    )


def _reviewed_sha256(path: str, historical: dict[str, str]) -> str:
    if path in REVIEWED_CODE30_4_SHA256:
        return REVIEWED_CODE30_4_SHA256[path]
    return REVIEWED_CODE30_3_SHA256.get(
        path,
        REVIEWED_CODE30_2_SHA256.get(
            path,
            REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(
                path,
                REVIEWED_CODE30_1_FEATURE_SHA256.get(
                    path,
                    REVIEWED_CODE30_FEATURE_SHA256.get(
                        path,
                        REVIEWED_CODE29_2_FEATURE_SHA256.get(path, historical[path]),
                    ),
                ),
            ),
        ),
    )


def _identity_expected(path: str) -> str:
    counts = {
        "android-native/app/build.gradle.kts": 1,
        "backend/pyproject.toml": 1,
        "backend/app/__init__.py": 1,
        "frontend/package.json": 1,
        "frontend/package-lock.json": 2,
        "frontend/.env.example": 1,
        "docker-compose.prod.yml": 6,
    }
    replacements = [("3.1.19", "3.1.30", counts[path])]
    if path == "android-native/app/build.gradle.kts":
        replacements.append(("versionCode = 29", "versionCode = 38", 1))
    return _replace_exact(_original(path), tuple(replacements))


def _workflow_step() -> str:
    return (
        "      - name: Verify production installer lock metadata\n"
        "        run: |\n"
        "          set -euo pipefail\n"
        '          test -n "$pythonLocation"\n'
        '          configured_python="$pythonLocation/bin/python"\n'
        '          test "${configured_python#/}" != "$configured_python"\n'
        '          test -x "$configured_python"\n'
        '          sudo -n -- "$configured_python" \\\n'
        "            tests/verify_production_installer_lock_metadata.py\n"
    )


def _workflow_expected(path: str) -> str:
    anchors = {
        ".github/workflows/ci.yml": (
            "          pip install --only-binary=:all: --require-hashes "
            "-r ops/backup-requirements.lock\n"
        ),
        ".github/workflows/release.yml": (
            "          python -m pip_audit -r ops/backup-requirements.lock\n"
        ),
    }
    anchor = anchors[path]
    android_setup = (
        "      - uses: android-actions/setup-android@"
        "40fd30fb8d7440372e1316f5d1809ec01dcd3699 # v4.0.1\n"
    )
    configured_android_setup = (
        android_setup
        + "        with:\n"
        + "          packages: platform-tools\n"
    )
    return _replace_exact(
        _original(path),
        (
            (anchor, anchor + _workflow_step(), 1),
            (android_setup, configured_android_setup, 1),
        ),
    )


def test_live_delta_is_exactly_the_reviewed_code29_correction() -> None:
    assert _changed_paths() == (
        EXPECTED_CORRECTION_PATHS
        | set(REVIEWED_CODE29_2_FEATURE_SHA256)
        | set(REVIEWED_CODE30_FEATURE_SHA256)
        | set(REVIEWED_CODE30_1_FEATURE_SHA256)
        | set(REVIEWED_CODE30_1_DELETION_REPLAY_SHA256)
        | CODE29_2_RELEASE_METADATA_PATHS
        | CODE30_RELEASE_METADATA_PATHS
        | CODE30_1_RELEASE_METADATA_PATHS
        | set(REVIEWED_CODE30_2_SHA256)
        | set(CODE30_2_FREEZE_CONTROL_PATHS)
        | set(REVIEWED_CODE30_3_SHA256)
        | set(CODE30_3_FREEZE_CONTROL_PATHS)
        | set(REVIEWED_CODE30_4_SHA256)
        | set(CODE30_4_FREEZE_CONTROL_PATHS)
    )
    protected_application_changes = {
        path
        for path in _changed_paths()
        if path.startswith(("backend/app/", "frontend/src/", "android-native/app/src/main/"))
    }
    assert protected_application_changes == {
        "backend/app/__init__.py",
        "backend/app/api/v1/gaming/router.py",
        "backend/app/services/gaming/tariff_catalog.py",
        "backend/app/services/auth/refresh_sessions.py",
        "frontend/src/lib/api-cookie-session-renewal.test.ts",
        "frontend/src/lib/api-session-renewal.test.ts",
        "frontend/src/lib/api.ts",
        "frontend/src/modules/remote-assistance/DeviceDetailPanel.tsx",
        "frontend/src/modules/remote-assistance/DeviceListPanel.tsx",
        "frontend/src/modules/gaming/GamingScreen.tsx",
        "frontend/src/modules/gaming/gaming-tariff.test.ts",
        "frontend/src/modules/settings/tabs/DevicesUpdatesTab.tsx",
        "frontend/src/modules/settings/tabs/SystemHealthTab.test.tsx",
        "frontend/src/modules/settings/tabs/SystemHealthTab.tsx",
        "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt",
        "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt",
        "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt",
        "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/inventory/InventoryScreen.kt",
    } | {
        path
        for path in REVIEWED_CODE29_2_FEATURE_SHA256
        if path.startswith(("backend/app/", "frontend/src/", "android-native/app/src/main/"))
    } | {
        path
        for path in REVIEWED_CODE30_FEATURE_SHA256
        if path.startswith(("backend/app/", "frontend/src/", "android-native/app/src/main/"))
    } | {
        path
        for path in REVIEWED_CODE30_1_FEATURE_SHA256
        if path.startswith(("backend/app/", "frontend/src/", "android-native/app/src/main/"))
    } | {
        path
        for path in REVIEWED_CODE30_1_DELETION_REPLAY_SHA256
        if path.startswith(("backend/app/", "frontend/src/", "android-native/app/src/main/"))
    } | set(REVIEWED_CODE30_2_PRODUCTION_PATHS) | set(
        REVIEWED_CODE30_3_PRODUCTION_PATHS
    ) | set(REVIEWED_CODE30_4_PRODUCTION_PATHS)


def test_live_coordinated_identity_is_version_name_3_1_31_with_build_39() -> None:
    for path in (
        "android-native/app/build.gradle.kts",
        "backend/pyproject.toml",
        "backend/app/__init__.py",
        "frontend/package.json",
        "frontend/package-lock.json",
        "frontend/.env.example",
        "docker-compose.prod.yml",
    ):
        if (
            path in REVIEWED_CODE30_4_SHA256
            or path in REVIEWED_CODE30_3_SHA256
            or path in REVIEWED_CODE30_2_SHA256
        ):
            _assert_sha256(
                path,
                (ROOT / path).read_bytes(),
                _current_reviewed_sha256(path, REVIEWED_CODE30_2_SHA256[path]),
            )
        else:
            _assert_exact_text(path, _current(path), _identity_expected(path))

    build = _current("android-native/app/build.gradle.kts")
    assert build.count("versionCode = 39") == 1
    assert build.count('versionName = "3.1.31"') == 1

    env_replacements = (
        ("APP_VERSION=3.1.19", "APP_VERSION=3.1.30", 1),
        (
            "# immutable history. Signed Code 28 (3.1.18) failed its production image-identity\n"
            "# gate before maintenance or cutover and was never staged or offered. Code 29\n"
            "# (3.1.19) is an unsigned corrective candidate; it is not advertised unless every\n"
            "# gate in docs/CODE29_RELEASE_CANDIDATE.md passes for its exact source and artifacts.",
            "# immutable history. Original signed Code 29 (3.1.19) failed its production\n"
            "# installer lock gate before builds or maintenance and was never staged or offered.\n"
            "# Code 29 (3.1.20) was cancelled before build/signing after the POS notice defect.\n"
            "# Code30.2 (3.1.29, installation build 37) is immutable predecessor history.\n"
            "# Current Code30.3 (3.1.30, installation build 38) adds owner-approved stale\n"
            "# Gaming cleanup reconciliation without a broad clear-all operation.\n"
            "# It is not advertised unless every gate in docs/CODE30_3_PATCH_CANDIDATE.md\n"
            "# passes for its exact source and artifacts.",
            1,
        ),
    )
    expected_env = _replace_exact(_original(".env.production.example"), env_replacements)
    actual_env = _current(".env.production.example")
    _assert_sha256(
        ".env.production.example",
        actual_env.encode("utf-8"),
        _current_reviewed_sha256(
            ".env.production.example",
            REVIEWED_CODE30_2_SHA256[".env.production.example"],
        ),
    )
    assert "APP_VERSION=3.1.31" in actual_env
    assert "ANDROID_MIN_SUPPORTED_VERSION_CODE=8" in actual_env
    assert "ANDROID_LATEST_VERSION_CODE=8" in actual_env
    assert "CLIENT_COMPATIBILITY_POLICY_REVISION=1" in actual_env
    assert "APP_VERSION=3.1.30" in expected_env


def test_live_identity_fixtures_are_exact_counted_transformations() -> None:
    replacements = {
        "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt": (
            ("3.1.19", "3.1.30", 2),
            ("assertEquals(29, BuildConfig.VERSION_CODE)", "assertEquals(38, BuildConfig.VERSION_CODE)", 1),
            ("code 29 artifact", "code 30 point 3 artifact", 1),
        ),
        "backend/tests/unit/test_client_compatibility.py": (("3.1.19", "3.1.25", 1),),
        "backend/tests/unit/test_release_audit_fixes.py": (("3.1.19", "3.1.25", 1),),
        "backend/tests/unit/test_release_contracts.py": (("3.1.19", "3.1.30", 1),),
        "backend/tests/unit/test_remote_assistance_contract.py": (("3.1.19", "3.1.30", 4),),
        "backend/tests/unit/test_runtime_release_parity.py": (
            ("3.1.19", "3.1.25", 6),
            ("version_code=29,", "version_code=33,", 1),
        ),
        "tests/test_android_runtime_parity.py": (("3.1.19", "3.1.25", 2),),
        "tests/test_code26_physical_audit_lane.py": (("3.1.19", "3.1.21", 2),),
    }
    for path, path_replacements in replacements.items():
        if (
            path in REVIEWED_CODE30_4_SHA256
            or path in REVIEWED_CODE30_3_SHA256
            or path in REVIEWED_CODE30_2_SHA256
        ):
            _assert_sha256(
                path,
                (ROOT / path).read_bytes(),
                _current_reviewed_sha256(path, REVIEWED_CODE30_2_SHA256[path]),
            )
            continue
        expected = _replace_exact(_original(path), path_replacements)
        _assert_exact_text(path, _current(path), expected)


def test_original_release_name_tests_remain_and_patch_cases_are_exactly_added() -> None:
    path = "tests/test_android_release_version.py"
    anchor = "    def test_tag_must_match_version_name_exactly(self) -> None:\n"
    addition = (
        "    def test_code29_installer_correction_accepts_v3_1_20(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"29\", version_name='\"3.1.20\"')\n"
        "        )\n\n"
        "        validate_tag(\"v3.1.20\", version)\n"
        "        validate_built_metadata(\n"
        "            self.write_metadata(version_code=29, version_name=\"3.1.20\"), version\n"
        "        )\n\n"
        "        self.assertEqual(AndroidVersion(code=29, name=\"3.1.20\"), version)\n\n"
        "    def test_code29_installer_correction_rejects_original_package_identity(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"29\", version_name='\"3.1.20\"')\n"
        "        )\n\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"expected 'v3.1.20'\"):\n"
        "            validate_tag(\"v3.1.19\", version)\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"does not match\"):\n"
        "            validate_built_metadata(\n"
        "                self.write_metadata(version_code=29, version_name=\"3.1.19\"),\n"
        "                version,\n"
            "            )\n\n"
    )
    current_addition = (
        "    def test_code29_ui_patch_accepts_v3_1_21(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"29\", version_name='\"3.1.21\"')\n"
        "        )\n\n"
        "        validate_tag(\"v3.1.21\", version)\n"
        "        validate_built_metadata(\n"
        "            self.write_metadata(version_code=29, version_name=\"3.1.21\"), version\n"
        "        )\n\n"
        "        self.assertEqual(AndroidVersion(code=29, name=\"3.1.21\"), version)\n\n"
        "    def test_code29_ui_patch_rejects_prior_package_identity(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"29\", version_name='\"3.1.21\"')\n"
        "        )\n\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"expected 'v3.1.21'\"):\n"
        "            validate_tag(\"v3.1.20\", version)\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"does not match\"):\n"
        "            validate_built_metadata(\n"
        "                self.write_metadata(version_code=29, version_name=\"3.1.20\"),\n"
        "                version,\n"
        "            )\n\n"
    )
    packaging_addition = (
        "    def test_code29_point1_pricing_patch_accepts_v3_1_22_build30(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"30\", version_name='\"3.1.22\"')\n"
        "        )\n\n"
        "        validate_tag(\"v3.1.22\", version)\n"
        "        validate_built_metadata(\n"
        "            self.write_metadata(version_code=30, version_name=\"3.1.22\"), version\n"
        "        )\n\n"
        "        self.assertEqual(AndroidVersion(code=30, name=\"3.1.22\"), version)\n\n"
        "    def test_code29_point1_pricing_patch_rejects_build29_identity(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"30\", version_name='\"3.1.22\"')\n"
        "        )\n\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"expected 'v3.1.22'\"):\n"
        "            validate_tag(\"v3.1.21\", version)\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"does not match\"):\n"
        "            validate_built_metadata(\n"
        "                self.write_metadata(version_code=29, version_name=\"3.1.21\"),\n"
        "                version,\n"
        "            )\n\n"
    )
    code29_2_addition = (
        "    def test_code29_point2_playtime_draft_accepts_v3_1_23_build31(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"31\", version_name='\"3.1.23\"')\n"
        "        )\n\n"
        "        validate_tag(\"v3.1.23\", version)\n"
        "        validate_built_metadata(\n"
        "            self.write_metadata(version_code=31, version_name=\"3.1.23\"), version\n"
        "        )\n\n"
        "        self.assertEqual(AndroidVersion(code=31, name=\"3.1.23\"), version)\n\n"
        "    def test_code29_point2_playtime_draft_rejects_build30_identity(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"31\", version_name='\"3.1.23\"')\n"
        "        )\n\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"expected 'v3.1.23'\"):\n"
        "            validate_tag(\"v3.1.22\", version)\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"does not match\"):\n"
        "            validate_built_metadata(\n"
        "                self.write_metadata(version_code=30, version_name=\"3.1.22\"),\n"
        "                version,\n"
        "            )\n\n"
    )
    code30_addition = (
        "    def test_code30_customer_lookup_accepts_v3_1_25_build33(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"33\", version_name='\"3.1.25\"')\n"
        "        )\n\n"
        "        validate_tag(\"v3.1.25\", version)\n"
        "        validate_built_metadata(\n"
        "            self.write_metadata(version_code=33, version_name=\"3.1.25\"), version\n"
        "        )\n\n"
        "        self.assertEqual(AndroidVersion(code=33, name=\"3.1.25\"), version)\n\n"
        "    def test_code30_customer_lookup_rejects_code29_point2_identity(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"33\", version_name='\"3.1.25\"')\n"
        "        )\n\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"expected 'v3.1.25'\"):\n"
        "            validate_tag(\"v3.1.23\", version)\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"does not match\"):\n"
        "            validate_built_metadata(\n"
        "                self.write_metadata(version_code=31, version_name=\"3.1.23\"),\n"
        "                version,\n"
        "            )\n\n"
    )
    code30_2_addition = (
        "    def test_code30_point2_finance_patch_accepts_v3_1_29_build37(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"37\", version_name='\"3.1.29\"')\n"
        "        )\n\n"
        "        validate_tag(\"v3.1.29\", version)\n"
        "        validate_built_metadata(\n"
        "            self.write_metadata(version_code=37, version_name=\"3.1.29\"), version\n"
        "        )\n\n"
        "        self.assertEqual(AndroidVersion(code=37, name=\"3.1.29\"), version)\n\n"
        "    def test_code30_point2_finance_patch_rejects_code30_point1_identity(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"37\", version_name='\"3.1.29\"')\n"
        "        )\n\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"expected 'v3.1.29'\"):\n"
        "            validate_tag(\"v3.1.28\", version)\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"does not match\"):\n"
        "            validate_built_metadata(\n"
        "                self.write_metadata(version_code=36, version_name=\"3.1.28\"),\n"
        "                version,\n"
        "            )\n\n"
    )
    code30_3_addition = (
        "    def test_code30_point3_cleanup_patch_accepts_v3_1_30_build38(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"38\", version_name='\"3.1.30\"')\n"
        "        )\n\n"
        "        validate_tag(\"v3.1.30\", version)\n"
        "        validate_built_metadata(\n"
        "            self.write_metadata(version_code=38, version_name=\"3.1.30\"), version\n"
        "        )\n\n"
        "        self.assertEqual(AndroidVersion(code=38, name=\"3.1.30\"), version)\n\n"
        "    def test_code30_point3_cleanup_patch_rejects_code30_point2_identity(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"38\", version_name='\"3.1.30\"')\n"
        "        )\n\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"expected 'v3.1.30'\"):\n"
        "            validate_tag(\"v3.1.29\", version)\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"does not match\"):\n"
        "            validate_built_metadata(\n"
        "                self.write_metadata(version_code=37, version_name=\"3.1.29\"),\n"
        "                version,\n"
        "            )\n\n"
    )
    expected = _replace_exact(
        _original(path),
        (
            (
                anchor,
                addition
                + current_addition
                + packaging_addition
                + code29_2_addition
                + code30_addition
                + code30_2_addition
                + code30_3_addition
                + anchor,
                1,
            ),
        ),
    )
    _assert_exact_text(path, _current(path), expected)
    assert "test_code29_image_identity_correction_accepts_v3_1_19" in expected


def test_installer_verifier_and_live_workflows_are_exact() -> None:
    installer_replacements = (
        (
            "lock_fd_metadata=$(stat -Lc '%u:%g:%a:%h:%F:%d:%i' \"/proc/$$/fd/9\")\n"
            'expected_lock_fd_metadata="0:0:600:1:regular file:${DCOMPANY_PRODUCTION_INSTALL_LOCK_ID}"',
            "# GNU stat's raw mode is independent of file contents and localized type names;\n"
            "# 8180 is the exact Linux mode for a regular file with permissions 0600.\n"
            "lock_fd_metadata=$(stat -Lc '%u:%g:%a:%h:%f:%d:%i' \"/proc/$$/fd/9\")\n"
            'expected_lock_fd_metadata="0:0:600:1:8180:${DCOMPANY_PRODUCTION_INSTALL_LOCK_ID}"',
            1,
        ),
    )
    assert hashlib.sha256(
        (ROOT / "scripts/verify_code26_regression_freeze.py").read_bytes()
    ).hexdigest() == CODE30_4_FREEZE_SCRIPT_SHA256
    assert hashlib.sha256(
        _historical_bytes(
            CODE30_3_BASE, "scripts/verify_code26_regression_freeze.py"
        )
    ).hexdigest() == CODE30_3_FREEZE_SCRIPT_SHA256
    assert hashlib.sha256(
        _historical_bytes(
            CODE30_2_BASE, "scripts/verify_code26_regression_freeze.py"
        )
    ).hexdigest() == CODE30_2_FREEZE_SCRIPT_SHA256
    installer_path = "infra/scripts/install-on-vm.sh"
    current_installer = _current(installer_path)
    _assert_sha256(
        installer_path,
        current_installer.encode("utf-8"),
        _current_reviewed_sha256(
            installer_path, REVIEWED_CODE30_2_SHA256[installer_path]
        ),
    )
    for unsafe, corrected, expected_count in installer_replacements:
        assert unsafe not in current_installer
        assert current_installer.count(corrected) == expected_count
    assert hashlib.sha256(
        (ROOT / "tests/verify_production_installer_lock_metadata.py").read_bytes()
    ).hexdigest() == LOCK_VERIFIER_SHA256

    for path in (".github/workflows/ci.yml", ".github/workflows/release.yml"):
        current = _current(path)
        _assert_sha256(
            path,
            current.encode("utf-8"),
            REVIEWED_CODE30_2_SHA256[path],
        )
        assert current.count(_workflow_step()) == 1
        assert current.count(CADDY_SHA256) == 1
        assert "continue-on-error" not in _workflow_step()

    action_path = ".github/actions/scan-production-images/action.yml"
    current_action = _current(action_path)
    _assert_sha256(
        action_path,
        current_action.encode("utf-8"),
        REVIEWED_CODE30_2_SHA256[action_path],
    )
    assert "verify-zlib-vex.py" in current_action
    assert current_action.count("-zlib.openvex.json") >= 15
    assert current_action.count("vex: ${{ runner.temp }}/container-security/") == 5


def test_physical_lane_accepts_only_the_exact_390_step_code30_point2_plan() -> None:
    plan_path = "android-native/audit-driver/plans/code26-gaming-finance-physical.json"
    corrected_plan = json.loads(_current(plan_path))
    assert hashlib.sha256((ROOT / plan_path).read_bytes()).hexdigest() == (
        _current_reviewed_sha256(plan_path, REVIEWED_CODE30_2_SHA256[plan_path])
    )
    assert len(corrected_plan["steps"]) == 390
    assert corrected_plan["expected_sessions"] == 16
    assert corrected_plan["name"] == (
        "Code30.3 3.1.30 current-tariff Gaming and Finance emulator acceptance"
    )
    rendered = json.dumps(corrected_plan["steps"], sort_keys=True, ensure_ascii=False)
    protected_categories = {
        "shift": ("Capture shift opening offline", "Request shift close"),
        "gaming": (
            "standard-single-session-30m",
            "standard-dual-session-30m",
            "standard-simdrive-session-15m",
            "vr-games-session-15m",
            "vr-racing-session-15m",
        ),
        "POS": ("request POS handoff", "continue verified bill"),
        "payment": ("submit cash once", "submit UPI once", "All sixteen receipts loaded"),
        "offline": ("Disconnect before opening shift", "Post-close reconnect"),
        "restart": ("Force-stop and restart actual ERP offline", "Restart after complete business day"),
    }
    for markers in protected_categories.values():
        assert all(marker in rendered for marker in markers)

    runner_path = "scripts/run_code26_physical_business_audit.sh"
    runner = _current(runner_path)
    assert hashlib.sha256((ROOT / runner_path).read_bytes()).hexdigest() == (
        _current_reviewed_sha256(runner_path, REVIEWED_CODE30_2_SHA256[runner_path])
    )
    for marker in (
        "cleanup() {",
        "trap cleanup EXIT INT TERM",
        'rm -f "$CREDENTIAL_FILE"',
        'dropdb --force --if-exists "$DB_NAME"',
    ):
        assert marker in runner


def test_freeze_extensions_and_historical_guards_are_exact() -> None:
    freeze_script_replacements = (
        (
            "import argparse\nimport json\n",
            "import argparse\nimport hashlib\nimport json\n",
            1,
        ),
        (
            'CODE25_BASE = "715ba8c2671c7fbceb362ab59052a8a128b67668"\n\n',
            'CODE25_BASE = "715ba8c2671c7fbceb362ab59052a8a128b67668"\n'
            'POS_NOTICE_TEST_PATH = (\n'
            '    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/"\n'
            '    "PosEmptyCatalogueUiTest.kt"\n'
            ')\n'
            'POS_NOTICE_TEST_SHA256 = (\n'
            '    "b56a28fda657fd04490b5dd055e24c524747dd3ba5b1b2e5f34af79f7e907ef5"\n'
            ')\n'
            'REFRESH_LOCKING_TEST_PATH = "backend/tests/integration/test_auth_refresh_locking.py"\n'
            'REFRESH_LOCKING_TEST_SHA256 = (\n'
            '    "719ba8b478d70e2a6a565f564b785df8e4ee7f89c0f92260891c4e7e808887cf"\n'
            ')\n\n',
            1,
        ),
        (
            '"""Fail closed when Code 26/27/28/29 weakens the proven Code 25 regression surface.',
            '"""Fail closed when Code 26 through current Code 29 weakens the proven Code 25 regression surface.',
            1,
        ),
        (
            "expiry repair. Code 28 hardens the release scanner path. Code 29 carries only\n"
            "the reviewed image-format and Android quantity corrections. None may delete,\n"
            "disable, reorder, or rewrite an existing",
            "expiry repair. Code 28 hardens the release scanner path. Original Code 29\n"
            "carries the reviewed image-format and Android quantity corrections; corrected\n"
            "Code 29 adds coordinated identity and the reviewed installer lock path. Current\n"
            "Code 29 adds only the reviewed POS-notice, inventory-layout, Web session, and\n"
            "refresh-lock corrections. None may delete, disable, reorder, or rewrite an existing",
            1,
        ),
        (
            'ALLOWED_PRODUCTION_PATHS = {\n    "backend/app/__init__.py",',
            'ALLOWED_PRODUCTION_PATHS = {\n'
            '    "backend/app/__init__.py",\n'
            '    "backend/app/services/auth/refresh_sessions.py",',
            1,
        ),
        (
            '    for current, baseline in (\n        ("3.1.19", "3.1.14"),',
            '    for current, baseline in (\n        ("3.1.21", "3.1.14"),\n'
            '        ("3.1.20", "3.1.14"),\n'
            '        ("3.1.19", "3.1.14"),',
            1,
        ),
        (
            "\n\ndef _missing_ordered_lines(baseline: str, candidate: str) -> list[str]:\n",
            "\n\ndef _normalise_pos_notice_dynamic_state_host(path: str, text: str) -> str:\n"
            "    if path != POS_NOTICE_TEST_PATH:\n"
            "        return text\n"
            "    if hashlib.sha256(text.encode(\"utf-8\")).hexdigest() != POS_NOTICE_TEST_SHA256:\n"
            "        return text\n"
            "    replacements = (\n"
            "        (\"                        state = state.value,\", \"                        state = state,\"),\n"
            "        (\n"
            "            \"                        onDismissNotice = onDismissNotice,\",\n"
            "            \"                        onDismissNotice = {},\",\n"
            "        ),\n"
            "    )\n"
            "    if any(text.count(current) != 1 for current, _ in replacements):\n"
            "        return text\n"
            "    for current, baseline in replacements:\n"
            "        text = text.replace(current, baseline)\n"
            "    return text\n"
            "\n\ndef _missing_ordered_lines(baseline: str, candidate: str) -> list[str]:\n",
            1,
        ),
        (
            "        candidate_normalised = _normalise_release_identity(path, candidate_text)\n"
            "        candidate_normalised = _normalise_audit_reader_locator(path, candidate_normalised)\n",
            "        candidate_normalised = _normalise_release_identity(path, candidate_text)\n"
            "        candidate_normalised = _normalise_pos_notice_dynamic_state_host(\n"
            "            path, candidate_normalised\n"
            "        )\n"
            "        candidate_normalised = _normalise_audit_reader_locator(path, candidate_normalised)\n",
            1,
        ),
        (
            "\n    changed = set(\n",
            "\n    refresh_locking_test = root / REFRESH_LOCKING_TEST_PATH\n"
            "    if not refresh_locking_test.is_file():\n"
            "        errors.append(f\"reviewed test file was removed: {REFRESH_LOCKING_TEST_PATH}\")\n"
            "    elif hashlib.sha256(refresh_locking_test.read_bytes()).hexdigest() != (\n"
            "        REFRESH_LOCKING_TEST_SHA256\n"
            "    ):\n"
            "        errors.append(\n"
            "            f\"reviewed test file differs from its approved bytes: {REFRESH_LOCKING_TEST_PATH}\"\n"
            "        )\n"
            "\n    changed = set(\n",
            1,
        ),
    )
    expected_script = _replace_exact(
        _original("scripts/verify_code26_regression_freeze.py"),
        freeze_script_replacements,
    )
    pricing_test_hashes = (
        'REVIEWED_PRICING_TEST_SHA256 = {\n'
        '    "backend/tests/unit/test_gaming_tariff_catalog.py": (\n'
        '        "26ab1a7adb35ee4110b0357854c07f363c42cc191845d3d9cc10770f98836699"\n'
        '    ),\n'
        '    "backend/tests/integration/test_gaming_tariff_pos_e2e.py": (\n'
        '        "98838882791dc32c9fac9a42932b3743611962f02a8e0b32cb0e5c427e101f1f"\n'
        '    ),\n'
        '    "backend/tests/integration/test_gaming_phase2_contracts.py": (\n'
        '        "7d37f3fb0ae085fb61e92a24ffbb5415cca20dc4b26ae9f667ceece3be48d4db"\n'
        '    ),\n'
        '    "backend/tests/integration/test_captured_shift_opening.py": (\n'
        '        "c2723589654819903ed6d5d3ae7d362e6389a18c9b4ea42becee947279ddf04f"\n'
        '    ),\n'
        '    "backend/tests/integration/test_gaming_co_owner_stop.py": (\n'
        '        "bf30afe6916d6ad8c3547ae5de9ba94e6a1f4bc9f354d7743783d4d39e8b3d80"\n'
        '    ),\n'
        '    "frontend/src/modules/gaming/gaming-tariff.test.ts": (\n'
        '        "03b1f1ca87c7e04c3d6e48470403ac383080384d1bc69a0282e3f9e4a0771e50"\n'
        '    ),\n'
        '    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt": (\n'
        '        "fe8f3202225b2ee0387c26bca1ffd3b6477434f17336b5cca7aaf5ed771893ac"\n'
        '    ),\n'
        '    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingStationPresentationTest.kt": (\n'
        '        "a9db63a7cc106918c04929b3b0c02dc6fce0b9c52695a838adce83f3125dd547"\n'
        '    ),\n'
        '    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModelRecoveryTest.kt": (\n'
        '        "5a90ad73d21081150ab012ea33774457164accfa413ef3e6da5fe0be65ce0314"\n'
        '    ),\n'
        '    "android-native/app/src/test/java/cloud/dcompany/erp/core/sync/GamingPackageExtensionReplayPolicyTest.kt": (\n'
        '        "6292de870fa7c02ecb99bfe2d81e8e99a1561b3faee8c8f5807409cf674208f1"\n'
        '    ),\n'
        '}\n'
    )
    pricing_freeze_script_replacements = (
        (
            "Code 29 adds only the reviewed POS-notice, inventory-layout, Web session, and\n"
            "refresh-lock corrections. None may delete, disable, reorder, or rewrite an existing\n"
            "test outside the exact fixture-only normalization below.",
            "Code 29 adds only the reviewed POS-notice, inventory-layout, Web session,\n"
            "refresh-lock, and owner-approved pricing-card corrections. None may delete,\n"
            "disable, reorder, or rewrite an existing test outside the exact fixture-only\n"
            "normalization or exact reviewed-test hashes below.",
            1,
        ),
        (
            'REFRESH_LOCKING_TEST_SHA256 = (\n'
            '    "719ba8b478d70e2a6a565f564b785df8e4ee7f89c0f92260891c4e7e808887cf"\n'
            ')\n',
            'REFRESH_LOCKING_TEST_SHA256 = (\n'
            '    "719ba8b478d70e2a6a565f564b785df8e4ee7f89c0f92260891c4e7e808887cf"\n'
            ')\n' + pricing_test_hashes,
            1,
        ),
        (
            "\nALLOWED_PRODUCTION_PATHS = {\n",
            "\nREVIEWED_PRICING_PRODUCTION_PATHS = frozenset({\n"
            '    "backend/app/api/v1/gaming/router.py",\n'
            '    "backend/app/services/gaming/tariff_catalog.py",\n'
            '    "frontend/src/modules/gaming/GamingScreen.tsx",\n'
            '    "frontend/src/modules/gaming/gaming-tariff.test.ts",\n'
            "})\n\n"
            "ALLOWED_PRODUCTION_PATHS = {\n",
            1,
        ),
        (
            "} | REVIEWED_WEB_AUTH_PATHS\n\nPRODUCTION_PREFIXES",
            "} | REVIEWED_WEB_AUTH_PATHS | REVIEWED_PRICING_PRODUCTION_PATHS\n\n"
            "PRODUCTION_PREFIXES",
            1,
        ),
        (
            '        \'    assert {path for path in report.changed_production_files if path.startswith("frontend/src/")} == REVIEWED_WEB_AUTH_PATHS\',\n',
            '        \'    assert {path for path in report.changed_production_files if path.startswith("frontend/src/")} == REVIEWED_WEB_AUTH_PATHS | {"frontend/src/modules/gaming/GamingScreen.tsx", "frontend/src/modules/gaming/gaming-tariff.test.ts"}\',\n',
            1,
        ),
        (
            "        if _missing_ordered_lines(baseline_normalised, candidate_normalised):\n"
            '            errors.append(f"baseline test was rewritten or reordered: {path}")\n',
            "        reviewed_pricing_sha256 = REVIEWED_PRICING_TEST_SHA256.get(path)\n"
            "        reviewed_pricing_bytes_match = reviewed_pricing_sha256 is not None and (\n"
            "            hashlib.sha256(candidate_path.read_bytes()).hexdigest()\n"
            "            == reviewed_pricing_sha256\n"
            "        )\n"
            "        if reviewed_pricing_sha256 is not None and not reviewed_pricing_bytes_match:\n"
            '            errors.append(f"reviewed pricing test differs from its approved bytes: {path}")\n'
            "        if not reviewed_pricing_bytes_match and _missing_ordered_lines(\n"
            "            baseline_normalised, candidate_normalised\n"
            "        ):\n"
            '            errors.append(f"baseline test was rewritten or reordered: {path}")\n',
            1,
        ),
    )
    expected_script = _replace_exact(expected_script, pricing_freeze_script_replacements)
    packaging_test_hashes = (
        'REVIEWED_PACKAGING_TEST_SHA256 = {\n'
        '    "frontend/src/modules/settings/tabs/SystemHealthTab.test.tsx": (\n'
        '        "0c6e16e7c782ef96463685ea8509b7f0a61070a5e4305c1540188dc7ac7d2724"\n'
        '    ),\n'
        '}\n'
    )
    packaging_ui_paths = (
        "REVIEWED_PACKAGING_UI_PATHS = frozenset({\n"
        '    "frontend/src/modules/settings/tabs/DevicesUpdatesTab.tsx",\n'
        '    "frontend/src/modules/settings/tabs/SystemHealthTab.tsx",\n'
        '    "frontend/src/modules/settings/tabs/SystemHealthTab.test.tsx",\n'
        '    "frontend/src/modules/remote-assistance/DeviceListPanel.tsx",\n'
        '    "frontend/src/modules/remote-assistance/DeviceDetailPanel.tsx",\n'
        "})\n\n"
    )
    packaging_freeze_script_replacements = (
        (
            "Code 29 adds only the reviewed POS-notice, inventory-layout, Web session,\n"
            "refresh-lock, and owner-approved pricing-card corrections. None may delete,\n"
            "disable, reorder, or rewrite an existing test outside the exact fixture-only\n"
            "normalization or exact reviewed-test hashes below.",
            "Code 29 adds only the reviewed POS-notice, inventory-layout, Web session,\n"
            "refresh-lock, owner-approved pricing-card, and Code 29.1 packaging-label\n"
            "corrections. None may delete, disable, reorder, or rewrite an existing test\n"
            "outside the exact fixture-only normalization or exact reviewed-test hashes below.",
            1,
        ),
        (pricing_test_hashes, pricing_test_hashes + packaging_test_hashes, 1),
        (
            '        ("3.1.21", "3.1.14"),\n',
            '        ("3.1.23", "3.1.14"),\n'
            '        ("3.1.22", "3.1.14"),\n'
            '        ("3.1.21", "3.1.14"),\n',
            1,
        ),
        (
            'r"version_code\\s*=\\s*(?:26|27|28|29)\\b"',
            'r"version_code\\s*=\\s*(?:26|27|28|29|30|31)\\b"',
            1,
        ),
        (
            'r"assertEquals\\((?:26|27|28|29),\\s*BuildConfig\\.VERSION_CODE\\)"',
            'r"assertEquals\\((?:26|27|28|29|30|31),\\s*BuildConfig\\.VERSION_CODE\\)"',
            1,
        ),
        (
            "\nALLOWED_PRODUCTION_PATHS = {\n",
            "\n" + packaging_ui_paths + "ALLOWED_PRODUCTION_PATHS = {\n",
            1,
        ),
        (
            "} | REVIEWED_WEB_AUTH_PATHS | REVIEWED_PRICING_PRODUCTION_PATHS\n\n"
            "PRODUCTION_PREFIXES",
            "} | REVIEWED_WEB_AUTH_PATHS | REVIEWED_PRICING_PRODUCTION_PATHS | "
            "REVIEWED_PACKAGING_UI_PATHS\n\nPRODUCTION_PREFIXES",
            1,
        ),
        (
            '        \'    assert {path for path in report.changed_production_files if path.startswith("frontend/src/")} == REVIEWED_WEB_AUTH_PATHS | {"frontend/src/modules/gaming/GamingScreen.tsx", "frontend/src/modules/gaming/gaming-tariff.test.ts"}\',\n',
            '        \'    assert {path for path in report.changed_production_files if path.startswith("frontend/src/")} == REVIEWED_WEB_AUTH_PATHS | {"frontend/src/modules/gaming/GamingScreen.tsx", "frontend/src/modules/gaming/gaming-tariff.test.ts"} | REVIEWED_PACKAGING_UI_PATHS\',\n',
            1,
        ),
        (
            "        reviewed_pricing_sha256 = REVIEWED_PRICING_TEST_SHA256.get(path)\n"
            "        reviewed_pricing_bytes_match = reviewed_pricing_sha256 is not None and (\n"
            "            hashlib.sha256(candidate_path.read_bytes()).hexdigest()\n"
            "            == reviewed_pricing_sha256\n"
            "        )\n"
            "        if reviewed_pricing_sha256 is not None and not reviewed_pricing_bytes_match:\n"
            '            errors.append(f"reviewed pricing test differs from its approved bytes: {path}")\n'
            "        if not reviewed_pricing_bytes_match and _missing_ordered_lines(\n"
            "            baseline_normalised, candidate_normalised\n"
            "        ):\n",
            "        reviewed_test_sha256 = (\n"
            "            REVIEWED_PRICING_TEST_SHA256.get(path)\n"
            "            or REVIEWED_PACKAGING_TEST_SHA256.get(path)\n"
            "        )\n"
            "        reviewed_test_bytes_match = reviewed_test_sha256 is not None and (\n"
            "            hashlib.sha256(candidate_path.read_bytes()).hexdigest()\n"
            "            == reviewed_test_sha256\n"
            "        )\n"
            "        if reviewed_test_sha256 is not None and not reviewed_test_bytes_match:\n"
            '            errors.append(f"reviewed test differs from its approved bytes: {path}")\n'
            "        if not reviewed_test_bytes_match and _missing_ordered_lines(\n"
            "            baseline_normalised, candidate_normalised\n"
            "        ):\n",
            1,
        ),
    )
    expected_script = _replace_exact(expected_script, packaging_freeze_script_replacements)
    code29_2_test_paths = (
        "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt",
        "android-native/app/src/test/java/cloud/dcompany/erp/ui/GamingCentreFeatureProfileTest.kt",
        "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/finance/FinancePresentationPolicyTest.kt",
        "backend/tests/integration/test_points_reservation_balance.py",
        "frontend/src/lib/product-profile.test.ts",
    )
    code29_2_test_hashes = "REVIEWED_CODE29_2_TEST_SHA256 = {\n" + "".join(
        f'    "{path}": "{REVIEWED_CODE29_2_FEATURE_SHA256[path]}",\n'
        for path in code29_2_test_paths
    ) + "}\n"
    code29_2_production_paths = (
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
    )
    code29_2_production_set = "REVIEWED_CODE29_2_PRODUCTION_PATHS = frozenset({\n" + "".join(
        f'    "{path}",\n' for path in code29_2_production_paths
    ) + "})\n\n"
    expected_script = _replace_exact(
        expected_script,
        (
            (
                "refresh-lock, owner-approved pricing-card, and Code 29.1 packaging-label\n"
                "corrections. None may delete, disable, reorder, or rewrite an existing test\n",
                "refresh-lock, owner-approved pricing-card, Code 29.1 packaging-label, and the\n"
                "reviewed Code29.2 customer-playtime draft. None may delete, disable, reorder, or rewrite an existing test\n",
                1,
            ),
            (packaging_test_hashes, packaging_test_hashes + code29_2_test_hashes, 1),
            (packaging_ui_paths, packaging_ui_paths + code29_2_production_set, 1),
            (
                "} | REVIEWED_WEB_AUTH_PATHS | REVIEWED_PRICING_PRODUCTION_PATHS | "
                "REVIEWED_PACKAGING_UI_PATHS\n\nPRODUCTION_PREFIXES",
                "} | REVIEWED_WEB_AUTH_PATHS | REVIEWED_PRICING_PRODUCTION_PATHS | "
                "REVIEWED_PACKAGING_UI_PATHS | REVIEWED_CODE29_2_PRODUCTION_PATHS\n\n"
                "PRODUCTION_PREFIXES",
                1,
            ),
            (
                "        reviewed_test_sha256 = (\n"
                "            REVIEWED_PRICING_TEST_SHA256.get(path)\n",
                "        reviewed_test_sha256 = (\n"
                "            REVIEWED_CODE29_2_TEST_SHA256.get(path)\n"
                "            or REVIEWED_PRICING_TEST_SHA256.get(path)\n",
                1,
            ),
        ),
    )
    code30_test_paths = (
        "android-native/app/src/androidTest/java/cloud/dcompany/erp/core/db/MigrationTest.kt",
        "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingDialogUiTest.kt",
        "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingApiContractTest.kt",
    )
    code30_test_hashes = "REVIEWED_CODE30_TEST_SHA256 = {\n" + "".join(
        f'    "{path}": "{REVIEWED_CODE30_FEATURE_SHA256[path]}",\n'
        for path in code30_test_paths
    ) + "}\n"
    code30_production_paths = (
        "android-native/app/src/main/java/cloud/dcompany/erp/core/db/CustomerDao.kt",
        "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingCustomerSearch.kt",
        "frontend/src/modules/gaming/GamingCustomerPicker.test.ts",
        "frontend/src/modules/gaming/GamingCustomerPicker.tsx",
    )
    code30_production_set = "REVIEWED_CODE30_PRODUCTION_PATHS = frozenset({\n" + "".join(
        f'    "{path}",\n' for path in code30_production_paths
    ) + "})\n\n"
    expected_script = _replace_exact(
        expected_script,
        (
            (
                "reviewed Code29.2 customer-playtime draft. None may delete, disable, reorder, or rewrite an existing test\n",
                "reviewed Code29.2 customer-playtime draft. Code30 adds the reviewed saved-customer\n"
                "lookup. None may delete, disable, reorder, or rewrite an existing test\n",
                1,
            ),
            (code29_2_test_hashes, code29_2_test_hashes + code30_test_hashes, 1),
            (
                code29_2_production_set,
                code29_2_production_set[:-1] + code30_production_set,
                1,
            ),
            (
                "REVIEWED_PACKAGING_UI_PATHS | REVIEWED_CODE29_2_PRODUCTION_PATHS\n\n"
                "PRODUCTION_PREFIXES",
                "REVIEWED_PACKAGING_UI_PATHS | REVIEWED_CODE29_2_PRODUCTION_PATHS | "
                "REVIEWED_CODE30_PRODUCTION_PATHS\n\nPRODUCTION_PREFIXES",
                1,
            ),
            (
                '        ("3.1.23", "3.1.14"),\n',
                '        ("3.1.25", "3.1.14"),\n'
                '        ("3.1.24", "3.1.14"),\n'
                '        ("3.1.23", "3.1.14"),\n',
                1,
            ),
            (
                '        ("Code 29", "Code 25"),\n',
                '        ("Code 30", "Code 25"),\n'
                '        ("code 30", "code 25"),\n'
                '        ("CODE30", "CODE25"),\n'
                '        ("code30", "code25"),\n'
                '        ("Code 29", "Code 25"),\n',
                1,
            ),
            (
                'r"version_code\\s*=\\s*(?:26|27|28|29|30|31)\\b"',
                'r"version_code\\s*=\\s*(?:26|27|28|29|30|31|32|33)\\b"',
                1,
            ),
            (
                'r"assertEquals\\((?:26|27|28|29|30|31),\\s*BuildConfig\\.VERSION_CODE\\)"',
                'r"assertEquals\\((?:26|27|28|29|30|31|32|33),\\s*BuildConfig\\.VERSION_CODE\\)"',
                1,
            ),
            (
                "        reviewed_test_sha256 = (\n"
                "            REVIEWED_CODE29_2_TEST_SHA256.get(path)\n",
                "        reviewed_test_sha256 = (\n"
                "            REVIEWED_CODE30_TEST_SHA256.get(path)\n"
                "            or REVIEWED_CODE29_2_TEST_SHA256.get(path)\n",
                1,
            ),
        ),
    )
    code30_1_feature_hashes = "REVIEWED_CODE30_1_FEATURE_SHA256 = {\n" + "".join(
        f'    "{path}": "{expected_sha256}",\n'
        for path, expected_sha256 in REVIEWED_CODE30_1_FEATURE_SHA256.items()
    ) + "}\n"
    code30_1_production_set = (
        "REVIEWED_CODE30_1_PRODUCTION_PATHS = frozenset({\n"
        "    path\n"
        "    for path in REVIEWED_CODE30_1_FEATURE_SHA256\n"
        '    if path.startswith("android-native/app/src/main/")\n'
        "})\n"
    )
    code30_1_exact_loop = (
        "    for path, expected_sha256 in REVIEWED_CODE30_1_FEATURE_SHA256.items():\n"
        "        candidate_path = root / path\n"
        "        if not candidate_path.is_file():\n"
        '            errors.append(f"reviewed Code30.1 file was removed: {path}")\n'
        "        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != expected_sha256:\n"
        '            errors.append(f"reviewed Code30.1 file differs from its approved bytes: {path}")\n'
        "\n"
    )
    expected_script = _replace_exact(
        expected_script,
        (
            (
                "reviewed Code29.2 customer-playtime draft. Code30 adds the reviewed saved-customer\n"
                "lookup. None may delete, disable, reorder, or rewrite an existing test\n",
                "reviewed Code29.2 customer-playtime draft. Code30 adds the reviewed saved-customer\n"
                "lookup. Code30.1 adds only the independently reviewed future-clock Stop recovery\n"
                "and rejected-session attention correction. None may delete, disable, reorder, or rewrite an existing test\n",
                1,
            ),
            (code30_test_hashes, code30_test_hashes + code30_1_feature_hashes, 1),
            (
                code30_production_set,
                code30_production_set[:-1] + code30_1_production_set + "\n",
                1,
            ),
            (
                "REVIEWED_CODE29_2_PRODUCTION_PATHS | REVIEWED_CODE30_PRODUCTION_PATHS\n\n"
                "PRODUCTION_PREFIXES",
                "REVIEWED_CODE29_2_PRODUCTION_PATHS | REVIEWED_CODE30_PRODUCTION_PATHS | "
                "REVIEWED_CODE30_1_PRODUCTION_PATHS\n\nPRODUCTION_PREFIXES",
                1,
            ),
            (
                '        ("3.1.25", "3.1.14"),\n',
                '        ("3.1.26", "3.1.14"),\n'
                '        ("3.1.25", "3.1.14"),\n',
                1,
            ),
            (
                '        ("Code 30", "Code 25"),\n',
                '        ("Code 30.1", "Code 25"),\n'
                '        ("code 30.1", "code 25"),\n'
                '        ("CODE30.1", "CODE25"),\n'
                '        ("code30.1", "code25"),\n'
                '        ("Code 30 point 1", "Code 25"),\n'
                '        ("code 30 point 1", "code 25"),\n'
                '        ("CODE30_POINT_1", "CODE25"),\n'
                '        ("code30_point_1", "code25"),\n'
                '        ("Code 30", "Code 25"),\n',
                1,
            ),
            (
                'r"version_code\\s*=\\s*(?:26|27|28|29|30|31|32|33)\\b"',
                'r"version_code\\s*=\\s*(?:26|27|28|29|30|31|32|33|34)\\b"',
                1,
            ),
            (
                'r"assertEquals\\((?:26|27|28|29|30|31|32|33),\\s*BuildConfig\\.VERSION_CODE\\)"',
                'r"assertEquals\\((?:26|27|28|29|30|31|32|33|34),\\s*BuildConfig\\.VERSION_CODE\\)"',
                1,
            ),
            (
                "        reviewed_test_sha256 = (\n"
                "            REVIEWED_CODE30_TEST_SHA256.get(path)\n",
                "        reviewed_test_sha256 = (\n"
                "            REVIEWED_CODE30_1_FEATURE_SHA256.get(path)\n"
                "            or REVIEWED_CODE30_TEST_SHA256.get(path)\n",
                1,
            ),
            (
                "    refresh_locking_test = root / REFRESH_LOCKING_TEST_PATH\n",
                code30_1_exact_loop
                + "    refresh_locking_test = root / REFRESH_LOCKING_TEST_PATH\n",
                1,
            ),
        ),
    )
    code30_1_release_test_hashes = "REVIEWED_CODE30_1_RELEASE_TEST_SHA256 = {\n" + "".join(
        f'    "{path}": "{expected_sha256}",\n'
        for path, expected_sha256 in REVIEWED_CODE30_1_RELEASE_TEST_SHA256.items()
    ) + "}\n"
    code30_1_release_test_exact_loop = (
        "    for path, expected_sha256 in REVIEWED_CODE30_1_RELEASE_TEST_SHA256.items():\n"
        "        candidate_path = root / path\n"
        "        if not candidate_path.is_file():\n"
        '            errors.append(f"reviewed Code30.1 release test was removed: {path}")\n'
        "        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != expected_sha256:\n"
        "            errors.append(\n"
        '                f"reviewed Code30.1 release test differs from its approved bytes: {path}"\n'
        "            )\n"
        "\n"
    )
    expected_script = _replace_exact(
        expected_script,
        (
            (
                "and rejected-session attention correction. None may delete, disable, reorder, or rewrite an existing test\n",
                "and rejected-session attention correction. Its build-35 retry changes only coordinated\n"
                "identity and two exact stale release-test fixtures. None may delete, disable, reorder, or rewrite an existing test\n",
                1,
            ),
            (
                code30_1_feature_hashes,
                code30_1_feature_hashes + code30_1_release_test_hashes,
                1,
            ),
            (
                '        ("3.1.26", "3.1.14"),\n',
                '        ("3.1.27", "3.1.14"),\n'
                '        ("3.1.26", "3.1.14"),\n',
                1,
            ),
            (
                'r"version_code\\s*=\\s*(?:26|27|28|29|30|31|32|33|34)\\b"',
                'r"version_code\\s*=\\s*(?:26|27|28|29|30|31|32|33|34|35)\\b"',
                1,
            ),
            (
                'r"assertEquals\\((?:26|27|28|29|30|31|32|33|34),\\s*BuildConfig\\.VERSION_CODE\\)"',
                'r"assertEquals\\((?:26|27|28|29|30|31|32|33|34|35),\\s*BuildConfig\\.VERSION_CODE\\)"',
                1,
            ),
            (
                "        reviewed_test_sha256 = (\n"
                "            REVIEWED_CODE30_1_FEATURE_SHA256.get(path)\n",
                "        reviewed_test_sha256 = (\n"
                "            REVIEWED_CODE30_1_RELEASE_TEST_SHA256.get(path)\n"
                "            or REVIEWED_CODE30_1_FEATURE_SHA256.get(path)\n",
                1,
            ),
            (
                code30_1_exact_loop,
                code30_1_exact_loop + code30_1_release_test_exact_loop,
                1,
            ),
        ),
    )
    code30_1_deletion_replay_hashes = (
        "REVIEWED_CODE30_1_DELETION_REPLAY_SHA256 = {\n"
        + "".join(
            f"    {path!r}: {expected_sha256!r},\n"
            for path, expected_sha256 in sorted(
                REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.items()
            )
        )
        + "}\n"
    )
    code30_1_build36_release_test_hashes = (
        "REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256 = {\n"
        + "".join(
            f'    "{path}": "{expected_sha256}",\n'
            for path, expected_sha256 in REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.items()
        )
        + "}\n"
    )
    code30_1_deletion_replay_production_set = (
        "REVIEWED_CODE30_1_DELETION_REPLAY_PRODUCTION_PATHS = frozenset({\n"
        "    path\n"
        "    for path in REVIEWED_CODE30_1_DELETION_REPLAY_SHA256\n"
        '    if path.startswith(("backend/app/", "frontend/src/", "android-native/app/src/main/"))\n'
        "})\n"
    )
    code30_1_deletion_replay_exact_loop = (
        "    for path, expected_sha256 in REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.items():\n"
        "        candidate_path = root / path\n"
        "        if not candidate_path.is_file():\n"
        '            errors.append(f"reviewed Code30.1 deletion-replay file was removed: {path}")\n'
        "        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != expected_sha256:\n"
        "            errors.append(\n"
        '                f"reviewed Code30.1 deletion-replay file differs from its approved bytes: {path}"\n'
        "            )\n\n"
    )
    code30_1_build36_release_test_exact_loop = (
        "    for path, expected_sha256 in REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.items():\n"
        "        candidate_path = root / path\n"
        "        if not candidate_path.is_file():\n"
        '            errors.append(f"reviewed Code30.1 build-36 release test was removed: {path}")\n'
        "        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != expected_sha256:\n"
        "            errors.append(\n"
        '                f"reviewed Code30.1 build-36 release test differs from its approved bytes: {path}"\n'
        "            )\n\n"
    )
    expected_script = _replace_exact(
        expected_script,
        (
            (
                "lookup. Code30.1 adds only the independently reviewed future-clock Stop recovery\n"
                "and rejected-session attention correction. Its build-35 retry changes only coordinated\n"
                "identity and two exact stale release-test fixtures. None may delete, disable, reorder, or rewrite an existing test\n",
                "lookup. Code30.1 adds the independently reviewed future-clock Stop recovery,\n"
                "rejected-session attention correction, and customer-deletion replay fence. Its\n"
                "build-35 retry changed only coordinated identity and two stale release fixtures;\n"
                "build 36 carries the exact reviewed replay correction. None may delete, disable,\n"
                "reorder, or rewrite an existing test\n",
                1,
            ),
            (
                code30_1_feature_hashes,
                code30_1_feature_hashes + code30_1_deletion_replay_hashes,
                1,
            ),
            (
                code30_1_release_test_hashes,
                code30_1_release_test_hashes
                + "\n"
                + code30_1_build36_release_test_hashes,
                1,
            ),
            (
                code30_1_production_set,
                code30_1_production_set
                + code30_1_deletion_replay_production_set,
                1,
            ),
            (
                "REVIEWED_CODE30_PRODUCTION_PATHS | REVIEWED_CODE30_1_PRODUCTION_PATHS\n\n"
                "PRODUCTION_PREFIXES",
                "REVIEWED_CODE30_PRODUCTION_PATHS | REVIEWED_CODE30_1_PRODUCTION_PATHS | "
                "REVIEWED_CODE30_1_DELETION_REPLAY_PRODUCTION_PATHS\n\nPRODUCTION_PREFIXES",
                1,
            ),
            (
                '        ("3.1.27", "3.1.14"),\n',
                '        ("3.1.28", "3.1.14"),\n'
                '        ("3.1.27", "3.1.14"),\n',
                1,
            ),
            (
                "(?:26|27|28|29|30|31|32|33|34|35)",
                "(?:26|27|28|29|30|31|32|33|34|35|36)",
                2,
            ),
            (
                "        reviewed_test_sha256 = (\n"
                "            REVIEWED_CODE30_1_RELEASE_TEST_SHA256.get(path)\n",
                "        reviewed_test_sha256 = (\n"
                "            REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(path)\n"
                "            or REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.get(path)\n"
                "            or REVIEWED_CODE30_1_RELEASE_TEST_SHA256.get(path)\n",
                1,
            ),
            (
                "    for path, expected_sha256 in REVIEWED_CODE30_1_FEATURE_SHA256.items():\n"
                "        candidate_path = root / path\n"
                "        if not candidate_path.is_file():\n"
                '            errors.append(f"reviewed Code30.1 file was removed: {path}")\n'
                "        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != expected_sha256:\n"
                '            errors.append(f"reviewed Code30.1 file differs from its approved bytes: {path}")\n\n',
                "    for path, expected_sha256 in REVIEWED_CODE30_1_FEATURE_SHA256.items():\n"
                "        candidate_path = root / path\n"
                "        current_expected_sha256 = REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(\n"
                "            path, expected_sha256\n"
                "        )\n"
                "        if not candidate_path.is_file():\n"
                '            errors.append(f"reviewed Code30.1 file was removed: {path}")\n'
                "        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != current_expected_sha256:\n"
                '            errors.append(f"reviewed Code30.1 file differs from its approved bytes: {path}")\n\n'
                + code30_1_deletion_replay_exact_loop
                + code30_1_build36_release_test_exact_loop,
                1,
            ),
            (
                "    for path, expected_sha256 in REVIEWED_CODE30_1_RELEASE_TEST_SHA256.items():\n"
                "        candidate_path = root / path\n"
                "        if not candidate_path.is_file():\n",
                "    for path, expected_sha256 in REVIEWED_CODE30_1_RELEASE_TEST_SHA256.items():\n"
                "        candidate_path = root / path\n"
                "        current_expected_sha256 = REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.get(\n"
                "            path, expected_sha256\n"
                "        )\n"
                "        if not candidate_path.is_file():\n",
                1,
            ),
            (
                "        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != expected_sha256:\n"
                "            errors.append(\n"
                '                f"reviewed Code30.1 release test differs from its approved bytes: {path}"\n',
                "        elif hashlib.sha256(candidate_path.read_bytes()).hexdigest() != current_expected_sha256:\n"
                "            errors.append(\n"
                '                f"reviewed Code30.1 release test differs from its approved bytes: {path}"\n',
                1,
            ),
        ),
    )
    _assert_exact_text(
        "scripts/verify_code26_regression_freeze.py",
        _normalise_code30_2_freeze_script_to_code30_1(
            _historical_bytes(
                CODE30_2_BASE, "scripts/verify_code26_regression_freeze.py"
            ).decode("utf-8")
        ),
        expected_script,
    )

    freeze_test_path = "tests/test_code26_regression_freeze.py"
    freeze_test_anchor = "def test_pipeline_normalisation_requires_the_exact_counted_transform() -> None:\n"
    freeze_test_addition = (
        "def test_current_code29_patch_identity_normalises_directly_to_inherited_code25_baseline() -> None:\n"
        "    path = \"android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt\"\n"
        "    current = 'assertEquals(29, BuildConfig.VERSION_CODE)\\n\"3.1.21\"\\ncode 29 artifact\\n'\n"
        "    assert _normalise_release_identity(path, current) == (\n"
        "        'assertEquals(25, BuildConfig.VERSION_CODE)\\n\"3.1.14\"\\ncode 25 artifact\\n'\n"
        "    )\n\n\n"
    )
    normalizer_test_addition = (
        "def test_pos_notice_dynamic_state_host_normalises_only_the_approved_bytes() -> None:\n"
        "    path = (\n"
        "        \"android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/\"\n"
        "        \"PosEmptyCatalogueUiTest.kt\"\n"
        "    )\n"
        "    current = (ROOT / path).read_text(encoding=\"utf-8\")\n"
        "    baseline = subprocess.run(\n"
        "        [\"git\", \"show\", f\"{CODE25_BASE}:{path}\"],\n"
        "        cwd=ROOT,\n"
        "        check=True,\n"
        "        capture_output=True,\n"
        "        text=True,\n"
        "    ).stdout\n"
        "    normalised = _normalise_pos_notice_dynamic_state_host(path, current)\n\n"
        "    assert normalised != current\n"
        "    assert not _missing_ordered_lines(baseline, normalised)\n"
        "    assert _normalise_pos_notice_dynamic_state_host(\"tests/other.kt\", current) == current\n\n"
        "    for mutated in (\n"
        "        current + \"\\n\",\n"
        "        current.replace(\"                        state = state.value,\", \"\", 1),\n"
        "        current.replace(\n"
        "            \"                        state = state.value,\",\n"
        "            \"                        state = state.value,\\n                        state = state.value,\",\n"
        "            1,\n"
        "        ),\n"
        "    ):\n"
        "        assert _normalise_pos_notice_dynamic_state_host(path, mutated) == mutated\n\n"
        "    original_assertion = (\n"
        "        '        compose.onNodeWithText(\"CONTINUE TO PAYMENT\").assertDoesNotExist()'\n"
        "    )\n"
        "    assertion_mutation = current.replace(original_assertion, \"\", 1)\n"
        "    assert _normalise_pos_notice_dynamic_state_host(path, assertion_mutation) == assertion_mutation\n"
        "    assert _missing_ordered_lines(baseline, assertion_mutation) == [original_assertion]\n"
        "    assert _normalise_pos_notice_dynamic_state_host(path, baseline) == baseline\n\n\n"
    )
    expected_test = _replace_exact(
        _original(freeze_test_path),
        (
            ("from pathlib import Path\n", "from pathlib import Path\nimport subprocess\n", 1),
            (
                "    _normalise_release_identity,\n    _normalise_audit_reader_locator,\n",
                "    _normalise_release_identity,\n"
                "    _normalise_pos_notice_dynamic_state_host,\n"
                "    _normalise_audit_reader_locator,\n",
                1,
            ),
            ("3.1.19", "3.1.20", 1),
            (
                freeze_test_anchor,
                freeze_test_addition + normalizer_test_addition + freeze_test_anchor,
                1,
            ),
        ),
    )
    pricing_freeze_test = (
        '@pytest.mark.parametrize(("path", "expected_sha256"), REVIEWED_PRICING_TEST_SHA256.items())\n'
        "def test_pricing_test_rewrites_require_exact_reviewed_bytes(\n"
        "    path: str,\n"
        "    expected_sha256: str,\n"
        ") -> None:\n"
        "    current = (ROOT / path).read_bytes()\n"
        "    assert hashlib.sha256(current).hexdigest() == expected_sha256\n"
        '    assert hashlib.sha256(current + b"\\n").hexdigest() != expected_sha256\n\n\n'
    )
    expected_test = _replace_exact(
        expected_test,
        (
            (
                "from pathlib import Path\nimport subprocess\n",
                "import hashlib\nfrom pathlib import Path\nimport subprocess\n",
                1,
            ),
            (
                "    RegressionFreezeError,\n    REVIEWED_WEB_AUTH_PATHS,\n",
                "    RegressionFreezeError,\n"
                "    REVIEWED_PRICING_TEST_SHA256,\n"
                "    REVIEWED_WEB_AUTH_PATHS,\n",
                1,
            ),
            (
                "def test_audit_reader_migration_only_normalises_one_locator() -> None:\n",
                pricing_freeze_test
                + "def test_audit_reader_migration_only_normalises_one_locator() -> None:\n",
                1,
            ),
            (
                '    assert {path for path in report.changed_production_files if path.startswith("frontend/src/")} == REVIEWED_WEB_AUTH_PATHS\n',
                '    assert {path for path in report.changed_production_files if path.startswith("frontend/src/")} == REVIEWED_WEB_AUTH_PATHS | {\n'
                '        "frontend/src/modules/gaming/GamingScreen.tsx",\n'
                '        "frontend/src/modules/gaming/gaming-tariff.test.ts",\n'
                "    }\n",
                1,
            ),
        ),
    )
    packaging_identity_test = (
        "def test_code29_point1_build30_identity_normalises_to_inherited_code25_baseline() -> None:\n"
        "    path = \"android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt\"\n"
        "    current = 'assertEquals(30, BuildConfig.VERSION_CODE)\\n\"3.1.22\"\\ncode 29 artifact\\n'\n"
        "    assert _normalise_release_identity(path, current) == (\n"
        "        'assertEquals(25, BuildConfig.VERSION_CODE)\\n\"3.1.14\"\\ncode 25 artifact\\n'\n"
        "    )\n\n\n"
    )
    code29_2_identity_test = (
        "def test_code29_point2_build31_identity_normalises_to_inherited_code25_baseline() -> None:\n"
        "    path = \"android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt\"\n"
        "    current = 'assertEquals(31, BuildConfig.VERSION_CODE)\\n\"3.1.23\"\\ncode 29 artifact\\n'\n"
        "    assert _normalise_release_identity(path, current) == (\n"
        "        'assertEquals(25, BuildConfig.VERSION_CODE)\\n\"3.1.14\"\\ncode 25 artifact\\n'\n"
        "    )\n\n\n"
    )
    packaging_guard_test = (
        '@pytest.mark.parametrize(("path", "expected_sha256"), REVIEWED_PACKAGING_TEST_SHA256.items())\n'
        "def test_packaging_label_test_rewrite_requires_exact_reviewed_bytes(\n"
        "    path: str,\n"
        "    expected_sha256: str,\n"
        ") -> None:\n"
        "    current = (ROOT / path).read_bytes()\n"
        "    assert hashlib.sha256(current).hexdigest() == expected_sha256\n"
        '    assert hashlib.sha256(current + b"\\n").hexdigest() != expected_sha256\n\n\n'
    )
    expected_test = _replace_exact(
        expected_test,
        (
            (
                "    RegressionFreezeError,\n    REVIEWED_PRICING_TEST_SHA256,\n",
                "    RegressionFreezeError,\n"
                "    REVIEWED_PACKAGING_TEST_SHA256,\n"
                "    REVIEWED_PACKAGING_UI_PATHS,\n"
                "    REVIEWED_PRICING_TEST_SHA256,\n",
                1,
            ),
            (
                "def test_pos_notice_dynamic_state_host_normalises_only_the_approved_bytes() -> None:\n",
                packaging_identity_test
                + code29_2_identity_test
                + "def test_pos_notice_dynamic_state_host_normalises_only_the_approved_bytes() -> None:\n",
                1,
            ),
            (
                "def test_audit_reader_migration_only_normalises_one_locator() -> None:\n",
                packaging_guard_test
                + "def test_audit_reader_migration_only_normalises_one_locator() -> None:\n",
                1,
            ),
            (
                '    assert {path for path in report.changed_production_files if path.startswith("frontend/src/")} == REVIEWED_WEB_AUTH_PATHS | {\n'
                '        "frontend/src/modules/gaming/GamingScreen.tsx",\n'
                '        "frontend/src/modules/gaming/gaming-tariff.test.ts",\n'
                "    }\n",
                '    assert {path for path in report.changed_production_files if path.startswith("frontend/src/")} == REVIEWED_WEB_AUTH_PATHS | {\n'
                '        "frontend/src/modules/gaming/GamingScreen.tsx",\n'
                '        "frontend/src/modules/gaming/gaming-tariff.test.ts",\n'
                "    } | REVIEWED_PACKAGING_UI_PATHS\n",
                1,
            ),
        ),
    )
    expected_test = _replace_exact(
        expected_test,
        (
            (
                "    RegressionFreezeError,\n    REVIEWED_PACKAGING_TEST_SHA256,\n",
                "    RegressionFreezeError,\n"
                "    REVIEWED_CODE29_2_PRODUCTION_PATHS,\n"
                "    REVIEWED_CODE29_2_TEST_SHA256,\n"
                "    REVIEWED_PACKAGING_TEST_SHA256,\n",
                1,
            ),
            (
                "    current = (ROOT / path).read_bytes()\n"
                "    assert hashlib.sha256(current).hexdigest() == expected_sha256\n"
                "    assert hashlib.sha256(current + b\"\\n\").hexdigest() != expected_sha256\n\n\n"
                '@pytest.mark.parametrize(("path", "expected_sha256"), REVIEWED_PACKAGING_TEST_SHA256.items())\n',
                "    current = (ROOT / path).read_bytes()\n"
                "    current_expected = REVIEWED_CODE29_2_TEST_SHA256.get(path, expected_sha256)\n"
                "    assert hashlib.sha256(current).hexdigest() == current_expected\n"
                "    assert hashlib.sha256(current + b\"\\n\").hexdigest() != current_expected\n\n\n"
                '@pytest.mark.parametrize(("path", "expected_sha256"), REVIEWED_PACKAGING_TEST_SHA256.items())\n',
                1,
            ),
            (
                "    } | REVIEWED_PACKAGING_UI_PATHS\n",
                "    } | REVIEWED_PACKAGING_UI_PATHS | {\n"
                "        path for path in REVIEWED_CODE29_2_PRODUCTION_PATHS if path.startswith(\"frontend/src/\")\n"
                "    }\n",
                1,
            ),
        ),
    )
    code30_identity_test = (
        "def test_code30_build33_identity_normalises_to_inherited_code25_baseline() -> None:\n"
        "    path = \"android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt\"\n"
        "    current = 'assertEquals(33, BuildConfig.VERSION_CODE)\\n\"3.1.25\"\\ncode 30 artifact\\n'\n"
        "    assert _normalise_release_identity(path, current) == (\n"
        "        'assertEquals(25, BuildConfig.VERSION_CODE)\\n\"3.1.14\"\\ncode 25 artifact\\n'\n"
        "    )\n\n\n"
    )
    code30_guard_test = (
        '@pytest.mark.parametrize(("path", "expected_sha256"), REVIEWED_CODE30_TEST_SHA256.items())\n'
        "def test_code30_test_rewrites_require_exact_reviewed_bytes(\n"
        "    path: str,\n"
        "    expected_sha256: str,\n"
        ") -> None:\n"
        "    current = (ROOT / path).read_bytes()\n"
        "    assert hashlib.sha256(current).hexdigest() == expected_sha256\n"
        '    assert hashlib.sha256(current + b"\\n").hexdigest() != expected_sha256\n\n\n'
    )
    expected_test = _replace_exact(
        expected_test,
        (
            (
                "    REVIEWED_CODE29_2_TEST_SHA256,\n"
                "    REVIEWED_PACKAGING_TEST_SHA256,\n",
                "    REVIEWED_CODE29_2_TEST_SHA256,\n"
                "    REVIEWED_CODE30_PRODUCTION_PATHS,\n"
                "    REVIEWED_CODE30_TEST_SHA256,\n"
                "    REVIEWED_PACKAGING_TEST_SHA256,\n",
                1,
            ),
            (
                code29_2_identity_test
                + "def test_pos_notice_dynamic_state_host_normalises_only_the_approved_bytes() -> None:\n",
                code29_2_identity_test
                + code30_identity_test
                + "def test_pos_notice_dynamic_state_host_normalises_only_the_approved_bytes() -> None:\n",
                1,
            ),
            (
                "    current_expected = REVIEWED_CODE29_2_TEST_SHA256.get(path, expected_sha256)\n",
                "    current_expected = REVIEWED_CODE30_TEST_SHA256.get(\n"
                "        path, REVIEWED_CODE29_2_TEST_SHA256.get(path, expected_sha256)\n"
                "    )\n",
                1,
            ),
            (
                "def test_audit_reader_migration_only_normalises_one_locator() -> None:\n",
                code30_guard_test
                + "def test_audit_reader_migration_only_normalises_one_locator() -> None:\n",
                1,
            ),
            (
                "    } | REVIEWED_PACKAGING_UI_PATHS | {\n"
                "        path for path in REVIEWED_CODE29_2_PRODUCTION_PATHS if path.startswith(\"frontend/src/\")\n"
                "    }\n",
                "    } | REVIEWED_PACKAGING_UI_PATHS | {\n"
                "        path for path in REVIEWED_CODE29_2_PRODUCTION_PATHS if path.startswith(\"frontend/src/\")\n"
                "    } | {\n"
                "        path for path in REVIEWED_CODE30_PRODUCTION_PATHS if path.startswith(\"frontend/src/\")\n"
                "    }\n",
                1,
            ),
        ),
    )
    code30_1_identity_test = (
        "def test_code30_point1_build34_identity_normalises_to_inherited_code25_baseline() -> None:\n"
        '    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"\n'
        "    current = 'assertEquals(34, BuildConfig.VERSION_CODE)\\n\"3.1.26\"\\ncode 30.1 artifact\\n'\n"
        "    assert _normalise_release_identity(path, current) == (\n"
        "        'assertEquals(25, BuildConfig.VERSION_CODE)\\n\"3.1.14\"\\ncode 25 artifact\\n'\n"
        "    )\n\n\n"
    )
    code30_1_guard_test = (
        '@pytest.mark.parametrize(("path", "expected_sha256"), REVIEWED_CODE30_1_FEATURE_SHA256.items())\n'
        "def test_code30_point1_reviewed_files_require_exact_bytes(\n"
        "    path: str,\n"
        "    expected_sha256: str,\n"
        ") -> None:\n"
        "    current = (ROOT / path).read_bytes()\n"
        "    assert hashlib.sha256(current).hexdigest() == expected_sha256\n"
        '    assert hashlib.sha256(current + b"\\n").hexdigest() != expected_sha256\n\n\n'
    )
    expected_test = _replace_exact(
        expected_test,
        (
            (
                "    REVIEWED_CODE29_2_TEST_SHA256,\n"
                "    REVIEWED_CODE30_PRODUCTION_PATHS,\n",
                "    REVIEWED_CODE29_2_TEST_SHA256,\n"
                "    REVIEWED_CODE30_1_FEATURE_SHA256,\n"
                "    REVIEWED_CODE30_1_PRODUCTION_PATHS,\n"
                "    REVIEWED_CODE30_PRODUCTION_PATHS,\n",
                1,
            ),
            (
                code30_identity_test
                + "def test_pos_notice_dynamic_state_host_normalises_only_the_approved_bytes() -> None:\n",
                code30_identity_test
                + code30_1_identity_test
                + "def test_pos_notice_dynamic_state_host_normalises_only_the_approved_bytes() -> None:\n",
                1,
            ),
            (
                "    current_expected = REVIEWED_CODE30_TEST_SHA256.get(\n"
                "        path, REVIEWED_CODE29_2_TEST_SHA256.get(path, expected_sha256)\n"
                "    )\n",
                "    current_expected = REVIEWED_CODE30_1_FEATURE_SHA256.get(\n"
                "        path,\n"
                "        REVIEWED_CODE30_TEST_SHA256.get(\n"
                "            path, REVIEWED_CODE29_2_TEST_SHA256.get(path, expected_sha256)\n"
                "        ),\n"
                "    )\n",
                1,
            ),
            (
                code30_guard_test
                + "def test_audit_reader_migration_only_normalises_one_locator() -> None:\n",
                code30_guard_test
                + code30_1_guard_test
                + "def test_audit_reader_migration_only_normalises_one_locator() -> None:\n",
                1,
            ),
            (
                "    } | {\n"
                '        path for path in REVIEWED_CODE30_PRODUCTION_PATHS if path.startswith("frontend/src/")\n'
                "    }\n",
                "    } | {\n"
                '        path for path in REVIEWED_CODE30_PRODUCTION_PATHS if path.startswith("frontend/src/")\n'
                "    } | {\n"
                '        path for path in REVIEWED_CODE30_1_PRODUCTION_PATHS if path.startswith("frontend/src/")\n'
                "    }\n",
                1,
            ),
        ),
    )
    code30_1_retry_identity_test = (
        "def test_code30_point1_retry_build35_identity_normalises_to_inherited_code25_baseline() -> None:\n"
        '    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"\n'
        "    current = 'assertEquals(35, BuildConfig.VERSION_CODE)\\n\"3.1.27\"\\ncode 30.1 artifact\\n'\n"
        "    assert _normalise_release_identity(path, current) == (\n"
        "        'assertEquals(25, BuildConfig.VERSION_CODE)\\n\"3.1.14\"\\ncode 25 artifact\\n'\n"
        "    )\n\n\n"
    )
    code30_1_release_test_guard = (
        "@pytest.mark.parametrize(\n"
        '    ("path", "expected_sha256"), REVIEWED_CODE30_1_RELEASE_TEST_SHA256.items()\n'
        ")\n"
        "def test_code30_point1_release_tests_require_exact_reviewed_bytes(\n"
        "    path: str,\n"
        "    expected_sha256: str,\n"
        ") -> None:\n"
        "    current = (ROOT / path).read_bytes()\n"
        "    assert hashlib.sha256(current).hexdigest() == expected_sha256\n"
        '    assert hashlib.sha256(current + b"\\n").hexdigest() != expected_sha256\n\n\n'
    )
    expected_test = _replace_exact(
        expected_test,
        (
            (
                "    REVIEWED_CODE30_1_FEATURE_SHA256,\n",
                "    REVIEWED_CODE30_1_FEATURE_SHA256,\n"
                "    REVIEWED_CODE30_1_RELEASE_TEST_SHA256,\n",
                1,
            ),
            (
                code30_1_identity_test
                + "def test_pos_notice_dynamic_state_host_normalises_only_the_approved_bytes() -> None:\n",
                code30_1_identity_test
                + code30_1_retry_identity_test
                + "def test_pos_notice_dynamic_state_host_normalises_only_the_approved_bytes() -> None:\n",
                1,
            ),
            (
                code30_1_guard_test
                + "def test_audit_reader_migration_only_normalises_one_locator() -> None:\n",
                code30_1_guard_test
                + code30_1_release_test_guard
                + "def test_audit_reader_migration_only_normalises_one_locator() -> None:\n",
                1,
            ),
        ),
    )
    code30_1_replay_identity_test = (
        "def test_code30_point1_replay_build36_identity_normalises_to_inherited_code25_baseline() -> None:\n"
        '    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"\n'
        "    current = 'assertEquals(36, BuildConfig.VERSION_CODE)\\n\"3.1.28\"\\ncode 30.1 artifact\\n'\n"
        "    assert _normalise_release_identity(path, current) == (\n"
        "        'assertEquals(25, BuildConfig.VERSION_CODE)\\n\"3.1.14\"\\ncode 25 artifact\\n'\n"
        "    )\n\n\n"
    )
    code30_1_deletion_replay_guard = (
        "@pytest.mark.parametrize(\n"
        '    ("path", "expected_sha256"), REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.items()\n'
        ")\n"
        "def test_code30_point1_deletion_replay_files_require_exact_bytes(\n"
        "    path: str,\n"
        "    expected_sha256: str,\n"
        ") -> None:\n"
        "    current = (ROOT / path).read_bytes()\n"
        "    assert hashlib.sha256(current).hexdigest() == expected_sha256\n"
        '    assert hashlib.sha256(current + b"\\n").hexdigest() != expected_sha256\n\n\n'
    )
    code30_1_build36_release_guard = (
        "@pytest.mark.parametrize(\n"
        '    ("path", "expected_sha256"),\n'
        "    REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.items(),\n"
        ")\n"
        "def test_code30_point1_build36_release_tests_require_exact_reviewed_bytes(\n"
        "    path: str,\n"
        "    expected_sha256: str,\n"
        ") -> None:\n"
        "    current = (ROOT / path).read_bytes()\n"
        "    assert hashlib.sha256(current).hexdigest() == expected_sha256\n"
        '    assert hashlib.sha256(current + b"\\n").hexdigest() != expected_sha256\n\n\n'
    )
    expected_test = _replace_exact(
        expected_test,
        (
            (
                "    REVIEWED_CODE29_2_TEST_SHA256,\n"
                "    REVIEWED_CODE30_1_FEATURE_SHA256,\n",
                "    REVIEWED_CODE29_2_TEST_SHA256,\n"
                "    REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256,\n"
                "    REVIEWED_CODE30_1_DELETION_REPLAY_PRODUCTION_PATHS,\n"
                "    REVIEWED_CODE30_1_DELETION_REPLAY_SHA256,\n"
                "    REVIEWED_CODE30_1_FEATURE_SHA256,\n",
                1,
            ),
            (
                code30_1_retry_identity_test
                + "def test_pos_notice_dynamic_state_host_normalises_only_the_approved_bytes() -> None:\n",
                code30_1_retry_identity_test
                + code30_1_replay_identity_test
                + "def test_pos_notice_dynamic_state_host_normalises_only_the_approved_bytes() -> None:\n",
                1,
            ),
            (
                "    current_expected = REVIEWED_CODE30_1_FEATURE_SHA256.get(\n"
                "        path,\n"
                "        REVIEWED_CODE30_TEST_SHA256.get(\n"
                "            path, REVIEWED_CODE29_2_TEST_SHA256.get(path, expected_sha256)\n"
                "        ),\n"
                "    )\n",
                "    current_expected = REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(\n"
                "        path,\n"
                "        REVIEWED_CODE30_1_FEATURE_SHA256.get(\n"
                "            path,\n"
                "            REVIEWED_CODE30_TEST_SHA256.get(\n"
                "                path, REVIEWED_CODE29_2_TEST_SHA256.get(path, expected_sha256)\n"
                "            ),\n"
                "        ),\n"
                "    )\n",
                1,
            ),
            (
                code30_1_guard_test,
                code30_1_guard_test.replace(
                    "    assert hashlib.sha256(current).hexdigest() == expected_sha256\n"
                    '    assert hashlib.sha256(current + b"\\n").hexdigest() != expected_sha256\n',
                    "    current_expected = REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(path, expected_sha256)\n"
                    "    assert hashlib.sha256(current).hexdigest() == current_expected\n"
                    '    assert hashlib.sha256(current + b"\\n").hexdigest() != current_expected\n',
                    1,
                )
                + code30_1_deletion_replay_guard,
                1,
            ),
            (
                code30_guard_test,
                code30_guard_test.replace(
                    "    assert hashlib.sha256(current).hexdigest() == expected_sha256\n"
                    '    assert hashlib.sha256(current + b"\\n").hexdigest() != expected_sha256\n',
                    "    current_expected = REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(\n"
                    "        path, expected_sha256\n"
                    "    )\n"
                    "    assert hashlib.sha256(current).hexdigest() == current_expected\n"
                    '    assert hashlib.sha256(current + b"\\n").hexdigest() != current_expected\n',
                    1,
                ),
                1,
            ),
            (
                code30_1_release_test_guard,
                code30_1_release_test_guard.replace(
                    "    assert hashlib.sha256(current).hexdigest() == expected_sha256\n"
                    '    assert hashlib.sha256(current + b"\\n").hexdigest() != expected_sha256\n',
                    "    current_expected = REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.get(\n"
                    "        path, expected_sha256\n"
                    "    )\n"
                    "    assert hashlib.sha256(current).hexdigest() == current_expected\n"
                    '    assert hashlib.sha256(current + b"\\n").hexdigest() != current_expected\n',
                    1,
                )
                + code30_1_build36_release_guard,
                1,
            ),
            (
                "    } | {\n"
                '        path for path in REVIEWED_CODE30_1_PRODUCTION_PATHS if path.startswith("frontend/src/")\n'
                "    }\n",
                "    } | {\n"
                '        path for path in REVIEWED_CODE30_1_PRODUCTION_PATHS if path.startswith("frontend/src/")\n'
                "    } | {\n"
                "        path\n"
                "        for path in REVIEWED_CODE30_1_DELETION_REPLAY_PRODUCTION_PATHS\n"
                '        if path.startswith("frontend/src/")\n'
                "    }\n",
                1,
            ),
        ),
    )
    historical_freeze_test = _historical_bytes(
        CODE30_2_BASE, freeze_test_path
    ).decode("utf-8")
    assert hashlib.sha256(historical_freeze_test.encode("utf-8")).hexdigest() == (
        CODE30_2_FREEZE_TEST_SHA256
    )
    current_freeze_test = _current(freeze_test_path)
    historical_tests = set(re.findall(r"^def (test_[^(]+)", expected_test, re.MULTILINE))
    current_tests = set(
        re.findall(r"^def (test_[^(]+)", current_freeze_test, re.MULTILINE)
    )
    assert historical_tests <= current_tests

    assert hashlib.sha256(
        (ROOT / "tests/test_code29_deployment_identity.py").read_bytes()
    ).hexdigest() == HISTORICAL_CODE29_GUARD_SHA256
    assert hashlib.sha256(
        (ROOT / "tests/test_caddy_dependency_security.py").read_bytes()
    ).hexdigest() == HISTORICAL_CADDY_GUARD_SHA256


def test_reviewed_pos_and_inventory_ui_corrections_are_exact() -> None:
    pos_path = "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt"
    stale_held_review_key = (
        "    state.heldOrderReview\n"
        "        ?.takeIf { access.canCreateAndCollect && voidTarget == null }\n"
        "        ?.let { review ->\n"
        "            key(review.orderId, review.checkoutVersion) {"
    )
    corrected_held_review_key = (
        "    state.heldOrderReview\n"
        "        ?.takeIf { access.canCreateAndCollect && voidTarget == null }\n"
        "        ?.let { review ->\n"
        "            key(review.orderId) {"
    )
    expected_pos = _replace_exact(
        _original(pos_path),
        (
            (
                stale_held_review_key,
                corrected_held_review_key,
                1,
            ),
        ),
    )
    current_pos = _current(pos_path)
    _assert_sha256(
        pos_path,
        current_pos.encode("utf-8"),
        _current_reviewed_sha256(
            pos_path, hashlib.sha256(expected_pos.encode("utf-8")).hexdigest()
        ),
    )
    assert stale_held_review_key not in current_pos
    assert current_pos.count(corrected_held_review_key) == 1

    for path in REVIEWED_UI_SHA256:
        _assert_sha256(
            path,
            (ROOT / path).read_bytes(),
            _reviewed_sha256(path, REVIEWED_UI_SHA256),
        )


def test_reviewed_web_session_correction_is_exact() -> None:
    for path in REVIEWED_WEB_SESSION_SHA256:
        _assert_sha256(
            path,
            (ROOT / path).read_bytes(),
            _reviewed_sha256(path, REVIEWED_WEB_SESSION_SHA256),
        )


def test_reviewed_backend_refresh_lock_correction_is_exact() -> None:
    for path in REVIEWED_BACKEND_REFRESH_LOCK_SHA256:
        _assert_sha256(
            path,
            (ROOT / path).read_bytes(),
            _reviewed_sha256(path, REVIEWED_BACKEND_REFRESH_LOCK_SHA256),
        )


def test_reviewed_pricing_card_correction_is_exact() -> None:
    for path in REVIEWED_PRICING_CARD_SHA256:
        _assert_sha256(
            path,
            (ROOT / path).read_bytes(),
            _reviewed_sha256(path, REVIEWED_PRICING_CARD_SHA256),
        )


def test_reviewed_packaging_labels_are_exact() -> None:
    for path in REVIEWED_PACKAGING_LABEL_SHA256:
        _assert_sha256(
            path,
            (ROOT / path).read_bytes(),
            _reviewed_sha256(path, REVIEWED_PACKAGING_LABEL_SHA256),
        )


def test_reviewed_code29_2_feature_files_are_exact() -> None:
    for path in REVIEWED_CODE29_2_FEATURE_SHA256:
        _assert_sha256(
            path,
            (ROOT / path).read_bytes(),
            _reviewed_sha256(path, REVIEWED_CODE29_2_FEATURE_SHA256),
        )


def test_reviewed_code30_feature_files_are_exact() -> None:
    for path in REVIEWED_CODE30_FEATURE_SHA256:
        _assert_sha256(
            path,
            (ROOT / path).read_bytes(),
            _reviewed_sha256(path, REVIEWED_CODE30_FEATURE_SHA256),
        )


def test_reviewed_code30_point1_feature_files_are_exact() -> None:
    for path, expected_sha256 in REVIEWED_CODE30_1_FEATURE_SHA256.items():
        _assert_sha256(
            path,
            (ROOT / path).read_bytes(),
            _current_reviewed_sha256(
                path,
                REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(path, expected_sha256),
            ),
        )


def test_reviewed_code30_point1_release_tests_are_exact() -> None:
    for path, expected_sha256 in REVIEWED_CODE30_1_RELEASE_TEST_SHA256.items():
        _assert_sha256(
            path,
            (ROOT / path).read_bytes(),
            _current_reviewed_sha256(
                path,
                REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.get(
                    path, expected_sha256
                ),
            ),
        )


def test_reviewed_code30_point1_deletion_replay_files_are_exact() -> None:
    for path, expected_sha256 in REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.items():
        _assert_sha256(
            path,
            (ROOT / path).read_bytes(),
            _current_reviewed_sha256(path, expected_sha256),
        )


def test_reviewed_code30_point1_build36_release_tests_are_exact() -> None:
    for path, expected_sha256 in REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.items():
        _assert_sha256(
            path,
            (ROOT / path).read_bytes(),
            _current_reviewed_sha256(path, expected_sha256),
        )


def test_reviewed_code30_point2_delta_files_are_exact() -> None:
    for path, expected_sha256 in REVIEWED_CODE30_2_SHA256.items():
        _assert_sha256(
            path, _historical_bytes(CODE30_2_BASE, path), expected_sha256
        )


def test_reviewed_code30_point3_delta_files_are_exact() -> None:
    for path, expected_sha256 in REVIEWED_CODE30_3_SHA256.items():
        _assert_sha256(
            path, _historical_bytes(CODE30_3_BASE, path), expected_sha256
        )


def test_reviewed_code30_point4_delta_files_are_exact() -> None:
    for path, expected_sha256 in REVIEWED_CODE30_4_SHA256.items():
        _assert_sha256(path, (ROOT / path).read_bytes(), expected_sha256)


@pytest.mark.parametrize("path", REVIEWED_BACKEND_REFRESH_LOCK_SHA256)
def test_reviewed_backend_refresh_lock_hash_guards_reject_mutations(path: str) -> None:
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_sha256(
            path,
            (ROOT / path).read_bytes() + b"\n",
            _reviewed_sha256(path, REVIEWED_BACKEND_REFRESH_LOCK_SHA256),
        )


@pytest.mark.parametrize("path", REVIEWED_PRICING_CARD_SHA256)
def test_reviewed_pricing_card_hash_guards_reject_mutations(path: str) -> None:
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_sha256(
            path,
            (ROOT / path).read_bytes() + b"\n",
            _reviewed_sha256(path, REVIEWED_PRICING_CARD_SHA256),
        )


@pytest.mark.parametrize("path", REVIEWED_PACKAGING_LABEL_SHA256)
def test_reviewed_packaging_label_hash_guards_reject_mutations(path: str) -> None:
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_sha256(
            path,
            (ROOT / path).read_bytes() + b"\n",
            _reviewed_sha256(path, REVIEWED_PACKAGING_LABEL_SHA256),
        )


@pytest.mark.parametrize("path", REVIEWED_WEB_SESSION_SHA256)
def test_reviewed_web_session_hash_guards_reject_working_tree_mutations(
    path: str,
) -> None:
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_sha256(
            path,
            (ROOT / path).read_bytes() + b"\n",
            _reviewed_sha256(path, REVIEWED_WEB_SESSION_SHA256),
        )


@pytest.mark.parametrize("path", REVIEWED_UI_SHA256)
def test_reviewed_ui_hash_guards_reject_working_tree_mutations(path: str) -> None:
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_sha256(
            path,
            (ROOT / path).read_bytes() + b"\n",
            _reviewed_sha256(path, REVIEWED_UI_SHA256),
        )


@pytest.mark.parametrize("path", REVIEWED_CODE29_2_FEATURE_SHA256)
def test_reviewed_code29_2_feature_hash_guards_reject_mutations(path: str) -> None:
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_sha256(
            path,
            (ROOT / path).read_bytes() + b"\n",
            _reviewed_sha256(path, REVIEWED_CODE29_2_FEATURE_SHA256),
        )


@pytest.mark.parametrize("path", REVIEWED_CODE30_FEATURE_SHA256)
def test_reviewed_code30_feature_hash_guards_reject_mutations(path: str) -> None:
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_sha256(
            path,
            (ROOT / path).read_bytes() + b"\n",
            _reviewed_sha256(path, REVIEWED_CODE30_FEATURE_SHA256),
        )


@pytest.mark.parametrize("path", REVIEWED_CODE30_1_FEATURE_SHA256)
def test_reviewed_code30_point1_feature_hash_guards_reject_mutations(path: str) -> None:
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_sha256(
            path,
            (ROOT / path).read_bytes() + b"\n",
            _reviewed_sha256(path, REVIEWED_CODE30_1_FEATURE_SHA256),
        )


@pytest.mark.parametrize("path", REVIEWED_CODE30_1_DELETION_REPLAY_SHA256)
def test_reviewed_code30_point1_deletion_replay_hash_guards_reject_mutations(
    path: str,
) -> None:
    content = (ROOT / path).read_bytes()
    expected_sha256 = _current_reviewed_sha256(
        path, REVIEWED_CODE30_1_DELETION_REPLAY_SHA256[path]
    )
    _assert_sha256(path, content, expected_sha256)
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_sha256(path, content + b"\n", expected_sha256)


@pytest.mark.parametrize("path", REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256)
def test_reviewed_code30_point1_build36_release_test_hash_guards_reject_mutations(
    path: str,
) -> None:
    content = (ROOT / path).read_bytes()
    expected_sha256 = _current_reviewed_sha256(
        path, REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256[path]
    )
    _assert_sha256(path, content, expected_sha256)
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_sha256(path, content + b"\n", expected_sha256)


@pytest.mark.parametrize("path", REVIEWED_CODE30_1_RELEASE_TEST_SHA256)
def test_reviewed_code30_point1_release_test_hash_guards_reject_mutations(
    path: str,
) -> None:
    content = (ROOT / path).read_bytes()
    expected_sha256 = _current_reviewed_sha256(
        path, REVIEWED_CODE30_1_RELEASE_TEST_SHA256[path]
    )
    _assert_sha256(path, content, expected_sha256)
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_sha256(path, content + b"\n", expected_sha256)


def test_operator_records_are_frozen_and_trial_precedes_production() -> None:
    for path, expected_sha256 in OPERATOR_RECORD_SHA256.items():
        assert hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == (
            _current_reviewed_sha256(path, expected_sha256)
        )
    combined = "\n".join(
        _current(path)
        for path in (
            *OPERATOR_RECORD_SHA256,
            "docs/CODE30_2_PATCH_CANDIDATE.md",
            "docs/CODE30_3_PATCH_CANDIDATE.md",
        )
    )
    for contract in (
        "Original signed Code 29",
        "Current Code 29",
        "v3.1.20",
        "cancelled before build or signing",
        "v3.1.21",
        "versionCode=29",
        "migration head `0071`",
        "final-source synthetic trial",
        "authenticated emulator and Web route",
        "prior Lenovo physical",
        "ANDROID_MIN_SUPPORTED_VERSION_CODE=8",
        "channel-wide",
        "user consent",
        "87 checks",
        "cleanup was verified",
        "signed-byte continuity",
        "emulator `5574`",
        "no signed or installed",
        "existing authority",
        "v3.1.29",
        "build `37`",
        "Alembic head `0078`",
        "Room schema `51`",
        "v3.1.30",
        "build `38`",
        "Alembic head `0082`",
        "Room schema `52`",
        "Physical Redmi Pad 2 acceptance",
    ):
        assert contract in combined
    assert "broader signed-Code29\nscanner-source review remains incomplete" in combined
    assert "does not claim that `3.1.21` final CI" in combined


@pytest.mark.parametrize(
    ("path", "old", "new"),
    [
        ("infra/scripts/install-on-vm.sh", "%f:%d:%i", "%F:%d:%i"),
        (".github/workflows/ci.yml", "sudo -n --", "sudo --"),
        ("backend/app/__init__.py", '"3.1.30"', '"3.1.30-mutated"'),
    ],
)
def test_live_exact_guards_reject_working_tree_mutations(
    path: str, old: str, new: str
) -> None:
    if path.startswith(".github/"):
        expected = _workflow_expected(path)
    elif path == "backend/app/__init__.py":
        expected = _identity_expected(path)
    else:
        expected = _current(path)
    assert old in expected
    mutated = expected.replace(old, new, 1)
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_exact_text(path, mutated, expected)
