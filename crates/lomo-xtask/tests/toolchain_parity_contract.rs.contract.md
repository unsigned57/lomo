Test Change Justification:
Reason category: mechanical migration.
Old behavior/assertion being replaced: NDK 29 literals in valid fixtures and mismatch diagnostics.
Why old assertion is no longer correct: the authorized dependency upgrade pins NDK 30.0.16248370.
Coverage preserved by: legal matching directories, mismatched revisions, missing metadata and
workflow cache path/key drift still exercise the real validator and parity checker.
Why this is not fitting the test to the implementation: the official SDK release is the selected
external toolchain input; rejection semantics and expected diagnostic detail remain unchanged.

Compile SDK behavior extension: the canonical declaration is a structured API identity.
Given equal major APIs and different minor APIs, parity must reject drift, and valid 37.2
copies must pass without truncating to 37. RED/GREEN: reports/manual/dependency-upgrade/sdk-parity-{red,green}.log.
The existing major/JDK/NDK/serialization drift and missing-input checks remain intact.
