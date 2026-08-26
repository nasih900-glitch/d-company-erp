from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.verify_android_release_version import (
    AndroidVersion,
    ReleaseVersionError,
    read_gradle_version,
    validate_built_metadata,
    validate_production_defaults,
    validate_tag,
)


class AndroidReleaseVersionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)

    def write_build_file(
        self, version_code: str = "7", version_name: str = '"3.1.0"'
    ) -> Path:
        build_file = self.root / "build.gradle.kts"
        build_file.write_text(
            "android {\n"
            "    defaultConfig {\n"
            f"        versionCode = {version_code}\n"
            f"        versionName = {version_name}\n"
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        return build_file

    def write_metadata(
        self, version_code: object = 7, version_name: object = "3.1.0"
    ) -> Path:
        metadata_file = self.root / "output-metadata.json"
        metadata_file.write_text(
            json.dumps(
                {
                    "applicationId": "cloud.dcompany.erp",
                    "variantName": "release",
                    "elements": [
                        {"versionCode": version_code, "versionName": version_name}
                    ],
                }
            ),
            encoding="utf-8",
        )
        return metadata_file

    def test_matching_tag_and_positive_version_code_pass(self) -> None:
        version = read_gradle_version(self.write_build_file())

        validate_tag("v3.1.0", version)
        validate_built_metadata(self.write_metadata(), version)

        self.assertEqual(AndroidVersion(code=7, name="3.1.0"), version)

    def test_tag_must_match_version_name_exactly(self) -> None:
        version = read_gradle_version(self.write_build_file())

        with self.assertRaisesRegex(ReleaseVersionError, "expected 'v3.1.0'"):
            validate_tag("v3.1.1", version)

    def test_tag_must_use_release_format(self) -> None:
        version = read_gradle_version(self.write_build_file())

        with self.assertRaisesRegex(ReleaseVersionError, "v<version>"):
            validate_tag("release-3.1.0", version)

    def test_version_code_must_be_direct_positive_integer(self) -> None:
        for invalid in ("0", "-1", '"7"', "releaseVersionCode"):
            with self.subTest(invalid=invalid):
                with self.assertRaisesRegex(ReleaseVersionError, "positive integer"):
                    read_gradle_version(self.write_build_file(version_code=invalid))

    def test_ambiguous_version_assignment_fails_closed(self) -> None:
        build_file = self.write_build_file()
        build_file.write_text(
            build_file.read_text(encoding="utf-8") + "versionCode = 8\n",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ReleaseVersionError, "exactly one versionCode"):
            read_gradle_version(build_file)

    def test_commented_assignments_are_ignored(self) -> None:
        build_file = self.write_build_file()
        build_file.write_text(
            "// versionCode = 99\n"
            '/* versionName = "99.0" */\n' + build_file.read_text(encoding="utf-8"),
            encoding="utf-8",
        )

        self.assertEqual(
            AndroidVersion(code=7, name="3.1.0"),
            read_gradle_version(build_file),
        )

    def test_built_metadata_must_match_source(self) -> None:
        version = read_gradle_version(self.write_build_file())

        with self.assertRaisesRegex(ReleaseVersionError, "does not match"):
            validate_built_metadata(
                self.write_metadata(version_code=8, version_name="3.1.0"),
                version,
            )

    def test_built_metadata_version_code_must_be_integer(self) -> None:
        version = read_gradle_version(self.write_build_file())

        with self.assertRaisesRegex(ReleaseVersionError, "positive integer"):
            validate_built_metadata(
                self.write_metadata(version_code="7", version_name="3.1.0"),
                version,
            )

    def write_production_defaults(
        self,
        env_code: int = 7,
        compose_code: int = 7,
    ) -> tuple[Path, Path]:
        env_file = self.root / ".env.production.example"
        env_file.write_text(
            f"ANDROID_LATEST_VERSION_CODE={env_code}\n",
            encoding="utf-8",
        )
        compose_file = self.root / "docker-compose.prod.yml"
        compose_file.write_text(
            "services:\n"
            "  backend:\n"
            "    environment:\n"
            "      ANDROID_LATEST_VERSION_CODE: "
            f"${{ANDROID_LATEST_VERSION_CODE:-{compose_code}}}\n",
            encoding="utf-8",
        )
        return env_file, compose_file

    def test_production_defaults_match_version_code(self) -> None:
        env_file, compose_file = self.write_production_defaults()

        validate_production_defaults(
            env_file,
            compose_file,
            AndroidVersion(code=7, name="3.1.0"),
        )

    def test_production_defaults_fail_when_compose_is_stale(self) -> None:
        env_file, compose_file = self.write_production_defaults(compose_code=6)

        with self.assertRaisesRegex(ReleaseVersionError, "env=7, compose=6"):
            validate_production_defaults(
                env_file,
                compose_file,
                AndroidVersion(code=7, name="3.1.0"),
            )


if __name__ == "__main__":
    unittest.main()
