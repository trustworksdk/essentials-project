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
        if (shouldSkipPostProcessing(bean, beanName)) {
            log.debug("Skipping post-processing of bean '{}'", beanName);
            return bean;
        }

        TTLJob annotation = AnnotationUtils.findAnnotation(bean.getClass(), TTLJob.class);
        if (annotation != null) {
            boolean enabled = annotation.enabled();
            if (!annotation.enabledProperty().isEmpty()) {
                enabled = environment.getProperty(annotation.enabledProperty(), Boolean.class, enabled);
            }
            if (!enabled) {
                log.info("TTL job for bean '{}' is disabled via config.", beanName);
                return bean;
            }

            var tableName = annotation.tableName();
            if (!annotation.tableNameProperty().isEmpty()) {
                tableName = environment.getProperty(annotation.tableNameProperty(), tableName);
            }

            var cronExpr = annotation.cronExpression();

            long ttlDuration;
            if (!annotation.ttlDuration().isEmpty()) {
                ttlDuration = environment.getProperty(annotation.ttlDuration(), Long.class, annotation.defaultTTLDuration());
            } else {
                ttlDuration = annotation.defaultTTLDuration();
            }

            String deleteStatement = bind(annotation.deleteStatementTemplate(),
                                          arg("tableName", tableName),
                                          arg("ttlDuration", String.valueOf(ttlDuration)));

            log.info("Configuring TTL for table '{}' with cron '{}' and ttlDuration '{}'",
                     tableName, cronExpr, ttlDuration);

            DefaultTTLJobAction deleteAction = new DefaultTTLJobAction(tableName, deleteStatement);
            CronScheduleConfiguration scheduleConfig = new CronScheduleConfiguration(new CronExpression(cronExpr), Optional.empty());
            TTLJobDefinition jobDefinition = new TTLJobDefinition(deleteAction, scheduleConfig);

            var timeToLiveManager = beanFactory.getBean(PostgresqlTTLManager.class);

            timeToLiveManager.scheduleTTLJob(jobDefinition);
        }

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
