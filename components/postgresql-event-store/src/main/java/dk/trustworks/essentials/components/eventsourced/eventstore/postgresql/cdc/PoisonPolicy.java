/*
 *  Copyright 2021-2025 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

/**
 * Defines the policy for handling "poison" messages in a system.
 *
 * A "poison" message refers to a message or event that cannot be processed,
 * such as due to malformed data or an unexpected error during processing.
 *
 * The policies available are:
 * - {@code STOP}: Processing stops immediately upon encountering a poison message.
 * - {@code QUARANTINE_AND_CONTINUE}: The poison message is quarantined (set aside),
 *   and processing continues with other messages.
 */
public enum PoisonPolicy {

    STOP, QUARANTINE_AND_CONTINUE
}
