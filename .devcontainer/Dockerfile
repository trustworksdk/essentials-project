# =============================================================================
# Multi-Language DevContainer Dockerfile
# Supports: Java/Maven, Node.js/NPM, Python/UV
# Node.js and Python are installed via DevContainer features
# (This project: Java 17 + Maven + Kotlin LSP + Node.js 25 + Python 3.14;
#  local-first agent stack: graphify + headroom; no egress firewall.)
# =============================================================================

# -----------------------------------------------------------------------------
# Build Arguments - Project Identity
# Used for container naming, Docker labels, and distinguishing instances.
# -----------------------------------------------------------------------------
ARG PROJECT_NAME="devcontainer"

# -----------------------------------------------------------------------------
# Build Arguments - Installation Toggles (override in devcontainer.json)
# Safe defaults: everything off. devcontainer.json build.args enables what's needed.
# -----------------------------------------------------------------------------
ARG INSTALL_JAVA="false"
ARG INSTALL_KOTLIN_LSP="false"
ARG INSTALL_CLAUDE="false"
# Local-first agent stack (knowledge graph + context compression).
# graphify is the knowledge-graph indexer; headroom is an independent context-
# compression layer. Both install in post-create.sh (uv tools land in
# ~/.local/bin).
ARG INSTALL_GRAPHIFY="false"
ARG INSTALL_HEADROOM="false"
# rtk (Rust Token Killer) — transparent CLI-output compression. Activated as part
# of the local-first stack. Installs a binary at build time + a Claude Code
# PreToolUse hook in post-create.sh. Independent of headroom (headroom's own
# rtk integration is proxy-based; this is the lean, proxy-free path).
ARG INSTALL_RTK="false"
ARG INSTALL_JETBRAINS="false"
# Headless Chromium for the essentials plugin's render-check (the slice-map and Slice
# Atlas pages, tests/fixtures/slice-map/render-check.py) and any browser check. The
# system libraries are baked in here; post-create.sh downloads the browser binary.
ARG INSTALL_PLAYWRIGHT="false"
# Static analysers the tw-code-review plugin runs when they are on PATH:
# gitleaks (secret scan), spotbugs + FindSecBugs, detekt and PMD (JVM). Built
# or fetched here. markdownlint, semgrep and PyYAML need Node or Python, which
# arrive with the devcontainer features after this build, so post-create.sh
# installs those. PyYAML is the plugin's YAML fallback reader and makes yq, its
# other one, redundant, so yq is not installed. detekt, spotbugs and PMD run on
# the JVM and need INSTALL_JAVA=true.
ARG INSTALL_REVIEW_TOOLS="false"

# Git identity is passed via containerEnv in devcontainer.json (not build args)
# to avoid leaking PII into image layers. post-create.sh reads it at runtime.

