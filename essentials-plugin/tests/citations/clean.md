# Citation lint — clean sample

Self-test input for `scripts/check-citations.py --self-test`: the check must report nothing here.
Every line below is a trap for a false positive — a citation, a compatibility line, a bare number,
or a deliberate exception carrying its reason.

The Essentials version comes from the `essentials.version` pin in `references/stack/stack-pins.md`.

The baseline is stack-contract S1: Spring Boot 4.1.x, and Kotlin 2.3 or newer on the Kotlin lane.

Testcontainers 2.x artifact names are required (S10); chapter 25 of the runbook covers the rest.

The persistence mapper MUST be the one S3 requires — see stack-contract S3.2 rather than a copy.

Every write goes through the unit of work (stack-contract S5), and handlers SHOULD be idempotent.

JDBI is held at {{pin:jdbi3-bom.version}} for now. <!-- cite-ok: sample of a deliberate exception on its own line -->

<!-- cite-ok: sample of a marker on the line above the exception -->
Tests pull in objenesis {{pin:objenesis.version}} through the Mockito BOM.
