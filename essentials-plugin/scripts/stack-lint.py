#!/usr/bin/env python3
"""stack-lint — the deterministic half of the stack contract (S1-S11) over a project's files.

Why this exists
---------------
Every requirement in `references/stack/stack-contract.md` that a project can break by leaving
something out compiles cleanly: a missing `provided` dependency, a Jackson 2 module jar, a missing
`@Import`, a Boot 3 config key. Most of them then kill context startup under a message that names
nothing relevant, and a few never fail at all. Whether a POM declares an artifact, whether a config
file carries a key, whether a source file imports a class — those are facts, and a language model
has no business guessing at them. This script reads them.

It is the stack-contract counterpart of `slice-lint.py`. `/essentials:init` runs it on the project
it renders before the smoke build, `/essentials:upgrade` Group C takes its output verbatim, and
`/essentials:review` reports it. What it cannot decide (whether security was *decided*, whether a
payload constructor's parameter names match its JSON) stays with the reader of the contract.

Every finding carries an `ESS-S<n>[.<m>]` id that resolves to a contract heading, a `check` slug,
a severity, `file:line`, a one-line fix, and — where the fix is mechanical — a machine-readable fix
descriptor. The contract is cited, never restated: each rule names the contract line it enforces.

Usage
-----
    stack-lint.py [ROOT] [options]

    ROOT                    the project root, holding the reactor pom.xml (default: .)
    --json                  emit the report as JSON on stdout
    --quiet                 print findings only, no header or summary
    --language kotlin|java  override detection (likewise --db, --web, --frontend)
    --fail-on LEVEL         advisory (default) | should-fix | blocking — lowest severity that exits 1
    --pins PATH             stack-pins.md (default: ../references/stack/stack-pins.md)
    --contract PATH         stack-contract.md (default: ../references/stack/stack-contract.md)
    --rules                 print the rule table (with --json: as JSON) and exit
    --self-test             run tests/stack-lint/ against expectations.json, check every rule's
                            citation line and id, and exit

Exit codes
----------
    0   no finding at or above --fail-on (with --self-test: every expectation met)
    1   findings (with --self-test: an expectation not met)
    2   could not run — not a directory, no pom.xml, no Essentials dependency, a POM that does not
        parse, or an unreadable stack-pins.md / stack-contract.md

Standard library only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import xml.parsers.expat
from pathlib import Path

PLUGIN = Path(__file__).resolve().parent.parent
CONTRACT_REL = "references/stack/stack-contract.md"
PINS_REL = "references/stack/stack-pins.md"
DOCS = {
    "C": CONTRACT_REL,
    "K": "references/stack/kotlin-spring-boot.md",
    "J": "references/stack/java-spring-boot.md",
    "F": "references/stack/frontend-react.md",
}
SKIP_DIRS = {".git", "target", "build", "out", "node_modules", ".idea", ".gradle", "dist", ".mvn",
             "__pycache__", ".venv"}
SEVERITY_ORDER = {"Blocking": 0, "Should-fix": 1, "Advisory": 2}
FAIL_ON = {"blocking": 0, "should-fix": 1, "advisory": 2}

ESS_GROUP = "dk.trustworks.essentials"
ESS_COMPONENTS = "dk.trustworks.essentials.components"
BOOT = "org.springframework.boot"
STARTERS = {
    "spring-boot-starter-postgresql-event-store": "pg-event-sourced",
    "spring-boot-starter-postgresql": "pg-crud",
    "spring-boot-starter-mongodb": "mongo",
}
# spring.data.mongodb.* keys Boot 4 no longer binds, and where each went (level=error deprecations
# in META-INF/spring-configuration-metadata.json of spring-boot-mongodb and spring-boot-data-mongodb
# on the Boot line stack-pins.md targets). auto-index-creation, field-naming-strategy, gridfs.bucket,
# gridfs.database, repositories.type and representation.big-decimal are still bound there.
MONGO_MOVED = {
    f"spring.data.mongodb.{k}": f"spring.mongodb.{k}"
    for k in ("uri", "host", "port", "database", "username", "password", "authentication-database",
              "replica-set-name", "additional-hosts", "protocol", "ssl.enabled", "ssl.bundle")
}
MONGO_MOVED["spring.data.mongodb.uuid-representation"] = "spring.mongodb.representation.uuid"
MONGO_MOVED["spring.data.mongodb.grid-fs-database"] = "spring.data.mongodb.gridfs.database"
# Looked up by relaxed-binding form (see norm_key).
MONGO_MOVED = {re.sub(r"[-_]", "", k.lower()): v for k, v in MONGO_MOVED.items()}
# Declaring these instead of a starter is how a context ends up half-configured (contract :75).
LOOSE_COMPONENTS = {
    "postgresql-queue", "postgresql-distributed-fenced-lock", "postgresql-event-store",
    "spring-postgresql-event-store", "springdata-mongo-queue", "springdata-mongo-distributed-fenced-lock",
}


# ---------------------------------------------------------------------------------------------
# The rule table. One row per check. `cites` are (doc, line, token): the first is always the
# contract; --self-test fails when the cited line no longer carries the token, so a contract edit
# that moves a line makes this table loud instead of quietly pointing at the wrong sentence.
# `src` records the 0.60 framework evidence for the rule (repository paths, not shipped).


class Rule:
    __slots__ = ("check", "sid", "severity", "cites", "title", "src")

    def __init__(self, check, sid, severity, cites, title, src=""):
        self.check, self.sid, self.severity, self.cites, self.title, self.src = (
            check, sid, severity, cites, title, src)

    @property
    def id(self):
        return f"ESS-{self.sid}"

    def cite_strings(self):
        return [f"{DOCS[d]}:{n}" for d, n, _ in self.cites]


RULES_LIST = [
    # S1 ---------------------------------------------------------------------------------------
    Rule("s1-boot-line", "S1", "Blocking", [("C", 55, "application runs on")],
         "Spring Boot is not on the line stack-pins.md targets"),
    Rule("s1-java-baseline", "S1", "Blocking", [("C", 57, "UnsupportedClassVersionError")],
         "Java release below the stack-pins.md baseline",
         "spring-boot-starter-parent pom (Maven Central): java.version defaults below the Essentials "
         "baseline and maven.compiler.release follows it; root pom.xml java.release.version"),
    Rule("s1-one-essentials-version", "S1", "Blocking", [("C", 59, "`essentials.version` property")],
         "an Essentials artifact not versioned from the one essentials.version property"),
    Rule("s1-kotlin-jvm-target", "S1", "Blocking",
         [("C", 55, "application runs on"), ("K", 42, "`jvmTarget` must match the `java.version` pin")],
         "kotlin-maven-plugin jvmTarget below the Java baseline"),
    # S2 ---------------------------------------------------------------------------------------
    Rule("s2-one-starter", "S2", "Blocking", [("C", 68, "MUST pick exactly one persistence profile"),
                                              ("C", 80, "declaring the *components* individually")],
         "no persistence starter, or starters of two profiles"),
    Rule("s2-redundant-starter", "S2", "Advisory", [("C", 79, "Declaring both is")],
         "the event-store starter and the PostgreSQL starter both declared"),
    Rule("s2-eventsourced-aggregates", "S2", "Blocking",
         [("C", 88, "`eventsourced-aggregates`; on Kotlin also `kotlin-eventsourcing`")],
         "eventsourced-aggregates not declared on pg-event-sourced (optional in the starter, so not transitive)",
         "components/spring-boot-starter-postgresql-event-store/pom.xml:105-110 (<optional>true</optional>)"),
    Rule("s2-kotlin-eventsourcing", "S2", "Blocking",
         [("C", 88, "`eventsourced-aggregates`; on Kotlin also `kotlin-eventsourcing`")],
         "kotlin-eventsourcing not declared on a Kotlin pg-event-sourced project (no starter depends on it)",
         "no starter POM names kotlin-eventsourcing (components/spring-boot-starter-*/pom.xml)"),
    Rule("s2-kotlin-eventsourcing-on-java", "S2", "Should-fix",
         [("C", 91, "A Java project must not carry `kotlin-eventsourcing`")],
         "kotlin-eventsourcing declared on a Java project, whose decider family lives in eventsourced-aggregates"),
    Rule("s2-document-db", "S2", "Advisory",
         [("C", 89, "`postgresql-document-db`, when read models use the document store")],
         "postgresql-document-db not declared on a Postgres profile (no starter depends on it)",
         "components/spring-boot-starter-postgresql/pom.xml, …-event-store/pom.xml: no postgresql-document-db"),
    Rule("s2-mongo-keys", "S2", "Blocking", [("C", 140, "connection properties are `spring.mongodb.*`")],
         "a spring.data.mongodb.* key, unbound in Boot 4",
         "LLM/LLM-spring-boot-starter-modules.md:684"),
    # S2.1 -------------------------------------------------------------------------------------
    Rule("s2.1-jdbc-starter", "S2.1", "Blocking", [("C", 113, "`spring-boot-starter-jdbc`")],
         "spring-boot-starter-jdbc missing on a Postgres profile",
         "components/spring-boot-starter-postgresql/pom.xml:46-49 (provided)"),
    Rule("s2.1-postgresql-driver", "S2.1", "Blocking", [("C", 114, "`org.postgresql:postgresql`")],
         "PostgreSQL JDBC driver missing on a Postgres profile",
         "components/spring-boot-starter-postgresql/pom.xml:157-160 (provided)"),
    Rule("s2.1-jdbi", "S2.1", "Blocking", [("C", 115, "`jdbi3-core`, `jdbi3-postgres`")],
         "jdbi3-core / jdbi3-postgres missing on a Postgres profile",
         "components/spring-boot-starter-postgresql/pom.xml:147-154 (provided)"),
    Rule("s2.1-kotlin-stdlib", "S2.1", "Blocking", [("C", 116, "`kotlin-stdlib-jdk8`, `kotlin-reflect`")],
         "kotlin-stdlib missing on a Postgres profile (either language)",
         "components/postgresql-document-db/pom.xml:50-52 (provided)"),
    Rule("s2.1-kotlin-reflect", "S2.1", "Blocking", [("C", 116, "`kotlin-stdlib-jdk8`, `kotlin-reflect`")],
         "kotlin-reflect missing on a Postgres profile (either language)",
         "components/postgresql-document-db/pom.xml:44-46 (provided)"),
    Rule("s2.1-data-mongodb", "S2.1", "Blocking", [("C", 117, "`spring-boot-starter-data-mongodb`")],
         "spring-boot-starter-data-mongodb missing on the mongo profile",
         "components/spring-boot-starter-mongodb/pom.xml:46-49 (provided)"),
    Rule("s2.1-jackson3-databind", "S2.1", "Blocking", [("C", 118, "`tools.jackson.core:jackson-databind`")],
         "Jackson 3 databind missing on an application with no Boot web or Jackson starter",
         "components/spring-boot-starter-postgresql/pom.xml:141-144 (provided)"),
    Rule("s2.1-reactor-core", "S2.1", "Blocking", [("C", 119, "`reactor-core`")],
         "reactor-core missing on a non-WebFlux application",
         "components/spring-boot-starter-postgresql/pom.xml:128-130 (provided)"),
    Rule("s2.1-scope", "S2.1", "Blocking", [("C", 131, "compile scope in the application")],
         "an S2.1 dependency declared provided/test/system — not on the runtime classpath"),
    Rule("s2.1-runtime-scope", "S2.1", "Advisory", [("C", 131, "compile scope in the application")],
         "an S2.1 dependency declared at runtime scope instead of compile"),
    # S3 ---------------------------------------------------------------------------------------
    Rule("s3.1-jackson2-essentials-module", "S3.1", "Blocking", [("C", 172, "`immutable-jackson` are gone")],
         "a Jackson 2 Essentials module (types-jackson / immutable-jackson) declared",
         "root pom.xml modules: only types-jackson3 / immutable-jackson3 are built"),
    Rule("s3.1-jackson2-databind", "S3.1", "Advisory", [("C", 174, "No Essentials artifact needs Jackson 2's")],
         "Jackson 2 jackson-databind declared; nothing in Essentials uses it"),
    Rule("s3.3-own-json-mapper", "S3.3", "Blocking", [("C", 195, "replaces Boot's `JsonMapper` with its own bean")],
         "a JsonMapper bean that does not register EssentialTypesJacksonModule"),
    Rule("s3.4-jackson-module-kotlin", "S3.4", "Blocking",
         [("C", 197, "Kotlin types are covered by neither mapper"),
          ("K", 76, "`tools.jackson.module:jackson-module-kotlin`")],
         "Kotlin project without Jackson 3 jackson-module-kotlin"),
    Rule("s3.4-persistence-serializer", "S3.4", "Blocking",
         [("C", 185, "comes from your own `JSONSerializer` / `JSONEventSerializer` bean"),
          ("K", 84, "its **type is the load-bearing part**")],
         "Kotlin project without its own persistence serializer bean carrying KotlinModule",
         "EventStoreConfiguration.java:567-571 and postgresql EssentialsComponentsConfiguration.java:472-477 "
         "(@ConditionalOnMissingBean, back off by type); EssentialsObjectMappers.java:54"),
    Rule("s3.5-java-parameters", "S3.5", "Blocking",
         [("C", 206, "Compile with parameter names retained"), ("J", 19, "`maven-compiler-plugin` with `-parameters`"),
          ("K", 31, "still carry `-parameters`")],
         "javac does not retain parameter names",
         "spring-boot-starter-parent pom: maven-compiler-plugin <parameters>true</parameters>"),
    Rule("s3.5-kotlin-allopen", "S3.5", "Blocking",
         [("C", 206, "Compile with parameter names retained"), ("K", 18, "`spring` compiler plugin")],
         "kotlin-maven-plugin without the all-open spring compiler plugin"),
    Rule("s3.5-kotlin-jsr305", "S3.5", "Blocking",
         [("C", 206, "Compile with parameter names retained"), ("K", 26, "`-Xjsr305=strict`")],
         "kotlin-maven-plugin without -Xjsr305=strict"),
    Rule("s3.5-kotlin-param-property", "S3.5", "Blocking",
         [("C", 206, "Compile with parameter names retained"),
          ("K", 27, "`-Xannotation-default-target=param-property`")],
         "kotlin-maven-plugin without -Xannotation-default-target=param-property"),
    # S4 ---------------------------------------------------------------------------------------
    Rule("s4-configurer-missing", "S4", "Blocking", [("C", 218, "auto-configures nothing")],
         "types-spring-web declared but no Essentials web configurer imported",
         "types-spring-web has no META-INF (no AutoConfiguration.imports)"),
    Rule("s4-configurer-count", "S4", "Blocking", [("C", 224, "whichever web stack you are on")],
         "more than one Essentials web configurer imported"),
    Rule("s4-configurer-web-mismatch", "S4", "Blocking", [("C", 224, "whichever web stack you are on")],
         "the imported configurer is for the other web stack",
         "EssentialsWebFluxConfigurer.java:47 implements WebFluxConfigurer; "
         "EssentialsWebMvcConfigurer.java:51 implements WebMvcConfigurer"),
    Rule("s4-types-spring-web-missing", "S4", "Advisory",
         [("C", 218, "auto-configures nothing"), ("J", 79, "`types-spring-web` and the `@Import`")],
         "Java web application without types-spring-web"),
    # S5 ---------------------------------------------------------------------------------------
    Rule("s5-document-db-factory", "S5", "Should-fix", [("C", 265, "`DocumentDbRepositoryFactory` bean built from")],
         "postgresql-document-db declared but no DocumentDbRepositoryFactory bean",
         "DocumentDbRepository.kt:536 (plain class, no auto-configuration)"),
    Rule("s5-mongo-document-db", "S5", "Should-fix", [("C", 266, "on the `mongo` profile there is no such thing")],
         "postgresql-document-db declared on the mongo profile"),
    Rule("s5-transactional-mode", "S5", "Should-fix", [("C", 261, "binds to nothing")],
         "essentials.durable-queues.transactional-mode in configuration",
         "docs/MIGRATION-0.60.md:542; EssentialsComponentsProperties.java:292-303 (no such field)"),
    Rule("s5-aggregate-declarations", "S5", "Should-fix", [("C", 271, "`EssentialsAggregateDeclarations`")],
         "an aggregate policy annotation on a class no EssentialsAggregateDeclarations bean declares",
         "AggregateSnapshotPolicy.java:38-40, AggregateClosingBooksPolicy.java:38 (@Target TYPE); "
         "EssentialsAggregateDeclarations.java:49; LLM/LLM-eventsourced-aggregates.md:671-674"),
    # S7 ---------------------------------------------------------------------------------------
    Rule("s7-spec-generation", "S7", "Advisory", [("C", 308, "generated from the code, never hand-written")],
         "nothing generates contracts/openapi.json: no test writing it from /v3/api-docs, no springdoc maven plugin"),
    Rule("s7-contract-file", "S7", "Advisory", [("C", 310, "checked-in `contracts/openapi.json`")],
         "no committed spec: the frontend generator's input, or contracts/openapi.json, does not exist"),
    Rule("s7-frontend-input", "S7", "Advisory",
         [("C", 310, "checked-in `contracts/openapi.json`"),
          ("F", 66, "target: '../contracts/openapi.json'")],
         "the frontend generator reads a live /v3/api-docs URL instead of the committed spec"),
    Rule("s7-model-converter", "S7", "Advisory", [("C", 328, "`SingleValueTypeModelConverter` as a bean")],
         "springdoc runs but SingleValueTypeModelConverter is not registered: the spec types semantic ids as objects",
         "types-spring-web/src/main/java/dk/trustworks/essentials/types/spring/web/SingleValueTypeModelConverter.java "
         "(a swagger-core ModelConverter; no auto-configuration in types-spring-web)"),
    # S8 ---------------------------------------------------------------------------------------
    Rule("s8-embedded-cors", "S8", "Should-fix",
         [("C", 346, "pick a mode explicitly and configure only that mode's pieces"),
          ("F", 191, "Do not configure CORS")],
         "embedded frontend with a CORS configuration"),
    Rule("s8-embedded-base-url", "S8", "Should-fix",
         [("C", 346, "pick a mode explicitly and configure only that mode's pieces"),
          ("F", 194, "Do not set an API base URL")],
         "embedded frontend with an API base URL set"),
    Rule("s8-embedded-spa-filter", "S8", "Should-fix",
         [("C", 346, "pick a mode explicitly and configure only that mode's pieces"),
          ("F", 145, "A SPA fallback filter")],
         "embedded frontend without a SPA fallback to index.html"),
    Rule("s8-embedded-static-copy", "S8", "Should-fix",
         [("C", 346, "pick a mode explicitly and configure only that mode's pieces"),
          ("F", 135, "`maven-resources-plugin` copies `frontend/dist`")],
         "embedded frontend whose build output is not copied into static resources"),
    Rule("s8-standalone-base-url", "S8", "Blocking",
         [("C", 346, "pick a mode explicitly and configure only that mode's pieces"),
          ("F", 256, "configured *and consumed*")],
         "standalone frontend whose fetch mutator does not prefix VITE_API_BASE_URL"),
    Rule("s8-standalone-cors-source", "S8", "Blocking",
         [("C", 346, "pick a mode explicitly and configure only that mode's pieces"),
          ("F", 208, "expressed as a `CorsConfigurationSource` bean")],
         "standalone frontend and no CorsConfigurationSource bean"),
    Rule("s8-standalone-cors-webfilter", "S8", "Should-fix",
         [("C", 346, "pick a mode explicitly and configure only that mode's pieces"),
          ("F", 212, "Expose the source, not a standalone `CorsWebFilter`")],
         "CORS applied by a standalone CorsWebFilter/CorsFilter bean"),
    Rule("s8-standalone-cors-value", "S8", "Blocking",
         [("C", 346, "pick a mode explicitly and configure only that mode's pieces"),
          ("F", 240, "Bind the origin list with `@ConfigurationProperties`, never `@Value`")],
         "CORS origins bound with @Value"),
    Rule("s8-standalone-cors-wildcard", "S8", "Blocking",
         [("C", 346, "pick a mode explicitly and configure only that mode's pieces"),
          ("F", 209, "`allowCredentials = true` **cannot**")],
         "wildcard origin combined with allowCredentials"),
    Rule("s8-standalone-dead-spa", "S8", "Advisory",
         [("C", 346, "pick a mode explicitly and configure only that mode's pieces"),
          ("F", 277, "SPA filter is dead code in this mode")],
         "standalone frontend with a backend SPA fallback"),
    Rule("s8-generated-gitignore", "S8", "Should-fix",
         [("C", 342, "contract-first pipeline of S7"),
          ("F", 76, "`generated/` and `model/` must not be committed")],
         "the generated API client is not git-ignored"),
    # S9 ---------------------------------------------------------------------------------------
    Rule("s9-admin-spi", "S9", "Should-fix",
         [("C", 359, "`EssentialsAuthenticatedUser` and `EssentialsSecurityProvider`")],
         "an admin starter without the two security SPIs",
         "shared/src/main/java/dk/trustworks/essentials/shared/security/"
         "{EssentialsSecurityProvider,EssentialsAuthenticatedUser}.java"),
    # S10 --------------------------------------------------------------------------------------
    Rule("s10-tc1-coordinates", "S10", "Advisory", [("C", 374, "The 1.x coordinates no longer resolve")],
         "a Testcontainers 1.x artifact coordinate",
         "~/.m2 org/testcontainers/testcontainers-{postgresql,mongodb} at the testcontainers-bom pin"),
    Rule("s10-tc-package", "S10", "Advisory", [("C", 399, "`org.testcontainers.postgresql.PostgreSQLContainer`")],
         "a container class imported from the deprecated org.testcontainers.containers package",
         "testcontainers-postgresql jar at the pin: org.testcontainers.containers.PostgreSQLContainer is "
         "@Deprecated, org.testcontainers.postgresql.PostgreSQLContainer is not generic (javap); "
         "likewise testcontainers-mongodb MongoDBContainer"),
    Rule("s10-webtestclient-package", "S10", "Advisory",
         [("C", 391, "`@AutoConfigureWebTestClient` is no longer in")],
         "@AutoConfigureWebTestClient imported from its Boot 3 package"),
    Rule("s10-failsafe", "S10", "Advisory", [("C", 372, "Failsafe runs those during `verify`")],
         "integration tests present but maven-failsafe-plugin is not bound",
         "spring-boot-starter-parent pom: failsafe is in pluginManagement only"),
    # S11 --------------------------------------------------------------------------------------
    Rule("s11-skip-frontend", "S11", "Should-fix", [("C", 423, "**`skip-frontend` profile** must exist")],
         "frontend-maven-plugin without a skip-frontend profile"),
    Rule("s11-boot-parent", "S11", "Advisory", [("C", 416, "`spring-boot-starter-parent` is the parent POM")],
         "the reactor does not inherit spring-boot-starter-parent"),
]
RULES = {r.check: r for r in RULES_LIST}
# Slugs that were emitted once and are retired: never reuse one for a different check. The forked-app
# spec pipeline they checked is no longer a contract shape (S7 generates the spec from an integration test).
RETIRED_CHECKS = {"s7-start-stop", "s7-openapi-profile"}


# ---------------------------------------------------------------------------------------------
# Findings


class Finding:
    __slots__ = ("rule", "file", "line", "message", "fix_text", "mechanical", "ops")

    def __init__(self, check, file, line, message, fix_text, ops=None, mechanical=None):
        self.rule = RULES[check]
        self.file = file
        self.line = line
        self.message = message
        self.fix_text = fix_text
        self.ops = ops or []
        self.mechanical = bool(self.ops) if mechanical is None else mechanical

    def sort_key(self):
        return (SEVERITY_ORDER[self.rule.severity], self.file or "", self.line or 0, self.rule.check)

    def as_dict(self, anchors):
        return {
            "id": self.rule.id,
            "check": self.rule.check,
            "severity": self.rule.severity,
            "file": self.file,
            "line": self.line,
            "message": self.message,
            "fix": {"text": self.fix_text, "mechanical": self.mechanical, "ops": self.ops},
            "cite": self.rule.cite_strings(),
            "link": f"{CONTRACT_REL}#{anchors.get(self.rule.sid, '')}",
        }


class UsageError(Exception):
    pass


# ---------------------------------------------------------------------------------------------
# Plugin sources: pins and contract headings


def read_pins(path: Path):
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise UsageError(f"cannot read {path}: {exc}")
    pins = {}
    for line in text.splitlines():
        if not line.lstrip().startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 2:
            pins[cells[0].replace("`", "").strip()] = cells[1].replace("*", "").strip()
    for need in ("spring-boot-starter-parent", "java.version"):
        if not re.match(r"^\d+", pins.get(need, "")):
            raise UsageError(f"{path}: no `{need}` pin")
    return pins


def gh_anchor(heading: str):
    """GitHub's heading anchor: lowercase, punctuation dropped, spaces to hyphens."""
    s = re.sub(r"[^\w\- ]", "", heading.strip().lower())
    return s.replace(" ", "-")


