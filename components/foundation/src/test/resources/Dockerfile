# Build the postgres-with-pg-cron:latest image
# requires container to be startet with command -> "postgres", "-c", "shared_preload_libraries=pg_cron", "-c", "cron.database_name=something"
# syntax=docker/dockerfile:1

# ─── STAGE 1: build pg_cron ──────────────────────────────────────────────
FROM postgres:17.5 AS builder

# install build deps, compile pg_cron, then purge everything in one layer
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates \
      git \
      build-essential \
      postgresql-server-dev-all \
 && git clone --depth=1 https://github.com/citusdata/pg_cron.git /tmp/pg_cron \
 && cd /tmp/pg_cron \
 && make install \
 && cd / \
 # remove build tools & temp files
 && apt-get purge -y --auto-remove \
      git \
      build-essential \
      postgresql-server-dev-all \
 && rm -rf /tmp/pg_cron /var/lib/apt/lists/*

# ─── STAGE 2: minimal runtime ─────────────────────────────────────────────
FROM postgres:17.5

# copy only the runtime bits of pg_cron
COPY --from=builder /usr/lib/postgresql /usr/lib/postgresql
COPY --from=builder /usr/share/postgresql /usr/share/postgresql

# drop privileges for defense-in-depth
USER postgres

# create an init-script in the official init dir
# Copy your SQL init script and set ownership so postgres can read it
COPY --chown=postgres:postgres create_pg_cron.sql \
     /docker-entrypoint-initdb.d/01-pg-cron.sql

# expose default port and inherit entrypoint/cmd
EXPOSE 5432


