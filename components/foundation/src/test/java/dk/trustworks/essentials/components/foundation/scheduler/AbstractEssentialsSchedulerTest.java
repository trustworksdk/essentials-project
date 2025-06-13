package dk.trustworks.essentials.components.foundation.scheduler;

import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;

public abstract class AbstractEssentialsSchedulerTest {

    protected static DockerImageName pgCronImage = DockerImageName.parse("ghcr.io/trustworksdk/postgres-with-pgcron:latest").asCompatibleSubstituteFor("postgres");
    protected static String TEST_TABLE_NAME = "pg_cron_test";
    protected static String TEST_FUNCTION_NAME = "insert_rows_each_second_for_10_seconds";
    protected Jdbi jdbi;

    @BeforeEach
    protected void setup() {
        jdbi = Jdbi.create(getPostgreSQLContainer().getJdbcUrl(), getPostgreSQLContainer().getUsername(), getPostgreSQLContainer().getPassword());
    }

    protected abstract PostgreSQLContainer<?> getPostgreSQLContainer();

    protected void setupTestData(String tableName, JdbiUnitOfWorkFactory unitOfWorkFactory) {
        String sql = bind("""
                CREATE TABLE IF NOT EXISTS {:tableName} (
                                           id SERIAL PRIMARY KEY,
                                           name TEXT NOT NULL,
                                           created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                           expiry_ts TIMESTAMPTZ
                                       );
                                       -- Insert some test rows:
                                       -- 1. A row with expiry in the past (expired).
                                       -- 2. A row with expiry in the very recent past (borderline).
                                       -- 3. Rows with expiry in the future (active).
                                       INSERT INTO {:tableName} (name, expiry_ts)
                                       VALUES
                                           ('Expired row - 1 hour ago', now() - interval '1 hour'),
                                           ('Expired row - 10 minutes ago', now() - interval '10 minutes'),
                                           ('Active row - expires in 1 hour', now() + interval '1 hour'),
                                           ('Active row - expires in 2 hours', now() + interval '2 hours'),
                                           ('Borderline expired - 1 second ago', now() - interval '1 second');
            """, arg("tableName", tableName));
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute(sql);
        });
    }

    protected void setupTestData(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        setupTestData(TEST_TABLE_NAME, unitOfWorkFactory);
    }

    protected void setupTestFunction(String functionName, JdbiUnitOfWorkFactory unitOfWorkFactory) {
        String sql = bind("""
            CREATE OR REPLACE FUNCTION {:functionName}()
            RETURNS void AS $$
            DECLARE
                i INT;
            BEGIN
                FOR i IN 1..10 LOOP
                    INSERT INTO pg_cron_test (name, expiry_ts)
                    VALUES ('Auto-inserted row at ' || now(), now() + interval '1 hour');
                    PERFORM pg_sleep(1);
                END LOOP;
            END;
            $$ LANGUAGE plpgsql;
            """, arg("functionName", functionName));
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute(sql);
        });
    }

    protected void setupTestFunction(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        setupTestFunction(TEST_FUNCTION_NAME, unitOfWorkFactory);
    }

    protected int getNumberOfRowsInTable(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return getNumberOfRowsInTable(TEST_TABLE_NAME, unitOfWorkFactory);
    }

    protected int getNumberOfRowsInTable(String tableName, JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            return uow.handle().createQuery("SELECT COUNT(*) FROM " + tableName)
                      .mapTo(Integer.class)
                      .one();
        });
    }

    static class ExpireRows {
        private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
        private final String                                                        tableName;

        ExpireRows(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory, String tableName) {
            this.unitOfWorkFactory = unitOfWorkFactory;
            this.tableName = tableName;
        }

        ExpireRows(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
           this(unitOfWorkFactory, TEST_TABLE_NAME);
        }

        public void deleteExpiredRows() {
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                int rows = uow.handle().execute("DELETE FROM " + tableName + " WHERE expiry_ts < now()");
                System.out.println("Deleted " + rows + " rows");
            });
        }
    }

}
