# The trading demo application as an image, for compose.app.yml (run-app-stack.sh builds what it copies).
# Classes and runtime dependencies are copied as they are, not repackaged: the module is not published, and the
# dependencies that change while developing (the Essentials DEV-SNAPSHOT jars) land in their own layer.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/app-lib/ /app/lib/
COPY target/classes/ /app/classes/
# Full JIT (no TieredStopAtLevel): under load a C1-only JVM took ~1.5x the CPU (_demo_harness/CLAUDE.md).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-cp", "/app/classes:/app/lib/*", \
            "dk.trustworks.essentials.examples.trading.TradingDemoApplication"]
