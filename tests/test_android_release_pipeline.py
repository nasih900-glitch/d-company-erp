from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
MAIN_MANIFEST = ROOT / "android-native" / "app" / "src" / "main" / "AndroidManifest.xml"
DIRECT_MANIFEST = (
    ROOT / "android-native" / "app" / "src" / "directRelease" / "AndroidManifest.xml"
)
INSTALL_PERMISSION = "android.permission.REQUEST_INSTALL_PACKAGES"
CADDYFILE = ROOT / "infra" / "caddy" / "Caddyfile"
PROD_COMPOSE = ROOT / "docker-compose.prod.yml"
GRADLE_WRAPPER = (
    ROOT / "android-native" / "gradle" / "wrapper" / "gradle-wrapper.properties"
)
ANDROID_APPLICATION = (
    ROOT
    / "android-native"
    / "app"
    / "src"
    / "main"
    / "java"
    / "cloud"
    / "dcompany"
    / "erp"
    / "DCompanyApp.kt"
)


class AndroidReleasePipelineTest(unittest.TestCase):
    def test_required_update_is_restored_and_persisted_at_application_startup(
        self,
    ) -> None:
        application = ANDROID_APPLICATION.read_text(encoding="utf-8")

        store_creation = application.index("ClientUpdateRequirementStore(")
        gate_creation = application.index("ClientCompatibilityGate(")
        database_creation = application.index("Room.databaseBuilder(")
        self.assertLess(store_creation, gate_creation)
        self.assertLess(gate_creation, database_creation)
        self.assertIn(
            "initialRequiredNotice = updateRequirementStore.restore()", application
        )
        self.assertIn("updateRequirementStore.persist(notice)", application)

    def test_gradle_release_toolchain_is_checksum_pinned(self) -> None:
        wrapper = GRADLE_WRAPPER.read_text(encoding="utf-8")

        self.assertIn(
            "distributionUrl=https\\://services.gradle.org/distributions/", wrapper
        )
        checksum_lines = [
            line
            for line in wrapper.splitlines()
            if line.startswith("distributionSha256Sum=")
        ]
        self.assertEqual(1, len(checksum_lines))
        self.assertRegex(checksum_lines[0].split("=", 1)[1], r"^[0-9a-f]{64}$")

    def test_play_and_direct_variants_are_built_linted_and_unit_tested(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        for task in (
            "lintRelease",
            "assembleRelease",
            "bundleRelease",
            "testReleaseUnitTest",
            "lintDirectRelease",
            "assembleDirectRelease",
            "testDirectReleaseUnitTest",
        ):
            with self.subTest(task=task):
                self.assertIn(task, workflow)

    def test_only_the_direct_source_set_requests_installer_permission(self) -> None:
        self.assertNotIn(INSTALL_PERMISSION, MAIN_MANIFEST.read_text(encoding="utf-8"))
        self.assertIn(INSTALL_PERMISSION, DIRECT_MANIFEST.read_text(encoding="utf-8"))

    def test_release_packages_the_direct_apk_and_ordinary_play_aab(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        self.assertIn(
            'cp "$direct_unsigned" "$package_dir/direct-unsigned.apk"',
            workflow,
        )
        self.assertIn(
            'sign_apk unsigned/direct-unsigned.apk '
            '"$signed_dir/app-directRelease.apk"',
            workflow,
        )
        self.assertIn(
            'unsigned/play-unsigned.aab',
            workflow,
        )
        self.assertIn("d-company-erp-${safe_release_ref}-direct.apk", workflow)
        self.assertIn("d-company-erp-${safe_release_ref}-play.aab", workflow)
        self.assertIn("build_android_release_manifest.py", workflow)
        self.assertNotIn(
            'cp "$play_apk" "$package_dir/d-company-erp-',
            workflow,
        )

    def test_workflow_fails_closed_on_permission_or_signer_drift(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        self.assertIn(
            "apkanalyzer manifest permissions unsigned/play-unsigned.apk", workflow
        )
        self.assertIn(
            "apkanalyzer manifest permissions unsigned/direct-unsigned.apk", workflow
        )
        self.assertIn(
            'grep -Fq \'android:name="android.permission.REQUEST_INSTALL_PACKAGES"\' '
            "unsigned/play-AndroidManifest.xml",
            workflow,
        )
        self.assertIn(
            'grep -Fq \'android:name="android.permission.REQUEST_INSTALL_PACKAGES"\' '
            "unsigned/direct-AndroidManifest.xml",
            workflow,
        )
        self.assertIn('test "$play_signer_sha256" = "$direct_signer_sha256"', workflow)
        self.assertIn('test "$play_signer_sha256" = "$aab_signer_sha256"', workflow)
        self.assertIn('-printcert -rfc -jarfile "$aab"', workflow)
        self.assertIn("Play release/AAB merged manifest unexpectedly", workflow)

    def test_signed_apk_versions_must_match_tag_verified_metadata(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        sign_start = workflow.index("  sign-android:")
        release_start = workflow.index("  release:")
        sign_job = workflow[sign_start:release_start]

        self.assertIn('signed_dir="$RUNNER_TEMP/signed-android"', sign_job)
        self.assertIn(
            'play_version_code="$(apkanalyzer manifest version-code "$play_apk")"',
            sign_job,
        )
        self.assertIn(
            'direct_version_code="$(apkanalyzer manifest version-code "$direct_apk")"',
            sign_job,
        )
        self.assertIn('readarray -t expected_version', workflow)
        self.assertIn(
            'test "$play_version_code" = "$expected_version_code"', workflow
        )
        self.assertIn(
            'test "$direct_version_code" = "$expected_version_code"', workflow
        )
        self.assertIn(
            'test "$play_version_name" = "$expected_version_name"', workflow
        )
        self.assertIn(
            'test "$direct_version_name" = "$expected_version_name"', workflow
        )

    def test_third_party_emulator_actions_are_isolated_from_signed_build(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        instrumentation_start = workflow.index("  android-instrumentation:")
        build_start = workflow.index("  build-android:")
        sign_start = workflow.index("  sign-android:")
        release_start = workflow.index("  release:")
        instrumentation_job = workflow[instrumentation_start:build_start]
        build_job = workflow[build_start:sign_start]
        sign_job = workflow[sign_start:release_start]
        release_job = workflow[release_start:]

        self.assertIn("reactivecircus/android-emulator-runner@", instrumentation_job)
        self.assertNotIn("ANDROID_KEYSTORE_BASE64", instrumentation_job)
        self.assertNotIn("cache: gradle", instrumentation_job)
        self.assertIn(
            "needs: [coordinated-release-gates, android-instrumentation]",
            build_job,
        )
        self.assertNotIn("reactivecircus/android-emulator-runner@", build_job)
        self.assertNotIn("android-actions/setup-android@", build_job)
        self.assertNotIn("cache: gradle", build_job)
        self.assertNotIn("ANDROID_KEYSTORE_BASE64", build_job)
        self.assertNotIn("ANDROID_KEYSTORE_PASSWORD", build_job)
        self.assertIn("needs: build-android", sign_job)
        self.assertNotIn("./gradlew", sign_job)
        self.assertNotIn("reactivecircus/android-emulator-runner@", sign_job)
        self.assertNotIn("android-actions/setup-android@", sign_job)
        self.assertNotIn("cache: gradle", sign_job)
        self.assertIn(
            "Remove Android signing material before artifact verification",
            sign_job,
        )
        self.assertIn("Remove Android signing material (final cleanup)", sign_job)
        self.assertIn("permissions:\n  contents: read", workflow)
        self.assertIn("permissions: { contents: write }", workflow)
        self.assertIn(
            "android-actions/setup-android@40fd30fb8d7440372e1316f5d1809ec01dcd3699",
            workflow,
        )
        self.assertIn(
            "reactivecircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d",
            workflow,
        )
        self.assertNotIn("softprops/action-gh-release@", release_job)
        self.assertIn('gh release create "$GITHUB_REF_NAME"', release_job)
        self.assertIn("--draft", release_job)
        allowed_build_actions = {
            "actions/checkout",
            "actions/setup-java",
            "actions/upload-artifact",
        }
        build_actions = {
            action.split("@", 1)[0]
            for action in re.findall(
                r"^\s*- uses:\s+([^\s#]+)", build_job, re.MULTILINE
            )
        }
        self.assertEqual(allowed_build_actions, build_actions)
        allowed_sign_actions = {
            "actions/checkout",
            "actions/setup-java",
            "actions/download-artifact",
            "actions/upload-artifact",
        }
        sign_actions = {
            action.split("@", 1)[0]
            for action in re.findall(
                r"^\s*- uses:\s+([^\s#]+)", sign_job, re.MULTILINE
            )
        }
        self.assertEqual(allowed_sign_actions, sign_actions)
        self.assertEqual(
            {"actions/download-artifact"},
            {
                action.split("@", 1)[0]
                for action in re.findall(
                    r"^\s*- uses:\s+([^\s#]+)", release_job, re.MULTILINE
                )
            },
        )
        for floating_action in (
            "actions/checkout@v4",
            "actions/setup-java@v4",
            "android-actions/setup-android@v4",
            "reactivecircus/android-emulator-runner@v2",
            "actions/upload-artifact@v4",
            "actions/download-artifact@v4",
            "softprops/action-gh-release@v3",
        ):
            with self.subTest(floating_action=floating_action):
                self.assertNotIn(floating_action, workflow)

        for action in re.findall(r"^\s*- uses:\s+([^\s#]+)", workflow, re.MULTILINE):
            with self.subTest(action=action):
                self.assertRegex(action, r"@[0-9a-f]{40}$")

    def test_gradle_and_signing_material_are_isolated_between_jobs(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        build_start = workflow.index("  build-android:")
        sign_start = workflow.index("  sign-android:")
        release_start = workflow.index("  release:")
        build_job = workflow[build_start:sign_start]
        sign_job = workflow[sign_start:release_start]

        unsigned_build = build_job.index("Build unsigned Play and direct Android releases")
        package_unsigned = build_job.index(
            "Package unsigned artifacts for the isolated signer"
        )
        handoff = sign_job.index("Verify unsigned handoff and release authority")
        require_secrets = sign_job.index("Require Android signing secrets")
        decode_key = sign_job.index("Decode signing keystore on isolated runner")
        sign_archives = sign_job.index("Sign prebuilt APK and AAB artifacts")
        remove_key = sign_job.index(
            "Remove Android signing material before artifact verification"
        )
        inspect_archives = sign_job.index(
            "Verify signed artifacts and build release package"
        )

        self.assertLess(unsigned_build, package_unsigned)
        self.assertLess(handoff, require_secrets)
        self.assertLess(require_secrets, decode_key)
        self.assertLess(decode_key, sign_archives)
        self.assertLess(sign_archives, remove_key)
        self.assertLess(remove_key, inspect_archives)
        self.assertNotIn("ANDROID_KEYSTORE_BASE64", build_job)
        self.assertNotIn("ANDROID_KEYSTORE_PASSWORD", build_job)
        self.assertNotIn("ANDROID_KEY_ALIAS", build_job)
        self.assertNotIn("ANDROID_KEY_PASSWORD", build_job)
        self.assertNotIn("./gradlew", sign_job)
        self.assertIn("test ! -e android-native/dcompany-release.keystore", build_job)
        self.assertIn("sha256sum --check --strict UNSIGNED_SHA256SUMS", sign_job)
        self.assertIn("ANDROID_BUILD_TOOLS_VERSION: '35.0.0'", sign_job)
        self.assertIn(
            'build_tools="$ANDROID_SDK_ROOT/build-tools/'
            '$ANDROID_BUILD_TOOLS_VERSION"',
            sign_job,
        )
        self.assertNotIn("sort -V | tail -1", sign_job)
        self.assertIn('"$apksigner" sign', sign_job)
        self.assertIn('"$zipalign" -f -p 4', sign_job)
        self.assertIn("-storepass:env ANDROID_KEYSTORE_PASSWORD", sign_job)
        self.assertIn("-keypass:env ANDROID_KEY_PASSWORD", sign_job)
        self.assertNotIn("storePassword=", sign_job)
        self.assertNotIn("keyPassword=", sign_job)

    def test_release_signer_is_pinned_to_out_of_band_fingerprint(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        self.assertIn(
            "EXPECTED_ANDROID_SIGNER_SHA256: "
            "${{ vars.ANDROID_EXPECTED_SIGNER_SHA256 }}",
            workflow,
        )
        self.assertIn('test "$play_signer_sha256" = "$direct_signer_sha256"', workflow)
        self.assertIn('test "$play_signer_sha256" = "$aab_signer_sha256"', workflow)
        self.assertIn(
            'if [ "$keystore_signer_sha256" != "$expected_signer_sha256" ]',
            workflow,
        )
        self.assertIn('test "$play_signer_sha256" = "$expected_signer_sha256"', workflow)
        self.assertIn("ANDROID_EXPECTED_SIGNER_SHA256 must be a 64-hex", workflow)

    def test_release_assets_are_serialized_and_never_replaced_in_place(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        self.assertIn("group: release-android-${{ github.ref }}", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn("Refuse to replace an existing GitHub Release", workflow)
        self.assertIn('gh release view "$GITHUB_REF_NAME"', workflow)
        self.assertIn('gh release create "$GITHUB_REF_NAME"', workflow)
        self.assertIn("--verify-tag", workflow)
        self.assertIn("--draft", workflow)
        self.assertIn("sha256sum --check --strict SHA256SUMS", workflow)
        self.assertIn(
            'test "$(find "$package_dir" -mindepth 1 -maxdepth 1 -type f', workflow
        )

    def test_tag_release_rechecks_exact_backend_and_web_contracts(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        coordinated_start = workflow.index("  coordinated-release-gates:")
        instrumentation_start = workflow.index("  android-instrumentation:")
        coordinated_job = workflow[coordinated_start:instrumentation_start]

        self.assertIn("python -m pip install -r backend/requirements.lock", coordinated_job)
        self.assertNotIn("pip install -r backend/requirements.txt", coordinated_job)
        self.assertIn("python -m pip_audit -r backend/requirements.lock", coordinated_job)
        self.assertIn("run: alembic upgrade head", coordinated_job)
        self.assertIn("run: pytest", coordinated_job)
        self.assertIn("npm audit --omit=dev --audit-level=high", coordinated_job)
        for gate in ("npm run lint", "npm run typecheck", "npm run test", "npm run build"):
            with self.subTest(gate=gate):
                self.assertIn(gate, coordinated_job)

    def test_ci_token_is_read_only_and_actions_are_commit_pinned(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")

        self.assertIn("permissions:\n  contents: read", workflow)
        actions = re.findall(r"^\s*- uses:\s+([^\s#]+)", workflow, re.MULTILINE)
        self.assertTrue(actions)
        for action in actions:
            with self.subTest(action=action):
                self.assertRegex(action, r"@[0-9a-f]{40}$")

    def test_server_serves_only_versioned_apks_from_a_read_only_mount(self) -> None:
        caddy = CADDYFILE.read_text(encoding="utf-8")
        compose = PROD_COMPOSE.read_text(encoding="utf-8")
        ci_workflow = CI_WORKFLOW.read_text(encoding="utf-8")

        self.assertIn(
            r"^/downloads/android/[A-Za-z0-9][A-Za-z0-9._-]*\.apk$",
            caddy,
        )
        self.assertIn(
            'Cache-Control "public, max-age=31536000, immutable, no-transform"', caddy
        )
        self.assertIn('respond "Not found" 404', caddy)
        self.assertIn("./releases/android:/srv/releases/android:ro", compose)
        self.assertIn("caddy validate --config /etc/caddy/Caddyfile", ci_workflow)


if __name__ == "__main__":
    unittest.main()
