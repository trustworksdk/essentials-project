---
paths:
  - "**/CLAUDE.md"
---

# Maintaining CLAUDE.md Files

Each module has a `CLAUDE.md` focused on contributor/dev context (package structure, key classes, gotchas, extension points). Keep them current — stale docs mislead future sessions.

## When to update

Update the relevant module `CLAUDE.md` immediately after:
- New class or package added that changes the internal structure
- SPI/extension point added, renamed, or removed
- Non-obvious invariant or gotcha discovered during a task
- Test infrastructure changes (new base class, new Docker requirement, new fixture pattern)
- Class renamed, moved, or deleted (remove stale entries)

Update the **root** `CLAUDE.md` when:
- New module added or removed
- Build commands change
- A project-wide gotcha is discovered

## What NOT to record

- How to *use* the module from a consuming project — that belongs in `LLM/LLM-*.md`
- Anything derivable by reading the source (obvious class purposes, standard patterns)
- Task-specific context, PR details, temporary state

## Style rules

Apply caveman writing to CLAUDE.md content:
- Drop articles (a/an/the), filler, hedging
- Fragments OK — one line per fact
- Tables for class lists; bullets for gotchas
- Aim for <100 lines per module file

## How to update

Prefer the `Edit` tool (targeted additions) over rewriting the whole file.
When a class is renamed/removed, find and delete its entry — don't leave ghost entries.
After adding a new SPI or key class, append a row to the relevant table.