def read_contract_anchors(path: Path):
    """{'S2': anchor, 'S2.1': anchor, 'S3.4': <S3's anchor>, …} and the contract's lines."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise UsageError(f"cannot read {path}: {exc}")
    anchors, current = {}, None
    for line in text.splitlines():
        m = re.match(r"^#{2,3}\s+(S\d+(?:\.\d+)?)\s+—", line)
        if m:
            anchors[m.group(1)] = gh_anchor(line.lstrip("#").strip())
            current = m.group(1)
            continue
        b = re.match(r"^\*\*(S\d+\.\d+)\s+—", line)
        if b and current:
            anchors.setdefault(b.group(1), anchors[current])
    if not anchors:
        raise UsageError(f"{path}: no `## S<n> —` headings")
    return anchors, text.splitlines()


# ---------------------------------------------------------------------------------------------
# XML with line numbers (ElementTree drops them; expat keeps them)


class Node:
    __slots__ = ("tag", "text", "children", "line", "parent")

    def __init__(self, tag, line, parent):
        self.tag, self.line, self.parent = tag, line, parent
        self.text, self.children = "", []

    def child(self, tag):
        for c in self.children:
            if c.tag == tag:
                return c
        return None

    def all(self, path):
        """Every node under this one matching a '/'-separated child path."""
        nodes = [self]
        for part in path.split("/"):
            nodes = [c for n in nodes for c in n.children if c.tag == part]
        return nodes

    def val(self, tag):
        c = self.child(tag)
        return c.text.strip() if c is not None else None

    def iter(self):
        yield self
        for c in self.children:
            yield from c.iter()


def parse_xml(path: Path):
    parser = xml.parsers.expat.ParserCreate()
    root = Node("#doc", 1, None)
    stack = [root]

    def start(tag, attrs):
        tag = tag.split(":")[-1]
        n = Node(tag, parser.CurrentLineNumber, stack[-1])
        stack[-1].children.append(n)
        stack.append(n)

    def end(tag):
        stack.pop()

    def chars(data):
        stack[-1].text += data

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = chars
    try:
        parser.Parse(path.read_bytes(), True)
    except (xml.parsers.expat.ExpatError, OSError) as exc:
        raise UsageError(f"{path}: not parseable XML: {exc}")
    return root.children[0] if root.children else root


# ---------------------------------------------------------------------------------------------
# POM model


class Dep:
    __slots__ = ("g", "a", "v", "scope", "line", "managed", "profile", "pom")

    def __init__(self, node, pom, managed, profile):
        self.g = node.val("groupId") or ""
        self.a = node.val("artifactId") or ""
        self.v = node.val("version")
        self.scope = node.val("scope")
        self.line = node.line
        self.managed, self.profile, self.pom = managed, profile, pom


class Pom:
    def __init__(self, path: Path, rel: str):
        self.path, self.rel = path, rel
        self.dir = path.parent
        self.root = parse_xml(path)
        r = self.root
        self.g = r.val("groupId")
        self.a = r.val("artifactId")
        parent = r.child("parent")
        self.parent = None
        if parent is not None:
            self.parent = (parent.val("groupId"), parent.val("artifactId"), parent.val("version"), parent.line)
            self.g = self.g or parent.val("groupId")
        self.props = {}
        props = r.child("properties")
        if props is not None:
            for p in props.children:
                self.props[p.tag] = (p.text.strip(), p.line)
        self.deps, self.managed, self.plugins, self.managed_plugins = [], [], [], []
        self.profiles = {}
        self._collect(r, None)
        for prof in r.all("profiles/profile"):
            pid = prof.val("id") or "?"
            self.profiles[pid] = prof
            self._collect(prof, pid)

    def _collect(self, base, profile):
        for d in base.all("dependencies/dependency"):
            self.deps.append(Dep(d, self, False, profile))
        for d in base.all("dependencyManagement/dependencies/dependency"):
            self.managed.append(Dep(d, self, True, profile))
        for p in base.all("build/plugins/plugin"):
            self.plugins.append(p)
        for p in base.all("build/pluginManagement/plugins/plugin"):
            self.managed_plugins.append(p)

    def plugin(self, artifact, include_profiles=True):
        for p in self.plugins:
            if p.val("artifactId") == artifact:
                if include_profiles or not _in_profile(p):
                    return p
        return None


def _in_profile(node):
    n = node.parent
    while n is not None:
        if n.tag == "profile":
            return True
        n = n.parent
    return False


class Project:
    def __init__(self, root: Path):
        self.root = root
        self.poms = []
        self.files = []  # every non-skipped file, as Paths
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS and not d.startswith("."))
            for f in sorted(filenames):
                p = Path(dirpath) / f
                self.files.append(p)
                if f == "pom.xml":
                    self.poms.append(Pom(p, self.rel(p)))
        self.by_ga = {(p.g, p.a): p for p in self.poms}

    def rel(self, p: Path):
        return p.relative_to(self.root).as_posix()

    def chain(self, pom):
        """pom, its in-project parent, …, outermost first-last order (child first)."""
        out, seen = [pom], {id(pom)}
        while pom.parent:
            nxt = self.by_ga.get(pom.parent[:2])
            if nxt is None or id(nxt) in seen:
                break
            out.append(nxt)
            seen.add(id(nxt))
            pom = nxt
        return out

    def external_parent(self, pom):
        """(g, a, v) of the first parent outside the project, or None."""
        top = self.chain(pom)[-1]
        return top.parent[:3] if top.parent else None

    def prop(self, pom, name):
        """(value, pom, line) resolving ${…} through the in-project chain; child wins."""
        for p in self.chain(pom):
            if name in p.props:
                v, line = p.props[name]
                return self.resolve(pom, v), p, line
        return None, None, None

    def resolve(self, pom, value, depth=0):
        if value is None or depth > 10:
            return value

        def sub(m):
            v, _, _ = self.prop(pom, m.group(1))
            return v if v is not None else m.group(0)

        new = re.sub(r"\$\{([^}]+)\}", sub, value)
        return new if new == value else self.resolve(pom, new, depth + 1)

    def all_deps(self, managed=False):
        for p in self.poms:
            yield from (p.managed if managed else p.deps)


# ---------------------------------------------------------------------------------------------
# Source text: comments stripped, line structure kept


def strip_comments(text: str, hash_comments=False):
    """Blank out // and /* */ comments (and # when asked), leaving strings and newlines intact."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(text[i:j])
            i = j
        elif c in "\"'`":
            j = i + 1
            while j < n and text[j] != c and (c == "`" or text[j] != "\n"):
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("//", i) or (hash_comments and c == "#"):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r"[^\n]", " ", text[i:j]))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def base_url_consumed(ts_text: str):
    """True when VITE_API_BASE_URL is read and the read prefixes a request URL.

    Accepts the read inline (`${import.meta.env.VITE_API_BASE_URL}…`), through a constant
    (`const BASE = import.meta.env.VITE_API_BASE_URL ?? ''` … `${BASE}${url}`), or through a function
    (`const baseUrl = (): string => import.meta.env.VITE_API_BASE_URL ?? ''` … `${baseUrl()}${url}`,
    `function baseUrl() { return import.meta.env.VITE_API_BASE_URL }`); the use is `${X}`/`${X()}` or `X + …`."""
    text = strip_comments(ts_text)
    if re.search(r"\$\{\s*import\.meta\.env\.VITE_API_BASE_URL", text):
        return True
    decl = re.compile(r"\b(?:const|let|var|function)\s+(\w+)"
                      r"((?:(?!\b(?:const|let|var|function)\s)[\s\S]){0,200}?)"
                      r"import\.meta\.env\.VITE_API_BASE_URL\b")
    for m in decl.finditer(text):
        v = re.escape(m.group(1))
        use = rf"\$\{{\s*{v}\s*(?:\(\s*\))?\s*\}}|\b{v}\s*(?:\(\s*\))?\s*\+"
        if re.search(use, text[m.end():]):
            return True
    return False


