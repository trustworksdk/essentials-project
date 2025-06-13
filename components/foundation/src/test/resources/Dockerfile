# Build the postgres-with-pg-cron:latest image
# requires container to be startet with command -> "postgres", "-c", "shared_preload_libraries=pg_cron", "-c", "cron.database_name=something"
# Stage 1
FROM postgres:latest as builder

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      ca-certificates \
      git \
      build-essential \
      postgresql-server-dev-all && \
    rm -rf /var/lib/apt/lists/*

RUN git clone --depth=1 https://github.com/citusdata/pg_cron.git /tmp/pg_cron && \
    cd /tmp/pg_cron && \
    make && make install && \
    cd / && rm -rf /tmp/pg_cron

# Stage 2: Final minimal image
FROM postgres:latest

# Copy only the pg_cron runtime files from the builder stage.
# (Adjust the source paths if necessary depending on where the extension files are installed.)
COPY --from=builder /usr/lib/postgresql /usr/lib/postgresql
COPY --from=builder /usr/share/postgresql /usr/share/postgresql

# Copy initialization script
COPY test-containers-init.sql /docker-entrypoint-initdb.d/

