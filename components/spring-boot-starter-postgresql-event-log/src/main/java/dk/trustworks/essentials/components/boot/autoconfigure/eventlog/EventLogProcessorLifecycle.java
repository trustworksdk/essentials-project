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
package dk.trustworks.essentials.components.boot.autoconfigure.eventlog;

import dk.trustworks.essentials.components.eventlog.EventLog;
import dk.trustworks.essentials.components.eventlog.integration.*;
import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import java.sql.SQLException;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Starts every {@link ViewProjection} and {@link EventReactor} bean in the context, and stops them on shutdown. The
 * processors are not {@code Lifecycle}s themselves (their {@code start()} throws {@link SQLException}), so this adapter
 * gives them one. Their {@link EventLog} is a dependency of every processor bean, so it is started (at creation) before
 * this starts them, and stopped (its {@code destroyMethod}) only after this has stopped them.
 * <p>
 * In schema mode {@code emit} nothing is started: the application only writes the schema script and stops.
 */
public final class EventLogProcessorLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(EventLogProcessorLifecycle.class);

    private final ObjectProvider<ViewProjection> viewProjections;
    private final ObjectProvider<EventReactor>   eventReactors;
    private final boolean                        emitOnly;
    private volatile boolean                     running;

    public EventLogProcessorLifecycle(ObjectProvider<ViewProjection> viewProjections, ObjectProvider<EventReactor> eventReactors, boolean emitOnly) {
        this.viewProjections = requireNonNull(viewProjections, "No viewProjections provided");
        this.eventReactors = requireNonNull(eventReactors, "No eventReactors provided");
        this.emitOnly = emitOnly;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (emitOnly) {
            log.info("Schema mode 'emit': no ViewProjection or EventReactor is started");
            running = true;
            return;
        }
        var projections = viewProjections.orderedStream().toList();
        var reactors    = eventReactors.orderedStream().toList();
        try {
            for (var projection : projections) {
                projection.start();
            }
            for (var reactor : reactors) {
                reactor.start();
            }
        } catch (SQLException e) {
            stopAll();
            throw new IllegalStateException("Failed to start an event log processor", e);
        }
        log.info("Started {} ViewProjection(s) and {} EventReactor(s)", projections.size(), reactors.size());
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        if (!emitOnly) {
            stopAll();
        }
        running = false;
    }

    private void stopAll() {
        eventReactors.orderedStream().forEach(reactor -> stopQuietly(reactor::stop));
        viewProjections.orderedStream().forEach(projection -> stopQuietly(projection::stop));
    }

    private static void stopQuietly(Runnable stop) {
        try {
            stop.run();
        } catch (RuntimeException e) {
            log.warn("Failed to stop an event log processor", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Late in start-up, early in shutdown: after the other lifecycle beans the processors' handlers may use have started,
     * and before they stop.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }
}
