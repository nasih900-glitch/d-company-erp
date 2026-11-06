# Build 71 / 3.1.64 candidate

The Gaming Glass SectionCard instrumentation case now queues Compose test
recomposition on the UI-driven clock when the renderer finishes bitmap work
in the background. It verifies main-thread recomposition and retains its
pixel checks for both scene-derived cards and the solid fallback. The other
Gaming Glass cases retain their existing scheduler. The test fix changes no
production behavior.

This candidate carries forward Build 70's image security updates and guarded
Owner acceptance window. The exact deployed predecessor remains Build 65 /
3.1.58 on Alembic 0094. The candidate targets Alembic 0095 and Android Room 58.
Canonical CI, signing, retained upgrade, device and production acceptance
remain release gates. [Build 70 candidate history](V3_1_63_RELEASE_CANDIDATE.md)
· [Guarded deployment procedure](BUILD66_GUARDED_DEPLOYMENT.md)
