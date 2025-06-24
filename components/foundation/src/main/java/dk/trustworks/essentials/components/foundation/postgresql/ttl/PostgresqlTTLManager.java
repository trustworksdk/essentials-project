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

package dk.trustworks.essentials.components.foundation.postgresql.ttl;

import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.foundation.ttl.*;
import dk.trustworks.essentials.shared.Lifecycle;
import org.slf4j.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;

public class PostgresqlTTLManager implements TTLManager, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(PostgresqlTTLManager.class);

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final List<TTLJobDefinition>                                        ttlJobDefinitions = new CopyOnWriteArrayList<>();

    private volatile     boolean started;
    private static final String  ROLLBACK_MANUALLY = "ROLLBACK_MANUALLY";

    public PostgresqlTTLManager(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
    }

    @Override
    public void scheduleTTLJob(TTLJobDefinition jobDefinition) {
        ttlJobDefinitions.add(jobDefinition);
        if (started) {
            log.info("Scheduling TTL job '{}'", jobDefinition);
            scheduleJob(jobDefinition);
        } else {
            log.info("Manager not started, skipping scheduleTTLJob '{}'", jobDefinition);
        }
    }

    private void scheduleJob(TTLJobDefinition jobDefinition) {
        TTLJobAction action = jobDefinition.action();
        ScheduleConfiguration scheduleConfig = jobDefinition.scheduleConfiguration();
        action.validate(unitOfWorkFactory);

        if (scheduleConfig instanceof CronScheduleConfiguration cronConfig) {
            if (postgresqlScheduler.isPgCronAvailable()) {
                String functionCall = action.buildFunctionCall();
                postgresqlScheduler.schedulePgCronJob(new PgCronJob(functionCall, cronConfig.cronExpression()));
                return;
            }
            log.warn("PgCron not available; falling back to fixed-delay scheduling.");
        }

        FixedDelayScheduleConfiguration fixedConfig;
        if (scheduleConfig instanceof FixedDelayScheduleConfiguration fdc) {
            fixedConfig = fdc;
        } else if (scheduleConfig instanceof CronScheduleConfiguration csc) {
            fixedConfig = csc.fixedDelay()
                             .map(FixedDelayScheduleConfiguration::new)
                             .orElseGet(csc::toFixedDelay);
        } else {
            throw new IllegalArgumentException("Unsupported schedule configuration type.");
        }

        Runnable runnable = () -> action.executeDirectly(unitOfWorkFactory);
        postgresqlScheduler.scheduleExecutorJob(
                new EssentialsScheduledJob(
                        fixedConfig.fixedDelay(),
                        action.toString(),
                        runnable)
                                               );
    }

    @Override
    public void start() {
        if (!started) {
            started = true;
            log.info("⚙️ Starting Postgresql Time-to-Live manager");

            initializeTimeToLiveFunction();

            for (TTLJobDefinition jobDefinition : ttlJobDefinitions) {
                scheduleJob(jobDefinition);
            }
        }
    }

    private void initializeTimeToLiveFunction() {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            String sql = bind("""
                            CREATE OR REPLACE FUNCTION {:functionName}
                            (p_table_name text, p_delete_statement text) RETURNS void AS $$
                            BEGIN
                            -- Use format/identifier quoting to guard against SQL injection
                            EXECUTE format('DELETE FROM %I WHERE %s', p_table_name, p_delete_statement);
                            END;\
                            $$ LANGUAGE plpgsql;""", arg("functionName", DEFAULT_TTL_FUNCTION_NAME));
            uow.handle().execute(sql);
        });
    }

    @Override
    public void stop() {
        if (started) {
            started = false;
            log.info("⚙️ Stopping Postgresql Time-to-Live manager");
        }
    }

    @Override
    public boolean isStarted() {
        return false;
    }
}
