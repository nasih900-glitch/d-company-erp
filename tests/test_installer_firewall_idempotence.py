"""Execute the installer helper against a command-recording iptables fixture."""
import os
from pathlib import Path
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize(('check_rc', 'insert_rc', 'expected_rc', 'calls'), [
    (0, 0, 0, 1), (1, 0, 0, 2), (2, 0, 2, 1), (1, 4, 4, 2),
])
def test_exact_existing_rule_is_not_duplicated_and_errors_are_not_hidden(tmp_path, check_rc, insert_rc, expected_rc, calls):
    source = (ROOT / 'infra/scripts/install-on-vm.sh').read_text()
    helper = 'ensure_input_accept() {' + source.split('ensure_input_accept() {', 1)[1].split('\n}', 1)[0] + '\n}\n'
    executable = tmp_path / 'iptables'
    log = tmp_path / 'calls'
    executable.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$CALL_LOG"\n'
                          'case "$1" in -C) exit "$CHECK_RC" ;; -I) exit "$INSERT_RC" ;; *) exit 99 ;; esac\n')
    executable.chmod(0o755)
    result = subprocess.run(['bash', '-c', 'set -e\n' + helper +
                             'ensure_input_accept -p tcp -m state --state NEW -m tcp --dport 443 -j ACCEPT\n'],
                            env={**os.environ, 'PATH': str(tmp_path) + os.pathsep + os.environ['PATH'],
                                 'CALL_LOG': str(log), 'CHECK_RC': str(check_rc), 'INSERT_RC': str(insert_rc)},
                            capture_output=True, text=True, timeout=10)
    assert result.returncode == expected_rc
    lines = log.read_text().splitlines()
    assert len(lines) == calls
    assert lines[0] == '-C INPUT -p tcp -m state --state NEW -m tcp --dport 443 -j ACCEPT'
    if calls == 2:
        assert lines[1] == '-I INPUT 6 -p tcp -m state --state NEW -m tcp --dport 443 -j ACCEPT'


def test_only_the_three_original_rules_are_requested():
    source = (ROOT / 'infra/scripts/install-on-vm.sh').read_text()
    calls = [line for line in source.splitlines() if line.startswith('ensure_input_accept ')]
    assert calls == [
        'ensure_input_accept -p tcp -m state --state NEW -m tcp --dport 80 -j ACCEPT',
        'ensure_input_accept -p tcp -m state --state NEW -m tcp --dport 443 -j ACCEPT',
        'ensure_input_accept -p udp -m udp --dport 443 -j ACCEPT',
    ]
