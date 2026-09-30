#!/usr/bin/env python3
"""review-scan — the deterministic half of /essentials:review: trap signatures in the added lines of a diff.

Why this exists
---------------
Some Essentials traps leave a mark a regular expression can find: a configuration key Spring Boot 4 no
longer binds, a property the framework retired, a Jackson 2 annotation Jackson 3 never reads, a 0.50
artifact id, a constructor shape that now binds to the wrong overload. Finding those is not judgement,
so the model does not do it. This script reads a unified diff, looks only at the lines it adds, and
reports every signature it matches with the trap's `ESS-NNN` id from `references/llm/LLM-traps.md` —
the one catalogue; the ids are never listed here a second time, only referenced, and the script
refuses to run when a referenced id is not an active line in that file.

Each signature is one of two kinds:

confirmed   the match is the trap. The finding carries a fix descriptor; where the fix is a literal
            edit that the match itself pins down, `mechanical` is true and `ops` describe it exactly.
candidate   the match is where the trap usually lives, but whether it bites needs context (which
            bus, which mapper, what the handler does). The model confirms or dismisses each one with
            a reason; this script never guesses.

What it never does: read a line the diff does not add, write a file, or run anything but read-only git.
Comments are stripped before matching (Java/Kotlin `//` and `/* */`, YAML/properties `#`, XML
`<!-- -->`); documentation and patch files are not scanned; main-code-only signatures skip test roots.

Context beyond the diff
-----------------------
A YAML key's full path, a file's imports and a dependency's groupId are often outside the hunk. When
the file exists under ROOT and every added line of it equals the file's line at the same number (the
tree *is* the diff's new side), the whole file is used; otherwise only the hunk's own lines are. A
YAML line whose path cannot be resolved that way is listed under `notRun`, never guessed.

Usage
-----
    review-scan.py --diff <file|->     [--root DIR] [--json] [--fail-on LEVEL] [--traps PATH]
    review-scan.py --base <ref> [--head <ref>] [--root DIR] [--json] [--fail-on LEVEL] [--traps PATH]
    review-scan.py --signatures [--json]
    review-scan.py --self-test

    --diff      a unified diff (`git diff`, `git diff -U0`, `diff -u`); `-` reads stdin
    --base      read-only git: `git diff --merge-base <ref>` against the working tree (tracked files,
                committed and uncommitted), or against `--head <ref>` when given
    --root      the tree the diff applies to (default: git top level with --base, else the current
                directory); used only to read context, never written
    --fail-on   lowest severity that makes the exit code 1: advisory (default), should-fix, blocking
    --traps     the catalogue (default: ../references/llm/LLM-traps.md beside this script)

Exit codes: 0 no finding at or above --fail-on · 1 findings · 2 could not run (unreadable or
malformed diff, git failure, catalogue missing, a signature id not active in the catalogue).
--self-test: 0 every fixture expectation met · 1 not.

Standard library only; Python 3.11+.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

PLUGIN = Path(__file__).resolve().parent.parent
TRAPS = PLUGIN / "references" / "llm" / "LLM-traps.md"
TRAPS_REL = "references/llm/LLM-traps.md"
FIXTURES = PLUGIN / "tests" / "review" / "signatures"

SEVERITIES = ("Blocking", "Should-fix", "Advisory")
SEVERITY_RANK = {s: i for i, s in enumerate(SEVERITIES)}
FAIL_ON = {"blocking": "Blocking", "should-fix": "Should-fix", "advisory": "Advisory"}


class UsageError(Exception):
    pass


# ---------------------------------------------------------------------------------------------
# The catalogue (read, never restated)

TRAP_LINE = re.compile(
    r'^- <a id="ess-(\d{3})"></a>`ESS-(\d{3})` (?P<symptom>.*) → \[(?P<label>[^\]]*)\]\((?P<href>[^)]*)\)\s*$')
RETIRED_HEADING = re.compile(r"^##\s+Retired ids\s*$")


@dataclass
class Trap:
    id: str
    line: int
    symptom: str
    href: str

    @property
    def section(self):
        return self.href if re.match(r"^[a-z]+://", self.href) else f"references/llm/{self.href}"


def load_traps(path: Path) -> dict[str, Trap]:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise UsageError(f"cannot read the traps catalogue {path}: {exc}")
    traps, retired = {}, False
    for no, line in enumerate(text.splitlines(), 1):
        if RETIRED_HEADING.match(line):
            retired = True
        m = TRAP_LINE.match(line)
        if m and not retired and m.group(1) == m.group(2):
            traps[f"ESS-{m.group(1)}"] = Trap(f"ESS-{m.group(1)}", no, m.group("symptom"), m.group("href"))
    if not traps:
        raise UsageError(f"{path}: no `ESS-NNN` trap lines — not the traps catalogue")
    return traps


# ---------------------------------------------------------------------------------------------
# The signature table. Evidence is the 0.60 source (or the Spring Boot 4.1 metadata) that makes the
# match the trap; it is shown by --signatures and recorded in the step ledger, and the self-test
# fails for a signature without it.

@dataclass(frozen=True)
class Signature:
    check: str
    id: str
    kind: str          # confirmed | candidate
    severity: str      # the default; a check may lower or raise it from what it matched
    scope: str         # what it reads
    evidence: tuple


SIGNATURES = [
    Signature("ess-088-mongo-key", "ESS-088", "confirmed", "Blocking",
              "application*/bootstrap* .properties/.yml keys; string literals in .java/.kt",
              ("spring-boot-mongodb jar (Boot line pinned in stack-pins.md) META-INF/spring-configuration-metadata.json: every "
               "spring.data.mongodb.{uri,host,port,database,username,password,authentication-database,"
               "replica-set-name,additional-hosts,protocol,ssl.enabled,ssl.bundle,uuid-representation} "
               "deprecated level=error with its spring.mongodb.* replacement",
               "spring-boot-data-mongodb jar: auto-index-creation, field-naming-strategy, gridfs.*, "
               "repositories.type, representation.big-decimal stay under spring.data.mongodb",
               "pom.xml:100 (spring-boot.version)")),
    Signature("ess-088-mongo-env", "ESS-088", "confirmed", "Blocking",
              "SPRING_DATA_MONGODB_* set as an environment variable (.env, compose, Dockerfile, manifests)",
              ("Spring Boot relaxed binding maps SPRING_DATA_MONGODB_URI to spring.data.mongodb.uri, "
               "which spring-boot-mongodb no longer binds (see ess-088-mongo-key)",)),
    Signature("ess-089-reactive-bpp-disabled", "ESS-089", "confirmed", "Blocking",
              "essentials.reactive-bean-post-processor-enabled=false in config or a string literal",
              ("components/spring-boot-starter-postgresql/src/main/java/dk/trustworks/essentials/components/"
               "boot/autoconfigure/postgresql/EssentialsComponentsConfiguration.java:163",
               "components/spring-boot-starter-mongodb/src/main/java/dk/trustworks/essentials/components/"
               "boot/autoconfigure/mongodb/EssentialsComponentsConfiguration.java:149")),
    Signature("ess-103-transactional-mode", "ESS-103", "confirmed", "Should-fix",
              "essentials.durable-queues.transactional-mode in config (Advisory unless fully-transactional)",
              ("components/spring-boot-starter-postgresql/.../postgresql/EssentialsComponentsProperties.java:292-307 "
               "(DurableQueuesProperties has no transactionalMode)",
               "components/spring-boot-starter-mongodb/.../mongodb/EssentialsComponentsProperties.java:222-234",
               "no ignoreUnknownFields=false on any starter @ConfigurationProperties (grep of components/*/src/main)")),
    Signature("ess-097-queue-statistics", "ESS-097", "confirmed", "Advisory",
              "essentials.durable-queues.{enable-queue-statistics,shared-queue-statistics-table-name,"
              "enable-queue-statistics-ttl,queue-statistics-ttl-duration} (Should-fix when enabling)",
              ("components/spring-boot-starter-postgresql/.../postgresql/EssentialsComponentsProperties.java:292-307 "
               "(no statistics fields)",
               "components/postgresql-queue/src/main/java/dk/trustworks/essentials/components/queue/postgresql/"
               "PostgresqlDurableQueues.java:412,434-472 (statistics trigger, function and table dropped)")),
    Signature("ess-050-lock-confirmation", "ESS-050", "confirmed", "Should-fix",
              "essentials.fenced-lock-manager.lock-time-out / lock-confirmation-interval where the interval is "
              "not at least 2x shorter (candidate when the other value is the default)",
              ("components/spring-boot-starter-postgresql/.../postgresql/EssentialsComponentsProperties.java:658-659 "
               "(defaults 15s / 4s); mongodb/EssentialsComponentsProperties.java:456-457",
               "postgresql/EssentialsComponentsConfiguration.java:262-263 (properties reach the settings)",
               "components/foundation/src/main/java/dk/trustworks/essentials/components/foundation/fencedlock/"
               "FencedLockManagerSettings.java:63-64 (interval > timeout throws at startup)")),
    Signature("ess-094-jackson2-module", "ESS-094", "confirmed", "Blocking",
              "dk.trustworks.essentials:types-jackson / immutable-jackson in a pom.xml dependency or a Gradle "
              "coordinate (candidate when the groupId is not visible)",
              ("components/foundation/src/main/java/dk/trustworks/essentials/components/foundation/json/"
               "EssentialsJacksonModules.java:50-66 (IllegalStateException for a Jackson 2 module)",
               "pom.xml:179,186 (types-jackson3, immutable-jackson3 are the modules)")),
    Signature("ess-021-j2-key-using", "ESS-021", "confirmed", "Should-fix",
              "@JsonDeserialize(keyUsing = …) whose JsonDeserialize is Jackson 2's (candidate when the import "
              "is not visible)",
              ("types-jackson3/src/main/java/dk/trustworks/essentials/jackson/types/EssentialTypesJacksonModule.java:52 "
               "(value-type keys handled by the module)",
               "types-jackson3/src/test/java/dk/trustworks/essentials/jackson/SingleValueTypeMapKeyTest.java:33")),
    Signature("ess-021-j2-databind-annotation", "ESS-021", "candidate", "Should-fix",
              "import com.fasterxml.jackson.databind.annotation.* in .java/.kt",
              ("types-jackson3 reads only tools.jackson.databind.annotation (EssentialTypesJacksonModule.java:52 "
               "is a Jackson 3 module); SingleValueTypeMapKeyTest.java:33",)),
    Signature("ess-110-notify-trigger-installation", "ESS-110", "confirmed", "Should-fix",
              "a call of enableNotifyTriggerInstallation(…) in .java/.kt",
              ("components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/"
               "eventstore/postgresql/persistence/table_per_aggregate_type/"
               "SeparateTablePerAggregateTypePersistenceStrategy.java:406-414 (@Deprecated, DDL outside the harness), "
               ":444-453 (enableNotifyTriggers(Consumer<String>) — a different parameter, not a rename)",)),
    Signature("ess-104-append-to-stream-optional", "ESS-104", "confirmed", "Blocking",
              "AppendToStream(…) constructed with an Optional argument on the same line",
              ("components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/"
               "eventstore/postgresql/operations/AppendToStream.java:107,144,201 (no Optional constructor), "
               ":153-165 (the varargs constructor throws IllegalArgumentException for an Optional)",)),
    Signature("ess-080-document-db-private-id", "ESS-080", "confirmed", "Blocking",
              "Java field annotated with postgresql-document-db's @Id that is not public",
              ("components/postgresql-document-db/src/main/kotlin/dk/trustworks/essentials/components/document_db/"
               "annotations/DocumentDBAnnotations.kt:163 (the @Id)",
               "components/postgresql-document-db/src/main/kotlin/dk/trustworks/essentials/components/document_db/"
               "postgresql/PostgresqlDocumentDbRepository.kt:559 (id read by getter.call, no isAccessible in src/main)")),
    Signature("ess-052-hand-built-mapper", "ESS-052", "candidate", "Should-fix",
              "a hand-built ObjectMapper/JsonMapper in main code",
              ("components/foundation/src/main/java/dk/trustworks/essentials/components/foundation/json/"
               "EssentialsObjectMappers.java:54,112 (the persistence mappers)",)),
    Signature("ess-016-local-command-bus", "ESS-016", "candidate", "Advisory",
              "a LocalCommandBus constructed in main code (not DurableLocalCommandBus)",
              ("reactive/src/main/java/dk/trustworks/essentials/reactive/command/LocalCommandBus.java:33,46-69 "
               "(sendAndDontWait is in-memory)",
               "postgresql/EssentialsComponentsConfiguration.java:419-426 (the starters' bus is DurableLocalCommandBus)")),
    Signature("ess-056-append-without-order", "ESS-056", "candidate", "Should-fix",
              ".appendToStream(…) in main code with no EventOrder on the line",
              ("components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/"
               "eventstore/postgresql/EventStore.java:139-142,178-181 (the order-less overloads pass Optional.empty())",)),
    Signature("ess-064-jdbi-create", "ESS-064", "candidate", "Should-fix",
              "Jdbi.create(…) in main code without TransactionAwareDataSourceProxy on the line",
              ("postgresql/EssentialsComponentsConfiguration.java:210 (the starter wraps the DataSource)",)),
    Signature("ess-058-async-subscription", "ESS-058", "candidate", "Advisory",
              ".subscribeToAggregateEventsAsynchronously(…) in main code",
              ("components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/"
               "eventstore/postgresql/subscription/EventStoreSubscriptionManagerBuilder.java:34 "
               "(SubscriptionErrorPolicy.skip() by default)",)),
    Signature("ess-032-kotlin-converter-by-hand", "ESS-032", "candidate", "Advisory",
              "KotlinValueTypeConverter constructed in main code",
              ("types-spring-web/src/main/java/dk/trustworks/essentials/types/spring/web/EssentialsWebMvcConfigurer.java:55, "
               "EssentialsWebFluxConfigurer.java:51, KotlinValueTypeConverterRegistrar.java:55 (already registered)",)),
]
BY_CHECK = {s.check: s for s in SIGNATURES}

# Spring Boot 4.1: spring.data.mongodb.<old> → spring.mongodb.<new> (spring-boot-mongodb metadata, level=error).
MONGO_MOVED = {
    "additional-hosts": "additional-hosts", "authentication-database": "authentication-database",
    "database": "database", "host": "host", "password": "password", "port": "port", "protocol": "protocol",
    "replica-set-name": "replica-set-name", "ssl.bundle": "ssl.bundle", "ssl.enabled": "ssl.enabled",
    "uri": "uri", "username": "username", "uuid-representation": "representation.uuid",
}
QUEUE_STATISTICS_KEYS = ("enable-queue-statistics", "shared-queue-statistics-table-name",
                         "enable-queue-statistics-ttl", "queue-statistics-ttl-duration")
LOCK_TIMEOUT_KEY = "essentials.fenced-lock-manager.lock-time-out"
LOCK_CONFIRM_KEY = "essentials.fenced-lock-manager.lock-confirmation-interval"
LOCK_DEFAULTS = {LOCK_TIMEOUT_KEY: 15.0, LOCK_CONFIRM_KEY: 4.0}   # seconds; see the ess-050 evidence


def canon(key: str) -> str:
    """Spring relaxed-binding comparison form: lower case, no dashes or underscores."""
    return re.sub(r"[-_]", "", key.lower())


MONGO_CANON = {canon("spring.data.mongodb." + k): v for k, v in MONGO_MOVED.items()}
QUEUE_STATS_CANON = {canon("essentials.durable-queues." + k): k for k in QUEUE_STATISTICS_KEYS}

# ---------------------------------------------------------------------------------------------
# Findings


@dataclass
class Finding:
    check: str
    file: str
    line: int
    message: str
    fix_text: str
    ops: list = field(default_factory=list)
    mechanical: bool = False
    kind: str | None = None
    severity: str | None = None
    match: str = ""

    def sig(self):
        return BY_CHECK[self.check]

    def as_dict(self, traps):
        s = self.sig()
        trap = traps[s.id]
        return {
            "id": s.id,
            "check": self.check,
            "kind": self.kind or s.kind,
            "severity": self.severity or s.severity,
            "file": self.file,
            "line": self.line,
            "match": self.match,
            "message": self.message,
            "symptom": trap.symptom,
            "fix": {"text": self.fix_text, "mechanical": bool(self.mechanical and self.ops), "ops": self.ops},
            "cite": [f"{TRAPS_REL}:{trap.line}"],
            "link": f"{TRAPS_REL}#{s.id.lower()}",
            "section": trap.section,
        }


# ---------------------------------------------------------------------------------------------
# Diff model


@dataclass
class Line:
    no: int         # line number on the new side
    text: str
    added: bool


@dataclass
class FileDiff:
    path: str
    hunks: list = field(default_factory=list)   # [[Line, …], …] — context and added lines, new-side order

    def added(self):
        return [ln for h in self.hunks for ln in h if ln.added]


HUNK = re.compile(r"^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


def parse_diff(text: str) -> list[FileDiff]:
    """Files with their new-side hunks. Hunk line counts decide where a hunk ends, so a removed line
    that starts with "-- " is never mistaken for a `---` file header."""
    files, cur, hunk = [], None, None
    new_no = old_left = new_left = 0
    for raw in text.splitlines():
        if hunk is not None and (old_left > 0 or new_left > 0):
            tag = raw[:1]
            if tag == "+":
                hunk.append(Line(new_no, raw[1:], True))
                new_no += 1
                new_left -= 1
            elif tag == "-":
                old_left -= 1
            elif tag == " " or raw == "":
                hunk.append(Line(new_no, raw[1:], False))
                new_no += 1
                old_left -= 1
                new_left -= 1
            elif tag == "\\":
                pass    # "\ No newline at end of file"
            else:
                hunk = None     # malformed or truncated hunk: fall through to header parsing
            if hunk is not None or tag in "+- \\":
                continue
        if raw.startswith("diff "):
            cur, hunk = None, None
            continue
        if raw.startswith("+++ "):
            target = raw[4:].split("\t")[0].strip()
            if target == "/dev/null":
                cur = None
            else:
                cur = FileDiff(target[2:] if target.startswith("b/") else target)
                files.append(cur)
            hunk = None
            continue
        m = HUNK.match(raw)
        if m:
            old_left = int(m.group(1)) if m.group(1) is not None else 1
            new_no = int(m.group(2))
            new_left = int(m.group(3)) if m.group(3) is not None else 1
            hunk = [] if cur is not None else None
            if cur is not None:
                cur.hunks.append(hunk)
            else:
                old_left = new_left = 0
            continue
    return files


def validate_diff(text: str, files: list[FileDiff]):
    if not text.strip():
        return
    if not files and not re.search(r"^(diff |--- |\+\+\+ |@@ )", text, re.MULTILINE):
        raise UsageError("input is not a unified diff (no file headers or hunks)")


# ---------------------------------------------------------------------------------------------
# File classification

DOC_SUFFIXES = {".md", ".adoc", ".txt", ".rst", ".html", ".htm", ".diff", ".patch", ".svg", ".json", ".csv"}
SOURCE_SUFFIXES = {".java", ".kt"}
TEST_ROOT = re.compile(r"(^|/)src/(test|it|testFixtures|integration-?[tT]est)[\w-]*/")
CONFIG_NAME = re.compile(r"^(application|bootstrap)([-.][\w.-]*)?\.(properties|ya?ml)$")


def kind_of(path: str) -> str:
    name = path.rsplit("/", 1)[-1]
    suffix = ("." + name.rsplit(".", 1)[-1].lower()) if "." in name else ""
    if suffix in DOC_SUFFIXES:
        return "doc"
    if name == "pom.xml":
        return "pom"
    if name in ("build.gradle", "build.gradle.kts"):
        return "gradle"
    if suffix in SOURCE_SUFFIXES:
        return "source"
    if CONFIG_NAME.match(name):
        return "config"
    return "other"


def is_test(path: str) -> bool:
    return bool(TEST_ROOT.search(path))


# ---------------------------------------------------------------------------------------------
# Context: the whole file when the tree matches the diff's new side, else the hunks


class Context:
    def __init__(self, fd: FileDiff, root: Path | None):
        self.fd = fd
        self.whole = None
        if root is not None:
            p = root / fd.path
            try:
                lines = p.read_text(encoding="utf-8").splitlines()
            except (OSError, UnicodeDecodeError, ValueError):
                lines = None
            if lines is not None and all(0 < ln.no <= len(lines) and lines[ln.no - 1] == ln.text
                                         for ln in fd.added()):
                self.whole = [Line(i, t, False) for i, t in enumerate(lines, 1)]
                added = {ln.no for ln in fd.added()}
                for ln in self.whole:
                    ln.added = ln.no in added

    def sequences(self):
        """Contiguous runs of new-side lines to analyse: the whole file, or each hunk."""
        return [self.whole] if self.whole is not None else self.fd.hunks

    def all_lines(self):
        return self.whole if self.whole is not None else [ln for h in self.fd.hunks for ln in h]


# ---------------------------------------------------------------------------------------------
# Comment stripping


def strip_code(seq: list[Line]) -> dict[int, str]:
    """Java/Kotlin: {line no: code with comments removed, string literals kept}."""
    out, in_block, in_text = {}, False, False
    for ln in seq:
        s, i, buf = ln.text, 0, []
        if not in_block and not in_text and re.match(r"^\s*\*(\s|/|$)", s):
            # a javadoc/block-comment body line whose opening sits outside the hunk
            end = s.find("*/")
            s, i = (s, len(s)) if end < 0 else (s, end + 2)
        while i < len(s):
            if in_block:
                end = s.find("*/", i)
                if end < 0:
                    i = len(s)
                else:
                    in_block, i = False, end + 2
                continue
            if in_text:
                end = s.find('"""', i)
                if end < 0:
                    buf.append(s[i:])
                    i = len(s)
                else:
                    buf.append(s[i:end + 3])
                    in_text, i = False, end + 3
                continue
            c = s[i]
            if s.startswith("//", i):
                break
            if s.startswith("/*", i):
                in_block, i = True, i + 2
                continue
            if s.startswith('"""', i):
                buf.append('"""')
                in_text, i = True, i + 3
                continue
            if c in "\"'":
                j = i + 1
                while j < len(s) and s[j] != c:
                    j += 2 if s[j] == "\\" else 1
                buf.append(s[i:j + 1])
                i = j + 1
                continue
            buf.append(c)
            i += 1
        out[ln.no] = "".join(buf)
    return out


STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')


def blank_strings(code: str) -> str:
    """Code shapes are matched with string literals emptied: a message naming an API is not a call."""
    return STRING_LITERAL.sub('""', code)


def strip_hash(text: str) -> str:
    """YAML: drop a `#` comment that starts the line or follows whitespace, outside quotes."""
    q, i = None, 0
    while i < len(text):
        c = text[i]
        if q:
            if c == q:
                q = None
        elif c in "\"'":
            q = c
        elif c == "#" and (i == 0 or text[i - 1] in " \t"):
            return text[:i].rstrip()
        i += 1
    return text.rstrip()


def strip_xml(seq: list[Line]) -> dict[int, str]:
    out, in_comment = {}, False
    for ln in seq:
        s, buf, i = ln.text, [], 0
        while i < len(s):
            if in_comment:
                end = s.find("-->", i)
                if end < 0:
                    break
                in_comment, i = False, end + 3
                continue
            start = s.find("<!--", i)
            if start < 0:
                buf.append(s[i:])
                break
            buf.append(s[i:start])
            in_comment, i = True, start + 4
        out[ln.no] = "".join(buf)
    return out


# ---------------------------------------------------------------------------------------------
# Configuration keys


@dataclass
class Key:
    no: int
    key: str | None      # full dotted key; None when the path cannot be resolved
    partial: str         # the part that could be resolved (the key itself for a rooted line)
    own: str             # the key text written on this line
    value: str | None
    added: bool