# -----------------------------------------------------------------------------
# Build Arguments - Version Configuration
# -----------------------------------------------------------------------------
ARG JAVA_VERSION="17"
# Extra JDKs to install alongside JAVA_VERSION, space separated (e.g. "17 21").
# JAVA_VERSION remains the default: it is what /usr/lib/jvm/temurin-jdk and
# JAVA_HOME point at. The extras are installed so a branch that needs a different
# JDK can be built with an explicit JAVA_HOME=/usr/lib/jvm/temurin-<v>-jdk-<arch>.
ARG ADDITIONAL_JAVA_VERSIONS=""
# MAVEN_SHA512 must match MAVEN_VERSION — update both together.
# Get the SHA512 from: https://archive.apache.org/dist/maven/maven-3/<version>/binaries/
ARG MAVEN_VERSION="3.9.16"
ARG MAVEN_SHA512="831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6"
# JDT-LS (Eclipse Java LSP). SHA-256 must match the pinned tarball — update both
# together. The build-date suffix is part of the filename. Get the SHA from:
# https://download.eclipse.org/jdtls/milestones/<version>/<tarball>.sha256
ARG JDTLS_VERSION="1.61.0"
ARG JDTLS_BUILD_DATE="202609031315"
ARG JDTLS_SHA256="338e7e73d61836651ba2453919a0d34fa763eb4e7c03342092309bffb8934c64"
# kotlin-language-server (official JetBrains Kotlin LSP). Tarballs are hosted on
# JetBrains' CDN (the GitHub release page just links out). Each tarball ships a
# sibling .sha256 file; values below were captured at the pinned version.
# Refresh recipe:
#   curl -fsSL https://download.jetbrains.com/language-server/kotlin-server/<ver>/kotlin-server-<ver>.tar.gz.sha256
#   curl -fsSL https://download.jetbrains.com/language-server/kotlin-server/<ver>/kotlin-server-<ver>-aarch64.tar.gz.sha256
ARG KOTLIN_LS_VERSION="263.4702.0"
ARG KOTLIN_LS_SHA256_AMD64="1e11d2e5fefbf9ea215ad8dd6be95f2222897cd086e8cb7a661a52084a590405"
ARG KOTLIN_LS_SHA256_ARM64="ec7cb254a6662a07fff9f10e4365226afab6c40008f8a974c10ac5e785d6510f"
# rtk (Rust Token Killer). Per-arch release tarball from GitHub (amd64 → musl,
# arm64 → gnu). SHA-256 = GitHub's published per-asset digest for the pinned tag,
# verified at build. Refresh recipe (after bumping RTK_VERSION):
#   curl -fsSL -H "Accept: application/vnd.github+json" \
#     https://api.github.com/repos/rtk-ai/rtk/releases/tags/v<ver> \
#     | python3 -c "import sys,json;[print(a['name'],a.get('digest')) for a in json.load(sys.stdin)['assets']]"
# The `rtk-oss-*` assets, not `rtk-*`: since 1.0.0 the default `rtk` binary links
# rtk-engine, which is BUSL-1.1 (engine/LICENSE: production use only inside RTK's
# own binary, never offered to third parties as a paid or hosted product). The
# oss edition is Apache-2.0 only, installs the same `rtk` binary name, and keeps
# the hook core and every command filter, which is all this container uses; it
# lacks only the pipeline engine and the llm-proxy setup that `rtk init -g`
# would otherwise offer. Take the digests of the rtk-oss-* assets.
ARG RTK_VERSION="1.0.1"
ARG RTK_SHA256_AMD64="a44e8ffc43981100b4ddf1710399a1649a6c874591aa6d0c0727fe6cbd983c41"
# Playwright release whose chromium-headless-shell post-create.sh downloads. Run with
# npx, never added to the project: the Essentials build stays JVM-only.
ARG PLAYWRIGHT_VERSION="1.55.0"
ARG RTK_SHA256_ARM64="ee24a43dac9162de55fea8460b5efcd56d401ec28b5117c921bffa8a57bc136e"
# Review tools (INSTALL_REVIEW_TOOLS). Every pin below was checked against
# OSV and the project's GitHub security advisories before it was added; re-check
# both when bumping one.
#
# gitleaks is built from source, not taken as the release binary: the v8.30.1
# binary is built with Go 1.24.11 and stale golang.org/x modules, and govulncheck
# finds 69 reachable vulnerabilities in it. Built with the Go below and the
# dependency pins in GITLEAKS_DEP_PINS, govulncheck reports none reachable and
# gitleaks' own test suite passes. GITLEAKS_COMMIT is the commit the v<ver> tag
# points at; the build fails, naming both shas, if the tag has moved. Refresh
# recipe (the commits endpoint peels an annotated tag to its commit; the
# git/ref/tags endpoint would return the tag object's sha, which never matches):
#   curl -fsSL https://api.github.com/repos/gitleaks/gitleaks/commits/v<ver> | jq -r .sha
#   docker buildx imagetools inspect golang:<go-ver>-trixie   # index digest
#   in a checkout: go get <pins> && go mod tidy && go test ./... && govulncheck ./...
ARG GITLEAKS_VERSION="8.30.1"
ARG GITLEAKS_COMMIT="83d9cd684c87d95d656c1458ef04895a7f1cbd8e"
ARG GITLEAKS_GO_IMAGE="golang:1.27.2-trixie@sha256:47667934874913a449b96faa7ef4777fa285d9ec29126ee80ed61c45b7cccf0e"
ARG GITLEAKS_DEP_PINS="github.com/mholt/archives@v0.1.5 github.com/nwaples/rardecode/v2@v2.2.0 github.com/ulikunitz/xz@v0.5.15 github.com/klauspost/compress@v1.18.7 golang.org/x/crypto@v0.58.0 golang.org/x/text@v0.43.0 golang.org/x/sys@v0.49.0"
# spotbugs: release tarball from GitHub; SHA-256 = GitHub's published asset digest.
#   curl -fsSL https://api.github.com/repos/spotbugs/spotbugs/releases/tags/<ver> | jq -r '.assets[]|.name+" "+.digest'
ARG SPOTBUGS_VERSION="4.10.4"
ARG SPOTBUGS_SHA256="72bc0d4edd686e462c0f71f42a049b27bf4da6708797ff7b2b56dd202714b4e5"
# FindSecBugs plugin and detekt CLI: jars from Maven Central. The SHA-256 was
# computed from the downloaded jar after it matched Central's published .sha1.
# detekt's GitHub release jar is a different build from Central's; pin Central's.
#   https://repo1.maven.org/maven2/com/h3xstream/findsecbugs/findsecbugs-plugin/<ver>/
#   https://repo1.maven.org/maven2/io/gitlab/arturbosch/detekt/detekt-cli/<ver>/   (the -all.jar)
ARG FINDSECBUGS_VERSION="1.14.0"
ARG FINDSECBUGS_SHA256="6fa340344fa433ff46c2985dab1010e8bc739f9395c983594a5240095e92abc8"
ARG DETEKT_VERSION="1.23.8"
ARG DETEKT_SHA256="3afe89a11120303c73c9bdda3d8fe558dd9070a6937d27819ddc04b275381245"
# PMD: the binary distribution zip from GitHub; SHA-256 = GitHub's published
# asset digest. The tag name carries a slash, URL-encoded in the download path.
#   curl -fsSL https://api.github.com/repos/pmd/pmd/releases/tags/pmd_releases/<ver> | jq -r '.assets[]|.name+" "+.digest'
# The rules it applies: pmd-ruleset.xml at the repository root.
ARG PMD_VERSION="7.28.0"
ARG PMD_SHA256="f974ba571f7bc01c73fffe11bade25fbc1f438698f9e9c00e416e8d65e9fbac2"
# semgrep and PyYAML are Python packages; post-create.sh installs them at these
# versions once the Python feature is present (pins reach it through ENV below).
ARG SEMGREP_VERSION="1.181.0"
ARG PYYAML_VERSION="6.0.3"

