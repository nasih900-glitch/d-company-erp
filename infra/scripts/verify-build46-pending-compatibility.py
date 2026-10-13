#!/usr/bin/env python3
"""Verify the exact build-45 -> build-46 unresolved-pending server transition.

This proof preserves two reported tablet counters without inspecting their
queues, retiring installations, or authorizing an app offer. It is valid only
while production remains on loyalty program revision 0.
"""
from __future__ import annotations

import argparse
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


PRIOR_REVISION = "ee025b5b78dff7397e16612cad80aebd1a4904d3"
PRIOR_VERSION = "3.1.37"
PRIOR_DATABASE_HEAD = "0086"
CANDIDATE_VERSION = "3.1.38"
CANDIDATE_DATABASE_HEAD = "0087"
PRIOR_SOURCE_ARCHIVE_SHA256 = "6b9bbd2b6613873663e6b23d212317041338b1f5916a39c1b2beec164f6572c7"
PRIOR_BACKEND_MANIFEST_SHA256 = "8deacbab0ad0daee1809070e10906b29e36b841e05b5b653daa48e05a19e86a0"
CANDIDATE_BACKEND_MANIFEST_SHA256 = "b5024239c3c747dc9bc4c952217b0ad1fe322ef60af867761ff842c9799b03df"
PRIOR_SOURCE_SHA256 = "8de1fc15d670fd8beac5bebcbb5c93471ec936058d605051051657a8c3b22fd5"
CANDIDATE_SOURCE_SHA256 = "1af474780ffce4ea227d339240a60060aa17093ae8a6639980c803a2f7030f6a"

HISTORICAL_HELPER = "infra/scripts/verify-build45-pending-compatibility.py"
HISTORICAL_HELPER_SHA256 = "bf42dacc6c8f4eb92c9c65646b8ac0210490d0b78779bca8731245047f7fb51b"
MAX_DOCUMENT_BYTES = 1024 * 1024
EXPECTED_INSTALLATION_ROWS = 11
EXPECTED_PENDING = 2
EXPECTED_NONZERO = 2
EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256 = "c7a3eea897b48e4fe8adb0d363661481a7f0cb32b2350e63539e5a0c0051d525"
EXPECTED_NONZERO_FLEET_SHA256 = "5a1100a5695e997be523eb7a948d06db0fd79fed1a4f3a5f48c3286c86ea5128"

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
    HISTORICAL_HELPER,
    "infra/scripts/verify-build45-pending-state.sql",
    "ops/runtime_release_parity.py",
)

