#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml==6.0.3"]
# ///
"""slice-source — syntactic facts from the Java and Kotlin sources of a slice-law project.

Why this exists
---------------
A slice manifest describes the code; nothing in a build checks that it still does. The facts that
decide whether it does are syntactic — which event type a `@MessageHandler` takes, which route and
`params` a request mapping carries, which package a file declares — and a model reading them by eye
counts the `@MessageHandler` in a javadoc, reads `Placed` where `import …OrderPlaced as Placed` says
`OrderPlaced`, and accepts a discriminator bound in the handler next door. This script reads them
deterministically, and says so loudly when it cannot.

It is a *syntactic* reader: a tokenizer that drops comments and string contents first, then
bracket-matched scanning. It resolves a type name through the file's imports (Kotlin `as` aliases
included), a fully-qualified name, the file's package and a project-wide declaration index. It does
not infer types: a handler typed as a generic stays that name, and a sealed parent is expanded only
to the subtypes declared inside ROOT. WebFlux functional `RouterFunction` routes are reported as not
analysed. Anything it cannot read is listed under `unparsed` — never skipped, never guessed.

Judgement stays with the command that calls it: the service-entity lane criterion ("state loaded,
mutated and saved in place"), gate 11(a), and every judgement field in
`references/slice/manifest-reconciliation.md` §1.

Usage
-----
    slice-source.py [ROOT] [--json] [--check] [--bc NAME]... [--quiet]

    ROOT        directory to scan (default: the current directory)
    --json      emit JSON on stdout (facts, or findings with --check)
    --check     compare every slice.yaml with its source: gates 6 (endpoints and query
                discriminators, per handler), 11(b) (handled events declared) and the
                signal-count rows of 14 (write-style lane). Needs pyyaml.
    --bc NAME   restrict to these bounded contexts (directory name); repeatable
    --quiet     text mode: findings and unparsed only

Exit codes
----------
    0   nothing to report: no findings, nothing unparsed, nothing unverified
    1   findings (--check)
    2   could not run — ROOT not a directory, bad arguments, --check without pyyaml
    3   incomplete, NOT a pass: no findings, but some source was unparsed or (with --check)
        some check had nothing to run against

Dependencies
------------
The facts mode is stdlib-only. `--check` reads manifests and needs `pyyaml`; run the script with
`uv run --script`, which installs the pin above, or `pip install pyyaml`.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

FORMAT = 1

ROLES = {"use_cases": "command", "views": "view", "automations": "automation", "external_systems": "translation"}
SKIP_DIRS = {"target", "build", "node_modules", "dist"}
NON_MAIN_SOURCE_SETS = {"test", "tests", "it", "integrationTest", "testFixtures", "intTest", "jmh"}
SOURCE_EXT = {".java": "java", ".kt": "kotlin"}
NOT_SLICE_FILES = {"slice.yaml", "CLAUDE.md"}

EVENT_HANDLER_ANNS = {"MessageHandler", "Handler", "EventListener"}
# `@Handler` on an AnnotatedCommandHandler is a command handler (it accepts @Handler or @CmdHandler)
COMMAND_HANDLER_BASES = {"AnnotatedCommandHandler", "CommandHandler"}
HANDLER_ANNS = EVENT_HANDLER_ANNS | {"CmdHandler", "EventHandler", "KafkaListener", "RabbitListener", "JmsListener"}
MAPPING_ANNS = {"GetMapping": "GET", "PostMapping": "POST", "PutMapping": "PUT", "PatchMapping": "PATCH",
                "DeleteMapping": "DELETE", "RequestMapping": None}
PROCESSOR_BASES = {"EventProcessor", "ViewEventProcessor", "InTransactionEventProcessor"}
DECIDER_BASES = {"Decider", "EventStreamDecider"}
ENVELOPES = {"OrderedMessage", "Message", "PersistedEvent", "Object", "Any"}
EVENT_STORE_SYMBOLS = {"EventStore", "ConfigurableEventStore", "PostgresqlEventStore", "AggregateType", "EventOrder",
                       "GlobalEventOrder"}
READ_MODEL_STORES = {"DocumentEntity": "document-db", "Entity": "jpa", "Document": "mongo", "Table": "jdbc"}
ROUTER_MARKERS = {"RouterFunction", "RouterFunctions", "coRouter", "router"}
COMMAND_BUS = re.compile(r"commandbus$", re.IGNORECASE)
EVENT_BUS = re.compile(r"eventbus$", re.IGNORECASE)

MODIFIERS = {
    # Java
    "public", "protected", "private", "static", "final", "abstract", "sealed", "strictfp", "default",
    "synchronized", "native", "transient", "volatile",
    # Kotlin
    "internal", "open", "override", "data", "value", "inner", "enum", "annotation", "companion", "lateinit",
    "const", "suspend", "inline", "infix", "operator", "tailrec", "external", "expect", "actual", "fun",
}
PARAM_MODIFIERS = {"final", "val", "var", "vararg", "private", "public", "protected", "internal", "override", "open",
                   "crossinline", "noinline"}
USE_SITE_TARGETS = {"file", "property", "field", "get", "set", "receiver", "param", "setparam", "delegate"}
JAVA_NOT_METHOD = {"if", "for", "while", "switch", "catch", "synchronized", "return", "new", "throw", "super", "this",
                   "assert", "else", "try", "do", "case", "yield"}
SEVERITY_ORDER = {"Blocking": 0, "Should-fix": 1, "Advisory": 2}


# --------------------------------------------------------------------------------------------------
# Lexer — comments dropped, strings kept as single tokens, so no token inside either is ever counted
# --------------------------------------------------------------------------------------------------

class LexError(Exception):
    def __init__(self, line, reason):
        super().__init__(reason)
        self.line = line
        self.reason = reason


class Tok:
    __slots__ = ("k", "t", "v", "line")

    def __init__(self, k, t, v, line):
        self.k = k  # id | str | chr | num | op
        self.t = t
        self.v = v  # decoded value of a string without templates, else None
        self.line = line

    def __repr__(self):
        return f"{self.k}:{self.t}@{self.line}"


# `{{name}}` is a plugin template placeholder; it stays part of the identifier it sits in.
IDENT_RE = re.compile(r"(?:[A-Za-z_$][A-Za-z0-9_$]*|\{\{\w+\}\})+")
NUM_RE = re.compile(r"[0-9][0-9A-Za-z_.]*")
KOTLIN_TEMPLATE_NAME = re.compile(r"`[^`\n]*`|[A-Za-z_][A-Za-z0-9_]*")
MULTI_OPS = ("...", "->", "::", "?.", "?:", "==", "!=", "&&", "||", "!!", "..", "++", "--", "+=", "-=")
ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f", "0": "\0", "s": " ", "\n": ""}


def _skip_block_comment(text, i, kotlin, line):
    """Kotlin block comments nest; Java's do not."""
    depth, j, n = 0, i, len(text)
    while j < n:
        if text.startswith("/*", j):
            depth = depth + 1 if kotlin else 1
            j += 2
        elif text.startswith("*/", j):
            depth -= 1
            j += 2
            if depth == 0:
                return j
        else:
            j += 1
    raise LexError(line, "unterminated block comment")


def _scan_char(text, i, line):
    j = i + 1
    if j < len(text) and text[j] == "\\":
        j += 2
    else:
        j += 1
    end = text.find("'", j, j + 8)
    if end < 0:
        raise LexError(line, "unterminated character literal")
    return end + 1


def _skip_template(text, j, line):
    """Past the `}` closing a Kotlin `${…}`, stepping over nested strings."""
    depth, n = 1, len(text)
    while j < n:
        ch = text[j]
        if ch == '"':
            j = _scan_string(text, j, True, line)[0]
            continue
        if ch == "'":
            j = _scan_char(text, j, line)
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    raise LexError(line, "unterminated string template")


def _scan_string(text, i, kotlin, line):
    """Returns (index after the literal, decoded value or None when templated, templated)."""
    n = len(text)
    triple = text.startswith('"""', i)
    j = i + 3 if triple else i + 1
    buf, templated = [], False
    while True:
        if j >= n:
            raise LexError(line, "unterminated string literal")
        ch = text[j]
        if triple and text.startswith('"""', j):
            while text.startswith('""""', j):
                buf.append('"')
                j += 1
            return j + 3, None if templated else "".join(buf), templated
        if not triple:
            if ch == '"':
                return j + 1, None if templated else "".join(buf), templated
            if ch == "\n":
                raise LexError(line, "unterminated string literal")
        if ch == "\\" and not (kotlin and triple):
            if j + 1 >= n:
                raise LexError(line, "unterminated string literal")
            e = text[j + 1]
            if e == "u":
                digits = text[j + 2:j + 6]
                try:
                    buf.append(chr(int(digits, 16)))
                except ValueError:
                    buf.append("\\u" + digits)
                j += 6
                continue
            buf.append(ESCAPES.get(e, e))
            j += 2
            continue
        if kotlin and ch == "$" and j + 1 < n:
            nx = text[j + 1]
            if nx == "{":
                templated = True
                j = _skip_template(text, j + 2, line)
                continue
            if nx.isalpha() or nx in "_`":
                templated = True
                name = KOTLIN_TEMPLATE_NAME.match(text, j + 1)
                if name is None:  # unreachable: nx is a letter, `_` or a backtick, all of which the pattern starts with
                    raise LexError(line, "unreadable string template")
                j = name.end()
                continue
        buf.append(ch)
        j += 1


def lex(text, kotlin):
    toks = []
    i, n, line = 0, len(text), 1
    while i < n:
        c = text[i]
        if c == "\n":
            line += 1
            i += 1
            continue
        if c in " \t\r\f\ufeff":
            i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] in "/*":
            if text[i + 1] == "/":
                j = text.find("\n", i)
                i = n if j < 0 else j
            else:
                j = _skip_block_comment(text, i, kotlin, line)
                line += text.count("\n", i, j)
                i = j
            continue
        if c == '"':
            j, value, _ = _scan_string(text, i, kotlin, line)
            toks.append(Tok("str", text[i:j], value, line))
            line += text.count("\n", i, j)
            i = j
            continue
        if c == "'":
            j = _scan_char(text, i, line)
            toks.append(Tok("chr", text[i:j], None, line))
            i = j
            continue
        if c == "`" and kotlin:
            j = text.find("`", i + 1)
            if j < 0 or "\n" in text[i:j]:
                raise LexError(line, "unterminated backtick identifier")
            toks.append(Tok("id", text[i + 1:j], None, line))
            i = j + 1
            continue
        m = IDENT_RE.match(text, i)
        if m:
            toks.append(Tok("id", m.group(), None, line))
            i = m.end()
            continue
        m = NUM_RE.match(text, i)
        if m:
            toks.append(Tok("num", m.group(), None, line))
            i = m.end()
            continue
        for op in MULTI_OPS:
            if text.startswith(op, i):
                toks.append(Tok("op", op, None, line))
                i += len(op)
                break
        else:
            toks.append(Tok("op", c, None, line))
            i += 1
    return toks


PAIRS = {")": "(", "]": "[", "}": "{"}


def match_brackets(toks):
    stack, match = [], {}
    for idx, t in enumerate(toks):
        if t.k != "op":
            continue
        if t.t in "([{":
            stack.append(idx)
        elif t.t in ")]}":
            if not stack or toks[stack[-1]].t != PAIRS[t.t]:
                raise LexError(t.line, f"unbalanced '{t.t}'")
            o = stack.pop()
            match[o] = idx
            match[idx] = o
    if stack:
        raise LexError(toks[stack[-1]].line, f"unclosed '{toks[stack[-1]].t}'")
    return match


# --------------------------------------------------------------------------------------------------
# Declarations
# --------------------------------------------------------------------------------------------------

class Ann:
    __slots__ = ("name", "target", "start", "end", "args", "line")

    def __init__(self, name, target, start, end, args, line):
        self.name, self.target, self.start, self.end, self.args, self.line = name, target, start, end, args, line

    @property
    def simple(self):
        return self.name.rsplit(".", 1)[-1]


class TypeRef:
    __slots__ = ("text", "base", "args", "nullable")

    def __init__(self, text, base, args=(), nullable=False):
        self.text, self.base, self.args, self.nullable = text, base, list(args), nullable


class Param:
    __slots__ = ("name", "type", "anns", "mods", "default", "line")

    def __init__(self, name, type_, anns, mods, default, line):
        self.name, self.type, self.anns, self.mods, self.default, self.line = name, type_, anns, mods, default, line


