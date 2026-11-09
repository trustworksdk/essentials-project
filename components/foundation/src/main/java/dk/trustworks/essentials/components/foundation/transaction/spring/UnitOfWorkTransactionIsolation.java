/*
 * Copyright 2021-2026 the original author or authors.
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
package dk.trustworks.essentials.components.foundation.transaction.spring;

import org.springframework.transaction.TransactionDefinition;

/**
 * The isolation level a {@link SpringTransactionAwareUnitOfWorkFactory} asks for when it starts a transaction for a
 * {@code UnitOfWork}.
 */
public enum UnitOfWorkTransactionIsolation {
    /**
     * The default: every transaction asks for READ COMMITTED. Spring's {@code DataSourceTransactionManager} then reads
     * the connection's current isolation level at the start of every transaction, so it can set and later restore it -
     * on PostgreSQL a round trip ({@code SHOW TRANSACTION ISOLATION LEVEL}) per transaction.
     */
    READ_COMMITTED(TransactionDefinition.ISOLATION_READ_COMMITTED),
    /**
     * Every transaction runs at the database's (or connection pool's) own default isolation level, and the round trip
     * per transaction is saved. The same guarantee as {@link #READ_COMMITTED} wherever that default is READ COMMITTED,
     * as it is on PostgreSQL unless {@code default_transaction_isolation} was changed for the server, the database or
     * the role. Choose it only if you know it is READ COMMITTED - nothing checks.
     */
    DATABASE_DEFAULT(TransactionDefinition.ISOLATION_DEFAULT);

    private final int springIsolationLevel;

    UnitOfWorkTransactionIsolation(int springIsolationLevel) {
        this.springIsolationLevel = springIsolationLevel;
    }

    /**
     * @return the {@link TransactionDefinition} isolation level constant
     */
    public int springIsolationLevel() {
        return springIsolationLevel;
    }
}
