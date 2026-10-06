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

package dk.trustworks.essentials.components.foundation.lifecycle;

import dk.trustworks.essentials.shared.Lifecycle;
import org.slf4j.*;
import org.springframework.beans.BeansException;
import org.springframework.context.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Default {@link LifecycleManager} that integrate with Spring to ensure that {@link ApplicationContext} Beans
 * that registered Beans implementing the {@link Lifecycle} interface are started and stopped
 */
public final class DefaultLifecycleManager implements SmartLifecycle, LifecycleManager, ApplicationContextAware {
    public static final Logger                       log       = LoggerFactory.getLogger(DefaultLifecycleManager.class);
    private             ApplicationContext           applicationContext;
    private             boolean                      hasStartedLifeCycleBeans;
    private             Map<String, Lifecycle>       lifeCycleBeans;
    private final       Consumer<ApplicationContext> contextRefreshedEventConsumer;
    private final       boolean                      isStartLifecycles;
    private volatile    boolean                      isRunning = false;
    private final       Duration                     shutdownTimeout;

    /**
     * How long {@link #stop()} still waits for a bean once the shutdown timeout has passed, so each remaining bean gets
     * its {@code stop()} called - by then with every {@link ShutdownAware} bean skipping its database cleanup
     */
    static final Duration STOP_GRACE_AFTER_SHUTDOWN_TIMEOUT = Duration.ofMillis(250);

    /**
     * @param contextRefreshedEventConsumer callback that will be called after all {@link Lifecycle} Beans {@link Lifecycle#start()} has been called
     * @param isStartLifecycles             determines if lifecycle beans should be started automatically
     */
    public DefaultLifecycleManager(Consumer<ApplicationContext> contextRefreshedEventConsumer,
                                   boolean isStartLifecycles) {
        this(contextRefreshedEventConsumer, isStartLifecycles, ShutdownContext.DEFAULT_SHUTDOWN_TIMEOUT);
    }

    /**
     * @param contextRefreshedEventConsumer callback that will be called after all {@link Lifecycle} Beans {@link Lifecycle#start()} has been called
     * @param isStartLifecycles             determines if lifecycle beans should be started automatically
     * @param shutdownTimeout               the time budget for stopping every {@link Lifecycle} bean - see {@link #stop()}
     */
    public DefaultLifecycleManager(Consumer<ApplicationContext> contextRefreshedEventConsumer,
                                   boolean isStartLifecycles,
                                   Duration shutdownTimeout) {
        this.contextRefreshedEventConsumer = requireNonNull(contextRefreshedEventConsumer);
        this.isStartLifecycles = isStartLifecycles;
        this.shutdownTimeout = requireNonNull(shutdownTimeout, "No shutdownTimeout provided");
        requireTrue(!shutdownTimeout.isNegative(), "shutdownTimeout must not be negative");
        log.info("Initializing {} with isStartLifecycles = {}", this.getClass().getSimpleName(), isStartLifecycles);
    }

