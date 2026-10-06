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
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultLifecycleManagerTest {
    private static final String BEAN_NAME = "beanName";

    @Test
    void startLifeCycleBeansTest() {
        var applicationContext = mock(ApplicationContext.class);
        var lifeCycleBean = mock(Lifecycle.class);
        when(lifeCycleBean.isStarted()).thenReturn(false);
        when(applicationContext.getBeansOfType(Lifecycle.class)).thenReturn(Map.of(BEAN_NAME, lifeCycleBean));

        var manager = new DefaultLifecycleManager(true);
        manager.setApplicationContext(applicationContext);

        manager.start();
        verify(lifeCycleBean, times(0)).stop();

        when(lifeCycleBean.isStarted()).thenReturn(true);
        manager.stop();
    }

    @Test
    void lifeCycleBeanAlreadyStartedTest() {
        var applicationContext = mock(ApplicationContext.class);
        var lifecycleBean = mock(Lifecycle.class);
        when(lifecycleBean.isStarted()).thenReturn(true);
        when(applicationContext.getBeansOfType(Lifecycle.class)).thenReturn(Map.of(BEAN_NAME, lifecycleBean));

        var manager = new DefaultLifecycleManager(true);
        manager.setApplicationContext(applicationContext);

        manager.start();
        verify(lifecycleBean, times(0)).start();
    }

    @Test
    void onStartCustomConsumerTest() {
        var applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansOfType(Lifecycle.class)).thenReturn(Map.of());

        Consumer<ApplicationContext> consumer = mock(Consumer.class);
        var manager = new DefaultLifecycleManager(consumer, true);
        manager.setApplicationContext(applicationContext);

        manager.start();
        verify(consumer).accept(applicationContext);
    }

    /**
     * The beans are stopped serially in one pass, so an exception from one used to abandon every bean
     * after it — and the case that reaches it is the one where stopping matters most: a database that
     * has gone away, where several beans try to release leases, locks or replication slots and the
     * first to fail takes the rest of the shutdown with it. The symptom is a process that logs its
     * way through part of a shutdown and then keeps running.
     * <p>
     * A {@link java.util.LinkedHashMap} rather than {@code Map.of}: the order is the whole hazard, and
     * an unordered map would make this pass roughly half the time against the broken implementation.
     */
    @Test
    void a_bean_that_throws_while_stopping_does_not_stop_the_beans_after_it() {
        var applicationContext = mock(ApplicationContext.class);
        var throwingBean       = mock(Lifecycle.class);
        var laterBean          = mock(Lifecycle.class);
        when(throwingBean.isStarted()).thenReturn(true);
        when(laterBean.isStarted()).thenReturn(true);
        doThrow(new IllegalStateException("the database is gone")).when(throwingBean).stop();

        var beans = new LinkedHashMap<String, Lifecycle>();
        beans.put("throwing", throwingBean);
        beans.put("later", laterBean);
        when(applicationContext.getBeansOfType(Lifecycle.class)).thenReturn(beans);

        var manager = new DefaultLifecycleManager(true);
        manager.setApplicationContext(applicationContext);
        manager.start();

        assertThatNoException()
                .describedAs("a failed stop is reported, not propagated — there is nothing above this "
                             + "that could act on it, and propagating abandons the rest")
                .isThrownBy(manager::stop);

        verify(laterBean).stop();
    }

    @Test
    void every_shutdown_aware_bean_is_told_before_the_first_bean_is_stopped() {
        var applicationContext = mock(ApplicationContext.class);
        var firstBean          = mock(Lifecycle.class);
        var shutdownAwareBean  = mock(ShutdownAware.class);
        when(firstBean.isStarted()).thenReturn(true);
        when(applicationContext.getBeansOfType(Lifecycle.class)).thenReturn(Map.of("first", firstBean));
        when(applicationContext.getBeansOfType(ShutdownAware.class, false, false)).thenReturn(Map.of("aware", shutdownAwareBean));

        var manager = new DefaultLifecycleManager(true);
        manager.setApplicationContext(applicationContext);
        manager.start();
        manager.stop();

        var inOrder = inOrder(shutdownAwareBean, firstBean);
        inOrder.verify(shutdownAwareBean).shutdownStarting(any(ShutdownContext.class));
        inOrder.verify(firstBean).stop();
    }

    /**
     * A JVM already running its shutdown hooks ignores a second Ctrl-C, so a stop() that never returns - waiting on a
     * database that is gone - used to leave kill -9 as the only way out.
     */
    @Test
    void a_bean_that_hangs_while_stopping_is_abandoned_at_the_shutdown_timeout_and_the_beans_after_it_are_still_stopped() throws InterruptedException {
        var applicationContext = mock(ApplicationContext.class);
        var hangingBean        = mock(Lifecycle.class);
        var laterBean          = mock(Lifecycle.class);
        var neverReleased      = new CountDownLatch(1);
        when(hangingBean.isStarted()).thenReturn(true);
        when(laterBean.isStarted()).thenReturn(true);
        doAnswer(invocation -> {
            neverReleased.await();
            return null;
        }).when(hangingBean).stop();

        var beans = new LinkedHashMap<String, Lifecycle>();
        beans.put("hanging", hangingBean);
        beans.put("later", laterBean);
        when(applicationContext.getBeansOfType(Lifecycle.class)).thenReturn(beans);

        var manager = new DefaultLifecycleManager(context -> {}, true, Duration.ofMillis(200));
        manager.setApplicationContext(applicationContext);
        manager.start();
        var started = System.nanoTime();
        try {
            manager.stop();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            verify(laterBean).stop();
            assertThat(manager.isRunning()).isFalse();
        } finally {
            neverReleased.countDown();
        }
    }

    @Test
    void dontStartLifeCycleBeansTest() {
        var applicationContext = mock(ApplicationContext.class);
        var manager = new DefaultLifecycleManager(false);
        manager.setApplicationContext(applicationContext);
        manager.start();

        verifyNoInteractions(applicationContext);

        manager.stop();

        verifyNoInteractions(applicationContext);
    }

}
