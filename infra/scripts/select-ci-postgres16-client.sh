#!/bin/bash
# Debian's pg_wrapper can otherwise select a newer client than the PG16 server.
set -euo pipefail

select_postgres16_client() {
  local client_bin=$1
  local client selected version
  [[ "$client_bin" == /* && "$client_bin" != *$'\n'* && "$client_bin" != *$'\r'* ]] || return 1
  [[ -n "${GITHUB_PATH:-}" && "$GITHUB_PATH" == /* ]] || {
    echo "PostgreSQL 16 selection requires GITHUB_PATH." >&2
    return 1
  }
  export PATH="${client_bin}:${PATH}"
  hash -r
  for client in psql pg_dump pg_restore; do
    [[ -x "${client_bin}/${client}" ]] || {
      echo "Required PostgreSQL 16 client is missing: ${client_bin}/${client}" >&2
      return 1
    }
    selected=$(command -v "$client") || return 1
    [[ "$selected" == "${client_bin}/${client}" ]] || {
      echo "Unexpected PostgreSQL client path: ${selected}" >&2
      return 1
    }
    version=$("$selected" --version) || return 1
    [[ "$version" != *$'\n'* && "$version" =~ \(PostgreSQL\)[[:space:]]16(\.|[[:space:]]|$) ]] || {
      echo "Expected PostgreSQL 16 ${client}; got: ${version}" >&2
      return 1
    }
    printf '%s: %s\n' "$selected" "$version"
  done
  # The export covers this step; GITHUB_PATH covers every subsequent step.
  printf '%s\n' "$client_bin" >> "$GITHUB_PATH"
}

main() {
  [[ "${CI:-}" == true && "${GITHUB_ACTIONS:-}" == true && $# -eq 0 ]] || {
    echo "This selector is restricted to GitHub Actions CI." >&2
    return 1
  }
  select_postgres16_client /usr/lib/postgresql/16/bin
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
