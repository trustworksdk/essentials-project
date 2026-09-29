# Changelog

The plugin carries no version number: every commit that reaches the marketplace ref is a release.
Entries name the Essentials release the plugin targets.

## First release — targets Essentials 0.60.0

The first official release of the `essentials` Claude Code plugin, published from the Essentials
repository itself.

- **`essentials-docs` skill** — framework knowledge over the bundled docs in `references/llm/`,
  generated from the repository's `LLM/` directory, plus the design guide in
  `references/design/essentials-design.md`.
- **`essentials-change` skill** — routes a change request described in prose to the slice that owns it.
- **Slice-design law** (`rules/slice-design.md`) — four slice kinds and the R1–R5 anti-god-class rules,
  with `/essentials:add-slice` and its four per-kind commands to scaffold slices in Java or Kotlin,
  `/essentials:slice-check` to audit, `/essentials:slice-discover` to analyse a codebase not yet on
  the law, and `/essentials:slice-map` to render the structure of one that is.
- **Application stack contract** (`references/stack/`, S1–S11) — what an Essentials application must
  provide, with Kotlin and Java bindings, the React/TypeScript frontend modes, and the version pins
  (Essentials 0.60.0, Java 25, Spring Boot 4.1.1).
- **`/essentials:init`** — scaffolds a verified Spring Boot project and builds it before handing it over.
- **`/essentials:upgrade`** — brings an existing project up to what the installed plugin ships.
- **`/essentials:intro`** — read-only orientation.
