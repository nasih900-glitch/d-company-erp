#!/bin/sh
# The production ERP uses PostgreSQL. Keep the upstream CPython dependency
# closure, removing only its optional SQLite consumer and provider.
set -eu

scan_python_elf() {
    scan_root=$1
    shift
    # Unlike `-exec ... ';'`, POSIX `-exec ... +` propagates a failed child
    # through find's exit status. The checked child also stops at the first
    # failed scan; never convert an unreadable ELF into evidence of absence.
    find "$scan_root" -type f "$@" -exec sh -ec '
        for file do
            scanelf --needed --nobanner --format "%n#p" "$file"
        done
    ' sh '{}' +
}

if [ "${1:-}" = '--after-wheels' ]; then
    # Wheels may bring their own RPATH libraries. Audit them without asking APK
    # to resolve those private sonames as distribution packages.
    scan_python_elf /usr/local > /tmp/python-final-needed.txt
    if tr ',' '\n' < /tmp/python-final-needed.txt | grep -q 'libsqlite3'; then
        echo 'A retained Python ELF still requires SQLite.' >&2
        exit 1
    fi
    apk del --no-network .sqlite-prune-tools
    rm /tmp/python-final-needed.txt
    exit 0
fi
test "$#" -eq 0
apk add --no-cache --virtual .sqlite-prune-tools pax-utils
apk info --depends .python-rundeps | sed -n '/^so:/p' | LC_ALL=C sort -u > /tmp/python-original-deps.txt
test "$(grep -Fxc 'so:libsqlite3.so.0' /tmp/python-original-deps.txt)" -eq 1
grep -Fvx 'so:libsqlite3.so.0' /tmp/python-original-deps.txt > /tmp/python-retained-deps.txt
test -s /tmp/python-retained-deps.txt
# Protect the actual existing providers while replacing the upstream group.
xargs -r apk add --no-network --virtual .python-rundeps-retained < /tmp/python-retained-deps.txt
python -I -B - <<'PY'
from pathlib import Path
import shutil
import sysconfig

stdlib = Path(sysconfig.get_path('stdlib'))
extensions = list((stdlib / 'lib-dynload').glob('_sqlite3.*.so'))
if len(extensions) != 1 or not (stdlib / 'sqlite3').is_dir():
    raise SystemExit('Expected exactly the upstream optional SQLite extension and wrapper')
extensions[0].unlink()
shutil.rmtree(stdlib / 'sqlite3')
PY
# Match the official Python image scan, BEFORE application wheels are copied.
# Upstream intentionally excludes Tk, which is not a supported runtime extra.
scan_python_elf /usr/local -executable -not \( -name '*tkinter*' \) > /tmp/python-cpython-needed.txt
tr ',' '\n' < /tmp/python-cpython-needed.txt | LC_ALL=C sort -u \
    | awk 'NF && system("[ -e /usr/local/lib/" $1 " ]") != 0 { print "so:" $1 }' \
    | LC_ALL=C sort -u > /tmp/python-recomputed-deps.txt
cmp /tmp/python-retained-deps.txt /tmp/python-recomputed-deps.txt
apk del --no-network .python-rundeps sqlite-libs
xargs -r apk add --no-network --virtual .python-rundeps < /tmp/python-recomputed-deps.txt
apk del --no-network .python-rundeps-retained
mkdir -p /etc/dcompany
cp /tmp/python-original-deps.txt /etc/dcompany/python-original-deps.txt
cp /tmp/python-recomputed-deps.txt /etc/dcompany/python-runtime-deps.txt
rm /tmp/python-original-deps.txt /tmp/python-retained-deps.txt /tmp/python-recomputed-deps.txt /tmp/python-cpython-needed.txt
