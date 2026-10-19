#!/usr/bin/env python3
"""Verify the exact deployed Build50/0089 to Build53/0091 transition.

Build53 reuses the independently frozen Build52 database and opaque-fleet
proof format. The Build52 implementation is loaded by exact hash, then only
the reviewed successor identity and protected-source pins are replaced. This
does not inspect, drain, rewrite, or retire either reported tablet queue.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import types


BUILD52_PROFILE = "infra/scripts/verify-build52-pending-compatibility.py"
BUILD52_PROFILE_SHA256 = (
    "fd850d3d08341c93166dbf89ead46f57c93c5f12670221afe2fee8f2a5b69dd2"
)
CANDIDATE_VERSION = "3.1.45"
CANDIDATE_DATABASE_HEAD = "0091"
CANDIDATE_RELEASE_FROZEN = True
CANDIDATE_BACKEND_MANIFEST_SHA256 = (
    "efa05e5858dd6e3a46533ab12ab89d2676d0a69d13c258b0b9ba83094a638889"
)
CANDIDATE_PROTECTED_SOURCE_SHA256 = (
    "1e2195294aabeccbb29593bbbf7361c13e3eb1730578c701c00911f4b7c23d41"
)
CANDIDATE_PROTECTED_PATH_COUNT = 1285
REVIEWED_CHANGED_PATH_COUNT = 184


def _load_build52(root: Path) -> types.ModuleType:
    path = root / BUILD52_PROFILE
    if path.is_symlink() or not path.is_file():
        raise RuntimeError("immutable Build52 compatibility profile is missing")
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != BUILD52_PROFILE_SHA256:
        raise RuntimeError("immutable Build52 compatibility profile changed")
    module = types.ModuleType("immutable_build52_pending_compatibility")
    module.__file__ = str(path)
    exec(compile(raw, str(path), "exec"), module.__dict__)
    return module


ROOT = Path(__file__).resolve().parents[2]
_profile = _load_build52(ROOT)
if (
    _profile.CANDIDATE_VERSION,
    _profile.CANDIDATE_DATABASE_HEAD,
    _profile.CANDIDATE_RELEASE_FROZEN,
) != ("3.1.44", "0091", True):
    raise RuntimeError("immutable Build52 compatibility identity changed")

# Patch only the successor-specific values in this private, hash-verified
# module instance. Every predecessor, fleet, schema and helper pin stays exact.
_profile.CANDIDATE_VERSION = CANDIDATE_VERSION
_profile.CANDIDATE_DATABASE_HEAD = CANDIDATE_DATABASE_HEAD
_profile.CANDIDATE_RELEASE_FROZEN = CANDIDATE_RELEASE_FROZEN
_profile.CANDIDATE_BACKEND_MANIFEST_SHA256 = CANDIDATE_BACKEND_MANIFEST_SHA256
_profile.CANDIDATE_PROTECTED_SOURCE_SHA256 = CANDIDATE_PROTECTED_SOURCE_SHA256
_profile.CANDIDATE_PROTECTED_PATH_COUNT = CANDIDATE_PROTECTED_PATH_COUNT
_profile.REVIEWED_CHANGED_PATH_COUNT = REVIEWED_CHANGED_PATH_COUNT

CompatibilityError = _profile.CompatibilityError
PRIOR_REVISION = _profile.PRIOR_REVISION
SAME_TREE_TAG_REVISION = _profile.SAME_TREE_TAG_REVISION
PRIOR_VERSION = _profile.PRIOR_VERSION
PRIOR_DATABASE_HEAD = _profile.PRIOR_DATABASE_HEAD
PRIOR_SOURCE_ARCHIVE_SHA256 = _profile.PRIOR_SOURCE_ARCHIVE_SHA256
PRIOR_BACKEND_MANIFEST_SHA256 = _profile.PRIOR_BACKEND_MANIFEST_SHA256
PRIOR_BACKEND_IMAGE_ID = _profile.PRIOR_BACKEND_IMAGE_ID
EXPECTED_INSTALLATION_ROWS = _profile.EXPECTED_INSTALLATION_ROWS
EXPECTED_PENDING = _profile.EXPECTED_PENDING
EXPECTED_NONZERO = _profile.EXPECTED_NONZERO
EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256 = (
    _profile.EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256
)
EXPECTED_NONZERO_FLEET_SHA256 = _profile.EXPECTED_NONZERO_FLEET_SHA256
EXPECTED_RAW_FLEET_SHA256 = _profile.EXPECTED_RAW_FLEET_SHA256
PROTECTED_SCOPES = _profile.PROTECTED_SCOPES
PRIOR_PROTECTED_SOURCE_SHA256 = _profile.PRIOR_PROTECTED_SOURCE_SHA256
PRIOR_PROTECTED_PATH_COUNT = _profile.PRIOR_PROTECTED_PATH_COUNT
FINANCE_TABLES = _profile.FINANCE_TABLES
REQUIRED_PRESERVED_TABLES = _profile.REQUIRED_PRESERVED_TABLES
AUDIT_SEMANTIC_COLUMNS = _profile.AUDIT_SEMANTIC_COLUMNS
COLLECTION_SOURCE_GUARD_COLUMNS = _profile.COLLECTION_SOURCE_GUARD_COLUMNS
EMPTY_SHA256 = _profile.EMPTY_SHA256

_load_modules = _profile._load_modules
_verify_identity = _profile._verify_identity
_verify_sources = _profile._verify_sources
_verify_pre_documents = _profile._verify_pre_documents
_verify_post_documents = _profile._verify_post_documents
_verify_transition_pair = _profile._verify_transition_pair
_decode_transition = _profile._decode_transition
_pre_report = _profile._pre_report
_post_report = _profile._post_report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="phase", required=True)
    _profile._add_pre_arguments(subparsers.add_parser("pre"))
    _profile._add_after_arguments(subparsers.add_parser("post"))
    _profile._add_after_arguments(subparsers.add_parser("rollback"))
    args = parser.parse_args()
    try:
        if args.phase == "pre":
            report = _pre_report(args, ROOT)
        else:
            report = _post_report(args, ROOT, rollback=args.phase == "rollback")
    except (CompatibilityError, OSError, ValueError) as exc:
        print(f"build53 compatibility refused: {exc}", file=sys.stderr)
        return 1
    sys.stdout.write(json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
