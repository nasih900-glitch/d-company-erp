# Picomatch 2.3.2

Source: https://registry.npmjs.org/picomatch/-/picomatch-2.3.2.tgz

Archive integrity from the prior npm lock: `sha512-V7+vQEJ06Z+c5tSye8S+nHUfI51xoXIXjHQ99cQtKUkQqqO1kO/KCJUfZXuB47h/YBlDhah2H3hdUGXn8ie0oA==`

The MIT license and runtime sources are retained. The package metadata omits upstream development tools and scripts. The only runtime change rejects repeated extglobs in the parser before it emits a regex, mitigating [CVE-2026-112366](https://github.com/advisories/GHSA-fc5q-39xc-vjpf). The ERP web build uses ordinary filename patterns. These copies are build-time dependencies. Remove them once patched upstream versions are available for all supported dependency ranges.
