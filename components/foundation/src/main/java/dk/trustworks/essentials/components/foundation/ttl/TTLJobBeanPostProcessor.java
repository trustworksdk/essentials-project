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

import dk.trustworks.essentials.components.foundation.postgresql.ttl.PostgresqlTTLManager;
import dk.trustworks.essentials.components.foundation.scheduler.pgcron.CronExpression;
import org.slf4j.*;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;

import java.util.Optional;

import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;
import static dk.trustworks.essentials.shared.MessageFormatter.bind;

/**
 * A Spring {@code BeanPostProcessor} that processes beans annotated with the {@code TTLJob} annotation,
 * configures and schedules Time-To-Live (TTL) jobs for database tables based on the provided annotation
 * properties.
 */
public class TTLJobBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(TTLJobBeanPostProcessor.class);

    private final ConfigurableListableBeanFactory beanFactory;
    private final Environment                     environment;

    public TTLJobBeanPostProcessor(ConfigurableListableBeanFactory beanFactory, Environment environment) {
        this.beanFactory = beanFactory;
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        TTLJob ttlJob = AnnotationUtils.findAnnotation(bean.getClass(), TTLJob.class);
        if (ttlJob == null || shouldSkipPostProcessing(bean, beanName)) {
            return bean;
        }

        boolean enabled = ttlJob.enabled();
        if (!ttlJob.enabledProperty().isEmpty()) {
            enabled = environment.getProperty(ttlJob.enabledProperty(), Boolean.class, enabled);
        }
        if (!enabled) {
            log.info("TTL job '{}' is disabled via property {}", beanName, ttlJob.enabledProperty());
            return bean;
        }

        String tableName = ttlJob.tableName();
        if (tableName.isEmpty() && !ttlJob.tableNameProperty().isEmpty()) {
            tableName = environment.getProperty(ttlJob.tableNameProperty(), tableName);
        } else if (tableName.isEmpty()) {
            throw new IllegalArgumentException("@TTLJob on '" + beanName + "' requires tableName or tableNameProperty");
        }

        long days = ttlJob.defaultTtlDays();
        if (!ttlJob.ttlDurationProperty().isEmpty()) {
            days = environment.getProperty(ttlJob.ttlDurationProperty(), Long.class, days);
        }

        String whereClause = DeleteStatementBuilder.buildWhereClause(
                ttlJob.timestampColumn(),
                ttlJob.operator(),
                days
                                                                    );
        String fullDeleteSql = DeleteStatementBuilder.build(
                tableName,
                ttlJob.timestampColumn(),
                ttlJob.operator(),
                days
                                                           );

        String jobName = !ttlJob.name().isEmpty()
                         ? ttlJob.name()
                         : "ttl_" + tableName + "_" + PostgresqlTTLManager.shortHash(fullDeleteSql);

        log.info("Registering TTL job '{}' on table '{}' with TTL {} days (cron='{}')",
                 jobName, tableName, days, ttlJob.cronExpression()
                );

        var deleteAction = new DefaultTTLJobAction(tableName, whereClause, fullDeleteSql, jobName);
        var cronConfig   = new CronScheduleConfiguration(
                CronExpression.of(ttlJob.cronExpression()), Optional.empty()
        );
        var jobDef       = new TTLJobDefinition(deleteAction, cronConfig);

        var manager = beanFactory.getBean(PostgresqlTTLManager.class);
        manager.scheduleTTLJob(jobDef);
        return bean;
    }

    private boolean shouldSkipPostProcessing(Object bean, String beanName) {
        if (this.beanFactory != null) {
            try {
                BeanDefinition beanDefinition = this.beanFactory.getBeanDefinition(beanName);
                if (beanDefinition.getRole() == BeanDefinition.ROLE_INFRASTRUCTURE || bean.getClass().isAnnotationPresent(AutoConfiguration.class)) {
                    return true;
                }
            } catch (NoSuchBeanDefinitionException e) {
                // Ignore
            }
        }
        return false;
    }
}