class TypeDecl:
    __slots__ = ("file", "name", "kind", "mods", "anns", "line", "kw", "end", "body", "params", "supers", "permits",
                 "outer", "props", "methods")

    def __init__(self, file, name, kind, mods, anns, line, kw):
        self.file, self.name, self.kind, self.mods, self.anns, self.line, self.kw = file, name, kind, mods, anns, line, kw
        self.end = kw + 1
        self.body = None
        self.params = []
        self.supers = []  # (role, TypeRef): extends / implements / Kotlin supertypes
        self.permits = []  # Java `permits` — subtypes, never supertypes
        self.outer = None
        self.props = []
        self.methods = []

    @property
    def concrete(self):
        if self.kind in ("interface", "annotation", "enum"):
            return False
        return not ({"abstract", "sealed"} & self.mods)

    def super_bases(self):
        return [tr.base.rsplit(".", 1)[-1] for _, tr in self.supers]


class Prop:
    __slots__ = ("owner", "name", "type", "anns", "static", "line", "init")

    def __init__(self, owner, name, type_, anns, static, line, init):
        self.owner, self.name, self.type, self.anns, self.static, self.line, self.init = (
            owner, name, type_, anns, static, line, init)


class Method:
    __slots__ = ("owner", "name", "anns", "params", "line", "body", "end")
    body: tuple[int, int] | None  # (start, end) token range, inclusive
    end: int | None

    def __init__(self, owner, name, anns, params, line):
        self.owner, self.name, self.anns, self.params, self.line = owner, name, anns, params, line
        self.body = None
        self.end = None


class Value:
    """An evaluated annotation argument: str, list of Value, a reference, or unreadable."""
    __slots__ = ("kind", "v", "text")

    def __init__(self, kind, v, text):
        self.kind, self.v, self.text = kind, v, text  # kind: str | list | ref | num | bad

    def strings(self):
        """Every string in the value, or None when any part is not a readable string."""
        if self.kind == "str":
            return [self.v]
        if self.kind == "list":
            out = []
            for item in self.v:
                s = item.strings()
                if s is None:
                    return None
                out.extend(s)
            return out
        return None

    def refs(self):
        if self.kind == "ref":
            return [self.v]
        if self.kind == "list":
            return [r for item in self.v for r in item.refs()]
        return []


