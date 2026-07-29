# syntax=docker/dockerfile:1

FROM postgres:17.5-bookworm AS builder

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
 && apt-get install -y --no-install-recommends postgresql-17-wal2json \
 && rm -rf /var/lib/apt/lists/*

FROM postgres:17.5-bookworm

COPY --from=builder /usr/lib/postgresql/ /usr/lib/postgresql/
COPY --from=builder /usr/share/postgresql/ /usr/share/postgresql/

EXPOSE 5432
