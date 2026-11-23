# Build64 / 3.1.57 candidate

Build63 remains immutable. A fresh pre-maintenance VM scan found four High
SQLite-library findings and correctly stopped deployment before maintenance.
Build64 removes the production Python runtime's optional SQLite extension,
standard-library wrapper and normally managed SQLite APK provider. PostgreSQL
remains authoritative; Android Room58 and Alembic0094 are unchanged.

The base image's other actual native dependencies are protected while its
CPython ELF closure is recomputed before application wheels are copied. A
checked post-wheel scan rejects SQLite consumers. The final non-root image
must prove module/package/library absence, retained native imports and dbm
fallback, complete application imports, PostgreSQL drivers, migrations and
normal readiness through the existing isolated production-image gate.

The installer also checks each exact existing HTTP/HTTPS INPUT rule before
insertion. Existing rules are not duplicated; inspection and insertion errors
fail the installation. Ports, protocols, rule position and prior upgrade
eligibility remain unchanged. No firewall flush or deletion is introduced.

Application UI/business source and packaging implementation are inherited
unchanged apart from coordinated release identity. This is an unoffered
candidate. Focused source checks are not image, signing, update-installation
or deployment acceptance. Fresh complete CI, final-image scans, canonical
reproducibility, signed-byte checks, upgrade/runtime checks and guarded VM
acceptance remain required. The physical Redmi Pad 2 is unavailable.