class SrcFile:
    def __init__(self, path, rel, lang, text):
        self.path, self.rel, self.lang = path, rel, lang
        self.kotlin = lang == "kotlin"
        self.package = None
        self.imports, self.aliases, self.static_imports, self.wildcards = {}, {}, {}, []
        self.typealiases = {}
        self.types, self.methods, self.props = [], [], []
        self.anns_at, self.ann_last, self.type_at = {}, {}, {}
        self.error = None
        try:
            self.toks = lex(text, self.kotlin)
            self.match = match_brackets(self.toks)
        except LexError as exc:
            self.toks, self.match = [], {}
            self.error = exc
            return
        self.n = len(self.toks)
        self._scan_annotations()
        self._scan_header()
        self._scan_types()
        for td in self.types:
            if td.body:
                self._scan_members(td, *td.body)
        if self.kotlin:
            self._scan_members(None, -1, self.n)
        attached = {id(a) for m in self.methods for a in m.anns} | {id(a) for td in self.types for a in td.anns}
        # a handler or mapping no named class owns: an anonymous class or an object expression
        self.stray = [a for _, a in sorted(self.anns_at.items())
                      if (a.simple in HANDLER_ANNS or a.simple in MAPPING_ANNS) and id(a) not in attached]

    # -- small helpers -----------------------------------------------------------------------------

    def tok(self, i):
        return self.toks[i] if 0 <= i < self.n else None

    def is_op(self, i, text):
        t = self.tok(i)
        return t is not None and t.k == "op" and t.t == text

    def is_id(self, i, text=None):
        t = self.tok(i)
        return t is not None and t.k == "id" and (text is None or t.t == text)

    def qname(self, j):
        parts = []
        while self.is_id(j):
            parts.append(self.toks[j].t)
            if self.is_op(j + 1, ".") and self.is_id(j + 2):
                j += 2
                continue
            j += 1
            break
        return ".".join(parts), j

    def text(self, a, b):
        """Compact source text of tokens [a, b)."""
        out, prev = [], None
        for t in self.toks[a:b]:
            if prev is not None:
                if t.t == "->" or prev.t == "->":
                    out.append(" ")
                elif prev.k in ("id", "num") and t.k in ("id", "num", "str"):
                    out.append(" ")
                elif prev.t == ",":
                    out.append(" ")
                elif t.t in ("+", "?:") or prev.t in ("+", "?:"):
                    out.append(" ")
                elif prev.t == "?" and t.k == "id":
                    out.append(" ")
            out.append(t.t)
            prev = t
        return "".join(out)

    # -- annotations, header -----------------------------------------------------------------------

    def _scan_annotations(self):
        toks = self.toks
        for i, t in enumerate(toks):
            if not (t.k == "op" and t.t == "@" and self.is_id(i + 1)) or toks[i + 1].t == "interface":
                continue
            j, target = i + 1, None
            if self.kotlin and toks[j].t in USE_SITE_TARGETS and self.is_op(j + 1, ":") and self.is_id(j + 2):
                target = toks[j].t
                j += 2
            name, j = self.qname(j)
            args = None
            if self.is_op(j, "(") and (not self.kotlin or toks[j].line == toks[j - 1].line):
                args = (j, self.match[j])
                j = self.match[j] + 1
            ann = Ann(name, target, i, j, args, t.line)
            self.anns_at[i] = ann
            self.ann_last[j - 1] = ann

    def _scan_header(self):
        i = 0
        while i < self.n:
            t = self.toks[i]
            if i in self.anns_at:
                i = self.anns_at[i].end
                continue
            if t.k == "id" and t.t == "package":
                self.package, i = self.qname(i + 1)
                continue
            if t.k == "id" and t.t == "import":
                j, static = i + 1, False
                if not self.kotlin and self.is_id(j, "static"):
                    static, j = True, j + 1
                name, j = self.qname(j)
                wildcard = False
                if self.is_op(j, ".") and self.is_op(j + 1, "*"):
                    wildcard, j = True, j + 2
                alias = None
                if self.kotlin and self.is_id(j, "as") and self.toks[j].line == self.toks[j - 1].line and self.is_id(j + 1):
                    alias, j = self.toks[j + 1].t, j + 2
                if wildcard:
                    self.wildcards.append(name)
                elif static:
                    self.static_imports[name.rsplit(".", 1)[-1]] = name
                elif alias:
                    self.aliases[alias] = name
                else:
                    self.imports[name.rsplit(".", 1)[-1]] = name
                i = j
                continue
            if t.k == "op" and t.t == ";":
                i += 1
                continue
            break
        self.header_end = i

    # -- types -------------------------------------------------------------------------------------

    def prefix(self, i):
        """Annotations and modifiers immediately before token i."""
        anns, mods, k = [], set(), i - 1
        while k >= 0:
            if k in self.ann_last:
                a = self.ann_last[k]
                anns.append(a)
                k = a.start - 1
                continue
            t = self.toks[k]
            if t.k == "id" and t.t in MODIFIERS:
                if t.t == "sealed" and self.is_op(k - 1, "-") and self.is_id(k - 2, "non"):
                    mods.add("non-sealed")
                    k -= 3
                    continue
                mods.add(t.t)
                k -= 1
                continue
            break
        anns.reverse()
        return anns, mods

    def _scan_types(self):
        toks = self.toks
        for i, t in enumerate(toks):
            if t.k != "id" or t.t not in ("class", "interface", "enum", "record", "object"):
                continue
            prev = self.tok(i - 1)
            if prev is not None and prev.k == "op" and prev.t in (".", "::"):
                continue
            kind = t.t
            if prev is not None and prev.k == "op" and prev.t == "@":
                if kind != "interface" or self.kotlin:
                    continue
                kind = "annotation"
            if self.kotlin:
                if kind in ("enum", "record"):
                    continue
            else:
                if kind == "object":
                    continue
                if kind == "record" and not (self.is_id(i + 1) and (self.is_op(i + 2, "(") or self.is_op(i + 2, "<"))):
                    continue
                if kind == "enum" and not self.is_id(i + 1):
                    continue
            anns, mods = self.prefix(i - 1 if kind == "annotation" else i)
            if self.kotlin and kind == "class":
                if "enum" in mods:
                    kind = "enum"
                elif "annotation" in mods:
                    kind = "annotation"
            if kind == "object":
                if self.is_id(i + 1) and toks[i + 1].t not in ("by",):
                    name, j = toks[i + 1].t, i + 2
                elif "companion" in mods:
                    name, j = "Companion", i + 1
                else:
                    continue  # an object expression, not a declaration
            elif self.is_id(i + 1):
                name, j = toks[i + 1].t, i + 2
            else:
                continue
            td = TypeDecl(self, name, kind, mods, anns, t.line, i)
            try:
                self._type_header(td, j)
            except (IndexError, KeyError):
                self.error = self.error or LexError(t.line, f"could not read the declaration of {name}")
                continue
            self.types.append(td)
            self.type_at[i] = td
        for td in self.types:
            td.outer = self.enclosing_type(td.kw, exclude=td)

    def _type_header(self, td, j):
        toks = self.toks
        if self.is_op(j, "<"):
            j = self.skip_angles(j)
        if self.kotlin:
            k = j
            while k < self.n and (k in self.anns_at or (toks[k].k == "id" and toks[k].t in MODIFIERS)):
                k = self.anns_at[k].end if k in self.anns_at else k + 1
            if self.is_id(k, "constructor"):
                j = k + 1
            if self.is_op(j, "("):
                td.params = self.params(j)
                j = self.match[j] + 1
            if self.is_op(j, ":"):
                j += 1
                while True:
                    tr, j2 = self.typeref(j)
                    if tr is None:
                        break
                    j = j2
                    if self.is_op(j, "(") and toks[j].line == toks[j - 1].line:
                        j = self.match[j] + 1
                    if self.is_id(j, "by"):
                        j += 1
                        while j < self.n and not (self.is_op(j, ",") or self.is_op(j, "{")) and toks[j].line == toks[j - 1].line:
                            j = self.match[j] + 1 if self.is_op(j, "(") else j + 1
                    td.supers.append(("super", tr))
                    if self.is_op(j, ","):
                        j += 1
                        continue
                    break
            if self.is_id(j, "where"):
                while j < self.n and not self.is_op(j, "{"):
                    j += 1
        else:
            if td.kind == "record" and self.is_op(j, "("):
                td.params = self.params(j)
                j = self.match[j] + 1
            while j < self.n and not (toks[j].k == "op" and toks[j].t in "{;"):
                if toks[j].k == "id" and toks[j].t in ("extends", "implements", "permits"):
                    role = toks[j].t
                    j += 1
                    while True:
                        tr, j2 = self.typeref(j)
                        if tr is None:
                            break
                        (td.permits if role == "permits" else td.supers).append((role, tr))
                        j = j2
                        if self.is_op(j, ","):
                            j += 1
                            continue
                        break
                    continue
                j += 1
        if self.is_op(j, "{"):
            td.body = (j, self.match[j])
            td.end = self.match[j] + 1
        else:
            td.end = j

    def enclosing_type(self, i, exclude=None):
        best = None
        for td in self.types:
            if td is exclude or not td.body:
                continue
            lb, rb = td.body
            if lb < i < rb and (best is None or td.body[0] > best.body[0]):
                best = td
        return best

    def skip_angles(self, j):
        """Past the `>` matching the `<` at j."""
        depth = 0
        while j < self.n:
            t = self.toks[j]
            if t.k == "op":
                if t.t == "<":
                    depth += 1
                elif t.t == ">":
                    depth -= 1
                    if depth == 0:
                        return j + 1
                elif t.t in "([":
                    j = self.match[j]
                elif t.t in ";{}=":
                    return j
            j += 1
        return j

    def typeref(self, j):
        """A type reference at j: returns (TypeRef, index after) or (None, j)."""
        toks, start = self.toks, j
        while j in self.anns_at:
            j = self.anns_at[j].end
        if self.is_id(j, "suspend") and self.is_op(j + 1, "("):
            j += 1
        if self.is_op(j, "("):  # Kotlin function type, or a parenthesised type
            close = self.match[j]
            if self.is_op(close + 1, "->"):
                ret, k = self.typeref(close + 2)
                if ret is None:
                    return None, start
                return TypeRef(self.text(j, k), "Function"), k
            inner, k = self.typeref(j + 1)
            if inner is None or k != close:
                return None, start
            j = close + 1
            nullable = self.is_op(j, "?")
            return TypeRef(self.text(start, j + (1 if nullable else 0)), inner.base, inner.args, nullable), \
                j + (1 if nullable else 0)
        if self.is_op(j, "?") or self.is_op(j, "*"):
            return TypeRef(toks[j].t, toks[j].t), j + 1
        if not self.is_id(j):
            return None, start
        if toks[j].t in ("out", "in") and self.is_id(j + 1):
            j += 1
        base, j = self.qname(j)
        args = []
        if self.is_op(j, "<"):
            k = j + 1
            while k < self.n:
                if self.is_op(k, "?") and self.is_id(k + 1) and toks[k + 1].t in ("extends", "super"):
                    k += 2
                arg, k2 = self.typeref(k)
                if arg is None:
                    k = self.skip_angles(j)
                    break
                args.append(arg)
                k = k2
                if self.is_op(k, ","):
                    k += 1
                    continue
                if self.is_op(k, ">"):
                    k += 1
                    break
                k = self.skip_angles(j)
                break
            j = k
        while self.is_op(j, "[") and self.is_op(j + 1, "]"):
            j += 2
        if self.is_op(j, "..."):
            j += 1
        nullable = False
        if self.kotlin and self.is_op(j, "?"):
            nullable = True
            j += 1
        while self.is_op(j, "."):  # `Outer<T>.Inner`
            more, j2 = self.typeref(j + 1)
            if more is None:
                break
            base = base + "." + more.base
            j = j2
        return TypeRef(self.text(start, j), base, args, nullable), j

    # -- parameters --------------------------------------------------------------------------------

    def params(self, lp):
        rp = self.match[lp]
        out, a, k, angle, in_default = [], lp + 1, lp + 1, 0, False
        while k <= rp:
            t = self.toks[k]
            if k == rp or (t.k == "op" and t.t == "," and angle == 0):
                if a < k:
                    p = self.param(a, k)
                    if p is not None:
                        out.append(p)
                a, k, angle, in_default = k + 1, k + 1, 0, False
                continue
            if t.k == "op":
                if t.t in "([{":
                    k = self.match[k] + 1
                    continue
                if t.t == "=" and self.kotlin:
                    in_default = True
                elif not in_default and t.t == "<":
                    angle += 1
                elif not in_default and t.t == ">":
                    angle = max(0, angle - 1)
            k += 1
        return out

    def param(self, a, b):
        anns, mods, k = [], set(), a
        while k < b:
            if k in self.anns_at:
                anns.append(self.anns_at[k])
                k = self.anns_at[k].end
                continue
            t = self.toks[k]
            if t.k == "id" and t.t in PARAM_MODIFIERS and not (self.kotlin and self.is_op(k + 1, ":")):
                mods.add(t.t)
                k += 1
                continue
            break
        if k >= b:
            return None
        line = self.toks[k].line
        if self.kotlin:
            if not (self.is_id(k) and self.is_op(k + 1, ":")):
                return None
            name = self.toks[k].t
            tr, j = self.typeref(k + 2)
            default = any(self.is_op(x, "=") for x in range(j, b))
            return Param(name, tr, anns, mods, default, line)
        e = b - 1
        while e > k and self.toks[e].t in ("[", "]"):
            e -= 1
        if not self.is_id(e) or e == k:
            return None
        tr, _ = self.typeref(k)
        return Param(self.toks[e].t, tr, anns, mods, False, line)

    # -- members -----------------------------------------------------------------------------------

    def _scan_members(self, owner, lb, rb):
        toks, i, pending, open_expr = self.toks, lb + 1, [], None

        def close(at):
            nonlocal open_expr
            if open_expr is not None and open_expr.body is not None:
                open_expr.body = (open_expr.body[0], max(open_expr.body[0], at))
                open_expr.end = at + 1
                open_expr = None

        while i < rb:
            t = toks[i]
            if i in self.anns_at:
                close(i - 1)
                if self.anns_at[i].target != "file":
                    pending.append(self.anns_at[i])
                i = self.anns_at[i].end
                continue
            if t.k == "op":
                if t.t in "([":
                    i = self.match[i] + 1
                    continue
                if t.t == "{":
                    if not self.kotlin or open_expr is None:
                        pending = []
                    i = self.match[i] + 1
                    continue
                if t.t == ";":
                    pending = []
                i += 1
                continue
            if t.k != "id":
                i += 1
                continue
            if i in self.type_at:
                close(i - 1)
                pending = []
                td = self.type_at[i]
                i = max(td.end, i + 1)
                continue
            if self.kotlin:
                if t.t == "fun" and not self.is_id(i + 1, "interface"):
                    close(i - 1)
                    m, nxt = self._kotlin_fun(i, owner, pending)
                    pending = []
                    if m is not None and m.body and m.end is None:
                        open_expr = m
                    i = nxt
                    continue
                if t.t in ("val", "var") and owner is not None or (t.t in ("val", "var") and lb == -1):
                    close(i - 1)
                    i = self._kotlin_prop(i, owner, pending)
                    pending = []
                    continue
                if t.t == "init" and self.is_op(i + 1, "{"):
                    close(i - 1)
                i += 1
                continue
            if owner is None:
                i += 1
                continue
            if self.is_op(i + 1, "(") and self._is_java_method(i, owner):
                i = self._java_method(i, owner, pending)
                pending = []
                continue
            nxt = self.tok(i + 1)
            if nxt is not None and nxt.k == "op" and nxt.t in ("=", ";", ","):
                prev = self.tok(i - 1)
                if prev is not None and (prev.k == "id" and prev.t not in JAVA_NOT_METHOD or prev.t in (">", "]")):
                    self._java_field(i, owner, pending)
            i += 1
        close(rb - 1)

    def _is_java_method(self, i, owner):
        name = self.toks[i].t
        if name in JAVA_NOT_METHOD:
            return False
        rp = self.match[i + 1]
        nxt = self.tok(rp + 1)
        if nxt is None or not (nxt.t in ("{", ";") or (nxt.k == "id" and nxt.t in ("throws", "default"))):
            return False
        prev = self.tok(i - 1)
        if prev is None:
            return False
        if prev.k == "id":
            return prev.t not in ("new", "return", "throw", "else", "case", "yield")
        if prev.k == "op" and prev.t in (">", "]"):
            return True
        if (i - 1) in self.ann_last:
            return True
        return name == owner.name and prev.k == "op" and prev.t in ("{", "}", ";")

    def _java_method(self, i, owner, pending):
        m = Method(owner, self.toks[i].t, list(pending), self.params(i + 1), self.toks[i].line)
        j = self.match[i + 1] + 1
        while j < self.n and not (self.toks[j].k == "op" and self.toks[j].t in "{;"):
            j += 1
        end = self.match[j] + 1 if self.is_op(j, "{") else j + 1
        if self.is_op(j, "{"):
            m.body = (j, self.match[j])
        m.end = end
        owner.methods.append(m)
        self.methods.append(m)
        return end

    def _java_type_start(self, name_idx):
        k = name_idx - 1
        while self.is_op(k, "]") and self.is_op(k - 1, "["):
            k -= 2
        if self.is_op(k, ">"):
            depth = 0
            while k >= 0:
                t = self.toks[k]
                if t.k == "op" and t.t == ">":
                    depth += 1
                elif t.k == "op" and t.t == "<":
                    depth -= 1
                    if depth == 0:
                        k -= 1
                        break
                k -= 1
        while self.is_id(k) and self.is_op(k - 1, ".") and self.is_id(k - 2):
            k -= 2
        return k

    def _java_field(self, i, owner, pending):
        start = self._java_type_start(i)
        if not self.is_id(start):
            return
        _, mods = self.prefix(start)
        tr, _ = self.typeref(start)
        init = None
        if self.is_op(i + 1, "="):
            e = i + 2
            while e < self.n and not (self.is_op(e, ";") or self.is_op(e, ",")):
                e = self.match[e] + 1 if self.toks[e].k == "op" and self.toks[e].t in "([{" else e + 1
            init = (i + 2, e)
        static = "static" in mods or (owner.kind in ("interface", "annotation"))
        prop = Prop(owner, self.toks[i].t, tr, list(pending), static, self.toks[i].line, init)
        owner.props.append(prop)
        self.props.append(prop)

    def _kotlin_fun(self, i, owner, pending):
        j = i + 1
        if self.is_op(j, "<"):
            j = self.skip_angles(j)
        k = j
        while k < self.n and not self.is_op(k, "(") and k - j < 30:
            if self.is_op(k, "<"):
                k = self.skip_angles(k)
                continue
            k += 1
        if not self.is_op(k, "(") or not self.is_id(k - 1):
            return None, i + 1
        m = Method(owner, self.toks[k - 1].t, list(pending), self.params(k), self.toks[k - 1].line)
        j = self.match[k] + 1
        if self.is_op(j, ":"):
            _, j = self.typeref(j + 1)
        if self.is_id(j, "where"):
            while j < self.n and not (self.is_op(j, "{") or self.is_op(j, "=")):
                j += 1
        if self.is_op(j, "{"):
            nxt = self.match[j] + 1
            m.body = (j, self.match[j])
            m.end = nxt
        elif self.is_op(j, "="):
            m.body = (j, j)
            nxt = j + 1
        else:
            m.end = j
            nxt = j
        if owner is not None:
            owner.methods.append(m)
        self.methods.append(m)
        return m, nxt

    def _kotlin_prop(self, i, owner, pending):
        _, mods = self.prefix(i)
        j = i + 1
        if self.is_op(j, "<"):
            j = self.skip_angles(j)
        if not self.is_id(j):
            return i + 1
        name_idx = j
        if self.is_op(j + 1, ".") and self.is_id(j + 2):  # extension property `val Foo.bar`
            name_idx = j + 2
        k = name_idx + 1
        tr = None
        if self.is_op(k, ":"):
            tr, k = self.typeref(k + 1)
        init = None
        if self.is_op(k, "="):
            e = k + 1
            line = self.toks[k].line
            while e < self.n and (self.toks[e].line == line or self.toks[e - 1].t in ("+", ".", "(", ",")):
                if self.toks[e].k == "op" and self.toks[e].t in "([{":
                    e = self.match[e] + 1
                    continue
                if self.toks[e].k == "op" and self.toks[e].t in ")]}":
                    break
                e += 1
            init = (k + 1, e)
            k = e
        static = owner is None or owner.kind == "object" or "const" in mods
        prop = Prop(owner, self.toks[name_idx].t, tr, list(pending), static, self.toks[name_idx].line, init)
        if owner is not None:
            owner.props.append(prop)
        self.props.append(prop)
        return max(k, i + 1)

    # -- annotation arguments ----------------------------------------------------------------------

    def ann_args(self, ann):
        """{name: (start, end)} token ranges of an annotation's arguments; positional → 'value'."""
        out = {}
        if not ann.args:
            return out
        lp, rp = ann.args
        a, k = lp + 1, lp + 1
        while k <= rp:
            if k == rp or self.is_op(k, ","):
                if a < k:
                    if self.is_id(a) and self.is_op(a + 1, "="):
                        out[self.toks[a].t] = (a + 2, k)
                    else:
                        out.setdefault("value", (a, k))
                a = k = k + 1
                continue
            t = self.toks[k]
            if t.k == "op" and t.t in "([{":
                k = self.match[k] + 1
                continue
            k += 1
        return out

    def method_at(self, i):
        best = None
        for m in self.methods:
            if m.body and m.body[0] <= i <= m.body[1]:
                if best is None or m.body[0] > best.body[0]:
                    best = m
        return best


# --------------------------------------------------------------------------------------------------
# Project: files, slices, indexes
# --------------------------------------------------------------------------------------------------

def under(path, directory):
    """`path` lies inside `directory` — a string test; `directory in path.parents` builds a Path per ancestor."""
    return str(path).startswith(str(directory) + os.sep)


def rel(path, root):
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def walk(root):
    """Main source files and slice manifests under root."""
    sources, manifests, dirs = [], [], []
    for dirpath, dirnames, filenames in os.walk(root):
        here = Path(dirpath)
        keep = []
        for d in dirnames:
            if d.startswith(".") or d in SKIP_DIRS:
                continue
            if here.name == "src" and (d in NON_MAIN_SOURCE_SETS or d.endswith("Test")):
                continue
            keep.append(d)
        dirnames[:] = sorted(keep)
        dirs.append(here)
        for f in sorted(filenames):
            p = here / f
            if f == "slice.yaml":
                manifests.append(p)
            elif p.suffix in SOURCE_EXT:
                sources.append(p)
    return sources, manifests, dirs


