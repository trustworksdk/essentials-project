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
import org.slf4j.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.*;
import org.springframework.boot.context.event.*;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.*;
import org.springframework.context.event.*;
import org.springframework.core.*;

import java.time.Duration;
import java.util.concurrent.*;

/**
 * Gives every Spring application context its own {@link EssentialsStartupReport}, prints it as one block once the
 * application is ready, keeps collecting the findings raised while it runs, and prints the complete report of the run
 * when it stops (0.60.1).
 * <ul>
 *     <li>As the first {@link BeanFactoryPostProcessor} it registers the report as the singleton {@value #REPORT_BEAN_NAME}
 *     and {@link EssentialsStartupReport#bindToCurrentThread() binds} it to the thread refreshing the context, so every
 *     check that runs during the refresh - the other bean factory post-processors, bean creation, the start of the
 *     {@code Lifecycle} beans - adds its findings to this context's report and no other.</li>
 *     <li>The binding ends with the refresh: at {@link ContextRefreshedEvent}, or when the context is destroyed after a
 *     failed refresh. At {@link ContextRefreshedEvent} the report is also
 *     {@link EssentialsStartupReport#registerAsRunReport() registered as the run report}, until the context closes: a
 *     finding raised after the refresh or on another thread - an {@code ApplicationRunner}, an enqueue outside a
 *     {@code UnitOfWork}, a processor started later - reaches it as long as it is the only report registered in the
 *     JVM. In a test JVM running several contexts at once such a finding is only logged by its check.</li>
 *     <li>On {@link ApplicationReadyEvent} it {@link EssentialsStartupReport#log() logs} the report: one block at ERROR
 *     if there is an error, at WARN if there is a warning, otherwise one INFO line saying there are no findings. From
 *     then on, every {@value #NEW_FINDINGS_INTERVAL_PROPERTY} (default {@code 5m}; {@code 0} or negative turns it off)
 *     a daemon thread {@link EssentialsStartupReport#logNewFindings() logs the findings not logged before}, at the
 *     highest severity among them - nothing when there are none.</li>
 *     <li>When the context closes it {@link EssentialsStartupReport#logRunReport() logs the complete report of the run}
 *     once - every finding, with how often and when it was first and last seen - and stops the thread. A shutdown log
 *     can be lost (the process is killed after SIGTERM, the log shipper is already gone), which is why the periodic
 *     log exists.</li>
 *     <li>An application that is stopped after {@link ApplicationStartedEvent} without ever becoming ready - the schema
 *     {@code emit} mode - gets the start-up block when the context closes, and nothing more. A context not started by
 *     {@code SpringApplication} (a test's {@code ApplicationContextRunner}) prints nothing; read the
 *     {@link EssentialsStartupReport} bean instead.</li>
 * </ul>
 */
public final class EssentialsStartupReportPublisher implements BeanFactoryPostProcessor, PriorityOrdered, ApplicationListener<ApplicationEvent>,
                                                                ApplicationContextAware, DisposableBean {
    /**
     * The name of the {@link EssentialsStartupReport} singleton registered in every context
     */
    public static final String   REPORT_BEAN_NAME               = "essentialsStartupReport";
    /**
     * How often the findings raised after start-up that were not logged before are logged, as a {@link Duration}
     * ({@code 5m}, {@code 30s}, {@code PT5M}); {@code 0} or negative turns the periodic log off - the report of the run
     * is still logged when the context closes
     */
    public static final String   NEW_FINDINGS_INTERVAL_PROPERTY = "essentials.startup-report.new-findings-interval";
    /**
     * The default of {@value #NEW_FINDINGS_INTERVAL_PROPERTY}
     */
    public static final Duration DEFAULT_NEW_FINDINGS_INTERVAL  = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(EssentialsStartupReportPublisher.class);

    private final EssentialsStartupReport              report              = new EssentialsStartupReport();
    private       ApplicationContext                   applicationContext;
    private       Duration                             newFindingsInterval = DEFAULT_NEW_FINDINGS_INTERVAL;
    private       EssentialsStartupReport.Binding      binding;
    private       EssentialsStartupReport.Registration registration;
    private       ScheduledExecutorService             newFindingsLogger;
    private       boolean                              started;
    private       boolean                              printed;
    private       boolean                              runReportPrinted;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (!beanFactory.containsSingleton(REPORT_BEAN_NAME)) {
            beanFactory.registerSingleton(REPORT_BEAN_NAME, report);
        }
        if (applicationContext != null) {
            // Bound here, so a malformed value fails the refresh rather than surfacing once the application is ready
            newFindingsInterval = Binder.get(applicationContext.getEnvironment())
                                        .bind(NEW_FINDINGS_INTERVAL_PROPERTY, Duration.class)
                                        .orElse(DEFAULT_NEW_FINDINGS_INTERVAL);
        }
        binding = report.bindToCurrentThread();
    }

    /**
     * @return this context's report
     */
    public EssentialsStartupReport getReport() {
        return report;
    }

    /**
     * @return the interval bound from {@value #NEW_FINDINGS_INTERVAL_PROPERTY}; zero or negative when the periodic log
     * is off
     */
    public Duration getNewFindingsInterval() {
        return newFindingsInterval;
    }

    /**
     * Package-private for tests
     *
     * @return whether the thread logging new findings runs
     */
    synchronized boolean isLoggingNewFindings() {
        return newFindingsLogger != null && !newFindingsLogger.isShutdown();
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
            case ContextRefreshedEvent refreshed when isOurs(refreshed.getApplicationContext()) -> refreshed();
            case ApplicationStartedEvent applicationStarted when isOurs(applicationStarted.getApplicationContext()) -> markStarted();
            case ApplicationReadyEvent ready when isOurs(ready.getApplicationContext()) -> ready();
            case ContextClosedEvent closed when isOurs(closed.getApplicationContext()) -> closed();
            default -> {
            }
        }
    }

    @Override
    public void destroy() {
        unbind();
        stop();
    }

    private boolean isOurs(ApplicationContext context) {
        return applicationContext == null || context == applicationContext;
    }

    private synchronized void refreshed() {
        unbind();
        if (registration == null) {
            registration = report.registerAsRunReport();
        }
    }

    private synchronized void markStarted() {
        started = true;
    }

    private synchronized void ready() {
        print();
        if (newFindingsLogger == null && newFindingsInterval.isPositive()) {
            newFindingsLogger = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                                                                                 .daemon()
                                                                                 .name("essentials-report-new-findings")
                                                                                 .factory());
            var millis = newFindingsInterval.toMillis();
            newFindingsLogger.scheduleWithFixedDelay(this::logNewFindings, millis, millis, TimeUnit.MILLISECONDS);
        }
    }

    private void logNewFindings() {
        try {
            report.logNewFindings();
        } catch (RuntimeException e) {
            // A thrown exception would silently cancel every later run
            log.warn("Could not log the new findings of the Essentials report", e);
        }
    }

    private synchronized void closed() {
        if (printed) {
            if (!runReportPrinted) {
                runReportPrinted = true;
                report.logRunReport();
            }
        } else if (started) {
            print();
        }
        stop();
    }

    private synchronized void unbind() {
        if (binding != null) {
            binding.close();
        }
    }

    private synchronized void stop() {
        if (newFindingsLogger != null) {
            newFindingsLogger.shutdownNow();
        }
        if (registration != null) {
            registration.close();
        }
    }

    private synchronized void print() {
        if (!printed) {
            printed = true;
            report.log();
        }
    }
}
