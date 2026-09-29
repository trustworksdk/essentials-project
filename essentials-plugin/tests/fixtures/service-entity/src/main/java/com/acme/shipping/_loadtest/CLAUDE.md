Not a slice: a throwaway load-generation harness for `DurableQueuesLoadIT`. `_`-prefixed directories
are excluded from slice enumeration and from the R4 boundary check (`rules/slice-design.md`
§ Directory vocabulary). It lives in `src/main` only because the IT is in another module; moving it
to `src/test` would be the cleaner fix.
