---
name: essentials-change
description: >
  Handle a spoken change request in a Trustworks Essentials slice-architecture project — add, extend,
  amend, fix, or retire a feature, endpoint, command, query, event, read model, projection,
  automation, or external integration — when the user describes the change in prose rather than
  running a command. Applies only where the slice law is in force: `.claude/rules/essentials-slices.md`
  exists, a `slice.yaml` is present, or the build declares `dk.trustworks.essentials`. Classifies the
  request, finds the owning slice from its manifest, applies rules/slice-design.md, and enters the
  matching scaffolding skill when the change turns out to need a new slice.
user-invocable: false
allowed-tools: [Read, Write, Edit, Glob, Grep, Bash]
---

# Change router — Essentials

`/essentials:add-slice` covers *new* slices and refuses to touch existing ones. This skill covers
everything else — the change requests that arrive as sentences instead of commands, which is most of
them.

**This skill routes and then does the work. It does not just print a command name.**

## Step 0 — Gate, before saying anything

Run the detection ladder in `${CLAUDE_PLUGIN_ROOT}/references/slice/change-procedure.md` §1.

If the project is **not** on the law and not an Essentials project, **stop silently**: add nothing to
the conversation and handle the request as ordinary work. A change-request trigger that fires in
unrelated repositories gets muted, and muting costs the projects that need it.

Detection is Glob and Grep only. Do not run a build, and do not walk the whole source tree.

## Step 1 — Load the law and the procedure

```
Read ${CLAUDE_PLUGIN_ROOT}/rules/slice-design.md
Read ${CLAUDE_PLUGIN_ROOT}/references/slice/change-procedure.md
```

The law is the structure; the procedure is how a change moves through it. Cite both by section name;
restate neither.

Read `${CLAUDE_PLUGIN_ROOT}/references/slice/manifest-guide.md` when the change touches manifest
fields you are not certain of, and let the `essentials-docs` skill answer framework-API questions —
this skill does not carry API knowledge and must not guess at one.

## Step 2 — Classify, out loud

Apply `change-procedure.md` §2 and name the class before touching a file:

- **A** new capability → new slice
- **B** extend one slice
- **C** spans several slices
- **D** read-model shape change
- **E** not a slice change

Then apply §3 to locate the owning slice **from the manifests**, and §4 to establish the bounded
context's §R5 lane. Read the slice's own `CLAUDE.md` before editing it.

Where A and B are both arguable, put the law's question to the user — §R1 for commands, §R2 test 1 for
views — rather than resolving it silently.

## Step 3 — Confirm

State, in three lines:

1. the classification and why,
2. the slice(s) that will change, by id and path,
3. what will be created, edited, or deleted.

Then get a yes. The user described a change in prose; they did not ask for six files to appear. For a
class-E change, or a one-line fix inside a slice that changes no structure, a confirmation is noise —
say what you are doing and do it.

## Step 4 — Route

### Class A — a new slice

Resolve the kind skill's inputs first, per `references/slice/slice-authoring.md` §1 (language), §1b
(lane), §2 (project resolution), §3 (bounded context) and §4 (placeholders). Elicit only what the law
requires and the project cannot answer — the package path is **read from the project, never asked**.

Then read the matching skill and follow it as written:

| Kind | Skill |
|---|---|
| command | `${CLAUDE_PLUGIN_ROOT}/skills/essentials-command-slice/SKILL.md` |
| view | `${CLAUDE_PLUGIN_ROOT}/skills/essentials-view-slice/SKILL.md` |
| automation | `${CLAUDE_PLUGIN_ROOT}/skills/essentials-automation-slice/SKILL.md` |
| translation | `${CLAUDE_PLUGIN_ROOT}/skills/essentials-translation-slice/SKILL.md` |

> **Why `Read` and not an invocation.** The four kind skills are `disable-model-invocation: true` on
> purpose — they write files and are meant to be entered by path from something that has already
> elicited the inputs, exactly as `/essentials:add-slice` does. Reading one and following its procedure
> with resolved inputs *is* that contract, not a way around it. Do not "fix" this by making them
> model-invocable; see the plugin's `CLAUDE.md` design invariants.

Never re-elicit an input inside the kind skill, and never merge into an existing slice directory — an
existing directory is an abort, and the request was misclassified if you reach one.

### Classes B, C, D — change what exists

Work through `change-procedure.md` §5, which carries the decision points: second intent (§5.1), another
query over the same model (§5.2), a model needing data it does not project (§5.3), a new event variant
and its `permits` append (§5.4), shared-logic pressure and the `_shared/` bar (§5.5), cross-context
reads (§5.6), adapter-layer pressure (§5.7), repository surface (§5.8), decomposition order for a
cross-slice change (§5.9), retirement (§5.10).

### Class E — not a slice change

Wiring, config, build, the entity and its migration, infrastructure. Do it plainly. No manifest edit,
no slice machinery, no lecture.

## Step 5 — Keep the manifest true

`change-procedure.md` §6 — the field table, and the slice `CLAUDE.md` when an invariant, a boundary, or
a deliberate divergence changed. This is part of the change, not follow-up: an endpoint absent from the
manifest is drift under §R2, and the manifests are what the next session and `/essentials:slice-map`
read.

## Step 6 — Verify what was touched

`change-procedure.md` §7. Re-read § Red flags, check only the gates the class implicates, run the
touched slice's test where the project has one, and *offer* — do not run — the full
`/essentials:slice-check`.

Then, because the change was Essentials code, apply the `essentials-docs` skill's post-write anti-
pattern pass. Both fire on the same edit and they check different things: this skill checks structure,
that one checks framework usage.

## Step 7 — Report

- The class, and the slice(s) changed, by id.
- Every file written, edited, or deleted, with its path.
- The manifest fields updated.
- What the user must still fill in — invariants, event fields, the query body.
- Anything deliberately **not** done, named: a drifted manifest left for `slice-check`, a consumer that
  must react to a new event, a `v1` awaiting retirement.

## Muting

A user who does not want this skill firing on prose sets, in `settings.json`:

```json
{ "skillOverrides": { "essentials:essentials-change": "off" } }
```

The commands (`/essentials:add-slice`, `/essentials:slice-check`, `/essentials:slice-map`) remain the
explicit path and are unaffected.
