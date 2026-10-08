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

package dk.trustworks.essentials.components.foundation.boot;

import dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.*;
import org.springframework.boot.context.event.*;
import org.springframework.context.*;
import org.springframework.context.event.*;
import org.springframework.core.*;

/**
 * Gives every Spring application context its own {@link EssentialsStartupReport} and prints it as one block once the
 * application is ready (0.60.1).
 * <ul>
 *     <li>As the first {@link BeanFactoryPostProcessor} it registers the report as the singleton {@value #REPORT_BEAN_NAME}
 *     and {@link EssentialsStartupReport#bindToCurrentThread() binds} it to the thread refreshing the context, so every
 *     check that runs during the refresh - the other bean factory post-processors, bean creation, the start of the
 *     {@code Lifecycle} beans - adds its findings to this context's report and no other.</li>
 *     <li>The binding ends with the refresh: at {@link ContextRefreshedEvent}, or when the context is destroyed after a
 *     failed refresh.</li>
 *     <li>On {@link ApplicationReadyEvent} it {@link EssentialsStartupReport#log() logs} the report: one block at ERROR
 *     if there is an error, at WARN if there is a warning, otherwise one INFO line saying there are no findings. An
 *     application that is stopped after {@link ApplicationStartedEvent} without ever becoming ready - the schema
 *     {@code emit} mode - gets it when the context closes. A context not started by {@code SpringApplication} (a test's
 *     {@code ApplicationContextRunner}) prints nothing; read the {@link EssentialsStartupReport} bean instead.</li>
 * </ul>
 * A finding raised on another thread, or after the refresh (an {@code ApplicationRunner}), is only logged by its check.
 */
public final class EssentialsStartupReportPublisher implements BeanFactoryPostProcessor, PriorityOrdered, ApplicationListener<ApplicationEvent>,
                                                                ApplicationContextAware, DisposableBean {
    /**
     * The name of the {@link EssentialsStartupReport} singleton registered in every context
     */
    public static final String REPORT_BEAN_NAME = "essentialsStartupReport";

    private final EssentialsStartupReport         report = new EssentialsStartupReport();
    private       ApplicationContext              applicationContext;
    private       EssentialsStartupReport.Binding binding;
    private       boolean                         started;
    private       boolean                         printed;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (!beanFactory.containsSingleton(REPORT_BEAN_NAME)) {
            beanFactory.registerSingleton(REPORT_BEAN_NAME, report);
        }
        binding = report.bindToCurrentThread();
    }

    /**
     * @return this context's report
     */
    public EssentialsStartupReport getReport() {
        return report;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (!(event instanceof ApplicationContextEvent || event instanceof SpringApplicationEvent)) {
            return;
        }
        switch (event) {
            case ContextRefreshedEvent refreshed when isOurs(refreshed.getApplicationContext()) -> unbind();
            case ApplicationStartedEvent applicationStarted when isOurs(applicationStarted.getApplicationContext()) -> started = true;
            case ApplicationReadyEvent ready when isOurs(ready.getApplicationContext()) -> print();
            case ContextClosedEvent closed when isOurs(closed.getApplicationContext()) && started -> print();
            default -> {
            }
        }
    }

    @Override
    public void destroy() {
        unbind();
    }

    private boolean isOurs(ApplicationContext context) {
        return applicationContext == null || context == applicationContext;
    }

    private synchronized void unbind() {
        if (binding != null) {
            binding.close();
        }
    }

    private synchronized void print() {
        if (!printed) {
            printed = true;
            report.log();
        }
    }
}
