# Slim runtime image for the perf-lab app. Pre-built JAR is copied in — the build itself
# happens on the host (mvn package), not inside the container, so a Docker rebuild is the
# cost of a single COPY rather than a full Maven download. Trade-off: requires the host
# to have run `mvn -pl examples/essentials-performance-lab package -DskipTests` before
# `docker compose build`. The wrapper script `scripts/build-app-image.sh` does both in
# one step for convenience.
FROM eclipse-temurin:17-jre

WORKDIR /app

# Install just what we need to run the JAR. The base image already has bash + a JRE; we
# don't add Maven, Git, or any of the build-time dependencies.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Copy the Spring Boot fat JAR. The exact name (DEV-SNAPSHOT) tracks the parent POM's
# ${revision} property; if that ever changes, update here too.
COPY target/essentials-performance-lab-DEV-SNAPSHOT.jar /app/app.jar

# Default scenario kept identical to the catalog scenario so a `docker compose up` without
# any overrides shows config rather than spinning up a workload nobody asked for. The
# chaos compose profile overrides this with the actual scenario it wants to run.
ENV ESSENTIALS_LAB_SCENARIO=catalog \
    SPRING_DATASOURCE_URL=jdbc:postgresql://postgres-wal2json:5432/essentials_lab \
    SPRING_DATASOURCE_USERNAME=essentials \
    SPRING_DATASOURCE_PASSWORD=essentials \
    JAVA_OPTS="-Xms512m -Xmx1g"

# Actuator listens on 8080 by default; useful for the chaos scripts to probe whether a
# given instance is "alive" via /actuator/health before deciding it's gone.
EXPOSE 8080

# Use exec form so signals (SIGTERM from `docker stop`, SIGKILL from `docker kill`) reach
# the JVM directly instead of going to a wrapping shell. The chaos scripts rely on this:
# `docker kill` should terminate the JVM hard so the failover path is genuinely tested,
# not gracefully shut down.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --essentials.lab.scenario=${ESSENTIALS_LAB_SCENARIO}"]
