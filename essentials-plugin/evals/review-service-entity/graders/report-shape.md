---
type: llm
---

The agent's final message is the report `/essentials:review` printed for a change to a test project.

PASS if all of these hold:
- every finding row carries a severity (Blocking, Should-fix or Advisory), an id (`ESS-NNN`, `ESS-S<n>` or `ESS-G<gate><clause>`), a `file:line`, a link to the owning section (a path with a `#` anchor such as `references/llm/LLM-traps.md#ess-050`, `references/stack/stack-contract.md#…` or `commands/slice-check.md#g9`), and a one-line fix;
- the report has a "Not run" block (or an equivalent statement), which may say none;
- it never calls the change clean, passing or ready to merge while it lists a check as not run.
FAIL if a finding row lacks one of those parts, the Not run block is absent, or a clean verdict sits beside an unrun check.
