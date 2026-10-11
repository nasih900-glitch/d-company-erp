# Source and maintenance notice

This private build dependency is derived from the MIT-licensed
`@dieub/braces-depth-guard@3.0.3-pn.3`, which is itself a guarded derivative of
`braces@3.0.3`. The original copyright and license are preserved in `LICENSE`.

D Company release engineering owns this local derivative. It must review the
upstream `braces` advisory state and compatible official releases before every
release. Replace this package with an official compatible fix when one exists.

The only runtime change from the attested `@dieub` source is the bounded
ancestor traversal in `lib/expand.js`, identified as
`dcompany-ancestor-depth-guard-v1` in `PROVENANCE.json`.
