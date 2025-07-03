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

import dk.trustworks.essentials.components.foundation.scheduler.pgcron.*;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
/**
 * Represents an action that relates to a Time-To-Live (TTL) job.
 * This defines the behavior for executing, validating, and describing a specific TTL job action.
 * @see DefaultTTLJobAction
 * @see #create(String, String, String, String)
 */
public interface TTLJobAction {

    /**
     * Retrieves the unique name of the TTL job associated with this action.
     *
     * @return the TTL job name, which uniquely identifies the job.
     */
    String jobName();

    /**
     * Provides the FunctionCall associated with the TTL job action. This represents
     * an SQL function call used within the action to execute specific TTL job behavior.
     *
     * @return the FunctionCall instance containing the function name and its arguments.
     */
    FunctionCall functionCall();

    /**
     * Executes a Time-To-Live (TTL) job action, such as any contained SQL, directly using the provided
     * {@link HandleAwareUnitOfWork} which can be used to coordinate transactions/{@link UnitOfWork}.
     *
     * @param unitOfWorkFactory the factory responsible for creating and managing instances
     *                          of {@link HandleAwareUnitOfWork} for this execution.
     */
    void executeDirectly(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory);

    /**
     * Validate action sql syntax without executing side effects.
     *
     * @param unitOfWorkFactory the factory responsible for creating and managing instances
     *                          of {@link HandleAwareUnitOfWork}, which is used during validation.
     */
    void validate(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory);

    /**
     * Creates a new {@link DefaultTTLJobAction} with the specified parameters.
     * <p>
     * <b>SECURITY WARNING - Limited Validation of {@link DefaultTTLJobAction} value validations:</b><br>
     * This implementation provides <b>only partial protection</b> against SQL injection:
     * <ul>
     * <li><b>VALIDATED:</b> {@link DefaultTTLJobAction#tableName} - checked for valid SQL identifier format as an initial layer of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input<br>
     * However, Essentials components does not offer exhaustive protection, nor does it ensure the complete security of the resulting SQL against SQL injection threats.<br>
     * <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
     * Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.<br>
     * Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br></li>
     * <li><b>NOT VALIDATED:</b> {@code DefaultTTLJobAction#whereClause} and {@link DefaultTTLJobAction#fullDeleteSql} provided to {@link DefaultTTLJobAction} - these are executed directly without any security checks</li>
     * </ul>
     * <p>
     * <b>Developer Responsibility:</b><br>
     * You MUST ensure that the provided {@link DefaultTTLJobAction} {@code tableName}, {@code whereClause} and {@code fullDeleteSql} values are safe before creating
     * this object. These values will be executed directly by the {@link TTLManager} with no additional
     * validation or sanitization.
     * <p>
     * <b>Security Best Practices:</b>
     * <ul>
     * <li>Only derive {@code tableName}, {@code whereClause} and {@code fullDeleteSql} from controlled, trusted sources</li>
     * <li>Never allow external or untrusted input to directly provide these values</li>
     * <li>Implement your own validation/sanitization before passing these parameters</li>
     * <li>Consider using parameterized queries or prepared statements where possible</li>
     * </ul>
     * <p>
     * <b>Failure to properly validate unprotected parameters may result in SQL injection vulnerabilities
     * that could compromise database security and integrity.</b>
     *
     * @param jobName       the job name
     * @param tableName     the table name (will be validated for SQL identifier format)
     * @param whereClause   the WHERE clause (NOT validated - must be pre-validated by caller)
     * @param fullDeleteSql the full DELETE SQL statement (NOT validated - must be pre-validated by caller)
     * @return {@link DefaultTTLJobAction} instance
     * @throws IllegalArgumentException if tableName is invalid or any parameter is null
     */
    static DefaultTTLJobAction create(String jobName,
                                      String tableName,
                                      String whereClause,
                                      String fullDeleteSql) {
        return new DefaultTTLJobAction(jobName, tableName, whereClause, fullDeleteSql);
    }
}