class BC:
    """A bounded context. One BC may span `src/main/java/<pkg>` and `src/main/kotlin/<pkg>`; `key` writes the
    language directory as `*`."""

    def __init__(self, key, name, dirs):
        self.key, self.name, self.dirs = key, name, dirs

    def holds(self, path):
        return any(under(path, d) for d in self.dirs)


SOURCE_ROOT_LANG = re.compile(r"(^|/)src/([^/]+)/(java|kotlin)(/|$)")


def bc_key(relpath):
    return SOURCE_ROOT_LANG.sub(r"\1src/\2/*\4", relpath)


class Slice:
    def __init__(self, dir_, role, bc_dir):
        self.dir, self.role, self.bc_dir = dir_, role, bc_dir
        self.manifest = None
        self.doc = None
        self.id = None
        self.kind = ROLES.get(role)
        self.files = []


class Project:
    def __init__(self, root, root_arg, bcs_filter, yaml_mod):
        self.root, self.root_arg, self.yaml = root, root_arg, yaml_mod
        self.unparsed, self.unresolved = [], []
        sources, manifest_paths, dirs = walk(root)
        self.files = []
        for p in sources:
            try:
                text = p.read_text(encoding="utf-8", errors="replace")
            except OSError as exc:
                self.unparsed.append(self._u(p, None, "source file", f"unreadable: {exc}"))
                continue
            f = SrcFile(p, rel(p, root), SOURCE_EXT[p.suffix], text)
            if f.error is not None:
                self.unparsed.append(self._u(p, f.error.line, "source file", f.error.reason))
            self.files.append(f)
        self.by_path = {f.path: f for f in self.files}
        self._event_names = {}
        self._discover_slices(dirs, manifest_paths)
        self._load_manifests()
        if bcs_filter:
            self.slices = [s for s in self.slices if s.bc_dir.name in bcs_filter]
        groups = {}
        for sl in self.slices:
            key = bc_key(rel(sl.bc_dir, root))
            groups.setdefault(key, set()).add(sl.bc_dir)
        self.bcs = []
        for key in sorted(groups):
            dirs = set(groups[key])
            if "*" in key:  # the same package in the other language's source root, slices or not
                for lang in ("java", "kotlin"):
                    other = root / key.replace("*", lang, 1)
                    if other.is_dir():
                        dirs.add(other)
            self.bcs.append(BC(key, Path(key).name, sorted(dirs, key=lambda p: rel(p, root))))
        for sl in self.slices:
            sl.bc = next(b for b in self.bcs if sl.bc_dir in b.dirs)
        self._index()

    def _u(self, path, line, what, reason):
        return {"file": rel(path, self.root) if isinstance(path, Path) else path, "line": line, "what": what,
                "reason": reason}

    def _discover_slices(self, dirs, manifest_paths):
        slices = {}
        for d in sorted(dirs, key=lambda p: len(p.parts)):
            if d.name not in ROLES:
                continue
            if any(under(d, parent) for parent in slices):
                continue
            for child in sorted(p for p in d.iterdir() if p.is_dir()):
                if child.name.startswith("_") or child.name.startswith(".") or child.name in SKIP_DIRS:
                    continue
                slices[child] = Slice(child, d.name, d.parent)
        for m in manifest_paths:
            d = m.parent
            if d not in slices:
                role = d.parent.name if d.parent.name in ROLES else None
                slices[d] = Slice(d, role, d.parent.parent if role else d.parent)
            slices[d].manifest = m
        self.slices = [slices[k] for k in sorted(slices, key=lambda p: rel(p, self.root))]
        for s in self.slices:
            s.files = [f for f in self.files if under(f.path, s.dir)]

    def _load_manifests(self):
        self.manifests_read = self.yaml is not None
        self.skipped_manifests = []
        for s in self.slices:
            s.id = f"{s.bc_dir.name}.{s.dir.name}"
            s.id_source = "directory"
            if s.manifest is None or self.yaml is None:
                continue
            try:
                doc = self.yaml.safe_load(s.manifest.read_text(encoding="utf-8"))
            except (OSError, self.yaml.YAMLError) as exc:
                reason = str(exc).splitlines()[0] if str(exc) else type(exc).__name__
                self.skipped_manifests.append({"file": rel(s.manifest, self.root),
                                               "reason": f"does not parse ({reason}) — slice-lint reports it (gate 1(a))"})
                continue
            if not isinstance(doc, dict):
                self.skipped_manifests.append({"file": rel(s.manifest, self.root),
                                               "reason": "not a mapping — slice-lint reports it (gate 1(b))"})
                continue
            s.doc = doc
            if isinstance(doc.get("slice"), str):
                s.id, s.id_source = doc["slice"], "manifest"
            if doc.get("kind") in ("command", "view", "automation", "translation"):
                s.kind = doc["kind"]

    # -- project-wide indexes ----------------------------------------------------------------------

    def _index(self):
        self.types_by_name = {}
        self.typealiases = {}
        for f in self.files:
            for td in f.types:
                self.types_by_name.setdefault(td.name, []).append(td)
            if f.kotlin:
                for i, t in enumerate(f.toks):
                    if t.k == "id" and t.t == "typealias" and f.is_id(i + 1) and f.is_op(i + 2, "="):
                        tr, _ = f.typeref(i + 3)
                        if tr is not None:
                            self.typealiases.setdefault(f.toks[i + 1].t, []).append((f, tr))
        # constants and AggregateType constants, keyed by (owner simple name or None, NAME)
        self.consts, self.agg_consts = {}, {}
        for f in self.files:
            for p in f.props:
                if not p.init:
                    continue
                a, b = p.init
                owner = p.owner
                while owner is not None and owner.kind == "object" and owner.name == "Companion" and owner.outer:
                    owner = owner.outer
                key = (owner.name if owner else None, p.name)
                if (b - a == 6 and f.is_id(a, "AggregateType") and f.is_op(a + 1, ".") and f.is_id(a + 2, "of")
                        and f.is_op(a + 3, "(") and f.toks[a + 4].k == "str" and f.toks[a + 4].v is not None):
                    self.agg_consts.setdefault(key, set()).add(f.toks[a + 4].v)
                else:
                    self.consts.setdefault(key, []).append((f, a, b, p))
        # supertype → subtypes, by simple name
        self.subtypes = {}
        for f in self.files:
            for td in f.types:
                for _, tr in td.supers:
                    self.subtypes.setdefault(tr.base.rsplit(".", 1)[-1], []).append(td)

    # -- resolution --------------------------------------------------------------------------------

    def resolve(self, f, tr_or_text):
        """(written, simple name, fqn or None, resolvedBy) for a type reference in file f."""
        if tr_or_text is None:
            raise ValueError("resolve() needs a type reference; callers check for a missing type first")
        base = tr_or_text.base if isinstance(tr_or_text, TypeRef) else tr_or_text
        written = tr_or_text.text if isinstance(tr_or_text, TypeRef) else tr_or_text
        return self._resolve_base(f, base, written, set())

    def _resolve_base(self, f, base, written, seen):
        if "." in base:
            parts = base.split(".")
            if parts[0][:1].islower() or parts[0].startswith("{{"):
                return {"written": written, "name": parts[-1], "fqn": base, "resolvedBy": "fqn"}
            outer = self._resolve_base(f, parts[0], parts[0], seen)
            fqn = outer["fqn"] + "." + ".".join(parts[1:]) if outer["fqn"] else None
            return {"written": written, "name": parts[-1], "fqn": fqn, "resolvedBy": "nested"}
        if base in f.aliases:
            fqn = f.aliases[base]
            return {"written": written, "name": fqn.rsplit(".", 1)[-1], "fqn": fqn, "resolvedBy": "alias"}
        if base in f.imports:
            fqn = f.imports[base]
            alias = self._typealias(fqn.rsplit(".", 1)[-1], fqn.rsplit(".", 1)[0], seen)
            if alias:
                return dict(alias, written=written)
            return {"written": written, "name": base, "fqn": fqn, "resolvedBy": "import"}
        local = [td for td in f.types if td.name == base]
        if local:
            chain, td = [base], local[0].outer
            while td is not None:
                chain.insert(0, td.name)
                td = td.outer
            fqn = ".".join(([f.package] if f.package else []) + chain)
            return {"written": written, "name": base, "fqn": fqn, "resolvedBy": "same-file"}
        alias = self._typealias(base, f.package, seen)
        if alias:
            return dict(alias, written=written)
        for td in self.types_by_name.get(base, []):
            if td.outer is None and td.file.package == f.package:
                fqn = f"{f.package}.{base}" if f.package else base
                return {"written": written, "name": base, "fqn": fqn, "resolvedBy": "same-package"}
        for td in self.types_by_name.get(base, []):
            if td.outer is None and td.file.package in f.wildcards:
                return {"written": written, "name": base, "fqn": f"{td.file.package}.{base}", "resolvedBy": "wildcard"}
        return {"written": written, "name": base, "fqn": None, "resolvedBy": "unresolved"}

    def _typealias(self, name, package, seen):
        for af, tr in self.typealiases.get(name, []):
            if af.package == package and (af.path, name) not in seen:
                seen.add((af.path, name))
                target = self._resolve_base(af, tr.base, tr.text, seen)
                return dict(target, resolvedBy="typealias")
        return None

    def const_value(self, f, a, b, depth=0):
        """A string expression in tokens [a, b): literals, `+` concatenation, resolvable constants."""
        if depth > 8 or a >= b:
            return None
        parts, k = [], a
        while k < b:
            t = f.toks[k]
            if t.k == "str":
                if t.v is None:
                    return None
                parts.append(t.v)
                k += 1
            elif t.k == "id":
                name, j = f.qname(k)
                if self.is_ctor_call(f, j):
                    return None
                v = self.lookup_const(f, name, k, depth)
                if v is None:
                    return None
                parts.append(v)
                k = j
            elif t.k == "op" and t.t == "(" and f.match[k] < b:
                v = self.const_value(f, k + 1, f.match[k], depth + 1)
                if v is None:
                    return None
                parts.append(v)
                k = f.match[k] + 1
            else:
                return None
            if k < b:
                if not f.is_op(k, "+"):
                    return None
                k += 1
        return "".join(parts)

    @staticmethod
    def is_ctor_call(f, j):
        return f.is_op(j, "(")

    def lookup_const(self, f, name, at, depth):
        parts = name.split(".")
        candidates = []
        if len(parts) >= 2:
            candidates = self.consts.get((parts[-2], parts[-1]), [])
        else:
            owner = f.enclosing_type(at)
            while owner is not None:
                target = owner.outer if owner.name == "Companion" and owner.outer else owner
                candidates = self.consts.get((target.name, name), [])
                if candidates:
                    break
                owner = owner.outer
            imported = f.static_imports.get(name) or f.imports.get(name)  # Kotlin imports members plainly
            if not candidates and imported:
                member = imported.split(".")
                if len(member) >= 2 and member[-2][:1].isupper():
                    candidates = self.consts.get((member[-2], member[-1]), [])
                else:
                    package = ".".join(member[:-1])
                    candidates = [c for c in self.consts.get((None, name), []) if c[0].package == package]
            for td in f.types if not candidates else ():
                candidates = self.consts.get((td.name, name), [])
                if candidates:
                    break
            if not candidates:
                candidates = [c for c in self.consts.get((None, name), []) if c[0].package == f.package or c[0] is f]
        values = {self.const_value(cf, ca, cb, depth + 1) for cf, ca, cb, _ in candidates}
        values.discard(None)
        return values.pop() if len(values) == 1 else None

    def lookup_agg(self, f, name, at, depth=0):
        """The stream name behind an `AggregateType` constant, following `A = B.C` chains."""
        parts = name.split(".")
        keys = []
        if len(parts) >= 2:
            keys.append((parts[-2], parts[-1]))
        else:
            owner = f.enclosing_type(at)
            while owner is not None:
                target = owner.outer if owner.name == "Companion" and owner.outer else owner
                keys.append((target.name, name))
                owner = owner.outer
            imported = f.static_imports.get(name) or f.imports.get(name)
            if imported and len(imported.split(".")) >= 2:
                member = imported.split(".")
                keys.append((member[-2], member[-1]))
            keys.append((None, name))
        for key in keys:
            values = set(self.agg_consts.get(key, set()))
            if depth < 8:
                for cf, ca, cb, _ in self.consts.get(key, []):
                    qn, j = cf.qname(ca) if cf.is_id(ca) else ("", ca)
                    if qn and j == cb:  # `AGGREGATE_TYPE = OtherTypes.ORDERS`
                        chained = self.lookup_agg(cf, qn, ca, depth + 1)
                        if chained is not None:
                            values.add(chained)
            if values:
                return next(iter(values)) if len(values) == 1 else None
        return None

    def value(self, f, a, b):
        """Evaluate an annotation argument in tokens [a, b)."""
        if a >= b:
            return Value("bad", None, "")
        text = f.text(a, b)
        t = f.toks[a]
        if t.k == "op" and t.t in "{[" and f.match[a] == b - 1:
            return self._list(f, a + 1, b - 1, text)
        if f.is_id(a, "arrayOf") and f.is_op(a + 1, "(") and f.match[a + 1] == b - 1:
            return self._list(f, a + 2, b - 1, text)
        if t.k == "num" and b - a == 1:
            return Value("num", t.t, text)
        s = self.const_value(f, a, b)
        if s is not None:
            return Value("str", s, text)
        if t.k == "id":
            name, j = f.qname(a)
            if j == b:
                return Value("ref", name, text)
        return Value("bad", None, text)

    def _list(self, f, a, b, text):
        items, k, start = [], a, a
        while k <= b:
            if k == b or f.is_op(k, ","):
                if start < k:
                    items.append(self.value(f, start, k))
                start = k = k + 1
                continue
            if f.toks[k].k == "op" and f.toks[k].t in "([{":
                k = f.match[k] + 1
                continue
            k += 1
        return Value("list", items, text)

    def arg(self, f, ann, *names):
        args = f.ann_args(ann)
        for n in names:
            if n in args:
                return self.value(f, *args[n])
        return None

    # -- per-slice facts ---------------------------------------------------------------------------

    def slice_facts(self, s):
        root = self.root
        facts = {
            "id": s.id, "idSource": s.id_source, "bc": s.bc.name, "bcPath": s.bc.key,
            "dir": rel(s.dir, root), "role": s.role, "kind": s.kind,
            "manifest": rel(s.manifest, root) if s.manifest else None,
        }
        langs = sorted({f.lang for f in s.files})
        facts["language"] = langs[0] if len(langs) == 1 else ("mixed" if langs else None)
        pkgs = []
        for f in s.files:
            if not f.package:
                continue
            # a file in `outgoing/` declares `<slice package>.outgoing`: strip the sub-directories it sits in
            sub = f.path.parent.relative_to(s.dir).parts
            pkg = f.package
            if sub and pkg.split(".")[-len(sub):] == list(sub):
                pkg = ".".join(pkg.split(".")[:-len(sub)])
            pkgs.append(pkg)
        facts["package"] = pkgs[0] if pkgs else None
        if len(set(pkgs)) > 1:
            self.unresolved.append(self._u(s.dir, None, "slice package",
                                           "files in the slice declare different packages: " + ", ".join(sorted(set(pkgs)))))
        listed = []
        for p in sorted(s.dir.rglob("*")):
            if p.is_file() and p.name not in NOT_SLICE_FILES and not any(x.startswith(".") for x in p.relative_to(s.dir).parts):
                listed.append(p.relative_to(s.dir).as_posix())
        facts["files"] = listed
        facts.update({k: [] for k in ("processors", "handlers", "handles", "dispatches", "publishes", "subscriptions",
                                      "mappings", "schedules")})
        facts["notAnalysed"] = []
        bc_events = self.bc_event_names(s.bc)
        for f in s.files:
            if f.error is not None:
                continue
            self._file_facts(f, facts, bc_events)
        if s.kind == "view":
            facts["readModels"] = self.read_models(s)
        return facts

    def bc_event_names(self, bc):
        evs = [d / "events" for d in bc.dirs]
        key = id(bc)
        if key not in self._event_names:
            self._event_names[key] = {td.name for f in self.files if any(under(f.path, ev) for ev in evs)
                                      for td in f.types}
        return self._event_names[key]

    def _loc(self, f, line):
        return {"file": f.rel, "line": line}

    def _file_facts(self, f, facts, bc_events):
        for td in f.types:
            bases = td.super_bases()
            for b in bases:
                if b in PROCESSOR_BASES:
                    facts["processors"].append({"class": td.name, "base": b, **self._loc(f, td.line)})
            for role, tr in td.supers:
                b = tr.base.rsplit(".", 1)[-1]
                if b in DECIDER_BASES and tr.args:
                    r = self.resolve(f, tr.args[0])
                    facts["handles"].append({"name": r["name"], "fqn": r["fqn"], "resolvedBy": r["resolvedBy"],
                                             "via": b, **self._loc(f, td.line)})
        base_routes = {}
        for td in f.types:
            for a in td.anns:
                if a.simple == "RequestMapping":
                    v = self.arg(f, a, "value", "path")
                    if v is None:
                        base_routes[td] = [""]
                        continue
                    base_routes[td] = v.strings()
                    if base_routes[td] is None:
                        self.unparsed.append(self._u(f.path, a.line, f"@RequestMapping path on {td.name}",
                                                     f"not a readable string: {v.text}"))
        for m in f.methods:
            for a in m.anns:
                if a.simple in HANDLER_ANNS:
                    self._handler(f, m, a, facts)
                elif a.simple in MAPPING_ANNS:
                    self._mapping(f, m, a, facts, base_routes)
                elif a.simple == "Scheduled":
                    self._schedule(f, m, a, facts)
        self._calls(f, facts, bc_events)
        for i, t in enumerate(f.toks):
            if t.k == "id" and t.t in ROUTER_MARKERS and (t.t != "router" or f.is_op(i + 1, "{")):
                facts["notAnalysed"].append({**self._loc(f, t.line), "kind": "routes",
                                             "reason": "WebFlux RouterFunction routes are not analysed"})
                break
        for a in f.stray:
            kind = "routes" if a.simple in MAPPING_ANNS else "handlers"
            facts["notAnalysed"].append({**self._loc(f, a.line), "kind": kind,
                                         "reason": f"@{a.simple} in an anonymous class or object expression is not "
                                                   f"attributed to a class of the slice"})

    def _handler(self, f, m, a, facts):
        params = [{"name": p.name, "type": p.type.text if p.type else None} for p in m.params]
        message, envelope = None, False
        if m.params and m.params[0].type is not None:
            message = self.resolve(f, m.params[0].type)
            envelope = message["name"] in ENVELOPES
        else:
            self.unparsed.append(self._u(f.path, m.line, f"@{a.simple} {m.owner.name if m.owner else ''}.{m.name}",
                                         "handler with no readable first parameter"))
        if a.simple == "CmdHandler" or (a.simple == "Handler" and m.owner is not None
                                          and set(m.owner.super_bases()) & COMMAND_HANDLER_BASES):
            role = "command"
        elif a.simple == "EventHandler":
            role = "aggregate-event"
        elif a.simple in EVENT_HANDLER_ANNS:
            role = "event"
        else:
            role = "inbound"
        facts["handlers"].append({
            "class": m.owner.name if m.owner else None, "method": m.name, "annotation": a.simple,
            "annotationFqn": self._ann_fqn(f, a), "role": role, **self._loc(f, m.line), "annotationLine": a.line,
            "message": message,
            "params": params, "envelope": envelope})
        if role == "command" and message and not envelope:
            facts["handles"].append({"name": message["name"], "fqn": message["fqn"],
                                     "resolvedBy": message["resolvedBy"], "via": a.simple, **self._loc(f, m.line)})

    def _ann_fqn(self, f, a):
        if "." in a.name:
            return a.name
        if a.name in f.imports:
            return f.imports[a.name]
        if a.name in f.aliases:
            return f.aliases[a.name]
        return None

    def _mapping(self, f, m, a, facts, base_routes):
        verb = MAPPING_ANNS[a.simple]
        if verb:
            methods = [verb]
        else:
            mv = self.arg(f, a, "method")
            methods = sorted({r.rsplit(".", 1)[-1] for r in mv.refs()}) if mv else []
            methods = methods or ["*"]
        pv = self.arg(f, a, "value", "path")
        paths = [""] if pv is None else pv.strings()
        routes = None
        if paths is None:
            unread = pv.text if pv is not None else ""
            self.unparsed.append(self._u(f.path, a.line, f"@{a.simple} path on {m.name}", f"not a readable string: {unread}"))
        else:
            bases = base_routes.get(m.owner, [""]) if m.owner else [""]
            if bases is not None:
                routes = sorted({normalise_route(b + "/" + p) for b in bases for p in paths})
        prm = self.arg(f, a, "params")
        params = [] if prm is None else prm.strings()
        if params is None:
            unread = prm.text if prm is not None else ""
            self.unparsed.append(self._u(f.path, a.line, f"@{a.simple} params on {m.name}", f"not a readable string: {unread}"))
            params = []
        request_params, body = [], None
        for p in m.params:
            for pa in p.anns:
                if pa.simple == "RequestParam":
                    nv = self.arg(f, pa, "value", "name")
                    names = nv.strings() if nv is not None else [p.name]
                    name = names[0] if names else p.name
                    req = self.arg(f, pa, "required")
                    optional = (req is not None and req.text == "false") or self.arg(f, pa, "defaultValue") is not None
                    if p.default or (p.type is not None and (p.type.nullable
                                                             or p.type.base.rsplit(".", 1)[-1] == "Optional")):
                        optional = True
                    is_map = p.type is not None and p.type.base.rsplit(".", 1)[-1] in ("Map", "MultiValueMap")
                    request_params.append({"name": name, "required": not optional and not is_map,
                                           **({"map": True} if is_map else {})})
                elif pa.simple == "RequestBody" and p.type is not None:
                    r = self.resolve(f, p.type)
                    body = {"name": r["name"], "fqn": r["fqn"], "resolvedBy": r["resolvedBy"]}
        query = []
        if m.body:
            for i in range(m.body[0], m.body[1]):
                if f.is_id(i, "queryParam") and f.is_op(i + 1, "(") and f.tok(i + 2).k == "str" and f.tok(i + 2).v:
                    query.append(f.tok(i + 2).v)
        facts["mappings"].append({
            "class": m.owner.name if m.owner else None, "method": m.name, **self._loc(f, m.line),
            "annotationLine": a.line, "annotation": a.simple, "httpMethods": methods, "routes": routes, "params": params,
            "requestParams": request_params, "queryParams": sorted(set(query)), "requestBody": body})

    def _schedule(self, f, m, a, facts):
        raw, out = {}, {"class": m.owner.name if m.owner else None, "method": m.name, **self._loc(f, m.line)}
        for key in ("cron", "fixedDelay", "fixedDelayString", "fixedRate", "fixedRateString", "initialDelay",
                    "initialDelayString", "timeUnit", "zone"):
            v = self.arg(f, a, key)
            if v is not None:
                raw[key] = v.v if v.kind in ("str", "num") else v.text
        unit_ms = "timeUnit" not in raw
        cron = raw.get("cron")
        out["cron"] = cron if isinstance(cron, str) else None
        for key in ("fixedDelay", "fixedRate", "initialDelay"):
            value = raw.get(key, raw.get(key + "String"))
            out[key] = iso_duration(value) if unit_ms and value is not None else None
        out["raw"] = {k: str(v) for k, v in raw.items()}
        facts["schedules"].append(out)

    def _calls(self, f, facts, bc_events):
        toks = f.toks
        for i, t in enumerate(toks):
            if t.k != "id":
                continue
            lp = f.skip_angles(i + 1) if f.is_op(i + 1, "<") else i + 1  # `send<Any?, PlaceOrder>(…)`
            if not f.is_op(lp, "("):
                continue
            recv = toks[i - 2] if i >= 2 and toks[i - 1].t in (".", "?.") else None
            name = t.t
            if name in ("send", "sendAsync", "sendAndDontWait") and recv is not None and (
                    (recv.k == "id" and COMMAND_BUS.search(recv.t)) or name != "send"):
                self._call_arg(f, lp, facts["dispatches"], name, "dispatch argument",
                               lambda td: "use_cases" in td.file.path.parts)
            elif name == "publish" and recv is not None and recv.k == "id" and EVENT_BUS.search(recv.t):
                self._call_arg(f, lp, facts["publishes"], "eventBus.publish", "published event",
                               lambda td: td.name in bc_events)
            elif name.startswith("subscribeToAggregateEvents"):
                self._subscriptions(f, lp, f.match[lp], facts)
        for m in f.methods:
            if m.name == "reactsToEventsRelatedToAggregateTypes" and m.body:
                self._subscriptions(f, m.body[0], m.body[1] + 1, facts)
        for td in f.types:
            if not (set(td.super_bases()) & DECIDER_BASES) or not td.body:
                continue
            seen = set()
            for i in range(td.body[0], td.body[1]):
                callee = self._ctor_at(f, i)
                if callee is None:
                    continue
                r = self.resolve(f, callee)
                if r["name"] in bc_events and r["name"] not in seen:
                    seen.add(r["name"])
                    facts["publishes"].append({"name": r["name"], "fqn": r["fqn"], "resolvedBy": r["resolvedBy"],
                                               "via": "decider", **self._loc(f, toks[i].line)})

    def _ctor_at(self, f, i):
        """The type name constructed at token i (`new X(` / Kotlin `X(`), else None."""
        t = f.toks[i]
        if f.kotlin:
            if t.k != "id" or not t.t[:1].isupper() or (i and f.toks[i - 1].t in (".", "::", "?.")):
                return None
            name, j = f.qname(i)
            return name if f.is_op(j, "(") and name.rsplit(".", 1)[-1][:1].isupper() else None
        if t.k == "id" and t.t == "new" and f.is_id(i + 1):
            name, j = f.qname(i + 1)
            if f.is_op(j, "<"):
                j = f.skip_angles(j)
            return name if f.is_op(j, "(") else None
        return None

    def _call_arg(self, f, lp, sink, via, what, accept_factory):
        rp = f.match[lp]
        a, k = lp + 1, lp + 1
        while k < rp and not f.is_op(k, ","):
            k = f.match[k] + 1 if f.toks[k].k == "op" and f.toks[k].t in "([{" else k + 1
        line = f.toks[lp].line
        if a >= k:
            return
        typ = self._expr_type(f, a, k, accept_factory)
        if typ is not None and (typ.base if isinstance(typ, TypeRef) else typ).rsplit(".", 1)[-1] in ENVELOPES:
            typ = None  # `send(Any())` / `send(new Object())` is a placeholder, not a message type
        if typ is None:
            self.unresolved.append(self._u(f.path, line, what, f"type not statically known: {f.text(a, k)}"))
            return
        r = self.resolve(f, typ)
        entry = {"name": r["name"], "fqn": r["fqn"], "resolvedBy": r["resolvedBy"], "via": via, "file": f.rel,
                 "line": line}
        if via != "eventBus.publish":
            # an API sending the command its own slice declares is not an outbound dispatch
            slice_dirs = [p for p in f.path.parents if p.parent.name in ROLES]
            entry["own"] = any(under(td.file.path, d) for d in slice_dirs for td in self.types_by_name.get(r["name"], []))
        sink.append(entry)

    def _expr_type(self, f, a, b, accept_factory):
        ctor = self._ctor_at(f, a)
        if ctor is not None:
            name, j = f.qname(a + (0 if f.kotlin else 1))
            if f.is_op(j, "<"):
                j = f.skip_angles(j)
            if f.is_op(j, "(") and f.match[j] == b - 1:
                return ctor
        if f.is_id(a):
            name, j = f.qname(a)
            parts = name.split(".")
            if j == b and len(parts) == 1:
                return self._local_type(f, a, name)
            # `X.from(…)` / `X.of(…)`: a static factory on a type declared in scope
            if (len(parts) >= 2 and parts[-2][:1].isupper() and parts[-1][:1].islower() and f.is_op(j, "(")
                    and f.match[j] == b - 1):
                owner = ".".join(parts[:-1])
                decls = self.types_by_name.get(parts[-2], [])
                if decls and any(accept_factory(td) for td in decls):
                    return owner
        return None

    def _local_type(self, f, at, name):
        m = f.method_at(at)
        if m is None:
            return None
        for p in m.params:
            if p.name == name and p.type is not None:
                return p.type
        lo, hi = m.body
        for i in range(lo, min(hi, at)):
            if f.toks[i].t != name or f.toks[i].k != "id":
                continue
            if f.kotlin and f.is_id(i - 1) and f.toks[i - 1].t in ("val", "var"):
                if f.is_op(i + 1, ":"):
                    tr, _ = f.typeref(i + 2)
                    return tr
                if f.is_op(i + 1, "="):
                    return self._ctor_at(f, i + 2)
            if not f.kotlin and f.is_op(i + 1, "="):
                start = f._java_type_start(i)
                if f.is_id(start) and start < i:
                    if f.toks[start].t == "var":
                        return self._ctor_at(f, i + 2)
                    tr, _ = f.typeref(start)
                    return tr
        return None

    def _subscriptions(self, f, a, b, facts):
        i = a
        while i < b:
            t = f.toks[i]
            if (t.k == "id" and t.t == "AggregateType" and f.is_op(i + 1, ".") and f.is_id(i + 2, "of")
                    and f.is_op(i + 3, "(")):
                lit = f.tok(i + 4)
                name = lit.v if lit is not None and lit.k == "str" and f.is_op(i + 5, ")") else None
                if name is None:
                    self.unresolved.append(self._u(f.path, t.line, "AggregateType.of argument",
                                                   f"not a string literal: {f.text(i + 4, f.match[i + 3])}"))
                facts["subscriptions"].append({"aggregateType": name, "written": f.text(i, f.match[i + 3] + 1),
                                               **self._loc(f, t.line)})
                i = f.match[i + 3] + 1
                continue
            if t.k == "id" and not (i and f.toks[i - 1].t in (".", "?.")):
                qn, j = f.qname(i)
                last = qn.rsplit(".", 1)[-1]
                if not f.is_op(j, "(") and last.isupper() and len(last) > 1:
                    value = self.lookup_agg(f, qn, i)
                    known = value is not None or any(k[1] == last for k in self.agg_consts)
                    if value is not None or known:
                        if value is None:
                            self.unresolved.append(self._u(f.path, t.line, "AggregateType constant",
                                                           f"{qn} has no single AggregateType.of(\"…\") in scope"))
                        facts["subscriptions"].append({"aggregateType": value, "written": qn, **self._loc(f, t.line)})
                i = j
                continue
            i += 1

    # -- read models, messages ---------------------------------------------------------------------

    def read_models(self, s):
        names = set()
        if s.doc:
            names |= set(_names(s.doc.get("owns"))) | set(_names(s.doc.get("reads")))
            for p in _projections(s.doc):
                if isinstance(p.get("name"), str):
                    names.add(p["name"])
        repo_args = set()
        behaviour = set()
        for f in s.files:
            for p in f.props:
                if p.type is not None and p.type.base.rsplit(".", 1)[-1] == "DocumentDbRepository" and p.type.args:
                    repo_args.add(p.type.args[0].base.rsplit(".", 1)[-1])
            for m in f.methods:
                for p in m.params:
                    if p.type is not None and p.type.base.rsplit(".", 1)[-1] == "DocumentDbRepository" and p.type.args:
                        repo_args.add(p.type.args[0].base.rsplit(".", 1)[-1])
            for td in f.types:
                if (set(td.super_bases()) & PROCESSOR_BASES or any(a.simple in ("RestController", "Controller")
                                                                   for a in td.anns)
                        or any(a.simple in HANDLER_ANNS or a.simple in MAPPING_ANNS for m in td.methods for a in m.anns)):
                    behaviour.add(td)
        out = []
        for f in s.files:
            for td in f.types:
                if td in behaviour or td.kind in ("annotation", "enum") or td.name == "Companion":
                    continue
                matched = []
                store = None
                for a in td.anns:
                    if a.simple in READ_MODEL_STORES:
                        matched.append("annotation")
                        store = READ_MODEL_STORES[a.simple]
                        break
                if td.name.endswith("View"):
                    matched.append("name")
                if td.name in repo_args:
                    matched.append("repository")
                if td.name in names:
                    matched.append("manifest")
                if not matched:
                    continue
                columns = self.columns(td)
                if store is None and td.kind == "interface" and columns:
                    store = "projection"
                out.append({"name": td.name, "store": store, "columns": columns, "declaredIn": f.rel,
                            "line": td.line, "matchedBy": matched})
        return out

    def columns(self, td):
        cols = []

        def note(anns):
            notes = [n for n in ("Id", "Indexed") if any(a.simple == n for a in anns)]
            return ", ".join(n.lower() for n in notes) or None

        if td.kind == "record" or td.file.kotlin:
            for p in td.params:
                if td.kind == "record" or p.mods & {"val", "var"}:
                    cols.append({"name": p.name, "type": p.type.text if p.type else None, "note": note(p.anns)})
        if td.kind == "interface":
            for m in td.methods:
                if m.params:
                    continue
                g = re.match(r"(get|is)([A-Z]\w*)$", m.name)
                if g:
                    cols.append({"name": g.group(2)[0].lower() + g.group(2)[1:], "type": None, "note": None})
        if td.kind != "record":
            for p in td.props:
                if not p.static:
                    cols.append({"name": p.name, "type": p.type.text if p.type else None, "note": note(p.anns)})
        return cols

    def message_fields(self, td):
        if td.kind == "record" or td.file.kotlin:
            return [(p.name, p.type.text if p.type else "?") for p in td.params]
        return [(p.name, p.type.text if p.type else "?") for p in td.props if not p.static]


