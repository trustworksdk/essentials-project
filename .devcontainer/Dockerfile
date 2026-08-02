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
ARG JDTLS_VERSION="1.60.0"
ARG JDTLS_BUILD_DATE="202606262232"
ARG JDTLS_SHA256="e94c303d8198f977930803582738771fd18c52c5492878410bf222b1aa81ef1d"
# kotlin-language-server (official JetBrains Kotlin LSP). Tarballs are hosted on
# JetBrains' CDN (the GitHub release page just links out). Each tarball ships a
# sibling .sha256 file; values below were captured at the pinned version.
# Refresh recipe:
#   curl -fsSL https://download-cdn.jetbrains.com/language-server/kotlin-server/<ver>/kotlin-server-<ver>.tar.gz.sha256
#   curl -fsSL https://download-cdn.jetbrains.com/language-server/kotlin-server/<ver>/kotlin-server-<ver>-aarch64.tar.gz.sha256
ARG KOTLIN_LS_VERSION="262.8190.0"
ARG KOTLIN_LS_SHA256_AMD64="8b4c70e95065420e7867c99aaf9f18e0b4e76311ec453e4c1a39e3f6ae774cbf"
ARG KOTLIN_LS_SHA256_ARM64="c3edd59ef34a7faa4d04f3517afb7a932b19c3f9cf17d1a14e9da17b0b5440ad"
# rtk (Rust Token Killer). Per-arch release tarball from GitHub (amd64 → musl,
# arm64 → gnu). SHA-256 = GitHub's published per-asset digest for the pinned tag,
# verified at build. Refresh recipe (after bumping RTK_VERSION):
#   curl -fsSL -H "Accept: application/vnd.github+json" \
#     https://api.github.com/repos/rtk-ai/rtk/releases/tags/v<ver> \
#     | python3 -c "import sys,json;[print(a['name'],a.get('digest')) for a in json.load(sys.stdin)['assets']]"
ARG RTK_VERSION="0.43.0"
ARG RTK_SHA256_AMD64="ff8a1e7766496e175291a85aeca1dc97c9ff6df33e51e5893d1fbc78fea2a609"
ARG RTK_SHA256_ARM64="5519f7ca12e5c143a609f0d28a0a77b97413a8dce31c2681f1a41c24519a8731"

# -----------------------------------------------------------------------------
# JetBrains Configuration
# -----------------------------------------------------------------------------
ARG JETBRAINS_BACKEND="IntelliJ"

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
    curl -fsSL "https://download-cdn.jetbrains.com/language-server/kotlin-server/${KOTLIN_LS_VERSION}/kotlin-server-${KOTLIN_LS_VERSION}${ARCH_SUFFIX}.tar.gz" -o /tmp/kotlin-lsp.tar.gz && \
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
    echo "Installing rtk ${RTK_VERSION} (${ARCH})..." && \
    curl -fsSL "https://github.com/rtk-ai/rtk/releases/download/v${RTK_VERSION}/rtk-${RTK_TRIPLE}.tar.gz" -o /tmp/rtk.tar.gz && \
    if [ -n "${RTK_SHA256}" ]; then \
    echo "${RTK_SHA256}  /tmp/rtk.tar.gz" | sha256sum -c - ; \
    else \
    echo "WARNING: RTK_SHA256 not pinned for ${ARCH} — skipping checksum verification." >&2 ; \
    fi && \
    mkdir -p /tmp/rtk-extract && tar -xzf /tmp/rtk.tar.gz -C /tmp/rtk-extract && \
    RTK_BIN=$(find /tmp/rtk-extract -type f -name rtk | head -n1) && \
    [ -n "$RTK_BIN" ] && install -m 0755 "$RTK_BIN" /usr/local/bin/rtk && \
    rm -rf /tmp/rtk.tar.gz /tmp/rtk-extract; \
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
ENV UV_CACHE_DIR=/home/vscode/.cache/uv

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
    /home/vscode/.cache/uv \
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
    chown -R vscode:vscode /home/vscode /workspace

# -----------------------------------------------------------------------------
# Final Configuration
# -----------------------------------------------------------------------------
# Reset to interactive mode
ENV DEBIAN_FRONTEND=dialog

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
