---
name: doctor
description: >-
  Check this machine for the tools the essentials plugin's commands run — python3 3.11+, uv (or
  pyyaml and jsonschema), the pinned JDK, Maven, Docker, npm and ripgrep — and say what each missing
  one costs: a command that stops, a gate that is not run, a compile-only smoke build, a slower
  fallback. Runs scripts/doctor.sh for one profile (init, review, slice, docs) or all of them, then
  explains each gap with an install hint for this OS. Installs nothing.
user-invocable: true
allowed-tools: [Bash]
argument-hint: "[init|review|slice|docs|all]"
---

# /essentials:doctor

Answers **"can the plugin's commands run here, and what do I lose without what is missing?"** Read-only:
it probes, explains and suggests. **It never installs, upgrades or starts anything** — not with the
user's permission either; it prints the command and leaves running it to them.

## Step 1 — Run the script

The argument is a profile: `init`, `review`, `slice` (add-slice, the slice skills, slice-check,
slice-map), `docs` (the docs skill's search and the slice law), or `all`, the default. Anything else:
say which values exist and stop.

```bash
"${CLAUDE_PLUGIN_ROOT}/scripts/doctor.sh" --for <profile>
```

`${CLAUDE_PLUGIN_ROOT}` unset ⇒ abort: "This command must be invoked from within Claude Code with the
essentials plugin installed."

The output is the answer. One line per requirement: its status (`ok`, `MISSING`, `TOO OLD`,
`NOT RUNNING`, `FALLBACK` — uv absent but python3 can run the scripts —, `PARTIAL`, `UNKNOWN`), what was
found, what is needed, and after `—` what degrades without it and how (`STOP`, `NOT RUN`,
`COMPILE-ONLY`, `SKIPPED`, `FALLBACK`). The last line says `OK` or `BLOCKED`; exit 1 means some command
in the profile stops. **Take it verbatim**: do not re-probe a tool the script already reported, and do
not add a requirement it did not list. The required JDK comes from `references/stack/stack-pins.md`;
quote the script's number, never one from memory.

## Step 2 — Explain the gaps

Print the script's output, then, for each line that is not `ok` and only for those, one short
paragraph in plain language:

1. What the user loses, from the line's impacts — the commands that stop first, then the gates that
   are not run, then what still works with a fallback. Say it the way the user will meet it ("
   `/essentials:slice-map` stops", "init's smoke build only compiles, so a context-startup failure
   would go unseen"), not by restating the script's tags.
2. The install hint for this OS. Read the OS from the header (`darwin…` is macOS, `linux…` Linux); on
   Linux, `cat /etc/os-release` once to pick the package manager. The usual routes:

| Requirement | macOS | Linux |
|---|---|---|
| python3, the version the line needs | `brew install python@3.13`, or python.org | the distribution's `python3.11`+ package (`apt`, `dnf`), or pyenv |
| uv | `brew install uv` | `curl -LsSf https://astral.sh/uv/install.sh \| sh`, or `pipx install uv` |
| pyyaml / jsonschema (only when uv is not wanted) | `python3 -m pip install --user pyyaml jsonschema`, or `pipx run <script>` per script | the same; on an externally managed Python, the distribution's `python3-yaml` and `python3-jsonschema` |
| JDK, the major the line needs | `brew install openjdk`, or SDKMAN (`sdk install java <major>-tem`) | the distribution's `openjdk-<major>-jdk`, or SDKMAN |
| Maven | `brew install maven` | the distribution's `maven`, or SDKMAN |
| Docker (`NOT RUNNING`: start it) | Docker Desktop, OrbStack or Colima, then start it | Docker Engine; `sudo systemctl start docker` and membership of the `docker` group |
| npm | `brew install node` | the distribution's `nodejs` and `npm`, or nvm |
| ripgrep | `brew install ripgrep` | the distribution's `ripgrep` |

A JDK that is installed but not first on the `PATH` or in `JAVA_HOME` is a `TOO OLD` line too: say
that `JAVA_HOME` is what Maven uses, and how to point it at the newer JDK.

## Step 3 — Close

One line: either "nothing here stops a command in <profile>" with the count of degraded lines, or the
blocking requirements with the one install that unblocks most (python3 3.11+ unblocks every profile).
Suggest re-running `/essentials:doctor` after installing. Nothing else: no offer to install, no edit to
any file, no build.