# -----------------------------------------------------------------------------
# JetBrains Configuration
# -----------------------------------------------------------------------------
ARG JETBRAINS_BACKEND="IntelliJ"

# -----------------------------------------------------------------------------
# gitleaks build stage (used only when INSTALL_REVIEW_TOOLS=true)
# Leaves the binary in /out, or /out empty when the toggle is off, so the
# COPY --from in the main stage works either way. The Go image is pulled even
# when the toggle is off (a COPY --from cannot be conditional); only the build
# is skipped. Nothing from this stage but /out reaches the image.
# -----------------------------------------------------------------------------
FROM ${GITLEAKS_GO_IMAGE} AS gitleaks-build
ARG INSTALL_REVIEW_TOOLS
ARG GITLEAKS_VERSION
ARG GITLEAKS_COMMIT
ARG GITLEAKS_DEP_PINS
RUN mkdir -p /out && if [ "${INSTALL_REVIEW_TOOLS}" = "true" ]; then \
    set -eux && \
    export CGO_ENABLED=0 GOTOOLCHAIN=local GOFLAGS=-mod=mod && \
    git clone --quiet --depth 1 --branch "v${GITLEAKS_VERSION}" https://github.com/gitleaks/gitleaks.git /src && \
    cd /src && \
    actual_commit="$(git rev-parse HEAD)" && \
    if [ "${actual_commit}" != "${GITLEAKS_COMMIT}" ]; then \
        echo "gitleaks v${GITLEAKS_VERSION}: tag resolves to commit ${actual_commit}, GITLEAKS_COMMIT is ${GITLEAKS_COMMIT}; the tag moved or the pin is not a commit sha (see the refresh recipe)" >&2; \
        exit 1; \
    fi && \
    go get ${GITLEAKS_DEP_PINS} && \
    go mod tidy && \
    go build -trimpath -ldflags "-s -w -X github.com/zricethezav/gitleaks/v8/version.Version=${GITLEAKS_VERSION}" -o /out/gitleaks . && \
    /out/gitleaks version; \
    fi

# -----------------------------------------------------------------------------
# Base Image
# -----------------------------------------------------------------------------
FROM mcr.microsoft.com/devcontainers/base:debian AS base

# Re-declare ARGs after FROM (they don't persist across stages)
ARG PROJECT_NAME
ARG INSTALL_JAVA
ARG INSTALL_KOTLIN_LSP
ARG INSTALL_CLAUDE
ARG INSTALL_GRAPHIFY
ARG INSTALL_HEADROOM
ARG INSTALL_RTK
ARG INSTALL_JETBRAINS
ARG INSTALL_PLAYWRIGHT
ARG INSTALL_REVIEW_TOOLS
ARG PLAYWRIGHT_VERSION
ARG JAVA_VERSION
ARG ADDITIONAL_JAVA_VERSIONS
ARG MAVEN_VERSION
ARG MAVEN_SHA512
ARG JDTLS_VERSION
ARG JDTLS_BUILD_DATE
ARG JDTLS_SHA256
ARG KOTLIN_LS_VERSION
ARG KOTLIN_LS_SHA256_AMD64
ARG KOTLIN_LS_SHA256_ARM64
ARG RTK_VERSION
ARG RTK_SHA256_AMD64
ARG RTK_SHA256_ARM64
ARG SPOTBUGS_VERSION
ARG SPOTBUGS_SHA256
ARG FINDSECBUGS_VERSION
ARG FINDSECBUGS_SHA256
ARG DETEKT_VERSION
ARG DETEKT_SHA256
ARG PMD_VERSION
ARG PMD_SHA256
ARG SEMGREP_VERSION
ARG PYYAML_VERSION
ARG JETBRAINS_BACKEND

