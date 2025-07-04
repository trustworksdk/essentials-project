/*
 * Copyright 2021-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.foundation.ttl;

import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;

public abstract class AbstractTTLManagerTest {

    protected static String TEST_TABLE_NAME = "pg_cron_test";
    protected        Jdbi   jdbi;

    @BeforeEach
    protected void setup() {
        jdbi = Jdbi.create(getPostgreSQLContainer().getJdbcUrl(), getPostgreSQLContainer().getUsername(), getPostgreSQLContainer().getPassword());
    }

    protected abstract PostgreSQLContainer<?> getPostgreSQLContainer();

    protected int getNumberOfRowsInTable(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return getNumberOfRowsInTable(TEST_TABLE_NAME, unitOfWorkFactory);
    }

    protected int getNumberOfRowsInTable(String tableName, JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("SELECT COUNT(*) FROM " + tableName)
                                                          .mapTo(Integer.class)
                                                          .one());
    }

    protected void setupTestData(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        setupTestData(TEST_TABLE_NAME, unitOfWorkFactory);
    }

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
}
