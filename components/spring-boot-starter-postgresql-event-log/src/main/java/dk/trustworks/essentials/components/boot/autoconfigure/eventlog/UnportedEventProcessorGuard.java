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

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.*;

import java.util.*;

/**
 * Fails start-up with one clear message when {@code essentials.event-log.enabled=true} and the context still defines a
 * classic (old-engine) processor bean: an {@code EventProcessor}, {@code ViewEventProcessor} or
 * {@code InTransactionEventProcessor} subclass (ESS-145). The message names every such bean, its class and the new-engine
 * base class to port it to.
 * <p>
 * A {@link BeanFactoryPostProcessor}, because it runs once every bean definition is registered and before any singleton
 * is created; the bare missing-bean error it replaces ({@code EventProcessorDependencies},
 * {@code EventStoreSubscriptionManager}) is raised while the processor bean is being created, so this always comes
 * first. Only bean types are inspected ({@link ConfigurableListableBeanFactory#getType(String, boolean)} without
 * initializing a {@code FactoryBean}); classes are matched by name, so nothing here needs the classic engine on the
 * class path.
 */
public final class UnportedEventProcessorGuard implements BeanFactoryPostProcessor {
    /** Where the porting steps are */
    static final String PORTING_DOC = "LLM-postgresql-event-log.md § Porting from the EventProcessor family";

    /** Each classic base class, by name, and the new-engine base class(es) to port a subclass to */
    static final Map<String, String> PORT_TO;

    static {
        var portTo  = new LinkedHashMap<String, String>();
        var package_ = "dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.";
        portTo.put(package_ + "PartitionedViewEventProcessor", "EventLogPartitionedViewProcessor");
        portTo.put(package_ + "ViewEventProcessor", "EventLogViewProcessor");
        portTo.put(package_ + "InTransactionEventProcessor", "EventLogViewProcessor");
        portTo.put(package_ + "EventProcessor", "EventLogInboxProcessor (with an inbox) or EventLogReactor (side effects only)");
        PORT_TO = Collections.unmodifiableMap(portTo);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        var problems = new ArrayList<String>();
        for (var name : beanFactory.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = beanFactory.getType(name, false);
            } catch (RuntimeException | LinkageError e) {
                continue;
            }
            var problem = problemOf(name, type);
            if (problem != null) {
                problems.add(problem);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(System.lineSeparator(), problems));
        }
    }

    /** @return the message for bean {@code name} of {@code type}, or {@code null} if it is no classic processor */
    static String problemOf(String name, Class<?> type) {
        for (var current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            var portTo = PORT_TO.get(current.getName());
            if (portTo != null) {
                return "Bean '" + name + "' (" + type.getName() + ") is an old-engine " + current.getSimpleName() + ". With essentials.event-log.enabled=true it must be ported to " +
                        portTo + " - see " + PORTING_DOC + ".";
            }
        }
        return null;
    }
}
