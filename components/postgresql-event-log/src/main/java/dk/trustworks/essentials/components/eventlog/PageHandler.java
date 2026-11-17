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
package dk.trustworks.essentials.components.eventlog;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Applies a page of events inside a page transaction (design phase 8 §2.3, §2.4). The engine has begun the
 * transaction; after this returns it moves the resume point on the same connection, as the last statement, and commits;
 * if this throws it rolls back. The handler never ends the transaction itself.
 */
@FunctionalInterface
public non-sealed interface PageHandler extends DeliveryHandler {
    /**
     * Apply {@code page}, in {@code global_order} order, through {@code transaction}
     */
    void handle(List<StoredEvent> page, PageTransaction transaction) throws Exception;

    /**
     * @return a row-by-row page handler on top of {@code handler}
     */
    static PageHandler perEvent(EventHandler handler) {
        requireNonNull(handler, "No handler provided");
        return (page, transaction) -> {
            for (var event : page) {
                handler.handle(event, transaction);
            }
        };
    }

    /**
     * Applies one event of a page in the page transaction; see {@link #perEvent(EventHandler)}
     */
    @FunctionalInterface
    interface EventHandler {
        void handle(StoredEvent event, PageTransaction transaction) throws Exception;
    }
}