# --------------------------------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------------------------------

def normalise_route(path):
    """`api//orders/{orderId}/` → `/api/orders/{orderId}`."""
    path = re.sub(r"/+", "/", "/" + path.strip())
    return path.rstrip("/") or "/"


def route_key(path):
    """The comparison form of a route: path variables compare as `{}`, whatever they are named."""
    return re.sub(r"\{[^}]*\}", "{}", normalise_route(path))


def iso_duration(value):
    if isinstance(value, str) and value.startswith("P"):
        return value
    try:
        ms = int(str(value).replace("_", "").rstrip("Ll"))
    except ValueError:
        return None
    secs, rem = divmod(ms, 1000)
    h, r = divmod(secs, 3600)
    m, s = divmod(r, 60)
    out = "PT" + (f"{h}H" if h else "") + (f"{m}M" if m else "")
    if s or rem or out == "PT":
        out += f"{s}.{rem:03d}S".replace(".000S", "S") if rem else f"{s}S"
    return out


def _names(value):
    out = []
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        for item in value:
            if isinstance(item, str):
                out.append(item)
            elif isinstance(item, dict) and isinstance(item.get("name"), str):
                out.append(item["name"])
    return out


def _projections(doc):
    value = doc.get("projections")
    return [p for p in value if isinstance(p, dict)] if isinstance(value, list) else []


