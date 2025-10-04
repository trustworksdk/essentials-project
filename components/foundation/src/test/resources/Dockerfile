# syntax=docker/dockerfile:1

# ─── STAGE 1: build pg_cron ────────────────────────────────────────────────
FROM postgres:17.5-bookworm AS builder

ARG PG_CRON_TAG=v1.6.4

RUN apt-get update \
 && apt-get install -y --no-install-recommends wget ca-certificates gnupg \
 && install -d /usr/share/postgresql-common/pgdg \
 && wget -qO- https://www.postgresql.org/media/keys/ACCC4CF8.asc \
      | gpg --dearmor > /usr/share/postgresql-common/pgdg/apt.postgresql.org.gpg \
 && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.gpg] \
      http://apt.postgresql.org/pub/repos/apt bookworm-pgdg main" \
      > /etc/apt/sources.list.d/pgdg.list \
 && apt-get update \
 # IMPORTANT: match the server major version
 && apt-get install -y --no-install-recommends \
      git build-essential postgresql-server-dev-17 \
 # build pg_cron from a tag (avoid HEAD drift)
 && git clone --branch ${PG_CRON_TAG} --depth=1 https://github.com/citusdata/pg_cron.git /tmp/pg_cron \
 && make -C /tmp/pg_cron install \
 # cleanup
 && apt-get purge -y --auto-remove git build-essential gnupg \
 && rm -rf /tmp/pg_cron /var/lib/apt/lists/*

# ─── STAGE 2: minimal runtime ──────────────────────────────────────────────
FROM postgres:17.5-bookworm

# Copy only the installed bits
COPY --from=builder /usr/lib/postgresql/ /usr/lib/postgresql/
COPY --from=builder /usr/share/postgresql/ /usr/share/postgresql/

USER postgres
COPY --chown=postgres:postgres create_pg_cron.sql /docker-entrypoint-initdb.d/01-pg-cron.sql
EXPOSE 5432