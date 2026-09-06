#!/bin/sh
# Build-time only: never derive deployed identity from mutable runtime env.
set -eu
version_name=${1:?version name required}
source_git_sha=${2:?source revision required}
destination=${3:?destination required}
case "$version_name" in *[!0-9.]*|'')
  echo 'Release identity version contains unsafe characters.' >&2; exit 1;;
esac
case "$source_git_sha" in *[!0-9a-f]*|'')
  echo 'Release identity revision contains unsafe characters.' >&2; exit 1;;
esac
printf '%s' "$version_name" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' || {
  echo 'Release identity requires a canonical X.Y.Z version.' >&2; exit 1;
}
printf '%s' "$source_git_sha" | grep -Eq '^[0-9a-f]{40}$' || {
  echo 'Release identity requires a full lowercase Git SHA.' >&2; exit 1;
}
mkdir -p "$(dirname "$destination")"
printf '{"version_name":"%s","source_git_sha":"%s"}\n' "$version_name" "$source_git_sha" > "$destination"
chmod 0444 "$destination"