def gate_id(gate):
    m = re.match(r"(\d+)(?:\(([a-z])\))?", gate)
    return f"ESS-G{m.group(1)}{m.group(2) or ''}" if m else None


def finding(severity, gate, slice_id, file, line, message, hint=None):
    return {"severity": severity, "gate": gate, "id": gate_id(gate), "slice": slice_id, "file": file, "line": line,
            "message": message, "hint": hint}


def parse_endpoint(path):
    route, _, query = str(path).partition("?")
    discs = []
    for part in query.split("&") if query else []:
        if not part:
            continue
        name, eq, val = part.partition("=")
        discs.append((name, val if eq and val else None))
    return route_key(route), discs


# --------------------------------------------------------------------------------------------------
# facts
# --------------------------------------------------------------------------------------------------

def lane_facts(project, bc):
    signals = {"decider": [], "aggregates": None, "entities": None, "eventStore": []}
    files = [f for f in project.files if bc.holds(f.path)]
    for f in files:
        for td in f.types:
            for b in td.super_bases():
                if b in DECIDER_BASES:
                    signals["decider"].append({"file": f.rel, "line": td.line, "symbol": td.name, "via": b})
        first = {}  # symbol -> line: the first use site, else the import that names it
        for i, t in enumerate(f.toks):
            if t.k == "id" and t.t in EVENT_STORE_SYMBOLS:
                in_header = i < getattr(f, "header_end", 0)
                if t.t not in first or (first[t.t][1] and not in_header):
                    first[t.t] = (t.line, in_header)
        for sym, (line, _) in sorted(first.items(), key=lambda kv: kv[1][0]):
            signals["eventStore"].append({"file": f.rel, "line": line, "symbol": sym})
    for key in ("aggregates", "entities"):
        ds = [d / key for d in bc.dirs]
        count = sum(1 for f in files if any(under(f.path, d) for d in ds))
        if count:
            signals[key] = {"dir": bc_key(rel(ds[0], project.root)), "files": count}
    declared = []
    for s in project.slices:
        if s.bc is bc and s.doc and isinstance(s.doc.get("lane"), str):
            declared.append({"slice": s.id, "lane": s.doc["lane"], "file": rel(s.manifest, project.root)})
    present = [k for k, v in (("decider", signals["decider"]), ("aggregate", signals["aggregates"]),
                              ("service-entity", signals["entities"])) if v]
    if len(present) > 1:
        detected = "conflict"
    elif not present:
        detected = "undetermined"
    elif present[0] == "service-entity":
        detected = "conflict" if signals["eventStore"] else "service-entity?"
    else:
        detected = present[0]
    return {"signals": signals, "declared": declared, "detected": detected, "_present": present}