class Source:
    __slots__ = ("path", "rel", "text", "lines")

    def __init__(self, path, rel):
        self.path, self.rel = path, rel
        try:
            raw = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            raw = ""
        self.text = strip_comments(raw)
        self.lines = self.text.splitlines()

    def line_of(self, offset):
        return self.text.count("\n", 0, offset) + 1

    def find(self, pattern, flags=0):
        return [(self.line_of(m.start()), m) for m in re.finditer(pattern, self.text, flags)]

    def has(self, pattern):
        return re.search(pattern, self.text) is not None

    def annotated(self, line, annotation, lookback=4):
        lo = max(0, line - 1 - lookback)
        return any(annotation in l for l in self.lines[lo:line])


# ---------------------------------------------------------------------------------------------
# Configuration files: flattened keys with line numbers


def norm_key(key):
    return re.sub(r"[-_]", "", key.lower())


def yaml_keys(text):
    """[(dotted key, value or None, line, own)] — `own` is the key text written on that line.

    A minimal block-mapping walker, enough for application*.yml: indentation, `---`, list items
    holding mappings, and one-level flow mappings (`{ enabled: false }`)."""
    out, stack = [], []
    for no, raw in enumerate(text.splitlines(), 1):
        line = re.sub(r"(^|\s)#.*$", "", raw).rstrip()
        if not line.strip():
            continue
        if line.strip() in ("---", "..."):
            stack = []
            continue
        m = re.match(r"^(\s*)(-\s+)?([\"']?)([^\"'\s:#{}\[\],][^:{}\[\]]*?)\3\s*:(?:\s+(.*)|$)", line)
        if not m:
            continue
        indent = len(m.group(1))
        if m.group(2):
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, "[]"))
            indent += len(m.group(2))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        key, value = m.group(4).strip(), (m.group(5) or "").strip()
        path = ".".join([k for _, k in stack if k != "[]"] + [key])
        flow = re.match(r"^\{(.*)\}$", value)
        if flow:
            out.append((path, None, no, key))
            for pair in flow.group(1).split(","):
                if ":" in pair:
                    k, v = pair.split(":", 1)
                    out.append((f"{path}.{k.strip()}", v.strip(), no, k.strip()))
            continue
        out.append((path, value or None, no, key))
        if not value:
            stack.append((indent, key))
    return out


