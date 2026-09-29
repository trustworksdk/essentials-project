---
name: add-command-slice
description: >
  Scaffold a Trustworks Essentials command slice (use case) into an existing project, in Java or
  Kotlin. Same flow as /essentials:add-slice with the kind pre-selected — no kind prompt.
user-invocable: true
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep, AskUserQuestion]
---

# /essentials:add-command-slice

`/essentials:add-slice` with `kind = command`. Reach for it when you already know the shape:
a user or system wants to do something that changes state and must enforce rules.

## Procedure

```
Read ${CLAUDE_PLUGIN_ROOT}/commands/add-slice.md
```

Run its **Step 0** (project + rules-pointer check), **Step 1** (language detection), **Step 3**
(bounded context, including **Step 3b** — the §R5 write-style lane, which selects the template family), and **Step 4** (names — ask for the slice name, the command type, and the event it emits). **Skip Step 2**: the kind is `command`.

Then run its **Step 5**, delegating to:

```
Read ${CLAUDE_PLUGIN_ROOT}/skills/essentials-command-slice/SKILL.md
```

The skill owns emission, wiring, and the report. This command writes no source file itself.

Error handling is `add-slice.md` § Errors — it is not restated here.