# Filled after the release-only installer path is frozen. The verifier itself
# is pinned by the repository regression overlay, avoiding a digest cycle.
REVIEWED_CHANGES: dict[str, tuple[str | None, str | None]] = {
    '.env.production.example': ('e7c0181f0c08afb8b51e720f893517b583e2c4485da33e68950f475bee453225', 'a38f3083f713917f7e3874e81b5ba759454d105b227406771df549d247d60988'),
    'android-native/app/build.gradle.kts': ('ab45fd0caf781298fe2bf143c12913c3fc1eb254d8f39b6a8a41c1e505fcabf7', '78349f2db2d350d0899616953afa2194c4882043b30175e9c4675c0d908184b8'),
    'android-native/app/schemas/cloud.dcompany.erp.core.db.ErpDatabase/54.json': (None, 'afc89eab7d769766611438f2bc5eb92165e15baffdaf431215eb0b3e20e997a3'),
    'android-native/app/src/main/java/cloud/dcompany/erp/MainActivity.kt': ('7315325f1e44fd73fb427e051c0142984bddd0839c9e6ffd3ae5b8809adb65b4', '3ed37c673c87972dff855ea79bbc8d32ed14c41e16cb8386a5ee0165bc1ecb0c'),
    'android-native/app/src/main/java/cloud/dcompany/erp/core/auth/CacheScope.kt': ('e384f4ce5263c8a19e04eeffa116d88bda60dad3726b7cfeb87370de3cee6968', 'edbdf3c3bc51b05ebb4a0afee2b5c414cf2140b45b465f9ee8d452bc3d2513d3'),
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/CustomerDao.kt': ('851aeee68320705ae3db67439d9440b21a63acdefb584cac9a1b762c4457044a', 'efa3bae5af8b30a311377d18648bbf5acf2eaa39f017db6f092a06ebb5652907'),
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/CustomerEntities.kt': ('b348b94c416790f73adb519e30566448f32702afc7228a97df1b4934b6e5b9bc', '409c00c91c275523c8533228b629de64a67c5025c1e8f441fe1c1878d738f7a4'),
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Dao.kt': ('cbc2190c82710c3f16ef711c77fd36c143f6432d1eab627ab1ecf537213f1e21', '701c829a3b2c75f461750179e223497dd4246eb6fc6faccc6359367527b48c1e'),
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/LoyaltyEntities.kt': (None, 'dd682fc18e550780c78a267ca617903d38709629e1146e84ebed2797f2a2ad15'),
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/Migrations.kt': ('a801cb9e7c191ddece727f4f4960fa31a195c5eb6931d643c63c8426b64d916d', '8ddb7b79f4e84f9ce698a3c6d2af5a736f72b2eccc6ad84c6eebdfbfcb41da33'),
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/OutboxSafetyDao.kt': ('700886984acbc5e350e35554583486ed1edf0e1c8584534d3bdddde7e634ad4f', 'c238f2cb46e3eee2de5437e99ba425b50043edf9f4f3802dda63e013bd2f8dad'),
    'android-native/app/src/main/java/cloud/dcompany/erp/core/db/ShiftCloseSafetyDao.kt': ('be0d0fd5120ca068b7237c856179de92c35900c56471759cfb4aae1cf80a901e', '595e645a41219edadb0afd7f838b0f08582e317bc3565c02d12126bd09118d82'),
    'android-native/app/src/main/java/cloud/dcompany/erp/core/net/ErpApi.kt': ('0f513ec44f128f165df45ea57a920e12cf349cf7ce3c789dff62bc1ae20a5de5', 'c638e3099762b90ed049230af43463a006177b079cfc3e56afc9e7c904e0fc65'),
    'android-native/app/src/main/java/cloud/dcompany/erp/core/net/Models.kt': ('5a5a862f0c897442620a38f8497431b3ddd6be21e76701cd87af607090b7a426', '951d60dbe54c04c44caff719102f5fd67e31ee23df056dad86769880f095440e'),
    'android-native/app/src/main/java/cloud/dcompany/erp/core/net/ReceiptHistoryModels.kt': ('2c45f5ec2ee2cc0f55f62bc76822c018585417b8b795ca4fd0e1851e5828e276', '0713b569ddd6d3f01fabbee4571b0f983ebdea81db2069a6d1518f80276e708e'),
    'android-native/app/src/main/java/cloud/dcompany/erp/core/sync/SyncEngine.kt': ('24821025d1db709db620dbca7cda0eb9cd68ca11156c8d7a43d01c3d85fd8f1a', '8f322b3503ef4f0760b7e1b7e6765c419ce449e8aef90be152e602ec16698e7b'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/Nav.kt': ('4d919d3019ac0291839344c983a187d844831f76dc8783032287634d4b9c2b5a', 'bde673c127be9371b6db7d4239aa68b74b09a743043075a7f150e450a8c58455'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/OperationalComponents.kt': ('ccbd4092310bbef883ca9f6642af1f1dcbfbb63bfd45b3cb6a7f464593e334c4', 'e5ba4f2bb36843041afeabca0857b223cc0a5de8c302825943182711013559c8'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/PremiumComponents.kt': ('88db134a123da205553366ed516d9192c6f36a7597a6d8288709a4c7acc82785', '7c0388e1200035aafca3a66315a72bd14377540dc623c7e5113c08be63061548'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/PricingUnlockDialog.kt': ('e56a7a719db009c94a38b74dfabdf4b025b8fd4b2aea99f6146aaa59ece0007f', '06773d5a0ec307f5e0c7382cc4134104e0d24f2af311e472b804003477b2c22a'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/Primitives.kt': ('c09c3005789d14506cdb8846e031db3b7eadcd9b72bd7212531f3babc73fedfc', 'b8fe543d8974ad731b1c446647681261774d352ae73b7846e4e6176c1488c2a0'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/ViewOnlyNotice.kt': ('75340805c3230d8530eaa4f34b606c6ec327ce2edb626a32e3e09e9dc6946b04', '3128ae2d1bea430cbdf4169ee0b534e220e404fe436b63c37b67821e3d358990'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/components/VoidReasonInput.kt': ('f671d55ff7867a23e5b55e8f917a693311164c1bb49784e971883dcd155896b5', '6b178276ce3ed7ac08a18f44c22860ef416208fdba3ce1be9ec4dd764f198475'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/remote/RemoteAssistanceUi.kt': ('02ec17fbca518de9d68a92d6a335ca955b823002e875f6e5470a94adc3ad7462', 'a7bb9f03c406c34179353424c7fce5aced9b33056ee77c3d45a44c7678edb5c4'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/CanonicalReceiptHistoryDialog.kt': ('4337222e51bacb967b4e72d2153168a767aa9f69ffffa97fc4e246cf6b16471f', '70e16ac11d5ad1cd49c2815588fbd2315dcfe5860d1bb688c95893f323961094'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/LoginScreen.kt': ('79b759e66ed8244c8a0e33e20d85700ebbb2d0eb28a3352aac2bac7ce2c6bedc', '5cb7e43038a9c97ced2437dd6ba8223777e88644b4f677a4cd3cf744d6970797'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PasswordRecoveryDialog.kt': ('bab27942fc20a97755764b1c8bf23736e6835838466224c77d7ae07e4049d880', '910edf156503778bd74323342fa8fb9efca1d6fec2c25e63e774158c2d9da069'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PointsActionReplay.kt': (None, 'aa4ce3caf05a41b999c2f217c17e70a2e565bffdbce22b00ac5c9977ad2a5373'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt': ('3c2587cea88a49b2df1a0cfd4273e0c218b4d8dc1c2f5f4bae8b310c839e1422', '27438786302032e3f2f78867c22a548864140f948e325f4d6b5ea9ee70f2f7a5'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt': ('1158e6cebb252e3f4be6748568e6ba58b23c4f9b253cb44f801682f1ada8ffa3', '1440feaa267991da115684b25279a936b6c1b2f386991bb5952d5fef9e7c9068'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/RewardEligibilityPolicy.kt': (None, '8a2d6c7e5286801927dec3a8ad82e861b6ad8faf98e6cb584cab00ba8b503608'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/TerminalSelectionScreen.kt': ('56b97efd464f367b23885ecc51b40235b301310d4296f02113e36b8c2bc8c12a', '01817c388b450463c9d062467aa683ff046fd2e084922818a7b59e664ffac6c4'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/accesscontrol/AccessControlScreen.kt': ('51103b6f5556011cf2509ce785b6217a9eef31704f453242080020e736fd98f4', 'babc6ea2ad4743592defd409c3680fa559df6d0e7c1683cfc28a93f572e6c651'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/analytics/AnalyticsScreen.kt': ('fde37dd7b7171ccc41c1ca148ce0debbea931c1d578478ffa565215f6bbec360', '69176a34be850af30a26f4e5be63bcc8bdff33c77abde8682d220506823b04b6'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/audit/AuditLogScreen.kt': ('3ab6fc24b6bb918348dd5705194d31be4f234bfc5251ac4e750655c2cdca3047', '683106415fcc1f3d30fdc297b5993493455cc078218aff08d1ed0f5603389030'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersModels.kt': ('aaf04196fbb2ef548634ab4e806787035ac5ecdefcc7066c64cdcb28757869a9', '78cda732fcd8b252422dc61595c5ff2a560984e38a881c62472ad29dfa2eae1f'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersScreen.kt': ('046dc59952864c9e1eec2211c925cef598b6452a9dd8b4820cb6f2ecc0df2b12', '94142c8b31c44eb65901595aa7522c71555553490980ce53e387605f9d203af8'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersViewModel.kt': ('9035607b527888720ea491b9f9030e67d3be0dc325da2f63686a009c76285eb5', '347011ba8e720894361e93705b475ed0a0d02d1e7dd962e845855d91362f81d4'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/events/EventsScreen.kt': ('e25608d26565ffab4a1d42e7a5365429f7ab33df5ce8247bff95e74d8f125641', 'decffa1863d8bed3caa87f211c0c2659e59a4231722ae518cdb18ea11ecb3575'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceScreen.kt': ('7e35db04d4076fec0fd1ae04a92eaab24d7c735fad10b24c3ca5c60111c0ad6e', 'a904b8c306fb1de63c17c8ced074def7d243f29cb25b710937046b0ab4e9659d'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDial.kt': ('e5da9406b6b3c03b9910e00a09acaaf2961991b0cc90bd64296982fb02ceef84', 'd105fd9c5f9e5dc9cd16b85194836595c75c57e4c4ee4f31263ace22e950776c'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': ('622b2d772dc18da7c1c5ecf306c361f92bf9d287d7e7e7166a539127b578c6c7', '9fc613a7873aeca27f1c0cd0128cd14f74bc7a1a0c7255b2e4211ff8f1518b24'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt': ('3899fa3a2e92643ba3f8c16eff83db1dab4df3b23841c3ac03456d50f668ac2f', '207e67253219d09e56dedb66183c30ac32ab20b826b6ec8e7e5f97c738be19a8'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/PauseSessionDialog.kt': ('f2e9c743b0716317c7745f8770c1cf5f1f0e863783f1cd4d8b6980cfaa88ee4f', '0cbc6e06cb696e23694387a8866816822e84787519a48894fd521a954f1eb0ea'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/inventory/InventoryScreen.kt': ('ef0b0be225b11243a918cf503a4ef0df59fd5504150cdbf65f64bad67c03c79e', '293dfa15cac61236e23a670137f5f012186ad74797da2619744bd3de172b2586'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/kitchen/KitchenScreen.kt': ('cc9f84eb88e0b9441cb33bf76e094a7bc47832ff7f38785dce9f5f80d22ead6c', '3f0a470399258e5e70e7c6f1cec0277a8bd533d3102ce9eba80959e323fe9283'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/memberships/MembershipsScreen.kt': ('0670bb4a69da93560f1376296175db14c7fc54cf5ac4e6b639d8765135812183', '9d98473ffb7d03f71b4fda2953368bf167acc4d3f7bb4feb256ac3a195d849ab'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/menu/MenuScreen.kt': ('37f356bb41b91a833bef23b4c319c659e8d6d3cad305e54fa16ff2ffdd4498f1', '76aaf1412e6db3fbbdec57048a92deaed71bac98d887862349f3b734cee39e74'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/refunds/RefundsScreen.kt': ('8ade1e714b54b47d6454213572a5eafefbc8b4a3de3e1286f8bfee40e0d7ce10', 'ee527769a487f821beda5d383aab6aaaac4950ae6f9a3e06c7b48f30d6047062'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/refunds/RefundsViewModel.kt': ('61478710c781007033189e37805b5218535b93264582edc4de67886b43834336', '941081ac9f0c087cdc7297e83cec13128e00e750a6310d9f972a144bef50316c'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/reports/ReportsScreen.kt': ('6c7eb48413247c7f3b4b6b9e6aed090dc62bb68111d9b0651666968dae7b15ea', '086fd40947d2ed4412492457c88d26db30c4528d36e5ffda60f8e4c29a31b0ad'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/reservations/ReservationsScreen.kt': ('9888dbfe2b059521ff305d4d1bb53c279c7ab86ff50ed445ecc1b33d569083a9', 'c7dd1d7a1f5aaa07b702ec45786ed151ec520752344d9e3e993737c60a3e0028'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/BugReportDialog.kt': ('86e2f4f179f66689b50e9c81889a791186c186d72d3d73522fcbfe476fe83f10', 'b66b1f09bb1cafda54870348d94cc4970fe0fd423ef2d726c41b5bbd8928d070'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/HelpScreen.kt': ('b1ac67b8db2a6931bceeeb0f548a8e02343ef55a67edbf46f2b8162b2137b722', '25ae4b873bd1633367ce574835835c85199536ba85ef2a7a7a0dea5ce59e3fd0'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SettingsApi.kt': ('71fb1b75c985003915eb81a5a38c3cbc425570057ebf1a04c5e73225a897588d', '6c5cc6c7f1404afc884e8365fa1445391eea93b4893f876e879e7e5a9a15a375'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SettingsModels.kt': ('f90f17ef5ada3cc9a4c6616a620042c8491467cf6c16b50ac1cf0762516ea512', '20052d2ddd195067fdccd771d79406c778d21155a0bb4abd5c56e0116b0225cc'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SettingsScreen.kt': ('3928c11fefc4fc950d939b42c44e5fe0684434f084d15eae083e0f7b6ac4ba2b', 'd5d042eddfae34d53281a0145ea619f1a9957d11039921e17081ea62db52f89c'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SettingsViewModel.kt': ('22829d9b67db52502e5c36cb2cafc3351d2c4d39c664cc0019fb16e96ace3a04', '78500fc12db4c49d0e46c20940e3a92a92d983352780e4a2198c525246253bb5'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/SupportInboxScreen.kt': ('be3a348b87edf2319f5a0060b7508a248081cb6fc35aab9515729178306ce140', 'e39bf2afaecee9ba0c971932d42a9081be3149ddc96e7ef910c8f782f1cb83ed'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftScreen.kt': ('a4b2a8344019d1e134c8ee649fe597adbe4d7c8ac8dabaf7eb607925cfac6900', '33cb2c25c125cb39bc4b3b5b06acaf05c8ba2a38ab2a83aff48fb29b33a8be98'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/staff/StaffScreen.kt': ('f3e7e2c3738af0554a7709738fd104975ff0b0dd457df4f9d55ddff02ca1d865', 'a18418cf4b8ef01d1f78962e00a3af0a48a1d992301a0bbcab41c93f611fadd7'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/tables/TablesScreen.kt': ('57cd0e90490e5adb5652338b38df5e31f5bf3c9e94a73b4705fc25942ac849cf', 'd136e69b2b0a857f9401c613b9931dfc74405e5f0d11d5e52a11d0b361966ba6'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/theme/AppearanceStore.kt': (None, 'fbea0d06d70b304da0efe4514e74e82b78e84d335ea6e09462062a86aa321444'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/theme/Theme.kt': ('af8ae871add96e618922389178925c1136c0782a46865ab9f5ab210bbf0a35ce', 'bd2ce71654cd8e047929aece3bf6edb9e8e9093614effb74edc0e7ca6d612326'),
    'backend/alembic/versions/0087_versioned_loyalty_program.py': (None, '68c77313226ad21166c9e0c5ad4fa7346bc53298bdf00a507df9fc77c66d0182'),
    'backend/app/__init__.py': ('2c72f9e68ac9e6c8c08e2c42fd7cd3ddd0875d091d0c5b478b1acdef95e88d64', '43d8580bf3b3f2e8b7c3c4a1fe6e7daaf33ed37a13dd48b8f18655b7f1eb8fdb'),
    'backend/app/api/v1/customers/router.py': ('a4d0d967b74d8c6099772f8ef96c2bae443de7702f3a9b011b5b0df3ffa70747', 'bf3e7f194c09bc0c77fb0bfba5c1e1b09778425337efdee670850a7b823d26d7'),
    'backend/app/api/v1/gaming/router.py': ('c252e3bdb83b71c993245b55043822a5dafdb8ab4b34c86ba76ef988b1a28481', '41810dd8033c217c73e74274219e7a8f2cc8df698edd37e111a947cfa8e79d97'),
    'backend/app/api/v1/pos/router.py': ('8d5e2932bd8ec9973d42257de243467757c40767a5d4e27a1925797b4990f907', 'd40869c7f846894841aef36e2b9d6e2712303c4e6034a914dcee9ee3f163f36f'),
    'backend/app/api/v1/settings/router.py': ('95b5dd0b65b6ee41e0c253ee80477452a19651698914c6f5660ef271c2a5e205', 'd00bcb6eed339387cc9eca2d321cd68b1ff053344cff03fcae18fc42a0b020fc'),
    'backend/app/models/__init__.py': ('d5270d7c9673bab020a8d48d0b350a6729a877d80ea2191325ef6b6a6c9e0660', '3815112b63d4a7295857b5b3bbe5c7b6efc107ea06d716162c1778b5c3e0eeed'),
    'backend/app/models/customer.py': ('25d8b7f8867eccf67b020403b17ff43af081399948bc21b2ce3e33962f443a83', '12da87050a96bedd95e98f01aed0153bf2d9ad619c59a7dd9601f34bb066e17f'),
    'backend/app/services/customers/identity.py': ('61bf633142010cf773a633fcf7095daf52eab795360d1a93985fe1c9596813a7', 'bf94c62726f429f136ceacc18cb435092401dc98c3a38b17285eaf9a2bdb4d95'),
    'backend/app/services/pos/loyalty_program.py': (None, '56fdf4ca1fc8830bbff31f226240ec1739bd8a41ac6c39969f86170cc4de0fdf'),
    'backend/app/services/pos/points.py': ('c098a827d4839bb8d50c28fb7c5460a186814fa2be9b16a8a8160ff1c751fd83', '4e0307afe27b6823c2f338e8057cd4890ac599cff48a8075b5a2e98c208cd368'),
    'backend/pyproject.toml': ('59cd0704990c4d89832fb2578756bcc9304cd4355f43b0c9a23406a876addc54', 'ef6911dd308f38126ae2f678831f805c2e2dc90580f700acbd8a398c782a418b'),
    'backend/scripts/seed.py': ('da9368ef46edeb24786dad18ae7c276ca8c6a913b66fdbeb7ee73359fadae95c', 'f2d756d9fdc6f0d71cc2e55d0dadd04f097199d1b042454ba875b70dbff469bb'),
    'backend/tests/conftest.py': ('c007253ac3d38e7310d7e283626a918a2bce6ff5d8e9f48471c17982680646c5', '3cf97d5ab5833567e55b93c37d32864aef04e205a7fe7f912363a7f5df8f8f0a'),
    'backend/tests/integration/test_cafe_workflow_postgres.py': ('267168d673ec0d1348f71ff14acbc731522c7f3f9cd281fb4be902742dcb4e24', 'b455ce18c06df1b0534f20c9dc96be62d2bee25ac44dc765ad7c280a45c3f1ce'),
    'backend/tests/integration/test_customer_playtime_draft.py': ('ecd27f14d7a1b384733fb611949bfee8dd3c699b8c2ed2c70efebce5e6b320d4', '934da2a41ba40b8e5643c36c731935fffeb51fec1c39eae3d274fcdd48edc92a'),
    'backend/tests/integration/test_gaming_session_participants.py': ('f57ced695a54e08b72dbb47d4e8d90efa4738c823b00a159b7e550e8652f3038', '266cd8ff870cd8fca9acb945a018900573aeca80f1dda763608b70bb6b63ec35'),
    'backend/tests/integration/test_loyalty_program_settings.py': (None, 'c3016d9ad97466199103432e4480d55aabb5f6fcd6464e6d61d16ec0706bffce'),
    'backend/tests/integration/test_points_reservation_balance.py': ('ef4bd05349fde10b2e1c14bc2b968e388f9a76318bceaad8e471f6a9912118a1', '688810d6ffc526e9ad2693f3f593b33a6f80948a665744c9c6b137adbdf9e588'),
    'backend/tests/integration/test_pos_receipt_history.py': ('a834235bc479dd82fef7aa785de073cc47c2b43903c39ef2e3aa713ea0eb93ef', 'fe7d02fdd964803baa875e650f6a20e5ad31963434048cec1e3449964efa7bef'),
    'backend/tests/integration/test_pos_refund_settlement.py': ('212dfd56951fc9c980d5a0b20bf19e33c68219512d2acf23f7ace699fc51fdec', 'bfa4f8225606d8228a7561f9bc92e33a5470cb1c30d8b400ff45edc037cdf100'),
    'backend/tests/integration/test_pos_split_payment_bundle.py': ('e94a2b59deaca284ee3c382ffbb62a18dadaf6591639966333d5026ff51820c0', '7946e3f46c33b80a8789f1c2abd106b459749ab55ed6f308f210ed6e797b4f01'),
    'backend/tests/integration/test_production_seed_acceptance.py': ('ea7cca2c614df9b07af49a84237a17230c4267836b987798f94a18f4d6faa5df', '5c5baee4767c847eedf5c1e6573f24f91a9f343a3e17986e5eada96d36ab8307'),
    'backend/tests/unit/test_checkout_claims.py': ('40f07f0965bb6ad2fa201348ca1e6be6a2cd8ec13e8ac07c457d548be73ccf36', 'f94a75d5e3c470fdd17ff027de9855d9641c3b7093e4f9a3b005525f4c551a76'),
    'backend/tests/unit/test_customers_router_upsert_race.py': ('54347746c08f9a542d12935dd947ff4edd58baf131bffdebd6154e2eb921811e', '59db7762897c384d22a374bd820b23e671481858aba3f4d79a8209a2251a3e1a'),
    'backend/tests/unit/test_loyalty_program.py': (None, 'f661ee5d9c88ea1aa8f77e2b4e078a1f2f2a3c98fdf0e765aca923af122a1220'),
    'backend/tests/unit/test_membership_benefits.py': ('151b40c9fe0feb4518dd381ac380bc828dd8c0a3ef03adf28cf12972ccd8d71b', 'd35dfb6de753f841da9d2f1eb4160183326a366d31dd8e3b390bb531f8bb9578'),
    'backend/tests/unit/test_operational_route_integrity.py': ('c201cb29e356a0e3d9c984009c696833441a2e3bf6eef77356441bc0e87f8576', '78f1ff4b43aa396a9af829a2884240b48f8f75cf531a80d48c57fd2683fe3b93'),
    'backend/tests/unit/test_release_contracts.py': ('5db99562b3084de52931017cd2b947b0dfe4949966f1668fc770e2d1ec2b7df4', 'c851496b1f8c5796d33603e2ddaf0b9522f68d1569fdbc3a3c294b25df90129f'),
    'backend/tests/unit/test_remote_assistance_contract.py': ('3910a435cd581e89f2a9e2b9f3540e63bacb598f236fc57dbe67cbfbe2019073', '39412e833b03c77e296e54d91601816eee91fccf10f38d7aa12dc36d4debb9fa'),
    'docker-compose.prod.yml': ('325efdc85c95e7409e2f23d4ae565be0993fa575a582e694144948d363e42ee6', '9d074fbcefdcce0192ae837515f91c681bbb2fbf874cfc16f65bf13e36e4ce97'),
    'infra/scripts/install-on-vm.sh': ('b0cdc19b6e8dde2c52c08e9f78e286a0b13db73e0ee10973b75fa9ab97d49ffd', 'db0e152890909c81591404f609d5196b418ba4d848ce76f8404d07ac81cdcfe8'),
}


def _load_historical(candidate: Path) -> types.ModuleType:
    path = candidate / HISTORICAL_HELPER
    if hashlib.sha256(path.read_bytes()).hexdigest() != HISTORICAL_HELPER_SHA256:
        raise CompatibilityError("historical build45 verifier changed")
    module = types.ModuleType("immutable_build45_pending_compatibility")
    module.__file__ = str(path)
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    module.PRIOR_REVISION = PRIOR_REVISION
    module.PRIOR_VERSION = PRIOR_VERSION
    module.PRIOR_DATABASE_HEAD = PRIOR_DATABASE_HEAD
    module.CANDIDATE_VERSION = CANDIDATE_VERSION
    module.CANDIDATE_DATABASE_HEAD = CANDIDATE_DATABASE_HEAD
    module.EXPECTED_INSTALLATION_ROWS = EXPECTED_INSTALLATION_ROWS
    module.EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256 = (
        EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256
    )
    return module


def _inventory(root: Path, scopes: tuple[str, ...]) -> dict[str, str]:
    if root.is_symlink() or not root.is_dir():
        raise CompatibilityError("source root must be a real directory")
    files: dict[str, str] = {}
    for scope in scopes:
        path = root / scope
        scope_parts = Path(scope).parts
        if any(
            root.joinpath(*scope_parts[:index]).is_symlink()
            for index in range(1, len(scope_parts) + 1)
        ):
            raise CompatibilityError("linked protected source path")
        if not path.exists() and not path.is_symlink():
            continue
        if path.is_symlink():
            raise CompatibilityError("linked protected source path")
        if path.is_dir():
            found = False
            for directory, directories, names in os.walk(path, followlinks=False):
                current = Path(directory)
                for name in directories:
                    item = current / name
                    if item.is_symlink() or not item.is_dir():
                        raise CompatibilityError("protected source contains a linked or non-directory entry")
                for name in names:
                    item = current / name
                    if item.is_symlink() or not item.is_file():
                        raise CompatibilityError("protected source contains a linked or non-regular entry")
                    relative = item.relative_to(root).as_posix()
                    if relative in files:
                        raise CompatibilityError("protected source scopes overlap")
                    files[relative] = hashlib.sha256(item.read_bytes()).hexdigest()
                    found = True
            if not found:
                raise CompatibilityError("empty protected source scope")
        elif path.is_file():
            if scope in files:
                raise CompatibilityError("protected source scopes overlap")
            files[scope] = hashlib.sha256(path.read_bytes()).hexdigest()
        else:
            raise CompatibilityError("protected source contains a non-regular entry")
    return files


def _inventory_sha256(files: dict[str, str]) -> str:
    return hashlib.sha256(
        "".join(f"{path}\0{digest}\n" for path, digest in sorted(files.items())).encode()
    ).hexdigest()


def _changes(before: dict[str, str], after: dict[str, str]) -> dict[str, tuple[str | None, str | None]]:
    return {
        path: (before.get(path), after.get(path))
        for path in sorted(before.keys() | after.keys())
        if before.get(path) != after.get(path)
    }


def _verify_sources(prior: Path, candidate: Path, **overrides) -> dict[str, int | str]:
    scopes = overrides.get("scopes", PROTECTED_SCOPES)
    before = _inventory(prior, scopes)
    after = _inventory(candidate, scopes)
    before_digest = overrides.get("prior_sha256", PRIOR_SOURCE_SHA256)
    after_digest = overrides.get("candidate_sha256", CANDIDATE_SOURCE_SHA256)
    changes = overrides.get("reviewed_changes", REVIEWED_CHANGES)
    if _inventory_sha256(before) != before_digest:
        raise CompatibilityError("prior source is not immutable build45")
    if _changes(before, after) != changes:
        raise CompatibilityError("candidate source has unreviewed runtime bytes or paths")
    if _inventory_sha256(after) != after_digest:
        raise CompatibilityError("candidate protected source digest changed")
    return {
        "prior_protected_source_sha256": before_digest,
        "candidate_protected_source_sha256": after_digest,
        "protected_path_count": len(after),
        "reviewed_changed_path_count": len(changes),
    }


def _backend_manifest_sha256(root: Path) -> str:
    files = _inventory(root, ("backend",))
    manifest = "".join(
        f"{digest}\t{path.removeprefix('backend/')}\n" for path, digest in sorted(files.items())
    ).encode()
    return hashlib.sha256(manifest).hexdigest()


def _validate_identity(**values: str) -> None:
    expected = (
        PRIOR_REVISION,
        PRIOR_VERSION,
        PRIOR_DATABASE_HEAD,
        CANDIDATE_VERSION,
        CANDIDATE_DATABASE_HEAD,
    )
    actual = tuple(values[key] for key in (
        "prior_revision", "prior_version", "prior_db_head", "candidate_version", "candidate_db_head"
    ))
    if actual != expected:
        raise CompatibilityError("unsupported release or database transition")
    revision = values["candidate_revision"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision) or revision == PRIOR_REVISION:
        raise CompatibilityError("candidate requires a distinct full revision")
    for key in ("prior_image_id", "candidate_image_id"):
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", values[key]):
            raise CompatibilityError("backend image requires an immutable image ID")
    if values["prior_image_id"] == values["candidate_image_id"]:
        raise CompatibilityError("prior and candidate images must be distinct")
    if values["prior_backend_manifest_sha256"] != PRIOR_BACKEND_MANIFEST_SHA256:
        raise CompatibilityError("prior backend manifest changed")
    if values["candidate_backend_manifest_sha256"] != CANDIDATE_BACKEND_MANIFEST_SHA256:
        raise CompatibilityError("candidate backend manifest changed")
    if values["prior_source_archive_sha256"] != PRIOR_SOURCE_ARCHIVE_SHA256:
        raise CompatibilityError("prior source archive changed")
    candidate_archive = values["candidate_source_archive_sha256"]
    if not re.fullmatch(r"[0-9a-f]{64}", candidate_archive) or candidate_archive == PRIOR_SOURCE_ARCHIVE_SHA256:
        raise CompatibilityError("candidate source archive attestation is invalid")


def _verify_effective_environment(
    historical, prior_doc: object, candidate_doc: object, compose_doc: object,
    prior_image: str, candidate_image: str, candidate_revision: str,
) -> int:
    before = historical._docker_environment(prior_doc, prior_image)
    after = historical._docker_environment(candidate_doc, candidate_image)
    try:
        backend = compose_doc["services"]["backend"]
        overrides = backend["environment"]
    except (KeyError, TypeError) as exc:
        raise CompatibilityError("candidate Compose backend environment is missing") from exc
    if backend.get("image") != f"d-company-erp-backend:{candidate_revision}" or "env_file" in backend or not isinstance(overrides, dict):
        raise CompatibilityError("candidate Compose environment source is unexpected")
    if {"PATH", "PYTHON_VERSION", "PYTHON_SHA256"} & overrides.keys():
        raise CompatibilityError("candidate Compose masks image metadata")
    if not all(isinstance(k, str) and isinstance(v, str) for k, v in overrides.items()):
        raise CompatibilityError("candidate Compose environment must contain strings")
    after.update(overrides)
    for env, version, revision in (
        (before, PRIOR_VERSION, PRIOR_REVISION),
        (after, CANDIDATE_VERSION, candidate_revision),
    ):
        if env.pop("APP_VERSION", None) != version or env.pop("APP_REVISION", None) != revision:
            raise CompatibilityError("effective runtime identity changed")
    expected_python = "3.14.8"
    expected_python_sha = "c2215904f02b175596dc49351585104f4bc20341e1c47378b26a2c274360ce73"
    for env in (before, after):
        if env.pop("PYTHON_VERSION", None) != expected_python or env.pop("PYTHON_SHA256", None) != expected_python_sha:
            raise CompatibilityError("reviewed Python image metadata changed")
    if before != after:
        raise CompatibilityError("effective backend environment changed")
    return len(before) + 4


def _ordered(source: str, *tokens: str) -> bool:
    position = -1
    for token in tokens:
        position = source.find(token, position + 1)
        if position < 0:
            return False
    return True


def _verify_loyalty_compatibility(candidate: Path) -> dict[str, object]:
    program = (candidate / "backend/app/services/pos/loyalty_program.py").read_text()
    router = (candidate / "backend/app/api/v1/pos/router.py").read_text()
    migration = (candidate / "backend/alembic/versions/0087_versioned_loyalty_program.py").read_text()
    points = (candidate / "backend/app/services/pos/points.py").read_text()
    if not _ordered(program, "if expected is None and program.revision == 0:", "if expected is None:", "if expected != program.revision:"):
        raise CompatibilityError("revisionless loyalty compatibility changed")
    direct = router[router.find("async def redeem_points("):router.find("async def redeem_reward(")]
    reward = router[router.find("async def redeem_reward("):router.find("async def ", router.find("async def redeem_reward(") + 10)]
    for block in (direct, reward):
        if not _ordered(block, "check_or_reserve(", "if existing_response:", "require_expected_revision("):
            raise CompatibilityError("saved redemption replay no longer precedes revision validation")
    required_migration = (
        "redemption_kind='legacy_frozen'",
        "accepted_points_cost=points_spent",
        "accepted_value_minor=amount_minor",
        "current_revision > 0 AND NEW.program_revision IS NULL",
    )
    if not all(token in migration for token in required_migration):
        raise CompatibilityError("legacy loyalty provenance fence changed")
    if not all(token in points for token in ("legacy_frozen", "accepted_points_cost", "accepted_value_minor")):
        raise CompatibilityError("accepted loyalty economics changed")
    return {
        "production_program_revision_required": 0,
        "revisionless_fresh_requests_after_customization": "refused",
        "saved_idempotent_replay_precedes_revision_validation": True,
        "accepted_legacy_settlement_and_refund_economics_preserved": True,
    }


def _private_file(path: Path) -> None:
    if path.is_symlink() or not path.is_file() or path.stat().st_uid != 0 or stat.S_IMODE(path.stat().st_mode) != 0o600:
        raise CompatibilityError("environment/evidence must be root-owned mode 0600")


def _private_root(path: Path) -> None:
    if path.is_symlink() or not path.is_dir() or path.stat().st_uid != 0 or stat.S_IMODE(path.stat().st_mode) != 0o700:
        raise CompatibilityError("source archive root must be root-owned mode 0700")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("prior-source-root", "candidate-source-root", "raw-historical-state", "raw-fleet-state", "prior-env", "live-env", "candidate-env", "prior-runtime-env", "candidate-image-env", "candidate-compose-config"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    for name in ("prior-revision", "prior-version", "prior-db-head", "candidate-revision", "candidate-version", "candidate-db-head", "prior-image-id", "candidate-image-id", "prior-backend-manifest-sha256", "candidate-backend-manifest-sha256", "prior-source-archive-sha256", "candidate-source-archive-sha256"):
        parser.add_argument(f"--{name}", required=True)
    args = parser.parse_args()
    try:
        identity = {name.replace("-", "_"): getattr(args, name.replace("-", "_")) for name in ("prior-revision", "prior-version", "prior-db-head", "candidate-revision", "candidate-version", "candidate-db-head", "prior-image-id", "candidate-image-id", "prior-backend-manifest-sha256", "candidate-backend-manifest-sha256", "prior-source-archive-sha256", "candidate-source-archive-sha256")}
        _validate_identity(**identity)
        for root in (args.prior_source_root, args.candidate_source_root):
            _private_root(root)
        if args.prior_source_root == args.candidate_source_root or Path(__file__).resolve().parents[2] != args.candidate_source_root.resolve():
            raise CompatibilityError("source archive binding is invalid")
        historical = _load_historical(args.candidate_source_root)
        source_proof = _verify_sources(args.prior_source_root, args.candidate_source_root)
        if _backend_manifest_sha256(args.prior_source_root) != args.prior_backend_manifest_sha256 or _backend_manifest_sha256(args.candidate_source_root) != args.candidate_backend_manifest_sha256:
            raise CompatibilityError("backend source manifest is not canonical")
        evidence_paths = (args.raw_historical_state, args.raw_fleet_state, args.prior_env, args.live_env, args.candidate_env, args.prior_runtime_env, args.candidate_image_env, args.candidate_compose_config)
        for path in evidence_paths:
            _private_file(path)
        history_raw = args.raw_historical_state.read_bytes()
        fleet_raw = args.raw_fleet_state.read_bytes()
        history = historical._verify_scoped_history(historical._decode_json(history_raw, historical._load_historical_verifier(args.candidate_source_root), label="historical state"), historical._load_historical_verifier(args.candidate_source_root))
        fleet = historical._verify_fleet(historical._decode_json(fleet_raw, historical._load_historical_verifier(args.candidate_source_root), label="fleet state"), historical._load_historical_verifier(args.candidate_source_root))
        historical._verify_environment(args.prior_env.read_bytes(), args.live_env.read_bytes(), args.candidate_env.read_bytes(), args.candidate_revision)
        effective_count = _verify_effective_environment(historical, historical._json_document(args.prior_runtime_env.read_bytes()), historical._json_document(args.candidate_image_env.read_bytes()), historical._json_document(args.candidate_compose_config.read_bytes()), args.prior_image_id, args.candidate_image_id, args.candidate_revision)
        loyalty = _verify_loyalty_compatibility(args.candidate_source_root)
        report = {
            "schema_version": 1,
            "status": "pending_carried_unresolved",
            "transition": identity,
            "source_proof": source_proof,
            "historical_cleanup_proof": history,
            "fleet": fleet,
            "loyalty_compatibility": loyalty,
            "raw_historical_state_sha256": hashlib.sha256(history_raw).hexdigest(),
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
    except (CompatibilityError, OSError, ValueError) as exc:
        print(f"build46 compatibility refused: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