# Store build args as environment variables for runtime scripts.
# GIT_USER_NAME and GIT_USER_EMAIL are intentionally NOT stored as ENV —
# they're applied by post-create.sh via git config and don't need to persist
# in image layers (which would leak PII if the image is shared).
ENV PROJECT_NAME=${PROJECT_NAME} \
    INSTALL_JAVA=${INSTALL_JAVA} \
    INSTALL_KOTLIN_LSP=${INSTALL_KOTLIN_LSP} \
    INSTALL_CLAUDE=${INSTALL_CLAUDE} \
    INSTALL_GRAPHIFY=${INSTALL_GRAPHIFY} \
    INSTALL_HEADROOM=${INSTALL_HEADROOM} \
    INSTALL_RTK=${INSTALL_RTK} \
    INSTALL_JETBRAINS=${INSTALL_JETBRAINS} \
    INSTALL_PLAYWRIGHT=${INSTALL_PLAYWRIGHT} \
    INSTALL_REVIEW_TOOLS=${INSTALL_REVIEW_TOOLS} \
    PLAYWRIGHT_VERSION=${PLAYWRIGHT_VERSION} \
    SEMGREP_VERSION=${SEMGREP_VERSION} \
    PYYAML_VERSION=${PYYAML_VERSION} \
    JETBRAINS_BACKEND=${JETBRAINS_BACKEND}

# -----------------------------------------------------------------------------
# System Setup
# -----------------------------------------------------------------------------
USER root

# Set noninteractive mode to prevent apt prompts during build
ENV DEBIAN_FRONTEND=noninteractive

# Install base system dependencies and configure locales
RUN apt-get update && \
    apt-get -y install --no-install-recommends \
    ca-certificates \
    curl \
    gnupg \
    lsb-release \
    apt-transport-https \
    git \
    vim \
    jq \
    tree \
    # ripgrep — installs the `rg` binary. Fast recursive search used by agent
    # tooling and interactive shells; Debian trixie ships 14.x in main.
    ripgrep \
    htop \
    unzip \
    wget \
    locales \
    build-essential \
    # Sandbox dependencies (bubblewrap + socat for Claude Code native sandbox)
    # Always installed (~200KB) so /sandbox is available without rebuild
    bubblewrap \
    socat && \
    # Generate locales
    sed -i -e 's/# en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen && \
    locale-gen && \
    # macOS (and JetBrains Gateway, which forwards the client locale) injects a
    # bare LC_CTYPE=UTF-8 into remote terminals. "UTF-8" is not a valid locale
    # name on Linux, so every shell startup prints:
    #   bash: warning: setlocale: LC_CTYPE: cannot change locale (UTF-8)
    # Setting LANG/LC_ALL does NOT silence it — bash warns about the invalid
    # LC_CTYPE regardless. Defining a locale literally named "UTF-8" (mapped to
    # en_US/UTF-8) makes setlocale() resolve it, so the warning disappears no
    # matter what the IDE/host forwards.
    localedef -i en_US -f UTF-8 UTF-8 && \
    # Apply security updates
    apt-get upgrade -y && \
    apt-get clean -y && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* && \
    # Create docker group and add vscode user
    groupadd -f docker && usermod -aG docker vscode && \
    # Set default locale
    update-locale LANG=en_US.UTF-8

# Set locale environment variables
ENV LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