def properties_keys(text):
    out = []
    for no, raw in enumerate(text.splitlines(), 1):
        m = re.match(r"^\s*([^#!=:\s][^=:\s]*)\s*[=:]\s*(.*)$", raw)
        if m:
            out.append((m.group(1), m.group(2).strip(), no, m.group(1)))
    return out


def config_keys(path: Path):
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return []
    return properties_keys(text) if path.suffix == ".properties" else yaml_keys(text)


# ---------------------------------------------------------------------------------------------
# The lint


class Lint:
    def __init__(self, root: Path, pins, overrides):
        self.root = root
        self.pins = pins
        self.findings = []
        self.not_run = []
        self.p = Project(root)
        if not self.p.poms:
            if any(f.name in ("build.gradle", "build.gradle.kts") for f in self.p.files):
                raise UsageError("Gradle build: stack-lint reads Maven POMs only")
            raise UsageError(f"no pom.xml under {root}")
        ess = [d for d in self.p.all_deps() if d.g.startswith(ESS_GROUP)]
        if not ess:
            raise UsageError("no dk.trustworks.essentials dependency in any pom.xml — not an Essentials project")
        starter_poms = [d.pom for d in ess if d.g == ESS_COMPONENTS and d.a in STARTERS]
        self.app = starter_poms[0] if starter_poms else ess[0].pom
        self.app_deps = [d for d in self.app.deps if d.scope != "import"]
        self.facts, self.fact_source = {}, {}
        self._facts(overrides)
        self.main = [s for s in self._sources(self.app.dir, "main")]
        self.tests = [s for s in self._sources(self.root, "test")]

    # -- helpers ---------------------------------------------------------------------------------

    def add(self, check, file, line, message, fix_text, ops=None, mechanical=None):
        self.findings.append(Finding(check, file, line, message, fix_text, ops, mechanical))

    def skip(self, checks, reason):
        self.not_run.append({"checks": list(checks), "reason": reason})

    def dep(self, g, a, deps=None):
        for d in (self.app_deps if deps is None else deps):
            if d.g == g and d.a == a and d.profile is None:
                return d
        return None

    def any_dep(self, g, a):
        return [d for d in self.p.all_deps() if d.g == g and d.a == a]

    def _sources(self, base: Path, kind):
        out = []
        for f in self.p.files:
            if f.suffix not in (".java", ".kt"):
                continue
            try:
                f.relative_to(base)
            except ValueError:
                continue
            parts = f.relative_to(self.root).parts
            if "src" in parts and kind in parts[parts.index("src") + 1:parts.index("src") + 2]:
                out.append(Source(f, self.p.rel(f)))
        return out

    def src_main_dir(self):
        return self.p.rel(self.app.dir / "src" / "main")

    def config_files(self, include_test=True):
        out = []
        for f in self.p.files:
            if not re.fullmatch(r"application(-[\w.-]+)?\.(ya?ml|properties)", f.name):
                continue
            parts = f.relative_to(self.root).parts
            if "resources" in parts and ("main" in parts or (include_test and "test" in parts)):
                out.append(f)
        return out

    def _facts(self, ov):
        plugins = {p.val("artifactId") for pom in self.p.poms for p in pom.plugins}
        if ov.get("language"):
            self.facts["language"], self.fact_source["language"] = ov["language"], "flag"
        else:
            kotlin = "kotlin-maven-plugin" in plugins or any(
                "src/main/kotlin" in self.p.rel(f) for f in self.p.files)
            self.facts["language"], self.fact_source["language"] = ("kotlin" if kotlin else "java"), "detected"
        profiles = sorted({STARTERS[d.a] for d in self.app_deps if d.g == ESS_COMPONENTS and d.a in STARTERS})
        self.starters = profiles
        if ov.get("db"):
            self.facts["db"], self.fact_source["db"] = ov["db"], "flag"
        else:
            if profiles == ["pg-crud", "pg-event-sourced"]:
                db = "pg-event-sourced"
            elif len(profiles) == 1:
                db = profiles[0]
            else:
                db = None
            self.facts["db"], self.fact_source["db"] = db, "detected"
        if ov.get("web"):
            self.facts["web"], self.fact_source["web"] = ov["web"], "flag"
        else:
            flux = self.dep(BOOT, "spring-boot-starter-webflux")
            mvc = self.dep(BOOT, "spring-boot-starter-webmvc") or self.dep(BOOT, "spring-boot-starter-web")
            web = "webflux" if flux and not mvc else "webmvc" if mvc and not flux else None if mvc else "none"
            self.facts["web"], self.fact_source["web"] = web, "detected"
        self.frontend_dirs = sorted({f.parent for f in self.p.files if f.name == "package.json"})
        if ov.get("frontend"):
            self.facts["frontend"], self.fact_source["frontend"] = ov["frontend"], "flag"
        else:
            fe = ("embedded" if "frontend-maven-plugin" in plugins
                  else "standalone" if self.frontend_dirs else "none")
            self.facts["frontend"], self.fact_source["frontend"] = fe, "detected"

    # -- S1 --------------------------------------------------------------------------------------

    def s1(self):
        pom = self.app
        ext = self.p.external_parent(pom)
        boot_parent = ext if ext and ext[0] == BOOT and ext[1] == "spring-boot-starter-parent" else None
        boot_version, boot_where = None, None
        if boot_parent:
            top = self.p.chain(pom)[-1]
            boot_version, boot_where = self.p.resolve(top, boot_parent[2]), (top.rel, top.parent[3])
        else:
            for d in self.p.all_deps(managed=True):
                if d.g == BOOT and d.a == "spring-boot-dependencies" and d.scope == "import":
                    boot_version, boot_where = self.p.resolve(d.pom, d.v), (d.pom.rel, d.line)
            self.add("s11-boot-parent", self.p.chain(pom)[-1].rel, None,
                     "the reactor does not inherit spring-boot-starter-parent, so the Boot plugin and compiler "
                     "defaults the contract assumes are absent",
                     "make spring-boot-starter-parent the parent of the reactor POM")
        self.facts["bootVersion"] = boot_version
        want = ".".join(self.pins["spring-boot-starter-parent"].split(".")[:2])
        if boot_version is None or boot_where is None:
            self.skip(["s1-boot-line"], "no spring-boot-starter-parent or spring-boot-dependencies import found")
        elif ".".join(boot_version.split(".")[:2]) != want:
            self.add("s1-boot-line", boot_where[0], boot_where[1],
                     f"Spring Boot {boot_version} — Essentials targets the {want}.x line; other lines are unsupported",
                     f"move the Boot version to the {want}.x line (stack-pins.md), as a deliberate upgrade")
        # Java baseline: java.version (spring-boot-starter-parent feeds maven.compiler.release from it)
        floor_m = re.match(r"\d+", self.pins["java.version"])
        if floor_m is None:
            raise UsageError(f"stack-pins java.version {self.pins['java.version']!r} is not a number")
        floor = int(floor_m.group(0))
        seen = False
        for name in ("maven.compiler.release", "java.version"):
            v, where, line = self.p.prop(pom, name)
            if v is None or where is None:
                continue
            seen = True
            m = re.match(r"^(?:1\.)?(\d+)$", v.strip())
            if m and int(m.group(1)) < floor:
                self.add("s1-java-baseline", where.rel, line,
                         f"{name} is {v}; Essentials class files need a {floor}+ runtime",
                         f"set {name} to {self.pins['java.version']}",
                         [{"op": "set-property", "pom": where.rel, "name": name, "from": v,
                           "to": self.pins["java.version"]}])
            if name == "maven.compiler.release":
                break
        if not seen and boot_parent:
            top = self.p.chain(pom)[-1]
            self.add("s1-java-baseline", top.rel, None,
                     "no java.version property: spring-boot-starter-parent's default is below the Essentials baseline",
                     f"add <java.version>{self.pins['java.version']}</java.version> to the reactor POM's properties",
                     [{"op": "set-property", "pom": top.rel, "name": "java.version", "from": None,
                       "to": self.pins["java.version"]}])
        for p in self.p.poms:
            for plug in p.plugins + p.managed_plugins:
                for cfg in plug.all("configuration/release"):
                    v = self.p.resolve(p, cfg.text.strip()) or ""
                    if re.fullmatch(r"\d+", v) and int(v) < floor:
                        self.add("s1-java-baseline", p.rel, cfg.line,
                                 f"{plug.val('artifactId')} <release> is {v}; below the Java baseline",
                                 f"set <release> to {self.pins['java.version']}",
                                 [{"op": "replace-text", "file": p.rel, "line": cfg.line,
                                   "from": f"<release>{cfg.text.strip()}</release>",
                                   "to": f"<release>{self.pins['java.version']}</release>"}])
                if plug.val("artifactId") == "kotlin-maven-plugin":
                    for cfg in plug.all("configuration/jvmTarget"):
                        v = self.p.resolve(p, cfg.text.strip())
                        m = re.fullmatch(r"(?:1\.)?(\d+)", v or "")
                        if m and int(m.group(1)) < floor:
                            self.add("s1-kotlin-jvm-target", p.rel, cfg.line,
                                     f"kotlin-maven-plugin jvmTarget is {v}; Essentials' inline functions "
                                     f"cannot be inlined into code built below {floor}",
                                     "set <jvmTarget>${java.version}</jvmTarget>",
                                     [{"op": "replace-text", "file": p.rel, "line": cfg.line,
                                       "from": f"<jvmTarget>{cfg.text.strip()}</jvmTarget>",
                                       "to": "<jvmTarget>${java.version}</jvmTarget>"}])
        # One essentials.version
        has_prop = any("essentials.version" in p.props for p in self.p.poms)
        for d in list(self.p.all_deps()) + list(self.p.all_deps(managed=True)):
            if not d.g.startswith(ESS_GROUP) or d.v is None:
                continue
            if d.v.strip() == "${essentials.version}":
                continue
            ops = []
            if has_prop:
                ops = [{"op": "set-dependency-version", "pom": d.pom.rel, "line": d.line, "groupId": d.g,
                        "artifactId": d.a, "from": d.v.strip(), "to": "${essentials.version}"}]
            self.add("s1-one-essentials-version", d.pom.rel, d.line,
                     f"{d.a} is versioned `{d.v.strip()}`, not from the essentials.version property — "
                     "mixed Essentials versions on one classpath are unsupported",
                     "version every Essentials artifact as ${essentials.version}"
                     + ("" if has_prop else " (define the property in the reactor POM first)"),
                     ops, mechanical=has_prop)

    # -- S2 / S2.1 -------------------------------------------------------------------------------

    def s2(self):
        rel = self.app.rel
        starters = [d for d in self.app_deps if d.g == ESS_COMPONENTS and d.a in STARTERS]
        profiles = self.starters
        loose = sorted({d.a for d in self.app_deps if d.g == ESS_COMPONENTS and d.a in LOOSE_COMPONENTS})
        if not starters:
            extra = f"; it declares components individually ({', '.join(loose)})" if loose else ""
            self.add("s2-one-starter", rel, None,
                     f"no Essentials persistence starter declared{extra}",
                     "declare exactly one of " + ", ".join(sorted(STARTERS)))
        elif "mongo" in profiles and len(profiles) > 1:
            d = [s for s in starters if s.a == "spring-boot-starter-mongodb"][0]
            self.add("s2-one-starter", rel, d.line,
                     "starters of two persistence profiles declared (MongoDB and PostgreSQL)",
                     "keep the one starter for the chosen profile, remove the other")
        elif profiles == ["pg-crud", "pg-event-sourced"]:
            d = [s for s in starters if s.a == "spring-boot-starter-postgresql"][0]
            self.add("s2-redundant-starter", rel, d.line,
                     "spring-boot-starter-postgresql is already brought by spring-boot-starter-postgresql-event-store",
                     "remove the spring-boot-starter-postgresql dependency",
                     [{"op": "remove-dependency", "pom": rel, "line": d.line, "groupId": d.g, "artifactId": d.a}])
        # The Essentials modules no starter brings: the decider/aggregate APIs and the document store.
        db, lang = self.facts["db"], self.facts["language"]
        anchor = next((d.line for d in starters), None)

        def missing(check, artifact, why):
            self.add(check, rel, anchor, f"{ESS_COMPONENTS}:{artifact} is not declared — {why}",
                     f"add {ESS_COMPONENTS}:{artifact} at ${{essentials.version}} to {rel}",
                     [{"op": "add-dependency", "pom": rel, "groupId": ESS_COMPONENTS, "artifactId": artifact,
                       "scope": None, "version": "${essentials.version}"}])

        if db is None:
            self.skip(["s2-eventsourced-aggregates", "s2-kotlin-eventsourcing", "s2-document-db"],
                      "persistence profile unknown (no single starter)")
        else:
            if db == "pg-event-sourced" and not self.dep(ESS_COMPONENTS, "eventsourced-aggregates"):
                missing("s2-eventsourced-aggregates", "eventsourced-aggregates",
                        "the event-store starter marks it optional, so the decider and aggregate APIs are absent "
                        "and the first command slice does not compile")
            if db == "pg-event-sourced" and lang == "kotlin" and not self.dep(ESS_COMPONENTS, "kotlin-eventsourcing"):
                missing("s2-kotlin-eventsourcing", "kotlin-eventsourcing",
                        "no starter brings the Kotlin Decider/Evolver DSL the Kotlin command slices are written in")
            if db in ("pg-crud", "pg-event-sourced") and not self.dep(ESS_COMPONENTS, "postgresql-document-db"):
                missing("s2-document-db", "postgresql-document-db",
                        "no starter brings the PostgreSQL document store; declare it when read models use it (S5), "
                        "which every generated view slice does")
        if lang == "java":
            d = self.dep(ESS_COMPONENTS, "kotlin-eventsourcing")
            if d:
                self.add("s2-kotlin-eventsourcing-on-java", rel, d.line,
                         "kotlin-eventsourcing on a Java project: its Kotlin DSL is not the Java decider family, "
                         "which lives in eventsourced-aggregates (EventStreamDecider / EventStreamEvolver)",
                         "remove kotlin-eventsourcing; use eventsourced-aggregates",
                         [{"op": "remove-dependency", "pom": rel, "line": d.line, "groupId": d.g,
                           "artifactId": d.a}])
        # The spring.data.mongodb.* keys Boot 4 no longer binds (deprecated at level error) — any profile.
        for f in self.config_files():
            frel = self.p.rel(f)
            for key, _, line, own in config_keys(f):
                to = MONGO_MOVED.get(norm_key(key))
                if to is None:
                    continue
                self.add("s2-mongo-keys", frel, line,
                         f"`{key}` is not bound by Spring Boot 4 — "
                         + ("the client silently falls back to mongodb://localhost/test"
                            if to.startswith("spring.mongodb.") else "the setting is silently ignored"),
                         f"rename to `{to}`",
                         [{"op": "rename-config-key", "file": frel, "line": line, "from": key, "to": to}],
                         mechanical=f.suffix == ".properties" or own == key)
        for s in self.main + self.tests:
            for line, m in s.find(r"([\"'])(spring\.data\.mongodb\.[\w.-]+)\1"):
                to = MONGO_MOVED.get(norm_key(m.group(2)))
                if to is None:
                    continue
                self.add("s2-mongo-keys", s.rel, line,
                         f"property name \"{m.group(2)}\" is not bound by Spring Boot 4",
                         f"rename to {to}",
                         [{"op": "replace-text", "file": s.rel, "line": line, "from": m.group(0),
                           "to": m.group(1) + to + m.group(1)}])

    def s2_1(self):
        db, web = self.facts["db"], self.facts["web"]
        rel = self.app.rel
        if db is None:
            self.skip([c for c in RULES if c.startswith("s2.1-")], "persistence profile unknown (no single starter)")
            return
        rows = []  # (check, [(g, a) alternatives], shown artifact)
        if db in ("pg-crud", "pg-event-sourced"):
            rows += [
                ("s2.1-jdbc-starter", [(BOOT, "spring-boot-starter-jdbc")]),
                ("s2.1-postgresql-driver", [("org.postgresql", "postgresql")]),
                ("s2.1-jdbi", [("org.jdbi", "jdbi3-core")]),
                ("s2.1-jdbi", [("org.jdbi", "jdbi3-postgres")]),
                ("s2.1-kotlin-stdlib", [("org.jetbrains.kotlin", "kotlin-stdlib-jdk8"),
                                        ("org.jetbrains.kotlin", "kotlin-stdlib")]),
                ("s2.1-kotlin-reflect", [("org.jetbrains.kotlin", "kotlin-reflect")]),
            ]
        else:
            rows.append(("s2.1-data-mongodb", [(BOOT, "spring-boot-starter-data-mongodb")]))
        web_or_jackson = any(self.dep(BOOT, a) for a in (
            "spring-boot-starter-webflux", "spring-boot-starter-webmvc", "spring-boot-starter-web",
            "spring-boot-starter-jackson"))
        if not web_or_jackson:
            rows.append(("s2.1-jackson3-databind", [("tools.jackson.core", "jackson-databind")]))
        if web != "webflux":
            rows.append(("s2.1-reactor-core", [("io.projectreactor", "reactor-core")]))
        anchor = next((d.line for d in self.app_deps
                       if d.g == ESS_COMPONENTS and d.a in STARTERS), None)
        for check, alts in rows:
            found = [d for g, a in alts for d in [self.dep(g, a)] if d]
            g, a = alts[0]
            if not found:
                self.add(check, rel, anchor,
                         f"{g}:{a} is not declared — the starter declares it `provided`, so nothing brings it; "
                         "compiles, then fails at context startup",
                         f"add {g}:{a} (compile scope) to {rel}",
                         [{"op": "add-dependency", "pom": rel, "groupId": g, "artifactId": a, "scope": None}])
                continue
            d = found[0]
            if d.scope in ("provided", "test", "system"):
                self.add("s2.1-scope", rel, d.line,
                         f"{d.a} is `{d.scope}` scope — not on the application's runtime classpath",
                         "remove the <scope> element (compile scope)",
                         [{"op": "set-dependency-scope", "pom": rel, "line": d.line, "groupId": d.g,
                           "artifactId": d.a, "from": d.scope, "to": None}])
            elif d.scope == "runtime":
                self.add("s2.1-runtime-scope", rel, d.line,
                         f"{d.a} is `runtime` scope; the contract declares the S2.1 set at compile scope",
                         "remove the <scope> element (compile scope)",
                         [{"op": "set-dependency-scope", "pom": rel, "line": d.line, "groupId": d.g,
                           "artifactId": d.a, "from": "runtime", "to": None}])

    # -- S3 --------------------------------------------------------------------------------------

    def s3(self):
        for d in self.p.all_deps():
            if d.g == ESS_GROUP and d.a in ("types-jackson", "immutable-jackson"):
                self.add("s3.1-jackson2-essentials-module", d.pom.rel, d.line,
                         f"{d.a} is the Jackson 2 module; it publishes EssentialTypesJacksonModule under the same "
                         "class name and makes every persistence serializer throw at startup",
                         f"remove {d.a} (the starters bring the Jackson 3 module)",
                         [{"op": "remove-dependency", "pom": d.pom.rel, "line": d.line, "groupId": d.g,
                           "artifactId": d.a}])
            if d.g == "com.fasterxml.jackson.core" and d.a == "jackson-databind":
                self.add("s3.1-jackson2-databind", d.pom.rel, d.line,
                         "Jackson 2 jackson-databind declared; Essentials serializes with Jackson 3 only",
                         "remove it unless a non-Essentials library needs it",
                         [{"op": "remove-dependency", "pom": d.pom.rel, "line": d.line, "groupId": d.g,
                           "artifactId": d.a}], mechanical=False)
        # S3.3: an own JsonMapper bean without EssentialTypesJacksonModule
        for s in self.main:
            pats = [r"\bfun\s+\w+\s*\([^)]*\)\s*:\s*(?:tools\.jackson\.databind\.json\.)?JsonMapper\b",
                    r"^[ \t]*(?:public\s+|protected\s+|private\s+)?(?:static\s+)?"
                    r"(?:tools\.jackson\.databind\.json\.)?JsonMapper\s+\w+\s*\("]
            for pat in pats:
                for line, _ in s.find(pat, re.MULTILINE):
                    if s.annotated(line, "@Bean") and "EssentialTypesJacksonModule" not in s.text:
                        self.add("s3.3-own-json-mapper", s.rel, line,
                                 "this JsonMapper bean replaces Boot's web mapper and does not register "
                                 "EssentialTypesJacksonModule — typed values go over the wire wrong, silently",
                                 "register EssentialTypesJacksonModule on this mapper, or drop the bean",
                                 mechanical=False)
        if self.facts["language"] != "kotlin":
            return
        if not self.dep("tools.jackson.module", "jackson-module-kotlin"):
            self.add("s3.4-jackson-module-kotlin", self.app.rel, None,
                     "tools.jackson.module:jackson-module-kotlin is not declared — Kotlin value classes "
                     "serialize as {\"value\":…} on both mappers",
                     f"add tools.jackson.module:jackson-module-kotlin to {self.app.rel}",
                     [{"op": "add-dependency", "pom": self.app.rel, "groupId": "tools.jackson.module",
                       "artifactId": "jackson-module-kotlin", "scope": None}])
        db = self.facts["db"]
        if db is None:
            self.skip(["s3.4-persistence-serializer"], "persistence profile unknown")
            return
        want = "JSONEventSerializer" if db == "pg-event-sourced" else "JSONSerializer"
        ok, wrong, module_bean = None, None, None
        for s in self.main:
            # `: JSONEventSerializer` declared, or `= Jackson3JSONEventSerializer(…)` inferred
            for line, m in s.find(r"\bfun\s+\w+\s*\([^)]*\)\s*(?::\s*|=\s*)"
                                  r"(?:Jackson3)?(JSONEventSerializer|JSONSerializer)\b"):
                if not s.annotated(line, "@Bean"):
                    continue
                good_file = "KotlinModule" in s.text and "EssentialsObjectMappers" in s.text
                if m.group(1) == want and good_file:
                    ok = (s.rel, line)
                elif wrong is None:
                    wrong = (s.rel, line, m.group(1), good_file)
            for line, _ in s.find(r"\bfun\s+\w+\s*\([^)]*\)\s*:\s*(?:KotlinModule|JacksonModule)\b"):
                if s.annotated(line, "@Bean") and module_bean is None:
                    module_bean = (s.rel, line)
        if ok:
            return
        if wrong:
            rel, line, typ, good_file = wrong
            why = (f"its type is {typ}; on {db} the starter only backs off from a {want} bean"
                   if typ != want else "it is not built on EssentialsObjectMappers with KotlinModule")
            self.add("s3.4-persistence-serializer", rel, line,
                     f"persistence serializer bean does not carry KotlinModule to the persisted format: {why}",
                     f"make it a {want} built on EssentialsObjectMappers.createJackson3ObjectMapper(KotlinModule…)",
                     mechanical=False)
        elif module_bean:
            self.add("s3.4-persistence-serializer", module_bean[0], module_bean[1],
                     "a KotlinModule bean reaches the web mapper only; the persistence serializer ignores module beans",
                     f"declare your own {want} bean built on "
                     "EssentialsObjectMappers.createJackson3ObjectMapper(KotlinModule…)",
                     mechanical=False)
        else:
            self.add("s3.4-persistence-serializer", self.src_main_dir(), None,
                     f"no {want} bean with KotlinModule — Kotlin value classes persist as {{\"value\":…}}",
                     f"declare your own {want} bean built on "
                     "EssentialsObjectMappers.createJackson3ObjectMapper(KotlinModule…)",
                     mechanical=False)

    def s3_5(self):
        pom = self.app
        chain = self.p.chain(pom)
        boot_parent = (self.p.external_parent(pom) or (None, None))[:2] == (BOOT, "spring-boot-starter-parent")

        def plugins(artifact):
            return [(p, pl) for p in chain for pl in p.plugins + p.managed_plugins
                    if pl.val("artifactId") == artifact]

        # javac: -parameters, or <parameters>true</parameters> (the Boot parent sets it)
        mcp = plugins("maven-compiler-plugin")
        explicit = None
        for p, pl in mcp:
            for c in pl.iter():
                if c.tag == "parameters":
                    explicit = explicit or (c.text.strip().lower(), p, c.line)
                if c.tag == "arg" and c.text.strip() == "-parameters" or \
                        c.tag == "compilerArgument" and "-parameters" in c.text:
                    explicit = explicit or ("true", p, c.line)
        if explicit and explicit[0] == "false":
            self.add("s3.5-java-parameters", explicit[1].rel, explicit[2],
                     "<parameters>false</parameters> erases constructor parameter names — every "
                     "properties-based creator then fails to bind on the way in",
                     "set <parameters>true</parameters>",
                     [{"op": "replace-text", "file": explicit[1].rel, "line": explicit[2],
                       "from": "<parameters>false</parameters>", "to": "<parameters>true</parameters>"}])
        elif not explicit and not boot_parent:
            self.add("s3.5-java-parameters", pom.rel, None,
                     "maven-compiler-plugin does not retain parameter names (no -parameters, and no "
                     "spring-boot-starter-parent to set it)",
                     "add -parameters to maven-compiler-plugin's compilerArgs",
                     [{"op": "add-compiler-arg", "pom": pom.rel, "plugin": "maven-compiler-plugin",
                       "arg": "-parameters"}])
        if self.facts["language"] != "kotlin":
            return
        kmp = plugins("kotlin-maven-plugin")
        if not kmp:
            self.skip(["s3.5-kotlin-allopen", "s3.5-kotlin-jsr305", "s3.5-kotlin-param-property"],
                      "no kotlin-maven-plugin in the application module's POM chain")
            return
        kpom, kpl = next(((p, pl) for p, pl in kmp if pl in p.plugins), kmp[0])
        compiler_plugins = {c.text.strip() for _, pl in kmp for c in pl.all("configuration/compilerPlugins/plugin")}
        args = {c.text.strip() for _, pl in kmp for c in pl.all("configuration/args/arg")}
        if not compiler_plugins & {"spring", "all-open"}:
            self.add("s3.5-kotlin-allopen", kpom.rel, kpl.line,
                     "kotlin-maven-plugin has no `spring` compiler plugin — @Configuration classes are final "
                     "and cannot be proxied",
                     "add <compilerPlugins><plugin>spring</plugin></compilerPlugins> and the kotlin-maven-allopen "
                     "plugin dependency",
                     [{"op": "add-kotlin-compiler-plugin", "pom": kpom.rel, "name": "spring"}])
        for check, arg, why in (
                ("s3.5-kotlin-jsr305", "-Xjsr305=strict",
                 "Essentials' Java nullability annotations stay advisory and null leaks into non-null types"),
                ("s3.5-kotlin-param-property", "-Xannotation-default-target=param-property",
                 "annotations on constructor vals may miss the property, silently")):
            if arg not in args:
                self.add(check, kpom.rel, kpl.line, f"kotlin-maven-plugin lacks {arg} — {why}",
                         f"add <arg>{arg}</arg> to kotlin-maven-plugin's <args>",
                         [{"op": "add-compiler-arg", "pom": kpom.rel, "plugin": "kotlin-maven-plugin", "arg": arg}])

    # -- S4 --------------------------------------------------------------------------------------

    def s4(self):
        web = self.facts["web"]
        tsw = self.dep(ESS_GROUP, "types-spring-web")
        imports = []  # (source, line, 'Flux'|'Mvc')
        for s in self.main:
            for m in re.finditer(r"@Import\s*\(", s.text):
                depth, j = 1, m.end()
                while j < len(s.text) and depth:
                    depth += {"(": 1, ")": -1}.get(s.text[j], 0)
                    j += 1
                for k in re.finditer(r"\bEssentialsWeb(Flux|Mvc)Configurer\b", s.text[m.end():j]):
                    imports.append((s, s.line_of(m.end() + k.start()), k.group(1)))
            for line, k in s.find(r"\b(?:new\s+)?EssentialsWeb(Flux|Mvc)Configurer\s*\(\s*\)"):
                imports.append((s, line, k.group(1)))
        if web is None:
            self.skip(["s4-configurer-web-mismatch", "s4-types-spring-web-missing"],
                      "both spring-boot-starter-webflux and a WebMvc starter declared")
        if not imports:
            if tsw:
                self.add("s4-configurer-missing", self.app.rel, tsw.line,
                         "types-spring-web is declared but no EssentialsWebFluxConfigurer/EssentialsWebMvcConfigurer "
                         "is @Import-ed — the jar alone registers nothing; a typed path variable returns 500",
                         "add a @Configuration class with @Import(EssentialsWeb"
                         + ("Mvc" if web == "webmvc" else "Flux") + "Configurer)", mechanical=False)
            elif self.facts["language"] == "java" and web in ("webflux", "webmvc"):
                self.add("s4-types-spring-web-missing", self.app.rel, None,
                         "a Java web application without types-spring-web cannot bind a CharSequenceType id "
                         "from a path variable or request parameter",
                         "add dk.trustworks.essentials:types-spring-web and @Import the configurer for the web stack",
                         [{"op": "add-dependency", "pom": self.app.rel, "groupId": ESS_GROUP,
                           "artifactId": "types-spring-web", "scope": None, "version": "${essentials.version}"}],
                         mechanical=False)
            return
        if len(imports) > 1:
            for s, line, _ in imports[1:]:
                self.add("s4-configurer-count", s.rel, line,
                         f"{len(imports)} Essentials web configurer registrations; exactly one belongs in an application",
                         "keep the one matching the web stack, remove the others", mechanical=False)
        want = {"webflux": "Flux", "webmvc": "Mvc"}.get(web or "")
        if want:
            for s, line, kind in imports:
                if kind != want:
                    frm, to = f"EssentialsWeb{kind}Configurer", f"EssentialsWeb{want}Configurer"
                    self.add("s4-configurer-web-mismatch", s.rel, line,
                             f"{frm} imported on a {web} application — the class cannot load without its web stack",
                             f"use {to} (the import line too)",
                             [{"op": "replace-text", "file": s.rel, "line": line, "from": frm, "to": to}],
                             mechanical=False)

    # -- S5 --------------------------------------------------------------------------------------

    def s5(self):
        db = self.facts["db"]
        ddb = self.dep(ESS_COMPONENTS, "postgresql-document-db")
        if ddb and db == "mongo":
            self.add("s5-mongo-document-db", self.app.rel, ddb.line,
                     "postgresql-document-db is PostgreSQL-only; on the mongo profile it has nothing to run on",
                     "remove postgresql-document-db",
                     [{"op": "remove-dependency", "pom": self.app.rel, "line": ddb.line, "groupId": ddb.g,
                       "artifactId": ddb.a}])
        elif ddb and db in ("pg-crud", "pg-event-sourced"):
            if not any(s.has(r"\bDocumentDbRepositoryFactory\s*\(") for s in self.main):
                self.add("s5-document-db-factory", self.app.rel, ddb.line,
                         "postgresql-document-db is declared but nothing builds a DocumentDbRepositoryFactory — "
                         "it is not auto-configured",
                         "add a @Bean DocumentDbRepositoryFactory(jdbi, unitOfWorkFactory, jsonSerializer)",
                         mechanical=False)
        for f in self.config_files():
            frel = self.p.rel(f)
            for key, _, line, _ in config_keys(f):
                if norm_key(key) == "essentials.durablequeues.transactionalmode":
                    self.add("s5-transactional-mode", frel, line,
                             f"`{key}` binds to nothing — the mode was removed and every queue operation runs "
                             "in its own transaction",
                             "delete the key",
                             [{"op": "delete-config-key", "file": frel, "line": line, "key": key}])
        declared = " ".join(s.text for s in self.main if "EssentialsAggregateDeclarations" in s.text)
        for s in self.main:
            for line, m in s.find(r"@(AggregateSnapshotPolicy|AggregateClosingBooksPolicy)\b"):
                cls = re.search(r"\bclass\s+(\w+)", s.text[m.end():])
                name = cls.group(1) if cls else None
                if name and re.search(rf"\b{re.escape(name)}\b", declared):
                    continue
                self.add("s5-aggregate-declarations", s.rel, line,
                         f"@{m.group(1)} on {name or 'this class'} reaches no registry: no "
                         "EssentialsAggregateDeclarations bean declares it, and nothing reports that",
                         f"declare {name or 'the aggregate'} in an EssentialsAggregateDeclarations bean",
                         mechanical=False)

    # -- S7 --------------------------------------------------------------------------------------

    def s7(self):
        # The sanctioned generator is a test that fetches /v3/api-docs from its running context (which has its
        # database) and writes contracts/openapi.json. A springdoc-openapi-maven-plugin fetch from a forked app
        # stays green on the previous spec once an endpoint needs the database, so it does not count.
        if self.facts["web"] == "none":
            self.skip(["s7-spec-generation"], "no web stack")
        else:
            chain = self.p.chain(self.app)
            maven_fetch = any(p.plugin("springdoc-openapi-maven-plugin") for p in chain)
            test_writer = any("/v3/api-docs" in s.text and "openapi.json" in s.text
                              and re.search(r"(IT|IntegrationTest)\.(java|kt)$", s.path.name) for s in self.tests)
            if not test_writer:
                why = ("contracts/openapi.json is fetched by springdoc-openapi-maven-plugin from a forked app, which "
                       "cannot serve /v3/api-docs once an endpoint's beans need the database and leaves the previous "
                       "spec behind a green build") if maven_fetch else \
                      ("nothing regenerates contracts/openapi.json at integration-test — no *IT writes it from "
                       "/v3/api-docs")
                self.add("s7-spec-generation", self.app.rel, None, why,
                         "add an integration test that fetches /v3/api-docs from the test context and writes "
                         "contracts/openapi.json (the OpenApiContractIT /essentials:init generates)"
                         + ("; then remove the springdoc-openapi-maven-plugin and start/stop executions"
                            if maven_fetch else ""), mechanical=False)
        springdoc = [d for d in self.app_deps
                     if d.g == "org.springdoc" and d.a.startswith("springdoc-openapi-starter-")]
        if springdoc and not any(s.has(r"\bSingleValueTypeModelConverter\s*\(") for s in self.main):
            self.add("s7-model-converter", self.app.rel, springdoc[0].line,
                     "springdoc publishes every Essentials semantic type as the Java object it is (a CharSequenceType "
                     "id as {bytes, empty, value}; a Kotlin value-class property under its mangled name), so every "
                     "client generated from contracts/openapi.json types the ids wrongly",
                     "register types-spring-web's SingleValueTypeModelConverter as a @Bean (e.g. in "
                     "config/EssentialsWebConfig)", mechanical=False)
        configs = [cfg for d in self.frontend_dirs for cfg in sorted(d.glob("orval.config.*"))]
        for cfg in configs:
            text = strip_comments(cfg.read_text(encoding="utf-8", errors="replace"))
            m = re.search(r"input\s*:\s*\{[^}]*?target\s*:\s*['\"]([^'\"]+)['\"]", text, re.S) or \
                re.search(r"input\s*:\s*['\"]([^'\"]+)['\"]", text)
            if not m:
                continue
            line = text.count("\n", 0, m.start(1)) + 1
            if re.match(r"https?://", m.group(1)):
                self.add("s7-frontend-input", self.p.rel(cfg), line,
                         f"orval reads {m.group(1)} — the client is built from whatever backend is running, not "
                         "from the committed spec",
                         "point input.target at the committed ../contracts/openapi.json",
                         [{"op": "replace-text", "file": self.p.rel(cfg), "line": line, "from": m.group(1),
                           "to": "../contracts/openapi.json"}], mechanical=False)
            elif not (cfg.parent / m.group(1)).exists():
                self.add("s7-contract-file", self.p.rel(cfg), line,
                         f"orval reads {m.group(1)}, which does not exist — the frontend build has no contract",
                         "run the backend's verify once and commit the generated contracts/openapi.json",
                         mechanical=False)
        if not configs and self.facts["web"] not in ("none", None):
            if not any(f.name == "openapi.json" and f.parent.name == "contracts" for f in self.p.files):
                self.add("s7-contract-file", "contracts/openapi.json", None,
                         "no committed contracts/openapi.json — other components have no contract to consume",
                         "run verify once and commit the generated contracts/openapi.json", mechanical=False)

    # -- S8 / S11 --------------------------------------------------------------------------------

    def s8(self):
        mode = self.facts["frontend"]
        if mode == "none":
            self.skip([c for c in RULES if c.startswith("s8-")] + ["s11-skip-frontend"], "no frontend")
            return
        cors = []
        for s in self.main:
            for line, m in s.find(r"\b(CorsConfigurationSource|CorsWebFilter|CorsFilter|CorsRegistry|"
                                  r"addCorsMappings|CrossOrigin|allowedOrigins|setAllowedOrigins)\b"):
                if not s.lines[line - 1].lstrip().startswith("import "):
                    cors.append((s, line, m.group(1)))
        spa = [(s, line) for s in self.main for line, _ in s.find(r"[\"'][^\"'\n]*index\.html[\"']")]
        fe_dirs = self.frontend_dirs
        if mode == "embedded":
            reported = set()
            for s, line, token in cors:
                if s.rel not in reported:
                    reported.add(s.rel)
                    self.add("s8-embedded-cors", s.rel, line,
                             f"CORS configuration ({token}) in an embedded build — same origin, so it never "
                             "fires and reads as intent", "remove the CORS configuration", mechanical=False)
            for d in fe_dirs:
                for env in sorted(d.glob(".env*")):
                    for no, raw in enumerate(env.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                        m = re.match(r"^\s*(?:export\s+)?VITE_API_BASE_URL\s*=\s*(.*)$", raw)
                        if m and m.group(1).strip().strip("'\""):
                            self.add("s8-embedded-base-url", self.p.rel(env), no,
                                     "VITE_API_BASE_URL is set in an embedded build; relative /api paths are "
                                     "correct by construction", "delete the line",
                                     [{"op": "replace-text", "file": self.p.rel(env), "line": no,
                                       "from": raw, "to": ""}], mechanical=False)
            if not spa:
                self.add("s8-embedded-spa-filter", self.src_main_dir(), None,
                         "no SPA fallback forwarding to /index.html — deep links 404 in the packaged JAR",
                         "add the SPA fallback filter for the web stack (frontend-react.md Mode A)", mechanical=False)
            copies = [p for p in self.p.poms for pl in p.plugins if pl.val("artifactId") == "maven-resources-plugin"
                      for o in pl.all("executions/execution/configuration/outputDirectory") if "static" in o.text]
            if not copies:
                self.add("s8-embedded-static-copy", self.app.rel, None,
                         "frontend/dist is not copied into the JAR's static resources — the JAR ships without the SPA",
                         "add the maven-resources-plugin copy-resources execution into "
                         "${project.build.outputDirectory}/static", mechanical=False)
            fmp_poms = [p for p in self.p.poms if p.plugin("frontend-maven-plugin", include_profiles=False)]
            for p in fmp_poms:
                if "skip-frontend" not in p.profiles and not any(
                        "skip-frontend" in q.profiles for q in self.p.chain(p)):
                    self.add("s11-skip-frontend", p.rel, p.plugin("frontend-maven-plugin").line,
                             "frontend-maven-plugin with no skip-frontend profile — every backend build runs "
                             "npm ci and the Vite build", "add a skip-frontend profile that skips the frontend "
                             "executions", mechanical=False)
        else:
            self._standalone(cors, spa)
        self._gitignore(fe_dirs)

    def _standalone(self, cors, spa):
        sources = {s for s, _, t in cors if t == "CorsConfigurationSource"}
        if not sources:
            self.add("s8-standalone-cors-source", self.src_main_dir(), None,
                     "standalone frontend and no CorsConfigurationSource bean — cross-origin calls are refused",
                     "publish a CorsConfigurationSource bean with the frontend's real origins, and http.cors {}",
                     mechanical=False)
        for s in self.main:
            for line, m in s.find(r"\b(?:new\s+)?(CorsWebFilter|CorsFilter)\s*\("):
                self.add("s8-standalone-cors-webfilter", s.rel, line,
                         f"{m.group(1)} bean: Spring Security's chain decides about the preflight before it runs",
                         "publish the CorsConfigurationSource instead and enable http.cors {}", mechanical=False)
            for line, m in s.find(r"@(?:field:)?Value\s*\(\s*\"\\?\$\{([^}\"]*)\}"):
                if re.search(r"cors|origin", m.group(1), re.I):
                    self.add("s8-standalone-cors-value", s.rel, line,
                             f"@Value(\"${{{m.group(1)}}}\") cannot bind a YAML list — startup fails to resolve it",
                             "bind the origins with a @ConfigurationProperties class", mechanical=False)
            if s.has(r"allowCredentials\s*=\s*true|setAllowCredentials\s*\(\s*true\s*\)|allowCredentials\s*\(\s*true"):
                for line, _ in s.find(r"(?:allowedOrigins\s*=\s*\w*\(?\s*|[Aa]llowedOrigins?\s*\(\s*"
                                      r"(?:\w+\.of\s*\(\s*|listOf\s*\(\s*)?)\"\*\""):
                    self.add("s8-standalone-cors-wildcard", s.rel, line,
                             "wildcard origin with allowCredentials = true — browsers reject the pair",
                             "name the real origins", mechanical=False)
        for s, line in spa[:1]:
            self.add("s8-standalone-dead-spa", s.rel, line,
                     "backend SPA fallback in a standalone build — the static host serves the SPA; this is dead code",
                     "remove the SPA filter; configure the rewrite on the static host", mechanical=False)
        consumed = False
        mutators = []
        for d in self.frontend_dirs:
            mutators += [f for f in self.p.files if f.name == "custom-fetch.ts" and d in f.parents]
        for f in mutators:
            consumed = consumed or base_url_consumed(f.read_text(encoding="utf-8", errors="replace"))
        if not consumed:
            where = self.p.rel(mutators[0]) if mutators else (
                self.p.rel(self.frontend_dirs[0]) if self.frontend_dirs else None)
            self.add("s8-standalone-base-url", where, None,
                     "no fetch mutator prefixes import.meta.env.VITE_API_BASE_URL — the generated client's relative "
                     "paths resolve against the static host and 404",
                     "in custom-fetch.ts: const BASE = import.meta.env.VITE_API_BASE_URL ?? ''; fetch(`${BASE}${url}`)",
                     mechanical=False)

    def _gitignore(self, fe_dirs):
        for d in fe_dirs:
            if not list(d.glob("orval.config.*")):
                continue
            patterns = []
            for g in [d] + [p for p in d.parents if self.root in p.parents or p == self.root]:
                gi = g / ".gitignore"
                if gi.is_file():
                    patterns += [l.strip() for l in gi.read_text(encoding="utf-8", errors="replace").splitlines()]
            for sub in ("generated", "model"):
                # `src/shared/api/<sub>/` (any prefix), or a bare `<sub>/` that ignores every such directory
                if not any(re.search(rf"(^|/)src/shared/api/{sub}/?$", p) or p.strip("/") == sub
                           for p in patterns if p and not p.startswith("#")):
                    self.add("s8-generated-gitignore", self.p.rel(d / ".gitignore"), None,
                             f"src/shared/api/{sub}/ is not git-ignored — generated client code gets committed",
                             f"add src/shared/api/{sub}/ to {self.p.rel(d / '.gitignore')}",
                             [{"op": "append-line", "file": self.p.rel(d / ".gitignore"),
                               "text": f"src/shared/api/{sub}/"}])

    # -- S9 / S10 --------------------------------------------------------------------------------

    def s9(self):
        admin = [d for d in self.app_deps if d.g == ESS_COMPONENTS and
                 d.a in ("spring-boot-starter-admin-ui", "spring-boot-starter-admin-api")]
        if not admin:
            return
        for spi in ("EssentialsSecurityProvider", "EssentialsAuthenticatedUser"):
            if not any(s.has(rf"\b{spi}\b") for s in self.main):
                self.add("s9-admin-spi", self.app.rel, admin[0].line,
                         f"{admin[0].a} is deny-all until the application supplies {spi}; nothing does",
                         f"provide a {spi} bean (dk.trustworks.essentials.shared.security)", mechanical=False)

    def s10(self):
        for d in self.p.all_deps():
            if d.g == "org.testcontainers" and not d.a.startswith("testcontainers"):
                self.add("s10-tc1-coordinates", d.pom.rel, d.line,
                         f"org.testcontainers:{d.a} is a 1.x coordinate",
                         f"rename to testcontainers-{d.a}",
                         [{"op": "set-artifact-id", "pom": d.pom.rel, "line": d.line, "groupId": d.g,
                           "from": d.a, "to": f"testcontainers-{d.a}"}])
        moved = {
            "org.testcontainers.containers.PostgreSQLContainer": "org.testcontainers.postgresql.PostgreSQLContainer",
            "org.testcontainers.containers.MongoDBContainer": "org.testcontainers.mongodb.MongoDBContainer",
            "org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient":
                "org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient",
        }
        for s in self.tests + self.main:
            for old, new in moved.items():
                for line, _ in s.find(rf"^[ \t]*import\s+{re.escape(old)}\b", re.MULTILINE):
                    check = "s10-webtestclient-package" if "WebTestClient" in old else "s10-tc-package"
                    note = ("; the new class is not generic — drop <?> / <*>" if "PostgreSQL" in old else "")
                    self.add(check, s.rel, line, f"{old} is the old package{note}",
                             f"import {new}",
                             [{"op": "replace-text", "file": s.rel, "line": line, "from": old, "to": new}],
                             mechanical="PostgreSQL" not in old)
        its = [s for s in self.tests if re.search(r"(IT|IntegrationTest)\.(java|kt)$", s.path.name)]
        if its:
            bound = any(pl.val("artifactId") == "maven-failsafe-plugin" for p in self.p.poms for pl in p.plugins)
            if not bound:
                self.add("s10-failsafe", self.app.rel, None,
                         f"{len(its)} integration test class(es) (*IT / *IntegrationTest) and no "
                         "maven-failsafe-plugin in <build><plugins> — they never run",
                         "declare maven-failsafe-plugin with the integration-test and verify goals",
                         mechanical=False)

    def run(self):
        self.s1()
        self.s2()
        self.s2_1()
        self.s3()
        self.s3_5()
        self.s4()
        self.s5()
        self.s7()
        self.s8()
        self.s9()
        self.s10()
        self.facts["appPom"] = self.app.rel
        v, _, _ = self.p.prop(self.app, "java.version")
        self.facts["javaVersion"] = v
        # One finding per (check, file, line).
        seen, unique = set(), []
        for f in sorted(self.findings, key=Finding.sort_key):
            key = (f.rule.check, f.file, f.line, f.message)
            if key not in seen:
                seen.add(key)
                unique.append(f)
        self.findings = unique
        return self.findings


# ---------------------------------------------------------------------------------------------
# Output


def report_text(lint, anchors, quiet, out):
    f = lint.facts
    if not quiet:
        src = lint.fact_source
        print(f"stack-lint {lint.root}", file=out)
        print("  facts: " + " · ".join(
            f"{k}={f.get(k) or 'unknown'}{'' if src.get(k) != 'flag' else ' (flag)'}"
            for k in ("language", "db", "web", "frontend")) + f" · app={f.get('appPom')}", file=out)
        for nr in lint.not_run:
            print(f"  not run: {', '.join(nr['checks'])} — {nr['reason']}", file=out)
    for x in lint.findings:
        loc = f"{x.file}:{x.line}" if x.line else (x.file or "(project)")
        print(f"[{x.rule.severity}] {x.rule.id} {x.rule.check}  {loc}", file=out)
        print(f"    {x.message}", file=out)
        print(f"    fix: {x.fix_text}", file=out)
        print(f"    → {', '.join(x.rule.cite_strings())}", file=out)
    if not quiet:
        counts = {s: sum(1 for x in lint.findings if x.rule.severity == s) for s in SEVERITY_ORDER}
        print(f"{len(lint.findings)} finding(s): " + ", ".join(f"{v} {k}" for k, v in counts.items()), file=out)


def report_json(lint, anchors):
    counts = {s: sum(1 for x in lint.findings if x.rule.severity == s) for s in SEVERITY_ORDER}
    facts = dict(lint.facts)
    facts["sources"] = lint.fact_source
    return {
        "tool": "stack-lint",
        "root": str(lint.root),
        "facts": facts,
        "notRun": lint.not_run,
        "findings": [x.as_dict(anchors) for x in lint.findings],
        "counts": counts,
    }


def rules_json(anchors):
    return [{"id": r.id, "check": r.check, "severity": r.severity, "title": r.title,
             "cite": r.cite_strings(), "link": f"{CONTRACT_REL}#{anchors.get(r.sid, '')}"}
            for r in RULES_LIST]


def lint_exit(findings, fail_on):
    limit = FAIL_ON[fail_on]
    return 1 if any(SEVERITY_ORDER[f.rule.severity] <= limit for f in findings) else 0


# ---------------------------------------------------------------------------------------------
# Self-test


def self_test(pins_path, contract_path):
    problems = []
    try:
        pins = read_pins(pins_path)
        anchors, _ = read_contract_anchors(contract_path)
    except UsageError as exc:
        print(f"stack-lint --self-test: {exc}", file=sys.stderr)
        return 2
    # 1. Every rule's citations still point at the sentence they were written against.
    docs = {}
    for r in RULES_LIST:
        if not r.cites or r.cites[0][0] != "C":
            problems.append(f"{r.check}: first citation must be {CONTRACT_REL}")
        for d, n, token in r.cites:
            if d not in docs:
                try:
                    docs[d] = (PLUGIN / DOCS[d]).read_text(encoding="utf-8").splitlines()
                except OSError as exc:
                    problems.append(f"cannot read {DOCS[d]}: {exc}")
                    docs[d] = []
            lines = docs[d]
            if n > len(lines) or token not in lines[n - 1]:
                hint = next((i + 1 for i, l in enumerate(lines) if token in l), None)
                problems.append(f"{r.check}: {DOCS[d]}:{n} does not contain {token!r}"
                                + (f" (found at :{hint})" if hint else " (not found anywhere)"))
        if r.check in RETIRED_CHECKS:
            problems.append(f"{r.check}: a retired slug; give the check a new one")
        # 2. Every id resolves to a contract heading or bold lead.
        if r.sid not in anchors:
            problems.append(f"{r.check}: {r.id} resolves to no `S{r.sid[1:]} —` heading in {CONTRACT_REL}")
    # 3. Fixtures.
    base = PLUGIN / "tests" / "stack-lint"
    try:
        spec = json.loads((base / "expectations.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(f"stack-lint --self-test: cannot read expectations.json: {exc}", file=sys.stderr)
        return 2
    covered = set()
    for name, exp in sorted(spec["fixtures"].items()):
        root = base / name
        ov = exp.get("facts", {})
        try:
            lint = Lint(root, pins, ov)
            lint.run()
            code = lint_exit(lint.findings, "advisory")
        except UsageError as exc:
            lint, code = None, 2
            if exp.get("exit") != 2:
                problems.append(f"{name}: could not run: {exc}")
                continue
        if code != exp.get("exit", 0):
            problems.append(f"{name}: exit {code}, expected {exp.get('exit', 0)}")
        if lint is None:
            continue
        for k, v in exp.get("expectFacts", {}).items():
            if lint.facts.get(k) != v:
                problems.append(f"{name}: fact {k}={lint.facts.get(k)!r}, expected {v!r}")
        got = {(f.rule.check, f.file, f.line) for f in lint.findings}
        want = {(e["check"], e["file"], e.get("line")) for e in exp.get("findings", [])}
        for e in exp.get("findings", []):
            if e["check"] not in RULES:
                problems.append(f"{name}: unknown check {e['check']}")
            covered.add(e["check"])
        for miss in sorted(want - got, key=str):
            problems.append(f"{name}: expected {miss[0]} at {miss[1]}:{miss[2]} — not reported")
        for extra in sorted(got - want, key=str):
            problems.append(f"{name}: unexpected {extra[0]} at {extra[1]}:{extra[2]}")
        for f in lint.findings:
            if f.mechanical and not f.ops:
                problems.append(f"{name}: {f.rule.check} claims mechanical with no ops")
    # 4. Every rule has at least one fixture that fires it.
    for r in RULES_LIST:
        if r.check not in covered:
            problems.append(f"{r.check}: no violating fixture expects it")
    for p in problems:
        print(f"FAIL {p}")
    print(f"stack-lint self-test: {len(RULES_LIST)} rules, {len(spec['fixtures'])} fixtures, "
          f"{len(problems)} problem(s)")
    return 1 if problems else 0


# ---------------------------------------------------------------------------------------------


def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="stack-lint",
        description="Check a project against the decidable half of the stack contract (S1-S11). "
                    "Deterministic; reports, never writes.")
    ap.add_argument("root", nargs="?", default=".", help="project root (default: .)")
    ap.add_argument("--json", action="store_true", help="emit the report as JSON")
    ap.add_argument("--quiet", action="store_true", help="print findings only")
    ap.add_argument("--language", choices=["kotlin", "java"])
    ap.add_argument("--db", choices=["pg-event-sourced", "pg-crud", "mongo"])
    ap.add_argument("--web", choices=["webflux", "webmvc", "none"])
    ap.add_argument("--frontend", choices=["none", "embedded", "standalone"])
    ap.add_argument("--fail-on", choices=list(FAIL_ON), default="advisory")
    ap.add_argument("--pins", default=str(PLUGIN / PINS_REL))
    ap.add_argument("--contract", default=str(PLUGIN / CONTRACT_REL))
    ap.add_argument("--rules", action="store_true", help="print the rule table and exit")
    ap.add_argument("--self-test", action="store_true", help="run the fixture and citation checks")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test(Path(args.pins), Path(args.contract))
    try:
        anchors, _ = read_contract_anchors(Path(args.contract))
        if args.rules:
            if args.json:
                json.dump(rules_json(anchors), sys.stdout, indent=2)
                sys.stdout.write("\n")
            else:
                for r in RULES_LIST:
                    print(f"{r.id:<10} {r.check:<34} {r.severity:<10} {r.title}  → {', '.join(r.cite_strings())}")
            return 0
        pins = read_pins(Path(args.pins))
        root = Path(args.root).resolve()
        if not root.is_dir():
            raise UsageError(f"not a directory: {root}")
        overrides = {k: getattr(args, k) for k in ("language", "db", "web", "frontend") if getattr(args, k)}
        lint = Lint(root, pins, overrides)
        lint.run()
    except UsageError as exc:
        print(f"stack-lint: {exc}", file=sys.stderr)
        return 2
    if args.json:
        json.dump(report_json(lint, anchors), sys.stdout, indent=2)
        sys.stdout.write("\n")
    else:
        report_text(lint, anchors, args.quiet, sys.stdout)
    return lint_exit(lint.findings, args.fail_on)


if __name__ == "__main__":
    sys.exit(main())