YAML_KEY = re.compile(r"^(\s*)(-\s+)?([\"']?)([^\"'\s:#{}\[\],][^:{}\[\]]*?)\3\s*:(?:\s+(.*)|$)")


def yaml_keys(seq: list[Line]) -> list[Key]:
    out, stack, block_indent = [], [], None   # stack: [(indent, key, rooted)]
    for ln in seq:
        raw = ln.text
        if block_indent is not None:
            if not raw.strip() or len(raw) - len(raw.lstrip()) > block_indent:
                continue
            block_indent = None
        line = strip_hash(raw)
        if not line.strip():
            continue
        if line.strip() in ("---", "..."):
            stack = []
            continue
        m = YAML_KEY.match(line)
        if not m:
            continue
        indent = len(m.group(1))
        if m.group(2):
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, None, bool(stack) and stack[-1][2] or indent == 0))
            indent += len(m.group(2))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        own = m.group(4).strip()
        value = m.group(5)
        value = value.strip().strip("\"'") if value is not None else None
        names = [k for _, k, _ in stack if k is not None]
        rooted = (stack[0][2] if stack else indent == 0)
        partial = ".".join(names + [own])
        out.append(Key(ln.no, partial if rooted else None, partial, own, value or None, ln.added))
        if value in (None, "") :
            stack.append((indent, own, rooted))
        elif value and re.match(r"^[|>][-+0-9]*$", value):
            block_indent = indent
    return out


