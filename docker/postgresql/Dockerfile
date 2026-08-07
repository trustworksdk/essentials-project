# syntax=docker/dockerfile:1

ARG POSTGRES_IMAGE=postgres:17.5-bookworm

FROM ${POSTGRES_IMAGE} AS builder

ARG PG_CRON_TAG=v1.6.4
ARG WAL2JSON_TAG=wal2json_2_6

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates wget gnupg \
 && install -d /usr/share/postgresql-common/pgdg \
 && wget -qO- https://www.postgresql.org/media/keys/ACCC4CF8.asc \
      | gpg --dearmor > /usr/share/postgresql-common/pgdg/apt.postgresql.org.gpg \
 && gpg --dry-run --import --import-options show-only \
      /usr/share/postgresql-common/pgdg/apt.postgresql.org.gpg \
      | grep -q "B97B0AFCAA1A47F044F244A07FCC7D46ACCC4CF8" \
 && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.gpg] \
      http://apt.postgresql.org/pub/repos/apt bookworm-pgdg main" \
      > /etc/apt/sources.list.d/pgdg.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
      git \
      build-essential \
      postgresql-server-dev-17 \
 && git clone --branch ${PG_CRON_TAG} --depth=1 https://github.com/citusdata/pg_cron.git /tmp/pg_cron \
 && make -C /tmp/pg_cron install \
 && git clone --branch ${WAL2JSON_TAG} --depth=1 https://github.com/eulerto/wal2json.git /tmp/wal2json \
 && make -C /tmp/wal2json install \
 && apt-get purge -y --auto-remove git build-essential gnupg \
 && rm -rf /tmp/pg_cron /tmp/wal2json /var/lib/apt/lists/*

FROM ${POSTGRES_IMAGE}

COPY --from=builder /usr/lib/postgresql/ /usr/lib/postgresql/
COPY --from=builder /usr/share/postgresql/ /usr/share/postgresql/

USER postgres
COPY --chown=postgres:postgres initdb/01-create-pg-cron.sql /docker-entrypoint-initdb.d/01-create-pg-cron.sql

EXPOSE 5432
