"""Exact reviewed same-major PostgreSQL security-base recipe delta."""
OLD_IMAGE = "postgres:16.15-alpine3.24@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685"
NEW_IMAGE = "postgres:16.16-alpine3.24@sha256:1d5b02d3a9941fbd52437db4107f8c52d6297dac553ca1e5c299ebb3756b4857"
BUILD79 = "9cecce5b035d0341ad390d9818a9f8162edce2d6"


def reviewed_postgres1616_recipe(previous: str) -> str:
    for before, after, count in (
        (OLD_IMAGE, NEW_IMAGE, 2),
        ("Preserve the exact PostgreSQL 16.15 base, entrypoint and data-directory",
         "Use the exact PostgreSQL 16.16 security base, preserving entrypoint and data-directory", 1),
        ('com.dcompany.upstream.version="16.15"', 'com.dcompany.upstream.version="16.16"', 1),
        ("'postgres (PostgreSQL) 16.15'", "'postgres (PostgreSQL) 16.16'", 1),
    ):
        assert previous.count(before) == count, before
        previous = previous.replace(before, after)
    return previous
