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
 * Who delivers a subscription. {@link Exclusive}: at most one event log at a time, the one
 * holding the lease on the progress row {@code (subscriber, aggregateType, '')}. {@link PerInstance}: every
 * instance delivers its own copy, keyed by its non-blank instance name; two event logs started with the same name
 * overwrite each other's progress.
 */
public sealed interface Ownership permits Ownership.Exclusive, Ownership.PerInstance {

    /** @return exclusive ownership */
    static Ownership exclusive() {
        return Exclusive.INSTANCE;
    }

    /** @return per-instance ownership under {@code instanceName} */
    static Ownership perInstance(String instanceName) {
        return new PerInstance(instanceName);
    }

    /** @return the instance name of the progress row: {@code ""} for {@link Exclusive} */
    String instanceName();

    /** Delivered by the lease holder only */
    enum Exclusive implements Ownership {
        INSTANCE;

        @Override
        public String instanceName() {
            return "";
        }
    }

    /** Delivered by every instance, each with its own progress row */
    record PerInstance(String instanceName) implements Ownership {
        public PerInstance {
            if (instanceName == null || instanceName.isBlank()) {
                throw new IllegalArgumentException("A per-instance subscription needs a non-blank instance name");
            }
        }
    }
}
