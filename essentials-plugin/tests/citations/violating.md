# Citation lint — violating sample

Self-test input for `scripts/check-citations.py --self-test`. Every line the check must flag
carries an `expect` marker naming the rule; a finding on any other line, or a marker with no
finding, fails the self-test. `{{pin:…}}` and `{{quote:…}}` are filled in from `stack-pins.md` and
`stack-contract.md` before the check runs, so this file survives a pin move or a contract edit.

Scaffold against Essentials {{pin:essentials.version}} on Spring Boot {{pin:spring-boot-starter-parent}}. <!-- expect: pinned-version -->

The language level is Java {{pin:java.version}} or newer. <!-- expect: pinned-version -->

Tests pull in objenesis {{pin:objenesis.version}} through the Mockito BOM. <!-- expect: pinned-version -->

```xml
<java.version>{{pin:java.version}}</java.version> <!-- expect: pinned-version -->
```

S5 is the rule here, and every write MUST follow it: {{quote:S5}} <!-- expect: restated-requirement -->

JDBI is held at {{pin:jdbi3-bom.version}} for now. <!-- expect: pinned-version --> <!-- expect: allow-marker --> <!-- cite-ok: -->

Re-read `rules/slice-design.md` § Warning signs before reporting. <!-- expect: law-section -->

The write style is §R9 of the law. <!-- expect: law-section -->

See `rules/slice-design.md` § Red flags, and § No such section either. <!-- expect: law-section -->

The law has no numbered sections (`rules/slice-design.md` §4). <!-- expect: law-section -->

A wrapped citation of the law is still checked: see `rules/slice-design.md`
§ Red banners. <!-- expect: law-section -->
