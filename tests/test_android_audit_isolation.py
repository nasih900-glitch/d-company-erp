"""Guard the synthetic device audit from affecting partner release builds."""
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]


class AndroidAuditIsolationTest(unittest.TestCase):
    def test_endpoint_override_is_only_used_by_physical_audit(self):
        source = (ROOT / "android-native/app/build.gradle.kts").read_text()
        audit_start = source.index('create("physicalAudit")')
        prefix = source[:audit_start]
        self.assertNotIn('buildConfigString(physicalAuditApiBaseUrl)', prefix)
        self.assertIn('buildConfigString(productionApiBaseUrl)', prefix)
        audit = source[audit_start:source.index("testBuildType =", audit_start)]
        self.assertIn('applicationIdSuffix = ".physicalaudit"', audit)
        self.assertIn('signingConfig = signingConfigs.getByName("debug")', audit)
        self.assertIn('"DIRECT_UPDATES_ENABLED", "false"', audit)

    def test_cloud_override_has_a_narrow_https_only_host_guard(self):
        source = (ROOT / "android-native/app/build.gradle.kts").read_text()
        for guard in (
            'physicalAuditApiUri?.scheme == "https"',
            'physicalAuditApiUri.host?.endsWith(".trycloudflare.com") == true',
            'physicalAuditApiUri.rawPath == "/api/v1/"',
            'physicalAuditApiUri.rawUserInfo == null',
            'physicalAuditApiUri.rawQuery == null',
            'physicalAuditApiUri.rawFragment == null',
            'physicalAuditApiUri.port == -1',
        ):
            self.assertIn(guard, source)

    def test_driver_cannot_target_the_partner_application(self):
        source = (ROOT / "android-native/audit-driver/src/androidTest/java/cloud/"
                  "dcompany/erp/auditdriver/BusinessWorkflowDeviceTest.kt").read_text()
        self.assertEqual(
            ["cloud.dcompany.erp.physicalaudit"],
            re.findall(r'private val appPackage = "([^"]+)"', source),
        )
        self.assertNotIn('getString("appPackage")', source)
        self.assertIn('cmd connectivity airplane-mode disable', source)
        self.assertIn('finally {', source)

    def test_driver_cleans_credentials_when_plan_or_fixture_validation_fails(self):
        source = (ROOT / "android-native/audit-driver/src/androidTest/java/cloud/"
                  "dcompany/erp/auditdriver/BusinessWorkflowDeviceTest.kt").read_text()
        workflow = source.index("private fun runBusinessWorkflow")
        outer_try = source.index("        try {", workflow)
        credential_validation = source.index("requireSafeInputPath(credentialPath)", outer_try)
        credential_assignment = source.index("validatedCredentialPath = credentialPath", outer_try)
        plan_validation = source.index("requireSafeInputPath(planPath)", outer_try)
        plan_read = source.index('JSONObject(device.executeShellCommand("cat $planPath"))', outer_try)
        fixture_check = source.index('require(credentials.optString("fixture")', outer_try)
        cleanup = source.index(
            'validatedCredentialPath?.let { device.executeShellCommand("rm $it") }',
            outer_try,
        )
        outer_finally = source.rindex("        } finally {", outer_try, cleanup)

        self.assertLess(credential_validation, credential_assignment)
        self.assertLess(credential_assignment, plan_validation)
        self.assertLess(outer_try, plan_read)
        self.assertLess(outer_try, fixture_check)
        self.assertLess(outer_finally, cleanup)
        self.assertIn("invalidPlanPathStillRemovesValidatedCredentialFile", source)

    def test_release_builds_target_only_the_partner_app_module(self):
        ci = (ROOT / ".github/workflows/ci.yml").read_text()
        release = (ROOT / ".github/workflows/release.yml").read_text()
        instrumentation = (ROOT / "scripts/run_android_instrumentation_ci.sh").read_text()
        readme = (ROOT / "android-native/README.md").read_text()

        for task in ("lintRelease", "assembleRelease", "bundleRelease", "testDebugUnitTest"):
            self.assertIn(f":app:{task}", ci)
        for task in ("assembleDebug", "assembleDebugAndroidTest"):
            self.assertIn(f":audit-driver:{task}", ci)
            self.assertNotIn(f":audit-driver:{task}", release)
        for task in (
            "lintRelease",
            "lintDirectRelease",
            "testReleaseUnitTest",
            "testDirectReleaseUnitTest",
            "assembleRelease",
            "bundleRelease",
            "assembleDirectRelease",
        ):
            self.assertIn(f":app:{task}", release)
        self.assertIn(":app:connectedDebugAndroidTest", instrumentation)
        self.assertNotIn(" --stacktrace connectedDebugAndroidTest", instrumentation)
        for task in (
            "testDebugUnitTest",
            "compileDebugKotlin",
            "compileDebugAndroidTestKotlin",
            "lintDebug",
            "assembleDebug",
            "connectedDebugAndroidTest",
        ):
            self.assertIn(f":app:{task}", readme)


if __name__ == "__main__":
    unittest.main()