def build_messages(project, slice_facts):
    wanted = {}  # (name, type) -> bc name hint
    named = set()  # names a manifest declares — reported even when abstract or not found

    def want(name, typ, bc, manifest=False):
        if isinstance(name, str) and name:
            wanted.setdefault((name, typ), bc)
            if manifest:
                named.add(name)

    for s, facts in slice_facts:
        bc = s.bc.name
        for h in facts["handlers"]:
            if (h["message"] and not h["envelope"] and h["role"] in ("event", "command")
                    and not (h["message"]["fqn"] or "").startswith(("org.springframework.", "java."))):
                want(h["message"]["name"], h["role"], bc)
        for key, typ in (("handles", "command"), ("dispatches", "command"), ("publishes", "event")):
            for item in facts[key]:
                want(item["name"], typ, bc)
        if s.doc:
            for key, typ in (("handles", "command"), ("dispatches", "command"), ("publishes", "event"),
                             ("consumes", "event")):
                for n in _names(s.doc.get(key)):
                    want(n, typ, bc, manifest=True)
            for p in _projections(s.doc):
                for n in p.get("from") or []:
                    want(n, "event", bc, manifest=True)
    for bc in project.bcs:
        evs = [d / "events" for d in bc.dirs]
        for f in project.files:
            if any(under(f.path, ev) for ev in evs):
                for td in f.types:
                    if td.concrete and td.outer is None:
                        want(td.name, "event", bc.name)
    aggregates = {}
    for s in project.slices:
        if s.doc:
            aggregates.setdefault(s.bc.name, set()).update(_names(s.doc.get("writes")))
    out = []
    for (name, typ), bc in sorted(wanted.items()):
        decls = [td for td in project.types_by_name.get(name, []) if td.kind not in ("annotation", "enum")]
        decls.sort(key=lambda td: (0 if f"{os.sep}{bc}{os.sep}" in str(td.file.path) else 1,
                                   td.file.rel, td.line))
        if not decls:
            out.append({"name": name, "type": typ, "bc": bc, "found": False, "identity": None, "fields": [],
                        "declaredIn": None, "line": None, "alsoDeclaredIn": []})
            continue
        td = decls[0]
        if not td.concrete and name not in named:
            continue  # a sealed parent handled as a whole: its subtypes are the messages
        fields = project.message_fields(td)
        identity = None
        for agg in sorted(aggregates.get(bc, set())):
            identity = next((f"{n}: {t}" for n, t in fields if t.rstrip("?") == f"{agg}Id"), None)
            if identity:
                break
        if identity is None:
            identity = next((f"{n}: {t}" for n, t in fields if n.endswith("Id")), None)
        if identity is None:
            identity = next((f"{n}: {t}" for n, t in fields if t.rstrip("?").endswith("Id")), None)
        out.append({"name": name, "type": typ, "bc": bc, "found": True, "identity": identity,
                    "fields": [f"{n}: {t}" for n, t in fields], "declaredIn": td.file.rel, "line": td.line,
                    "alsoDeclaredIn": [f"{d.file.rel}:{d.line}" for d in decls[1:]]})
    return out


def facts_json(project):
    slice_facts = [(s, project.slice_facts(s)) for s in project.slices]
    bcs = []
    for bc in project.bcs:
        lane = lane_facts(project, bc)
        lane.pop("_present")
        langs = sorted({f.lang for f in project.files if bc.holds(f.path)})
        bcs.append({"name": bc.name, "path": bc.key, "dirs": [rel(d, project.root) for d in bc.dirs],
                    "languages": langs, "slices": [s.id for s in project.slices if s.bc is bc], "lane": lane})
    return {
        "tool": "slice-source", "format": FORMAT, "mode": "facts", "root": project.root_arg,
        "manifestsRead": project.manifests_read, "sourceFiles": len(project.files),
        "bcs": bcs,
        "slices": [facts for _, facts in slice_facts],
        "messages": build_messages(project, slice_facts),
        "unparsed": sorted(project.unparsed, key=_loc_key),
        "unresolved": sorted(_dedupe(project.unresolved), key=_loc_key),
    }


def _loc_key(d):
    return (d.get("file") or "", d.get("line") or 0, d.get("what") or "")


def _dedupe(items):
    seen, out = set(), []
    for d in items:
        key = json.dumps(d, sort_keys=True)
        if key not in seen:
            seen.add(key)
            out.append(d)
    return out


# --------------------------------------------------------------------------------------------------
# check
# --------------------------------------------------------------------------------------------------

def check_json(project):
    findings, unverified, endpoint_table = [], [], []
    root = project.root
    for s in project.slices:
        if s.doc is None:
            continue
        facts = project.slice_facts(s)
        broken = [f.rel for f in s.files if f.error is not None]
        if broken:
            gates = [("11(b) handled events", "ESS-G11b")] if s.kind in ("view", "automation", "translation") else []
            gates.append(("6 endpoint route", "ESS-G6"))  # an undeclared mapping may sit in the unread file
            for gate, gid in gates:
                unverified.append({"slice": s.id, "gate": gate, "id": gid, "file": rel(s.manifest, root),
                                   "reason": "source not readable, so the check ran on the rest of the slice only: "
                                             + ", ".join(broken)})
        _check_handled_events(project, s, facts, findings, unverified, bool(broken))
        _check_endpoints(project, s, facts, findings, unverified, endpoint_table)
    lanes = []
    for bc in project.bcs:
        lane = lane_facts(project, bc)
        _check_lane(project, bc, lane, findings)
        declared = {}
        for d in lane["declared"]:
            declared.setdefault(d["lane"], []).append(d["slice"])
        sig = lane["signals"]
        lanes.append({"bc": bc.name, "path": bc.key, "detected": lane["detected"],
                      "signals": {"decider": len(sig["decider"]), "aggregates": bool(sig["aggregates"]),
                                  "entities": bool(sig["entities"]), "eventStore": len(sig["eventStore"])},
                      "declared": declared})
    findings.sort(key=lambda d: (SEVERITY_ORDER.get(d["severity"], 9), d["file"] or "", d["line"] or 0, d["gate"],
                                 d["message"]))
    manifests = [s for s in project.slices if s.manifest]
    return {
        "tool": "slice-source", "format": FORMAT, "mode": "check", "root": project.root_arg,
        "manifests": len(manifests), "parsed": sum(1 for s in manifests if s.doc is not None),
        "skippedManifests": project.skipped_manifests,
        "slicesWithoutManifest": [s.id for s in project.slices if s.manifest is None],
        "lanes": lanes,
        "endpoints": endpoint_table,
        "findings": findings,
        "unverified": sorted(unverified, key=lambda d: (d["file"] or "", d["gate"])),
        "unparsed": sorted(project.unparsed, key=_loc_key),
    }


def _check_handled_events(project, s, facts, findings, unverified, broken=False):
    if s.kind not in ("view", "automation", "translation"):
        return
    declared = set(_names(s.doc.get("consumes")))
    for p in _projections(s.doc):
        declared.update(n for n in (p.get("from") or []) if isinstance(n, str))
    field = "projections[].from" if s.kind == "view" else "consumes"
    handlers = [h for h in facts["handlers"] if h["role"] == "event" and h["message"] and not h["envelope"]]
    handlers = [h for h in handlers if not (h["annotation"] == "EventListener" and (h["message"]["fqn"] or "").startswith(
        ("org.springframework.", "java.")))]
    anonymous = [na for na in facts["notAnalysed"] if na["kind"] == "handlers"]
    for na in anonymous:
        unverified.append({"slice": s.id, "gate": "11(b) handled events", "id": "ESS-G11b",
                           "file": na["file"], "reason": na["reason"] + f" (line {na['line']}); its event type is not "
                                                                        f"compared with the manifest"})
    # an inbound translation's `consumes` names the external message its ingress receives, which 11(b) does not read;
    # only the BC's own events need a handler to check against
    internal = declared & project.bc_event_names(s.bc)
    if internal and not handlers and not anonymous and not broken:
        unverified.append({"slice": s.id, "gate": "11(b) handled events", "id": "ESS-G11b",
                           "file": rel(s.manifest, project.root),
                           "reason": f"the manifest declares {', '.join(sorted(internal))} but no @MessageHandler / "
                                     f"@Handler / @EventListener method was found in the slice's source, so the "
                                     f"declaration cannot be checked against code"})
    for h in handlers:
        msg = h["message"]
        name = msg["name"]
        if name in declared:
            continue
        how = ""
        if msg["resolvedBy"] == "alias":
            how = f" (written `{msg['written']}`, an import alias of {msg['fqn']})"
        elif msg["resolvedBy"] == "fqn":
            how = f" (written fully qualified: {msg['fqn']})"
        leaves = _concrete_subtypes(project, name)
        if leaves:
            missing = sorted(leaves - declared)
            if not missing:
                continue
            findings.append(finding(
                "Should-fix", "11(b) handled events", s.id, h["file"], h["line"],
                f"{h['class']}.{h['method']}({msg['written']}) handles the supertype {name}{how}; its subtypes "
                f"{', '.join(missing)} are not declared in {field}",
                "the code is right and the manifest is stale — `/essentials:slice-check --fix-manifests` "
                "(manifest-reconciliation.md §2)"))
            continue
        findings.append(finding(
            "Should-fix", "11(b) handled events", s.id, h["file"], h["line"],
            f"{h['class']}.{h['method']}({msg['written']}) handles {name}{how}, which is not declared in {field}",
            "the code is right and the manifest is stale — `/essentials:slice-check --fix-manifests` "
            "(manifest-reconciliation.md §2)"))


def _concrete_subtypes(project, name, seen=None):
    seen = seen if seen is not None else set()
    if name in seen:
        return set()
    seen.add(name)
    out = set()
    for td in project.subtypes.get(name, []):
        if td.concrete:
            out.add(td.name)
        out |= _concrete_subtypes(project, td.name, seen)
    return out


def _binds(disc, mapping):
    """How mapping binds discriminator (name, pinned value), or None."""
    name, pinned = disc
    for p in mapping["params"]:
        pname, eq, pval = p.partition("=")
        if pname.startswith("!") or pname.endswith("!"):
            continue
        if pname.strip() != name:
            continue
        if pinned is None or (eq and pval.strip() == pinned):
            return f'params = "{p}"'
    if pinned is None:
        for rp in mapping["requestParams"]:
            if rp["name"] == name and rp["required"]:
                return f'@RequestParam("{name}")'
        if name in mapping["queryParams"]:
            return f'queryParam("{name}")'
    return None


def _positive_params(mapping):
    out = []
    for p in mapping["params"]:
        pname = p.partition("=")[0].strip()
        if pname and not pname.startswith("!") and not pname.endswith("!"):
            out.append(pname)
    return out


def _method_ok(endpoint_method, mapping):
    return "*" in mapping["httpMethods"] or str(endpoint_method).upper() in mapping["httpMethods"]


def _matches(route, discs, method, mapping):
    if mapping["routes"] is None or route not in {route_key(r) for r in mapping["routes"]} or not _method_ok(method, mapping):
        return False
    if any(_binds(d, mapping) is None for d in discs):
        return False
    names = {d[0] for d in discs}
    return all(p in names for p in _positive_params(mapping))