    /**
     * @param isStartLifecycles determines if lifecycle beans should be started automatically
     */
    public DefaultLifecycleManager(boolean isStartLifecycles) {
        this(event -> {
        }, isStartLifecycles);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void stop() {
        if (hasStartedLifeCycleBeans) {
            var shutdown = ShutdownContext.startingNow(shutdownTimeout);
            log.info("Stopping Essentials Lifecycle beans (shutdown timeout {} ms)", shutdownTimeout.toMillis());
            signalShutdownStarting(shutdown);
            lifeCycleBeans.forEach((beanName, lifecycleBean) -> stopWithinShutdownTimeout(beanName, lifecycleBean, shutdown));
            hasStartedLifeCycleBeans = false;
            log.info("Essentials Lifecycle beans have been stopped");
        }
        isRunning = false;
    }

    /**
     * Tell every {@link ShutdownAware} bean before stopping any. The beans are stopped one after the other, so without
     * this a bean not yet stopped cannot tell that the call it serves comes from another bean's shutdown - the case that
     * made a shutdown against an unreachable database take minutes: each event processor released its fenced lock
     * through a lock manager that still believed it was running, and waited out the connection timeout like it would at
     * runtime, queued behind its own background ticks doing the same.
     */
    private void signalShutdownStarting(ShutdownContext shutdown) {
        Map<String, ShutdownAware> shutdownAwareBeans;
        try {
            shutdownAwareBeans = applicationContext.getBeansOfType(ShutdownAware.class, false, false);
        } catch (RuntimeException e) {
            log.warn("Could not look up the {} beans - stopping without signalling shutdown first", ShutdownAware.class.getSimpleName(), e);
            return;
        }
        shutdownAwareBeans.forEach((beanName, bean) -> {
            try {
                bean.shutdownStarting(shutdown);
            } catch (RuntimeException e) {
                log.warn("{} bean '{}' of type '{}' failed to handle shutdownStarting; continuing",
                         ShutdownAware.class.getSimpleName(), beanName, bean.getClass().getName(), e);
            }
        });
    }

    /**
     * Stops one bean on its own thread and waits no longer than what is left of the shutdown timeout - and, once that has
     * passed, {@link #STOP_GRACE_AFTER_SHUTDOWN_TIMEOUT}, so the beans after it still get their {@code stop()} called.
     * A bean that is still stopping by then is abandoned with a WARN: whatever it was handing back (fenced locks, leases)
     * expires on its own. Leaving the application unable to exit is the worse outcome - a JVM already running its shutdown
     * hooks ignores a second Ctrl-C, so a hung stop could only be ended with {@code kill -9}.
     */
    private void stopWithinShutdownTimeout(String beanName, Lifecycle lifecycleBean, ShutdownContext shutdown) {
        try {
            if (!lifecycleBean.isStarted()) {
                return;
            }
        } catch (RuntimeException e) {
            log.error("{} bean '{}' of type '{}' failed to report whether it is started; stopping it anyway",
                      Lifecycle.class.getSimpleName(), beanName, lifecycleBean.getClass().getName(), e);
        }
        log.info("Stopping {} bean '{}' of type '{}'", Lifecycle.class.getSimpleName(), beanName, lifecycleBean.getClass().getName());
        var failure = new AtomicReference<RuntimeException>();
        var stopper = Thread.ofPlatform()
                            .daemon()
                            .name("essentials-lifecycle-stop-" + beanName)
                            .start(() -> {
                                try {
                                    lifecycleBean.stop();
                                } catch (RuntimeException e) {
                                    failure.set(e);
                                }
                            });
        var wait = shutdown.isDeadlinePassed() ? STOP_GRACE_AFTER_SHUTDOWN_TIMEOUT : shutdown.remaining().plus(STOP_GRACE_AFTER_SHUTDOWN_TIMEOUT);
        try {
            if (!stopper.join(wait)) {
                log.warn("{} bean '{}' of type '{}' did not stop within the shutdown timeout ({} ms) - continuing without it. " +
                                 "Anything it was handing back expires on its own; raise essentials.life-cycles.shutdown-timeout " +
                                 "if this happens with a healthy database",
                         Lifecycle.class.getSimpleName(), beanName, lifecycleBean.getClass().getName(), shutdownTimeout.toMillis());
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while stopping {} bean '{}' - continuing without waiting for it", Lifecycle.class.getSimpleName(), beanName);
            return;
        }
        if (failure.get() != null) {
            // One bean's shutdown must not decide whether the others get one.
            //
            // These are stopped serially in one pass, so an exception escaping here used to
            // abandon every bean after this one in the iteration — silently, and in an order
            // nobody chose, since it is the order getBeansOfType happened to return. The case
            // that reaches it is the one where shutting down matters most: a database that has
            // gone away, where several beans release leases, locks or slots and the first to
            // give up takes the rest of the shutdown with it.
            //
            // Logged at ERROR rather than swallowed. A stop that failed is a real finding —
            // something was probably not handed back — but it is a finding about that bean,
            // not a reason to leave the others running.
            //
            // start() is deliberately NOT given the same treatment: a bean that cannot start
            // should fail the context rather than leave the application running as though it
            // had. Stopping is the opposite — it is the last chance anything gets.
            log.error("{} bean '{}' of type '{}' failed to stop; continuing with the rest",
                      Lifecycle.class.getSimpleName(), beanName, lifecycleBean.getClass().getName(), failure.get());
        }
    }

    @Override
    public void start() {
        if (!isStartLifecycles) {
            log.info("Start of lifecycle beans is disabled");
            return;
        }
        if (!hasStartedLifeCycleBeans) {
            log.info("Starting Essentials Lifecycle beans");
            hasStartedLifeCycleBeans = true;
            lifeCycleBeans = applicationContext.getBeansOfType(Lifecycle.class);
            lifeCycleBeans.forEach((beanName, lifecycleBean) -> {
                if (!lifecycleBean.isStarted()) {
                    log.info("Starting {} bean '{}' of type '{}'", Lifecycle.class.getSimpleName(), beanName, lifecycleBean.getClass().getName());
                    lifecycleBean.start();
                }
            });
            log.info("Essentials Lifecycle beans have been started");
            log.info("Calling {} contextRefreshedEventConsumer", this.getClass().getSimpleName());
            contextRefreshedEventConsumer.accept(this.applicationContext);
            log.info("Completed calling {} contextRefreshedEventConsumer", this.getClass().getSimpleName());
        }
        isRunning = true;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public int getPhase() {
        // The higher the phase value the earlier it is shut down and the later it starts
        return Integer.MAX_VALUE - 1;
    }
}
