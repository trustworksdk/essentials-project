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

Re-read `rules/slice-design.md` § Red flags, then § Wiring is part of done, before reporting.

The lane is a per-BC choice (`rules/slice-design.md` §R5, aggregate style), so §R1, §R2 and
§ The read side on this lane all bind here. See `rules/slice-design.md` § Service-entity style.

A citation wrapped mid-name still resolves: `rules/slice-design.md` § Reporting
severities, and so does `rules/slice-design.md` § The command and the view *are* the contract.

Another document's sections are not the law's: `slice-model.md` §4.1, change-procedure §5.1, and
the findings in §4 of this guide.
A wrapped citation of another file stays that file's: see `references/design/essentials-design.md`
§ State-stored entities.
