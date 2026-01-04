# Set default Java version via build argument
ARG JAVA_VERSION="24"
# Use multi-stage build to reduce attack surface
FROM mcr.microsoft.com/devcontainers/java:1-${JAVA_VERSION}-bullseye

# Build arguments for optional tool installations
ARG INSTALL_MAVEN="false"
ARG INSTALL_GRADLE="false"
ARG INSTALL_RIPGREP="false"
ARG MAVEN_VERSION="3.9.11"
ARG MAVEN_SHA_512="bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978"
ARG GRADLE_VERSION="9.1.0"
ARG GRADLE_SHA_256="a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806"

# Switch to root for installations and configuration
USER root

# Set noninteractive mode to prevent apt prompts during build
ENV DEBIAN_FRONTEND=noninteractive

# Consolidate apt commands to reduce image layers and improve caching
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
        htop \
        unzip \
        wget \
        uidmap \
        locales && \
    # Generate locales \
    sed -i -e 's/# en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen && \
    locale-gen && \
    # Apply security updates first \
    apt-get upgrade -y && \
    apt-get clean -y && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* && \
    # Create the 'docker' group and add the 'vscode' user for Docker-in-Docker support \
    groupadd -f docker && usermod -aG docker vscode && \
    # Set default locale \
    update-locale LANG=en_US.UTF-8

# Prepare JetBrains Gateway support:
# Create the required directory structure and ensure the 'vscode' user owns it.
RUN mkdir -p /home/vscode/.local/share/JetBrains/RemoteDev && \
    chown -R vscode:vscode /home/vscode/.local

# Install specific Maven version if requested
RUN if [ "${INSTALL_MAVEN}" = "true" ]; then \
        curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -o maven.tar.gz && \
        echo "${MAVEN_SHA_512}  maven.tar.gz" | sha512sum -c - && \
        tar -xzf maven.tar.gz -C /opt && \
        rm maven.tar.gz && \
        ln -sf /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn && \
        ln -sf /opt/apache-maven-${MAVEN_VERSION}/bin/mvnDebug /usr/local/bin/mvnDebug && \
        echo "export MAVEN_HOME=/opt/apache-maven-${MAVEN_VERSION}" >> /etc/profile.d/maven.sh && \
        echo "export PATH=\$MAVEN_HOME/bin:\$PATH" >> /etc/profile.d/maven.sh && \
        chmod +x /etc/profile.d/maven.sh; \
    fi

# Install specific Gradle version if requested
RUN if [ "${INSTALL_GRADLE}" = "true" ]; then \
        curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip && \
        echo "${GRADLE_SHA_256}  /tmp/gradle.zip" | sha256sum -c - && \
        unzip -q /tmp/gradle.zip -d /opt && \
        rm /tmp/gradle.zip && \
        ln -sf /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle && \
        echo "export GRADLE_HOME=/opt/gradle-${GRADLE_VERSION}" >> /etc/profile.d/gradle.sh && \
        echo "export PATH=\$GRADLE_HOME/bin:\$PATH" >> /etc/profile.d/gradle.sh && \
        chmod +x /etc/profile.d/gradle.sh; \
    fi

# Install ripgrep if requested
RUN if [ "${INSTALL_RIPGREP}" = "true" ]; then \
        apt-get update && \
        apt-get install -y --no-install-recommends ripgrep && \
        apt-get clean && rm -rf /var/lib/apt/lists/*; \
    fi

# Prepare JetBrains Gateway support and IDE directories
RUN mkdir -p /home/vscode/.local/share/JetBrains/RemoteDev \
             /home/vscode/.cache/JetBrains/RemoteDev \
             /home/vscode/.config/JetBrains \
             /home/vscode/.java \
             /home/vscode/.config/claude-code \
             /home/vscode/.cache/claude-code && \
    chown -R vscode:vscode /home/vscode/.local /home/vscode/.config /home/vscode/.cache /home/vscode/.java

# Create Maven and Gradle settings directories for user-specific configuration
RUN mkdir -p /home/vscode/.m2 \
             /home/vscode/.gradle && \
    chown -R vscode:vscode /home/vscode/.m2 /home/vscode/.gradle


# Switch back to the non-root user to minimize runtime risks.
USER vscode

ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

ENV MAVEN_HOME=/opt/apache-maven-${MAVEN_VERSION}
ENV GRADLE_HOME=/opt/gradle-${GRADLE_VERSION}
ENV PATH=${MAVEN_HOME}/bin:${GRADLE_HOME}/bin:${PATH}

# Configure Maven and Gradle for optimal DevContainer usage
RUN mkdir -p /home/vscode/.m2 && \
    echo '<?xml version="1.0" encoding="UTF-8"?><settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"></settings>' > /home/vscode/.m2/settings.xml && \
    mkdir -p /home/vscode/.gradle && \
    echo 'org.gradle.daemon=false' > /home/vscode/.gradle/gradle.properties && \
    echo 'org.gradle.caching=true' >> /home/vscode/.gradle/gradle.properties

# Revert DEBIAN_FRONTEND to its default mode for interactive use
ENV DEBIAN_FRONTEND=dialog

# Set default working directory
WORKDIR /workspace
