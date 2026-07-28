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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api;

/**
 * Operational API for inspecting Change Data Capture (CDC) state, effective configuration,
 * and runtime diagnostics.
 */
public interface CdcApi {

    /**
     * Returns a snapshot of the current CDC operational state and effective configuration.
     *
     * @param principal the principal requesting CDC information
     * @return the current CDC status snapshot
     */
    ApiCdcStatus getStatus(Object principal);
}
