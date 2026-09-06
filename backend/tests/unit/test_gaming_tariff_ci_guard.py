"""The critical Gaming/POS suite must run, not silently skip, in release CI."""

import re
from pathlib import Path

import pytest

from tests.integration.test_gaming_tariff_pos_e2e import _require_isolated_gaming_database


@pytest.mark.parametrize(
    "database_name",
    ["dcompany_code22_audit_20260903", "dcompany_audit_test", "erp_test"],
)
def test_exact_isolated_database_names_are_allowed(database_name: str) -> None:
    _require_isolated_gaming_database(database_name, in_ci=True)


@pytest.mark.parametrize("database_name", ["erp", "production", "erp_test_copy", "other_test"])
@pytest.mark.parametrize("in_ci", [False, True])
def test_unknown_database_is_never_used_and_ci_cannot_hide_a_skip(
    database_name: str, in_ci: bool,
) -> None:
    expected = pytest.fail.Exception if in_ci else pytest.skip.Exception
    with pytest.raises(expected, match="explicitly allowlisted"):
        _require_isolated_gaming_database(database_name, in_ci=in_ci)


@pytest.mark.parametrize("workflow", ["ci.yml", "release.yml"])
def test_workflow_postgres_database_and_test_dsn_match_the_gaming_gate(workflow: str) -> None:
    repository = Path(__file__).resolve().parents[3]
    source = (repository / ".github" / "workflows" / workflow).read_text()
    configured_databases = re.findall(r"^\s+POSTGRES_DB:\s*(\w+)\s*$", source, re.MULTILINE)
    assert configured_databases, f"No PostgreSQL service database found in {workflow}"
    for database_name in configured_databases:
        _require_isolated_gaming_database(database_name, in_ci=True)
        # Check the runtime test DSN as well as the service declaration; a
        # renamed service alone does not prove the tests connect to that DB.
        assert re.search(
            rf"^\s+DATABASE_URL:\s*postgresql\+psycopg://[^\s]+/{re.escape(database_name)}\s*$",
            source,
            re.MULTILINE,
        ), f"No matching test DATABASE_URL found in {workflow}"
