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

/**
 * Handles one event; the subscription saves its resume point periodically (design phase 8 §2.3). Returning normally
 * marks the event handled; throwing retries it, then parks it ({@link ParkEventException} parks at once).
 */
@FunctionalInterface
public non-sealed interface PeriodicHandler extends DeliveryHandler {
    void handle(StoredEvent event) throws Exception;
}
