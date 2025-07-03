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

package dk.trustworks.essentials.components.foundation.scheduler.pgcron;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.scheduler.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;
import static java.util.stream.Collectors.joining;

/**
 * Represents a PostgreSQL cron job that is scheduled using a {@link CronExpression}.
 * This class implements {@link EssentialsScheduledJob} and encapsulates details
 * about a scheduled job, including its name, the PostgreSQL function to execute,
 * arguments to the function, and the cron expression that determines the job's schedule.
 * <p>
 * {@link PgCronJob#functionName()} is validated using {@link PostgresqlUtil#isValidFunctionName(String)} as an initial layer
 * of defense against SQL injection by applying naming conventions intended to reduce the risk of malicious input.<br>
 * However, Essentials components does not offer exhaustive protection, nor does it ensure the complete security of the resulting SQL against SQL injection threats.<br>
 * <b>The responsibility for implementing protective measures against SQL Injection lies exclusively with the users/developers using the Essentials components and its supporting classes.</b><br>
 * Users must ensure thorough sanitization and validation of API input parameters, column, table, and index names.<br>
 * Insufficient attention to these practices may leave the application vulnerable to SQL injection, potentially endangering the security and integrity of the database.<br>
 * <br>
 * It is highly recommended that the {@link PgCronJob#cronExpression()}, {@link PgCronJob#functionName()} and  {@link PgCronJob#args()} value is only derived from a controlled and trusted source.<br>
 * To mitigate the risk of SQL injection attacks, external or untrusted inputs should never directly provide the {@link PgCronJob#cronExpression()}, {@link  PgCronJob#functionName()} and  {@link PgCronJob#args()} values.<br>
 *
 * <p>Example usage:
 * <pre>{@code
 * Arg<String> arg1 = Arg.literal("value1");
 * Arg<Integer> arg2 = Arg.expression(42, Object::toString);
 * CronExpression cronExpression = CronExpression.parse("0 5 * * *");
 *
 * PgCronJob job = new PgCronJob("my_job", "my_function", List.of(arg1, arg2), cronExpression);
 * System.out.println(job.name()); // Outputs: "my_job"
 * System.out.println(job.functionName()); // Outputs: "my_function"
 * System.out.println(job.argsToCommaSeparatedString()); // Outputs: "'value1',42"
 * }
 * </pre>
 *
 * @param name          the unique name of the cron job, which must not be null or exceed 50 characters.
 * @param functionName  the name of the PostgreSQL function this job triggers; must not be null and must pass SQL validation.
 * @param args          a list of arguments passed to the PostgreSQL function; may be null or empty, but cannot exceed {@link Arg#MAX_ARGS_COUNT}.
 * @param cronExpression the cron expression defining the schedule; must not be null.
 *
 * @see {@link EssentialsScheduledJob}
 * @see {@link CronExpression}
 * @see {@link Arg}
 * @see {@link Arg#toSql()}
 * @see {@link PostgresqlUtil}
 */
public record PgCronJob(String name, String functionName, List<Arg<?>> args, CronExpression cronExpression) implements EssentialsScheduledJob {

    public PgCronJob {
        requireNonNull(name, "name cannot be null");
        requireTrue(name.length() <= 50, "name must be <= 50 characters long");
        requireNonNull(functionName, "functionName cannot be null");
        requireNonNull(cronExpression, "cronExpression cannot be null");
        if (!PostgresqlUtil.isValidFunctionName(functionName)) {
            throw new IllegalArgumentException(msg("Invalid function name '{}' ", functionName));
        }
        if (args != null && args.size() > Arg.MAX_ARGS_COUNT) {
            throw new IllegalArgumentException(
                    msg("Too many arguments ('{}'), max is '{}'",
                                  args.size(), Arg.MAX_ARGS_COUNT)
            );
        }
    }

    /**
     * Converts the list of arguments available in the {@code args} field of {@link PgCronJob}
     * into a single comma-separated string by mapping each argument to its SQL string representation
     * using {@link Arg#toSql()}.
     *
     * @return a comma-separated {@code String} of the SQL representations of the arguments in {@code args}.
     *         If {@code args} is empty, an empty string is returned.
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * // Assume the following setup:
     * Arg<String> arg1 = Arg.literal("value1");
     * Arg<Integer> arg2 = Arg.expression(42, Object::toString);
     *
     * PgCronJob job = new PgCronJob("job_name", "function_name", List.of(arg1, arg2), cronExpression);
     *
     * String result = job.argsToCommaSeparatedString();
     * System.out.println(result); // Outputs: "'value1',42"
     * }</pre>
     *
     * @see {@link Arg} for more details about individual argument behavior.
     */
    public String argsToCommaSeparatedString() {
        return args.stream().map(Arg::toSql).collect(joining(","));
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        PgCronJob pgCronJob = (PgCronJob) o;
        return Objects.equals(name, pgCronJob.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }
}
