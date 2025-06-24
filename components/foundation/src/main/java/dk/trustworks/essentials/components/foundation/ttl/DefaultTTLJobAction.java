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

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;

import static dk.trustworks.essentials.components.foundation.ttl.TTLManager.DEFAULT_TTL_FUNCTION_NAME;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class DefaultTTLJobAction implements TTLJobAction {
    private final String tableName;
    private final String deleteStatement;

    public DefaultTTLJobAction(String tableName, String deleteStatement) {
        this.tableName = requireNonNull(tableName,"tableName must not be null");
        this.deleteStatement = requireNonNull(deleteStatement, "deleteStatement must not be null");
    }

    @Override
    public void validate(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        requireNonNull(unitOfWorkFactory, "unitOfWorkFactory must not be null");
        PostgresqlUtil.checkIsValidTableOrColumnName(tableName);
        try {
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                // Validate syntax without executing side effects
                uow.handle().execute("PREPARE stmt AS " + deleteStatement);
                uow.handle().execute("DEALLOCATE stmt");
            });
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Failed to validate delete statement '%s'", deleteStatement), e
            );
        }
    }

    @Override
    public String buildFunctionCall() {
        return String.format("SELECT %s(%s::text, %s::text);",
                             DEFAULT_TTL_FUNCTION_NAME,
                             quoteLiteral(tableName),
                             quoteLiteral(deleteStatement)
                            );
    }

    private String quoteLiteral(String input) {
        return "'" + input.replace("'", "''") + "'";
    }

    @Override
    public void executeDirectly(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        requireNonNull(unitOfWorkFactory, "unitOfWorkFactory must not be null");
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute(deleteStatement);
        });
    }

    @Override
    public String toString() {
        return tableName + " -> " + deleteStatement;
    }
}

