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

import java.util.Objects;

import static dk.trustworks.essentials.components.foundation.ttl.TTLManager.DEFAULT_TTL_FUNCTION_NAME;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Default implementation of the {@link TTLJobAction} interface. This class provides
 * functionality for managing a Time-To-Live (TTL) job action, including execution, validation,
 * and function call generation specific to a table and a job.
 * <p>
 * <b>SECURITY WARNING - Limited Validation:</b><br>
 * This implementation provides <b>only partial protection</b> against SQL injection:
 * <ul>
 * <li><b>VALIDATED:</b> {@link DefaultTTLJobAction#tableName} - checked for valid SQL identifier format as an initial layer of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input<br>
 * However, Essentials components does not offer exhaustive protection, nor does it ensure the complete security of the resulting SQL against SQL injection threats.<br>
 * <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
 * Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.<br>
 * Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br></li>
 * <li><b>NOT VALIDATED:</b> {@link DefaultTTLJobAction#whereClause} and {@link DefaultTTLJobAction#fullDeleteSql} -
 *     executed directly without any security checks</li>
 * </ul>
 * <p>
 * <b>Developer Responsibility:</b><br>
 * You MUST ensure that {@code whereClause} and {@code fullDeleteSql} values are safe before creating
 * this object. These values will be executed directly by the {@link TTLManager} with no additional
 * validation or sanitization.
 * <p>
 * <b>Security Best Practices:</b>
 * <ul>
 * <li>Only derive {@code whereClause} and {@code fullDeleteSql} from controlled, trusted sources</li>
 * <li>Never allow external or untrusted input to directly provide these values</li>
 * <li>Implement your own validation/sanitization before passing these parameters</li>
 * <li>Consider using parameterized queries or prepared statements where possible</li>
 * </ul>
 * <p>
 * <b>Failure to properly validate unprotected parameters may result in SQL injection vulnerabilities
 * that could compromise database security and integrity.</b>
 */
public class DefaultTTLJobAction implements TTLJobAction {
    public final String tableName;
    public final String whereClause;
    public final String fullDeleteSql;
    public final String jobName;

    /**
     * Creates a new DefaultTTLJobAction with the specified parameters.
     * <p>
     * <b>SECURITY NOTE:</b> Only {@code tableName} is validated. You MUST ensure
     * {@code whereClause} and {@code fullDeleteSql} are safe before calling this constructor.
     *
     * @param tableName the table name (will be validated for SQL identifier format)
     * @param whereClause the WHERE clause (NOT validated - must be pre-validated by caller)
     * @param fullDeleteSql the full DELETE SQL statement (NOT validated - must be pre-validated by caller)
     * @param jobName the job name
     * @throws IllegalArgumentException if tableName is invalid or any parameter is null
     */
    public DefaultTTLJobAction(String tableName,
                               String whereClause,
                               String fullDeleteSql,
                               String jobName) {
        this.tableName     = requireNonNull(tableName, "tableName must not be null");
        this.whereClause   = requireNonNull(whereClause, "whereClause must not be null");
        this.fullDeleteSql = requireNonNull(fullDeleteSql, "fullDeleteSql must not be null");
        this.jobName       = requireNonNull(jobName, "jobName must not be null");
        PostgresqlUtil.checkIsValidTableOrColumnName(tableName);
    }

    @Override
    public void validate(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        requireNonNull(unitOfWorkFactory, "unitOfWorkFactory must not be null");
        try {
            unitOfWorkFactory.usingUnitOfWork(uow -> {
                uow.handle().execute("PREPARE stmt AS " + fullDeleteSql);
                uow.handle().execute("DEALLOCATE stmt");
            });
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Failed to validate delete statement '%s'", fullDeleteSql), e
            );
        }
    }

    @Override
    public String jobName() {
        return jobName;
    }

    @Override
    public String buildFunctionCall() {
        String whereClause = this.whereClause;
        if (whereClause.trim().toLowerCase().startsWith("where ")) {
            whereClause = whereClause.trim().substring(6);
        }
        return String.format("%s(%s, %s);",
                             DEFAULT_TTL_FUNCTION_NAME,
                             quoteLiteral(tableName),
                             quoteLiteral(whereClause)
                            );
    }

    private String quoteLiteral(String input) {
        return "'" + input.replace("'", "''") + "'";
    }

    @Override
    public void executeDirectly(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        requireNonNull(unitOfWorkFactory, "unitOfWorkFactory must not be null");
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute(fullDeleteSql);
        });
    }

    @Override
    public String toString() {
        return jobName + " -> " + fullDeleteSql;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        DefaultTTLJobAction that = (DefaultTTLJobAction) o;
        return Objects.equals(jobName, that.jobName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(jobName);
    }
}

