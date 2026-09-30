# Changelog

The plugin carries no version number: every commit that reaches the marketplace ref is a release.
Entries name the Essentials release the plugin targets.

## First release — targets Essentials 0.60.0

The first official release of the `essentials` Claude Code plugin, published from the Essentials
repository itself.

- **`essentials-docs` skill** — framework knowledge over the bundled docs in `references/llm/`,
  generated from the repository's `LLM/` directory, plus the design guide in
  `references/design/essentials-design.md`. The traps index (`LLM-traps.md`) gives every trap a
  stable `ESS-NNN` id.
- **`essentials-change` skill** — routes a change request described in prose to the slice that owns it.
- **Slice-design law** (`rules/slice-design.md`) — four slice kinds and the R1–R5 anti-god-class rules,
  with `/essentials:add-slice` and its four per-kind commands to scaffold slices in Java or Kotlin,
  `/essentials:slice-check` to audit, `/essentials:slice-discover` to analyse a codebase not yet on
  the law, and `/essentials:slice-map` to render the structure of one that is.
- **Application stack contract** (`references/stack/`, S1–S11) — what an Essentials application must
  provide, with Kotlin and Java bindings, the React/TypeScript frontend modes, and the version pins
  (Essentials 0.60.0, Java 25, Spring Boot 4.1.1, Kotlin 2.4.10).
- **`/essentials:init`** — scaffolds a Spring Boot project (Kotlin or Java, WebFlux or WebMvc, three
  DB profiles, an optional embedded or standalone React frontend, optional Docker Compose, an
  optional slice-manifest lint gate) with its wiring tests, then lints and builds it before handing
  it over.
- **`/essentials:upgrade`** — brings an existing project up to what the installed plugin ships.
- **`/essentials:review`** — reviews a change against the traps index, the stack contract and the
  slice law, with `ESS-*` finding ids linking to the section that owns each; `--fix` applies the
  mechanical fixes one at a time.
- **`/essentials:intro`** — read-only orientation.
- **Deterministic scripts** behind the commands, each with committed goldens or tests:
  `init-render.py` and `render-slice.py` (project and slice rendering), `slice-lint.py`,
  `slice-source.py` and `slice-index.py` (manifests, source facts, the map), `stack-lint.py`
  (S1–S11) and `review-scan.py` (trap signatures).
- **Eval suite** (`evals/`) — `claude plugin eval` cases for the steps that need the model's
  judgement.