def _check_endpoints(project, s, facts, findings, unverified, table):
    endpoints = [e for e in (s.doc.get("endpoints") or []) if isinstance(e, dict) and isinstance(e.get("path"), str)]
    mappings = facts["mappings"]
    manifest = rel(s.manifest, project.root)
    unreadable = [m for m in mappings if m["routes"] is None]
    not_analysed = [na for na in facts["notAnalysed"] if na["kind"] == "routes"]
    if s.kind == "command":
        per_file = {}
        for m in mappings:
            per_file.setdefault(m["file"], []).append(m)
        for file, ms in sorted(per_file.items()):
            if len(ms) > 1:
                findings.append(finding(
                    "Blocking", "6 command mappings", s.id, file, ms[1]["line"],
                    f"a command slice's API file carries {len(ms)} method-level request mappings "
                    f"({', '.join(m['method'] for m in ms)}); a command slice exposes exactly one (§R2)",
                    "one command, one endpoint — a second mapping is a second command slice"))
    def row(e, status, bound=None):
        table.append({"slice": s.id, "method": str(e.get("method", "")).upper(), "path": e["path"],
                      "status": status, "boundBy": bound})

    if unreadable or not_analysed:
        for e in endpoints:
            row(e, "unverified")
        if endpoints:
            reason = ("a request mapping's path is not a readable literal" if unreadable
                      else not_analysed[0]["reason"])
            unverified.append({"slice": s.id, "gate": "6 endpoint route", "id": "ESS-G6", "file": manifest,
                               "reason": f"endpoints not checked: {reason}"})
        return
    parsed = [(e, *parse_endpoint(e["path"])) for e in endpoints]
    try:
        manifest_lines = s.manifest.read_text(encoding="utf-8").splitlines()
    except OSError:
        manifest_lines = []

    def declared_at(path):
        for n, text in enumerate(manifest_lines, 1):
            if re.search(r"""\bpath:\s*["']?""" + re.escape(path) + r"""["']?\s*[,}]?\s*(#.*)?$""", text) or \
                    re.search(r"""\bpath:\s*["']""" + re.escape(path) + r"""["']""", text):
                return n
        return None

    for e, route, discs in parsed:
        method = str(e.get("method", "")).upper()
        bound = [m for m in mappings if _matches(route, discs, method, m)]
        if bound:
            row(e, "ok", [f"{m['class']}.{m['method']}" for m in bound])
            continue
        on_route = [m for m in mappings if route in {route_key(r) for r in m["routes"]} and _method_ok(method, m)]
        declared = f"{method} {e['path']}"
        if not on_route:
            row(e, "route-missing")
            findings.append(finding(
                "Should-fix", "6 endpoint route", s.id, manifest, declared_at(e["path"]),
                f"declared endpoint {declared} has no request mapping in the slice's source "
                f"(route compared before any `?`, path variables by position)",
                "a stale endpoint is removed by `--fix-manifests`; a missing handler is a source change"))
            continue
        names = {d[0] for d in discs}
        row(e, "discriminator-unbound")
        relevant = [m for m in on_route
                    if (set(_positive_params(m)) & names) or any(_binds(d, m) for d in discs)
                    or (not discs and _positive_params(m))]
        details = [] if relevant else [f"no handler on {route} binds "
                                       + ", ".join(d[0] + (f"={d[1]}" if d[1] else "") for d in discs)]
        for m in relevant:
            unbound = [d[0] + (f"={d[1]}" if d[1] else "") for d in discs if _binds(d, m) is None]
            extra = [p for p in _positive_params(m) if p not in {d[0] for d in discs}]
            part = f"{m['class']}.{m['method']} ({m['file']}:{m['line']})"
            if unbound:
                part += f" does not bind {', '.join(unbound)}"
            if extra:
                part += (" and" if unbound else "") + f" requires params {', '.join(extra)} the endpoint does not declare"
            details.append(part)
        sibling = [m for m in mappings if m not in on_route and discs and all(_binds(d, m) for d in discs)]
        hint = "each discriminator after `?` must be bound in the handler that serves the route (manifest-guide.md §3)"
        if sibling:
            names = ", ".join(f"{m['class']}.{m['method']}" for m in sibling)
            hint = f"bound only in a handler on another route: {names} — per-handler, not per-slice (manifest-guide.md §3)"
        findings.append(finding(
            "Should-fix", "6 discriminator", s.id, manifest, declared_at(e["path"]),
            f"declared endpoint {declared}: {'; '.join(details)}", hint))
    # gate 6 reads undeclared mappings on views, and counts them on commands; a translation's webhook ingress is the
    # external system's API, not the bounded context's, so its manifest declares none
    for m in mappings if s.kind in ("view", "command") else []:
        for route in m["routes"]:
            single = dict(m, routes=[route])
            ok = any(_matches(r, d, str(e.get("method", "")).upper(), single) for e, r, d in parsed)
            if not ok:
                shown_params = [p.strip() for p in m["params"] if p.partition("=")[0].strip() in _positive_params(m)]
                qs = "&".join(p + ("" if "=" in p else "=") for p in shown_params)
                shown = route + (f"?{qs}" if qs else "")
                findings.append(finding(
                    "Should-fix", "6 undeclared mapping", s.id, m["file"], m["line"],
                    f"{'/'.join(m['httpMethods'])} {shown} ({m['class']}.{m['method']}) is not described by any "
                    f"endpoint in {manifest}",
                    "the code is right and the manifest is stale — `--fix-manifests` adds it"))


def _check_lane(project, bc_obj, lane, findings):
    sig, present, bc = lane["signals"], lane["_present"], bc_obj.name
    path = bc_obj.key
    described = {
        "decider": lambda: "deciders " + ", ".join(f"{d['symbol']} ({d['file']}:{d['line']})" for d in sig["decider"]),
        "aggregate": lambda: f"{sig['aggregates']['dir']}/ ({sig['aggregates']['files']} source file(s))",
        "service-entity": lambda: f"{sig['entities']['dir']}/ ({sig['entities']['files']} source file(s))",
    }
    if len(present) > 1:
        findings.append(finding(
            "Blocking", "14 two write styles", None, path, None,
            f"bounded context '{bc}' holds {len(present)} write styles: " + "; ".join(described[p]() for p in present),
            "two write designs over one consistency boundary — report both signals and stop; do not pick one (§R5)"))
    elif present == ["service-entity"] and sig["eventStore"]:
        refs = ", ".join(f"{r['symbol']} ({r['file']}:{r['line']})" for r in sig["eventStore"])
        findings.append(finding(
            "Blocking", "14 entities with event store", None, path, None,
            f"bounded context '{bc}' has {described['service-entity']()} and references the event store: {refs}",
            "the BC is drifting off the service-entity lane (§R5)"))
    lanes = {}
    for d in lane["declared"]:
        lanes.setdefault(d["lane"], []).append(d)
    if len(lanes) > 1:
        findings.append(finding(
            "Blocking", "14 declared lanes", None, path, None,
            f"manifests in bounded context '{bc}' declare {len(lanes)} lanes: "
            + "; ".join(f"{k}: {', '.join(d['slice'] for d in v)}" for k, v in sorted(lanes.items())),
            "a half-finished migration between write styles — corroborates gate 14"))
    if len(present) == 1 and lane["detected"] != "conflict":
        detected = present[0]
        for d in lane["declared"]:
            if d["lane"] != detected:
                findings.append(finding(
                    "Should-fix", "14 stale lane", d["slice"], d["file"], None,
                    f"declares lane: {d['lane']}, but the only write-style signal in '{bc}' is "
                    f"{described[detected]()}",
                    "`lane` is machine-derived; `--fix-manifests` repairs it"))


# --------------------------------------------------------------------------------------------------
# output
# --------------------------------------------------------------------------------------------------

def print_facts(data, quiet):
    out = sys.stdout
    if data["unparsed"]:
        print("UNPARSED — the reader could not read these; facts from them are missing, not empty:", file=out)
        for u in data["unparsed"]:
            print(f"  {u['file']}:{u['line'] or '-'}  {u['what']}: {u['reason']}", file=out)
        print("", file=out)
    if quiet:
        return
    for s in data["slices"]:
        print(f"{s['id']}  [{s['kind']}]  {s['dir']}", file=out)
        print(f"  package   {s['package']}", file=out)
        print(f"  files     {', '.join(s['files']) or '-'}", file=out)
        for h in s["handlers"]:
            msg = h["message"]
            shown = "-" if msg is None else msg["name"] + (f" (as {msg['written']})" if msg["written"] != msg["name"] else "")
            print(f"  handler   @{h['annotation']} {h['class']}.{h['method']}({shown})  {h['file']}:{h['line']}", file=out)
        for m in s["mappings"]:
            routes = ", ".join(m["routes"]) if m["routes"] is not None else "UNPARSED"
            extra = f" params={m['params']}" if m["params"] else ""
            print(f"  mapping   {'/'.join(m['httpMethods'])} {routes}{extra}  {m['class']}.{m['method']}", file=out)
        for key in ("handles", "dispatches", "publishes"):
            if s[key]:
                print(f"  {key:<9} {', '.join(sorted({x['name'] for x in s[key]}))}", file=out)
        if s["subscriptions"]:
            print(f"  streams   {', '.join(str(x['aggregateType']) for x in s['subscriptions'])}", file=out)
        for rm in s.get("readModels", []):
            print(f"  model     {rm['name']} ({rm['store']}): {', '.join(c['name'] for c in rm['columns'])}", file=out)
        for na in s["notAnalysed"]:
            print(f"  NOT ANALYSED  {na['file']}:{na['line']}  {na['reason']}", file=out)
    for bc in data["bcs"]:
        print(f"lane      {bc['name']}: {bc['lane']['detected']}", file=out)
    print(f"\n{len(data['slices'])} slice(s), {data['sourceFiles']} source file(s), {len(data['messages'])} message(s), "
          f"{len(data['unparsed'])} unparsed, {len(data['unresolved'])} unresolved.", file=out)
    if not data["manifestsRead"]:
        print("Manifests NOT read (no pyyaml): slice ids come from directory names.", file=out)


def print_check(data, quiet):
    out = sys.stdout
    if data["skippedManifests"]:
        print("MANIFESTS NOT CHECKED — they do not parse, so their slices are absent from every check:", file=out)
        for m in data["skippedManifests"]:
            print(f"  {m['file']}  {m['reason']}", file=out)
        print("", file=out)
    if data["unparsed"]:
        print("UNPARSED SOURCE — checks depending on it did not run:", file=out)
        for u in data["unparsed"]:
            print(f"  {u['file']}:{u['line'] or '-'}  {u['what']}: {u['reason']}", file=out)
        print("", file=out)
    if not quiet:
        for lane in data["lanes"]:
            print(f"lane      {lane['bc']}: {lane['detected']}", file=out)
        print("", file=out)
    for f in data["findings"]:
        loc = f"{f['file']}:{f['line']}" if f["line"] else f["file"]
        print(f"{f['severity']:<11} {f['gate']:<30} {loc}", file=out)
        print(f"            {f['message']}", file=out)
        if f["hint"]:
            print(f"            → {f['hint']}", file=out)
    for u in data["unverified"]:
        print(f"UNVERIFIED  {u['gate']:<30} {u['file']}", file=out)
        print(f"            {u['reason']}", file=out)
    if not quiet:
        print(f"\n{data['manifests']} manifest(s), {data['parsed']} checked, {len(data['findings'])} finding(s), "
              f"{len(data['unverified'])} unverified, {len(data['unparsed'])} unparsed.", file=out)


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="slice-source",
        description="Syntactic facts from a slice-law project's Java/Kotlin sources. Deterministic; never writes.")
    parser.add_argument("root", nargs="?", default=".", help="directory to scan (default: .)")
    parser.add_argument("--json", action="store_true", help="emit JSON")
    parser.add_argument("--check", action="store_true", help="compare manifests with source (gates 6, 11(b), 14)")
    parser.add_argument("--bc", action="append", default=[], help="restrict to this bounded context (repeatable)")
    parser.add_argument("--quiet", action="store_true", help="text mode: findings and unparsed only")
    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        return 2 if exc.code else 0

    root = Path(args.root).resolve()
    if not root.is_dir():
        print(f"slice-source: not a directory: {args.root}", file=sys.stderr)
        return 2
    try:
        import yaml
    except ImportError:
        yaml = None
        if args.check:
            print("slice-source: --check reads slice.yaml and needs `pyyaml`.\n"
                  "  uv run --script slice-source.py …   (installs the pinned version)\n"
                  "  pip install pyyaml", file=sys.stderr)
            return 2

    project = Project(root, args.root, set(args.bc), yaml)
    if args.check:
        data = check_json(project)
        if args.json:
            json.dump(data, sys.stdout, indent=2)
            sys.stdout.write("\n")
        else:
            print_check(data, args.quiet)
        if data["findings"]:
            return 1
        return 3 if data["unparsed"] or data["unverified"] else 0
    data = facts_json(project)
    if args.json:
        json.dump(data, sys.stdout, indent=2)
        sys.stdout.write("\n")
    else:
        print_facts(data, args.quiet)
    return 3 if data["unparsed"] else 0


if __name__ == "__main__":
    sys.exit(main())