def properties_keys(seq: list[Line]) -> list[Key]:
    out = []
    for ln in seq:
        s = ln.text.strip()
        if not s or s[0] in "#!":
            continue
        m = re.match(r"^([^=:\s]+)\s*(?:[=:]\s*|\s+)(.*)$", s) or re.match(r"^([^=:\s]+)$", s)
        if not m:
            continue
        value = m.group(2).strip() if m.lastindex and m.lastindex >= 2 else None
        out.append(Key(ln.no, m.group(1), m.group(1), m.group(1), value or None, ln.added))
    return out


def config_keys(path: str, seq: list[Line]) -> list[Key]:
    return properties_keys(seq) if path.endswith(".properties") else yaml_keys(seq)


WATCHED = ([LOCK_TIMEOUT_KEY, LOCK_CONFIRM_KEY, "essentials.reactive-bean-post-processor-enabled",
            "essentials.durable-queues.transactional-mode"]
           + ["essentials.durable-queues." + k for k in QUEUE_STATISTICS_KEYS]
           + ["spring.data.mongodb." + k for k in MONGO_MOVED])
WATCHED_CANON = [canon(k) for k in WATCHED]
GENERIC_LEAVES = {canon(x) for x in ("uri", "host", "port", "database", "username", "password", "protocol",
                                      "enabled", "bundle")}


def watched_suffix(partial: str) -> str | None:
    """The watched key an unrooted YAML path could be the tail of — only when that tail is telling."""
    p = canon(partial)
    segs = partial.split(".")
    if len(segs) < 2 and p in GENERIC_LEAVES:
        return None
    for key, c in zip(WATCHED, WATCHED_CANON):
        if c == p or c.endswith("." + p):
            return key
    return None


def parse_duration(value: str | None) -> float | None:
    """Spring Boot's Duration forms, in seconds: 15s, 500ms, 2m, PT15S, a bare number is milliseconds."""
    if value is None:
        return None
    v = value.strip().strip("\"'")
    m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*(ns|us|ms|s|m|h|d)?", v)
    if m:
        n, unit = float(m.group(1)), m.group(2) or "ms"
        return n * {"ns": 1e-9, "us": 1e-6, "ms": 1e-3, "s": 1, "m": 60, "h": 3600, "d": 86400}[unit]
    m = re.fullmatch(r"(?i)P(?:(\d+)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?", v)
    if m and any(m.groups()):
        d, h, mi, s = (float(g) if g else 0.0 for g in m.groups())
        return d * 86400 + h * 3600 + mi * 60 + s
    return None


def truthy(value: str | None) -> bool:
    return (value or "").strip().strip("\"'").lower() == "true"


# ---------------------------------------------------------------------------------------------
# The scan


