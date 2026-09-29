---
name: add-view-slice
description: >
  Scaffold a Trustworks Essentials view slice (read model) into an existing project, in Java or
  Kotlin. Same flow as /essentials:add-slice with the kind pre-selected — no kind prompt.
user-invocable: true
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep, AskUserQuestion]
---

# /essentials:add-view-slice

`/essentials:add-slice` with `kind = view`. Reach for it when you already know the shape:
someone needs to see something — a query answered from a read model.

## Procedure

```
Read ${CLAUDE_PLUGIN_ROOT}/commands/add-slice.md
```

Run its **Step 0** (project + rules-pointer check), **Step 1** (language detection), **Step 3**
(bounded context, including **Step 3b** — the §R5 write-style lane, which selects the template family), and **Step 4** (names — ask for the view name and the first event it projects). **Skip Step 2**: the kind is `view`.

Then run its **Step 5**, delegating to:

```
Read ${CLAUDE_PLUGIN_ROOT}/skills/essentials-view-slice/SKILL.md
```

The skill owns emission, wiring, and the report. This command writes no source file itself.

Error handling is `add-slice.md` § Errors — it is not restated here.
