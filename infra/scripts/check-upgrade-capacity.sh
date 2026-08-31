#!/bin/bash
# Validate independent free-space reserves before a quiesced upgrade backup.

set -euo pipefail

database_size_bytes=${1:?database size in bytes is required}
snapshot_free_kib=${2:?snapshot filesystem free KiB is required}
postgres_free_kib=${3:?PostgreSQL filesystem free KiB is required}

for value in "$database_size_bytes" "$snapshot_free_kib" "$postgres_free_kib"; do
  if ! [[ "$value" =~ ^[0-9]+$ ]]; then
    echo "Capacity inputs must be non-negative integers." >&2
    exit 1
  fi
done

# A full database copy plus restore/index workspace and a fixed operational
# reserve is required independently on each filesystem.
required_free_bytes=$((database_size_bytes * 2 + 1073741824))
snapshot_free_bytes=$((snapshot_free_kib * 1024))
postgres_free_bytes=$((postgres_free_kib * 1024))
if [ "$snapshot_free_bytes" -lt "$required_free_bytes" ] || \
   [ "$postgres_free_bytes" -lt "$required_free_bytes" ]; then
  echo "Insufficient free space for a verified backup and full restore test." >&2
  echo "Required independently on backup and PostgreSQL filesystems: $required_free_bytes bytes." >&2
  exit 1
fi

echo "Upgrade backup capacity preflight passed."