class Scan:
    def __init__(self, root: Path | None):
        self.root = root
        self.findings: list[Finding] = []
        self.not_run: list[dict] = []
        self.scanned = 0
        self.changed = 0

    def add(self, *a, **kw):
        self.findings.append(Finding(*a, **kw))

    def run(self, files: list[FileDiff]):
        for fd in files:
            k = kind_of(fd.path)
            if k == "doc" or not fd.added():
                continue
            self.scanned += 1
            ctx = Context(fd, self.root)
            if k == "config":
                self.config_file(fd, ctx)
            elif k == "source":
                self.source_file(fd, ctx)
            elif k == "pom":
                self.pom_file(fd, ctx)
            elif k == "gradle":
                self.gradle_file(fd)
            if k != "source":
                self.env_vars(fd)
        return self

    # -- configuration files ------------------------------------------------------------------

    def config_file(self, fd: FileDiff, ctx: Context):
        keys = [k for seq in ctx.sequences() for k in config_keys(fd.path, seq)]
        flat_file = fd.path.endswith(".properties")
        lock = {}
        for k in keys:
            if k.key is not None and canon(k.key) in (canon(LOCK_TIMEOUT_KEY), canon(LOCK_CONFIRM_KEY)):
                name = LOCK_TIMEOUT_KEY if canon(k.key) == canon(LOCK_TIMEOUT_KEY) else LOCK_CONFIRM_KEY
                lock.setdefault(name, k)
            if not k.added:
                continue
            if k.key is None:
                watched = watched_suffix(k.partial)
                if watched:
                    check = self.check_for_key(watched)
                    self.not_run.append({"checks": [check],
                                         "reason": f"{fd.path}:{k.no}: YAML key `…{k.partial}` has no parent keys in "
                                                   f"the diff; re-run with --root at the checked-out tree"})
                continue
            self.key_rules(fd.path, k.no, k.key, k.value, flat=flat_file or k.own == k.key, literal=None)
        self.lock_rule(fd.path, lock, whole=ctx.whole is not None)

    @staticmethod
    def check_for_key(key: str) -> str:
        c = canon(key)
        if c in MONGO_CANON:
            return "ess-088-mongo-key"
        if c in QUEUE_STATS_CANON:
            return "ess-097-queue-statistics"
        if c in (canon(LOCK_TIMEOUT_KEY), canon(LOCK_CONFIRM_KEY)):
            return "ess-050-lock-confirmation"
        if c == canon("essentials.durable-queues.transactional-mode"):
            return "ess-103-transactional-mode"
        return "ess-089-reactive-bpp-disabled"

    def key_rules(self, path, no, key, value, flat, literal):
        """One key = value, from a config file (literal None) or a string literal in source."""
        c = canon(key)
        if c in MONGO_CANON:
            to = "spring.mongodb." + MONGO_CANON[c]
            if literal is None:
                self.add("ess-088-mongo-key", path, no,
                         f"`{key}` is not bound by Spring Boot 4 — the client silently falls back to mongodb://localhost/test",
                         f"rename to `{to}`" + ("" if flat else " (move the key under `spring.mongodb`)"),
                         [{"op": "rename-config-key", "file": path, "line": no, "from": key, "to": to}],
                         mechanical=flat, match=key)
            else:
                self.add("ess-088-mongo-key", path, no,
                         f"property name `{key}` in a string literal is not bound by Spring Boot 4",
                         f"rename to `{to}`",
                         [{"op": "replace-text", "file": path, "line": no, "from": key, "to": to}],
                         mechanical=True, match=key)
        elif c == canon("essentials.reactive-bean-post-processor-enabled") and (value or "").strip().lower() == "false":
            self.add("ess-089-reactive-bpp-disabled", path, no,
                     f"`{key}=false` unwires every CommandHandler and EventHandler bean — send(...) finds no handler at runtime",
                     "remove the property (the default is true)",
                     [] if literal else [{"op": "delete-config-key", "file": path, "line": no, "key": key}],
                     mechanical=literal is None, match=f"{key}={value}")
        elif c == canon("essentials.durable-queues.transactional-mode"):
            fully = canon(value or "") == "fullytransactional"
            self.add("ess-103-transactional-mode", path, no,
                     f"`{key}` binds to nothing — the mode is retired and every queue operation runs in its own transaction"
                     + ("; a handler's rollback no longer undoes the delivery attempt" if fully else ""),
                     "delete the key" + ("; make the handler idempotent — its rollback no longer undoes the delivery" if fully else ""),
                     [] if literal else [{"op": "delete-config-key", "file": path, "line": no, "key": key}],
                     mechanical=literal is None, severity=None if fully else "Advisory", match=key)
        elif c in QUEUE_STATS_CANON:
            enabling = QUEUE_STATS_CANON[c] == "enable-queue-statistics" and truthy(value)
            self.add("ess-097-queue-statistics", path, no,
                     f"`{key}` binds to nothing — the queue statistics feature is removed, and its trigger, function "
                     "and table are dropped on the first start",
                     "delete the key" + ("; export what you still need from the statistics table before upgrading" if enabling else ""),
                     [] if literal else [{"op": "delete-config-key", "file": path, "line": no, "key": key}],
                     mechanical=literal is None, severity="Should-fix" if enabling else None, match=key)

    def lock_rule(self, path, lock: dict, whole: bool):
        added = [k for k in lock.values() if k.added]
        if not added:
            return
        vals, from_default = {}, []
        for name in (LOCK_TIMEOUT_KEY, LOCK_CONFIRM_KEY):
            k = lock.get(name)
            v = parse_duration(k.value) if k else None
            if k is not None and v is None:
                return          # a placeholder or an unparseable value: nothing to compare
            if k is None:
                from_default.append(name)
                v = LOCK_DEFAULTS[name]
            vals[name] = v
        timeout, confirm = vals[LOCK_TIMEOUT_KEY], vals[LOCK_CONFIRM_KEY]
        if confirm * 2 <= timeout:
            return
        at = min(added, key=lambda k: k.no)
        default_note = (f" ({', '.join(n.rsplit('.', 1)[1] for n in from_default)} taken as the starter default)"
                        if from_default else "")
        if confirm > timeout:
            msg = (f"lock-confirmation-interval {confirm:g}s is longer than lock-time-out {timeout:g}s — "
                   f"FencedLockManagerSettings refuses it at startup{default_note}")
            sev = "Blocking"
        else:
            msg = (f"lock-confirmation-interval {confirm:g}s is not at least 2x shorter than lock-time-out "
                   f"{timeout:g}s — one slow confirmation lets the lock expire{default_note}")
            sev = None
        self.add("ess-050-lock-confirmation", path, at.no, msg,
                 "keep lock-confirmation-interval 2-3x shorter than lock-time-out",
                 mechanical=False, kind="candidate" if from_default else None, severity=sev,
                 match=f"{at.key}={at.value}")

    # -- environment variables ----------------------------------------------------------------

    ENV = re.compile(r"(?:^|[\s\"'\-]|ENV\s+|name:\s*[\"']?)(SPRING_DATA_MONGODB_([A-Z0-9_]+))(?=[\"']?\s*[=:]|\s+\S|[\"']?\s*$)")

    def env_vars(self, fd: FileDiff):
        for ln in fd.added():
            text = strip_hash(ln.text) if not fd.path.endswith(".xml") else ln.text
            for m in self.ENV.finditer(text):
                if "${" + m.group(1) in text:
                    continue
                suffix = m.group(2).lower().replace("_", ".")
                target = MONGO_CANON.get(canon("spring.data.mongodb." + suffix))
                if target is None:
                    continue
                to = "SPRING_MONGODB_" + target.replace(".", "_").replace("-", "").upper()
                self.add("ess-088-mongo-env", fd.path, ln.no,
                         f"environment variable `{m.group(1)}` binds spring.data.mongodb.*, which Spring Boot 4 ignores — "
                         "the client falls back to mongodb://localhost/test",
                         f"rename to `{to}`",
                         [{"op": "replace-text", "file": fd.path, "line": ln.no, "from": m.group(1), "to": to}],
                         mechanical=True, match=m.group(1))

    # -- Java / Kotlin ------------------------------------------------------------------------

    LITERAL_KEY = re.compile(r"[\"'](\s*)((?:spring\.data\.mongodb|essentials)\.[\w.\-]+)(?:\s*[=:]\s*([^\"']*))?[\"']")
    MAPPER = re.compile(r"(?<![\w.])(?:new\s+)?(ObjectMapper|JsonMapper)\s*\(|(?<![\w.])JsonMapper\s*\.\s*builder\s*\("
                        r"|(?<![\w.])(jacksonObjectMapper|jacksonMapperBuilder)\s*\(|(?<![\w.])jsonMapper\s*[({]")
    LOCAL_BUS = re.compile(r"(?<![\w.])LocalCommandBus\s*(?:\(|\.\s*builder\s*\()")
    APPEND = re.compile(r"\.appendToStream\s*\(")
    JDBI = re.compile(r"(?<![\w.])Jdbi\s*\.\s*create\s*\(")
    ASYNC_SUB = re.compile(r"\.subscribeToAggregateEventsAsynchronously\s*\(")
    KOTLIN_CONVERTER = re.compile(r"(?<![\w.])KotlinValueTypeConverter\s*\(")
    NOTIFY_INSTALL = re.compile(r"(?<![\w])enableNotifyTriggerInstallation\s*\(")
    APPEND_OP = re.compile(r"(?<![\w.])AppendToStream\s*(?:<[^()]*>)?\s*\(")
    OPTIONAL_ARG = re.compile(r"\bOptional\s*\.\s*(?:of|ofNullable|empty)\s*\(")
    KEY_USING = re.compile(r"@(?:com\.fasterxml\.jackson\.databind\.annotation\.)?JsonDeserialize\s*\([^)]*\bkeyUsing\b")
    J2_ANNOTATION_IMPORT = re.compile(r"^\s*import\s+com\.fasterxml\.jackson\.databind\.annotation\.([\w*]+)")
    DOCDB_ID_IMPORT = re.compile(r"^\s*import\s+dk\.trustworks\.essentials\.components\.document_db\.annotations\.(Id|\*)\s*;")
    DOCDB_ID = re.compile(r"@(?:dk\.trustworks\.essentials\.components\.document_db\.annotations\.)?Id\b(?!\s*\()")
    FIELD = re.compile(r"^\s*((?:(?:private|protected|public|static|final|transient|volatile)\s+)*)"
                       r"[\w.<>\[\],? ]+?\s+\w+\s*(?:=[^;]*)?;\s*$")

    def source_file(self, fd: FileDiff, ctx: Context):
        main = not is_test(fd.path)
        java = fd.path.endswith(".java")
        code = {}
        for seq in ctx.sequences():
            code.update(strip_code(seq))
        all_code = [code.get(ln.no, "") for ln in ctx.all_lines()]
        j2_deserialize = any(re.match(r"^\s*import\s+com\.fasterxml\.jackson\.databind\.annotation\.(JsonDeserialize|\*)\b", c)
                             for c in all_code)
        j3_deserialize = any(re.match(r"^\s*import\s+tools\.jackson\.databind\.annotation\.(JsonDeserialize|\*)\b", c)
                             for c in all_code)
        docdb_id = any(self.DOCDB_ID_IMPORT.match(c) for c in all_code)
        order = [ln.no for ln in ctx.all_lines()]
        nxt = {a: b for a, b in zip(order, order[1:])}
        added = {ln.no for ln in fd.added()}

        for ln in fd.added():
            lit = code.get(ln.no, "")
            if not lit.strip():
                continue
            c = blank_strings(lit)
            # configuration keys in string literals (tests included: a test context binds them too)
            for m in self.LITERAL_KEY.finditer(lit):
                key, value = m.group(2).rstrip("."), m.group(3)
                self.key_rules(fd.path, ln.no, key, value, flat=True, literal=m.group(0))
            key_using = self.KEY_USING.search(c)
            if key_using:
                fq = "@com.fasterxml.jackson.databind.annotation.JsonDeserialize" in c
                if fq or (j2_deserialize and not j3_deserialize):
                    self.add("ess-021-j2-key-using", fd.path, ln.no,
                             "a Jackson 2 @JsonDeserialize(keyUsing = …) — Jackson 3 never reads it, so the key "
                             "deserializer silently stops applying",
                             "remove it (types-jackson3 handles value-type map keys); for a custom key deserializer use "
                             "tools.jackson.databind.annotation.JsonDeserialize", mechanical=False,
                             match=key_using.group(0))
                elif not j3_deserialize:
                    self.add("ess-021-j2-key-using", fd.path, ln.no,
                             "@JsonDeserialize(keyUsing = …) whose import is not in the diff — if it is Jackson 2's "
                             "(com.fasterxml.jackson.databind.annotation), Jackson 3 ignores it",
                             "check the import; remove a Jackson 2 annotation or switch to "
                             "tools.jackson.databind.annotation.JsonDeserialize", mechanical=False, kind="candidate",
                             match=key_using.group(0))
            m = self.J2_ANNOTATION_IMPORT.match(c)
            if m and not any(f.check == "ess-021-j2-key-using" and f.file == fd.path for f in self.findings) \
                    and not (m.group(1) in ("JsonDeserialize", "*") and any(self.KEY_USING.search(code.get(n, "")) for n in added)):
                self.add("ess-021-j2-databind-annotation", fd.path, ln.no,
                         f"imports Jackson 2's com.fasterxml.jackson.databind.annotation.{m.group(1)} — Jackson 3 does "
                         "not read that package, so the annotation silently stops applying",
                         "use tools.jackson.databind.annotation instead, or drop the annotation if the Essentials "
                         "modules already cover it", mechanical=False, match=m.group(0).strip())
            if self.NOTIFY_INSTALL.search(c) and not re.search(r"\b(void|fun)\s+enableNotifyTriggerInstallation", c):
                self.add("ess-110-notify-trigger-installation", fd.path, ln.no,
                         "enableNotifyTriggerInstallation(...) runs its trigger DDL beside the schema harness — "
                         "validate/emit never see the trigger",
                         "use enableNotifyTriggers(tableName -> …) (takes a Consumer<String>, not the installer)",
                         mechanical=False, match="enableNotifyTriggerInstallation(")
            m = self.APPEND_OP.search(c)
            if m and self.OPTIONAL_ARG.search(c[m.end():]):
                self.add("ess-104-append-to-stream-optional", fd.path, ln.no,
                         "AppendToStream(...) given an Optional — no constructor takes one, so this binds to the varargs "
                         "constructor, which throws IllegalArgumentException",
                         "pass the event order as a Long (null for none): AppendToStream(type, id, Long, List), or use "
                         "AppendToStream.builder()", mechanical=False, match=m.group(0) + "…Optional")
            if java and docdb_id and self.DOCDB_ID.search(c):
                self.docdb_id(fd, ln, c, code, nxt, added)
            if not main:
                continue
            m = self.MAPPER.search(c)
            if m:
                self.add("ess-052-hand-built-mapper", fd.path, ln.no,
                         "a hand-built Jackson mapper — if it serializes events, queue payloads, commands or documents, "
                         "persisted JSON drifts from the Essentials wire format",
                         "for persistence use EssentialsObjectMappers.createJSONSerializer() / "
                         "EssentialsJSONEventSerializers.create(); a web-only mapper is fine", mechanical=False,
                         match=m.group(0))
            m = self.LOCAL_BUS.search(c)
            if m:
                self.add("ess-016-local-command-bus", fd.path, ln.no,
                         "an in-memory LocalCommandBus — commands sent with sendAndDontWait() are lost on restart",
                         "use the starter's DurableLocalCommandBus when fire-and-forget commands must survive a restart",
                         mechanical=False, match=m.group(0))
            m = self.APPEND.search(c)
            if m and "EventOrder" not in c and not self.APPEND_OP.search(c[m.end():]):
                self.add("ess-056-append-without-order", fd.path, ln.no,
                         "appendToStream(...) without an expected EventOrder skips optimistic concurrency — concurrent "
                         "writers interleave silently",
                         "pass the EventOrder the decision was based on (EventOrder.NO_EVENTS_PREVIOUSLY_PERSISTED for a new stream)",
                         mechanical=False, match=m.group(0))
            m = self.JDBI.search(c)
            if m and "TransactionAwareDataSourceProxy" not in c:
                self.add("ess-064-jdbi-create", fd.path, ln.no,
                         "Jdbi.create(...) on a plain DataSource — JDBI writes ignore the Spring transaction",
                         "wrap the DataSource: Jdbi.create(new TransactionAwareDataSourceProxy(dataSource)), or use the "
                         "starter's Jdbi bean", mechanical=False, match=m.group(0))
            m = self.ASYNC_SUB.search(c)
            if m:
                self.add("ess-058-async-subscription", fd.path, ln.no,
                         "a direct async subscription — a failing handler is logged at ERROR and the event skipped "
                         "(SubscriptionErrorPolicy.skip() is the default)",
                         "set a SubscriptionErrorPolicy on the subscription manager (retryThenSkip/stop), or use an "
                         "EventProcessor", mechanical=False, match=m.group(0))
            m = self.KOTLIN_CONVERTER.search(c)
            if m:
                self.add("ess-032-kotlin-converter-by-hand", fd.path, ln.no,
                         "KotlinValueTypeConverter registered by hand — EssentialsWebMvcConfigurer/EssentialsWebFluxConfigurer "
                         "already register it when kotlin-reflect is present",
                         "drop it if one of those configurers is @Import-ed", mechanical=False, match=m.group(0))

    def docdb_id(self, fd, ln, c, code, nxt, added):
        m = self.DOCDB_ID.search(c)
        if m is None:
            return
        rest = c[m.end():]
        target_no, decl = ln.no, rest
        if not rest.strip():
            n = nxt.get(ln.no)
            while n is not None and (not code.get(n, "").strip() or code.get(n, "").strip().startswith("@")):
                n = nxt.get(n)
            if n is None:
                return
            target_no, decl = n, code.get(n, "")
        field_m = None if "(" in decl else self.FIELD.match(decl)
        if field_m is None:
            return      # a method or something else — not the field this trap is about
        if target_no not in added and ln.no not in added:
            return
        mods = field_m.group(1).split()
        if "public" in mods:
            return
        weaker = next((w for w in ("private", "protected") if w in mods), None)
        ops = ([{"op": "replace-text", "file": fd.path, "line": target_no, "from": weaker + " ", "to": "public "}]
               if weaker else [])
        self.add("ess-080-document-db-private-id", fd.path, target_no,
                 "document-db @Id field is not public — the first save/update/delete throws IllegalCallableAccessException",
                 "make the @Id field public (it is read by field access)", ops, mechanical=bool(weaker),
                 match="@Id " + decl.strip())

    # -- build files --------------------------------------------------------------------------

    J2_ARTIFACT = re.compile(r"<artifactId>\s*(types-jackson|immutable-jackson)\s*</artifactId>")

    def pom_file(self, fd: FileDiff, ctx: Context):
        for seq in ctx.sequences():
            xml = strip_xml(seq)
            nos = [ln.no for ln in seq]
            for i, ln in enumerate(seq):
                if not ln.added:
                    continue
                m = self.J2_ARTIFACT.search(xml.get(ln.no, ""))
                if not m:
                    continue
                opener, start = None, None
                for j in range(i, -1, -1):
                    t = xml.get(nos[j], "")
                    o = re.search(r"<(dependency|exclusion|plugin|parent)>", t)
                    if o and (j < i or t.find(o.group(0)) < t.find("<artifactId>")):
                        opener, start = o.group(1), nos[j]
                        break
                    if j < i and re.search(r"</(dependency|exclusion|plugin|parent)>", t):
                        break
                if opener in ("exclusion", "plugin", "parent"):
                    continue
                group = None
                for j in range(i - 1 if start is None else nos.index(start), len(seq)):
                    t = xml.get(nos[j], "")
                    g = re.search(r"<groupId>\s*([^<\s]+)\s*</groupId>", t)
                    if g:
                        group = g.group(1)
                        break
                    if re.search(r"</(dependency|exclusion)>", t):
                        break
                if group is not None and group != "dk.trustworks.essentials":
                    continue
                confirmed = group == "dk.trustworks.essentials" and start is not None
                a = m.group(1)
                self.add("ess-094-jackson2-module", fd.path, ln.no,
                         f"{a} is the 0.50 Jackson 2 module — on the classpath it makes EssentialsJacksonModules.modules() "
                         "throw IllegalStateException at startup",
                         f"depend on {a}3 instead",
                         [{"op": "set-artifact-id", "pom": fd.path, "line": start, "groupId": "dk.trustworks.essentials",
                           "from": a, "to": a + "3"}] if confirmed else [],
                         mechanical=confirmed, kind=None if confirmed else "candidate", match=m.group(0))

    GRADLE_J2 = re.compile(r"dk\.trustworks\.essentials:(types-jackson|immutable-jackson)(?=[:\"'])")

    def gradle_file(self, fd: FileDiff):
        for ln in fd.added():
            if ln.text.lstrip().startswith("//"):
                continue
            m = self.GRADLE_J2.search(ln.text)
            if m:
                a = m.group(1)
                self.add("ess-094-jackson2-module", fd.path, ln.no,
                         f"{a} is the 0.50 Jackson 2 module — on the classpath it makes EssentialsJacksonModules.modules() "
                         "throw IllegalStateException at startup",
                         f"depend on {a}3 instead",
                         [{"op": "replace-text", "file": fd.path, "line": ln.no, "from": m.group(0), "to": m.group(0) + "3"}],
                         mechanical=True, match=m.group(0))