# -----------------------------------------------------------------------------
# Java + Maven Installation (Conditional)
# Uses Eclipse Temurin (Adoptium) for better cross-distro compatibility
# -----------------------------------------------------------------------------
RUN if [ "${INSTALL_JAVA}" = "true" ]; then \
    set -eux && \
    echo "Installing Java ${JAVA_VERSION}${ADDITIONAL_JAVA_VERSIONS:+ (plus ${ADDITIONAL_JAVA_VERSIONS})} and Maven ${MAVEN_VERSION}..." && \
    # Install Eclipse Temurin (Adoptium) - works on all Debian versions
    install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME=/{print $2}' /etc/os-release) main" \
    > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends temurin-${JAVA_VERSION}-jdk && \
    # Extra JDKs for branches that target a different release than JAVA_VERSION
    for v in ${ADDITIONAL_JAVA_VERSIONS}; do \
    apt-get install -y --no-install-recommends "temurin-${v}-jdk"; \
    done && \
    apt-get clean -y && rm -rf /var/lib/apt/lists/* && \
    # Install Maven with SHA512 verification
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -o maven.tar.gz && \
    echo "${MAVEN_SHA512}  maven.tar.gz" | sha512sum -c - && \
    tar -xzf maven.tar.gz -C /opt && \
    rm maven.tar.gz && \
    ln -sf /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn && \
    ln -sf /opt/apache-maven-${MAVEN_VERSION}/bin/mvnDebug /usr/local/bin/mvnDebug && \
    # Stable, version-independent Maven home so MAVEN_HOME survives version bumps
    # (mirrors the /usr/lib/jvm/temurin-jdk JAVA_HOME symlink). Exposed to every
    # process — including the JetBrains/Gateway backend — via MAVEN_HOME in
    # devcontainer.json containerEnv, which IntelliJ uses to auto-detect Maven.
    ln -sf /opt/apache-maven-${MAVEN_VERSION} /opt/maven && \
    echo "export MAVEN_HOME=/opt/maven" >> /etc/profile.d/maven.sh && \
    echo "export PATH=\$MAVEN_HOME/bin:\$PATH" >> /etc/profile.d/maven.sh && \
    chmod +x /etc/profile.d/maven.sh && \
    # Eclipse JDT-LS (Java LSP). Tarball ships a bin/jdtls Python launcher;
    # Python is provided at runtime by the DevContainer feature.
    curl -fsSL "https://download.eclipse.org/jdtls/milestones/${JDTLS_VERSION}/jdt-language-server-${JDTLS_VERSION}-${JDTLS_BUILD_DATE}.tar.gz" -o /tmp/jdtls.tar.gz && \
    echo "${JDTLS_SHA256}  /tmp/jdtls.tar.gz" | sha256sum -c - && \
    mkdir -p /opt/jdtls && \
    tar -xzf /tmp/jdtls.tar.gz -C /opt/jdtls && \
    rm /tmp/jdtls.tar.gz && \
    chmod +x /opt/jdtls/bin/jdtls && \
    ln -sf /opt/jdtls/bin/jdtls /usr/local/bin/jdtls; \
    fi

# Set Java environment variables (if installed)
# Note: Adoptium packages include architecture suffix (e.g., temurin-24-jdk-arm64)
# We create a symlink to provide a consistent JAVA_HOME path.
# ENV directives are inside the conditional so a non-Java build has a clean PATH.
# The symlink resolves JAVA_VERSION explicitly rather than globbing: with more than
# one JDK installed (see ADDITIONAL_JAVA_VERSIONS) a glob sorts lexically, so
# "temurin-17-jdk-arm64" would win over "temurin-25-jdk-arm64" and JAVA_HOME would
# silently point at the wrong JDK.
RUN if [ "${INSTALL_JAVA}" = "true" ]; then \
    ARCH_SPECIFIC_JDK="/usr/lib/jvm/temurin-${JAVA_VERSION}-jdk-$(dpkg --print-architecture)" && \
    if [ ! -d "$ARCH_SPECIFIC_JDK" ]; then \
    ARCH_SPECIFIC_JDK=$(ls -d /usr/lib/jvm/temurin-${JAVA_VERSION}-jdk* 2>/dev/null | head -1); \
    fi && \
    if [ -n "$ARCH_SPECIFIC_JDK" ] && [ -d "$ARCH_SPECIFIC_JDK" ]; then \
    ln -sf "$ARCH_SPECIFIC_JDK" /usr/lib/jvm/temurin-jdk && \
    echo "Created symlink: /usr/lib/jvm/temurin-jdk -> $ARCH_SPECIFIC_JDK"; \
    else \
    echo "ERROR: could not locate the JDK for JAVA_VERSION=${JAVA_VERSION}" >&2 && exit 1; \
    fi && \
    # Write Java env to profile.d so it loads in login shells
    echo 'export JAVA_HOME=/usr/lib/jvm/temurin-jdk' >> /etc/profile.d/java.sh && \
    echo "export MAVEN_HOME=/opt/maven" >> /etc/profile.d/java.sh && \
    echo 'export PATH="$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH"' >> /etc/profile.d/java.sh && \
    echo 'export MAVEN_OPTS="-XX:MaxRAMPercentage=20 -XX:+TieredCompilation -XX:TieredStopAtLevel=1"' >> /etc/profile.d/java.sh && \
    chmod +x /etc/profile.d/java.sh; \
    fi

# -----------------------------------------------------------------------------
# Kotlin Language Server (Conditional)
# Official JetBrains Kotlin LSP (https://github.com/Kotlin/kotlin-lsp), shipped
# as a bundled IntelliJ-platform server (~400 MB compressed, ~800 MB extracted).
# Runs on the JVM, so INSTALL_KOTLIN_LSP=true requires INSTALL_JAVA=true.
# Tarballs are per-arch: amd64 has no suffix, arm64 uses "-aarch64".
# The conventional binary name `kotlin-language-server` is symlinked to the
# real launcher `bin/intellij-server` so external LSP plugins find it on PATH.
# -----------------------------------------------------------------------------
RUN if [ "${INSTALL_KOTLIN_LSP}" = "true" ]; then \
    if [ "${INSTALL_JAVA}" != "true" ]; then \
    echo "ERROR: INSTALL_KOTLIN_LSP=true requires INSTALL_JAVA=true" >&2; exit 1; \
    fi && \
    set -eux && \
    ARCH=$(dpkg --print-architecture) && \
    case "$ARCH" in \
    amd64) ARCH_SUFFIX=""; KOTLIN_LS_SHA256="${KOTLIN_LS_SHA256_AMD64}" ;; \
    arm64) ARCH_SUFFIX="-aarch64"; KOTLIN_LS_SHA256="${KOTLIN_LS_SHA256_ARM64}" ;; \
    *) echo "ERROR: unsupported architecture for Kotlin LSP: $ARCH" >&2; exit 1 ;; \
    esac && \
    echo "Installing official Kotlin LSP ${KOTLIN_LS_VERSION} (${ARCH})..." && \
    curl -fsSL "https://download.jetbrains.com/language-server/kotlin-server/${KOTLIN_LS_VERSION}/kotlin-server-${KOTLIN_LS_VERSION}${ARCH_SUFFIX}.tar.gz" -o /tmp/kotlin-lsp.tar.gz && \
    echo "${KOTLIN_LS_SHA256}  /tmp/kotlin-lsp.tar.gz" | sha256sum -c - && \
    mkdir -p /opt/kotlin-lsp && \
    tar -xzf /tmp/kotlin-lsp.tar.gz -C /opt/kotlin-lsp --strip-components=1 && \
    rm /tmp/kotlin-lsp.tar.gz && \
    chmod +x /opt/kotlin-lsp/bin/intellij-server && \
    ln -sf /opt/kotlin-lsp/bin/intellij-server /usr/local/bin/kotlin-language-server; \
    fi

# -----------------------------------------------------------------------------
# Node.js - Installed via DevContainer feature (ghcr.io/devcontainers/features/node)
# Node.js v22+ respects container memory limits automatically — no explicit
# --max-old-space-size needed. Override via containerEnv if you hit heap limits.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# rtk — Rust Token Killer (Conditional, part of the local-first stack)
# Transparent CLI-output compression. Per-arch release tarball from GitHub
# (amd64 → x86_64-unknown-linux-musl, arm64 → aarch64-unknown-linux-gnu). The
# Apache-2.0 rtk-oss edition (see RTK_VERSION for why not the default binary).
# `rtk` binary lands in /usr/local/bin; post-create.sh installs its Claude Code
# PreToolUse hook (`rtk init -g`). SHA-256 verified against GitHub's published
# per-asset digest. The tarball layout isn't contractually fixed, so we locate
# the `rtk` binary after extraction rather than assuming a path.
# -----------------------------------------------------------------------------
RUN if [ "${INSTALL_RTK}" = "true" ]; then \
    set -eux && \
    ARCH=$(dpkg --print-architecture) && \
    case "$ARCH" in \
    amd64) RTK_TRIPLE="x86_64-unknown-linux-musl"; RTK_SHA256="${RTK_SHA256_AMD64}" ;; \
    arm64) RTK_TRIPLE="aarch64-unknown-linux-gnu"; RTK_SHA256="${RTK_SHA256_ARM64}" ;; \
    *) echo "ERROR: unsupported architecture for rtk: $ARCH" >&2; exit 1 ;; \
    esac && \
    echo "Installing rtk-oss ${RTK_VERSION} (${ARCH})..." && \
    curl -fsSL "https://github.com/rtk-ai/rtk/releases/download/v${RTK_VERSION}/rtk-oss-${RTK_TRIPLE}.tar.gz" -o /tmp/rtk.tar.gz && \
    if [ -n "${RTK_SHA256}" ]; then \
    echo "${RTK_SHA256}  /tmp/rtk.tar.gz" | sha256sum -c - ; \
    else \
    echo "WARNING: RTK_SHA256 not pinned for ${ARCH} — skipping checksum verification." >&2 ; \
    fi && \
    mkdir -p /tmp/rtk-extract && tar -xzf /tmp/rtk.tar.gz -C /tmp/rtk-extract && \
    RTK_BIN=$(find /tmp/rtk-extract -type f -name rtk | head -n1) && \
    [ -n "$RTK_BIN" ] && install -m 0755 "$RTK_BIN" /usr/local/bin/rtk && \
    install -D -m 0644 /tmp/rtk-extract/LICENSE /usr/local/share/doc/rtk/LICENSE && \
    rm -rf /tmp/rtk.tar.gz /tmp/rtk-extract; \
    fi

# -----------------------------------------------------------------------------
# Review tools (Conditional) — the tw-code-review plugin's static analysers
# gitleaks comes from the build stage above. spotbugs is unpacked to
# /opt/spotbugs; the FindSecBugs jar goes into its plugin/ directory, which
# spotbugs loads on its own, so no -pluginList or review config is needed.
# detekt is the self-contained CLI jar behind a small wrapper. PMD is unpacked
# to /opt/pmd. Pins and the reasons for them: the ARG block at the top.
# -----------------------------------------------------------------------------
COPY --from=gitleaks-build /out/ /usr/local/bin/
RUN if [ "${INSTALL_REVIEW_TOOLS}" = "true" ]; then \
    set -eux && \
    command -v java >/dev/null || { echo "ERROR: INSTALL_REVIEW_TOOLS needs INSTALL_JAVA=true (spotbugs, detekt, pmd)" >&2; exit 1; } && \
    curl -fsSL "https://github.com/spotbugs/spotbugs/releases/download/${SPOTBUGS_VERSION}/spotbugs-${SPOTBUGS_VERSION}.tgz" -o /tmp/spotbugs.tgz && \
    echo "${SPOTBUGS_SHA256}  /tmp/spotbugs.tgz" | sha256sum -c - && \
    tar -xzf /tmp/spotbugs.tgz -C /opt && \
    ln -sfn "/opt/spotbugs-${SPOTBUGS_VERSION}" /opt/spotbugs && \
    curl -fsSL "https://repo1.maven.org/maven2/com/h3xstream/findsecbugs/findsecbugs-plugin/${FINDSECBUGS_VERSION}/findsecbugs-plugin-${FINDSECBUGS_VERSION}.jar" -o /opt/spotbugs/plugin/findsecbugs-plugin.jar && \
    echo "${FINDSECBUGS_SHA256}  /opt/spotbugs/plugin/findsecbugs-plugin.jar" | sha256sum -c - && \
    chmod 0755 /opt/spotbugs/bin/spotbugs && \
    ln -sf /opt/spotbugs/bin/spotbugs /usr/local/bin/spotbugs && \
    mkdir -p /opt/detekt && \
    curl -fsSL "https://repo1.maven.org/maven2/io/gitlab/arturbosch/detekt/detekt-cli/${DETEKT_VERSION}/detekt-cli-${DETEKT_VERSION}-all.jar" -o /opt/detekt/detekt-cli-all.jar && \
    echo "${DETEKT_SHA256}  /opt/detekt/detekt-cli-all.jar" | sha256sum -c - && \
    printf '#!/bin/sh\nexec java -jar /opt/detekt/detekt-cli-all.jar "$@"\n' > /usr/local/bin/detekt && \
    chmod 0755 /usr/local/bin/detekt && \
    curl -fsSL "https://github.com/pmd/pmd/releases/download/pmd_releases%2F${PMD_VERSION}/pmd-dist-${PMD_VERSION}-bin.zip" -o /tmp/pmd.zip && \
    echo "${PMD_SHA256}  /tmp/pmd.zip" | sha256sum -c - && \
    unzip -q /tmp/pmd.zip -d /opt && \
    ln -sfn "/opt/pmd-bin-${PMD_VERSION}" /opt/pmd && \
    ln -sf /opt/pmd/bin/pmd /usr/local/bin/pmd && \
    rm -f /tmp/spotbugs.tgz /tmp/pmd.zip; \
    fi

# -----------------------------------------------------------------------------
# Python - Installed via DevContainer feature (ghcr.io/devcontainers/features/python)
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# UV Package Manager Installation
# -----------------------------------------------------------------------------
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /tmp/uv-binaries/
RUN cp /tmp/uv-binaries/uv /usr/local/bin/uv && \
    cp /tmp/uv-binaries/uvx /usr/local/bin/uvx && \
    chmod +x /usr/local/bin/uv /usr/local/bin/uvx && \
    rm -rf /tmp/uv-binaries

# UV environment variables
ENV UV_LINK_MODE=copy
# UV_CACHE_DIR is deliberately NOT set here. A Dockerfile ENV reaches every
# process, whatever its user, and the container starts as root
# ("containerUser": "root"). One root-context `uv` run against a cache inside
# vscode's home leaves root-owned entries in the cache volume; every later `uv`
# command as vscode then fails ("Failed to initialize cache … Permission
# denied"), across rebuilds, and vscode has no sudo to repair it. It is set for
# the remote user only, in devcontainer.json "remoteEnv".

# -----------------------------------------------------------------------------
# Claude Code CLI (Conditional)
# Installed via native installer in post-create script (requires Node.js at runtime)
# ENV set conditionally to keep non-Claude images clean.
# -----------------------------------------------------------------------------
RUN if [ "${INSTALL_CLAUDE}" = "true" ]; then \
    echo 'export CLAUDE_CONFIG_DIR=/home/vscode/.claude' >> /etc/profile.d/claude.sh && \
    chmod +x /etc/profile.d/claude.sh; \
    fi

# -----------------------------------------------------------------------------
# Playwright / Headless Chromium System Libraries (Conditional)
# Headless Chromium needs shared libraries the base image does not ship. Baked in
# here (root, cached) so the browser binary post-create.sh downloads as vscode runs.
# Chromium only: it bundles ICU, libvpx and libavif, so libicu*/libvpx*/libavif* are
# deliberately absent. Package names are Debian 13 "trixie" (t64 suffixes); a base
# without them fails the build loudly — refresh against
# `npx playwright install-deps --dry-run chromium`.
# -----------------------------------------------------------------------------
RUN if [ "${INSTALL_PLAYWRIGHT}" = "true" ]; then \
    set -eux && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
    libnss3 libnspr4 libdbus-1-3 \
    libatk1.0-0t64 libatk-bridge2.0-0t64 libatspi2.0-0t64 libcups2t64 \
    libdrm2 libgbm1 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 \
    libxrandr2 libxshmfence1 libasound2t64 fonts-liberation && \
    apt-get clean -y && rm -rf /var/lib/apt/lists/* && \
    echo 'export PLAYWRIGHT_BROWSERS_PATH=/home/vscode/.cache/ms-playwright' > /etc/profile.d/playwright.sh && \
    chmod +x /etc/profile.d/playwright.sh; \
    fi

# -----------------------------------------------------------------------------
# Plugin Marketplace Seed Directory
# Private Marketplaces can be cloned on the host by local-init.sh (initializeCommand)
# and LIVE bind-mounted into the container at /opt/claude-seed/marketplaces at
# runtime (see devcontainer.json "mounts"). They are intentionally NOT COPYed
# into the image: a baked COPY only refreshes on rebuild, so a plain container
# start would keep serving stale plugin versions. The bind-mount means every
# START picks up whatever initializeCommand just pulled. No git credentials
# enter the image. We create the mount point so the bind target exists.
# -----------------------------------------------------------------------------
RUN mkdir -p /opt/claude-seed/marketplaces

# -----------------------------------------------------------------------------
# Directory Structure & Permissions
# -----------------------------------------------------------------------------
# Create base directories (always needed)
RUN mkdir -p \
    /home/vscode/.npm \
    /home/vscode/.uv-cache \
    /home/vscode/.cache/pip \
    /home/vscode/.local/bin \
    /workspace && \
    # Java/Maven directories (conditional)
    if [ "${INSTALL_JAVA}" = "true" ]; then \
    mkdir -p /home/vscode/.m2/repository /home/vscode/.java/.userPrefs; \
    fi && \
    # JetBrains directories (conditional)
    if [ "${INSTALL_JETBRAINS}" = "true" ]; then \
    mkdir -p /home/vscode/.local/share/JetBrains/RemoteDev \
    /home/vscode/.cache/JetBrains/RemoteDev \
    /home/vscode/.cache/JetBrains \
    /home/vscode/.config/JetBrains; \
    fi && \
    # Claude Code directories (conditional)
    if [ "${INSTALL_CLAUDE}" = "true" ]; then \
    mkdir -p /home/vscode/.claude /home/vscode/.cache/claude-code; \
    fi && \
    # headroom workspace/CCR store (conditional) — pre-created so the named volume
    # mounted at ~/.headroom (HEADROOM_WORKSPACE_DIR: memory DB, CCR originals,
    # savings ledger, logs) inherits vscode ownership instead of root:root.
    if [ "${INSTALL_HEADROOM}" = "true" ]; then \
    mkdir -p /home/vscode/.headroom; \
    fi && \
    # Playwright browser cache (conditional) — pre-created so the named volume
    # mounted at ~/.cache/ms-playwright inherits vscode ownership, not root:root.
    if [ "${INSTALL_PLAYWRIGHT}" = "true" ]; then \
    mkdir -p /home/vscode/.cache/ms-playwright; \
    fi && \
    chown -R vscode:vscode /home/vscode /workspace

# -----------------------------------------------------------------------------
# Final Configuration
# -----------------------------------------------------------------------------
# Reset to interactive mode
ENV DEBIAN_FRONTEND=dialog

# -----------------------------------------------------------------------------
# Sudo: no root for the vscode user
# -----------------------------------------------------------------------------
# The base image gives vscode passwordless sudo for everything
# (/etc/sudoers.d/vscode). Everything that runs as vscode — an AI agent included —
# could then become root and, among other things, remount the read-only
# .devcontainer/ bind writable and plant code that runs on the host at the next
# start. There is no egress firewall in this config, so vscode needs no sudo rule
# at all. Docker-in-Docker still works: PID 1 runs as root ("containerUser":
# "root"), so the feature starts dockerd without sudo. Note that membership of
# the docker group stays root-equivalent; this removes the direct path, not that one.
# A developer who needs root runs, from the HOST:
#   docker exec -u root -it essentials-project-devcontainer bash
# The build fails if a blanket rule survives.
RUN rm -f /etc/sudoers.d/vscode && \
    if sudo -l -U vscode | grep -qE 'NOPASSWD: *ALL *$|\) *ALL *$'; then \
    echo "ERROR: vscode still has blanket sudo — find the rule under /etc/sudoers*" >&2; exit 1; \
    fi

# Switch to non-root user for security
USER vscode

# Configure PATH for user. ~/.local/bin is added here (not just via profile.d)
# so non-login interactive shells also resolve it (profile.d only loads in
# login shells; ~/.bashrc loads in interactive shells).
RUN echo 'export PATH="$HOME/.local/bin:$PATH"' >> /home/vscode/.bashrc

# Set working directory
WORKDIR /workspace

# Labels for documentation and container identification
# PROJECT_NAME is set via devcontainer.json build.args for per-project identity
LABEL org.opencontainers.image.title="${PROJECT_NAME}-devcontainer" \
    org.opencontainers.image.description="Development container for ${PROJECT_NAME}" \
    org.opencontainers.image.version="1.0.0" \
    devcontainer.project="${PROJECT_NAME}"
