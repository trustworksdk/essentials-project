---
name: add-automation-slice
description: >
  Scaffold a Trustworks Essentials automation slice (policy / process manager) into an existing project, in Java or
  Kotlin. Same flow as /essentials:add-slice with the kind pre-selected — no kind prompt.
user-invocable: true
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep, AskUserQuestion]
---

# /essentials:add-automation-slice

`/essentials:add-slice` with `kind = automation`. Reach for it when you already know the shape:
something that happened should cause a follow-up, with no user involved.

## Procedure

```
Read ${CLAUDE_PLUGIN_ROOT}/commands/add-slice.md
```

Run its **Step 0** (project + rules-pointer check), **Step 1** (language detection), **Step 3**
(bounded context, including **Step 3b** — the §R5 write-style lane, which selects the template family), and **Step 4** (names — ask for the automation name and the first event that drives it). **Skip Step 2**: the kind is `automation`.

Then run its **Step 5**, delegating to:

```
Read ${CLAUDE_PLUGIN_ROOT}/skills/essentials-automation-slice/SKILL.md
```

The skill owns emission, wiring, and the report. This command writes no source file itself.

**New bounded context** is not offered: a BC starts with its first command slice (`add-slice.md` Step 3).

On a **service-entity** bounded context there is no automation template (the lane has no event store);
the flow stops at Step 3b and the skill says why.

Error handling is `add-slice.md` § Errors — it is not restated here.
