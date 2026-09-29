---
name: add-translation-slice
description: >
  Scaffold a Trustworks Essentials translation slice (anti-corruption layer) into an existing project, in Java or
  Kotlin. Same flow as /essentials:add-slice with the kind pre-selected — no kind prompt.
user-invocable: true
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep, AskUserQuestion]
---

# /essentials:add-translation-slice

`/essentials:add-slice` with `kind = translation`. Reach for it when you already know the shape:
you are talking to a system you do not own, in either direction.

## Procedure

```
Read ${CLAUDE_PLUGIN_ROOT}/commands/add-slice.md
```

Run its **Step 0** (project + rules-pointer check), **Step 1** (language detection), **Step 3**
(bounded context, including **Step 3b** — the §R5 write-style lane, which selects the template family), and **Step 4** (names — ask for the external system, the external event, and the direction). **Skip Step 2**: the kind is `translation`.

Then run its **Step 5**, delegating to:

```
Read ${CLAUDE_PLUGIN_ROOT}/skills/essentials-translation-slice/SKILL.md
```

The skill owns emission, wiring, and the report. This command writes no source file itself.

Error handling is `add-slice.md` § Errors — it is not restated here.
