# Picomatch 4.0.7

Source: https://registry.npmjs.org/picomatch/-/picomatch-4.0.7.tgz

Archive integrity from the prior npm lock: `sha512-qcJu88Q2IWqJsDD529JKMdwGm/dvInW4HvQnRwiH9JtihJvzGOscDtHE3x1pBKeUOTysQ8kVmLnJ2kJu7yhcGA==`

The MIT license and runtime sources are retained. The package metadata omits upstream development tools and scripts. The only runtime change rejects repeated extglobs in the parser before it emits a regex, mitigating [CVE-2026-112366](https://github.com/advisories/GHSA-fc5q-39xc-vjpf). The ERP web build uses ordinary filename patterns. These copies are build-time dependencies. Remove them once patched upstream versions are available for all supported dependency ranges.