# ---------------------------------------------------------------------------------------------
# Running


def git(root: Path, *args) -> str:
    try:
        r = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True, check=False)
    except OSError as exc:
        raise UsageError(f"cannot run git: {exc}")
    if r.returncode != 0:
        raise UsageError(f"git {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def diff_from_git(root: Path, base: str, head: str | None):
    top = Path(git(root, "rev-parse", "--show-toplevel").strip())
    args = ["diff", "--no-color", "--no-ext-diff", "--no-renames", "--merge-base", base]
    if head:
        args.append(head)
    text = git(top, *args)
    untracked = [] if head else [p for p in git(top, "ls-files", "--others", "--exclude-standard").splitlines() if p]
    return top, text, untracked


def scan_text(text: str, root: Path | None) -> Scan:
    files = parse_diff(text)
    validate_diff(text, files)
    s = Scan(root).run(files)
    s.changed = len(files)
    s.findings.sort(key=lambda f: (SEVERITY_RANK[f.severity or f.sig().severity], f.file, f.line, f.check))
    return s


def check_catalogue(traps):
    missing = sorted({s.id for s in SIGNATURES} - set(traps))
    if missing:
        raise UsageError(f"signature ids not active in {TRAPS_REL}: {', '.join(missing)} — the catalogue is the "
                         "source of the ids; retire the signature or fix the catalogue")


def report(scan: Scan, traps, source: dict, as_json: bool, out) -> dict:
    items = [f.as_dict(traps) for f in scan.findings]
    counts = {s: sum(1 for i in items if i["severity"] == s) for s in SEVERITIES}
    kinds = {k: sum(1 for i in items if i["kind"] == k) for k in ("confirmed", "candidate")}
    doc = {"tool": "review-scan", "source": source,
           "files": {"changed": scan.changed, "scanned": scan.scanned},
           "notRun": scan.not_run, "findings": items, "counts": counts, "kinds": kinds}
    if as_json:
        json.dump(doc, out, indent=2)
        out.write("\n")
        return doc
    for i in items:
        out.write(f"{i['file']}:{i['line']}: {i['id']} {i['severity']} {i['kind']}: {i['message']}\n"
                  f"    fix: {i['fix']['text']}{'  [mechanical]' if i['fix']['mechanical'] else ''}\n"
                  f"    see: {i['link']} → {i['section']}\n")
    for n in scan.not_run:
        out.write(f"not run ({', '.join(n['checks'])}): {n['reason']}\n")
    out.write(f"review-scan: {len(items)} finding(s) ({kinds['confirmed']} confirmed, {kinds['candidate']} candidate) "
              f"in {scan.scanned} scanned of {scan.changed} changed file(s)")
    if source.get("untracked"):
        out.write(f"; {len(source['untracked'])} untracked file(s) not in the diff")
    out.write("\n")
    return doc


def list_signatures(traps, as_json, out):
    rows = [{"check": s.check, "id": s.id, "kind": s.kind, "severity": s.severity, "scope": s.scope,
             "evidence": list(s.evidence), "symptom": traps[s.id].symptom if s.id in traps else None,
             "link": f"{TRAPS_REL}#{s.id.lower()}"} for s in SIGNATURES]
    if as_json:
        json.dump(rows, out, indent=2)
        out.write("\n")
    else:
        for r in rows:
            out.write(f"{r['id']}  {r['check']:<38} {r['kind']:<9} {r['severity']:<10} {r['scope']}\n")
    return 0


# ---------------------------------------------------------------------------------------------
# Self-test: tests/review/signatures/*.diff carry their own expectations in the preamble git ignores:
#   # expect: <file>:<line> <check> [confirmed|candidate] [Blocking|Should-fix|Advisory] [mechanical]
#   # expect-not: <file>:<line> <check>       (a look-alike the check must not fire on; counts as its negative)
#   # expect-not-run: <file>:<line> <check>
#   # root: <dir relative to the fixture>    (context tree; default: none, the diff alone)
# Every reported finding must be expected, every expectation reported, and every signature must have at
# least one positive and one negative case across the fixtures.

EXPECT = re.compile(r"^#\s*(expect|expect-not|expect-not-run):\s*(\S+):(\d+)\s+(\S+)((?:\s+\S+)*)\s*$")


def self_test(traps, out) -> int:
    failures = 0
    positives, negatives = set(), set()
    try:
        check_catalogue(traps)
    except UsageError as exc:
        out.write(f"FAIL catalogue: {exc}\n")
        failures += 1
    for s in SIGNATURES:
        if not s.evidence:
            out.write(f"FAIL {s.check}: no 0.60 evidence recorded\n")
            failures += 1
    fixtures = sorted(FIXTURES.glob("*.diff"))
    if not fixtures:
        out.write(f"FAIL no fixtures under {FIXTURES}\n")
        return 1
    for fx in fixtures:
        rel = fx.relative_to(PLUGIN).as_posix()
        text = fx.read_text(encoding="utf-8")
        want, want_not, want_nr, root = {}, set(), set(), None
        for line in text.splitlines():
            if line.startswith("diff ") or line.startswith("--- "):
                break
            m = EXPECT.match(line)
            if m:
                key = (m.group(2), int(m.group(3)), m.group(4))
                if m.group(4) not in BY_CHECK:
                    out.write(f"FAIL {rel}: unknown check {m.group(4)}\n")
                    failures += 1
                if m.group(1) == "expect":
                    want[key] = m.group(5).split()
                elif m.group(1) == "expect-not":
                    want_not.add(key)
                else:
                    want_nr.add(key)
            r = re.match(r"^#\s*root:\s*(\S+)\s*$", line)
            if r:
                root = (fx.parent / r.group(1)).resolve()
        try:
            scan = scan_text(text, root)
        except UsageError as exc:
            out.write(f"FAIL {rel}: {exc}\n")
            failures += 1
            continue
        got = {(f.file, f.line, f.check): f for f in scan.findings}
        bad = 0
        for key, attrs in want.items():
            f = got.get(key)
            if f is None:
                out.write(f"FAIL {rel}: expected {key[2]} at {key[0]}:{key[1]}, not reported\n")
                bad += 1
                continue
            d = f.as_dict(traps)
            for a in attrs:
                ok = ((a in ("confirmed", "candidate") and d["kind"] == a)
                      or (a in SEVERITIES and d["severity"] == a)
                      or (a == "mechanical" and d["fix"]["mechanical"])
                      or (a == "manual" and not d["fix"]["mechanical"]))
                if not ok:
                    out.write(f"FAIL {rel}: {key[2]} at {key[0]}:{key[1]} is not {a} "
                              f"({d['kind']}, {d['severity']}, mechanical={d['fix']['mechanical']})\n")
                    bad += 1
            positives.add(key[2])
        for key in sorted(set(got) - set(want)):
            out.write(f"FAIL {rel}: unexpected {key[2]} at {key[0]}:{key[1]}: {got[key].message}\n")
            bad += 1
        for key in want_not:
            if key in got:
                bad += 1   # already reported as unexpected above
            negatives.add(key[2])
        nr = {(n["reason"].split(":")[0], int(n["reason"].split(":")[1]), c)
              for n in scan.not_run for c in n["checks"]}
        for key in sorted(want_nr - nr):
            out.write(f"FAIL {rel}: expected not-run {key[2]} at {key[0]}:{key[1]}\n")
            bad += 1
        for key in sorted(nr - want_nr):
            out.write(f"FAIL {rel}: unexpected not-run {key[2]} at {key[0]}:{key[1]}\n")
            bad += 1
        if bad:
            failures += bad
        else:
            out.write(f"ok   {rel}: {len(want)} finding(s), {len(want_not)} look-alike(s), {len(want_nr)} not-run\n")
    for s in SIGNATURES:
        if s.check not in positives:
            out.write(f"FAIL {s.check}: no fixture expects it to fire\n")
            failures += 1
        if s.check not in negatives:
            out.write(f"FAIL {s.check}: no fixture names a look-alike it must not fire on (# expect-not)\n")
            failures += 1
    out.write(f"self-test: {len(fixtures)} fixture(s), {len(SIGNATURES)} signature(s), "
              f"{'FAILED' if failures else 'ok'}\n")
    return 1 if failures else 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="review-scan",
        description="Trap signatures in the added lines of a diff, with ESS-NNN ids from LLM-traps.md. "
                    "Deterministic; reports, never writes.")
    src = parser.add_mutually_exclusive_group()
    src.add_argument("--diff", metavar="FILE|-")
    src.add_argument("--base", metavar="REF")
    parser.add_argument("--head", metavar="REF")
    parser.add_argument("--root", metavar="DIR")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--fail-on", choices=sorted(FAIL_ON), default="advisory")
    parser.add_argument("--traps", metavar="PATH", default=str(TRAPS))
    parser.add_argument("--signatures", action="store_true", help="print the signature table")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    out = sys.stdout
    try:
        traps = load_traps(Path(args.traps))
        if args.self_test:
            return self_test(traps, out)
        check_catalogue(traps)
        if args.signatures:
            return list_signatures(traps, args.json, out)
        if args.head and not args.base:
            raise UsageError("--head needs --base")
        if args.base:
            top, text, untracked = diff_from_git(Path(args.root or "."), args.base, args.head)
            root = None if args.head else top
            source = {"mode": "git", "base": args.base, "head": args.head or "working tree", "root": str(top),
                      "untracked": untracked}
        elif args.diff:
            try:
                text = sys.stdin.read() if args.diff == "-" else Path(args.diff).read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError) as exc:
                raise UsageError(f"cannot read the diff {args.diff}: {exc}")
            root = Path(args.root) if args.root else Path(".")
            source = {"mode": "diff", "diff": args.diff, "root": str(root.resolve())}
        else:
            raise UsageError("give --diff <file|->, --base <ref>, --signatures or --self-test")
        if root is not None and not root.is_dir():
            raise UsageError(f"--root {root} is not a directory")
        scan = scan_text(text, root)
        doc = report(scan, traps, source, args.json, out)
        floor = SEVERITY_RANK[FAIL_ON[args.fail_on]]
        return 1 if any(SEVERITY_RANK[f["severity"]] <= floor for f in doc["findings"]) else 0
    except UsageError as exc:
        print(f"review-scan: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
