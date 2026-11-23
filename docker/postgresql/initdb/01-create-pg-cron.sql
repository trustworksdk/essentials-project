-- pg_cron can only be created on a server that preloads it (shared_preload_libraries=pg_cron, plus cron.database_name).
-- The image also serves suites that do not preload it - the CDC tests need only wal2json - so it is created only then;
-- without the check, initdb failed and such a container never started.
DO $$
BEGIN
    IF current_setting('shared_preload_libraries') LIKE '%pg_cron%' THEN
        CREATE EXTENSION IF NOT EXISTS pg_cron;
    END IF;
END
$$;
