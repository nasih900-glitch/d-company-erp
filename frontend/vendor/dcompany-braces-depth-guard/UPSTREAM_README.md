# Braces depth guard

A guard-only nesting-depth backport of MIT-licensed braces 3.0.3.

Runtime code starts from the published upstream 3.0.3 release and applies only
the depth guards and documented local followups derived from https://github.com/micromatch/braces/pull/72 at commit
`d0d575e55e74a4e0218e5248fafb79efc3e54ebb`. Original stringify parent behavior
is retained. Upstream authorship and the original MIT license are preserved.

The change bounds nesting and recursive AST traversal to 100 levels. Inputs
above that bound are rejected: string parsing throws SyntaxError, while
direct AST traversal guards throw RangeError. It does not limit expansion cardinality,
AST width or arbitrary malformed AST behavior. This is a maintained derivative,
not an upstream release.

Original advisory: https://github.com/advisories/GHSA-vfj7-8cjw-p6xm. Renaming
the package does not establish advisory remediation or security acceptance.
Consumers must review the patch, artifact, provenance and compatibility.

Version 3.0.3-pn.0 is a bootstrap publication and does not claim GitHub OIDC
provenance. Version 3.0.3-pn.1 was published from the separately
authorized GitHub-hosted workflow with provenance. Version 3.0.3-pn.2 was subsequently published through that same package and workflow.
Version 3.0.3-pn.3 is a local release candidate; it has not been published. No package installs itself.

Run `npm ci --ignore-scripts` followed by `npm test` to execute the upstream
release suite and depth-guard regressions. Package tarballs contain only runtime
code, license, README and package metadata.

## Historical 3.0.3-pn.2 followups

Published 3.0.3-pn.1 remains available for reproducible review. It admits depth 2
when a caller sets `maxDepth: 1.5`, and expansion of a caller-supplied or mutated
paren AST can loop when its parent chain is cyclic. The default integer limit
of 100 still holds; these findings do not establish a bypass of that default.

The pn.2 changes check the next parsing level before admission and reject
cycles in the two upward parent walks used by expansion. They follow the
public cases in https://github.com/FSDevelop/braces/pull/1 and
https://github.com/FSDevelop/braces/pull/3. Regression tests cover fractional
brace/paren limits, self-parent and two-node cycles, and ordinary parsed parens.
The original MIT license and attribution are retained.

These changes do not bound expansion cardinality, AST width, acyclic parent
chain length, or exotic object getters. No direct use of fractional limits or
caller-supplied ASTs was found in a bounded inspection of PinchNode application
code; that inspection is not proof that every transitive path is unreachable.
Neither this package name nor successful tests establish advisory clearance,
independent acceptance, or production readiness. Consumers need fresh review
of the exact published artifact and provenance before adoption.

## Proposed 3.0.3-pn.3: explicit maxDepth option domain

This unpublished source candidate validates the option consistently in parse,
compile, expand and stringify. A finite numeric `maxDepth` must be non-negative;
negative values throw `RangeError: maxDepth must be non-negative` before processing.
Zero permits a root AST or plain text with no nested containers. Positive
fractional limits remain inclusive bounds on integer nesting (1.5 permits depth1,
rejects depth2). Values above100 are capped at100. Omitted, nonfinite and nonnumeric
values retain the existing default100 without coercion, including numeric strings,
booleans, null and boxed numbers. Negative zero behaves as zero.

The option value is read once per processor so a stateful getter cannot replace
a checked finite limit with NaN and disable depth comparisons. Arbitrary AST or
options getters remain outside a comprehensive malformed-object guarantee.
This candidate changes rejection diagnostics for negative values; it does not
make negative nesting meaningful, clamp it to allow work, or clear any veto.
It requires a separately reviewed new package version and fresh provenance before
adoption. The proposed new version is 3.0.3-pn.3; these modified bytes must never replace pn.2.

Validation is per processor: public string wrappers may call parse and another
processor, reading the option once in each. The create shortcut for very short
strings remains unchanged and can return without invoking a processor. These
paths do not bypass the100-level cap, but this candidate does not promise one
getter evaluation across an entire public wrapper call or universal validation
of an unused option.

The same local candidate repairs the existing parsing-length option boundary.
Parse reads `maxLength` once; NaN and negative numeric values (including
negative infinity) throw `RangeError: maxLength must be a non-negative number`
before processing. Zero accepts only an empty string; positive fractional values
remain exact inclusive comparisons against integer string length. Positive
infinity and numbers above10,000 retain the hard10,000-character cap. Omitted
and nonnumeric values retain that default without coercion. Negative zero
behaves as zero. This changes invalid numeric option diagnostics intentionally.

Upstream3.0.3 and pn.2 accept a10,001-character string with `maxLength: NaN`
or a numeric-to-NaN getter; this candidate rejects those bypasses. The change
does not bound expansion cardinality, AST width or malformed-object handling.
Direct caller-supplied ASTs have no parsing-length check. The existing create
shortcut for strings shorter than3characters remains unchanged and can bypass
a caller's stricter unused limit, although it cannot exceed the hard10,000cap.
No universal resource-exhaustion or advisory-clearance claim is made.
