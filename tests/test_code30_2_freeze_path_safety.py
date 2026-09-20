from pathlib import Path

from scripts.verify_code26_regression_freeze import _is_canonical_regular_file


def test_reviewed_freeze_paths_reject_symbolic_links(tmp_path: Path) -> None:
    regular = tmp_path / "reviewed.txt"
    regular.write_text("reviewed\n", encoding="utf-8")
    linked = tmp_path / "linked.txt"
    linked.symlink_to(regular)

    assert _is_canonical_regular_file(regular)
    assert not _is_canonical_regular_file(linked)


def test_reviewed_freeze_paths_reject_symlinked_parents(tmp_path: Path) -> None:
    real_parent = tmp_path / "real"
    real_parent.mkdir()
    regular = real_parent / "reviewed.txt"
    regular.write_text("reviewed\n", encoding="utf-8")
    linked_parent = tmp_path / "linked"
    linked_parent.symlink_to(real_parent, target_is_directory=True)

    assert not _is_canonical_regular_file(linked_parent / regular.name)
